-- ============================================================================
-- 30_grants_consolidacion.sql — RetailMind · Ajustes de privilegios (deuda técnica)
--  Tres ajustes de mínimo privilegio detectados en la auditoría de seguridad:
--
--  1) grp_gerente NO debe leer usuario.password_hash. El script 19 le dio
--     SELECT sobre TODAS las tablas (incluida usuario completa); aquí se
--     revoca y se re-otorga POR COLUMNAS (mismo patrón que grp_analista).
--     OJO: si se re-ejecuta 19_privilegios.sql, su "GRANT SELECT ON ALL
--     TABLES" vuelve a abrir usuario completa — re-ejecutar este script
--     SIEMPRE después del 19.
--
--  2) grp_gerente audita sus aprobaciones de orden de compra: INSERT sobre
--     log_auditoria + USAGE de su secuencia (hasta ahora solo grp_administrador
--     podía escribir la bitácora, y la aprobación de un gerente quedaba sin
--     rastro — ver ComprasService.aprobarOrden).
--
--  3) grp_cliente ve el historial de estados de SUS pedidos: SELECT sobre
--     historial_estado_pedido + política RLS de propiedad heredada de pedido
--     (la tabla ya tiene RLS habilitada por 21_rls.sql con pol_horario para
--     los grupos internos; sin política propia el cliente veía 0 filas).
--
--  Idempotente.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1) grp_gerente: usuario por columnas, sin password_hash
-- ----------------------------------------------------------------------------
REVOKE SELECT ON usuario FROM grp_gerente;
GRANT SELECT (id, email, nombre, apellido, telefono, email_verificado,
              ultimo_acceso, intentos_fallidos, bloqueado_hasta, activo,
              fecha_creacion, fecha_actualizacion)
    ON usuario TO grp_gerente;

-- ----------------------------------------------------------------------------
-- 2) grp_gerente: bitácora de auditoría de sus aprobaciones
-- ----------------------------------------------------------------------------
GRANT INSERT ON log_auditoria TO grp_gerente;

DO $$
DECLARE
    v_seq text := pg_get_serial_sequence('public.log_auditoria', 'id');
BEGIN
    IF v_seq IS NOT NULL THEN
        EXECUTE format('GRANT USAGE, SELECT ON SEQUENCE %s TO grp_gerente', v_seq);
    END IF;
END $$;

-- ----------------------------------------------------------------------------
-- 3) grp_cliente: historial de estados de SUS pedidos (solo lectura)
-- ----------------------------------------------------------------------------
GRANT SELECT ON historial_estado_pedido TO grp_cliente;

DROP POLICY IF EXISTS pol_cliente_propio ON historial_estado_pedido;
CREATE POLICY pol_cliente_propio ON historial_estado_pedido
    FOR SELECT TO grp_cliente
    USING (esta_en_horario('grp_cliente')
           AND pedido_id IN (SELECT id FROM pedido WHERE cliente_id = fn_cliente_actual()));
