-- ============================================================================
-- 71_respaldo_descuentos.sql
-- RESPALDO RESTAURABLE previo a la aplicación de descuentos de cupón (A8) y
-- promoción (A9) sobre el dinero de los pedidos (scripts 72 y 73).
--
-- Respalda FILAS COMPLETAS (columnas monetarias + fecha_actualizacion) de las
-- 6 tablas que las fases 2 y 3 escriben, en el esquema seed_backup (NO public),
-- más una huella md5 por tabla que permite probar que la reversión
-- (99_revert_descuentos.sql) devuelve el estado BIT-IDÉNTICO.
--
-- Solo lectura sobre public: este script NO modifica ni una fila de negocio.
-- Ejecutar como postgres (superusuario) sobre la BD retailmind.
-- ============================================================================
\set ON_ERROR_STOP on

BEGIN;

CREATE SCHEMA IF NOT EXISTS seed_backup;

-- ── 1. Respaldo de filas ────────────────────────────────────────────────────

DROP TABLE IF EXISTS seed_backup.dsc71_pedido;
CREATE TABLE seed_backup.dsc71_pedido AS
SELECT id, subtotal, monto_descuento, monto_impuesto, costo_envio, total,
       fecha_actualizacion
FROM pedido;
ALTER TABLE seed_backup.dsc71_pedido ADD PRIMARY KEY (id);

DROP TABLE IF EXISTS seed_backup.dsc71_pedido_detalle;
CREATE TABLE seed_backup.dsc71_pedido_detalle AS
SELECT id, pedido_id, producto_variante_id, cantidad, precio_unitario,
       subtotal, monto_descuento, monto_impuesto
FROM pedido_detalle;
ALTER TABLE seed_backup.dsc71_pedido_detalle ADD PRIMARY KEY (id);

DROP TABLE IF EXISTS seed_backup.dsc71_pago;
CREATE TABLE seed_backup.dsc71_pago AS
SELECT id, pedido_id, monto, estado, fecha_actualizacion
FROM pago;
ALTER TABLE seed_backup.dsc71_pago ADD PRIMARY KEY (id);

DROP TABLE IF EXISTS seed_backup.dsc71_factura_venta;
CREATE TABLE seed_backup.dsc71_factura_venta AS
SELECT id, pedido_id, estado, subtotal, monto_descuento, monto_impuesto, total,
       fecha_actualizacion
FROM factura_venta;
ALTER TABLE seed_backup.dsc71_factura_venta ADD PRIMARY KEY (id);

DROP TABLE IF EXISTS seed_backup.dsc71_factura_venta_detalle;
CREATE TABLE seed_backup.dsc71_factura_venta_detalle AS
SELECT id, factura_venta_id, pedido_detalle_id, cantidad, precio_unitario,
       subtotal, monto_descuento, monto_impuesto
FROM factura_venta_detalle;
ALTER TABLE seed_backup.dsc71_factura_venta_detalle ADD PRIMARY KEY (id);

DROP TABLE IF EXISTS seed_backup.dsc71_uso_cupon;
CREATE TABLE seed_backup.dsc71_uso_cupon AS
SELECT id, cupon_id, pedido_id, cliente_id, monto_descontado
FROM uso_cupon;
ALTER TABLE seed_backup.dsc71_uso_cupon ADD PRIMARY KEY (id);

-- Testigos de lo que NO se debe tocar (kardex / inventario / cupón)
DROP TABLE IF EXISTS seed_backup.dsc71_testigo_intocable;
CREATE TABLE seed_backup.dsc71_testigo_intocable AS
SELECT 'movimiento_inventario_filas' AS metrica, count(*)::numeric AS valor FROM movimiento_inventario
UNION ALL SELECT 'movimiento_inventario_unidades', COALESCE(sum(cantidad),0) FROM movimiento_inventario
UNION ALL SELECT 'inventario_filas',               count(*)::numeric FROM inventario
UNION ALL SELECT 'inventario_stock_actual',        COALESCE(sum(stock_actual),0) FROM inventario
UNION ALL SELECT 'inventario_stock_reservado',     COALESCE(sum(stock_reservado),0) FROM inventario
UNION ALL SELECT 'pago_fallido_filas',             count(*)::numeric FROM pago WHERE estado = 'fallido'
UNION ALL SELECT 'pago_fallido_monto',             COALESCE(sum(monto),0) FROM pago WHERE estado = 'fallido'
UNION ALL SELECT 'cupon_usos_actuales',            COALESCE(sum(usos_actuales),0) FROM cupon
UNION ALL SELECT 'uso_cupon_filas',                count(*)::numeric FROM uso_cupon
UNION ALL SELECT 'pedido_filas',                   count(*)::numeric FROM pedido
UNION ALL SELECT 'pedido_detalle_filas',           count(*)::numeric FROM pedido_detalle
UNION ALL SELECT 'factura_venta_filas',            count(*)::numeric FROM factura_venta
UNION ALL SELECT 'factura_venta_detalle_filas',    count(*)::numeric FROM factura_venta_detalle;

-- ── 2. Agregados monetarios de referencia (foto ANTES) ──────────────────────

DROP TABLE IF EXISTS seed_backup.dsc71_agregados;
CREATE TABLE seed_backup.dsc71_agregados AS
SELECT 'pedido_total'                 AS metrica, COALESCE(sum(total),0)           AS valor FROM pedido
UNION ALL SELECT 'pedido_subtotal',            COALESCE(sum(subtotal),0)        FROM pedido
UNION ALL SELECT 'pedido_monto_descuento',     COALESCE(sum(monto_descuento),0) FROM pedido
UNION ALL SELECT 'pedido_monto_impuesto',      COALESCE(sum(monto_impuesto),0)  FROM pedido
UNION ALL SELECT 'pedido_costo_envio',         COALESCE(sum(costo_envio),0)     FROM pedido
UNION ALL SELECT 'pedido_detalle_descuento',   COALESCE(sum(monto_descuento),0) FROM pedido_detalle
UNION ALL SELECT 'pedido_detalle_impuesto',    COALESCE(sum(monto_impuesto),0)  FROM pedido_detalle
UNION ALL SELECT 'pedido_detalle_subtotal',    COALESCE(sum(subtotal),0)        FROM pedido_detalle
UNION ALL SELECT 'factura_total_vigente',      COALESCE(sum(total),0)           FROM factura_venta WHERE estado <> 'anulada'
UNION ALL SELECT 'factura_descuento_vigente',  COALESCE(sum(monto_descuento),0) FROM factura_venta WHERE estado <> 'anulada'
UNION ALL SELECT 'factura_detalle_descuento',  COALESCE(sum(monto_descuento),0) FROM factura_venta_detalle
UNION ALL SELECT 'pago_completado',            COALESCE(sum(monto),0)           FROM pago WHERE estado = 'completado'
UNION ALL SELECT 'uso_cupon_descontado',       COALESCE(sum(monto_descontado),0) FROM uso_cupon;

-- ── 3. Huella md5 por tabla (para probar la reversión bit-idéntica) ─────────

DROP TABLE IF EXISTS seed_backup.dsc71_huella;
CREATE TABLE seed_backup.dsc71_huella (tabla text PRIMARY KEY, huella text NOT NULL);

INSERT INTO seed_backup.dsc71_huella
SELECT 'pedido', md5(string_agg(
         id || '|' || subtotal || '|' || monto_descuento || '|' || monto_impuesto
            || '|' || costo_envio || '|' || total
            || '|' || COALESCE(fecha_actualizacion::text,'~'), E'\n' ORDER BY id))
FROM pedido;

INSERT INTO seed_backup.dsc71_huella
SELECT 'pedido_detalle', md5(string_agg(
         id || '|' || monto_descuento || '|' || monto_impuesto, E'\n' ORDER BY id))
FROM pedido_detalle;

INSERT INTO seed_backup.dsc71_huella
SELECT 'pago', md5(string_agg(
         id || '|' || monto || '|' || estado
            || '|' || COALESCE(fecha_actualizacion::text,'~'), E'\n' ORDER BY id))
FROM pago;

INSERT INTO seed_backup.dsc71_huella
SELECT 'factura_venta', md5(string_agg(
         id || '|' || subtotal || '|' || monto_descuento || '|' || monto_impuesto
            || '|' || total || '|' || COALESCE(fecha_actualizacion::text,'~'), E'\n' ORDER BY id))
FROM factura_venta;

INSERT INTO seed_backup.dsc71_huella
SELECT 'factura_venta_detalle', md5(string_agg(
         id || '|' || monto_descuento || '|' || monto_impuesto, E'\n' ORDER BY id))
FROM factura_venta_detalle;

INSERT INTO seed_backup.dsc71_huella
SELECT 'uso_cupon', md5(string_agg(
         id || '|' || monto_descontado, E'\n' ORDER BY id))
FROM uso_cupon;

-- ── 4. Comprobación del respaldo ────────────────────────────────────────────

DO $$
DECLARE
    v text;
BEGIN
    SELECT string_agg(t, ', ') INTO v FROM (
        SELECT 'pedido' t WHERE (SELECT count(*) FROM seed_backup.dsc71_pedido) <> (SELECT count(*) FROM pedido)
        UNION ALL SELECT 'pedido_detalle' WHERE (SELECT count(*) FROM seed_backup.dsc71_pedido_detalle) <> (SELECT count(*) FROM pedido_detalle)
        UNION ALL SELECT 'pago' WHERE (SELECT count(*) FROM seed_backup.dsc71_pago) <> (SELECT count(*) FROM pago)
        UNION ALL SELECT 'factura_venta' WHERE (SELECT count(*) FROM seed_backup.dsc71_factura_venta) <> (SELECT count(*) FROM factura_venta)
        UNION ALL SELECT 'factura_venta_detalle' WHERE (SELECT count(*) FROM seed_backup.dsc71_factura_venta_detalle) <> (SELECT count(*) FROM factura_venta_detalle)
        UNION ALL SELECT 'uso_cupon' WHERE (SELECT count(*) FROM seed_backup.dsc71_uso_cupon) <> (SELECT count(*) FROM uso_cupon)
    ) x;
    IF v IS NOT NULL THEN
        RAISE EXCEPTION 'RESPALDO INCOMPLETO en: %', v;
    END IF;
    IF (SELECT count(*) FROM seed_backup.dsc71_huella) <> 6 THEN
        RAISE EXCEPTION 'Faltan huellas md5 (esperadas 6, hay %)',
              (SELECT count(*) FROM seed_backup.dsc71_huella);
    END IF;
    RAISE NOTICE 'Respaldo OK: 6 tablas + huellas + agregados + testigos.';
END $$;

COMMIT;

\echo '--- Huellas del estado PREVIO ---'
SELECT tabla, huella FROM seed_backup.dsc71_huella ORDER BY tabla;
\echo '--- Agregados monetarios ANTES ---'
SELECT metrica, round(valor,2) AS valor FROM seed_backup.dsc71_agregados ORDER BY metrica;
