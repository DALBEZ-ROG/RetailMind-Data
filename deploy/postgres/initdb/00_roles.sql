-- =====================================================================
-- 00_roles.sql — los 11 roles de RetailMind, sus 9 membresias y la
--                configuracion por rol del ETL.
--
-- POR QUE ESTE ARCHIVO EXISTE
-- Los roles son objetos de CLUSTER: un `pg_dump` DE LA BASE no los
-- incluye. Si no existen ANTES de restaurar, fallan las 95 politicas
-- RLS, los 1.354 GRANT de tabla y los 109 GRANT de columna, y queda una
-- base que arranca, responde como `postgres` y DENIEGA TODO en cuanto la
-- aplicacion hace `SET LOCAL ROLE`.
--
-- Se escribe EXPLICITAMENTE y no se genera con `pg_dumpall --globals-only`:
-- el cluster de origen tiene 11 roles con LOGIN y 8 son de OTRAS MATERIAS
-- (ElToke, Darinxxo, jefe_ventas, aux_tthh, ...). Un volcado sin filtrar
-- los arrastraria a este contenedor.
--
-- LAS CONTRASENAS NO ESTAN AQUI: las fija `02_restaurar.sh` desde las
-- variables de entorno PG_APP_PASSWORD / PG_ETL_PASSWORD, que fallan de
-- forma ruidosa si no estan definidas. Durante initdb el servidor solo
-- escucha por socket local, asi que no hay ventana de exposicion.
-- =====================================================================

-- --- 9 roles de grupo: NOLOGIN, INHERIT ------------------------------
-- Nadie inicia sesion con ellos: los asume `retailmind_app` por
-- transaccion con `SET LOCAL ROLE` (aspecto PgSessionRoleAspect).
CREATE ROLE grp_administrador NOLOGIN INHERIT;
CREATE ROLE grp_gerente       NOLOGIN INHERIT;
CREATE ROLE grp_vendedor      NOLOGIN INHERIT;
CREATE ROLE grp_compras       NOLOGIN INHERIT;
CREATE ROLE grp_bodega        NOLOGIN INHERIT;
CREATE ROLE grp_despacho      NOLOGIN INHERIT;
CREATE ROLE grp_cliente       NOLOGIN INHERIT;
CREATE ROLE grp_analista      NOLOGIN INHERIT;
CREATE ROLE grp_soporte       NOLOGIN INHERIT;

-- --- Rol de la aplicacion --------------------------------------------
-- NOINHERIT es el punto entero del diseno: tiene los 9 grupos pero NO
-- sus privilegios hasta que hace `SET LOCAL ROLE` dentro de una
-- transaccion. Sin NOINHERIT, la app tendria la union de los 9 siempre.
CREATE ROLE retailmind_app LOGIN NOINHERIT;

-- Las 9 membresias. Sin ellas `SET LOCAL ROLE` falla y TODA la
-- aplicacion devuelve 403.
GRANT grp_administrador, grp_gerente, grp_vendedor, grp_compras,
      grp_bodega, grp_despacho, grp_cliente, grp_analista, grp_soporte
   TO retailmind_app;

-- --- Rol del ETL ------------------------------------------------------
-- BYPASSRLS es obligatorio: `pol_horario` esta declarada con `cmd = ALL`,
-- y ALL incluye SELECT. Un ETL nocturno con cualquier `grp_*` no recibe
-- un error: RLS filtra EN SILENCIO y devuelve CERO FILAS, publicando las
-- tablas del almacen vacias sin un solo aviso en ningun log.
CREATE ROLE retailmind_etl LOGIN NOINHERIT BYPASSRLS;

-- Estas dos lineas viven en `pg_db_role_setting`, NO en la base: un dump
-- de base las pierde SIN DAR NINGUN ERROR, y la perdida solo se nota el
-- dia que alguien escriba. Son dos de las cuatro capas de solo-lectura
-- del rol del ETL.
ALTER ROLE retailmind_etl SET default_transaction_read_only = on;
ALTER ROLE retailmind_etl SET search_path = public;
