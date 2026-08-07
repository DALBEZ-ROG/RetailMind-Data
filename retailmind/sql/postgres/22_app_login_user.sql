-- ============================================================================
-- 22_app_login_user.sql — RetailMind · Usuario LOGIN de la aplicacion
--  Crea retailmind_app: el UNICO usuario con el que se conecta el backend
--  (nunca postgres). Es NOINHERIT y miembro de los 8 grupos:
--    - Sin SET ROLE no hereda NINGUN privilegio de los grupos.
--    - El backend ejecuta SET LOCAL ROLE grp_x por transaccion segun el
--      rol del usuario autenticado (JWT), y el cambio muere con la tx.
--  Privilegios DIRECTOS minimos (bootstrap de autenticacion): solo las
--  tablas usuario / rol / usuario_rol, porque el login ocurre ANTES de
--  conocer el rol de la sesion.
--  Idempotente.
--
-- ---------------------------------------------------------------------------
-- COMO SE EJECUTA (la contrasena NO esta en este archivo)
-- ---------------------------------------------------------------------------
--   set -a; . ./.env; set +a
--   docker compose exec -T postgres \
--       psql -U postgres -d retailmind -v app_password="$PG_APP_PASSWORD" \
--       < retailmind/sql/postgres/22_app_login_user.sql
--
-- El valor VIVO es `PG_APP_PASSWORD` en el `.env` de la raiz, que es el mismo
-- que el compose le pasa al backend. `.env` esta fuera del indice de git.
--
-- Hasta el 2026-08-06 este archivo llevaba la contrasena EN CLARO, dos veces
-- (lineas 17 y 20). Y no era solo una fuga: ese literal habia quedado
-- OBSOLETO tras la rotacion de secretos del 2026-08-03, asi que volver a
-- ejecutar el script le habria puesto al rol una contrasena distinta de la
-- que usa el backend y habria tumbado la conexion entera. Al tomarla del
-- entorno, el script vuelve a ser idempotente de verdad.
--
-- DOS TRAMPAS de este mecanismo (las mismas que documenta 85_rol_etl.sql):
--   1) `:variable` NO se interpola dentro de `$$ ... $$`: psql sustituye a
--      nivel lexico y respeta el dollar-quoting, asi que un `:app_password`
--      dentro del bloque DO habria creado el rol con la contrasena literal
--      «:app_password». Por eso el rol nace SIN contrasena dentro del DO y la
--      recibe en el ALTER ROLE de fuera.
--   2) `\quit` SIEMPRE sale con codigo 0, asi que un guardia hecho solo con
--      `\quit` dejaria a un despliegue automatizado creyendo que fue bien. El
--      guardia lanza un RAISE con ON_ERROR_STOP activo, que aborta con codigo
--      distinto de cero ANTES de llegar al \quit.
-- ============================================================================

\set ON_ERROR_STOP on
\if :{?app_password}
\else
  \echo '[ERROR] Falta la variable `app_password`.'
  \echo '        Ejecutalo con:  psql ... -v app_password="$PG_APP_PASSWORD" < 22_app_login_user.sql'
  DO $guardia$ BEGIN
      RAISE EXCEPTION 'Falta la variable psql `app_password`: el script no se ejecuta.';
  END $guardia$;
  \quit
\endif

-- El rol nace SIN contrasena: dentro de `$$ ... $$` no hay interpolacion
-- (trampa 1 de la cabecera). La pone el ALTER ROLE de abajo, que corre SIEMPRE.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'retailmind_app') THEN
        CREATE ROLE retailmind_app LOGIN NOINHERIT;
        RAISE NOTICE 'Rol LOGIN retailmind_app creado (sin contrasena todavia)';
    ELSE
        RAISE NOTICE 'Rol retailmind_app ya existia; se converge a los atributos esperados';
    END IF;
END $$;

-- `:'app_password'` (con comillas simples) hace que psql lo emita como literal
-- SQL correctamente escapado; nunca se concatena a mano.
ALTER ROLE retailmind_app LOGIN NOINHERIT PASSWORD :'app_password';

-- Miembro de los 8 grupos: habilita SET ROLE / SET LOCAL ROLE grp_x
GRANT grp_administrador, grp_gerente, grp_vendedor, grp_compras,
      grp_bodega, grp_despacho, grp_cliente, grp_analista
    TO retailmind_app;

-- ----------------------------------------------------------------------------
-- Bootstrap de autenticacion (privilegios PROPIOS, no heredados):
-- validar credenciales, sembrar el admin al arrancar y refrescar ultimo_acceso.
-- ----------------------------------------------------------------------------
GRANT USAGE  ON SCHEMA public TO retailmind_app;
GRANT SELECT ON usuario, rol, usuario_rol TO retailmind_app;
GRANT INSERT ON usuario, usuario_rol      TO retailmind_app;  -- DataInitializer
GRANT UPDATE ON usuario                   TO retailmind_app;  -- ultimo_acceso / intentos

DO $$
DECLARE
    v_tabla text;
    v_seq   text;
BEGIN
    FOREACH v_tabla IN ARRAY ARRAY['usuario','usuario_rol'] LOOP
        v_seq := pg_get_serial_sequence('public.' || quote_ident(v_tabla), 'id');
        IF v_seq IS NOT NULL THEN
            EXECUTE format('GRANT USAGE, SELECT ON SEQUENCE %s TO retailmind_app', v_seq);
        END IF;
    END LOOP;
END $$;

-- ----------------------------------------------------------------------------
-- fn_cliente_id_de_usuario: resuelve el cliente_id de un usuario en el login.
-- SECURITY DEFINER porque cliente tiene RLS y en el momento del login la
-- sesion aun no asumio ningun grupo (retailmind_app no veria la fila).
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_cliente_id_de_usuario(p_usuario_id bigint)
RETURNS bigint
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
    SELECT id FROM cliente WHERE usuario_id = p_usuario_id
$$;

REVOKE ALL ON FUNCTION fn_cliente_id_de_usuario(bigint) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION fn_cliente_id_de_usuario(bigint) TO retailmind_app;
-- La gestion de usuarios (/api/auth/usuarios) corre bajo SET LOCAL ROLE
-- grp_administrador y consulta el mismo SELECT que usa esta funcion:
GRANT EXECUTE ON FUNCTION fn_cliente_id_de_usuario(bigint) TO grp_administrador;
