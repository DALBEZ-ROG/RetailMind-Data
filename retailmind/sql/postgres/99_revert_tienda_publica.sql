-- ============================================================================
-- 99_revert_tienda_publica.sql — deshace el script 112  (2026-08-21)
--
--   docker compose exec -T postgres psql -U postgres -d retailmind \
--       -v ON_ERROR_STOP=1 < retailmind/sql/postgres/99_revert_tienda_publica.sql
--
-- Deja el motor como estaba: sin rol de visitante, sin tabla de intereses y sin
-- función de alta pública. NO toca ninguna otra política, ventana ni GRANT.
--
-- OJO al orden: un rol no se puede eliminar mientras conserve un privilegio o
-- sea miembro de algo, y PostgreSQL no lo dice con claridad —el mensaje habla
-- de «objetos dependientes» sin nombrarlos—. Por eso se revoca todo primero.
--
-- AVISO: si ya hay clientes registrados por la tienda pública, sus cuentas NO
-- se borran (son usuarios de pleno derecho, con sus pedidos detrás). Lo único
-- que se pierde son sus INTERESES, que es dato declarativo y opcional.
-- ============================================================================

\set ON_ERROR_STOP on

BEGIN;

DROP FUNCTION IF EXISTS fn_registrar_cliente(
    text, text, text, text, text, text, text, date, text, boolean);

DROP TABLE IF EXISTS cliente_categoria_interes;

DROP POLICY IF EXISTS pol_visitante_catalogo ON inventario;

REVOKE SELECT ON producto, producto_variante, producto_categoria,
                 categoria, marca, inventario FROM grp_visitante;
REVOKE USAGE ON SCHEMA public FROM grp_visitante;

DELETE FROM grupo_horario WHERE rol_grupo = 'grp_visitante';

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'grp_visitante') THEN
        REVOKE grp_visitante FROM retailmind_app;
        EXECUTE 'DROP OWNED BY grp_visitante';
        DROP ROLE grp_visitante;
        RAISE NOTICE 'Rol grp_visitante eliminado.';
    END IF;
END $$;

COMMIT;

-- Guardia: que no quede ni rastro.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'grp_visitante') THEN
        RAISE EXCEPTION 'ABORTA: grp_visitante sigue existiendo';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_proc WHERE proname = 'fn_registrar_cliente') THEN
        RAISE EXCEPTION 'ABORTA: fn_registrar_cliente sigue existiendo';
    END IF;
    IF to_regclass('public.cliente_categoria_interes') IS NOT NULL THEN
        RAISE EXCEPTION 'ABORTA: cliente_categoria_interes sigue existiendo';
    END IF;
    RAISE NOTICE 'Reversion completa: el script 112 queda deshecho.';
END $$;

\echo ''
\echo 'LISTO. El motor vuelve al estado anterior al script 112.'
\echo 'Recuerda que la aplicacion tambien hay que revertirla: sin grp_visitante,'
\echo 'el catalogo publico responde 500 en cuanto entra un visitante anonimo.'
