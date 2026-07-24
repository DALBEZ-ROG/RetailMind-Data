-- =====================================================================
-- 99_revert_bloque_c.sql   (NO se ejecuta en la siembra; es la reversion)
-- Deshace por completo la SIEMBRA BLOQUE C (scripts 64-66) usando los
-- umbrales max(id) PRE-siembra guardados en configuracion_tienda, en
-- orden seguro de FKs.
--
-- Bloque C SOLO agrega marketing y metas: NO toco pedidos, pagos, stock,
-- kardex ni nada de lo real / Bloque A / Bloque B. Por tanto esta
-- reversion NO restaura stock ni totales: solo borra las filas sembradas
-- (id > umbral) en cupon/uso_cupon/promocion/promocion_producto/campana/
-- banner/meta_venta y quita las marcas. Los 6 cupones, 3 usos, 1
-- promocion, 1 campana y 1 banner reales/previos quedan intactos (sus
-- usos_actuales nunca se tocaron). Ejecutar como postgres.
-- =====================================================================

BEGIN;

DO $$
DECLARE
    t64 jsonb; t65 jsonb; t66 jsonb;
BEGIN
    SELECT valor::jsonb INTO t64 FROM configuracion_tienda WHERE clave='seed_bc_64_cupones';
    SELECT valor::jsonb INTO t65 FROM configuracion_tienda WHERE clave='seed_bc_65_marketing';
    SELECT valor::jsonb INTO t66 FROM configuracion_tienda WHERE clave='seed_bc_66_metas';

    IF t64 IS NULL AND t65 IS NULL AND t66 IS NULL THEN
        RAISE NOTICE 'No hay marca de Bloque C (64/65/66); nada que revertir.';
        RETURN;
    END IF;

    -- ---- 66: metas -------------------------------------------------
    IF t66 IS NOT NULL THEN
        DELETE FROM meta_venta WHERE id > (t66->>'meta_venta')::bigint;
    END IF;

    -- ---- 65: marketing (hijos antes que padres) --------------------
    IF t65 IS NOT NULL THEN
        DELETE FROM promocion_producto WHERE id > (t65->>'promocion_producto')::bigint;
        DELETE FROM banner             WHERE id > (t65->>'banner')::bigint;
        DELETE FROM promocion          WHERE id > (t65->>'promocion')::bigint;
        DELETE FROM campana            WHERE id > (t65->>'campana')::bigint;
    END IF;

    -- ---- 64: cupones (usos antes que cupon por FK RESTRICT) --------
    IF t64 IS NOT NULL THEN
        DELETE FROM uso_cupon WHERE id > (t64->>'uso_cupon')::bigint;
        DELETE FROM cupon     WHERE id > (t64->>'cupon')::bigint;
    END IF;

    -- ---- quitar marcas ---------------------------------------------
    DELETE FROM configuracion_tienda WHERE clave LIKE 'seed_bc_%';

    RAISE NOTICE 'Bloque C revertido por completo (scripts 64-66).';
END $$;

COMMIT;
