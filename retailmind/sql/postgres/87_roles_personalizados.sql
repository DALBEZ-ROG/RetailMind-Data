-- ============================================================================
-- 87_roles_personalizados.sql — RetailMind · crear y eliminar ROLES NUEVOS
--                                desde la pantalla de Permisos (2026-08-06)
--
-- Permite al administrador crear un rol propio (p. ej. `PRUEBA` -> rol de motor
-- `grp_prueba`), asignarselo a un usuario real y darle privilegios con los
-- interruptores de la pantalla, SIN tocar ninguno de los 9 roles que funcionan.
--
-- ---------------------------------------------------------------------------
-- 1) LAS SEIS PIEZAS QUE HACEN FALTA PARA QUE UN ROL NUEVO *FUNCIONE*
-- ---------------------------------------------------------------------------
-- Un `CREATE ROLE` a secas produce un rol que NO SIRVE PARA NADA en este
-- sistema, y lo peor es que falla en silencio. Hacen falta las seis:
--
--   1. **CREATE ROLE ... NOLOGIN**. Nadie se conecta con el: la aplicacion
--      entra como `retailmind_app` y lo ASUME por transaccion.
--   2. **GRANT USAGE ON SCHEMA public**. El script 19 se lo revoco a PUBLIC,
--      asi que sin esto el rol no ve NI UNA tabla por mas GRANT que se le den.
--   3. **GRANT <rol> TO retailmind_app**. Sin la membresia, el
--      `set_config('role', ...)` del aspecto falla y la aplicacion entera
--      responde 403 para ese usuario.
--   4. **Ventanas en `grupo_horario`**. `esta_en_horario()` devuelve FALSE para
--      un rol sin ventanas, y el script 53 hace que eso BLOQUEE EL LOGIN.
--   5. **Una politica RLS propia en cada tabla con RLS**. Esta es la que se
--      olvida: el comportamiento por defecto de RLS es DENEGAR, asi que un rol
--      con SELECT sobre una tabla con RLS y sin politica lee **CERO FILAS sin
--      un solo error**. Son 50 tablas. Se crea una politica por tabla, con el
--      mismo criterio horario que tienen los 9 roles de personal.
--   6. **Fila en `rol`** (`es_sistema = false`), que es lo que hace que el rol
--      aparezca en el desplegable de la pantalla de Usuarios.
--
-- ---------------------------------------------------------------------------
-- 2) `rol_base`: POR QUE UN ROL NUEVO NECESITA IMITAR A UNO EXISTENTE
-- ---------------------------------------------------------------------------
-- La autorizacion de RUTAS de Spring (`SecurityConfig`) es codigo compilado:
-- enumera ADMIN, GERENTE, VENDEDOR... Un rol creado en caliente no aparece en
-- ninguna regla, asi que su usuario entraria al sistema y recibiria 403 en
-- TODAS las pantallas — un rol tecnicamente correcto y practicamente inutil.
--
-- La solucion es declarar a QUE rol imita para las RUTAS. El usuario del rol
-- nuevo ve las pantallas de su rol base, pero **contra el motor va con SU
-- propio rol**, con los privilegios que le hayan puesto los interruptores. Es
-- justo lo que hace demostrable el sistema: dos usuarios en la MISMA pantalla
-- viendo datos distintos porque su rol de PostgreSQL es distinto.
--
-- `rol_base` NO concede nada en la base de datos: no hay herencia, no hay
-- GRANT, no hay membresia entre el rol nuevo y el base. Solo decide que
-- pantallas se dibujan.
--
-- ---------------------------------------------------------------------------
-- 3) LO QUE ESTAS FUNCIONES *NO* PUEDEN TOCAR
-- ---------------------------------------------------------------------------
--   * Los 9 roles del sistema (`rol.es_sistema = true`) y sus roles de motor:
--     ni se modifican ni se eliminan. La lista blanca es al reves de lo
--     habitual — solo se puede tocar lo que se creo AQUI, marcado con el
--     comentario de rol 'retailmind:rol-personalizado' Y con fila en
--     `rol_personalizado`. Las dos condiciones, no una.
--   * `retailmind_app`, `retailmind_etl`, `postgres` y PUBLIC.
--   * Ninguna politica RLS existente: solo se CREAN politicas nuevas, con
--     nombre propio (`pol_<rol_grupo>`), y solo se borran esas.
--
-- Idempotente y transaccional.
-- ============================================================================

BEGIN;

-- ── 1) Registro de los roles creados desde la pantalla ───────────────────────
-- Tabla NUEVA: no se modifica ninguna existente. Guarda el puente
-- rol de aplicacion -> rol de motor y el rol base de rutas.
CREATE TABLE IF NOT EXISTS rol_personalizado (
    rol_id          bigint       PRIMARY KEY REFERENCES rol(id) ON DELETE CASCADE,
    rol_grupo       varchar(63)  NOT NULL UNIQUE,
    rol_base_codigo varchar(50)  REFERENCES rol(codigo),
    creado_por      bigint       REFERENCES usuario(id) ON DELETE SET NULL,
    fecha_creacion  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT rol_personalizado_grupo_chk CHECK (rol_grupo ~ '^grp_[a-z0-9_]{2,40}$')
);

COMMENT ON TABLE rol_personalizado IS
    'Roles creados desde la pantalla de Permisos del Motor (script 87). '
    'rol_base_codigo decide que RUTAS ve su usuario; no concede nada en la BD.';

GRANT SELECT ON rol_personalizado TO grp_administrador;
-- retailmind_app lo lee SIN rol asumido, en el login, para resolver el rol de
-- motor del usuario antes de que exista rol que asumir. Solo SELECT.
GRANT SELECT ON rol_personalizado TO retailmind_app;

-- ── 2) Crear un rol nuevo, con las seis piezas ──────────────────────────────
CREATE OR REPLACE FUNCTION fn_admin_crear_rol(
    p_codigo     text,           -- codigo de aplicacion, p. ej. 'PRUEBA'
    p_nombre     text,           -- nombre legible
    p_rol_base   text,           -- codigo de uno de los 9, o NULL (sin pantallas)
    p_creado_por bigint DEFAULT NULL
)
RETURNS TABLE (rol_id bigint, rol_grupo text, politicas int, ventanas int)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
    v_codigo text := upper(btrim(coalesce(p_codigo, '')));
    v_nombre text := btrim(coalesce(p_nombre, ''));
    v_base   text := nullif(upper(btrim(coalesce(p_rol_base, ''))), '');
    v_grupo  text;
    v_rol_id bigint;
    v_pol    int := 0;
    v_ven    int := 0;
    r        record;
BEGIN
    -- Codigo: letras, digitos y guion bajo. El patron es lo que garantiza que
    -- el nombre del rol de motor derivado sea un identificador sano.
    IF v_codigo !~ '^[A-Z][A-Z0-9_]{2,20}$' THEN
        RAISE EXCEPTION 'Codigo invalido: %. Debe empezar por letra y tener de 3 a 21 '
            'caracteres (A-Z, 0-9, _).', p_codigo USING ERRCODE = '22023';
    END IF;
    IF v_nombre = '' THEN
        RAISE EXCEPTION 'El nombre del rol es obligatorio.' USING ERRCODE = '22023';
    END IF;

    v_grupo := 'grp_' || lower(v_codigo);

    IF EXISTS (SELECT 1 FROM rol WHERE codigo = v_codigo) THEN
        RAISE EXCEPTION 'Ya existe un rol con el codigo %.', v_codigo USING ERRCODE = '23505';
    END IF;
    IF EXISTS (SELECT 1 FROM rol WHERE nombre = v_nombre) THEN
        RAISE EXCEPTION 'Ya existe un rol con el nombre %.', v_nombre USING ERRCODE = '23505';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = v_grupo) THEN
        RAISE EXCEPTION 'Ya existe el rol de motor %. Elige otro codigo.', v_grupo
            USING ERRCODE = '23505';
    END IF;

    -- El rol base tiene que ser uno de los 9 del sistema (o ninguno).
    IF v_base IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM rol WHERE codigo = v_base AND es_sistema
    ) THEN
        RAISE EXCEPTION 'El rol base % no es uno de los roles del sistema.', v_base
            USING ERRCODE = '22023';
    END IF;

    -- (1) El rol, sin ningun atributo peligroso. Se enumeran TODOS en negativo
    --     a proposito: un NOBYPASSRLS olvidado convierte el sandbox en un rol
    --     que lo ve todo.
    EXECUTE format(
        'CREATE ROLE %I NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE '
        'NOBYPASSRLS NOREPLICATION INHERIT', v_grupo);

    -- Marca: junto con la fila de rol_personalizado, es lo que autoriza a
    -- borrarlo despues. Las dos condiciones, nunca una sola.
    EXECUTE format('COMMENT ON ROLE %I IS %L', v_grupo, 'retailmind:rol-personalizado');

    -- (2) Sin USAGE no ve nada, por muchos GRANT que reciba.
    EXECUTE format('GRANT USAGE ON SCHEMA public TO %I', v_grupo);

    -- (3) Sin la membresia, SET LOCAL ROLE falla y la app responde 403.
    EXECUTE format('GRANT %I TO retailmind_app', v_grupo);

    -- (6) Fila en `rol`: es_sistema = false lo separa de los 9 intocables.
    INSERT INTO rol (codigo, nombre, descripcion, es_sistema, activo)
    VALUES (v_codigo, v_nombre,
            'Rol personalizado creado desde Permisos del Motor (script 87).',
            false, true)
    RETURNING id INTO v_rol_id;

    INSERT INTO rol_personalizado (rol_id, rol_grupo, rol_base_codigo, creado_por)
    VALUES (v_rol_id, v_grupo, v_base, p_creado_por);

    -- (4) Ventana horaria completa los 7 dias. Un rol sin ventanas no puede
    --     ni iniciar sesion (script 53). Se edita luego en Horarios de Acceso.
    FOR r IN SELECT generate_series(0, 6) AS dia LOOP
        INSERT INTO grupo_horario (rol_grupo, dia_semana, hora_inicio, hora_fin, activo)
        VALUES (v_grupo, r.dia, '00:00'::time, '23:59'::time, true);
        v_ven := v_ven + 1;
    END LOOP;

    -- (5) LA PIEZA QUE SE OLVIDA: una politica por cada tabla con RLS. Sin
    --     ella el rol lee CERO FILAS sin dar error. Mismo criterio horario que
    --     los 9 roles de personal. Se CREA una politica nueva con nombre
    --     propio; ninguna politica existente se toca.
    FOR r IN
        SELECT c.relname
        FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'public' AND c.relkind = 'r' AND c.relrowsecurity
        ORDER BY c.relname
    LOOP
        EXECUTE format(
            'CREATE POLICY %I ON public.%I FOR ALL TO %I USING (esta_en_horario(%L))',
            'pol_' || v_grupo, r.relname, v_grupo, v_grupo);
        v_pol := v_pol + 1;
    END LOOP;

    rol_id    := v_rol_id;
    rol_grupo := v_grupo;
    politicas := v_pol;
    ventanas  := v_ven;
    RETURN NEXT;
END $$;

COMMENT ON FUNCTION fn_admin_crear_rol(text, text, text, bigint) IS
    'Crea un rol de aplicacion + rol de motor con las 6 piezas necesarias '
    '(USAGE, membresia, horario, politicas RLS, fila en rol). Script 87.';

-- ── 3) Eliminar un rol personalizado ────────────────────────────────────────
CREATE OR REPLACE FUNCTION fn_admin_eliminar_rol(p_codigo text)
RETURNS TABLE (rol_grupo text, politicas int, ventanas int)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
    v_codigo   text := upper(btrim(coalesce(p_codigo, '')));
    v_rol_id   bigint;
    v_grupo    text;
    v_usuarios int;
    v_pol      int := 0;
    v_ven      int := 0;
    r          record;
BEGIN
    -- OJO con el alias: `r` es la variable de bucle declarada arriba, y
    -- PL/pgSQL resuelve `r.id` contra ELLA antes que contra la tabla, con un
    -- «record is not assigned yet» que apunta a la consulta y no a la causa.
    -- Los alias de tabla de esta funcion no pueden llamarse `r`.
    SELECT ro.id, rp.rol_grupo INTO v_rol_id, v_grupo
    FROM rol ro
    JOIN rol_personalizado rp ON rp.rol_id = ro.id
    WHERE ro.codigo = v_codigo AND NOT ro.es_sistema;

    IF v_rol_id IS NULL THEN
        RAISE EXCEPTION 'No hay ningun rol PERSONALIZADO con el codigo %. Los 9 roles '
            'del sistema no se pueden eliminar.', v_codigo USING ERRCODE = '42501';
    END IF;

    -- Doble condicion: la fila de rol_personalizado Y la marca en el catalogo.
    IF shobj_description((SELECT oid FROM pg_roles WHERE rolname = v_grupo), 'pg_authid')
       IS DISTINCT FROM 'retailmind:rol-personalizado' THEN
        RAISE EXCEPTION 'El rol de motor % no lleva la marca de rol personalizado. '
            'No se elimina.', v_grupo USING ERRCODE = '42501';
    END IF;

    SELECT count(*) INTO v_usuarios FROM usuario_rol WHERE rol_id = v_rol_id;
    IF v_usuarios > 0 THEN
        RAISE EXCEPTION 'El rol % tiene % usuario(s) asignado(s). Cambiales el rol antes '
            'de eliminarlo.', v_codigo, v_usuarios USING ERRCODE = '23503';
    END IF;

    -- Solo las politicas creadas para ESTE rol, por nombre.
    FOR r IN
        SELECT tablename FROM pg_policies
        WHERE schemaname = 'public' AND policyname = 'pol_' || v_grupo
    LOOP
        EXECUTE format('DROP POLICY %I ON public.%I', 'pol_' || v_grupo, r.tablename);
        v_pol := v_pol + 1;
    END LOOP;

    -- `rol_grupo` es ADEMAS una columna de salida de esta funcion: sin el alias,
    -- PostgreSQL no sabe si el nombre es la columna de la tabla o la variable y
    -- aborta con «column reference is ambiguous».
    DELETE FROM grupo_horario gh WHERE gh.rol_grupo = v_grupo;
    GET DIAGNOSTICS v_ven = ROW_COUNT;

    DELETE FROM rol_personalizado WHERE rol_id = v_rol_id;
    DELETE FROM rol WHERE id = v_rol_id;

    -- DROP OWNED retira TODOS los privilegios que se le concedieron; sin el,
    -- el DROP ROLE falla con «no se puede eliminar, tiene privilegios».
    EXECUTE format('REVOKE %I FROM retailmind_app', v_grupo);
    EXECUTE format('DROP OWNED BY %I', v_grupo);
    EXECUTE format('DROP ROLE %I', v_grupo);

    rol_grupo := v_grupo;
    politicas := v_pol;
    ventanas  := v_ven;
    RETURN NEXT;
END $$;

COMMENT ON FUNCTION fn_admin_eliminar_rol(text) IS
    'Elimina un rol personalizado y sus 6 piezas. Exige marca + fila propia y '
    'cero usuarios asignados. Nunca toca los 9 del sistema. Script 87.';

REVOKE ALL ON FUNCTION fn_admin_crear_rol(text, text, text, bigint) FROM PUBLIC;
REVOKE ALL ON FUNCTION fn_admin_eliminar_rol(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION fn_admin_crear_rol(text, text, text, bigint) TO grp_administrador;
GRANT EXECUTE ON FUNCTION fn_admin_eliminar_rol(text) TO grp_administrador;

COMMIT;
