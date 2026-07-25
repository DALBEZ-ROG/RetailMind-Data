-- ============================================================================
-- 99_revert_abastecimiento.sql
-- REVERSION COMPLETA del REBALANCEO DEL ABASTECIMIENTO (scripts 75-78).
-- Devuelve public al estado BIT-IDENTICO respaldado por 74_respaldo_abastecimiento.sql
-- y lo COMPRUEBA comparando las 16 huellas md5. Si alguna no coincide, aborta
-- (la transaccion completa hace ROLLBACK).
--
-- Estrategia:
--   1. Las tablas del ciclo de compra solo recibieron ALTAS -> DELETE id > umbral.
--   2. movimiento_inventario recibio altas, bajas y modificaciones -> se
--      reconstruye fila a fila desde seed_backup.reb74_movimiento_inventario
--      (id es GENERATED ALWAYS: se reinsertan con OVERRIDING SYSTEM VALUE).
--   3. inventario no se escribe en el rebalanceo; se restaura igualmente por
--      seguridad.
--   4. Se devuelven las secuencias de identidad y seq_numero_documento a su
--      valor previo para que una re-aplicacion reproduzca los mismos ids.
--
-- Ejecutar como postgres (superusuario) sobre la BD retailmind.
-- ============================================================================
\set ON_ERROR_STOP on

BEGIN;

DO $$
DECLARE
    v_faltan text;
BEGIN
    SELECT string_agg(t, ', ') INTO v_faltan FROM (
        SELECT 'reb74_movimiento_inventario' t WHERE to_regclass('seed_backup.reb74_movimiento_inventario') IS NULL
        UNION ALL SELECT 'reb74_inventario' WHERE to_regclass('seed_backup.reb74_inventario') IS NULL
        UNION ALL SELECT 'reb74_umbral'     WHERE to_regclass('seed_backup.reb74_umbral') IS NULL
        UNION ALL SELECT 'reb74_huella'     WHERE to_regclass('seed_backup.reb74_huella') IS NULL
    ) x;
    IF v_faltan IS NOT NULL THEN
        RAISE EXCEPTION 'No hay respaldo para revertir. Falta: %', v_faltan;
    END IF;
END $$;

-- ── 1. Bajas de las tablas que solo recibieron altas (orden FK-seguro) ──────

DELETE FROM pago_proveedor          WHERE id > (SELECT max_id FROM seed_backup.reb74_umbral WHERE tabla='pago_proveedor');
DELETE FROM cuenta_por_pagar        WHERE id > (SELECT max_id FROM seed_backup.reb74_umbral WHERE tabla='cuenta_por_pagar');
DELETE FROM factura_compra_detalle  WHERE id > (SELECT max_id FROM seed_backup.reb74_umbral WHERE tabla='factura_compra_detalle');
DELETE FROM factura_compra          WHERE id > (SELECT max_id FROM seed_backup.reb74_umbral WHERE tabla='factura_compra');
DELETE FROM movimiento_inventario   WHERE id > (SELECT max_id FROM seed_backup.reb74_umbral WHERE tabla='movimiento_inventario');
DELETE FROM recepcion_detalle       WHERE id > (SELECT max_id FROM seed_backup.reb74_umbral WHERE tabla='recepcion_detalle');
DELETE FROM recepcion_mercancia     WHERE id > (SELECT max_id FROM seed_backup.reb74_umbral WHERE tabla='recepcion_mercancia');
DELETE FROM orden_compra_detalle    WHERE id > (SELECT max_id FROM seed_backup.reb74_umbral WHERE tabla='orden_compra_detalle');
DELETE FROM orden_compra            WHERE id > (SELECT max_id FROM seed_backup.reb74_umbral WHERE tabla='orden_compra');
DELETE FROM producto_proveedor      WHERE id > (SELECT max_id FROM seed_backup.reb74_umbral WHERE tabla='producto_proveedor');
DELETE FROM log_auditoria           WHERE id > (SELECT max_id FROM seed_backup.reb74_umbral WHERE tabla='log_auditoria');

-- ── 2. Reconstruccion exacta del kardex ────────────────────────────────────

-- 2a. modificaciones (stock_anterior/nuevo, cantidad, fecha_creacion, ...)
UPDATE movimiento_inventario m
   SET producto_variante_id = b.producto_variante_id,
       bodega_id            = b.bodega_id,
       tipo_movimiento_id   = b.tipo_movimiento_id,
       lote_id              = b.lote_id,
       usuario_id           = b.usuario_id,
       cantidad             = b.cantidad,
       stock_anterior       = b.stock_anterior,
       stock_nuevo          = b.stock_nuevo,
       costo_unitario       = b.costo_unitario,
       referencia_tipo      = b.referencia_tipo,
       referencia_id        = b.referencia_id,
       observacion          = b.observacion,
       fecha_creacion       = b.fecha_creacion
FROM seed_backup.reb74_movimiento_inventario b
WHERE b.id = m.id
  AND (m.producto_variante_id, m.bodega_id, m.tipo_movimiento_id, m.cantidad,
       m.stock_anterior, m.stock_nuevo, m.fecha_creacion)
   IS DISTINCT FROM
      (b.producto_variante_id, b.bodega_id, b.tipo_movimiento_id, b.cantidad,
       b.stock_anterior, b.stock_nuevo, b.fecha_creacion);

-- 2b. filas borradas por el rebalanceo (apertura migrada) -> se reinsertan
INSERT INTO movimiento_inventario
    (id, producto_variante_id, bodega_id, tipo_movimiento_id, lote_id, usuario_id,
     cantidad, stock_anterior, stock_nuevo, costo_unitario, referencia_tipo,
     referencia_id, observacion, fecha_creacion)
OVERRIDING SYSTEM VALUE
SELECT b.id, b.producto_variante_id, b.bodega_id, b.tipo_movimiento_id, b.lote_id,
       b.usuario_id, b.cantidad, b.stock_anterior, b.stock_nuevo, b.costo_unitario,
       b.referencia_tipo, b.referencia_id, b.observacion, b.fecha_creacion
FROM seed_backup.reb74_movimiento_inventario b
WHERE NOT EXISTS (SELECT 1 FROM movimiento_inventario m WHERE m.id = b.id);

-- ── 3. inventario (testigo: el rebalanceo no lo escribe) ───────────────────

UPDATE inventario i
   SET stock_actual = b.stock_actual,
       stock_reservado = b.stock_reservado,
       stock_minimo = b.stock_minimo,
       stock_maximo = b.stock_maximo,
       fecha_actualizacion = b.fecha_actualizacion
FROM seed_backup.reb74_inventario b
WHERE b.id = i.id
  AND (i.stock_actual, i.stock_reservado, i.stock_minimo, i.stock_maximo,
       i.fecha_actualizacion)
   IS DISTINCT FROM
      (b.stock_actual, b.stock_reservado, b.stock_minimo, b.stock_maximo,
       b.fecha_actualizacion);

-- ── 4. Marcas de idempotencia del rebalanceo ───────────────────────────────

DELETE FROM configuracion_tienda WHERE clave IN
    ('seed_reb75_plan','seed_reb76_ordenes','seed_reb77_facturas','seed_reb78_kardex',
     'seed_rebalanceo_abastecimiento');

-- ── 5. Secuencias al valor previo (para reproducir los mismos ids) ─────────

SELECT setval('public.seq_numero_documento',
              (SELECT max_id FROM seed_backup.reb74_umbral WHERE tabla='seq_numero_documento'),
              true);

DO $$
DECLARE r record; s text;
BEGIN
    FOR r IN SELECT tabla, max_id FROM seed_backup.reb74_umbral
             WHERE tabla IN ('orden_compra','orden_compra_detalle','recepcion_mercancia',
                             'recepcion_detalle','factura_compra','factura_compra_detalle',
                             'cuenta_por_pagar','pago_proveedor','producto_proveedor',
                             'log_auditoria','movimiento_inventario')
    LOOP
        s := pg_get_serial_sequence('public.' || r.tabla, 'id');
        IF s IS NOT NULL THEN PERFORM setval(s, greatest(r.max_id,1), true); END IF;
    END LOOP;
END $$;

-- ── 6. Comprobacion: huellas md5 identicas al respaldo ─────────────────────

DO $$
DECLARE
    v_now  text[];
    v_diff text;
BEGIN
    CREATE TEMP TABLE _h_now (tabla text PRIMARY KEY, filas bigint, huella text) ON COMMIT DROP;

    INSERT INTO _h_now SELECT 'movimiento_inventario', count(*), md5(string_agg(
         id || '|' || producto_variante_id || '|' || bodega_id || '|' || tipo_movimiento_id
            || '|' || COALESCE(lote_id::text,'~') || '|' || COALESCE(usuario_id::text,'~')
            || '|' || cantidad || '|' || stock_anterior || '|' || stock_nuevo
            || '|' || COALESCE(costo_unitario::text,'~') || '|' || COALESCE(referencia_tipo,'~')
            || '|' || COALESCE(referencia_id::text,'~') || '|' || COALESCE(observacion,'~')
            || '|' || fecha_creacion::text, E'\n' ORDER BY id)) FROM movimiento_inventario;

    INSERT INTO _h_now SELECT 'inventario', count(*), md5(string_agg(
         id || '|' || producto_variante_id || '|' || bodega_id || '|' || stock_actual
            || '|' || stock_reservado || '|' || stock_minimo
            || '|' || COALESCE(stock_maximo::text,'~')
            || '|' || COALESCE(fecha_actualizacion::text,'~'), E'\n' ORDER BY id)) FROM inventario;

    INSERT INTO _h_now SELECT 'orden_compra', count(*), md5(string_agg(
         id || '|' || numero || '|' || proveedor_id || '|' || estado || '|' || fecha_emision
            || '|' || subtotal || '|' || monto_impuesto || '|' || total, E'\n' ORDER BY id)) FROM orden_compra;

    INSERT INTO _h_now SELECT 'orden_compra_detalle', count(*), md5(string_agg(
         id || '|' || orden_compra_id || '|' || producto_variante_id || '|' || cantidad
            || '|' || precio_unitario || '|' || monto_impuesto || '|' || cantidad_recibida,
         E'\n' ORDER BY id)) FROM orden_compra_detalle;

    INSERT INTO _h_now SELECT 'recepcion_mercancia', count(*), md5(string_agg(
         id || '|' || numero || '|' || orden_compra_id || '|' || estado
            || '|' || fecha_recepcion::text, E'\n' ORDER BY id)) FROM recepcion_mercancia;

    INSERT INTO _h_now SELECT 'recepcion_detalle', count(*), md5(string_agg(
         id || '|' || recepcion_mercancia_id || '|' || orden_compra_detalle_id
            || '|' || cantidad_recibida || '|' || cantidad_rechazada, E'\n' ORDER BY id)) FROM recepcion_detalle;

    INSERT INTO _h_now SELECT 'factura_compra', count(*), md5(string_agg(
         id || '|' || proveedor_id || '|' || COALESCE(orden_compra_id::text,'~')
            || '|' || numero_factura || '|' || fecha_emision || '|' || estado
            || '|' || subtotal || '|' || monto_impuesto || '|' || total, E'\n' ORDER BY id)) FROM factura_compra;

    INSERT INTO _h_now SELECT 'factura_compra_detalle', count(*), md5(string_agg(
         id || '|' || factura_compra_id || '|' || producto_variante_id || '|' || cantidad
            || '|' || precio_unitario || '|' || monto_impuesto, E'\n' ORDER BY id)) FROM factura_compra_detalle;

    INSERT INTO _h_now SELECT 'cuenta_por_pagar', count(*), md5(string_agg(
         id || '|' || factura_compra_id || '|' || monto_original || '|' || saldo_pendiente
            || '|' || estado || '|' || fecha_vencimiento, E'\n' ORDER BY id)) FROM cuenta_por_pagar;

    INSERT INTO _h_now SELECT 'pago_proveedor', count(*), md5(string_agg(
         id || '|' || cuenta_por_pagar_id || '|' || monto || '|' || fecha_pago
            || '|' || COALESCE(referencia,'~'), E'\n' ORDER BY id)) FROM pago_proveedor;

    INSERT INTO _h_now SELECT 'producto_proveedor', count(*), md5(string_agg(
         id || '|' || proveedor_id || '|' || producto_variante_id || '|' || costo
            || '|' || es_preferido || '|' || activo, E'\n' ORDER BY id)) FROM producto_proveedor;

    INSERT INTO _h_now SELECT 'log_auditoria', count(*), md5(string_agg(
         id || '|' || COALESCE(usuario_id::text,'~') || '|' || tabla || '|' || registro_id
            || '|' || accion, E'\n' ORDER BY id)) FROM log_auditoria;

    INSERT INTO _h_now SELECT 'pedido', count(*), md5(string_agg(id || '|' || subtotal
            || '|' || monto_descuento || '|' || monto_impuesto || '|' || costo_envio
            || '|' || total || '|' || estado_pedido_id, E'\n' ORDER BY id)) FROM pedido;

    INSERT INTO _h_now SELECT 'pedido_detalle', count(*), md5(string_agg(id || '|' || producto_variante_id
            || '|' || cantidad || '|' || precio_unitario || '|' || monto_descuento
            || '|' || monto_impuesto, E'\n' ORDER BY id)) FROM pedido_detalle;

    INSERT INTO _h_now SELECT 'factura_venta', count(*), md5(string_agg(id || '|' || estado
            || '|' || subtotal || '|' || monto_descuento || '|' || monto_impuesto
            || '|' || total, E'\n' ORDER BY id)) FROM factura_venta;

    INSERT INTO _h_now SELECT 'pago', count(*), md5(string_agg(id || '|' || pedido_id
            || '|' || monto || '|' || estado, E'\n' ORDER BY id)) FROM pago;

    SELECT array_agg(b.tabla || ' (respaldo ' || b.filas || '/' || COALESCE(b.huella,'~')
                     || ' vs actual ' || COALESCE(n.filas::text,'-') || '/' || COALESCE(n.huella,'~') || ')')
      INTO v_now
    FROM seed_backup.reb74_huella b
    LEFT JOIN _h_now n ON n.tabla = b.tabla
    WHERE n.huella IS DISTINCT FROM b.huella OR n.filas IS DISTINCT FROM b.filas;

    IF v_now IS NOT NULL THEN
        v_diff := array_to_string(v_now, E'\n  ');
        RAISE EXCEPTION E'REVERSION INCOMPLETA. Difieren:\n  %', v_diff;
    END IF;

    RAISE NOTICE 'Reversion OK: las 16 huellas md5 coinciden con el respaldo 74 (estado bit-identico).';
END $$;

COMMIT;
