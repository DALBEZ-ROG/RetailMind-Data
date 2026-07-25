-- ============================================================================
-- 79_respaldo_objetivos_pendientes.sql
-- RESPALDO RESTAURABLE previo al CIERRE DE LOS 6 OBJETIVOS SIN DATOS
-- (seccion 8 de docs/tactico/AUDITORIA_TRANSVERSAL_SEED.md), scripts 80-84.
--
-- QUE SE VA A TOCAR (y por tanto que se respalda):
--   * Solo ALTAS (la reversion borra id > umbral):
--       transferencia_bodega, ajuste_inventario, pregunta_producto,
--       respuesta_pregunta, log_acceso, meta_venta, promocion,
--       promocion_producto, campana, banner, cupon, log_auditoria.
--   * ALTAS + MODIFICACIONES (se respaldan FILAS COMPLETAS para restaurar
--     bit-identico; id es GENERATED ALWAYS -> la reversion usa
--     OVERRIDING SYSTEM VALUE):
--       movimiento_inventario  (el script 80 inserta movimientos con fecha
--                               PASADA y reencadena stock_anterior/stock_nuevo
--                               de las cadenas afectadas)
--       inventario             (stock_actual de las filas afectadas por las
--                               transferencias y los ajustes)
--   * VENTAS / COMPRAS / DINERO: NO se tocan. Se respaldan huellas md5 como
--     testigo (pedido, pedido_detalle, factura_venta, pago, orden_compra,
--     factura_compra, cuenta_por_pagar, pago_proveedor, uso_cupon).
--
-- Todo va al esquema seed_backup (NO public), prefijo op79_.
-- Solo lectura sobre public: este script NO modifica ni una fila de negocio.
-- Ejecutar como postgres (superusuario) sobre la BD retailmind.
-- ============================================================================
\set ON_ERROR_STOP on

BEGIN;

CREATE SCHEMA IF NOT EXISTS seed_backup;

-- ── 1. Filas completas de las tablas que se MODIFICAN ───────────────────────

DROP TABLE IF EXISTS seed_backup.op79_movimiento_inventario;
CREATE TABLE seed_backup.op79_movimiento_inventario AS
SELECT id, producto_variante_id, bodega_id, tipo_movimiento_id, lote_id, usuario_id,
       cantidad, stock_anterior, stock_nuevo, costo_unitario, referencia_tipo,
       referencia_id, observacion, fecha_creacion
FROM movimiento_inventario;
ALTER TABLE seed_backup.op79_movimiento_inventario ADD PRIMARY KEY (id);

DROP TABLE IF EXISTS seed_backup.op79_inventario;
CREATE TABLE seed_backup.op79_inventario AS
SELECT id, producto_variante_id, bodega_id, stock_actual, stock_reservado,
       stock_minimo, stock_maximo, fecha_actualizacion
FROM inventario;
ALTER TABLE seed_backup.op79_inventario ADD PRIMARY KEY (id);

-- ── 2. Umbrales de id (tablas que SOLO reciben altas) ───────────────────────

DROP TABLE IF EXISTS seed_backup.op79_umbral;
CREATE TABLE seed_backup.op79_umbral (tabla text PRIMARY KEY, max_id bigint NOT NULL,
                                      filas bigint NOT NULL);
INSERT INTO seed_backup.op79_umbral
          SELECT 'transferencia_bodega', COALESCE(max(id),0), count(*) FROM transferencia_bodega
UNION ALL SELECT 'ajuste_inventario',    COALESCE(max(id),0), count(*) FROM ajuste_inventario
UNION ALL SELECT 'pregunta_producto',    COALESCE(max(id),0), count(*) FROM pregunta_producto
UNION ALL SELECT 'respuesta_pregunta',   COALESCE(max(id),0), count(*) FROM respuesta_pregunta
UNION ALL SELECT 'log_acceso',           COALESCE(max(id),0), count(*) FROM log_acceso
UNION ALL SELECT 'meta_venta',           COALESCE(max(id),0), count(*) FROM meta_venta
UNION ALL SELECT 'promocion',            COALESCE(max(id),0), count(*) FROM promocion
UNION ALL SELECT 'promocion_producto',   COALESCE(max(id),0), count(*) FROM promocion_producto
UNION ALL SELECT 'campana',              COALESCE(max(id),0), count(*) FROM campana
UNION ALL SELECT 'banner',               COALESCE(max(id),0), count(*) FROM banner
UNION ALL SELECT 'cupon',                COALESCE(max(id),0), count(*) FROM cupon
UNION ALL SELECT 'log_auditoria',        COALESCE(max(id),0), count(*) FROM log_auditoria
UNION ALL SELECT 'movimiento_inventario',COALESCE(max(id),0), count(*) FROM movimiento_inventario;

-- ── 3. Agregados de referencia (foto ANTES) ─────────────────────────────────

DROP TABLE IF EXISTS seed_backup.op79_agregados;
CREATE TABLE seed_backup.op79_agregados AS
-- los 6 objetivos
          SELECT 'obj1_transferencias'      AS metrica, count(*)::numeric AS valor FROM transferencia_bodega
UNION ALL SELECT 'obj1_transf_en_camino',   count(*)::numeric FROM transferencia_bodega WHERE estado IN ('pendiente','en_transito')
UNION ALL SELECT 'obj2_preguntas',          count(*)::numeric FROM pregunta_producto
UNION ALL SELECT 'obj2_respuestas',         count(*)::numeric FROM respuesta_pregunta
UNION ALL SELECT 'obj2_preg_pendientes',    count(*)::numeric FROM pregunta_producto WHERE estado = 'pendiente'
UNION ALL SELECT 'obj3_log_acceso',         count(*)::numeric FROM log_acceso
UNION ALL SELECT 'obj3_log_acceso_meses',   count(DISTINCT date_trunc('month', fecha_creacion))::numeric FROM log_acceso
UNION ALL SELECT 'obj4_promo_vigentes',     count(*)::numeric FROM promocion WHERE activo AND fecha_inicio <= now() AND (fecha_fin IS NULL OR fecha_fin >= now())
UNION ALL SELECT 'obj4_campana_vigentes',   count(*)::numeric FROM campana  WHERE fecha_inicio <= current_date AND fecha_fin >= current_date
UNION ALL SELECT 'obj4_banner_vigentes',    count(*)::numeric FROM banner   WHERE activo AND (fecha_inicio IS NULL OR fecha_inicio <= now()) AND (fecha_fin IS NULL OR fecha_fin >= now())
UNION ALL SELECT 'obj4_cupon_vigentes',     count(*)::numeric FROM cupon    WHERE activo AND fecha_inicio <= now() AND (fecha_fin IS NULL OR fecha_fin >= now())
UNION ALL SELECT 'obj5_ajustes',            count(*)::numeric FROM ajuste_inventario
UNION ALL SELECT 'obj6_metas',              count(*)::numeric FROM meta_venta
UNION ALL SELECT 'obj6_departamentos',      count(DISTINCT departamento)::numeric FROM meta_venta
-- kardex / inventario (invariantes del script 80)
UNION ALL SELECT 'kardex_movs',             count(*)::numeric FROM movimiento_inventario
UNION ALL SELECT 'kardex_uds_entrada', COALESCE(sum(mi.cantidad),0)::numeric
     FROM movimiento_inventario mi JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id WHERE tm.factor = 1
UNION ALL SELECT 'kardex_uds_salida',  COALESCE(sum(mi.cantidad),0)::numeric
     FROM movimiento_inventario mi JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id WHERE tm.factor = -1
UNION ALL SELECT 'kardex_uds_salida_venta', COALESCE(sum(cantidad),0)::numeric FROM movimiento_inventario WHERE tipo_movimiento_id = 5
UNION ALL SELECT 'kardex_uds_entrada_compra',COALESCE(sum(cantidad),0)::numeric FROM movimiento_inventario WHERE tipo_movimiento_id = 1
UNION ALL SELECT 'inventario_filas',        count(*)::numeric FROM inventario
UNION ALL SELECT 'inventario_stock_actual', COALESCE(sum(stock_actual),0)::numeric FROM inventario
-- testigo de VENTAS / COMPRAS / DINERO (nada de esto se toca)
UNION ALL SELECT 'venta_pedido_filas',      count(*)::numeric      FROM pedido
UNION ALL SELECT 'venta_pedido_total',      COALESCE(sum(total),0) FROM pedido
UNION ALL SELECT 'venta_factura_total',     COALESCE(sum(total),0) FROM factura_venta WHERE estado <> 'anulada'
UNION ALL SELECT 'venta_pago_completado',   COALESCE(sum(monto),0) FROM pago WHERE estado = 'completado'
UNION ALL SELECT 'compra_factura_total',    COALESCE(sum(total),0) FROM factura_compra
UNION ALL SELECT 'compra_pago_total',       COALESCE(sum(monto),0) FROM pago_proveedor
UNION ALL SELECT 'compra_cxp_saldo',        COALESCE(sum(saldo_pendiente),0) FROM cuenta_por_pagar;

-- ── 4. Huellas md5 (prueba de reversion bit-identica) ───────────────────────

DROP TABLE IF EXISTS seed_backup.op79_huella;
CREATE TABLE seed_backup.op79_huella (tabla text PRIMARY KEY, filas bigint NOT NULL, huella text);

INSERT INTO seed_backup.op79_huella
SELECT 'movimiento_inventario', count(*), md5(string_agg(
         id || '|' || producto_variante_id || '|' || bodega_id || '|' || tipo_movimiento_id
            || '|' || COALESCE(lote_id::text,'~') || '|' || COALESCE(usuario_id::text,'~')
            || '|' || cantidad || '|' || stock_anterior || '|' || stock_nuevo
            || '|' || COALESCE(costo_unitario::text,'~') || '|' || COALESCE(referencia_tipo,'~')
            || '|' || COALESCE(referencia_id::text,'~') || '|' || COALESCE(observacion,'~')
            || '|' || fecha_creacion::text, E'\n' ORDER BY id))
FROM movimiento_inventario;

INSERT INTO seed_backup.op79_huella
SELECT 'inventario', count(*), md5(string_agg(
         id || '|' || producto_variante_id || '|' || bodega_id || '|' || stock_actual
            || '|' || stock_reservado || '|' || stock_minimo
            || '|' || COALESCE(stock_maximo::text,'~')
            || '|' || COALESCE(fecha_actualizacion::text,'~'), E'\n' ORDER BY id))
FROM inventario;

-- Testigos que NO deben cambiar (ventas, compras, dinero)
INSERT INTO seed_backup.op79_huella
SELECT 'pedido', count(*), md5(string_agg(
         id || '|' || cliente_id || '|' || estado_pedido_id || '|' || canal || '|' || fecha_pedido::text
            || '|' || subtotal || '|' || monto_descuento || '|' || monto_impuesto
            || '|' || costo_envio || '|' || total, E'\n' ORDER BY id))
FROM pedido;

INSERT INTO seed_backup.op79_huella
SELECT 'pedido_detalle', count(*), md5(string_agg(
         id || '|' || pedido_id || '|' || producto_variante_id || '|' || cantidad
            || '|' || precio_unitario || '|' || monto_descuento || '|' || monto_impuesto,
         E'\n' ORDER BY id))
FROM pedido_detalle;

INSERT INTO seed_backup.op79_huella
SELECT 'factura_venta', count(*), md5(string_agg(
         id || '|' || COALESCE(pedido_id::text,'~') || '|' || numero || '|' || estado
            || '|' || subtotal || '|' || monto_impuesto || '|' || total, E'\n' ORDER BY id))
FROM factura_venta;

INSERT INTO seed_backup.op79_huella
SELECT 'pago', count(*), md5(string_agg(
         id || '|' || COALESCE(pedido_id::text,'~') || '|' || estado || '|' || monto
            || '|' || fecha_pago::text, E'\n' ORDER BY id))
FROM pago;

INSERT INTO seed_backup.op79_huella
SELECT 'uso_cupon', count(*), md5(string_agg(
         id || '|' || cupon_id || '|' || COALESCE(pedido_id::text,'~')
            || '|' || COALESCE(cliente_id::text,'~') || '|' || monto_descontado,
         E'\n' ORDER BY id))
FROM uso_cupon;

INSERT INTO seed_backup.op79_huella
SELECT 'orden_compra', count(*), md5(string_agg(
         id || '|' || numero || '|' || proveedor_id || '|' || estado || '|' || total,
         E'\n' ORDER BY id))
FROM orden_compra;

INSERT INTO seed_backup.op79_huella
SELECT 'factura_compra', count(*), md5(string_agg(
         id || '|' || proveedor_id || '|' || numero_factura || '|' || estado || '|' || total,
         E'\n' ORDER BY id))
FROM factura_compra;

INSERT INTO seed_backup.op79_huella
SELECT 'cuenta_por_pagar', count(*), md5(string_agg(
         id || '|' || factura_compra_id || '|' || estado || '|' || monto_original
            || '|' || saldo_pendiente, E'\n' ORDER BY id))
FROM cuenta_por_pagar;

INSERT INTO seed_backup.op79_huella
SELECT 'pago_proveedor', count(*), md5(string_agg(
         id || '|' || cuenta_por_pagar_id || '|' || monto || '|' || fecha_pago::text,
         E'\n' ORDER BY id))
FROM pago_proveedor;

-- ── 5. Marca ────────────────────────────────────────────────────────────────

INSERT INTO configuracion_tienda (clave, valor, tipo_dato, descripcion)
VALUES ('seed_op_79_respaldo',
        jsonb_build_object(
            'fecha', now(),
            'movimiento_inventario', (SELECT count(*) FROM seed_backup.op79_movimiento_inventario),
            'inventario',            (SELECT count(*) FROM seed_backup.op79_inventario))::text,
        'json', 'Respaldo previo al cierre de objetivos pendientes (scripts 80-84)')
ON CONFLICT (clave) DO UPDATE SET valor = EXCLUDED.valor;

COMMIT;

\echo '--- Respaldo op79 creado. Estado ANTES: ---'
SELECT metrica, valor FROM seed_backup.op79_agregados
WHERE metrica LIKE 'obj%' ORDER BY metrica;
SELECT tabla, filas, left(huella, 12) AS huella FROM seed_backup.op79_huella ORDER BY tabla;
