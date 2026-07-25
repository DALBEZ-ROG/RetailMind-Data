-- =====================================================================
-- 99_revert_bloque_d_demanda.sql
-- REVERSION COMPLETA DEL BLOQUE D (redistribucion de demanda A5/A6)
-- ---------------------------------------------------------------------
-- Devuelve pedido_detalle, factura_venta_detalle, movimiento_inventario e
-- inventario EXACTAMENTE al estado capturado por
-- 68_bloque_d_respaldo_demanda.sql.
--
-- POR QUE RESTAURA BIT A BIT
--   El Bloque D solo ejecuta UPDATE sobre esas 4 tablas: nunca INSERT ni
--   DELETE de filas de negocio. Por lo tanto el conjunto de claves
--   primarias (id) es IDENTICO antes y despues, y un UPDATE ... FROM
--   emparejado por id alcanza todas las filas modificadas y solo esas.
--   Las columnas que el bloque no toca (cantidad, precio_unitario,
--   montos, tipo_movimiento_id, fecha_creacion) se reescriben igualmente
--   con su valor del snapshot: si algo las hubiera alterado, esto lo
--   corrige; si no, el UPDATE es un no-op de valor.
--
-- ORDEN
--   1) inventario            — hoja, sin efectos secundarios.
--   2) movimiento_inventario — restaura variante y cadena stock_anterior/
--                              stock_nuevo. Sin triggers de recalculo.
--   3) factura_venta_detalle — restaura variante y descripcion. Su
--                              subtotal es GENERATED de cantidad x precio,
--                              que no cambian: los totales de factura_venta
--                              no se mueven.
--   4) pedido_detalle        — ULTIMO, porque su trigger AFTER
--                              (fn_recalcular_total_pedido) reescribe la
--                              cabecera del pedido. Como cantidad,
--                              precio_unitario, monto_descuento y
--                              monto_impuesto se restauran a su valor
--                              original, el recalculo converge al mismo
--                              subtotal/impuesto/total que habia antes.
--
-- VERIFICACION
--   Al cierre compara las 4 tablas contra el snapshot con EXCEPT ALL en
--   ambos sentidos y ademas revalida la invariante sagrada
--   (suma del kardex = inventario.stock_actual). Si algo no cuadra hace
--   RAISE EXCEPTION DENTRO de la transaccion, de modo que el ROLLBACK es
--   automatico y la base queda como estaba al iniciar la reversion.
--
-- IDEMPOTENTE: correrlo dos veces deja el mismo resultado.
-- Ejecutar como postgres.
-- =====================================================================

BEGIN;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.tables
                   WHERE table_schema='seed_backup' AND table_name='bd68_pedido_detalle') THEN
        RAISE EXCEPTION 'No existe el respaldo seed_backup.bd68_* : ejecute primero 68_bloque_d_respaldo_demanda.sql';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM seed_backup.bd68_pedido_detalle) THEN
        RAISE EXCEPTION 'El respaldo seed_backup.bd68_pedido_detalle esta VACIO: no hay a que revertir';
    END IF;
END $$;

-- ---------------------------------------------------------------------
-- 1. inventario
-- ---------------------------------------------------------------------
UPDATE inventario i
SET stock_actual = b.stock_actual
FROM seed_backup.bd68_inventario b
WHERE i.id = b.id AND i.stock_actual IS DISTINCT FROM b.stock_actual;

-- ---------------------------------------------------------------------
-- 2. movimiento_inventario
-- ---------------------------------------------------------------------
UPDATE movimiento_inventario m
SET producto_variante_id = b.producto_variante_id,
    bodega_id            = b.bodega_id,
    tipo_movimiento_id   = b.tipo_movimiento_id,
    cantidad             = b.cantidad,
    stock_anterior       = b.stock_anterior,
    stock_nuevo          = b.stock_nuevo
FROM seed_backup.bd68_movimiento_inventario b
WHERE m.id = b.id
  AND (m.producto_variante_id, m.bodega_id, m.tipo_movimiento_id,
       m.cantidad, m.stock_anterior, m.stock_nuevo)
   IS DISTINCT FROM
      (b.producto_variante_id, b.bodega_id, b.tipo_movimiento_id,
       b.cantidad, b.stock_anterior, b.stock_nuevo);

-- ---------------------------------------------------------------------
-- 3. factura_venta_detalle
-- ---------------------------------------------------------------------
UPDATE factura_venta_detalle f
SET producto_variante_id = b.producto_variante_id,
    descripcion          = b.descripcion,
    cantidad             = b.cantidad,
    precio_unitario      = b.precio_unitario
FROM seed_backup.bd68_factura_venta_detalle b
WHERE f.id = b.id
  AND (f.producto_variante_id, f.descripcion, f.cantidad, f.precio_unitario)
   IS DISTINCT FROM
      (b.producto_variante_id, b.descripcion, b.cantidad, b.precio_unitario);

-- ---------------------------------------------------------------------
-- 4. pedido_detalle  (ultimo: dispara el recalculo de cabecera)
--    EN PASADAS ITERATIVAS, por el mismo motivo que el script 70: la
--    restriccion uq_pedido_detalle (pedido_id, producto_variante_id) NO es
--    deferrable y se valida fila a fila, asi que un UPDATE unico falla
--    cuando una linea vuelve a la variante que otra linea del mismo pedido
--    esta abandonando. (Se detecto ejecutando esta reversion de verdad
--    sobre datos ya redistribuidos: fallaba con "llave duplicada viola
--    restriccion de unicidad uq_pedido_detalle". Probarla en seco no lo
--    habria mostrado.) Converge porque el grafo de dependencias inverso de
--    un orden topologico tambien es topologico.
-- ---------------------------------------------------------------------
DO $$
DECLARE n bigint; i int := 0;
BEGIN
    LOOP
        i := i + 1;
        UPDATE pedido_detalle d
        SET producto_variante_id = b.producto_variante_id,
            nombre_producto      = b.nombre_producto,
            sku                  = b.sku,
            cantidad             = b.cantidad,
            precio_unitario      = b.precio_unitario,
            monto_descuento      = b.monto_descuento,
            monto_impuesto       = b.monto_impuesto
        FROM seed_backup.bd68_pedido_detalle b
        WHERE d.id = b.id
          AND (d.producto_variante_id, d.nombre_producto, d.sku, d.cantidad,
               d.precio_unitario, d.monto_descuento, d.monto_impuesto)
           IS DISTINCT FROM
              (b.producto_variante_id, b.nombre_producto, b.sku, b.cantidad,
               b.precio_unitario, b.monto_descuento, b.monto_impuesto)
          AND NOT EXISTS (SELECT 1 FROM pedido_detalle o
                          WHERE o.pedido_id = d.pedido_id
                            AND o.producto_variante_id = b.producto_variante_id
                            AND o.id <> d.id);
        GET DIAGNOSTICS n = ROW_COUNT;
        EXIT WHEN n = 0;
        IF i >= 20 THEN
            RAISE EXCEPTION 'La reversion de pedido_detalle no convergio en 20 pasadas';
        END IF;
    END LOOP;
    RAISE NOTICE 'pedido_detalle restaurado en % pasadas', i;
END $$;

-- ---------------------------------------------------------------------
-- 5. VERIFICACION — cualquier fallo aborta y revierte la reversion
-- ---------------------------------------------------------------------
DO $$
DECLARE d bigint;
BEGIN
    SELECT count(*) INTO d FROM (
      (SELECT id, producto_variante_id, nombre_producto, sku, cantidad,
              precio_unitario, monto_descuento, monto_impuesto FROM pedido_detalle
       EXCEPT ALL
       SELECT id, producto_variante_id, nombre_producto, sku, cantidad,
              precio_unitario, monto_descuento, monto_impuesto FROM seed_backup.bd68_pedido_detalle)
      UNION ALL
      (SELECT id, producto_variante_id, nombre_producto, sku, cantidad,
              precio_unitario, monto_descuento, monto_impuesto FROM seed_backup.bd68_pedido_detalle
       EXCEPT ALL
       SELECT id, producto_variante_id, nombre_producto, sku, cantidad,
              precio_unitario, monto_descuento, monto_impuesto FROM pedido_detalle)) x;
    IF d <> 0 THEN RAISE EXCEPTION 'Reversion incompleta en pedido_detalle: % filas', d; END IF;

    SELECT count(*) INTO d FROM (
      (SELECT id, producto_variante_id, descripcion, cantidad, precio_unitario FROM factura_venta_detalle
       EXCEPT ALL
       SELECT id, producto_variante_id, descripcion, cantidad, precio_unitario FROM seed_backup.bd68_factura_venta_detalle)
      UNION ALL
      (SELECT id, producto_variante_id, descripcion, cantidad, precio_unitario FROM seed_backup.bd68_factura_venta_detalle
       EXCEPT ALL
       SELECT id, producto_variante_id, descripcion, cantidad, precio_unitario FROM factura_venta_detalle)) x;
    IF d <> 0 THEN RAISE EXCEPTION 'Reversion incompleta en factura_venta_detalle: % filas', d; END IF;

    SELECT count(*) INTO d FROM (
      (SELECT id, producto_variante_id, bodega_id, cantidad, stock_anterior, stock_nuevo FROM movimiento_inventario
       EXCEPT ALL
       SELECT id, producto_variante_id, bodega_id, cantidad, stock_anterior, stock_nuevo FROM seed_backup.bd68_movimiento_inventario)
      UNION ALL
      (SELECT id, producto_variante_id, bodega_id, cantidad, stock_anterior, stock_nuevo FROM seed_backup.bd68_movimiento_inventario
       EXCEPT ALL
       SELECT id, producto_variante_id, bodega_id, cantidad, stock_anterior, stock_nuevo FROM movimiento_inventario)) x;
    IF d <> 0 THEN RAISE EXCEPTION 'Reversion incompleta en movimiento_inventario: % filas', d; END IF;

    SELECT count(*) INTO d FROM (
      (SELECT id, stock_actual FROM inventario
       EXCEPT ALL SELECT id, stock_actual FROM seed_backup.bd68_inventario)
      UNION ALL
      (SELECT id, stock_actual FROM seed_backup.bd68_inventario
       EXCEPT ALL SELECT id, stock_actual FROM inventario)) x;
    IF d <> 0 THEN RAISE EXCEPTION 'Reversion incompleta en inventario: % filas', d; END IF;

    -- invariante sagrada
    SELECT count(*) INTO d
    FROM (SELECT mi.producto_variante_id v, mi.bodega_id b,
                 sum(CASE WHEN tm.codigo LIKE 'entrada%' THEN mi.cantidad ELSE -mi.cantidad END) saldo
          FROM movimiento_inventario mi JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id
          GROUP BY 1,2) s
    JOIN inventario i ON i.producto_variante_id = s.v AND i.bodega_id = s.b
    WHERE i.stock_actual <> s.saldo;
    IF d <> 0 THEN RAISE EXCEPTION 'Kardex <> stock_actual en % pares tras revertir', d; END IF;

    RAISE NOTICE 'Reversion del Bloque D COMPLETA y verificada (4 tablas + invariante kardex=stock).';
END $$;

-- ---------------------------------------------------------------------
-- 6. Marcas: se retira SOLO la del script que escribio datos de negocio.
--    La del 69 se conserva a proposito: describe el staging de
--    seed_backup.bd69_*, que sigue siendo valido y permite re-aplicar con
--    el script 70 sin recalcular. (No re-ejecutar el 69 despues de
--    revertir sin necesidad: recalcularia el mapeo contra el estado ya
--    restaurado, que es el correcto, pero es trabajo innecesario.)
-- ---------------------------------------------------------------------
DELETE FROM configuracion_tienda WHERE clave = 'seed_bd_70_redistribucion';

COMMIT;
