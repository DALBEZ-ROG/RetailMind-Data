-- ============================================================================
-- 113_grant_admin_historial_devolucion.sql — RetailMind
--     el ADMIN no podía abrir una devolución  (2026-08-25)
--
--   docker compose exec -T postgres psql -U postgres -d retailmind \
--       -v ON_ERROR_STOP=1 < retailmind/sql/postgres/113_grant_admin_historial_devolucion.sql
--
-- ---------------------------------------------------------------------------
-- EL DEFECTO
-- ---------------------------------------------------------------------------
-- `grp_administrador` no tenía NI UN GRANT sobre `historial_estado_devolucion`.
-- Lo tenían los otros siete grupos del pipeline —gerente, soporte, bodega,
-- despacho, vendedor, analista y cliente—; el administrador, no. Es una
-- omisión del script 38 (RMA), no una decisión: su propio bloque de RLS SÍ
-- incluyó a `grp_administrador` en `pol_horario`, o sea que la política se
-- escribió contando con que ese rol leería la tabla.
--
-- Medido contra el sistema vivo antes de este script:
--
--     GET /api/devoluciones/{id}          ADMIN 403 · GERENTE 200 · SOPORTE 200
--     GET /api/devoluciones/{id}/guia-pdf ADMIN 403 · GERENTE 200 · SOPORTE 200
--
-- El 403 es un SQLSTATE 42501 traducido por `GlobalExceptionHandler`; el log
-- dice «permission denied for table historial_estado_devolucion». La pantalla
-- de devoluciones y el PDF de la guía de retorno estaban cerrados para el único
-- rol que puede ejecutar TODAS las transiciones del RMA — `SecurityConfig` pone
-- a ADMIN en las seis: revisión, aprobación, tránsito, recepción, inspección y
-- reembolso.
--
-- ---------------------------------------------------------------------------
-- POR QUÉ SON DOS GRANTS Y NO UNO
-- ---------------------------------------------------------------------------
-- El GRANT sobre la tabla arregla la LECTURA y deja la ESCRITURA rota: el
-- `id` de esta tabla es un `serial` con `DEFAULT nextval(...)`, y un serial
-- exige **USAGE sobre su secuencia** aparte del INSERT sobre la tabla. Sin la
-- segunda línea, el admin abriría el detalle sin problema y la primera
-- transición que intentara moriría con «permission denied for sequence».
--
-- Y es la única de su familia a la que le pasa: `meta_venta`, `novedad_envio`,
-- `item_defectuoso`, `devolucion_proveedor`, `historial_devolucion_proveedor` y
-- `devolucion_proveedor_detalle` son columnas **IDENTITY** (`attidentity='d'`),
-- y ahí PostgreSQL no comprueba privilegios de secuencia: el INSERT sobre la
-- tabla basta. Por eso `has_sequence_privilege` dice `false` en las seis y
-- todas funcionan — **ese predicado NO es el oráculo de esta pregunta**.
-- El único oráculo fiable es ejecutar el INSERT, y eso es lo que hace la
-- guardia 3.
--
-- ---------------------------------------------------------------------------
-- QUÉ NO HACE ESTE SCRIPT
-- ---------------------------------------------------------------------------
--  · NO toca la RLS. `pol_horario` ya enumera a `grp_administrador` sobre esta
--    tabla desde el script 38, así que no hace falta política nueva. (Ese es el
--    otro medio arreglo posible: conceder el GRANT sobre una tabla con RLS y
--    sin política deja al rol leyendo CERO FILAS, en silencio y sin error.)
--  · NO da UPDATE ni DELETE. El historial es un rastro: se escribe y no se
--    corrige. Ningún rol tiene esos dos privilegios sobre esta tabla y el
--    administrador tampoco los tendrá.
--  · NO toca ningún otro rol ni ninguna otra tabla. Auditadas las 113: es la
--    ÚNICA en la que `grp_administrador` no tenía privilegio alguno teniéndolo
--    otros grupos.
--
-- IDEMPOTENTE: un GRANT repetido no hace nada.
-- TRANSACCIONAL con guardias: si el privilegio efectivo no queda puesto, o si
-- se movió algo que no tocaba, ABORTA y no deja nada aplicado.
-- ============================================================================

\set ON_ERROR_STOP on

BEGIN;

-- Foto del ANTES, para poder exigir después que solo cambió lo previsto.
CREATE TEMP TABLE _antes_113 ON COMMIT DROP AS
SELECT grantee, privilege_type
FROM information_schema.role_table_grants
WHERE table_schema = 'public' AND table_name = 'historial_estado_devolucion';

-- ── LA CORRECCIÓN ───────────────────────────────────────────────────────────
-- Los mismos dos privilegios que tienen gerente y soporte: leer el rastro y
-- añadirle un hito. Ni uno más.
GRANT SELECT, INSERT ON TABLE public.historial_estado_devolucion
    TO grp_administrador;

-- La otra mitad, sin la cual el INSERT falla: la tabla es `serial`.
GRANT USAGE ON SEQUENCE public.historial_estado_devolucion_id_seq
    TO grp_administrador;

-- ── GUARDIA 1 · el privilegio EFECTIVO quedó puesto ─────────────────────────
-- Se comprueba el privilegio efectivo y no el texto del ACL: un GRANT
-- ejecutado por quien no es propietario NO FALLA —emite un WARNING y no hace
-- nada—, así que sin esta guardia el script terminaría diciendo «listo» sin
-- haber cambiado el motor (misma lección que `fn_admin_cambiar_permiso`,
-- script 86).
DO $$
BEGIN
    IF NOT has_table_privilege('grp_administrador',
                               'public.historial_estado_devolucion', 'SELECT') THEN
        RAISE EXCEPTION 'ABORTA: grp_administrador sigue sin SELECT sobre historial_estado_devolucion';
    END IF;
    IF NOT has_table_privilege('grp_administrador',
                               'public.historial_estado_devolucion', 'INSERT') THEN
        RAISE EXCEPTION 'ABORTA: grp_administrador sigue sin INSERT sobre historial_estado_devolucion';
    END IF;
    IF NOT has_sequence_privilege('grp_administrador',
                                  'public.historial_estado_devolucion_id_seq', 'USAGE') THEN
        RAISE EXCEPTION 'ABORTA: falta USAGE sobre la secuencia; el INSERT fallaria igual';
    END IF;
    RAISE NOTICE 'Guardia 1 OK: SELECT + INSERT sobre la tabla y USAGE sobre su secuencia.';
END $$;

-- ── GUARDIA 2 · no se concedió NADA más ─────────────────────────────────────
DO $$
DECLARE sobra text;
BEGIN
    -- Lo único que puede haber aparecido es (grp_administrador, SELECT|INSERT).
    SELECT string_agg(g.grantee || ':' || g.privilege_type, ', ')
      INTO sobra
    FROM information_schema.role_table_grants g
    WHERE g.table_schema = 'public'
      AND g.table_name = 'historial_estado_devolucion'
      AND NOT EXISTS (SELECT 1 FROM _antes_113 a
                      WHERE a.grantee = g.grantee AND a.privilege_type = g.privilege_type)
      AND NOT (g.grantee = 'grp_administrador'
               AND g.privilege_type IN ('SELECT', 'INSERT'));
    IF sobra IS NOT NULL THEN
        RAISE EXCEPTION 'ABORTA: aparecieron privilegios no previstos: %', sobra;
    END IF;

    -- Y no puede haberse PERDIDO ninguno de los que ya estaban.
    SELECT string_agg(a.grantee || ':' || a.privilege_type, ', ')
      INTO sobra
    FROM _antes_113 a
    WHERE NOT EXISTS (SELECT 1 FROM information_schema.role_table_grants g
                      WHERE g.table_schema = 'public'
                        AND g.table_name = 'historial_estado_devolucion'
                        AND g.grantee = a.grantee AND g.privilege_type = a.privilege_type);
    IF sobra IS NOT NULL THEN
        RAISE EXCEPTION 'ABORTA: se perdieron privilegios que ya estaban: %', sobra;
    END IF;

    -- El administrador NO gana escritura destructiva sobre un rastro.
    IF has_table_privilege('grp_administrador',
                           'public.historial_estado_devolucion', 'UPDATE')
       OR has_table_privilege('grp_administrador',
                              'public.historial_estado_devolucion', 'DELETE') THEN
        RAISE EXCEPTION 'ABORTA: el historial es un rastro; nadie debe poder corregirlo';
    END IF;
    RAISE NOTICE 'Guardia 2 OK: solo SELECT e INSERT para grp_administrador; nada perdido.';
END $$;

-- ── GUARDIA 3 · el INSERT funciona DE VERDAD bajo el rol ────────────────────
-- Es la única comprobación que vale para la mitad de la secuencia: los
-- predicados `has_*_privilege` dan `false` en seis tablas IDENTITY que insertan
-- perfectamente, así que no distinguen «le falta el privilegio» de «no lo
-- necesita». Se ESCRIBE una fila real bajo `grp_administrador`, pasando por la
-- RLS, y se borra acto seguido.
DO $$
DECLARE
    v_dev  bigint;
    v_id   bigint;
BEGIN
    SELECT id INTO v_dev FROM devolucion ORDER BY id LIMIT 1;
    IF v_dev IS NULL THEN
        RAISE NOTICE 'Guardia 3 OMITIDA: no hay ninguna devolucion contra la que probar.';
        RETURN;
    END IF;

    -- `set_config('role', ..., true)` y no `SET LOCAL ROLE `+nombre: el mismo
    -- camino que usa la aplicacion, con el rol como PARAMETRO LIGADO.
    PERFORM set_config('role', 'grp_administrador', true);

    INSERT INTO historial_estado_devolucion (devolucion_id, estado, comentario)
    VALUES (v_dev, 'solicitada', '[113] prueba de privilegio, se borra en el acto')
    RETURNING id INTO v_id;

    PERFORM set_config('role', 'none', true);
    DELETE FROM historial_estado_devolucion WHERE id = v_id;

    RAISE NOTICE 'Guardia 3 OK: el ADMIN escribe un hito de verdad (fila % creada y borrada).', v_id;
END $$;

-- ── GUARDIA 4 · la RLS de la tabla no se tocó ───────────────────────────────
DO $$
DECLARE n_pol int; con_rls boolean;
BEGIN
    SELECT relrowsecurity INTO con_rls
    FROM pg_class WHERE oid = 'public.historial_estado_devolucion'::regclass;
    IF NOT con_rls THEN
        RAISE EXCEPTION 'ABORTA: la tabla se quedo sin RLS';
    END IF;

    SELECT count(*) INTO n_pol
    FROM pg_policy WHERE polrelid = 'public.historial_estado_devolucion'::regclass;
    IF n_pol <> 3 THEN
        RAISE EXCEPTION 'ABORTA: la tabla deberia tener 3 politicas (horario, soporte, cliente) y tiene %', n_pol;
    END IF;

    -- Y `grp_administrador` tiene que seguir cubierto por `pol_horario`: con
    -- GRANT y sin politica, un rol lee CERO FILAS sin un solo error.
    IF NOT EXISTS (
        SELECT 1 FROM pg_policy p
        WHERE p.polrelid = 'public.historial_estado_devolucion'::regclass
          AND p.polname = 'pol_horario'
          AND 'grp_administrador'::regrole::oid = ANY(p.polroles)) THEN
        RAISE EXCEPTION 'ABORTA: grp_administrador no esta en pol_horario; leeria cero filas en silencio';
    END IF;
    RAISE NOTICE 'Guardia 4 OK: RLS activa, 3 politicas y el admin dentro de pol_horario.';
END $$;

COMMIT;

\echo ''
\echo 'LISTO. El ADMIN ya puede abrir una devolucion y su guia de retorno.'
\echo 'Se concedieron DOS privilegios y ni uno mas: SELECT+INSERT sobre'
\echo 'historial_estado_devolucion y USAGE sobre su secuencia.'
\echo 'Verifica con:  py -3 pruebas/p17_mejoras.py E3   y   py -3 pruebas/p03_motor.py E3'
