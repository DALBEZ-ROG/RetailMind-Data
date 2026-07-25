-- ============================================================================
-- 74_respaldo_abastecimiento.sql
-- RESPALDO RESTAURABLE previo al REBALANCEO DEL ABASTECIMIENTO (A1/M1/M2/B2 de
-- docs/tactico/AUDITORIA_TRANSVERSAL_SEED.md), scripts 75-78.
--
-- QUE SE VA A TOCAR (y por tanto que se respalda):
--   * Ciclo de compra: orden_compra, orden_compra_detalle, recepcion_mercancia,
--     recepcion_detalle, factura_compra, factura_compra_detalle,
--     cuenta_por_pagar, pago_proveedor, producto_proveedor, log_auditoria.
--     -> el rebalanceo solo AGREGA filas: la reversion borra id > umbral.
--   * Kardex: movimiento_inventario. Aqui si se MODIFICAN y BORRAN filas
--     (apertura 'inventario_inicial' y recomputo de stock_anterior/nuevo de las
--     cadenas afectadas) -> se respaldan las FILAS COMPLETAS para restaurarlas
--     bit-identicas (id es GENERATED ALWAYS: la reversion usa
--     OVERRIDING SYSTEM VALUE).
--   * inventario: NO se escribe (el stock final no cambia). Se respalda como
--     TESTIGO para probar la invariante (a).
--   * VENTAS: no se tocan. Se respaldan huellas md5 como testigo de (g).
--
-- Todo va al esquema seed_backup (NO public), prefijo reb74_.
-- Solo lectura sobre public: este script NO modifica ni una fila de negocio.
-- Ejecutar como postgres (superusuario) sobre la BD retailmind.
-- ============================================================================
\set ON_ERROR_STOP on

BEGIN;

CREATE SCHEMA IF NOT EXISTS seed_backup;

-- ── 1. Filas completas de las tablas que se MODIFICAN o BORRAN ──────────────

DROP TABLE IF EXISTS seed_backup.reb74_movimiento_inventario;
CREATE TABLE seed_backup.reb74_movimiento_inventario AS
SELECT id, producto_variante_id, bodega_id, tipo_movimiento_id, lote_id, usuario_id,
       cantidad, stock_anterior, stock_nuevo, costo_unitario, referencia_tipo,
       referencia_id, observacion, fecha_creacion
FROM movimiento_inventario;
ALTER TABLE seed_backup.reb74_movimiento_inventario ADD PRIMARY KEY (id);

DROP TABLE IF EXISTS seed_backup.reb74_inventario;
CREATE TABLE seed_backup.reb74_inventario AS
SELECT id, producto_variante_id, bodega_id, stock_actual, stock_reservado,
       stock_minimo, stock_maximo, fecha_actualizacion
FROM inventario;
ALTER TABLE seed_backup.reb74_inventario ADD PRIMARY KEY (id);

-- ── 2. Umbrales de id (las tablas del ciclo de compra solo reciben ALTAS) ───

DROP TABLE IF EXISTS seed_backup.reb74_umbral;
CREATE TABLE seed_backup.reb74_umbral (tabla text PRIMARY KEY, max_id bigint NOT NULL,
                                       filas bigint NOT NULL);
INSERT INTO seed_backup.reb74_umbral
SELECT 'orden_compra',           COALESCE(max(id),0), count(*) FROM orden_compra
UNION ALL SELECT 'orden_compra_detalle',   COALESCE(max(id),0), count(*) FROM orden_compra_detalle
UNION ALL SELECT 'recepcion_mercancia',    COALESCE(max(id),0), count(*) FROM recepcion_mercancia
UNION ALL SELECT 'recepcion_detalle',      COALESCE(max(id),0), count(*) FROM recepcion_detalle
UNION ALL SELECT 'factura_compra',         COALESCE(max(id),0), count(*) FROM factura_compra
UNION ALL SELECT 'factura_compra_detalle', COALESCE(max(id),0), count(*) FROM factura_compra_detalle
UNION ALL SELECT 'cuenta_por_pagar',       COALESCE(max(id),0), count(*) FROM cuenta_por_pagar
UNION ALL SELECT 'pago_proveedor',         COALESCE(max(id),0), count(*) FROM pago_proveedor
UNION ALL SELECT 'producto_proveedor',     COALESCE(max(id),0), count(*) FROM producto_proveedor
UNION ALL SELECT 'log_auditoria',          COALESCE(max(id),0), count(*) FROM log_auditoria
UNION ALL SELECT 'movimiento_inventario',  COALESCE(max(id),0), count(*) FROM movimiento_inventario
UNION ALL SELECT 'seq_numero_documento',   (SELECT last_value FROM seq_numero_documento), 0;

-- ── 3. Agregados de referencia (foto ANTES) ─────────────────────────────────

DROP TABLE IF EXISTS seed_backup.reb74_agregados;
CREATE TABLE seed_backup.reb74_agregados AS
-- cuadre contable de compras
SELECT 'cxp_factura_compra_total' AS metrica, COALESCE(sum(total),0) AS valor FROM factura_compra
UNION ALL SELECT 'cxp_pago_proveedor_total',  COALESCE(sum(monto),0)           FROM pago_proveedor
UNION ALL SELECT 'cxp_saldo_pendiente',       COALESCE(sum(saldo_pendiente),0) FROM cuenta_por_pagar
UNION ALL SELECT 'cxp_monto_original',        COALESCE(sum(monto_original),0)  FROM cuenta_por_pagar
UNION ALL SELECT 'oc_total',                  COALESCE(sum(total),0)           FROM orden_compra
UNION ALL SELECT 'oc_filas',                  count(*)::numeric                FROM orden_compra
-- abastecimiento en unidades
UNION ALL SELECT 'uds_apertura',   COALESCE(sum(cantidad),0)::numeric FROM movimiento_inventario WHERE referencia_tipo = 'inventario_inicial'
UNION ALL SELECT 'movs_apertura',  count(*)::numeric                  FROM movimiento_inventario WHERE referencia_tipo = 'inventario_inicial'
UNION ALL SELECT 'uds_entrada_compra', COALESCE(sum(cantidad),0)::numeric FROM movimiento_inventario WHERE tipo_movimiento_id = 1
UNION ALL SELECT 'uds_entradas_todas', COALESCE(sum(mi.cantidad),0)::numeric
     FROM movimiento_inventario mi JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id WHERE tm.factor = 1
UNION ALL SELECT 'uds_salidas_todas',  COALESCE(sum(mi.cantidad),0)::numeric
     FROM movimiento_inventario mi JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id WHERE tm.factor = -1
UNION ALL SELECT 'uds_salida_venta',   COALESCE(sum(cantidad),0)::numeric FROM movimiento_inventario WHERE tipo_movimiento_id = 5
UNION ALL SELECT 'movs_totales',       count(*)::numeric                  FROM movimiento_inventario
UNION ALL SELECT 'inventario_stock_actual', COALESCE(sum(stock_actual),0)::numeric FROM inventario
-- testigo de VENTAS (no se toca nada de esto)
UNION ALL SELECT 'venta_pedido_total',   COALESCE(sum(total),0) FROM pedido
UNION ALL SELECT 'venta_factura_total',  COALESCE(sum(total),0) FROM factura_venta WHERE estado <> 'anulada'
UNION ALL SELECT 'venta_pago_completado',COALESCE(sum(monto),0) FROM pago WHERE estado = 'completado'
UNION ALL SELECT 'venta_pedido_filas',   count(*)::numeric      FROM pedido
UNION ALL SELECT 'venta_pedido_detalle_uds', COALESCE(sum(cantidad),0)::numeric FROM pedido_detalle
-- variantes vendidas sin ninguna compra (hallazgo A1)
UNION ALL SELECT 'variantes_vendidas_sin_compra', (
     SELECT count(*) FROM (SELECT DISTINCT producto_variante_id v FROM pedido_detalle) s
     WHERE NOT EXISTS (SELECT 1 FROM orden_compra_detalle d
                       WHERE d.producto_variante_id = s.v AND d.cantidad_recibida > 0))::numeric;

-- ── 4. Huellas md5 (prueba de reversion bit-identica) ───────────────────────

DROP TABLE IF EXISTS seed_backup.reb74_huella;
CREATE TABLE seed_backup.reb74_huella (tabla text PRIMARY KEY, filas bigint NOT NULL, huella text);

INSERT INTO seed_backup.reb74_huella
SELECT 'movimiento_inventario', count(*), md5(string_agg(
         id || '|' || producto_variante_id || '|' || bodega_id || '|' || tipo_movimiento_id
            || '|' || COALESCE(lote_id::text,'~') || '|' || COALESCE(usuario_id::text,'~')
            || '|' || cantidad || '|' || stock_anterior || '|' || stock_nuevo
            || '|' || COALESCE(costo_unitario::text,'~') || '|' || COALESCE(referencia_tipo,'~')
            || '|' || COALESCE(referencia_id::text,'~') || '|' || COALESCE(observacion,'~')
            || '|' || fecha_creacion::text, E'\n' ORDER BY id))
FROM movimiento_inventario;

INSERT INTO seed_backup.reb74_huella
SELECT 'inventario', count(*), md5(string_agg(
         id || '|' || producto_variante_id || '|' || bodega_id || '|' || stock_actual
            || '|' || stock_reservado || '|' || stock_minimo
            || '|' || COALESCE(stock_maximo::text,'~')
            || '|' || COALESCE(fecha_actualizacion::text,'~'), E'\n' ORDER BY id))
FROM inventario;

INSERT INTO seed_backup.reb74_huella
SELECT 'orden_compra', count(*), md5(string_agg(
         id || '|' || numero || '|' || proveedor_id || '|' || estado || '|' || fecha_emision
            || '|' || subtotal || '|' || monto_impuesto || '|' || total, E'\n' ORDER BY id))
FROM orden_compra;

INSERT INTO seed_backup.reb74_huella
SELECT 'orden_compra_detalle', count(*), md5(string_agg(
         id || '|' || orden_compra_id || '|' || producto_variante_id || '|' || cantidad
            || '|' || precio_unitario || '|' || monto_impuesto || '|' || cantidad_recibida,
         E'\n' ORDER BY id))
FROM orden_compra_detalle;

INSERT INTO seed_backup.reb74_huella
SELECT 'recepcion_mercancia', count(*), md5(string_agg(
         id || '|' || numero || '|' || orden_compra_id || '|' || estado
            || '|' || fecha_recepcion::text, E'\n' ORDER BY id))
FROM recepcion_mercancia;

INSERT INTO seed_backup.reb74_huella
SELECT 'recepcion_detalle', count(*), md5(string_agg(
         id || '|' || recepcion_mercancia_id || '|' || orden_compra_detalle_id
            || '|' || cantidad_recibida || '|' || cantidad_rechazada, E'\n' ORDER BY id))
FROM recepcion_detalle;

INSERT INTO seed_backup.reb74_huella
SELECT 'factura_compra', count(*), md5(string_agg(
         id || '|' || proveedor_id || '|' || COALESCE(orden_compra_id::text,'~')
            || '|' || numero_factura || '|' || fecha_emision || '|' || estado
            || '|' || subtotal || '|' || monto_impuesto || '|' || total, E'\n' ORDER BY id))
FROM factura_compra;

INSERT INTO seed_backup.reb74_huella
SELECT 'factura_compra_detalle', count(*), md5(string_agg(
         id || '|' || factura_compra_id || '|' || producto_variante_id || '|' || cantidad
            || '|' || precio_unitario || '|' || monto_impuesto, E'\n' ORDER BY id))
FROM factura_compra_detalle;

INSERT INTO seed_backup.reb74_huella
SELECT 'cuenta_por_pagar', count(*), md5(string_agg(
         id || '|' || factura_compra_id || '|' || monto_original || '|' || saldo_pendiente
            || '|' || estado || '|' || fecha_vencimiento, E'\n' ORDER BY id))
FROM cuenta_por_pagar;

INSERT INTO seed_backup.reb74_huella
SELECT 'pago_proveedor', count(*), md5(string_agg(
         id || '|' || cuenta_por_pagar_id || '|' || monto || '|' || fecha_pago
            || '|' || COALESCE(referencia,'~'), E'\n' ORDER BY id))
FROM pago_proveedor;

INSERT INTO seed_backup.reb74_huella
SELECT 'producto_proveedor', count(*), md5(string_agg(
         id || '|' || proveedor_id || '|' || producto_variante_id || '|' || costo
            || '|' || es_preferido || '|' || activo, E'\n' ORDER BY id))
FROM producto_proveedor;

INSERT INTO seed_backup.reb74_huella
SELECT 'log_auditoria', count(*), md5(string_agg(
         id || '|' || COALESCE(usuario_id::text,'~') || '|' || tabla || '|' || registro_id
            || '|' || accion, E'\n' ORDER BY id))
FROM log_auditoria;

-- testigos de VENTA (invariante g: no se tocan)
INSERT INTO seed_backup.reb74_huella
SELECT 'pedido', count(*), md5(string_agg(id || '|' || subtotal || '|' || monto_descuento
            || '|' || monto_impuesto || '|' || costo_envio || '|' || total
            || '|' || estado_pedido_id, E'\n' ORDER BY id))
FROM pedido;

INSERT INTO seed_backup.reb74_huella
SELECT 'pedido_detalle', count(*), md5(string_agg(id || '|' || producto_variante_id
            || '|' || cantidad || '|' || precio_unitario || '|' || monto_descuento
            || '|' || monto_impuesto, E'\n' ORDER BY id))
FROM pedido_detalle;

INSERT INTO seed_backup.reb74_huella
SELECT 'factura_venta', count(*), md5(string_agg(id || '|' || estado || '|' || subtotal
            || '|' || monto_descuento || '|' || monto_impuesto || '|' || total,
         E'\n' ORDER BY id))
FROM factura_venta;

INSERT INTO seed_backup.reb74_huella
SELECT 'pago', count(*), md5(string_agg(id || '|' || pedido_id || '|' || monto || '|' || estado,
         E'\n' ORDER BY id))
FROM pago;

-- ── 5. Comprobacion del respaldo ────────────────────────────────────────────

DO $$
DECLARE v text;
BEGIN
    IF (SELECT count(*) FROM seed_backup.reb74_movimiento_inventario)
       <> (SELECT count(*) FROM movimiento_inventario) THEN
        RAISE EXCEPTION 'RESPALDO INCOMPLETO: movimiento_inventario';
    END IF;
    IF (SELECT count(*) FROM seed_backup.reb74_inventario) <> (SELECT count(*) FROM inventario) THEN
        RAISE EXCEPTION 'RESPALDO INCOMPLETO: inventario';
    END IF;
    IF (SELECT count(*) FROM seed_backup.reb74_huella) <> 16 THEN
        RAISE EXCEPTION 'Faltan huellas md5 (esperadas 16, hay %)',
              (SELECT count(*) FROM seed_backup.reb74_huella);
    END IF;
    IF (SELECT count(*) FROM seed_backup.reb74_umbral) <> 12 THEN
        RAISE EXCEPTION 'Faltan umbrales (esperados 12, hay %)',
              (SELECT count(*) FROM seed_backup.reb74_umbral);
    END IF;
    SELECT string_agg(tabla, ', ') INTO v FROM seed_backup.reb74_huella WHERE huella IS NULL;
    IF v IS NOT NULL THEN RAISE EXCEPTION 'Huella nula en: %', v; END IF;
    RAISE NOTICE 'Respaldo 74 OK: kardex+inventario completos, 16 huellas, 12 umbrales.';
END $$;

COMMIT;

\echo '--- Huellas del estado PREVIO (74) ---'
SELECT tabla, filas, huella FROM seed_backup.reb74_huella ORDER BY tabla;
\echo '--- Umbrales de id ---'
SELECT tabla, max_id, filas FROM seed_backup.reb74_umbral ORDER BY tabla;
\echo '--- Agregados de referencia ANTES ---'
SELECT metrica, round(valor,2) AS valor FROM seed_backup.reb74_agregados ORDER BY metrica;
