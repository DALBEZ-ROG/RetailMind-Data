-- ============================================================================
-- 85_rol_etl.sql — RetailMind · rol de LECTURA del pipeline ETL (2026-07-30)
--
-- FASE 0 del plan de docs/estrategico/DISENO_ETL_CLICKHOUSE.md (§9.1, punto 1).
-- Crea el rol `retailmind_etl` con el que el ETL PostgreSQL → ClickHouse
-- extrae los datos que alimentan las 19 tablas del data warehouse.
--
-- ---------------------------------------------------------------------------
-- POR QUE HACE FALTA UN ROL NUEVO (§8.1 del diseno — riesgo CRITICO)
-- ---------------------------------------------------------------------------
-- La politica `pol_horario` esta declarada con `cmd = ALL`, y ALL INCLUYE
-- SELECT:
--
--     tabla: pedido · policyname: pol_horario · cmd: ALL
--     roles: {grp_administrador, grp_analista, grp_bodega, grp_compras,
--             grp_despacho, grp_gerente, grp_vendedor}
--     qual:  esta_en_horario(fn_grupo_actual())
--
-- Un ETL que se conecte a las 02:00 con CUALQUIERA de esos roles no recibe un
-- 403 ni una excepcion: RLS filtra EN SILENCIO y devuelve CERO FILAS. El
-- pipeline terminaria "con exito", publicaria 19 tablas vacias y los 39
-- informes compuestos apareceran en blanco sin un solo mensaje de error.
-- Agravante: un rol nuevo SIN politica asociada tampoco lee nada, porque el
-- comportamiento por defecto de RLS es DENEGAR.
--
-- Por eso el rol lleva BYPASSRLS: es el atributo que lo saca de `pol_horario`
-- y de las politicas de cliente. No se agrega ninguna politica RLS nueva ni se
-- toca ninguna de las existentes.
--
-- ---------------------------------------------------------------------------
-- POR QUE NO SE REUTILIZA UN ROL EXISTENTE
-- ---------------------------------------------------------------------------
--   * `retailmind_app`  → es NOINHERIT y SIN privilegios de negocio por
--     diseno: asume el rol del usuario por transaccion con SET LOCAL ROLE
--     (PgSessionRoleAspect). El ETL no tiene usuario, asi que no hay rol que
--     asumir y leeria cero filas.
--   * `postgres`        → superusuario. Funcionaria (tiene BYPASSRLS), pero le
--     daria al pipeline permiso para DESTRUIR la base operativa. Un ETL de
--     solo lectura debe ser INCAPAZ de escribir, no meramente abstenerse.
--   * cualquier `grp_*` → es exactamente el caso que dispara el bug de arriba.
--
-- ---------------------------------------------------------------------------
-- QUE PUEDE Y QUE NO PUEDE HACER ESTE ROL
-- ---------------------------------------------------------------------------
-- PUEDE:  SELECT sobre las 54 tablas del esquema `public` que el diseno declara
--         como ORIGEN de las 19 tablas del DWH (§4 y §5), + `inventario` (cifra
--         de control de §9.4). Nada mas.
-- NO PUEDE: INSERT / UPDATE / DELETE / TRUNCATE / REFERENCES / TRIGGER sobre
--         ninguna tabla; CREATE en ningun esquema; crear bases ni roles;
--         replicar; ni leer las tablas que no estan en la lista.
--
-- CUATRO CAPAS de garantia de solo-lectura, de fuera hacia dentro:
--   1) El rol NO tiene atributos SUPERUSER / CREATEDB / CREATEROLE / REPLICATION.
--   2) Solo se otorga SELECT; jamas un privilegio de escritura.
--   3) REVOKE explicito de todo privilegio de escritura sobre `public` (no-op
--      en la primera corrida; red de seguridad si alguien concede algo despues
--      y se vuelve a correr este script).
--   4) `default_transaction_read_only = on` a nivel de ROL: aunque alguien le
--      concediera INSERT manana, TODA transaccion de este rol nace de solo
--      lectura y el motor rechaza la escritura.
--
-- EXCEPCION DELIBERADA — `usuario` se concede POR COLUMNA. Es la unica tabla
-- del origen que guarda un secreto (`password_hash`), y el ETL solo necesita
-- el nombre del vendedor / del agente de soporte / del autor de una novedad.
-- Se conceden id, email, nombre, apellido, activo y fecha_creacion; el hash de
-- contrasena, los intentos fallidos y el bloqueo NO se conceden. Consecuencia
-- practica: `SELECT * FROM usuario` FALLA para este rol — hay que nombrar las
-- columnas. Es la misma disciplina de los scripts 41 y 43.
--
-- ---------------------------------------------------------------------------
-- LIMITACION DECLARADA — conexion a otras bases del cluster
-- ---------------------------------------------------------------------------
-- En este cluster NINGUNA base tiene ACL propia (`pg_database.datacl IS NULL`),
-- de modo que el CONNECT lo concede el `PUBLIC` por defecto de PostgreSQL a
-- todos los roles, incluido este. Cerrarlo exigiria
-- `REVOKE CONNECT ON DATABASE ... FROM PUBLIC` en cada base, que es MODIFICAR
-- privilegios existentes y afectaria a las demas aplicaciones del cluster:
-- queda FUERA del alcance de este script. El riesgo residual es nulo en la
-- practica: `retailmind_etl` no tiene privilegio alguno sobre los objetos de
-- esas bases, ni USAGE sobre sus esquemas, ni es superusuario.
--
-- TODO es ADITIVO: no se crea, modifica ni elimina ninguna politica RLS,
-- ningun privilegio y ningun rol de los 10 existentes (9 grp_* + retailmind_app).
-- No se toca el esquema operativo ni un solo dato de negocio.
-- Idempotente y transaccional.
-- ============================================================================

BEGIN;

-- ------------------------------------------------------------------ 1) El rol
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'retailmind_etl') THEN
        CREATE ROLE retailmind_etl LOGIN PASSWORD 'Etl2026!';
        RAISE NOTICE 'Rol retailmind_etl creado';
    ELSE
        RAISE NOTICE 'Rol retailmind_etl ya existia; se converge a los atributos esperados';
    END IF;
END $$;

-- Converge los atributos en cada corrida (idempotencia real, no "si no existe").
-- BYPASSRLS es el motivo de ser de este rol; el resto son NEGACIONES explicitas.
ALTER ROLE retailmind_etl
    LOGIN
    BYPASSRLS
    NOSUPERUSER
    NOCREATEDB
    NOCREATEROLE
    NOREPLICATION
    NOINHERIT
    PASSWORD 'Etl2026!';

-- Capa 4 de solo-lectura: toda transaccion de este rol nace READ ONLY.
ALTER ROLE retailmind_etl SET default_transaction_read_only = on;
ALTER ROLE retailmind_etl SET search_path = public;

-- ------------------------------------------------------------ 2) Conexion
-- Redundante hoy (PUBLIC ya lo concede), pero deja la intencion por escrito y
-- sobrevive a que alguien revoque el CONNECT de PUBLIC sobre esta base.
GRANT CONNECT ON DATABASE retailmind TO retailmind_etl;

-- El script 19 revoco el esquema public a PUBLIC y lo concede rol por rol: sin
-- USAGE, para este rol las tablas "no existen" (falla la resolucion de nombres).
GRANT USAGE ON SCHEMA public TO retailmind_etl;

-- ------------------------------------------------- 3) SELECT sobre los origenes
-- Lista blanca cerrada, derivada de las clausulas "Origen" de §4 y §5 del
-- diseno. Agrupada por la tabla del DWH que alimenta.

-- dim_producto (§4.2) + dim_cliente (§4.3) + dim_proveedor (§4.4)
GRANT SELECT ON producto_variante, producto, producto_categoria,
                categoria, marca,
                cliente, direccion, ciudad, provincia, pais,
                proveedor
    TO retailmind_etl;

-- dim_promocion_producto (§4.5)
GRANT SELECT ON promocion, promocion_producto TO retailmind_etl;

-- fact_pedido (§5.1) + fact_venta_linea (§5.2)
GRANT SELECT ON pedido, pedido_detalle, estado_pedido, historial_estado_pedido,
                uso_cupon, cupon, factura_venta, factura_venta_detalle
    TO retailmind_etl;

-- fact_flujo_caja (§5.3) — cobros de cliente y pagos a proveedor
GRANT SELECT ON pago, transaccion_pago, metodo_pago,
                pago_proveedor, cuenta_por_pagar, factura_compra
    TO retailmind_etl;

-- fact_orden_compra (§5.4) + fact_compra_linea (§5.5)
GRANT SELECT ON orden_compra, orden_compra_detalle,
                recepcion_mercancia, recepcion_detalle, bodega
    TO retailmind_etl;

-- fact_movimiento_inventario (§5.6) + fact_stock_mensual (§5.7)
-- `inventario` no es origen de ninguna tabla: es la CIFRA DE CONTROL de §9.4
-- (el stock_cierre del ultimo mes debe cuadrar con las 1.406 posiciones).
GRANT SELECT ON movimiento_inventario, tipo_movimiento, ajuste_inventario,
                inventario
    TO retailmind_etl;

-- fact_envio (§5.8) + fact_novedad_envio (§5.9)
GRANT SELECT ON envio, metodo_envio, transportista, zona_envio, novedad_envio
    TO retailmind_etl;

-- fact_devolucion (§5.10) + fact_devolucion_linea (§5.11)
GRANT SELECT ON devolucion, devolucion_detalle, historial_estado_devolucion,
                motivo_devolucion, reembolso
    TO retailmind_etl;

-- fact_ticket (§5.12) + fact_resena (§5.13)
GRANT SELECT ON ticket_soporte, categoria_ticket, mensaje_ticket, resena
    TO retailmind_etl;

-- fact_devolucion_proveedor (§5.14)
GRANT SELECT ON item_defectuoso, devolucion_proveedor,
                devolucion_proveedor_detalle
    TO retailmind_etl;

-- `usuario` POR COLUMNA: nombre del vendedor / agente / autor. NUNCA el hash.
GRANT SELECT (id, email, nombre, apellido, activo, fecha_creacion)
    ON usuario TO retailmind_etl;

-- ------------------------------------------- 4) Red de seguridad de solo-lectura
-- Solo afecta a retailmind_etl: no toca los privilegios de ningun otro rol.
REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER
    ON ALL TABLES IN SCHEMA public FROM retailmind_etl;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM retailmind_etl;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA public FROM retailmind_etl;
REVOKE CREATE ON SCHEMA public FROM retailmind_etl;
REVOKE ALL ON SCHEMA seed_backup FROM retailmind_etl;

COMMIT;

-- ============================================================================
-- VERIFICACION (ejecutar despues del COMMIT)
--
--   -- 1) existe y tiene los atributos esperados
--   SELECT rolname, rolcanlogin, rolbypassrls, rolsuper, rolcreatedb,
--          rolcreaterole, rolreplication
--   FROM pg_roles WHERE rolname = 'retailmind_etl';
--
--   -- 2) lee una tabla protegida por pol_horario y devuelve el conteo REAL
--   --    (conectado COMO retailmind_etl):  SELECT count(*) FROM pedido;
--
--   -- 3) no puede escribir (debe fallar):  INSERT INTO categoria(nombre) ...
--
--   -- 4) no tiene ni un privilegio de escritura en todo el esquema
--   SELECT count(*) FROM information_schema.table_privileges
--   WHERE grantee = 'retailmind_etl' AND privilege_type <> 'SELECT';
--   -- esperado: 0
-- ============================================================================
