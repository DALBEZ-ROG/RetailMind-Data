-- ============================================================================
-- 99_revert_objetivos_pendientes.sql
-- REVERSION COMPLETA del cierre de los 6 objetivos sin datos (scripts 80-84).
-- Devuelve public al estado BIT-IDENTICO respaldado por
-- 79_respaldo_objetivos_pendientes.sql y lo COMPRUEBA comparando las huellas
-- md5. Si alguna no coincide, aborta (la transaccion completa hace ROLLBACK).
--
-- Estrategia:
--   1. Las tablas que solo recibieron ALTAS -> DELETE id > umbral, en orden
--      FK-seguro (respuesta_pregunta antes que pregunta_producto,
--      promocion_producto antes que promocion, banner antes que campana).
--   2. movimiento_inventario recibio altas Y modificaciones (reencadenado)
--      -> se reconstruye fila a fila desde seed_backup.op79_movimiento_inventario
--      (id es GENERATED ALWAYS: se reinsertan con OVERRIDING SYSTEM VALUE).
--   3. inventario recibio altas (destinos nuevos) y modificaciones de
--      stock_actual -> se borran las filas nuevas y se restauran las demas.
--   4. Se devuelven las secuencias de identidad a su valor previo para que una
--      re-aplicacion reproduzca los mismos ids.
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
        SELECT 'op79_movimiento_inventario' t WHERE to_regclass('seed_backup.op79_movimiento_inventario') IS NULL
        UNION ALL SELECT 'op79_inventario' WHERE to_regclass('seed_backup.op79_inventario') IS NULL
        UNION ALL SELECT 'op79_umbral'     WHERE to_regclass('seed_backup.op79_umbral') IS NULL
        UNION ALL SELECT 'op79_huella'     WHERE to_regclass('seed_backup.op79_huella') IS NULL
    ) x;
    IF v_faltan IS NOT NULL THEN
        RAISE EXCEPTION 'No hay respaldo para revertir. Falta: %', v_faltan;
    END IF;
END $$;

-- ── 1. Bajas de las tablas que solo recibieron altas (orden FK-seguro) ──────

DELETE FROM respuesta_pregunta   WHERE id > (SELECT max_id FROM seed_backup.op79_umbral WHERE tabla='respuesta_pregunta');
DELETE FROM pregunta_producto    WHERE id > (SELECT max_id FROM seed_backup.op79_umbral WHERE tabla='pregunta_producto');
DELETE FROM log_acceso           WHERE id > (SELECT max_id FROM seed_backup.op79_umbral WHERE tabla='log_acceso');
DELETE FROM meta_venta           WHERE id > (SELECT max_id FROM seed_backup.op79_umbral WHERE tabla='meta_venta');
DELETE FROM promocion_producto   WHERE id > (SELECT max_id FROM seed_backup.op79_umbral WHERE tabla='promocion_producto');
DELETE FROM promocion            WHERE id > (SELECT max_id FROM seed_backup.op79_umbral WHERE tabla='promocion');
DELETE FROM banner               WHERE id > (SELECT max_id FROM seed_backup.op79_umbral WHERE tabla='banner');
DELETE FROM campana              WHERE id > (SELECT max_id FROM seed_backup.op79_umbral WHERE tabla='campana');
DELETE FROM cupon                WHERE id > (SELECT max_id FROM seed_backup.op79_umbral WHERE tabla='cupon');
DELETE FROM log_auditoria        WHERE id > (SELECT max_id FROM seed_backup.op79_umbral WHERE tabla='log_auditoria');
-- el kardex referencia estas dos cabeceras: primero los movimientos nuevos
DELETE FROM movimiento_inventario WHERE id > (SELECT max_id FROM seed_backup.op79_umbral WHERE tabla='movimiento_inventario');
DELETE FROM transferencia_bodega WHERE id > (SELECT max_id FROM seed_backup.op79_umbral WHERE tabla='transferencia_bodega');
DELETE FROM ajuste_inventario    WHERE id > (SELECT max_id FROM seed_backup.op79_umbral WHERE tabla='ajuste_inventario');

-- ── 2. Reconstruccion exacta del kardex (reencadenado del script 80) ────────

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
FROM seed_backup.op79_movimiento_inventario b
WHERE b.id = m.id
  AND (m.producto_variante_id, m.bodega_id, m.tipo_movimiento_id, m.cantidad,
       m.stock_anterior, m.stock_nuevo, m.fecha_creacion)
   IS DISTINCT FROM
      (b.producto_variante_id, b.bodega_id, b.tipo_movimiento_id, b.cantidad,
       b.stock_anterior, b.stock_nuevo, b.fecha_creacion);

INSERT INTO movimiento_inventario
    (id, producto_variante_id, bodega_id, tipo_movimiento_id, lote_id, usuario_id,
     cantidad, stock_anterior, stock_nuevo, costo_unitario, referencia_tipo,
     referencia_id, observacion, fecha_creacion)
OVERRIDING SYSTEM VALUE
SELECT b.id, b.producto_variante_id, b.bodega_id, b.tipo_movimiento_id, b.lote_id,
       b.usuario_id, b.cantidad, b.stock_anterior, b.stock_nuevo, b.costo_unitario,
       b.referencia_tipo, b.referencia_id, b.observacion, b.fecha_creacion
FROM seed_backup.op79_movimiento_inventario b
WHERE NOT EXISTS (SELECT 1 FROM movimiento_inventario m WHERE m.id = b.id);

-- ── 3. inventario: se borran las filas nuevas y se restauran las demas ──────

DELETE FROM inventario i
WHERE NOT EXISTS (SELECT 1 FROM seed_backup.op79_inventario b WHERE b.id = i.id);

-- trg_inventario_touch pisa fecha_actualizacion con now() en cada UPDATE: hay
-- que apagarlo para poder restaurar la fila BIT-IDENTICA (incluida esa columna).
ALTER TABLE inventario DISABLE TRIGGER trg_inventario_touch;

UPDATE inventario i
   SET stock_actual = b.stock_actual,
       stock_reservado = b.stock_reservado,
       stock_minimo = b.stock_minimo,
       stock_maximo = b.stock_maximo,
       fecha_actualizacion = b.fecha_actualizacion
FROM seed_backup.op79_inventario b
WHERE b.id = i.id
  AND (i.stock_actual, i.stock_reservado, i.stock_minimo, i.stock_maximo,
       i.fecha_actualizacion)
   IS DISTINCT FROM
      (b.stock_actual, b.stock_reservado, b.stock_minimo, b.stock_maximo,
       b.fecha_actualizacion);

ALTER TABLE inventario ENABLE TRIGGER trg_inventario_touch;

-- ── 4. Marcas de idempotencia ──────────────────────────────────────────────

DELETE FROM configuracion_tienda WHERE clave IN
    ('seed_op_80_transferencias_ajustes','seed_op_81_preguntas','seed_op_82_log_acceso',
     'seed_op_83_marketing_vigente','seed_op_84_metas_departamentos');

-- ── 5. Secuencias al valor previo ──────────────────────────────────────────

DO $$
DECLARE r record; s text;
BEGIN
    FOR r IN SELECT tabla, max_id FROM seed_backup.op79_umbral LOOP
        s := pg_get_serial_sequence('public.' || r.tabla, 'id');
        IF s IS NOT NULL THEN PERFORM setval(s, greatest(r.max_id, 1), true); END IF;
    END LOOP;
    -- inventario no esta en el umbral: se recoloca sobre su max actual
    PERFORM setval(pg_get_serial_sequence('public.inventario', 'id'),
                   greatest((SELECT max(id) FROM inventario), 1), true);
END $$;

-- ── 6. Comprobacion: huellas md5 identicas al respaldo ─────────────────────

DO $$
DECLARE
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

    INSERT INTO _h_now SELECT 'pedido', count(*), md5(string_agg(
         id || '|' || cliente_id || '|' || estado_pedido_id || '|' || canal
            || '|' || fecha_pedido::text || '|' || subtotal || '|' || monto_descuento
            || '|' || monto_impuesto || '|' || costo_envio || '|' || total,
         E'\n' ORDER BY id)) FROM pedido;

    INSERT INTO _h_now SELECT 'pedido_detalle', count(*), md5(string_agg(
         id || '|' || pedido_id || '|' || producto_variante_id || '|' || cantidad
            || '|' || precio_unitario || '|' || monto_descuento || '|' || monto_impuesto,
         E'\n' ORDER BY id)) FROM pedido_detalle;

    INSERT INTO _h_now SELECT 'factura_venta', count(*), md5(string_agg(
         id || '|' || COALESCE(pedido_id::text,'~') || '|' || numero || '|' || estado
            || '|' || subtotal || '|' || monto_impuesto || '|' || total,
         E'\n' ORDER BY id)) FROM factura_venta;

    INSERT INTO _h_now SELECT 'pago', count(*), md5(string_agg(
         id || '|' || COALESCE(pedido_id::text,'~') || '|' || estado || '|' || monto
            || '|' || fecha_pago::text, E'\n' ORDER BY id)) FROM pago;

    INSERT INTO _h_now SELECT 'uso_cupon', count(*), md5(string_agg(
         id || '|' || cupon_id || '|' || COALESCE(pedido_id::text,'~')
            || '|' || COALESCE(cliente_id::text,'~') || '|' || monto_descontado,
         E'\n' ORDER BY id)) FROM uso_cupon;

    INSERT INTO _h_now SELECT 'orden_compra', count(*), md5(string_agg(
         id || '|' || numero || '|' || proveedor_id || '|' || estado || '|' || total,
         E'\n' ORDER BY id)) FROM orden_compra;

    INSERT INTO _h_now SELECT 'factura_compra', count(*), md5(string_agg(
         id || '|' || proveedor_id || '|' || numero_factura || '|' || estado || '|' || total,
         E'\n' ORDER BY id)) FROM factura_compra;

    INSERT INTO _h_now SELECT 'cuenta_por_pagar', count(*), md5(string_agg(
         id || '|' || factura_compra_id || '|' || estado || '|' || monto_original
            || '|' || saldo_pendiente, E'\n' ORDER BY id)) FROM cuenta_por_pagar;

    INSERT INTO _h_now SELECT 'pago_proveedor', count(*), md5(string_agg(
         id || '|' || cuenta_por_pagar_id || '|' || monto || '|' || fecha_pago::text,
         E'\n' ORDER BY id)) FROM pago_proveedor;

    SELECT string_agg(b.tabla || ' (respaldo ' || b.filas || ' filas / ahora '
                      || COALESCE(n.filas::text, '?') || ')', ', ')
      INTO v_diff
    FROM seed_backup.op79_huella b
    LEFT JOIN _h_now n ON n.tabla = b.tabla
    WHERE n.huella IS DISTINCT FROM b.huella;

    IF v_diff IS NOT NULL THEN
        RAISE EXCEPTION 'La reversion NO quedo bit-identica en: %', v_diff;
    END IF;
    RAISE NOTICE 'Reversion verificada: las % huellas md5 coinciden con el respaldo.',
                 (SELECT count(*) FROM seed_backup.op79_huella);
END $$;

COMMIT;

\echo '--- Estado tras la reversion (debe coincidir con la foto ANTES del 79) ---'
SELECT 'transferencia_bodega' t, count(*) FROM transferencia_bodega
UNION ALL SELECT 'ajuste_inventario', count(*) FROM ajuste_inventario
UNION ALL SELECT 'pregunta_producto', count(*) FROM pregunta_producto
UNION ALL SELECT 'respuesta_pregunta', count(*) FROM respuesta_pregunta
UNION ALL SELECT 'log_acceso', count(*) FROM log_acceso
UNION ALL SELECT 'meta_venta', count(*) FROM meta_venta
UNION ALL SELECT 'promocion', count(*) FROM promocion
UNION ALL SELECT 'campana', count(*) FROM campana
UNION ALL SELECT 'banner', count(*) FROM banner
UNION ALL SELECT 'cupon', count(*) FROM cupon
UNION ALL SELECT 'movimiento_inventario', count(*) FROM movimiento_inventario
UNION ALL SELECT 'inventario', count(*) FROM inventario
ORDER BY 1;
