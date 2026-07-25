-- =====================================================================
-- 70_bloque_d_redistribucion.sql
-- BLOQUE D — FASE 3: APLICACION Y RECUADRE  (fecha: 2026-07-24)
-- ---------------------------------------------------------------------
-- Aplica el mapeo linea -> variante que resolvio el script 69 y recuadra
-- el kardex. Requiere haber corrido 68 (respaldo) y 69 (mapeo).
-- Reversion completa: 99_revert_bloque_d_demanda.sql.
--
-- QUE ESCRIBE  (4 tablas, y ninguna otra)
--   1. pedido_detalle        : producto_variante_id, nombre_producto, sku
--   2. factura_venta_detalle : producto_variante_id, descripcion
--   3. movimiento_inventario : producto_variante_id, stock_anterior, stock_nuevo
--   4. inventario            : stock_actual
--   NUNCA: cantidad, precio_unitario, monto_descuento, monto_impuesto,
--   subtotal (GENERATED), ni una sola columna de dinero. Por eso el
--   impacto monetario es exactamente $0,00.
--
-- POR QUE factura_venta_detalle TAMBIEN
--   9.876 de las 10.384 lineas de venta (95,2 %) estan espejadas en
--   factura_venta_detalle, que guarda SU PROPIO producto_variante_id y
--   descripcion. Reasignar la linea del pedido sin reasignar la de la
--   factura dejaria la factura apuntando a un producto distinto del que
--   figura en el pedido: una incoherencia dura NUEVA, peor que el A5 que
--   se esta corrigiendo. Se escriben SOLO esas dos columnas de identidad;
--   cantidad y precio_unitario quedan intactos y su subtotal es GENERATED,
--   de modo que factura_venta, pago y transaccion_pago no se mueven.
--
-- ---------------------------------------------------------------------
-- ETAPA 1 — pedido_detalle, EN PASADAS ITERATIVAS (no en un solo UPDATE)
-- ---------------------------------------------------------------------
--   uq_pedido_detalle UNIQUE (pedido_id, producto_variante_id) NO es
--   deferrable, asi que se valida fila a fila. Hay 8 pedidos en los que
--   una linea se muda a la variante que OTRA linea del mismo pedido esta
--   abandonando: si el motor tocara la receptora antes que la que libera,
--   el UNIQUE saltaria aunque el estado final sea valido.
--   Se resuelve moviendo en cada pasada solo las lineas cuyo destino esta
--   libre AHORA, y repitiendo. Converge porque el grafo de dependencias es
--   ACICLICO por construccion: el goloso del script 69 jamas eligio una
--   variante que estuviera ocupada en ese pedido, de modo que una linea
--   solo puede recibir una variante que otra libero ANTES que ella. El
--   bucle aborta si no converge en 20 pasadas.
--
--   El trigger AFTER fn_recalcular_total_pedido se dispara por fila y
--   reagrega la cabecera desde el detalle. Se deja actuar A PROPOSITO: es
--   el camino del sistema y a la vez la PRUEBA de que los totales no
--   cambian, porque reagrega exactamente las columnas que no se tocaron.
--
-- ---------------------------------------------------------------------
-- ETAPA 3 — RECUADRE DEL KARDEX
-- ---------------------------------------------------------------------
--   La cadena stock_anterior/stock_nuevo se reconstruye por (variante,
--   bodega) en orden CRONOLOGICO (fecha_creacion, id), que es el mismo
--   orden en que el script 69 simulo los saldos, de modo que el resultado
--   coincide con la simulacion que ya garantizo no-negatividad.
--
--   EFECTO DOCUMENTADO: la cadena que habia se habia encadenado en orden
--   de INSERCION (id), no de fecha. Se midio: 5.860 de 12.396 movimientos
--   tenian stock_anterior incoherente con su propia cronologia (los ids de
--   venta del Bloque B son todos mayores que los de compra del Bloque A,
--   aunque una venta de 2025-02 preceda a una compra de 2026-05). Este
--   script deja las 12.396 filas encadenadas cronologicamente, que es la
--   unica forma de que la verificacion "0 negativos recorriendo la linea
--   de tiempo" sea literalmente cierta. Se comprobo de antemano que el
--   reordenamiento cronologico NO produce ningun negativo (0 casos), asi
--   que corregirlo no arriesga nada.
--   La INVARIANTE SAGRADA se preserva porque la suma algebraica de los
--   movimientos de una variante no depende del orden en que se encadenen.
--
-- Transaccional. Idempotente (reejecutar converge al mismo estado).
-- Ejecutar como postgres.
-- =====================================================================

BEGIN;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.tables
                   WHERE table_schema='seed_backup' AND table_name='bd69_mapeo') THEN
        RAISE EXCEPTION 'Falta seed_backup.bd69_mapeo: ejecute 69_bloque_d_curva_objetivo.sql';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM seed_backup.bd68_pedido_detalle) THEN
        RAISE EXCEPTION 'Falta el respaldo seed_backup.bd68_*: ejecute 68_bloque_d_respaldo_demanda.sql';
    END IF;
END $$;

-- ---------------------------------------------------------------------
-- ETAPA 1 — pedido_detalle
-- ---------------------------------------------------------------------
DO $$
DECLARE n bigint; i int := 0; total bigint := 0;
BEGIN
    LOOP
        i := i + 1;
        UPDATE pedido_detalle pd
        SET producto_variante_id = m.variante_nueva,
            nombre_producto      = pr.nombre,
            sku                  = pv.sku
        FROM seed_backup.bd69_mapeo m
        JOIN producto_variante pv ON pv.id = m.variante_nueva
        JOIN producto pr          ON pr.id = pv.producto_id
        WHERE pd.id = m.pedido_detalle_id
          AND m.cambio
          AND pd.producto_variante_id <> m.variante_nueva
          AND NOT EXISTS (SELECT 1 FROM pedido_detalle o
                          WHERE o.pedido_id = pd.pedido_id
                            AND o.producto_variante_id = m.variante_nueva
                            AND o.id <> pd.id);
        GET DIAGNOSTICS n = ROW_COUNT;
        total := total + n;
        EXIT WHEN n = 0;
        IF i >= 20 THEN
            RAISE EXCEPTION 'pedido_detalle no convergio en 20 pasadas (quedan lineas pendientes)';
        END IF;
    END LOOP;

    SELECT count(*) INTO n FROM seed_backup.bd69_mapeo m
    JOIN pedido_detalle pd ON pd.id = m.pedido_detalle_id
    WHERE pd.producto_variante_id <> m.variante_nueva;
    IF n <> 0 THEN RAISE EXCEPTION '% lineas quedaron sin aplicar el mapeo', n; END IF;

    RAISE NOTICE 'Etapa 1: % lineas de pedido_detalle reasignadas en % pasadas', total, i;
END $$;

-- ---------------------------------------------------------------------
-- ETAPA 2 — factura_venta_detalle (identidad; sin tocar dinero)
-- ---------------------------------------------------------------------
UPDATE factura_venta_detalle f
SET producto_variante_id = pd.producto_variante_id,
    descripcion          = pr.nombre
FROM pedido_detalle pd
JOIN producto_variante pv ON pv.id = pd.producto_variante_id
JOIN producto pr          ON pr.id = pv.producto_id
WHERE f.pedido_detalle_id = pd.id
  AND f.producto_variante_id IS DISTINCT FROM pd.producto_variante_id;

-- ---------------------------------------------------------------------
-- ETAPA 3a — movimiento_inventario: variante de la salida de venta
-- ---------------------------------------------------------------------
UPDATE movimiento_inventario mi
SET producto_variante_id = m.variante_nueva
FROM seed_backup.bd69_mapeo m
WHERE mi.id = m.mov_id
  AND m.mov_id IS NOT NULL
  AND m.cambio
  AND mi.producto_variante_id <> m.variante_nueva;

-- referencia_id sigue apuntando al pedido correcto; no cambia.

-- ---------------------------------------------------------------------
-- ETAPA 3b — recuadre CRONOLOGICO de la cadena de stock
-- ---------------------------------------------------------------------
WITH cad AS (
    SELECT mi.id,
           CASE WHEN tm.codigo LIKE 'entrada%' THEN mi.cantidad ELSE -mi.cantidad END AS d,
           coalesce(sum(CASE WHEN tm.codigo LIKE 'entrada%' THEN mi.cantidad ELSE -mi.cantidad END)
                    OVER (PARTITION BY mi.producto_variante_id, mi.bodega_id
                          ORDER BY mi.fecha_creacion, mi.id
                          ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0) AS prev
    FROM movimiento_inventario mi
    JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id
)
UPDATE movimiento_inventario m
SET stock_anterior = cad.prev,
    stock_nuevo    = cad.prev + cad.d
FROM cad
WHERE m.id = cad.id
  AND (m.stock_anterior, m.stock_nuevo) IS DISTINCT FROM (cad.prev, cad.prev + cad.d);

-- ---------------------------------------------------------------------
-- ETAPA 3c — inventario.stock_actual = suma algebraica del kardex
--            (fecha_actualizacion la pone el trigger touch: no se escribe)
-- ---------------------------------------------------------------------
UPDATE inventario i
SET stock_actual = s.saldo
FROM (SELECT mi.producto_variante_id v, mi.bodega_id b,
             sum(CASE WHEN tm.codigo LIKE 'entrada%' THEN mi.cantidad ELSE -mi.cantidad END)::int saldo
      FROM movimiento_inventario mi JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id
      GROUP BY 1,2) s
WHERE i.producto_variante_id = s.v AND i.bodega_id = s.b
  AND i.stock_actual <> s.saldo;

-- ---------------------------------------------------------------------
-- 4. VERIFICACION DE INTEGRIDAD — cualquier fallo revierte TODO
-- ---------------------------------------------------------------------
DO $$
DECLARE d bigint; a numeric; b numeric;
BEGIN
    -- (a) INVARIANTE SAGRADA: kardex = stock_actual, TODAS las variantes
    SELECT count(*) INTO d
    FROM (SELECT mi.producto_variante_id v, mi.bodega_id bo,
                 sum(CASE WHEN tm.codigo LIKE 'entrada%' THEN mi.cantidad ELSE -mi.cantidad END) saldo
          FROM movimiento_inventario mi JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id
          GROUP BY 1,2) s
    FULL JOIN inventario i ON i.producto_variante_id = s.v AND i.bodega_id = s.bo
    WHERE coalesce(i.stock_actual,0) <> coalesce(s.saldo,0);
    IF d <> 0 THEN RAISE EXCEPTION '(a) kardex <> stock_actual en % pares', d; END IF;

    -- (b) 0 negativos recorriendo la linea de tiempo
    SELECT count(*) INTO d FROM movimiento_inventario
    WHERE stock_anterior < 0 OR stock_nuevo < 0;
    IF d <> 0 THEN RAISE EXCEPTION '(b) % movimientos con stock negativo', d; END IF;

    SELECT count(*) INTO d FROM (
        SELECT mi.id, mi.stock_anterior sa, mi.stock_nuevo sn,
               CASE WHEN tm.codigo LIKE 'entrada%' THEN mi.cantidad ELSE -mi.cantidad END dd,
               coalesce(sum(CASE WHEN tm.codigo LIKE 'entrada%' THEN mi.cantidad ELSE -mi.cantidad END)
                        OVER (PARTITION BY mi.producto_variante_id, mi.bodega_id
                              ORDER BY mi.fecha_creacion, mi.id
                              ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0) pv
        FROM movimiento_inventario mi JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id) x
    WHERE sa <> pv OR sn <> pv + dd;
    IF d <> 0 THEN RAISE EXCEPTION '(b) cadena cronologica rota en % movimientos', d; END IF;

    SELECT count(*) INTO d FROM inventario WHERE stock_actual < 0;
    IF d <> 0 THEN RAISE EXCEPTION '(b) % filas de inventario negativas', d; END IF;

    -- (c) unidades y filas de venta intactas
    SELECT valor INTO a FROM seed_backup.bd68_agregados WHERE momento='antes' AND clave='linea_unidades';
    SELECT sum(cantidad) INTO b FROM pedido_detalle;
    IF a <> b THEN RAISE EXCEPTION '(c) unidades vendidas cambiaron: % -> %', a, b; END IF;

    SELECT valor INTO a FROM seed_backup.bd68_agregados WHERE momento='antes' AND clave='linea_filas';
    SELECT count(*) INTO b FROM pedido_detalle;
    IF a <> b THEN RAISE EXCEPTION '(c) numero de lineas cambio: % -> %', a, b; END IF;

    -- (d) los agregados monetarios y fisicos de referencia, al centavo.
    --     inventario_stock, kardex_movs y kardex_unidades tambien deben
    --     conservarse: la suma total de entradas menos salidas no depende
    --     de a que variante se imputa cada salida.
    SELECT count(*) INTO d FROM (
        SELECT ag.clave, ag.valor antes, x.valor ahora
        FROM seed_backup.bd68_agregados ag
        JOIN (
            SELECT 'pedido_total' clave, coalesce(sum(total),0) valor FROM pedido
            UNION ALL SELECT 'pedido_subtotal',  coalesce(sum(subtotal),0)        FROM pedido
            UNION ALL SELECT 'pedido_impuesto',  coalesce(sum(monto_impuesto),0)  FROM pedido
            UNION ALL SELECT 'pedido_descuento', coalesce(sum(monto_descuento),0) FROM pedido
            UNION ALL SELECT 'pedido_envio',     coalesce(sum(costo_envio),0)     FROM pedido
            UNION ALL SELECT 'linea_subtotal',   coalesce(sum(subtotal),0)        FROM pedido_detalle
            UNION ALL SELECT 'linea_descuento',  coalesce(sum(monto_descuento),0) FROM pedido_detalle
            UNION ALL SELECT 'linea_impuesto',   coalesce(sum(monto_impuesto),0)  FROM pedido_detalle
            UNION ALL SELECT 'factura_venta_total', coalesce(sum(total),0)        FROM factura_venta
            UNION ALL SELECT 'factura_linea_subtotal', coalesce(sum(subtotal),0)  FROM factura_venta_detalle
            UNION ALL SELECT 'pago_total',       coalesce(sum(monto),0)           FROM pago
            UNION ALL SELECT 'kardex_movs',      count(*)                         FROM movimiento_inventario
            UNION ALL SELECT 'kardex_unidades',  coalesce(sum(cantidad),0)        FROM movimiento_inventario
            UNION ALL SELECT 'inventario_stock', coalesce(sum(stock_actual),0)    FROM inventario
        ) x ON x.clave = ag.clave
        WHERE ag.momento = 'antes' AND ag.valor <> x.valor) y;
    IF d <> 0 THEN
        RAISE EXCEPTION '(d) % agregados de referencia cambiaron (deberian ser 0)', d;
    END IF;

    -- (g) coherencia cruzada pedido_detalle <-> factura_venta_detalle
    SELECT count(*) INTO d FROM factura_venta_detalle f
    JOIN pedido_detalle pd ON pd.id = f.pedido_detalle_id
    WHERE f.producto_variante_id <> pd.producto_variante_id;
    IF d <> 0 THEN RAISE EXCEPTION '(g) % lineas de factura desalineadas del pedido', d; END IF;

    -- (g) denormalizacion coherente
    SELECT count(*) INTO d FROM pedido_detalle pd
    JOIN producto_variante pv ON pv.id = pd.producto_variante_id
    JOIN producto pr ON pr.id = pv.producto_id
    WHERE pd.sku <> pv.sku OR pd.nombre_producto <> pr.nombre;
    IF d <> 0 THEN RAISE EXCEPTION '(g) % lineas con sku/nombre incoherente', d; END IF;

    -- (g) el ratio precio_unitario/precio sigue en la banda historica
    SELECT count(*) INTO d FROM pedido_detalle pd
    JOIN producto_variante pv ON pv.id = pd.producto_variante_id
    WHERE pd.precio_unitario / pv.precio NOT BETWEEN 0.90 AND 1.0000001;
    IF d <> 0 THEN RAISE EXCEPTION '(g) % lineas con ratio de precio fuera de [0,90;1,00]', d; END IF;

    -- resenas: ninguna queda sin compra que la respalde
    SELECT count(*) INTO d FROM resena r
    WHERE NOT EXISTS (SELECT 1 FROM pedido p
                      JOIN pedido_detalle pd ON pd.pedido_id = p.id
                      JOIN producto_variante pv ON pv.id = pd.producto_variante_id
                      WHERE p.cliente_id = r.cliente_id AND pv.producto_id = r.producto_id);
    IF d <> 0 THEN RAISE EXCEPTION '% resenas quedaron sin compra verificada', d; END IF;

    RAISE NOTICE 'Integridad OK: kardex=stock, 0 negativos cronologicos, unidades y los 14 agregados intactos, factura alineada, resenas respaldadas.';
END $$;

-- ---------------------------------------------------------------------
-- 5. AGREGADOS 'despues' (para el contraste del informe) y MARCA
-- ---------------------------------------------------------------------
DELETE FROM seed_backup.bd68_agregados WHERE momento = 'despues';
INSERT INTO seed_backup.bd68_agregados (momento, clave, valor)
SELECT 'despues', k, v FROM (
    SELECT 'pedido_total'              k, coalesce(sum(total),0)              v FROM pedido
    UNION ALL SELECT 'pedido_subtotal',      coalesce(sum(subtotal),0)             FROM pedido
    UNION ALL SELECT 'pedido_impuesto',      coalesce(sum(monto_impuesto),0)       FROM pedido
    UNION ALL SELECT 'pedido_descuento',     coalesce(sum(monto_descuento),0)      FROM pedido
    UNION ALL SELECT 'pedido_envio',         coalesce(sum(costo_envio),0)          FROM pedido
    UNION ALL SELECT 'linea_subtotal',       coalesce(sum(subtotal),0)             FROM pedido_detalle
    UNION ALL SELECT 'linea_descuento',      coalesce(sum(monto_descuento),0)      FROM pedido_detalle
    UNION ALL SELECT 'linea_impuesto',       coalesce(sum(monto_impuesto),0)       FROM pedido_detalle
    UNION ALL SELECT 'linea_unidades',       coalesce(sum(cantidad),0)             FROM pedido_detalle
    UNION ALL SELECT 'linea_filas',          count(*)                              FROM pedido_detalle
    UNION ALL SELECT 'factura_venta_total',  coalesce(sum(total),0)                FROM factura_venta
    UNION ALL SELECT 'factura_linea_subtotal', coalesce(sum(subtotal),0)           FROM factura_venta_detalle
    UNION ALL SELECT 'pago_total',           coalesce(sum(monto),0)                FROM pago
    UNION ALL SELECT 'kardex_movs',          count(*)                              FROM movimiento_inventario
    UNION ALL SELECT 'kardex_unidades',      coalesce(sum(cantidad),0)             FROM movimiento_inventario
    UNION ALL SELECT 'inventario_stock',     coalesce(sum(stock_actual),0)         FROM inventario
    UNION ALL SELECT 'variantes_con_venta',  count(DISTINCT producto_variante_id)  FROM pedido_detalle
) t;

INSERT INTO configuracion_tienda (clave, valor, tipo_dato, descripcion)
VALUES ('seed_bd_70_redistribucion',
        (SELECT jsonb_build_object(
            'fecha', now(),
            'lineas_reasignadas', (SELECT count(*) FROM seed_backup.bd69_mapeo WHERE cambio),
            'uds_reasignadas',    (SELECT coalesce(sum(cantidad),0) FROM seed_backup.bd69_mapeo WHERE cambio),
            'variantes_con_venta',(SELECT count(DISTINCT producto_variante_id) FROM pedido_detalle))::text),
        'json',
        'Script 70 (2026-07-24) Bloque D Fase 3: aplica la redistribucion de demanda '
        || '(A5/A6) y recuadra el kardex cronologicamente. Escribe SOLO columnas de '
        || 'identidad en pedido_detalle y factura_venta_detalle y la cadena de stock en '
        || 'movimiento_inventario/inventario. Impacto monetario 0,00. '
        || 'Reversion: 99_revert_bloque_d_demanda.sql.')
ON CONFLICT (clave) DO UPDATE SET valor = EXCLUDED.valor, descripcion = EXCLUDED.descripcion;

COMMIT;
