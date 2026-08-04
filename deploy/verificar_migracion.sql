-- =====================================================================
-- verificar_migracion.sql — V1..V9 de la seccion 9.5 del diseno.
--
-- Se lanza contra el LOCAL (5432) y contra el CONTENEDOR (5433) y se
-- DIFERENCIAN las dos salidas: comparar dos salidas es mejor prueba que
-- leer dos numeros.
--
--   psql -h localhost -p 5432 -U postgres -d retailmind -f deploy/verificar_migracion.sql > local.txt
--   psql -h localhost -p 5433 -U postgres -d retailmind -f deploy/verificar_migracion.sql > cont.txt
--   diff local.txt cont.txt
--
-- V10 (prueba funcional del motor) y V11 (prueba funcional de la
-- aplicacion) NO estan aqui: son pruebas de comportamiento, no de
-- catalogo, y van aparte.
--
-- OJO CON EL ORDEN: todos los ORDER BY sobre texto llevan COLLATE "C".
-- El origen ordena con `Spanish_Ecuador.1252` (libc de Windows) y el
-- destino con ICU `es-EC` (§9.3): sin fijar la colacion, el propio
-- listado saldria en otro orden y el diff acusaria una diferencia que
-- no existe.
-- =====================================================================

\pset format unaligned
\pset fieldsep '|'
\pset footer off
\pset null '<NULL>'
\pset tuples_only off

\echo '===== V1 · ROLES ====='
SELECT rolname, rolsuper, rolinherit, rolcreaterole, rolcreatedb,
       rolcanlogin, rolreplication, rolbypassrls, rolconnlimit,
       rolvaliduntil
  FROM pg_roles
 WHERE rolname LIKE 'grp\_%' OR rolname LIKE 'retailmind%'
 ORDER BY rolname COLLATE "C";

\echo ''
\echo '===== V2 · MEMBRESIAS DE ROL ====='
SELECT r.rolname AS grupo, m.rolname AS miembro, am.admin_option
  FROM pg_auth_members am
  JOIN pg_roles r ON r.oid = am.roleid
  JOIN pg_roles m ON m.oid = am.member
 WHERE m.rolname = 'retailmind_app'
 ORDER BY r.rolname COLLATE "C";

\echo ''
\echo '===== V3 · CONFIGURACION POR ROL (pg_db_role_setting) ====='
-- El elemento que mas facil se pierde y el que menos ruido hace al
-- perderse: no viaja en el dump y su ausencia no da ningun error.
SELECT r.rolname, coalesce(d.datname, '<todas las bases>') AS ambito,
       s.setconfig
  FROM pg_db_role_setting s
  LEFT JOIN pg_roles    r ON r.oid = s.setrole
  LEFT JOIN pg_database d ON d.oid = s.setdatabase
 WHERE r.rolname LIKE 'grp\_%' OR r.rolname LIKE 'retailmind%'
 ORDER BY r.rolname COLLATE "C";

\echo ''
\echo '===== V4a · RECUENTO DE RLS Y POLITICAS ====='
SELECT (SELECT count(*) FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public' AND c.relrowsecurity)   AS tablas_con_rls,
       (SELECT count(*) FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public' AND c.relforcerowsecurity) AS tablas_force_rls,
       (SELECT count(*) FROM pg_policies
         WHERE schemaname = 'public')                       AS politicas;

\echo ''
\echo '===== V4b · LAS POLITICAS, UNA A UNA (diff debe salir vacio) ====='
-- No solo el nombre: tambien el comando, los roles a los que aplica y
-- la expresion. Una politica con el mismo nombre y otra expresion seria
-- invisible comparando solo nombres.
SELECT tablename, policyname, permissive, cmd,
       array_to_string(roles, ',') AS roles, qual, with_check
  FROM pg_policies
 WHERE schemaname = 'public'
 ORDER BY tablename COLLATE "C", policyname COLLATE "C";

\echo ''
\echo '===== V5a · RECUENTO DE ACL A NIVEL DE COLUMNA ====='
-- OJO CON LA METRICA: `information_schema.column_privileges` devuelve
-- miles de filas porque EXPANDE tambien los permisos heredados de la
-- tabla. La cifra real de ACL propia de columna se lee de
-- `pg_attribute.attacl`.
SELECT count(*) AS columnas_con_acl, count(DISTINCT c.relname) AS tablas
  FROM pg_attribute a
  JOIN pg_class     c ON c.oid = a.attrelid
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public' AND a.attacl IS NOT NULL AND NOT a.attisdropped;

\echo ''
\echo '===== V5b · LOS GRANT DE COLUMNA, UNO A UNO (diff vacio) ====='
-- Es la segregacion financiera del script 41: Bodega y Despacho sin
-- columnas de dinero.
SELECT c.relname, a.attname, a.attacl::text
  FROM pg_attribute a
  JOIN pg_class     c ON c.oid = a.attrelid
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public' AND a.attacl IS NOT NULL AND NOT a.attisdropped
 ORDER BY c.relname COLLATE "C", a.attname COLLATE "C";

\echo ''
\echo '===== V6a · FILAS POR TABLA (diff vacio) ====='
SELECT t.table_schema, t.table_name,
       (xpath('/row/c/text()',
              query_to_xml(format('SELECT count(*) AS c FROM %I.%I',
                                  t.table_schema, t.table_name),
                           false, true, '')))[1]::text::bigint AS filas
  FROM information_schema.tables t
 WHERE t.table_schema IN ('public', 'seed_backup')
   AND t.table_type = 'BASE TABLE'
 ORDER BY t.table_schema COLLATE "C", t.table_name COLLATE "C";

\echo ''
\echo '===== V6b · TOTALES ====='
SELECT count(*) AS tablas_public,
       count(*) FILTER (WHERE filas > 0) AS con_filas,
       sum(filas) AS filas_totales
  FROM (SELECT (xpath('/row/c/text()',
                query_to_xml(format('SELECT count(*) AS c FROM public.%I', table_name),
                             false, true, '')))[1]::text::bigint AS filas
          FROM information_schema.tables
         WHERE table_schema = 'public' AND table_type = 'BASE TABLE') s;

\echo ''
\echo '===== V6c · COLUMNAS GENERATED: las cuatro sumas de subtotal ====='
-- Los valores GENERATED no se copian: se RECALCULAN al restaurar. Si
-- las cuatro sumas coinciden al centavo, se recalcularon bien.
-- El UNION va DENTRO de un FROM: el ORDER BY de un UNION solo admite
-- nombres de columna pelados, no expresiones — y `COLLATE` lo es. (Y por
-- el alias y no por `1`: con un COLLATE detras, `ORDER BY 1` se lee como
-- el literal entero 1 y no como la primera columna.)
SELECT tabla, suma_subtotal FROM (
            SELECT 'pedido_detalle'         AS tabla, sum(subtotal) AS suma_subtotal FROM pedido_detalle
  UNION ALL SELECT 'orden_compra_detalle',   sum(subtotal) FROM orden_compra_detalle
  UNION ALL SELECT 'factura_venta_detalle',  sum(subtotal) FROM factura_venta_detalle
  UNION ALL SELECT 'factura_compra_detalle', sum(subtotal) FROM factura_compra_detalle
) s
 ORDER BY tabla COLLATE "C";

\echo ''
\echo '===== V7 · FUNCIONES SECURITY DEFINER, PROPIETARIO Y ACL (diff vacio) ====='
-- El propietario decide la elevacion: si cambia, cambia la seguridad
-- del sistema. Las 13 deben pertenecer a `postgres`.
SELECT p.proname, p.prosecdef, pg_get_userbyid(p.proowner) AS propietario,
       coalesce(p.proacl::text, '<por defecto>') AS acl
  FROM pg_proc p
  JOIN pg_namespace n ON n.oid = p.pronamespace
 WHERE n.nspname = 'public' AND p.prosecdef
 ORDER BY p.proname COLLATE "C";

\echo ''
\echo '===== V7b · QUIEN PUEDE EJECUTAR CADA SECURITY DEFINER (diff vacio) ====='
-- El ACL CRUDO de V7 no es comparable tal cual: cuando los privilegios
-- de una funcion coinciden con los que tendria por defecto, `pg_dump`
-- NO emite ningun GRANT, asi que en el origen puede verse un ACL
-- materializado (`{=X/postgres,postgres=X/postgres}`) y en el destino un
-- `proacl` nulo. Son la MISMA cosa dicha de dos maneras — pero eso hay
-- que PROBARLO, no suponerlo. Esto pregunta por el privilegio EFECTIVO,
-- que es lo unico que decide si una llamada pasa o no.
SELECT p.proname, r.rolname,
       has_function_privilege(r.rolname, p.oid, 'EXECUTE') AS puede_ejecutar
  FROM pg_proc p
  JOIN pg_namespace n ON n.oid = p.pronamespace
 CROSS JOIN (SELECT rolname FROM pg_roles
              WHERE rolname LIKE 'grp\_%' OR rolname LIKE 'retailmind%') r
 WHERE n.nspname = 'public' AND p.prosecdef
 ORDER BY p.proname COLLATE "C", r.rolname COLLATE "C";

\echo ''
\echo '===== V8 · SECUENCIAS ====='
SELECT (SELECT last_value FROM seq_numero_documento) AS seq_numero_documento,
       (SELECT count(*) FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public' AND c.relkind = 'S') AS secuencias_public;

\echo ''
\echo '===== V9a · EXTENSIONES ====='
SELECT extname, extversion FROM pg_extension ORDER BY extname COLLATE "C";

\echo ''
\echo '===== V9b · ACL DEL ESQUEMA public ====='
-- El script 19 revoco USAGE a PUBLIC: sin estos GRANT ningun rol ve nada.
-- El cast va DENTRO de unnest: en la clausula FROM el elemento tiene que
-- ser una llamada a funcion, no una expresion con `::`.
SELECT g AS grant_de_esquema
  FROM pg_namespace, unnest(nspacl::text[]) AS g
 WHERE nspname = 'public'
 ORDER BY g COLLATE "C";

\echo ''
\echo '===== V9c · TRIGGERS, INDICES Y FUNCIONES (recuento) ====='
SELECT (SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid
          JOIN pg_namespace n ON n.oid=c.relnamespace
         WHERE n.nspname='public' AND NOT t.tgisinternal)      AS triggers,
       (SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid=i.indrelid
          JOIN pg_namespace n ON n.oid=c.relnamespace
         WHERE n.nspname='public')                             AS indices,
       (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
         WHERE n.nspname='public')                             AS funciones,
       (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
         WHERE n.nspname='public' AND c.relkind='v')           AS vistas;

\echo ''
\echo '===== V9d · GRANTS DE TABLA A LOS ROLES DE RETAILMIND (recuento) ====='
SELECT grantee, count(*) AS grants
  FROM information_schema.role_table_grants
 WHERE table_schema = 'public'
   AND (grantee LIKE 'grp\_%' OR grantee LIKE 'retailmind%')
 GROUP BY grantee
 ORDER BY grantee COLLATE "C";

\echo ''
\echo '===== FIN (V10/V11 van aparte: son pruebas de comportamiento) ====='
