-- =====================================================================
-- 66_seed_bloque_c_metas.sql
-- SIEMBRA DE VOLUMEN HISTORICO - BLOQUE C / Parte 3: METAS DE VENTA
-- ---------------------------------------------------------------------
-- Objetivo tactico OTD-VEN-15 (cumplimiento contra meta). La tabla
-- meta_venta (script 48) esta vacia: nadie captura 18 meses a mano.
--
-- Que hace:
--   * Una meta mensual 'general' (ambito global) y otra 'ventas'
--     (departamento comercial = canal interno tienda/telefono) por cada
--     uno de los meses ene-2025 .. jul-2026 (19 meses inclusive) => 38 metas.
--   * CORRELACION OTD-VEN-15: cada meta se deriva de la VENTA REAL ya
--     sembrada de ese mes, multiplicada por un factor en [0.85,1.15] y
--     redondeada a miles. Los factores estan tuneados para que unos meses
--     la venta SUPERE la meta (cumplimiento > 100%) y otros quede por
--     DEBAJO, con variacion mes a mes (nada de metas planas ni
--     desconectadas).
--   * BASE = FACTURADO del mes (factura_venta.total no anulada, por
--     fecha_emision): es la MISMA metrica de "venta real" que calcula el
--     informe (MetasVentaService.VENTA_REAL_SQL), que ademas usa la MISMA
--     base para 'general' y para 'ventas'. Por eso ambas metas se derivan
--     del facturado total del mes (cumplimiento = facturado/meta = 1/factor
--     cae en banda), diferenciandose solo por su factor de ambicion.
--   * Autor (fijada_por): 'general' -> admin (id 2), 'ventas' -> gerente
--     (id 6). Ambos con rol de Gerencia/Administracion (requisito).
--
-- meta_venta admite periodos pasados a proposito (script 48): NO se valida
-- futuro. Sin columnas GENERATED; solo trigger touch on UPDATE (no aplica).
-- Tag [SEED-BC] en notas. Marca 'seed_bc_66_metas'. Idempotente/
-- transaccional. Ejecutar como postgres.
-- =====================================================================

BEGIN;

DO $$
DECLARE
    v_thr    jsonb;
    -- factor meta/real por mes (idx 1 = ene-2025 .. idx 19 = jul-2026;
    -- ene-2025..jul-2026 inclusive = 19 meses)
    gen_f numeric[] := ARRAY[0.92,1.05,0.97,1.08,0.90,1.03,1.10,0.95,1.02,0.98,1.06,0.88,1.09,1.01,0.94,0.99,0.91,1.04,0.96];
    ven_f numeric[] := ARRAY[1.04,0.93,1.07,0.96,1.10,0.91,0.98,1.05,0.94,1.08,0.97,1.11,0.90,0.99,1.06,0.95,1.02,0.89,1.03];
    idx int := 0;
    y int; mo int;
    v_real numeric;
    v_meta_gen numeric; v_meta_ven numeric;
    v_n int := 0;
BEGIN
    IF EXISTS (SELECT 1 FROM configuracion_tienda WHERE clave='seed_bc_66_metas') THEN
        RAISE NOTICE 'Bloque C / 66 (metas) ya sembrado; se omite.';
        RETURN;
    END IF;

    v_thr := jsonb_build_object('meta_venta', (SELECT COALESCE(max(id),0) FROM meta_venta));

    FOR y IN 2025..2026 LOOP
      FOR mo IN 1..12 LOOP
        EXIT WHEN (y=2026 AND mo>7);
        idx := idx + 1;

        -- venta real del mes = FACTURADO (misma metrica del informe OTD-VEN-15)
        SELECT COALESCE(sum(fv.total),0)
          INTO v_real
        FROM factura_venta fv
        WHERE fv.estado <> 'anulada'
          AND fv.fecha_emision >= make_date(y,mo,1)
          AND fv.fecha_emision <  make_date(y,mo,1) + interval '1 month';

        -- meta = facturado * factor, redondeada a miles (>0 garantizado)
        v_meta_gen := GREATEST(1000, round(v_real * gen_f[idx], -3));
        v_meta_ven := GREATEST(1000, round(v_real * ven_f[idx], -3));

        INSERT INTO meta_venta (anio, mes, departamento, monto_meta, notas, fijada_por, activo, fecha_creacion)
        VALUES (y, mo, 'general', v_meta_gen,
                '[SEED-BC] Meta global mensual fijada por gerencia',
                2, true, make_timestamptz(y,mo,1,9,0,0,'America/Guayaquil'));

        INSERT INTO meta_venta (anio, mes, departamento, monto_meta, notas, fijada_por, activo, fecha_creacion)
        VALUES (y, mo, 'ventas', v_meta_ven,
                '[SEED-BC] Meta mensual del departamento de ventas',
                6, true, make_timestamptz(y,mo,1,9,0,0,'America/Guayaquil'));

        v_n := v_n + 2;
      END LOOP;
    END LOOP;

    INSERT INTO configuracion_tienda (clave, valor, tipo_dato, descripcion, fecha_creacion)
    VALUES ('seed_bc_66_metas', v_thr::text, 'json',
            'Bloque C/66: metas mensuales general+ventas 18 meses derivadas de la venta real (85-115%). Reversion: borrar id>umbral.',
            now());

    RAISE NOTICE 'Bloque C / 66 OK. Metas sembradas: % (19 general + 19 ventas).', v_n;
END $$;

COMMIT;
