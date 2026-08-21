-- ============================================================================
-- 112_tienda_publica_y_registro.sql — RetailMind · el escaparate público, el
--                        registro de clientes y sus intereses  (2026-08-21)
--
--   docker compose exec -T postgres psql -U postgres -d retailmind \
--       -v ON_ERROR_STOP=1 < retailmind/sql/postgres/112_tienda_publica_y_registro.sql
--
-- ---------------------------------------------------------------------------
-- QUÉ RESUELVE
-- ---------------------------------------------------------------------------
-- Hasta hoy nadie podía ver la tienda sin cuenta, y las cuentas solo las creaba
-- un administrador. El sistema era un back-office con una tienda dentro. Esto
-- abre las dos puertas que faltaban, y las dos chocan con la MISMA pared:
--
--   1. Un visitante anónimo no trae JWT, así que el aspecto PgSessionRoleAspect
--      no asume ningún rol y la transacción corre como `retailmind_app`, que es
--      LOGIN **NOINHERIT** y no tiene un solo privilegio de negocio. Abrir
--      `GET /api/catalogo/**` en SecurityConfig no habría bastado: la consulta
--      habría muerto con un 42501 en la primera tabla.
--   2. El registro anónimo tiene que ESCRIBIR en `usuario`, `usuario_rol` y
--      `cliente` sin que haya nadie autenticado que preste sus privilegios.
--
-- La respuesta es la misma que el proyecto ya usa en los dos casos: un ROL DE
-- MOTOR nuevo para leer (`grp_visitante`) y una función SECURITY DEFINER para
-- escribir (`fn_registrar_cliente`). Ni un privilegio suelto, ni un GRANT a
-- PUBLIC, ni una excepción en el aspecto.
--
-- ---------------------------------------------------------------------------
-- POR QUÉ UN ROL NUEVO Y NO REUTILIZAR `grp_cliente`
-- ---------------------------------------------------------------------------
-- Sería una línea en el aspecto y es la peor idea del archivo: `grp_cliente`
-- puede ESCRIBIR en carrito, wishlist, pedido, pago, resena y direccion. Un
-- visitante sin identificar que asume ese rol es un usuario anónimo con permiso
-- de compra; lo único que lo detendría sería `app.cliente_id`, o sea la capa de
-- aplicación, que es justo lo que este proyecto se niega a usar como barrera.
--
-- `grp_visitante` solo tiene SELECT, y solo sobre las SEIS tablas que el
-- catálogo consulta de verdad (medidas sobre `ProductoCatalogoService`):
-- producto, producto_variante, producto_categoria, categoria, marca, inventario.
--
-- ---------------------------------------------------------------------------
-- LAS SEIS PIEZAS DE UN ROL NUEVO (CLAUDE.md, script 87)
-- ---------------------------------------------------------------------------
-- Un `CREATE ROLE` a secas deja un rol INSERVIBLE que falla en silencio. Hacen
-- falta: NOLOGIN · USAGE ON SCHEMA public (el 19 se lo revocó a PUBLIC) ·
-- GRANT del rol a `retailmind_app` (sin ella `set_config('role',…)` falla y la
-- aplicación entera responde 403) · ventanas en `grupo_horario` · política RLS
-- en cada tabla con RLS que vaya a leer · y la fila en `rol` si es un rol de
-- APLICACIÓN.
--
-- Aquí la quinta se reduce a UNA tabla: de las seis del catálogo, la única con
-- RLS es `inventario` (comprobado contra `pg_class.relrowsecurity`). Y la sexta
-- NO aplica y es deliberado: **`grp_visitante` no lleva fila en `rol`** porque
-- nadie inicia sesión como visitante. Es un rol de MOTOR, no de aplicación; una
-- fila en `rol` lo ofrecería en el desplegable del alta de usuarios.
--
-- Las ventanas de `grupo_horario` SÍ hacen falta aunque no haya login, y no es
-- obvio: la política de `inventario` llama a `esta_en_horario()`, y esa función
-- devuelve **false** para un rol sin filas — o sea, el catálogo se quedaría sin
-- stock, en silencio y sin un error. Van en 24/7 como los otros ocho (script 88).
--
-- ---------------------------------------------------------------------------
-- REVERSIÓN
-- ---------------------------------------------------------------------------
--   \i retailmind/sql/postgres/99_revert_tienda_publica.sql
-- ============================================================================

\set ON_ERROR_STOP on

BEGIN;

-- ════════════════════════════════════════════════════════════════════════════
-- 1. EL ROL DEL VISITANTE
-- ════════════════════════════════════════════════════════════════════════════

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'grp_visitante') THEN
        CREATE ROLE grp_visitante NOLOGIN;
        RAISE NOTICE 'Rol grp_visitante creado.';
    ELSE
        RAISE NOTICE 'Rol grp_visitante ya existía; se reaplican sus privilegios.';
    END IF;
END $$;

-- Pieza 2: sin USAGE no ve ni el esquema (el script 19 se lo revocó a PUBLIC).
GRANT USAGE ON SCHEMA public TO grp_visitante;

-- Pieza 3: sin esto, `set_config('role','grp_visitante',true)` falla y CUALQUIER
-- petición anónima al catálogo responde 500. `retailmind_app` es NOINHERIT, así
-- que recibir el rol no le da sus privilegios salvo cuando lo asume.
GRANT grp_visitante TO retailmind_app;

-- Las seis tablas del catálogo, SOLO lectura. La lista es cerrada a propósito:
-- un `GRANT SELECT ON ALL TABLES` daría al anónimo `pago` y `usuario`.
GRANT SELECT ON producto            TO grp_visitante;
GRANT SELECT ON producto_variante   TO grp_visitante;
GRANT SELECT ON producto_categoria  TO grp_visitante;
GRANT SELECT ON categoria           TO grp_visitante;
GRANT SELECT ON marca               TO grp_visitante;
GRANT SELECT ON inventario          TO grp_visitante;

-- Pieza 4: las ventanas. En 24/7, como los ocho roles desde el script 88.
-- La frontera es 24:00:00 y no 23:59: `esta_en_horario` compara con el
-- intervalo SEMIABIERTO [inicio, fin) y un instante real nunca vale 24:00:00,
-- así que es la única frontera que no deja un microsegundo fuera.
INSERT INTO grupo_horario (rol_grupo, dia_semana, hora_inicio, hora_fin, activo)
SELECT 'grp_visitante', d, '00:00:00'::time, '24:00:00'::time, true
FROM generate_series(0, 6) AS d
WHERE NOT EXISTS (
    SELECT 1 FROM grupo_horario g
    WHERE g.rol_grupo = 'grp_visitante' AND g.dia_semana = d
);

-- Pieza 5: la política RLS de `inventario`, la única de las seis que la tiene.
-- Solo lectura ('r'): el visitante no escribe stock ni por accidente.
-- El predicado va envuelto en un subselect escalar para que se evalúe UNA VEZ
-- por consulta y no una por fila (script 111, defecto D-11).
DROP POLICY IF EXISTS pol_visitante_catalogo ON inventario;
CREATE POLICY pol_visitante_catalogo ON inventario
    FOR SELECT TO grp_visitante
    USING ((SELECT esta_en_horario('grp_visitante')));

-- ════════════════════════════════════════════════════════════════════════════
-- 2. LOS INTERESES DEL CLIENTE
-- ════════════════════════════════════════════════════════════════════════════
-- Último paso del registro: qué departamentos le interesan. Es OPCIONAL, así
-- que la ausencia de filas es un estado legítimo y no un dato que falte.
--
-- Es una tabla de unión pura, sin id propio: la clave es el par, que además
-- impide por construcción que un cliente marque dos veces la misma categoría.

CREATE TABLE IF NOT EXISTS cliente_categoria_interes (
    cliente_id     bigint      NOT NULL,
    categoria_id   bigint      NOT NULL,
    fecha_creacion timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_cliente_categoria_interes PRIMARY KEY (cliente_id, categoria_id),
    CONSTRAINT fk_cci_cliente   FOREIGN KEY (cliente_id)
        REFERENCES cliente (id)   ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT fk_cci_categoria FOREIGN KEY (categoria_id)
        REFERENCES categoria (id) ON UPDATE CASCADE ON DELETE CASCADE
);

COMMENT ON TABLE cliente_categoria_interes IS
    'Departamentos que el cliente declara que le interesan (script 112). '
    'Se recogen en el último paso del registro y son opcionales: cero filas '
    'significa «no lo dijo», no «no le interesa nada».';

CREATE INDEX IF NOT EXISTS idx_cci_categoria ON cliente_categoria_interes (categoria_id);

ALTER TABLE cliente_categoria_interes ENABLE ROW LEVEL SECURITY;

-- El cliente ve y gestiona LO SUYO. Mismo par de políticas que el resto de sus
-- tablas: la de aislamiento por `app.cliente_id` y la de horario del personal.
DROP POLICY IF EXISTS pol_cliente_propio ON cliente_categoria_interes;
CREATE POLICY pol_cliente_propio ON cliente_categoria_interes
    FOR ALL TO grp_cliente
    USING      ((SELECT esta_en_horario('grp_cliente')) AND cliente_id = (SELECT fn_cliente_actual()))
    WITH CHECK ((SELECT esta_en_horario('grp_cliente')) AND cliente_id = (SELECT fn_cliente_actual()));

DROP POLICY IF EXISTS pol_horario ON cliente_categoria_interes;
CREATE POLICY pol_horario ON cliente_categoria_interes
    FOR ALL TO grp_administrador, grp_gerente, grp_vendedor, grp_analista
    USING      ((SELECT esta_en_horario(fn_grupo_actual())))
    WITH CHECK ((SELECT esta_en_horario(fn_grupo_actual())));

GRANT SELECT, INSERT, DELETE ON cliente_categoria_interes TO grp_cliente;
GRANT SELECT ON cliente_categoria_interes TO grp_administrador, grp_gerente, grp_analista;
GRANT SELECT, INSERT, DELETE ON cliente_categoria_interes TO grp_administrador;

-- El ETL lee todo lo de negocio y este rol es de solo lectura por cuatro capas.
GRANT SELECT ON cliente_categoria_interes TO retailmind_etl;

-- ════════════════════════════════════════════════════════════════════════════
-- 3. EL REGISTRO PÚBLICO
-- ════════════════════════════════════════════════════════════════════════════
-- Escribe en tres tablas sin que haya NADIE autenticado. Va SECURITY DEFINER
-- —el patrón del proyecto: fn_admin_cambiar_permiso, fn_registrar_uso_cupon—
-- con el `search_path` clavado, que es lo que impide que alguien anteponga un
-- esquema con una tabla `usuario` suya.
--
-- CUATRO cosas que esta función NO hace, y cada una es deliberada:
--
--   · NO recibe el rol. El 'CLIENTE' está ESCRITO AQUÍ DENTRO. Es la diferencia
--     con `POST /api/auth/register`, que lo toma del cuerpo y por eso está y
--     seguirá estando reservado a ADMIN: expuesto en abierto, un
--     `{"rol":"ADMIN"}` sería una escalada de privilegios en una línea.
--   · NO cifra la contraseña. Llega ya en BCrypt desde Java; el motor nunca ve
--     una contraseña en claro, ni siquiera de paso.
--   · NO marca `email_verificado`. El alta pública nace SIN verificar, que es
--     la verdad: nadie ha comprobado ese correo. El alta por ADMIN sí lo marca
--     porque ahí hay una persona respondiendo por la cuenta.
--   · NO deja que un correo existente se convierta en cliente. Devuelve un
--     error identificable para que la aplicación conteste 409 y no un 500.

CREATE OR REPLACE FUNCTION fn_registrar_cliente(
    p_email             text,
    p_password_hash     text,
    p_nombre            text,
    p_apellido          text,
    p_telefono          text,
    p_tipo_ident        text,
    p_num_ident         text,
    p_fecha_nacimiento  date,
    p_genero            text,
    p_acepta_marketing  boolean
)
RETURNS TABLE (usuario_id bigint, cliente_id bigint)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_usuario_id bigint;
    v_cliente_id bigint;
BEGIN
    IF p_email IS NULL OR btrim(p_email) = '' THEN
        RAISE EXCEPTION 'REGISTRO_EMAIL_VACIO';
    END IF;
    IF p_password_hash IS NULL OR btrim(p_password_hash) = '' THEN
        RAISE EXCEPTION 'REGISTRO_SIN_CLAVE';
    END IF;
    IF p_nombre IS NULL OR btrim(p_nombre) = '' THEN
        RAISE EXCEPTION 'REGISTRO_NOMBRE_VACIO';
    END IF;

    IF EXISTS (SELECT 1 FROM usuario WHERE lower(email) = lower(btrim(p_email))) THEN
        RAISE EXCEPTION 'REGISTRO_EMAIL_DUPLICADO';
    END IF;

    -- `uq_cliente_identificacion` es UNIQUE sobre (tipo, numero). Se comprueba
    -- ANTES y con etiqueta propia porque, dejandolo al motor, el usuario recibe
    -- el texto generico de una violacion de restriccion —«referencia
    -- inexistente, duplicado o valor fuera de rango»— y no tiene forma de saber
    -- que lo repetido es su cedula. La identificacion es ademas OPCIONAL en el
    -- alta, asi que la comprobacion solo aplica cuando viene.
    IF p_num_ident IS NOT NULL AND btrim(p_num_ident) <> ''
       AND EXISTS (SELECT 1 FROM cliente c
                   WHERE c.tipo_identificacion   = NULLIF(btrim(p_tipo_ident), '')
                     AND c.numero_identificacion = btrim(p_num_ident)) THEN
        RAISE EXCEPTION 'REGISTRO_IDENT_DUPLICADA';
    END IF;

    INSERT INTO usuario (email, password_hash, nombre, apellido, telefono,
                         email_verificado, activo)
    VALUES (lower(btrim(p_email)), p_password_hash, btrim(p_nombre),
            NULLIF(btrim(coalesce(p_apellido, '')), ''),
            NULLIF(btrim(coalesce(p_telefono, '')), ''),
            false, true)
    RETURNING id INTO v_usuario_id;

    INSERT INTO usuario_rol (usuario_id, rol_id)
    SELECT v_usuario_id, id FROM rol WHERE codigo = 'CLIENTE';

    -- Sin esta fila el usuario ENTRA pero no es un cliente: el login resuelve
    -- `cliente_id` uniendo `cliente.usuario_id`, y sin él no se fija
    -- `app.cliente_id` y la tienda devuelve cero filas sin dar error (D-16).
    INSERT INTO cliente (usuario_id, nombre, apellido, email, telefono,
                         tipo_identificacion, numero_identificacion,
                         fecha_nacimiento, genero, acepta_marketing)
    VALUES (v_usuario_id, btrim(p_nombre),
            NULLIF(btrim(coalesce(p_apellido, '')), ''),
            lower(btrim(p_email)),
            NULLIF(btrim(coalesce(p_telefono, '')), ''),
            NULLIF(btrim(coalesce(p_tipo_ident, '')), ''),
            NULLIF(btrim(coalesce(p_num_ident, '')), ''),
            p_fecha_nacimiento,
            NULLIF(btrim(coalesce(p_genero, '')), ''),
            coalesce(p_acepta_marketing, false))
    RETURNING id INTO v_cliente_id;

    RETURN QUERY SELECT v_usuario_id, v_cliente_id;
END $$;

COMMENT ON FUNCTION fn_registrar_cliente IS
    'Alta pública de un cliente de la tienda (script 112). SECURITY DEFINER '
    'porque no hay nadie autenticado que preste privilegios. El rol CLIENTE '
    'está escrito dentro y NO es un parámetro: ese es el motivo de que exista '
    'aparte de POST /api/auth/register, que sí lo recibe y sigue siendo de ADMIN.';

REVOKE ALL ON FUNCTION fn_registrar_cliente(
    text, text, text, text, text, text, text, date, text, boolean) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION fn_registrar_cliente(
    text, text, text, text, text, text, text, date, text, boolean) TO retailmind_app;

COMMIT;

-- ════════════════════════════════════════════════════════════════════════════
-- GUARDIAS — se ejecutan FUERA de la transacción, sobre el estado ya aplicado
-- ════════════════════════════════════════════════════════════════════════════

-- Guardia 1: el visitante lee las seis tablas del catálogo y NADA más.
DO $$
DECLARE faltan text; sobran text;
BEGIN
    SELECT string_agg(t, ', ') INTO faltan
    FROM unnest(ARRAY['producto','producto_variante','producto_categoria',
                      'categoria','marca','inventario']) AS t
    WHERE NOT has_table_privilege('grp_visitante', t, 'SELECT');
    IF faltan IS NOT NULL THEN
        RAISE EXCEPTION 'ABORTA: grp_visitante no puede leer: %', faltan;
    END IF;

    -- Lo que importa de verdad: que no pueda leer nada MÁS ni escribir nada.
    SELECT string_agg(c.relname, ', ') INTO sobran
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relkind = 'r'
      AND c.relname <> ALL (ARRAY['producto','producto_variante','producto_categoria',
                                  'categoria','marca','inventario'])
      AND has_table_privilege('grp_visitante', c.oid, 'SELECT');
    IF sobran IS NOT NULL THEN
        RAISE EXCEPTION 'ABORTA: grp_visitante lee tablas que no son del catalogo: %', sobran;
    END IF;

    SELECT string_agg(c.relname, ', ') INTO sobran
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relkind = 'r'
      AND has_table_privilege('grp_visitante', c.oid, 'INSERT,UPDATE,DELETE');
    IF sobran IS NOT NULL THEN
        RAISE EXCEPTION 'ABORTA: grp_visitante puede ESCRIBIR en: %', sobran;
    END IF;

    RAISE NOTICE 'Guardia 1 OK: grp_visitante lee 6 tablas, escribe 0.';
END $$;

-- Guardia 2: sus ventanas cubren la semana entera. Sin ellas,
-- `esta_en_horario` niega y el stock desaparece del catálogo SIN dar error.
DO $$
DECLARE dias int; abierto boolean;
BEGIN
    SELECT count(DISTINCT dia_semana) INTO dias
    FROM grupo_horario WHERE rol_grupo = 'grp_visitante' AND activo;
    IF dias <> 7 THEN
        RAISE EXCEPTION 'ABORTA: grp_visitante tiene % dias con ventana, se esperaban 7', dias;
    END IF;
    SELECT esta_en_horario('grp_visitante') INTO abierto;
    IF NOT abierto THEN
        RAISE EXCEPTION 'ABORTA: grp_visitante esta fuera de horario con ventanas 24/7';
    END IF;
    RAISE NOTICE 'Guardia 2 OK: 7 ventanas y la compuerta abierta.';
END $$;

-- Guardia 3: el rol de motor NO es un rol de aplicación. Si apareciera en `rol`
-- saldría en el desplegable del alta de usuarios como si alguien pudiera serlo.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM rol WHERE upper(codigo) LIKE '%VISITANTE%') THEN
        RAISE EXCEPTION 'ABORTA: grp_visitante no debe tener fila en `rol`';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'grp_visitante' AND rolcanlogin) THEN
        RAISE EXCEPTION 'ABORTA: grp_visitante no debe poder iniciar sesion';
    END IF;
    RAISE NOTICE 'Guardia 3 OK: rol de motor, sin login y fuera de `rol`.';
END $$;

-- Guardia 4: la función de registro no admite elegir rol. Se comprueba por su
-- FIRMA, que es donde estaría el agujero: mientras no exista un parámetro de
-- rol, no hay forma de pedir uno distinto de CLIENTE.
DO $$
DECLARE args text;
BEGIN
    SELECT pg_get_function_arguments(oid) INTO args
    FROM pg_proc WHERE proname = 'fn_registrar_cliente';
    IF args ~* 'rol' THEN
        RAISE EXCEPTION 'ABORTA: fn_registrar_cliente acepta un parametro de rol: %', args;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_proc WHERE proname = 'fn_registrar_cliente' AND prosecdef) THEN
        RAISE EXCEPTION 'ABORTA: fn_registrar_cliente no es SECURITY DEFINER';
    END IF;
    IF has_function_privilege('public', 'fn_registrar_cliente(text,text,text,text,text,text,text,date,text,boolean)', 'EXECUTE') THEN
        RAISE EXCEPTION 'ABORTA: fn_registrar_cliente es ejecutable por PUBLIC';
    END IF;
    RAISE NOTICE 'Guardia 4 OK: rol fijo, SECURITY DEFINER y sin EXECUTE para PUBLIC.';
END $$;

-- Guardia 5: las defensas que ya existían siguen en pie. Se cuentan porque
-- este script toca políticas y ventanas, que es justo donde se rompen.
DO $$
DECLARE n_pol int; n_trg int; n_rls int;
BEGIN
    SELECT count(*) INTO n_pol FROM pg_policy WHERE polname = 'pol_horario';
    SELECT count(*) INTO n_trg FROM pg_trigger WHERE tgname LIKE 'trg_horario_%';
    SELECT count(*) INTO n_rls FROM pg_class WHERE relrowsecurity AND relkind = 'r';
    RAISE NOTICE 'Guardia 5: % politicas pol_horario, % triggers de horario, % tablas con RLS.',
                 n_pol, n_trg, n_rls;
    IF n_trg < 34 THEN
        RAISE EXCEPTION 'ABORTA: quedan % triggers trg_horario_*, habia 34', n_trg;
    END IF;
END $$;

\echo ''
\echo 'LISTO (script 112).'
\echo '  · grp_visitante: rol de MOTOR, solo SELECT sobre las 6 tablas del catalogo.'
\echo '  · cliente_categoria_interes: intereses opcionales del cliente, con RLS propia.'
\echo '  · fn_registrar_cliente: alta publica con el rol CLIENTE ESCRITO DENTRO.'
\echo ''
\echo 'Falta el lado de la aplicacion: PgSessionRoleAspect debe asumir grp_visitante'
\echo 'cuando no hay nadie autenticado, o el catalogo publico seguira dando 500.'
