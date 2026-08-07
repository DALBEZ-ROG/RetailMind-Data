-- ============================================================================
-- 88_horario_24_7.sql — RetailMind · ventanas de `grupo_horario` a 24/7
--                        para TODOS los roles y los 7 dias (2026-08-06)
--
-- ---------------------------------------------------------------------------
-- POR QUE LAS VENTANAS ESTAN ABIERTAS  (leelo antes de sacar conclusiones)
-- ---------------------------------------------------------------------------
-- **La restriccion por horario NO se ha eliminado ni se ha debilitado. Sigue
-- ARMADA, entera, y se puede volver a activar en un solo UPDATE.** Lo unico que
-- cambia este script son las FILAS DE DATOS de `grupo_horario`.
--
-- Que NO se toca, y se verifica que no cambia:
--   * `esta_en_horario(text)`        - STABLE SECURITY DEFINER, anclada a
--                                      America/Guayaquil, admin exento.
--   * `fn_grupo_actual()`
--   * `fn_bloquear_fuera_horario()`
--   * los 34 triggers `trg_horario_*` STATEMENT-level sobre las tablas
--     operativas, que siguen lanzando SQLSTATE 42501.
--   * las 50 politicas RLS `pol_horario`, que siguen declaradas `cmd = ALL`.
--   * ni un GRANT.
--
-- El motivo del cambio es de PRESENTACION, no de diseno. El sistema se muestra
-- en una feria academica en fecha y hora aun desconocidas, y las ventanas
-- vigentes dejaban a los seis roles de personal (bodega, compras, despacho,
-- vendedor, gerente, analista) bloqueados **1.986-1.988 minutos de cada 10.080,
-- el 19,7 % de la semana**: los martes fuera de 08:00-18:00 y los sabados fuera
-- de 08:00-13:00. Si la demostracion caia dentro de esa franja, las pantallas
-- de personal no habrian mostrado NADA, y ademas **sin un solo mensaje de
-- error**: `pol_horario` esta declarada `cmd = ALL`, y ALL incluye SELECT, asi
-- que RLS no rechaza la consulta - la filtra y devuelve CERO FILAS en silencio.
-- Un fallo mudo delante de un tribunal es el peor de los fallos posibles.
--
-- Para DEMOSTRAR EN VIVO que la restriccion funciona estan los scripts
-- hermanos, que este archivo no ejecuta:
--   * `89_horario_demo_restringir.sql`  - estrecha la ventana de UN rol a un
--                                         rango que excluye el momento actual.
--   * `90_horario_demo_restaurar.sql`   - deja el sistema como lo deja este.
--
-- ---------------------------------------------------------------------------
-- LA FRONTERA: por que `24:00:00` y no `23:59` ni `23:59:59`
-- ---------------------------------------------------------------------------
-- `esta_en_horario()` compara asi (verbatim de `pg_get_functiondef`):
--
--     AND (now() AT TIME ZONE 'America/Guayaquil')::time >= gh.hora_inicio
--     AND (now() AT TIME ZONE 'America/Guayaquil')::time <  gh.hora_fin
--
-- Es un intervalo SEMIABIERTO `[hora_inicio, hora_fin)`: el limite inferior
-- entra y el superior NO. De ahi salen los minutos muertos que tenia la tabla:
-- con `hora_fin = 23:59` el minuto 23:59 quedaba FUERA, y las filas con
-- `hora_inicio = 00:01` dejaban fuera el minuto 00:00.
--
-- El dominio de `(timestamptz AT TIME ZONE ...)::time` es `[00:00:00,
-- 24:00:00)`. El instante mas alto representable de un dia es
-- `23:59:59.999999`, y un literal de timestamp `24:00:00` NO produce ese valor:
-- PostgreSQL lo normaliza al dia siguiente a las `00:00:00` (comprobado). Es
-- decir, **un cast desde un instante real NUNCA puede valer `24:00:00`**.
--
-- Por eso la unica frontera que no deja ni un microsegundo fuera es:
--
--     hora_inicio = 00:00:00     hora_fin = 24:00:00
--
-- `time '24:00:00'` es un valor VALIDO del tipo `time` (es su maximo) y cumple
-- el CHECK `grupo_horario_check (hora_inicio < hora_fin)`. No es un invento
-- para este script: tres filas de `grp_soporte` ya lo usaban.
--
-- Cotejo de las tres candidatas contra el ultimo instante del dia:
--     23:59:59.999999 <  23:59:00  -> FALSE  (deja 60 s fuera)
--     23:59:59.999999 <  23:59:59  -> FALSE  (deja 1 s fuera)
--     23:59:59.999999 <  24:00:00  -> TRUE   <- la elegida
--
-- ---------------------------------------------------------------------------
-- NOTAS DE ESTRUCTURA (inspeccionada, no supuesta)
-- ---------------------------------------------------------------------------
--   * `grupo_horario` = (id, rol_grupo, dia_semana, hora_inicio, hora_fin,
--     activo, fecha_creacion). Grano: UNA fila por (rol_grupo, dia_semana).
--     Hoy son 56 filas = 8 roles x 7 dias.
--   * `id` es **GENERATED ALWAYS AS IDENTITY**: los INSERT de aqui lo OMITEN.
--     Restaurar el respaldo exigiria `OVERRIDING SYSTEM VALUE`.
--   * **NO existe UNIQUE (rol_grupo, dia_semana)** - solo la PK sobre `id`.
--     Por eso la idempotencia NO puede apoyarse en `ON CONFLICT`: se hace con
--     un UPDATE acotado por WHERE y un INSERT ... WHERE NOT EXISTS.
--   * `dia_semana` es smallint 0-6 con CHECK, y coincide con
--     `EXTRACT(DOW ...)`: 0 = domingo ... 6 = sabado.
--   * `grupo_horario` NO tiene trigger `trg_horario_*`, asi que modificarla no
--     depende de la propia compuerta que gobierna.
--
-- IDEMPOTENTE: correrlo dos veces seguidas deja EXACTAMENTE el mismo estado.
--   El respaldo solo se llena la PRIMERA vez (si ya tiene filas no se vuelve a
--   escribir), de modo que una segunda ejecucion no pisa la foto del estado
--   ORIGINAL con la foto del estado ya modificado.
--
-- TRANSACCIONAL con guardia: si al final quedara un solo minuto sin cubrir, o
--   una sola fila fuera de la frontera, el bloque final lanza EXCEPTION y todo
--   el script hace ROLLBACK. No puede confirmarse un estado a medias.
--
-- Ejecutar como postgres (superusuario) sobre la BD retailmind del CONTENEDOR:
--   docker exec -i retailmind-postgres-1 psql -U postgres -d retailmind \
--       -v ON_ERROR_STOP=1 < retailmind/sql/postgres/88_horario_24_7.sql
-- ============================================================================
\set ON_ERROR_STOP on

BEGIN;

-- ── 1. Respaldo del estado ACTUAL (solo la primera vez) ─────────────────────

CREATE SCHEMA IF NOT EXISTS seed_backup;

CREATE TABLE IF NOT EXISTS seed_backup.hor88_grupo_horario_20260806 (
    id             integer,
    rol_grupo      text,
    dia_semana     smallint,
    hora_inicio    time,
    hora_fin       time,
    activo         boolean,
    fecha_creacion timestamptz,
    respaldado_en  timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE seed_backup.hor88_grupo_horario_20260806 IS
    'Foto de public.grupo_horario ANTES de abrir las ventanas a 24/7 '
    '(script 88, 2026-08-06). Restaurar exige OVERRIDING SYSTEM VALUE: '
    'grupo_horario.id es GENERATED ALWAYS AS IDENTITY.';

-- El WHERE NOT EXISTS es lo que hace idempotente al respaldo: en la segunda
-- ejecucion la tabla ya tiene filas y este INSERT no escribe ninguna, con lo
-- que la foto conservada sigue siendo la del estado ORIGINAL.
INSERT INTO seed_backup.hor88_grupo_horario_20260806
    (id, rol_grupo, dia_semana, hora_inicio, hora_fin, activo, fecha_creacion)
SELECT gh.id, gh.rol_grupo, gh.dia_semana, gh.hora_inicio, gh.hora_fin,
       gh.activo, gh.fecha_creacion
FROM public.grupo_horario gh
WHERE NOT EXISTS (SELECT 1 FROM seed_backup.hor88_grupo_horario_20260806);

-- ── 2. Abrir las ventanas existentes ────────────────────────────────────────
-- El WHERE deja el UPDATE en 0 filas cuando ya esta aplicado: la idempotencia
-- se puede VER en el conteo, no solo afirmarse.

UPDATE public.grupo_horario
SET    hora_inicio = time '00:00:00',
       hora_fin    = time '24:00:00',
       activo      = true
WHERE  hora_inicio IS DISTINCT FROM time '00:00:00'
   OR  hora_fin    IS DISTINCT FROM time '24:00:00'
   OR  activo      IS DISTINCT FROM true;

-- ── 3. Completar los (rol, dia) que faltaran ────────────────────────────────
-- Hoy la tabla esta completa (8 roles x 7 dias = 56) y esto no inserta nada.
-- Se deja escrito para que el script siga siendo correcto si manana alguien
-- crea un rol con menos de 7 ventanas (los roles personalizados del script 87
-- nacen con las 7, pero eso no lo garantiza esta tabla: no hay restriccion que
-- lo imponga). `id` se OMITE: es GENERATED ALWAYS AS IDENTITY.

INSERT INTO public.grupo_horario (rol_grupo, dia_semana, hora_inicio, hora_fin, activo)
SELECT r.rol_grupo, d.dia, time '00:00:00', time '24:00:00', true
FROM   (SELECT DISTINCT rol_grupo FROM public.grupo_horario) r
CROSS  JOIN generate_series(0, 6) AS d(dia)
WHERE  NOT EXISTS (
           SELECT 1 FROM public.grupo_horario gh
           WHERE gh.rol_grupo = r.rol_grupo
             AND gh.dia_semana = d.dia::smallint);

-- ── 4. Guardia: 0 minutos bloqueados, o ROLLBACK ────────────────────────────
-- Se comprueban las tres cosas por separado porque fallan por motivos
-- distintos: la FORMA de cada fila, la COBERTURA de los 7 dias por rol, y el
-- barrido minuto a minuto con la MISMA condicion que usa esta_en_horario().

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

    -- Barrido de los 10.080 minutos de la semana, rol por rol.
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

    RAISE NOTICE 'Guardia OK: 0 minutos bloqueados de 10080 para todos los roles.';
END
$guardia$;

COMMIT;
