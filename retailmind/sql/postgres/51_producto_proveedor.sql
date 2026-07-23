-- ============================================================================
-- 51_producto_proveedor.sql — Catálogo proveedor ↔ producto (2026-07-22,
-- OTD-COM-10, cierre de brecha del catálogo táctico § 11.1)
--
-- La tabla producto_proveedor existía completa (costo, tiempo_entrega_dias,
-- cantidad_minima, es_preferido, UNIQUE(proveedor, variante), RLS pol_horario,
-- trigger touch y matriz de GRANTs correcta: compras/admin escriben,
-- gerente/analista leen, bodega/despacho SIN acceso por segregación
-- financiera) pero con 0 filas: nada la escribía. Este script agrega SOLO lo
-- que faltaba en el motor:
--
-- 1) UN SOLO PREFERIDO POR PRODUCTO: índice único parcial sobre la variante.
--    El backend desmarca a los demás al marcar uno; el índice es el backstop.
--
-- 2) fn_upsert_producto_proveedor — SECURITY DEFINER: la recepción de
--    mercancía registra/actualiza la relación proveedor-variante con el costo
--    REALMENTE recibido (precio pactado de la línea de OC). La recepción la
--    ejecuta BODEGA, que NO tiene (ni debe tener) escritura sobre esta tabla:
--    la función corre como su dueño (postgres), mismo patrón que
--    fn_recalcular_total_* y fn_registrar_uso_cupon. DECISIÓN DOCUMENTADA:
--    la alimentación es AUTOMÁTICA (sin confirmación) porque el costo de una
--    recepción es un hecho ya pactado en la OC aprobada por Gerencia — no hay
--    nada que confirmar; solo actualiza costo/activo y NUNCA pisa los datos
--    comerciales manuales (es_preferido, cantidad_minima, tiempo_entrega,
--    codigo_proveedor son de COMPRAS).
--
-- 3) DERIVACIÓN HISTÓRICA (documentada): las 14 recepciones reales existentes
--    (22 líneas) se vuelcan una vez con el costo de la ÚLTIMA recepción por
--    par proveedor-variante. Es dato DERIVADO de las compras reales, no
--    capturado a mano. ON CONFLICT DO NOTHING = idempotente y jamás pisa
--    filas ya gestionadas por Compras.
-- ============================================================================

BEGIN;

-- ── 1) Solo un proveedor preferido por variante ─────────────────────────────
CREATE UNIQUE INDEX IF NOT EXISTS uq_producto_proveedor_preferido
    ON producto_proveedor (producto_variante_id)
    WHERE es_preferido;

-- ── 2) Upsert desde la recepción (SECURITY DEFINER: bodega no escribe la tabla)
CREATE OR REPLACE FUNCTION fn_upsert_producto_proveedor(
    p_proveedor_id bigint,
    p_variante_id  bigint,
    p_costo        numeric
) RETURNS bigint
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
    v_id bigint;
BEGIN
    INSERT INTO producto_proveedor (proveedor_id, producto_variante_id, costo)
    VALUES (p_proveedor_id, p_variante_id, p_costo)
    ON CONFLICT ON CONSTRAINT uq_producto_proveedor
    DO UPDATE SET costo = EXCLUDED.costo, activo = true
    RETURNING id INTO v_id;
    RETURN v_id;
END $$;

-- EXECUTE solo para los roles que registran recepciones (/api/compras/**)
REVOKE ALL ON FUNCTION fn_upsert_producto_proveedor(bigint, bigint, numeric) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION fn_upsert_producto_proveedor(bigint, bigint, numeric)
    TO grp_administrador, grp_gerente, grp_compras, grp_bodega;

-- ── 3) Derivación histórica desde las recepciones reales (una vez) ──────────
INSERT INTO producto_proveedor (proveedor_id, producto_variante_id, costo)
SELECT DISTINCT ON (oc.proveedor_id, ocd.producto_variante_id)
       oc.proveedor_id, ocd.producto_variante_id, ocd.precio_unitario
FROM recepcion_detalle rd
JOIN recepcion_mercancia rm ON rm.id = rd.recepcion_mercancia_id
JOIN orden_compra_detalle ocd ON ocd.id = rd.orden_compra_detalle_id
JOIN orden_compra oc ON oc.id = rm.orden_compra_id
WHERE rd.cantidad_recibida > 0
ORDER BY oc.proveedor_id, ocd.producto_variante_id, rm.fecha_creacion DESC, rd.id DESC
ON CONFLICT ON CONSTRAINT uq_producto_proveedor DO NOTHING;

COMMIT;
