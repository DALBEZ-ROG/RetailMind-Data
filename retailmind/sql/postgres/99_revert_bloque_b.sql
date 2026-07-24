-- =====================================================================
-- 99_revert_bloque_b.sql   (NO se ejecuta en la siembra; es la reversion)
-- Deshace por completo la SIEMBRA BLOQUE B (scripts 60-63) usando los
-- umbrales max(id) PRE-siembra guardados en configuracion_tienda, en
-- orden seguro de FKs, y RESTAURA inventario.stock_actual a su valor
-- previo (revierte el efecto NETO de TODOS los movimientos sembrados por
-- Bloque B: salidas de venta, reingresos de devolucion y reposiciones).
--
-- Deja la base exactamente como estaba tras el Bloque A: los 35 pedidos,
-- 25 envios, 12 tickets, 7 devoluciones, 20 carritos, 4 resenas, etc.
-- reales/Bloque A quedan intactos. Requiere las marcas seed_bb_60..63.
-- Ejecutar como postgres.
-- =====================================================================

DO $$
DECLARE
    t60 jsonb; t61 jsonb; t62 jsonb; t63 jsonb;
    v_mov_thr bigint; v_log_thr bigint;
BEGIN
    SELECT valor::jsonb INTO t60 FROM configuracion_tienda WHERE clave='seed_bb_60_ventas';
    SELECT valor::jsonb INTO t61 FROM configuracion_tienda WHERE clave='seed_bb_61_carritos';
    SELECT valor::jsonb INTO t62 FROM configuracion_tienda WHERE clave='seed_bb_62_posventa';
    SELECT valor::jsonb INTO t63 FROM configuracion_tienda WHERE clave='seed_bb_63_soporte';

    IF t60 IS NULL THEN
        RAISE NOTICE 'No hay marca de Bloque B (60); nada que revertir.';
        RETURN;
    END IF;
    v_mov_thr := (t60->>'movimiento_inventario')::bigint;   -- el mas antiguo
    v_log_thr := (t60->>'log_auditoria')::bigint;

    -- ---- auditorias sembradas (60/62/63) -----------------------------
    DELETE FROM log_auditoria WHERE id > v_log_thr;

    -- ---- 63: soporte + resenas + devolucion a proveedor --------------
    IF t63 IS NOT NULL THEN
        DELETE FROM devolucion_proveedor_detalle    WHERE id > (t63->>'devolucion_proveedor_detalle')::bigint;
        DELETE FROM historial_devolucion_proveedor  WHERE id > (t63->>'historial_devolucion_proveedor')::bigint;
        DELETE FROM devolucion_proveedor            WHERE id > (t63->>'devolucion_proveedor')::bigint;
        DELETE FROM mensaje_ticket                  WHERE id > (t63->>'mensaje_ticket')::bigint;
        DELETE FROM ticket_soporte                  WHERE id > (t63->>'ticket_soporte')::bigint;
        DELETE FROM resena                          WHERE id > (t63->>'resena')::bigint;
        -- correlativo_ticket: 2025 lo creo el seed; 2026 lo subio de 7
        DELETE FROM correlativo_ticket WHERE anio = 2025;
        UPDATE correlativo_ticket SET ultimo = 7 WHERE anio = 2026;
    END IF;

    -- ---- 62: RMA + reembolsos + novedades ----------------------------
    IF t62 IS NOT NULL THEN
        DELETE FROM reembolso                       WHERE id > (t62->>'reembolso')::bigint;
        DELETE FROM historial_estado_devolucion     WHERE id > (t62->>'historial_estado_devolucion')::bigint;
        DELETE FROM item_defectuoso                 WHERE id > (t62->>'item_defectuoso')::bigint;
        DELETE FROM devolucion_detalle              WHERE id > (t62->>'devolucion_detalle')::bigint;
        DELETE FROM devolucion                      WHERE id > (t62->>'devolucion')::bigint;
        DELETE FROM novedad_envio                   WHERE id > (t62->>'novedad_envio')::bigint;
    END IF;

    -- ---- RESTAURAR STOCK: revertir el efecto neto de TODOS los -------
    -- movimientos sembrados por Bloque B (id > umbral de 60) -----------
    UPDATE inventario i SET stock_actual = i.stock_actual - s.delta
    FROM (
        SELECT mi.producto_variante_id pv, mi.bodega_id bod,
               SUM(CASE WHEN tm.factor=1 THEN mi.cantidad ELSE -mi.cantidad END) delta
        FROM movimiento_inventario mi JOIN tipo_movimiento tm ON tm.id=mi.tipo_movimiento_id
        WHERE mi.id > v_mov_thr
        GROUP BY 1,2
    ) s
    WHERE i.producto_variante_id=s.pv AND i.bodega_id=s.bod;
    DELETE FROM movimiento_inventario WHERE id > v_mov_thr;

    -- ---- 60: envios, facturas, pagos, historial, pedidos -------------
    DELETE FROM seguimiento_envio      WHERE id > (t60->>'seguimiento_envio')::bigint;
    DELETE FROM envio                  WHERE id > (t60->>'envio')::bigint;
    DELETE FROM factura_venta_detalle  WHERE id > (t60->>'factura_venta_detalle')::bigint;
    DELETE FROM factura_venta          WHERE id > (t60->>'factura_venta')::bigint;
    DELETE FROM transaccion_pago       WHERE id > (t60->>'transaccion_pago')::bigint;
    DELETE FROM pago                   WHERE id > (t60->>'pago')::bigint;
    DELETE FROM historial_estado_pedido WHERE id > (t60->>'historial_estado_pedido')::bigint;
    DELETE FROM pedido_detalle         WHERE id > (t60->>'pedido_detalle')::bigint;
    DELETE FROM pedido                 WHERE id > (t60->>'pedido')::bigint;

    -- ---- 61: carritos ------------------------------------------------
    IF t61 IS NOT NULL THEN
        DELETE FROM carrito_item       WHERE id > (t61->>'carrito_item')::bigint;
        DELETE FROM carrito            WHERE id > (t61->>'carrito')::bigint;
    END IF;

    -- ---- quitar marcas -----------------------------------------------
    DELETE FROM configuracion_tienda WHERE clave LIKE 'seed_bb_%';

    RAISE NOTICE 'Bloque B revertido por completo (scripts 60-63).';
END $$;
