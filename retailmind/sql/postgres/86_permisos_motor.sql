-- ============================================================================
-- 86_permisos_motor.sql — RetailMind · GRANT/REVOKE en vivo desde la pantalla
--                          de Permisos del Motor (2026-08-05)
--
-- Da soporte a la PARTE 2 de la pantalla `/operativo/seguridad/permisos`:
-- conceder y revocar privilegios de TABLA y de COLUMNA a los 9 roles de grupo,
-- con la lista de protegidos aplicada DENTRO del motor.
--
-- ---------------------------------------------------------------------------
-- 1) POR QUE HACE FALTA UNA FUNCION Y NO BASTA CON EJECUTAR EL GRANT
-- ---------------------------------------------------------------------------
-- La app conecta como `retailmind_app` y asume el rol del usuario por
-- transaccion (`SET LOCAL ROLE grp_administrador`). Las 110 tablas son
-- propiedad de `postgres`, y en PostgreSQL solo el PROPIETARIO (o un
-- superusuario) puede otorgar privilegios sobre un objeto.
--
-- Lo grave es COMO falla: un GRANT ejecutado por quien no es propietario
-- NO LANZA ERROR. Emite un WARNING y no hace nada. Medido en este sistema:
--
--     SET ROLE grp_administrador;
--     GRANT SELECT ON marca TO grp_soporte;
--     WARNING:  no privileges were granted for "marca"
--     -- ...y cero filas nuevas en el ACL.
--
-- Es decir: sin esta funcion, el boton «Conceder» respondera 200, la pantalla
-- dira «hecho» y el motor no habra cambiado NADA. Por eso la funcion, ademas
-- de ejecutar, VERIFICA el privilegio efectivo antes y despues y devuelve si
-- el cambio se aplico de verdad. La pantalla nunca afirma un cambio que el
-- motor no confirmo.
--
-- ---------------------------------------------------------------------------
-- 2) POR QUE ES SEGURA UNA FUNCION SECURITY DEFINER CON ESTE PODER
-- ---------------------------------------------------------------------------
-- Corre como `postgres`, asi que hay que acotarla por los cuatro lados. Mismo
-- patron que `fn_registrar_intento_acceso` (script 53) y
-- `fn_registrar_intento_pago_fallido` (script 52), pero con validacion mucho
-- mas estricta porque aqui lo que se toca es la seguridad misma:
--
--   a. `SET search_path = public` fijado en la definicion: nadie puede
--      secuestrar la resolucion de nombres con un esquema temporal.
--   b. EXECUTE revocado a PUBLIC y otorgado SOLO a `grp_administrador`.
--   c. LISTA BLANCA en los cuatro parametros, contrastada contra el CATALOGO
--      REAL (pg_roles, pg_class, pg_attribute) antes de construir nada. Un
--      nombre que no existe en el catalogo no llega a la sentencia.
--   d. La sentencia se arma con `format('%I')` — identificadores CITADOS por
--      el propio motor— y el privilegio es una palabra clave tomada de un
--      conjunto CERRADO de cuatro valores. En ningun punto se concatena texto
--      del usuario.
--
-- ---------------------------------------------------------------------------
-- 3) LA LISTA DE PROTEGIDOS (se repite en Java; esta es la que MANDA)
-- ---------------------------------------------------------------------------
-- La validacion vive en los DOS lados a proposito. Java rechaza antes de
-- viajar —para dar un mensaje util—, pero la barrera que cuenta es esta: es la
-- que sigue en pie si alguien llama a la funcion por fuera de la aplicacion.
--
--   R1. El rol `grp_administrador` NO SE TOCA, ni conceder ni revocar.
--       Es el rol con el que corre el propio administrador que usa esta
--       pantalla: revocarle algo puede dejarlo sin poder leer `usuario`,
--       `grupo_horario` o `log_auditoria`, que es exactamente lo que necesita
--       para volver a entrar y deshacerlo. Conceder es ademas inutil: ya tiene
--       ALL sobre las 113 relaciones.
--
--   R2. Las 8 tablas del NUCLEO DE IDENTIDAD Y SEGURIDAD no se tocan en
--       NINGUNA direccion:
--         usuario, usuario_rol, rol   → identidad. Un GRANT aqui es escalada
--             de privilegio, no un permiso mas: `usuario` contiene
--             `password_hash`, y dar SELECT a `grp_cliente` entregaria los
--             hashes de los 10 usuarios del personal. La direccion peligrosa
--             de estas tres es CONCEDER, no revocar.
--         grupo_horario              → la compuerta horaria. Quien la lee
--             decide quien puede entrar; se administra en su propia pantalla,
--             con sus propias guardias (script 53 + Fase 2 del patron de UI).
--         log_auditoria, log_acceso  → el rastro. Revocar el INSERT de
--             `log_auditoria` no le quita acceso a nadie: solo CIEGA la
--             auditoria — incluida la de esta misma pantalla, que es la
--             proteccion C del requisito— y ademas rompe los flujos que la
--             escriben (crear pedido interno, despachar, registrar factura de
--             compra, moderar). Nunca es una mejora de seguridad.
--         permiso, rol_permiso       → vestigiales y VACIAS. El control de
--             acceso real lo hace el motor; se protegen para que nadie las
--             confunda con la autoridad y crea que administra algo tocandolas.
--
--   R3. DESTINATARIOS: solo los 9 roles `grp_*` (NOLOGIN, sin BYPASSRLS).
--       Es una lista BLANCA, no una negra, y de ahi salen tres protecciones
--       del requisito por CONSTRUCCION, no por enumeracion:
--         · `retailmind_app`  no es un destinatario posible. Sus 10 privilegios
--           (SELECT sobre `rol`, SELECT/INSERT/UPDATE sobre `usuario`,
--           SELECT/INSERT sobre `usuario_rol` y las 2 secuencias) son los que
--           usa el LOGIN, antes de que exista rol que asumir: sin ellos nadie
--           vuelve a entrar al sistema, jamas.
--         · `retailmind_etl`  tampoco. Sus 53 SELECT alimentan el pipeline
--           nocturno: quitarle uno no da error visible hasta la carga de las
--           02:00, y entonces la tabla del DWH se publica con menos filas o el
--           informe compuesto sale vacio sin un solo mensaje.
--         · `postgres` y `PUBLIC` tampoco: no son roles de negocio.
--
--   R4. ALCANCE: solo privilegios de TABLA y de COLUMNA, y solo
--       SELECT/INSERT/UPDATE/DELETE. Quedan fuera POR CONSTRUCCION —no hay
--       parametro que los exprese— otras dos protecciones del requisito:
--         · `USAGE ON SCHEMA public`: sin el, el rol deja de ver absolutamente
--           todo (el script 19 se lo revoco a PUBLIC, asi que es el unico
--           camino de entrada al esquema). Esta funcion no concede ni revoca
--           privilegios de ESQUEMA.
--         · Las MEMBRESIAS de `retailmind_app` sobre los 9 grupos: sin ellas
--           `SET LOCAL ROLE` falla y la aplicacion entera responde 403. Esta
--           funcion no toca `pg_auth_members`.
--       TRUNCATE, REFERENCES, TRIGGER y MAINTAIN quedan fuera por no aportar
--       nada al caso de uso y ser TRUNCATE ademas destructivo.
--
-- ---------------------------------------------------------------------------
-- 4) LO QUE ESTE SCRIPT *NO* HACE
-- ---------------------------------------------------------------------------
-- No crea tablas, no modifica ninguna existente, no toca politicas RLS ni
-- privilegios ya otorgados. Solo agrega una funcion y su EXECUTE. La LECTURA
-- del mapa de seguridad no necesita nada de aqui: `grp_administrador` ya lee
-- pg_catalog entero (verificado: 9/95/109/1.467 identicos a los del
-- superusuario), asi que la pantalla consulta los catalogos directamente.
--
-- Idempotente y transaccional.
-- ============================================================================

BEGIN;

-- ── Cambio de privilegio de tabla o columna (SECURITY DEFINER; ver §1 y §2) ──
CREATE OR REPLACE FUNCTION fn_admin_cambiar_permiso(
    p_accion     text,             -- 'conceder' | 'revocar'
    p_rol        text,             -- uno de los 9 grp_*
    p_tabla      text,             -- tabla base de public
    p_privilegio text,             -- SELECT | INSERT | UPDATE | DELETE
    p_columna    text DEFAULT NULL -- NULL = privilegio de TABLA
)
RETURNS TABLE (aplicado boolean, antes boolean, despues boolean, sentencia text)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
    --  R2 en DOS niveles, porque la direccion peligrosa no es la misma:
    --
    --  IDENTIDAD -> ni conceder ni revocar. Un GRANT aqui es ESCALADA
    --  (`usuario` lleva password_hash) y un REVOKE rompe el login.
    c_identidad constant text[] := ARRAY[
        'usuario', 'usuario_rol', 'rol', 'permiso', 'rol_permiso'];
    --  RASTRO y COMPUERTA -> revocar NUNCA (cegar la auditoria o dejar sin
    --  compuerta no es una mejora de seguridad), pero CONCEDER lo seguro SI:
    --  un rol nuevo que no pueda escribir en `log_auditoria` no puede ejecutar
    --  ninguna accion auditada, y prohibirlo no protegia nada.
    c_rastro    constant text[] := ARRAY['log_auditoria', 'log_acceso'];

    v_accion text := lower(btrim(coalesce(p_accion, '')));
    v_rol    text := btrim(coalesce(p_rol, ''));
    v_tabla  text := btrim(coalesce(p_tabla, ''));
    v_priv   text := upper(btrim(coalesce(p_privilegio, '')));
    v_col    text := nullif(btrim(coalesce(p_columna, '')), '');
    v_sql    text;
    v_antes  boolean;
    v_desp   boolean;
BEGIN
    -- ── R4: accion y privilegio, conjuntos CERRADOS ─────────────────────────
    IF v_accion NOT IN ('conceder', 'revocar') THEN
        RAISE EXCEPTION 'Accion no permitida: %. Solo conceder o revocar.', p_accion
            USING ERRCODE = '22023';
    END IF;

    IF v_priv NOT IN ('SELECT', 'INSERT', 'UPDATE', 'DELETE') THEN
        RAISE EXCEPTION 'Privilegio no permitido: %. Solo SELECT, INSERT, UPDATE o DELETE.',
            p_privilegio USING ERRCODE = '22023';
    END IF;

    -- DELETE no existe a nivel de columna en PostgreSQL: borrar es de la fila
    -- entera. Se rechaza aqui para no emitir una sentencia que el motor
    -- rechazaria con un mensaje mucho menos claro.
    IF v_col IS NOT NULL AND v_priv = 'DELETE' THEN
        RAISE EXCEPTION 'DELETE no existe como privilegio de columna: se borra la fila completa.'
            USING ERRCODE = '22023';
    END IF;

    -- ── R3: destinatario, LISTA BLANCA contra el catalogo real ──────────────
    -- Se exige que el rol EXISTA, que se llame grp_*, que no pueda hacer LOGIN
    -- y que no tenga BYPASSRLS. Los tres atributos juntos describen exactamente
    -- a los 9 roles de grupo y excluyen a retailmind_app, retailmind_etl y
    -- postgres aunque alguien renombrara algo.
    IF NOT EXISTS (
        SELECT 1 FROM pg_roles
        WHERE rolname = v_rol
          AND rolname LIKE 'grp\_%'
          AND NOT rolcanlogin
          AND NOT rolbypassrls
    ) THEN
        RAISE EXCEPTION 'Rol no administrable desde esta pantalla: %. Solo los 9 roles de grupo grp_*.',
            p_rol USING ERRCODE = '22023';
    END IF;

    -- ── R1: grp_administrador no se toca ────────────────────────────────────
    IF v_rol = 'grp_administrador' THEN
        RAISE EXCEPTION 'PERMISO PROTEGIDO: grp_administrador es el rol con el que operas. '
            'Revocarle un privilegio puede dejarte sin poder entrar a deshacerlo.'
            USING ERRCODE = '42501';
    END IF;

    -- ── R2a: nucleo de IDENTIDAD, intocable en ambas direcciones ────────────
    IF v_tabla = ANY (c_identidad) THEN
        RAISE EXCEPTION 'PERMISO PROTEGIDO: la tabla % es del nucleo de identidad del '
            'sistema (contiene password_hash o el reparto de roles) y no se administra '
            'desde esta pantalla, ni para conceder ni para revocar.', v_tabla
            USING ERRCODE = '42501';
    END IF;

    -- ── R2b: rastro y compuerta horaria ─────────────────────────────────────
    IF v_tabla = ANY (c_rastro) OR v_tabla = 'grupo_horario' THEN
        IF v_accion = 'revocar' THEN
            RAISE EXCEPTION 'PERMISO PROTEGIDO: no se revoca nada sobre %. Cegar el rastro '
                'de auditoria o dejar un rol sin compuerta horaria no le quita acceso a '
                'nadie: solo apaga el control.', v_tabla USING ERRCODE = '42501';
        END IF;
        IF v_tabla = 'grupo_horario' AND v_priv <> 'SELECT' THEN
            RAISE EXCEPTION 'Sobre grupo_horario solo se puede conceder SELECT: escribir en '
                'ella es mover la compuerta de acceso, y eso se hace en Horarios de Acceso.'
                USING ERRCODE = '42501';
        END IF;
        IF v_tabla = ANY (c_rastro) AND v_priv NOT IN ('SELECT', 'INSERT') THEN
            RAISE EXCEPTION 'Sobre % solo se puede conceder SELECT o INSERT: es un registro '
                'de solo anexar, y UPDATE o DELETE permitirian reescribir el rastro.',
                v_tabla USING ERRCODE = '42501';
        END IF;
    END IF;

    -- ── Objeto: tiene que existir y ser una TABLA BASE de public ────────────
    IF NOT EXISTS (
        SELECT 1 FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'public' AND c.relname = v_tabla AND c.relkind = 'r'
    ) THEN
        RAISE EXCEPTION 'No existe la tabla public.%', v_tabla USING ERRCODE = '42P01';
    END IF;

    IF v_col IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM pg_attribute a
        JOIN pg_class c ON c.oid = a.attrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'public' AND c.relname = v_tabla
          AND a.attname = v_col AND a.attnum > 0 AND NOT a.attisdropped
    ) THEN
        RAISE EXCEPTION 'No existe la columna %.%', v_tabla, v_col USING ERRCODE = '42703';
    END IF;

    -- ── Estado ANTES (privilegio EFECTIVO, no la entrada del ACL) ───────────
    IF v_col IS NULL THEN
        v_antes := has_table_privilege(v_rol, format('public.%I', v_tabla), v_priv);
    ELSE
        v_antes := has_column_privilege(v_rol, format('public.%I', v_tabla), v_col, v_priv);
    END IF;

    -- ── Sentencia ───────────────────────────────────────────────────────────
    -- %I cita los identificadores; v_priv es una de cuatro palabras clave ya
    -- validadas contra el conjunto cerrado de arriba. No hay concatenacion de
    -- texto libre del usuario en ningun punto.
    IF v_col IS NULL THEN
        v_sql := format(
            CASE WHEN v_accion = 'conceder'
                 THEN 'GRANT %s ON TABLE public.%I TO %I'
                 ELSE 'REVOKE %s ON TABLE public.%I FROM %I' END,
            v_priv, v_tabla, v_rol);
    ELSE
        v_sql := format(
            CASE WHEN v_accion = 'conceder'
                 THEN 'GRANT %s (%I) ON TABLE public.%I TO %I'
                 ELSE 'REVOKE %s (%I) ON TABLE public.%I FROM %I' END,
            v_priv, v_col, v_tabla, v_rol);
    END IF;

    EXECUTE v_sql;

    -- ── Estado DESPUES: la funcion no cree en la sentencia, la comprueba ────
    IF v_col IS NULL THEN
        v_desp := has_table_privilege(v_rol, format('public.%I', v_tabla), v_priv);
    ELSE
        v_desp := has_column_privilege(v_rol, format('public.%I', v_tabla), v_col, v_priv);
    END IF;

    aplicado  := (v_antes IS DISTINCT FROM v_desp);
    antes     := v_antes;
    despues   := v_desp;
    sentencia := v_sql;
    RETURN NEXT;
END $$;

COMMENT ON FUNCTION fn_admin_cambiar_permiso(text, text, text, text, text) IS
    'GRANT/REVOKE de tabla o columna para los 9 roles grp_*, con lista de '
    'protegidos y verificacion del efecto. Script 86. Solo grp_administrador.';

-- Solo el administrador. El resto de roles no puede ni verla.
REVOKE ALL ON FUNCTION fn_admin_cambiar_permiso(text, text, text, text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION fn_admin_cambiar_permiso(text, text, text, text, text)
    TO grp_administrador;

COMMIT;
