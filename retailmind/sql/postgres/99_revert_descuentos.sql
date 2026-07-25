-- ============================================================================
-- 99_revert_descuentos.sql
-- REVERSIÓN COMPLETA de los scripts 72 (cupón / A8) y 73 (promoción / A9).
-- Restaura el estado respaldado por 71_respaldo_descuentos.sql y VERIFICA que
-- las 6 huellas md5 coinciden con las del estado previo (vuelta bit-idéntica).
--
-- Usa session_replication_role = 'replica' para que NO disparen los triggers
-- de recálculo (fn_recalcular_total_pedido, fn_recalcular_total_factura_venta)
-- ni el touch de fecha_actualizacion: la restauración es de VALORES CRUDOS,
-- no un recálculo. Si los triggers corrieran, fecha_actualizacion cambiaría y
-- la huella no volvería a ser idéntica.
--
-- Aborta (transacción completa) si alguna huella no coincide.
-- Ejecutar como postgres (superusuario) sobre la BD retailmind.
-- ============================================================================
\set ON_ERROR_STOP on

BEGIN;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.tables
                   WHERE table_schema='seed_backup' AND table_name='dsc71_huella') THEN
        RAISE EXCEPTION 'No existe el respaldo seed_backup.dsc71_* — ejecute primero 71_respaldo_descuentos.sql';
    END IF;
END $$;

SET LOCAL session_replication_role = 'replica';

-- ── Restauración de valores crudos ──────────────────────────────────────────

UPDATE pedido p SET
    subtotal            = b.subtotal,
    monto_descuento     = b.monto_descuento,
    monto_impuesto      = b.monto_impuesto,
    costo_envio         = b.costo_envio,
    total               = b.total,
    fecha_actualizacion = b.fecha_actualizacion
FROM seed_backup.dsc71_pedido b
WHERE b.id = p.id
  AND (p.subtotal, p.monto_descuento, p.monto_impuesto, p.costo_envio, p.total,
       p.fecha_actualizacion)
   IS DISTINCT FROM
      (b.subtotal, b.monto_descuento, b.monto_impuesto, b.costo_envio, b.total,
       b.fecha_actualizacion);

UPDATE pedido_detalle d SET
    monto_descuento = b.monto_descuento,
    monto_impuesto  = b.monto_impuesto
FROM seed_backup.dsc71_pedido_detalle b
WHERE b.id = d.id
  AND (d.monto_descuento, d.monto_impuesto)
   IS DISTINCT FROM (b.monto_descuento, b.monto_impuesto);

UPDATE pago g SET
    monto               = b.monto,
    fecha_actualizacion = b.fecha_actualizacion
FROM seed_backup.dsc71_pago b
WHERE b.id = g.id
  AND (g.monto, g.fecha_actualizacion)
   IS DISTINCT FROM (b.monto, b.fecha_actualizacion);

UPDATE factura_venta f SET
    subtotal            = b.subtotal,
    monto_descuento     = b.monto_descuento,
    monto_impuesto      = b.monto_impuesto,
    total               = b.total,
    fecha_actualizacion = b.fecha_actualizacion
FROM seed_backup.dsc71_factura_venta b
WHERE b.id = f.id
  AND (f.subtotal, f.monto_descuento, f.monto_impuesto, f.total, f.fecha_actualizacion)
   IS DISTINCT FROM
      (b.subtotal, b.monto_descuento, b.monto_impuesto, b.total, b.fecha_actualizacion);

UPDATE factura_venta_detalle d SET
    monto_descuento = b.monto_descuento,
    monto_impuesto  = b.monto_impuesto
FROM seed_backup.dsc71_factura_venta_detalle b
WHERE b.id = d.id
  AND (d.monto_descuento, d.monto_impuesto)
   IS DISTINCT FROM (b.monto_descuento, b.monto_impuesto);

UPDATE uso_cupon u SET
    monto_descontado = b.monto_descontado
FROM seed_backup.dsc71_uso_cupon b
WHERE b.id = u.id
  AND u.monto_descontado IS DISTINCT FROM b.monto_descontado;

-- ── Verificación: las 6 huellas deben coincidir ─────────────────────────────

DO $$
DECLARE
    v_actual  text;
    v_previa  text;
    v_malas   text := '';
BEGIN
    FOR v_previa, v_actual IN
        SELECT h.huella, a.huella FROM seed_backup.dsc71_huella h
        JOIN (
            SELECT 'pedido' tabla, md5(string_agg(
                     id || '|' || subtotal || '|' || monto_descuento || '|' || monto_impuesto
                        || '|' || costo_envio || '|' || total
                        || '|' || COALESCE(fecha_actualizacion::text,'~'), E'\n' ORDER BY id)) huella
            FROM pedido
            UNION ALL
            SELECT 'pedido_detalle', md5(string_agg(
                     id || '|' || monto_descuento || '|' || monto_impuesto, E'\n' ORDER BY id))
            FROM pedido_detalle
            UNION ALL
            SELECT 'pago', md5(string_agg(
                     id || '|' || monto || '|' || estado
                        || '|' || COALESCE(fecha_actualizacion::text,'~'), E'\n' ORDER BY id))
            FROM pago
            UNION ALL
            SELECT 'factura_venta', md5(string_agg(
                     id || '|' || subtotal || '|' || monto_descuento || '|' || monto_impuesto
                        || '|' || total || '|' || COALESCE(fecha_actualizacion::text,'~'), E'\n' ORDER BY id))
            FROM factura_venta
            UNION ALL
            SELECT 'factura_venta_detalle', md5(string_agg(
                     id || '|' || monto_descuento || '|' || monto_impuesto, E'\n' ORDER BY id))
            FROM factura_venta_detalle
            UNION ALL
            SELECT 'uso_cupon', md5(string_agg(
                     id || '|' || monto_descontado, E'\n' ORDER BY id))
            FROM uso_cupon
        ) a ON a.tabla = h.tabla
        WHERE a.huella IS DISTINCT FROM h.huella
    LOOP
        v_malas := v_malas || ' (previa=' || v_previa || ' actual=' || v_actual || ')';
    END LOOP;

    IF v_malas <> '' THEN
        RAISE EXCEPTION 'REVERSIÓN INCOMPLETA — huellas distintas:%', v_malas;
    END IF;
    RAISE NOTICE 'REVERSIÓN OK: las 6 huellas md5 coinciden con el estado previo (bit-idéntico).';
END $$;

COMMIT;

\echo '--- Agregados monetarios tras revertir (deben igualar a dsc71_agregados) ---'
WITH ahora AS (
    SELECT 'pedido_total' m, COALESCE(sum(total),0) v FROM pedido
    UNION ALL SELECT 'pedido_monto_descuento', COALESCE(sum(monto_descuento),0) FROM pedido
    UNION ALL SELECT 'pedido_detalle_descuento', COALESCE(sum(monto_descuento),0) FROM pedido_detalle
    UNION ALL SELECT 'factura_total_vigente', COALESCE(sum(total),0) FROM factura_venta WHERE estado <> 'anulada'
    UNION ALL SELECT 'pago_completado', COALESCE(sum(monto),0) FROM pago WHERE estado='completado'
    UNION ALL SELECT 'uso_cupon_descontado', COALESCE(sum(monto_descontado),0) FROM uso_cupon
)
SELECT a.m AS metrica, round(b.valor,2) AS antes, round(a.v,2) AS ahora,
       round(a.v - b.valor, 2) AS diferencia
FROM ahora a JOIN seed_backup.dsc71_agregados b ON b.metrica = a.m ORDER BY 1;
