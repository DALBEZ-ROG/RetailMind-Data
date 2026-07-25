-- ============================================================================
-- 82_log_acceso_historico.sql
-- OBJETIVO TACTICO OTD-GER-09 (informe de intentos de acceso).
-- Seccion 8 de docs/tactico/AUDITORIA_TRANSVERSAL_SEED.md.
--
-- ANTES: log_acceso con 39 filas, TODAS de julio-2026 (las del E2E del script
-- 53). El informe de accesos solo puede dibujar un mes.
--
-- Que hace: siembra 1.400 intentos de acceso entre el 2025-01-13 (primer
-- pedido del sistema) y el 2026-07-24, con la estructura EXACTA que escribe
-- el login real (script 53 / fn de registro de acceso):
--   * usuario_id + email_intentado + exitoso + motivo_fallo + ip_origen +
--     user_agent. NUNCA se inventan usuarios: todos salen de la tabla usuario.
--   * ~87 % exitosos. Los 4 motivos de fallo son los de
--     LoginFallidoException (= lista de log_acceso.motivo_fallo):
--       email_no_registrado -> usuario_id NULL (correo tecleado mal o de
--                              alguien que ya no trabaja aqui)
--       password_incorrecto -> usuario identificado, clave equivocada
--       fuera_horario       -> SOLO personal interno, en franja nocturna o en
--                              domingo (es exactamente lo que bloquea
--                              grupo_horario + esta_en_horario)
--       usuario_inactivo    -> cuenta deshabilitada en ese momento
--   * PERSONAL (60 %): entra en dias habiles entre 07:00 y 18:59, desde la LAN
--     de oficina (192.168.10.x) o su conexion fija; navegador de escritorio.
--     CLIENTES (40 %): entran a cualquier hora, con sesgo a la tarde-noche,
--     desde IPs de ISP ecuatoriano; mezcla de escritorio y movil.
--   * La densidad CRECE con el tiempo (exponente 0,8 sobre el eje temporal):
--     el negocio triplica su volumen entre 2025 y 2026 y los accesos lo siguen.
--
-- Escritura pura de bitacora: no toca login, ni Spring Security, ni ninguna
-- otra tabla. Marca 'seed_op_82_log_acceso'. Idempotente y transaccional.
-- Ejecutar como postgres sobre la BD retailmind.
-- ============================================================================
\set ON_ERROR_STOP on

BEGIN;

DO $$
DECLARE
    v_n int := 0;
BEGIN
    IF EXISTS (SELECT 1 FROM configuracion_tienda WHERE clave = 'seed_op_82_log_acceso') THEN
        RAISE NOTICE 'Objetivo 3 (log de acceso) ya sembrado; se omite.';
        RETURN;
    END IF;

    -- personal interno con peso (admin/gerente/vendedor entran mas seguido)
    CREATE TEMP TABLE op82_cfg ON COMMIT DROP AS
    SELECT ARRAY[2,2,2,2,6,6,6,6,7,7,7,7,9,9,9,10,10,10,11,11,12,12,13,13,14,15,16,17,18,19,8,20]::bigint[] AS staff,
           (SELECT array_agg(u.id ORDER BY u.id)
            FROM usuario u JOIN cliente c ON c.usuario_id = u.id)                       AS clientes,
           ARRAY['Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36',
                 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36 Edg/137.0.0.0',
                 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:129.0) Gecko/20100101 Firefox/129.0',
                 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15']::text[] AS ua_escritorio,
           ARRAY['Mozilla/5.0 (Linux; Android 14; SM-A546E) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36',
                 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1',
                 'Mozilla/5.0 (Linux; Android 13; Redmi Note 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Mobile Safari/537.36']::text[] AS ua_movil,
           ARRAY['jsandoval@retailmind.com','contabilidad@retailmind.com','admin@retailmid.com',
                 'ventas@retailmind.com','pmoran@retailmind.com','bodega@retailmind.co',
                 'gerencia@retailmind.com','soporte@retailmnd.com']::text[]            AS correos_muertos;

    INSERT INTO log_acceso (usuario_id, email_intentado, exitoso, motivo_fallo,
                            ip_origen, user_agent, fecha_creacion)
    SELECT
        CASE WHEN e.motivo = 'email_no_registrado' THEN NULL ELSE e.usuario_id END,
        CASE WHEN e.motivo = 'email_no_registrado'
             THEN cfg.correos_muertos[1 + (e.r / 3 % array_length(cfg.correos_muertos, 1))]
             ELSE u.email END,
        e.motivo IS NULL,
        e.motivo,
        (CASE
            WHEN e.es_staff AND e.r % 3 <> 0
                THEN '192.168.10.' || (11 + e.r % 40)
            WHEN e.es_staff
                THEN '186.4.' || (e.r / 5 % 250) || '.' || (1 + e.r / 7 % 250)
            WHEN e.r % 3 = 0
                THEN '190.95.' || (e.r / 5 % 250) || '.' || (1 + e.r / 7 % 250)
            WHEN e.r % 3 = 1
                THEN '181.39.' || (e.r / 5 % 250) || '.' || (1 + e.r / 7 % 250)
            ELSE '186.101.' || (e.r / 5 % 250) || '.' || (1 + e.r / 7 % 250)
         END)::inet,
        CASE WHEN e.es_staff OR e.r % 5 < 2
             THEN cfg.ua_escritorio[1 + (e.r / 11 % array_length(cfg.ua_escritorio, 1))]
             ELSE cfg.ua_movil[1 + (e.r / 11 % array_length(cfg.ua_movil, 1))] END,
        e.fecha
    FROM (
        SELECT b.*,
               -- fuera_horario: se fuerza a franja nocturna (el resto ya es habil)
               CASE WHEN b.motivo = 'fuera_horario'
                    THEN date_trunc('day', b.fecha_base)
                         + ((CASE WHEN b.r % 2 = 0 THEN 3 ELSE 21 END) || ' hour')::interval
                         + ((b.r / 13 % 60) || ' minute')::interval
                         + ((b.r / 17 % 60) || ' second')::interval
                    ELSE b.fecha_base END AS fecha
        FROM (
            SELECT s.seq, s.r,
                   (s.r % 100) < 60 AS es_staff,
                   -- motivo del fallo (NULL = login exitoso, ~87 %)
                   CASE
                     WHEN (s.r / 23 % 100) >= 13 THEN NULL
                     WHEN (s.r % 100) < 60 THEN                       -- personal
                          CASE WHEN (s.r / 29 % 100) < 55 THEN 'password_incorrecto'
                               WHEN (s.r / 29 % 100) < 80 THEN 'fuera_horario'
                               WHEN (s.r / 29 % 100) < 95 THEN 'email_no_registrado'
                               ELSE 'usuario_inactivo' END
                     ELSE                                            -- clientes
                          CASE WHEN (s.r / 29 % 100) < 60 THEN 'password_incorrecto'
                               WHEN (s.r / 29 % 100) < 90 THEN 'email_no_registrado'
                               ELSE 'usuario_inactivo' END
                   END AS motivo,
                   CASE WHEN (s.r % 100) < 60
                        THEN cfg2.staff[1 + (s.r / 31 % array_length(cfg2.staff, 1))]
                        ELSE cfg2.clientes[1 + (s.r / 31 % array_length(cfg2.clientes, 1))]
                   END AS usuario_id,
                   -- eje temporal con densidad creciente + horario segun perfil
                   (SELECT d + CASE WHEN (s.r % 100) < 60 AND extract(dow FROM d) = 0
                                    THEN interval '1 day' ELSE interval '0' END
                    FROM (SELECT (timestamptz '2025-01-13 00:00:00-05'
                                  + (floor(557 * power(s.seq / 1400.0, 0.8))::int || ' day')::interval
                                  + (CASE WHEN (s.r % 100) < 60
                                          THEN 7 + (s.r / 3 % 12)          -- personal: 07-18
                                          ELSE (ARRAY[8,9,10,11,12,13,14,15,16,17,18,19,19,20,20,21,21,22,23,0,1,7])
                                               [1 + (s.r / 3 % 22)]        -- clientes: sesgo tarde-noche
                                     END || ' hour')::interval
                                  + ((s.r / 37 % 60) || ' minute')::interval
                                  + ((s.r / 41 % 60) || ' second')::interval) AS d) x
                   ) AS fecha_base
            FROM (SELECT g AS seq,
                         (('x' || substr(md5('la' || g::text), 1, 8))::bit(32)::bigint
                          & 2147483647) AS r
                  FROM generate_series(1, 1400) g) s
            CROSS JOIN op82_cfg cfg2
        ) b
    ) e
    CROSS JOIN op82_cfg cfg
    LEFT JOIN usuario u ON u.id = e.usuario_id;

    GET DIAGNOSTICS v_n = ROW_COUNT;

    INSERT INTO configuracion_tienda (clave, valor, tipo_dato, descripcion)
    VALUES ('seed_op_82_log_acceso',
            jsonb_build_object('fecha', now(), 'log_acceso', v_n)::text,
            'json', 'OTD-GER-09 (log de acceso historico) — script 82')
    ON CONFLICT (clave) DO UPDATE SET valor = EXCLUDED.valor;

    RAISE NOTICE 'log_acceso: % filas nuevas', v_n;
END $$;

COMMIT;

\echo '--- OTD-GER-09: resultado y motivos ---'
SELECT exitoso, COALESCE(motivo_fallo, '(exitoso)') motivo, count(*),
       round(100.0 * count(*) / sum(count(*)) OVER (), 1) pct
FROM log_acceso GROUP BY 1,2 ORDER BY 1,3 DESC;

\echo '--- OTD-GER-09: cobertura temporal ---'
SELECT count(*) filas, count(DISTINCT date_trunc('month', fecha_creacion)) meses,
       min(fecha_creacion)::date desde, max(fecha_creacion)::date hasta,
       count(DISTINCT usuario_id) usuarios FROM log_acceso;
