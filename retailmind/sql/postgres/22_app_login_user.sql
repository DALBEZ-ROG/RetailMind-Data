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
-- ============================================================================

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'retailmind_app') THEN
        CREATE ROLE retailmind_app LOGIN NOINHERIT PASSWORD 'RmApp_2026!x9Qz';
        RAISE NOTICE 'Rol LOGIN retailmind_app creado';
    ELSE
        ALTER ROLE retailmind_app LOGIN NOINHERIT PASSWORD 'RmApp_2026!x9Qz';
        RAISE NOTICE 'Rol retailmind_app ya existia; login/noinherit/password reafirmados';
    END IF;
END $$;

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
