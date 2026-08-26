-- ============================================================================
-- 99_revert_grant_admin_historial.sql — deshace el script 113  (2026-08-25)
--
--   docker compose exec -T postgres psql -U postgres -d retailmind \
--       -v ON_ERROR_STOP=1 < retailmind/sql/postgres/99_revert_grant_admin_historial.sql
--
-- Devuelve `historial_estado_devolucion` al estado en que la dejó el script 38:
-- `grp_administrador` sin privilegio alguno sobre ella ni sobre su secuencia.
--
-- OJO CON LO QUE ESO SIGNIFICA: revertir REINTRODUCE el defecto — el ADMIN
-- vuelve a recibir 403 en `GET /api/devoluciones/{id}` y en la guía de retorno.
-- Está aquí porque todo cambio de privilegios del proyecto lleva su marcha
-- atrás, no porque haya un motivo para usarlo.
-- ============================================================================

\set ON_ERROR_STOP on

BEGIN;

REVOKE SELECT, INSERT ON TABLE public.historial_estado_devolucion
    FROM grp_administrador;

REVOKE USAGE ON SEQUENCE public.historial_estado_devolucion_id_seq
    FROM grp_administrador;

-- Guardia: la reversión tiene que haber quitado LO SUYO y nada más. Los otros
-- siete grupos conservan lo que el script 38 les dio.
DO $$
DECLARE faltan text;
BEGIN
    IF has_table_privilege('grp_administrador',
                           'public.historial_estado_devolucion', 'SELECT')
       OR has_table_privilege('grp_administrador',
                              'public.historial_estado_devolucion', 'INSERT') THEN
        RAISE EXCEPTION 'ABORTA: grp_administrador conserva privilegios sobre la tabla';
    END IF;

    SELECT string_agg(r, ', ') INTO faltan
    FROM unnest(ARRAY['grp_gerente', 'grp_soporte', 'grp_bodega', 'grp_despacho',
                      'grp_vendedor', 'grp_analista', 'grp_cliente']) r
    WHERE NOT has_table_privilege(r, 'public.historial_estado_devolucion', 'SELECT');
    IF faltan IS NOT NULL THEN
        RAISE EXCEPTION 'ABORTA: la reversion se llevo por delante a: %', faltan;
    END IF;
    RAISE NOTICE 'Reversion OK: solo grp_administrador perdio el privilegio.';
END $$;

COMMIT;

\echo ''
\echo 'REVERTIDO. El ADMIN vuelve a recibir 403 al abrir una devolucion.'
