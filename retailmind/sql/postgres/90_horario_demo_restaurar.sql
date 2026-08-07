-- ============================================================================
-- 90_horario_demo_restaurar.sql — RetailMind · deshacer la demostracion y
--                                  volver a 24/7 (2026-08-06)
--
-- Devuelve `grupo_horario` al estado EXACTO en que lo deja el script 88:
-- todas las filas, de todos los roles, en `[00:00:00, 24:00:00)` y activas.
--
-- Es el PASO 3 del guion de `89_horario_demo_restringir.sql`. Se puede correr
-- aunque el 89 no se haya ejecutado: no supone nada sobre el estado de partida,
-- ni sobre que rol se restringio, ni sobre cuantos. Repara lo que encuentre.
--
--   docker exec -i retailmind-postgres-1 psql -U postgres -d retailmind \
--       -v ON_ERROR_STOP=1 < retailmind/sql/postgres/90_horario_demo_restaurar.sql
--
-- ---------------------------------------------------------------------------
-- POR QUE `24:00:00`  (el razonamiento completo esta en el script 88)
-- ---------------------------------------------------------------------------
-- `esta_en_horario()` compara `t >= hora_inicio AND t < hora_fin`: intervalo
-- SEMIABIERTO. El instante mas alto que puede producir
-- `(now() AT TIME ZONE 'America/Guayaquil')::time` es `23:59:59.999999`, y solo
-- `hora_fin = 24:00:00` lo cubre - con `23:59` se pierde un minuto y con
-- `23:59:59` se pierde un segundo. `time '24:00:00'` es el maximo del tipo
-- `time` y cumple el CHECK `hora_inicio < hora_fin`.
--
-- NO TOCA el mecanismo: ni `esta_en_horario()`, ni `fn_grupo_actual()`, ni
-- `fn_bloquear_fuera_horario()`, ni los 34 triggers `trg_horario_*`, ni las 50
-- politicas `pol_horario` (`cmd = ALL`), ni un solo GRANT. Solo filas de datos.
--
-- IDEMPOTENTE: el UPDATE lleva WHERE, asi que la segunda ejecucion toca 0
--   filas. TRANSACCIONAL con la MISMA guardia del script 88: si quedara un solo
--   minuto sin cubrir, EXCEPTION y ROLLBACK.
--
-- No restaura desde `seed_backup.hor88_grupo_horario_20260806` A PROPOSITO: esa
--   tabla guarda las ventanas RESTRINGIDAS originales (martes 08-18, sabado
--   08-13), que es justo lo que no se quiere durante la feria. El respaldo esta
--   ahi para poder volver al horario de negocio real cuando la feria termine.
-- ============================================================================
\set ON_ERROR_STOP on

BEGIN;

-- ── 1. Todas las ventanas, de todos los roles, a cobertura completa ─────────

UPDATE public.grupo_horario
SET    hora_inicio = time '00:00:00',
       hora_fin    = time '24:00:00',
       activo      = true
WHERE  hora_inicio IS DISTINCT FROM time '00:00:00'
   OR  hora_fin    IS DISTINCT FROM time '24:00:00'
   OR  activo      IS DISTINCT FROM true;

-- ── 2. Completar los (rol, dia) que faltaran ────────────────────────────────
-- `id` se OMITE: es GENERATED ALWAYS AS IDENTITY.

INSERT INTO public.grupo_horario (rol_grupo, dia_semana, hora_inicio, hora_fin, activo)
SELECT r.rol_grupo, d.dia, time '00:00:00', time '24:00:00', true
FROM   (SELECT DISTINCT rol_grupo FROM public.grupo_horario) r
CROSS  JOIN generate_series(0, 6) AS d(dia)
WHERE  NOT EXISTS (
           SELECT 1 FROM public.grupo_horario gh
           WHERE gh.rol_grupo = r.rol_grupo
             AND gh.dia_semana = d.dia::smallint);

-- ── 3. Guardia: 0 minutos bloqueados, o ROLLBACK ────────────────────────────

DO $guardia$
DECLARE
    v_mal_formadas int;
    v_dias_faltantes int;
    v_bloqueados int;
    v_rol text;
BEGIN
    SELECT count(*) INTO v_mal_formadas
    FROM public.grupo_horario
    WHERE hora_inicio <> time '00:00:00'
       OR hora_fin    <> time '24:00:00'
       OR NOT activo;
    IF v_mal_formadas > 0 THEN
        RAISE EXCEPTION
            'ABORTA: % fila(s) de grupo_horario no quedaron en [00:00:00, 24:00:00) activas.',
            v_mal_formadas;
    END IF;

    SELECT count(*) INTO v_dias_faltantes
    FROM (SELECT DISTINCT rol_grupo FROM public.grupo_horario) r
    CROSS JOIN generate_series(0, 6) AS d(dia)
    WHERE NOT EXISTS (SELECT 1 FROM public.grupo_horario gh
                      WHERE gh.rol_grupo = r.rol_grupo
                        AND gh.dia_semana = d.dia::smallint);
    IF v_dias_faltantes > 0 THEN
        RAISE EXCEPTION
            'ABORTA: faltan % par(es) (rol, dia) en grupo_horario.', v_dias_faltantes;
    END IF;

    SELECT x.rol_grupo, x.bloq INTO v_rol, v_bloqueados
    FROM (
        SELECT r.rol_grupo,
               count(*) FILTER (WHERE NOT EXISTS (
                   SELECT 1 FROM public.grupo_horario gh
                   WHERE gh.rol_grupo = r.rol_grupo
                     AND gh.activo
                     AND gh.dia_semana = mi.dow::smallint
                     AND mi.t >= gh.hora_inicio
                     AND mi.t <  gh.hora_fin)) AS bloq
        FROM (SELECT DISTINCT rol_grupo FROM public.grupo_horario) r
        CROSS JOIN (
            SELECT d.dow, (time '00:00' + (m.m || ' minutes')::interval)::time AS t
            FROM generate_series(0, 6) AS d(dow),
                 generate_series(0, 1439) AS m(m)
        ) mi
        GROUP BY r.rol_grupo
    ) x
    WHERE x.bloq > 0
    ORDER BY x.bloq DESC
    LIMIT 1;

    IF v_bloqueados IS NOT NULL THEN
        RAISE EXCEPTION
            'ABORTA: el rol % sigue con % minuto(s) bloqueado(s) de 10080.',
            v_rol, v_bloqueados;
    END IF;

    RAISE NOTICE 'Guardia OK: 24/7 restaurado, 0 minutos bloqueados de 10080.';
END
$guardia$;

COMMIT;

\echo ''
\echo 'RESTAURADO. Todas las ventanas en [00:00:00, 24:00:00) para los 7 dias.'
\echo 'Recarga la pantalla: los datos vuelven.'
\echo ''
