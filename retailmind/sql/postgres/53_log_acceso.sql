-- ============================================================================
-- 53_log_acceso.sql — Registro de intentos de acceso al sistema
-- (2026-07-23, OTD-GER-09, última brecha del catálogo táctico § 11.1)
--
-- La tabla log_acceso existe completa (exitoso/motivo_fallo/ip_origen/
-- email_intentado/user_agent/usuario_id) pero tiene 0 filas: el flujo de
-- inicio de sesión nunca la escribe, así que no hay trazabilidad de accesos
-- ni de intentos fallidos ("quién intentó entrar, desde dónde y por qué").
--
-- PROBLEMA DE MOTOR (idéntico al del script 52, fn_registrar_intento_pago_
-- fallido): un intento de login FALLIDO ocurre ANTES de que exista
-- autenticación — no hay JWT, no hay usuario, no se asumió ningún grp_* con
-- SET LOCAL ROLE. La transacción corre como retailmind_app (LOGIN NOINHERIT,
-- sin privilegios de negocio), que NO tiene INSERT sobre log_acceso.
--
-- SOLUCIÓN: fn_registrar_intento_acceso (SECURITY DEFINER, corre como el
-- owner = postgres). El backend la invoca desde el flujo de login en una
-- transacción REQUIRES_NEW, de modo que el rastro del intento se CONFIRMA
-- aunque la transacción del intento de autenticación se revierta. La función
-- nunca recibe ni almacena la contraseña (ni fragmento ni hash): solo correo
-- intentado, éxito/fallo, motivo, IP, user-agent y usuario si se identificó.
--
-- LECTURA DEL INFORME: solo Administración y Gerencia. grp_administrador y
-- grp_gerente ya tienen SELECT; se re-otorga idempotente y se REVOCA el SELECT
-- que grp_analista arrastraba de un grant amplio (ningún otro rol debe leer).
-- log_acceso no lleva RLS: es bitácora interna sin filas por cliente.
--
-- Idempotente y transaccional.
-- ============================================================================

BEGIN;

-- ── 1) Registro del intento (SECURITY DEFINER; ver cabecera) ─────────────────
CREATE OR REPLACE FUNCTION fn_registrar_intento_acceso(
    p_usuario_id  bigint,
    p_email       varchar,
    p_exitoso     boolean,
    p_motivo      varchar,
    p_ip          text,
    p_user_agent  text
) RETURNS bigint
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
    v_id bigint;
    v_ip inet;
BEGIN
    -- IP tolerante a valores ausentes o mal formados: si no parsea, va NULL
    -- (un fallo de auditoría jamás debe romper el login).
    BEGIN
        v_ip := NULLIF(btrim(p_ip), '')::inet;
    EXCEPTION WHEN others THEN
        v_ip := NULL;
    END;

    INSERT INTO log_acceso (usuario_id, email_intentado, exitoso, motivo_fallo,
                            ip_origen, user_agent)
    VALUES (p_usuario_id,
            NULLIF(btrim(p_email), ''),
            COALESCE(p_exitoso, false),
            -- el motivo solo tiene sentido en el fallo; en el éxito va NULL
            CASE WHEN COALESCE(p_exitoso, false) THEN NULL
                 ELSE left(NULLIF(btrim(p_motivo), ''), 100) END,
            v_ip,
            NULLIF(btrim(p_user_agent), ''))
    RETURNING id INTO v_id;

    RETURN v_id;
END $$;

-- Quien escribe es el flujo de login (corre como retailmind_app, sin rol de
-- grupo asumido). grp_administrador incluido para pruebas/contexto autenticado.
REVOKE ALL ON FUNCTION fn_registrar_intento_acceso(bigint, varchar, boolean, varchar, text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION fn_registrar_intento_acceso(bigint, varchar, boolean, varchar, text, text)
    TO retailmind_app, grp_administrador;

-- La compuerta horaria del login llama a esta_en_horario() como retailmind_app.
-- Ya tiene EXECUTE (SECURITY DEFINER); se re-otorga idempotente por si acaso.
GRANT EXECUTE ON FUNCTION esta_en_horario(text) TO retailmind_app;

-- ── 2) Lectura del informe: SOLO Administración y Gerencia ───────────────────
GRANT SELECT ON log_acceso TO grp_administrador, grp_gerente;

-- Cierre de brecha: grp_analista arrastraba SELECT de un grant amplio previo.
-- El informe de accesos es atribución de Admin/Gerencia — ningún otro rol lee.
REVOKE SELECT ON log_acceso FROM grp_analista;

COMMIT;
