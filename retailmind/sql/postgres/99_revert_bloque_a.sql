-- =====================================================================
-- 99_revert_bloque_a.sql   (NO se ejecuta en la siembra; es la reversion)
-- Deshace por completo la SIEMBRA BLOQUE A (scripts 55-59) usando los
-- umbrales max(id) PRE-siembra guardados en configuracion_tienda, en
-- orden seguro de FKs, y RESTAURA inventario.stock_actual a su valor
-- original (los movimientos de apertura no lo alteraron; solo las
-- entradas por compra sembradas lo subieron, asi que se resta esa suma).
--
-- Deja la base exactamente como estaba antes del script 55. Los 99
-- movimientos reales, las 17 ordenes, 14 facturas, 2 proveedores, 2
-- clientes originales, etc. quedan intactos.
-- =====================================================================

DO $$
DECLARE
    t55 jsonb; t56 jsonb; t57 jsonb; t58 jsonb;
BEGIN
    SELECT valor::jsonb INTO t55 FROM configuracion_tienda WHERE clave='seed_ba_55_entidades';
    SELECT valor::jsonb INTO t56 FROM configuracion_tienda WHERE clave='seed_ba_56_apertura';
    SELECT valor::jsonb INTO t57 FROM configuracion_tienda WHERE clave='seed_ba_57_ordenes';
    SELECT valor::jsonb INTO t58 FROM configuracion_tienda WHERE clave='seed_ba_58_facturas';

    IF t55 IS NULL THEN
        RAISE NOTICE 'No hay marca de Bloque A; nada que revertir.';
        RETURN;
    END IF;

    -- ---- 58: pagos, CxP, factura detalle/cabecera --------------------
    IF t58 IS NOT NULL THEN
        DELETE FROM pago_proveedor         WHERE id > (t58->>'pago_proveedor')::bigint;
        DELETE FROM cuenta_por_pagar        WHERE id > (t58->>'cuenta_por_pagar')::bigint;
        DELETE FROM factura_compra_detalle  WHERE id > (t58->>'factura_compra_detalle')::bigint;
        DELETE FROM factura_compra          WHERE id > (t58->>'factura_compra')::bigint;
    END IF;

    -- ---- auditorias sembradas (aprobaciones 57/59 + facturas 58/59) ---
    DELETE FROM log_auditoria WHERE id > (t57->>'log_auditoria')::bigint;

    -- ---- RESTAURAR STOCK: restar entradas por compra sembradas -------
    UPDATE inventario i SET stock_actual = i.stock_actual - s.q
    FROM (
        SELECT producto_variante_id pv, bodega_id bod, COALESCE(sum(cantidad),0) q
        FROM movimiento_inventario
        WHERE referencia_tipo='recepcion_mercancia' AND id > (t57->>'movimiento_inventario')::bigint
        GROUP BY 1,2
    ) s
    WHERE i.producto_variante_id=s.pv AND i.bodega_id=s.bod;

    -- ---- borrar filas de inventario creadas por 57/59 ----------------
    -- (las que no tienen apertura ni movimiento real previo; ya quedaron en 0)
    DELETE FROM inventario i
    WHERE NOT EXISTS (SELECT 1 FROM movimiento_inventario m
                      WHERE m.producto_variante_id=i.producto_variante_id AND m.bodega_id=i.bodega_id
                        AND m.referencia_tipo='inventario_inicial')
      AND NOT EXISTS (SELECT 1 FROM movimiento_inventario m
                      WHERE m.producto_variante_id=i.producto_variante_id AND m.bodega_id=i.bodega_id
                        AND m.id <= (t56->>'movimiento_inventario')::bigint);

    -- ---- 57: movimientos sembrados (entradas compra + apertura) ------
    DELETE FROM movimiento_inventario WHERE id > (t57->>'movimiento_inventario')::bigint;       -- entradas compra 57/59
    DELETE FROM movimiento_inventario WHERE referencia_tipo='inventario_inicial'
                                        AND id > (t56->>'movimiento_inventario')::bigint;         -- apertura 56

    -- ---- 57: recepciones y ordenes -----------------------------------
    DELETE FROM recepcion_detalle    WHERE id > (t57->>'recepcion_detalle')::bigint;
    DELETE FROM recepcion_mercancia  WHERE id > (t57->>'recepcion_mercancia')::bigint;
    DELETE FROM orden_compra_detalle WHERE id > (t57->>'orden_compra_detalle')::bigint;
    DELETE FROM orden_compra         WHERE id > (t57->>'orden_compra')::bigint;

    -- ---- 55: entidades -----------------------------------------------
    DELETE FROM producto_proveedor   WHERE id > (t55->>'producto_proveedor')::bigint;
    DELETE FROM direccion            WHERE id > (t55->>'direccion')::bigint;
    DELETE FROM cliente              WHERE id > (t55->>'cliente')::bigint;
    DELETE FROM usuario_rol          WHERE id > (t55->>'usuario_rol')::bigint;
    DELETE FROM usuario              WHERE id > (t55->>'usuario')::bigint;
    DELETE FROM contacto_proveedor   WHERE id > (t55->>'contacto_proveedor')::bigint;
    DELETE FROM transportista        WHERE id > (t55->>'transportista')::bigint;
    DELETE FROM proveedor            WHERE id > (t55->>'proveedor')::bigint;
    DELETE FROM ciudad               WHERE id > (t55->>'ciudad')::bigint;

    -- ---- quitar marcas -----------------------------------------------
    DELETE FROM configuracion_tienda WHERE clave LIKE 'seed_ba_%' OR clave='seed_bloque_a';

    RAISE NOTICE 'Bloque A revertido por completo.';
END $$;
