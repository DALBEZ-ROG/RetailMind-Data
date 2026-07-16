-- ============================================================================
-- 37_rol_soporte.sql — RetailMind · 9º rol: SOPORTE (2026-07-15)
--
-- Crea el rol de atención al cliente en las DOS capas y robustece tickets:
--   1) Motor: grp_soporte (NOLOGIN) asumible por retailmind_app vía
--      SET LOCAL ROLE (PgSessionRoleAspect mapea SOPORTE → grp_soporte).
--   2) App: fila en rol (SOPORTE) + usuario de prueba
--      soporte@retailmind.com / Retail2026! (BCrypt pgcrypto, patrón script 27).
--   3) categoria_ticket.prioridad_defecto: la prioridad del ticket se asigna
--      AUTOMÁTICAMENTE según la categoría (el cliente NO la elige) + seed de
--      categorías reales de atención.
--
-- TODO es ADITIVO: no se quita ni cambia ningún privilegio/política de los 8
-- roles existentes (verificado tras aplicar: la matriz previa queda igual).
--
-- Privilegios de grp_soporte (mínimo necesario para su función):
--   * ticket_soporte  SELECT/INSERT/UPDATE   (bandeja, tomar, estado, prioridad)
--   * mensaje_ticket  SELECT/INSERT          (hilo con el cliente + notas internas)
--   * categoria_ticket SELECT/INSERT/UPDATE  (clasificar y mantener motivos)
--   * faq SELECT                              (centro de ayuda)
--   * LECTURA de apoyo: cliente, pedido(+detalle, estado), factura_venta,
--     devolucion(+detalle)  — para ayudar al cliente y validar devoluciones
--   * usuario/usuario_rol/rol SELECT          (mostrar agente asignado)
--   * NADA de finanzas (pago/cxp) ni escritura de inventario.
--
-- RLS: las políticas pol_horario existentes enumeran los grupos → se agregan
-- políticas propias pol_soporte (horario de grp_soporte) SIN tocar las demás.
-- Horario: soporte atiende 24/7 (00:00–24:00 los 7 días) — decisión documentada:
-- la atención al cliente no cierra aunque la operación interna sí.
-- Idempotente.
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ----------------------------------------------------------------- 1) Rol motor
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'grp_soporte') THEN
        CREATE ROLE grp_soporte NOLOGIN;
        RAISE NOTICE 'Rol grp_soporte creado';
    END IF;
END $$;

GRANT grp_soporte TO retailmind_app;

-- El script 19 revocó el schema public a PUBLIC y lo concede por grupo: sin
-- USAGE, para grp_soporte las tablas "no existen" (falla la resolución de
-- nombres). Mismo tratamiento que los otros 8 grupos.
GRANT USAGE ON SCHEMA public TO grp_soporte;

-- ------------------------------------------------------------- 2) Privilegios
GRANT SELECT, INSERT, UPDATE ON ticket_soporte   TO grp_soporte;
GRANT SELECT, INSERT         ON mensaje_ticket   TO grp_soporte;
GRANT USAGE ON SEQUENCE ticket_soporte_id_seq, mensaje_ticket_id_seq TO grp_soporte;
GRANT SELECT, INSERT, UPDATE ON categoria_ticket TO grp_soporte;
GRANT USAGE ON SEQUENCE categoria_ticket_id_seq  TO grp_soporte;
GRANT SELECT ON faq TO grp_soporte;

-- Lectura de apoyo (ayudar al cliente / validar devoluciones)
GRANT SELECT ON cliente, pedido, pedido_detalle, estado_pedido,
                factura_venta, devolucion, devolucion_detalle,
                motivo_devolucion TO grp_soporte;

-- Mostrar el agente asignado / validar que el asignado es personal interno
GRANT SELECT ON usuario, usuario_rol, rol TO grp_soporte;

-- Reapertura por el cliente: si responde un ticket 'resuelto', el backend lo
-- regresa a 'en_proceso' EN SU MISMA transacción (grp_cliente). Grant de
-- COLUMNA (solo estado); pol_cliente_propio (script 29) limita la fila a los
-- tickets propios. Es el único cambio sobre un rol preexistente y es aditivo.
GRANT UPDATE (estado) ON ticket_soporte TO grp_cliente;

-- ------------------------------------------------- 3) RLS para grp_soporte
-- Las tablas con RLS enumeran los grupos en pol_horario: grp_soporte necesita
-- política propia o vería 0 filas. Patrón idéntico (horario del grupo).
DO $$
DECLARE
    v_tabla text;
BEGIN
    -- Escritura + lectura en las tablas del módulo
    FOREACH v_tabla IN ARRAY ARRAY['ticket_soporte','mensaje_ticket'] LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname='public'
                       AND tablename = v_tabla AND policyname = 'pol_soporte') THEN
            EXECUTE format(
                'CREATE POLICY pol_soporte ON public.%I FOR ALL TO grp_soporte
                 USING (esta_en_horario(''grp_soporte''))
                 WITH CHECK (esta_en_horario(''grp_soporte''))', v_tabla);
        END IF;
    END LOOP;

    -- Solo lectura en las tablas de apoyo que tienen RLS habilitado
    FOREACH v_tabla IN ARRAY ARRAY['cliente','pedido','pedido_detalle','factura_venta'] LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname='public'
                       AND tablename = v_tabla AND policyname = 'pol_soporte') THEN
            EXECUTE format(
                'CREATE POLICY pol_soporte ON public.%I FOR SELECT TO grp_soporte
                 USING (esta_en_horario(''grp_soporte''))', v_tabla);
        END IF;
    END LOOP;
END $$;

-- --------------------------------------------------- 4) Horario: 24/7 soporte
INSERT INTO grupo_horario (rol_grupo, dia_semana, hora_inicio, hora_fin, activo)
SELECT 'grp_soporte', d, '00:00'::time, '24:00'::time, true
FROM generate_series(0, 6) AS d
WHERE NOT EXISTS (SELECT 1 FROM grupo_horario gh
                  WHERE gh.rol_grupo = 'grp_soporte' AND gh.dia_semana = d);

-- ------------------------------------------- 5) Rol de app + usuario de prueba
INSERT INTO rol (codigo, nombre, descripcion, es_sistema, activo)
SELECT 'SOPORTE', 'Agente de Soporte',
       'Atención al cliente: bandeja de tickets, hilo de mensajes y FAQ', true, true
WHERE NOT EXISTS (SELECT 1 FROM rol WHERE codigo = 'SOPORTE');

INSERT INTO usuario (email, password_hash, nombre, apellido, email_verificado, activo)
SELECT 'soporte@retailmind.com', crypt('Retail2026!', gen_salt('bf', 10)),
       'Soporte', 'Prueba', true, true
WHERE NOT EXISTS (SELECT 1 FROM usuario WHERE lower(email) = 'soporte@retailmind.com');

INSERT INTO usuario_rol (usuario_id, rol_id)
SELECT u.id, r.id
FROM usuario u, rol r
WHERE lower(u.email) = 'soporte@retailmind.com' AND r.codigo = 'SOPORTE'
ON CONFLICT (usuario_id, rol_id) DO NOTHING;

-- ------------------------- 6) Prioridad automática por categoría + seed real
ALTER TABLE categoria_ticket
    ADD COLUMN IF NOT EXISTS prioridad_defecto varchar(10) NOT NULL DEFAULT 'media';

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'categoria_ticket_prioridad_defecto_check') THEN
        ALTER TABLE categoria_ticket
            ADD CONSTRAINT categoria_ticket_prioridad_defecto_check
            CHECK (prioridad_defecto IN ('baja', 'media', 'alta', 'urgente'));
    END IF;
END $$;

-- Normaliza la categoría legacy sin tilde y siembra los motivos reales
UPDATE categoria_ticket SET nombre = 'Facturación' WHERE nombre = 'Facturacion';

INSERT INTO categoria_ticket (nombre, descripcion, prioridad_defecto)
SELECT v.nombre, v.descripcion, v.prioridad
FROM (VALUES
    ('Consulta general',     'Dudas generales sobre la tienda o los productos', 'baja'),
    ('Problema con pedido',  'Pedidos demorados, incompletos o con errores',    'media'),
    ('Devolución',           'Solicitudes y seguimiento de devoluciones',       'media'),
    ('Reclamo',              'Quejas formales sobre el servicio o la compra',   'alta'),
    ('Facturación',          'Errores o dudas con facturas y cobros',           'media'),
    ('Producto defectuoso',  'Producto dañado, incompleto o que no funciona',   'alta'),
    ('Sugerencia',           'Ideas y mejoras propuestas por los clientes',     'baja')
) AS v(nombre, descripcion, prioridad)
WHERE NOT EXISTS (SELECT 1 FROM categoria_ticket c
                  WHERE lower(c.nombre) = lower(v.nombre));

-- Prioridad por defecto de las sembradas (por si ya existían sin ella)
UPDATE categoria_ticket c SET prioridad_defecto = v.prioridad
FROM (VALUES
    ('Consulta general', 'baja'), ('Problema con pedido', 'media'),
    ('Devolución', 'media'), ('Reclamo', 'alta'), ('Facturación', 'media'),
    ('Producto defectuoso', 'alta'), ('Sugerencia', 'baja'), ('Envios', 'media')
) AS v(nombre, prioridad)
WHERE lower(c.nombre) = lower(v.nombre) AND c.prioridad_defecto <> v.prioridad;
