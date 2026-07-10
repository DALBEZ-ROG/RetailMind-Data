-- ============================================================================
-- 29_rls_soporte.sql — RetailMind · RLS del módulo de soporte
--  Lleva al MOTOR el aislamiento por cliente que hasta ahora solo aplicaba la
--  capa de servicio (SoporteService), cerrando la deuda anotada en
--  28_grants_soporte.sql. Replica los patrones de 21_rls.sql:
--    - pol_horario para los grupos internos (fuera de horario SELECT = 0 filas;
--      la escritura ya la corta el trigger de 20_horario.sql).
--    - pol_cliente_propio para grp_cliente: ticket_soporte por cliente_id
--      directo; mensaje_ticket por propiedad heredada del ticket, excluyendo
--      las notas internas (es_interno) también a nivel de motor.
--  grp_administrador queda exento vía esta_en_horario y el owner (postgres)
--  hace bypass de RLS por definición (sin FORCE).
--  Idempotente: DROP POLICY IF EXISTS + CREATE.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1) Habilitar RLS y política de HORARIO para los grupos internos
-- ----------------------------------------------------------------------------
DO $$
DECLARE
    v_tabla text;
BEGIN
    FOREACH v_tabla IN ARRAY ARRAY['ticket_soporte','mensaje_ticket'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', v_tabla);
        EXECUTE format('DROP POLICY IF EXISTS pol_horario ON public.%I', v_tabla);
        EXECUTE format(
            'CREATE POLICY pol_horario ON public.%I
             FOR ALL
             TO grp_administrador, grp_gerente, grp_vendedor, grp_compras,
                grp_bodega, grp_despacho, grp_analista
             USING (esta_en_horario(fn_grupo_actual()))
             WITH CHECK (esta_en_horario(fn_grupo_actual()))',
            v_tabla);
    END LOOP;
    RAISE NOTICE 'RLS habilitada y politica de horario creada en ticket_soporte y mensaje_ticket';
END $$;

-- ----------------------------------------------------------------------------
-- 2) grp_cliente: solo SUS tickets (cliente_id = fn_cliente_actual())
-- ----------------------------------------------------------------------------
DROP POLICY IF EXISTS pol_cliente_propio ON ticket_soporte;
CREATE POLICY pol_cliente_propio ON ticket_soporte
    FOR ALL TO grp_cliente
    USING      (esta_en_horario('grp_cliente') AND cliente_id = fn_cliente_actual())
    WITH CHECK (esta_en_horario('grp_cliente') AND cliente_id = fn_cliente_actual());

-- ----------------------------------------------------------------------------
-- 3) grp_cliente en mensaje_ticket: propiedad heredada del ticket.
--    Lectura: solo mensajes NO internos de sus tickets.
--    Escritura: solo como él mismo (cliente_id propio) y nunca nota interna.
-- ----------------------------------------------------------------------------
DROP POLICY IF EXISTS pol_cliente_propio ON mensaje_ticket;
CREATE POLICY pol_cliente_propio ON mensaje_ticket
    FOR ALL TO grp_cliente
    USING      (esta_en_horario('grp_cliente')
                AND NOT es_interno
                AND ticket_soporte_id IN
                    (SELECT id FROM ticket_soporte WHERE cliente_id = fn_cliente_actual()))
    WITH CHECK (esta_en_horario('grp_cliente')
                AND NOT es_interno
                AND cliente_id = fn_cliente_actual()
                AND ticket_soporte_id IN
                    (SELECT id FROM ticket_soporte WHERE cliente_id = fn_cliente_actual()));
