-- ============================================================================
-- 28_grants_soporte.sql — RetailMind · Addendum de privilegios (módulo soporte)
--  Activa las tablas de M12 (categoria_ticket, ticket_soporte, mensaje_ticket,
--  faq) para el módulo de atención al cliente:
--    - grp_gerente: gestiona tickets (crear en nombre del cliente, responder,
--      cambiar estado, asignar agente). Ya tenía SELECT por el script 19.
--    - grp_cliente: crea sus tickets y mensajes y los consulta. OJO: estas
--      tablas NO tienen política RLS; el aislamiento por cliente se aplica en
--      la capa de servicio (SoporteService) filtrando por cliente_id del JWT.
--      Si se decide llevarlo al motor, agregar política sobre cliente_id =
--      fn_cliente_actual() como en pedido (script 21) — decisión pendiente.
--  Las escrituras de categoria_ticket y faq siguen siendo solo de
--  grp_administrador (script 19). Idempotente.
-- ============================================================================

-- Gestión de tickets por gerencia
GRANT INSERT, UPDATE ON ticket_soporte TO grp_gerente;
GRANT INSERT          ON mensaje_ticket TO grp_gerente;
GRANT USAGE ON SEQUENCE ticket_soporte_id_seq, mensaje_ticket_id_seq TO grp_gerente;

-- Tickets propios del cliente (aislamiento por servicio; ver nota de cabecera)
GRANT SELECT, INSERT ON ticket_soporte  TO grp_cliente;
GRANT SELECT, INSERT ON mensaje_ticket  TO grp_cliente;
GRANT SELECT         ON categoria_ticket TO grp_cliente;
GRANT USAGE ON SEQUENCE ticket_soporte_id_seq, mensaje_ticket_id_seq TO grp_cliente;
