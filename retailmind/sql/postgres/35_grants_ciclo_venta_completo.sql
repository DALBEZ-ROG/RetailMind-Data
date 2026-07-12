-- ============================================================================
-- 35_grants_ciclo_venta_completo.sql — Ciclo de venta completo (2026-07-12)
--
-- El ciclo de venta pasa a: confirmado -> PAGO del cliente -> pagado ->
-- factura -> despacho -> entregado -> (devolución). Este script agrega SOLO
-- los privilegios que faltaban para operarlo con SET LOCAL ROLE. Idempotente.
--
--  * grp_vendedor cobra el pedido: INSERT en pago + transaccion_pago (sin RLS:
--    esas tablas no tienen row security habilitado, igual que devolucion).
--    También le faltaba INSERT en historial_estado_pedido, que la app escribe
--    al crear el pedido y en cada transición (bug latente hasta hoy), y
--    SELECT en envio para mostrar la guía en el detalle del pedido.
--  * grp_despacho cambia el estado del pedido al despachar/entregar (UPDATE
--    pedido) y valida las compuertas (SELECT factura_venta y pago).
--  * grp_cliente ve el rastro de SU pedido en "Mis Pedidos": SELECT sobre
--    factura_venta(+detalle), envio(+detalle) y seguimiento_envio, con
--    políticas RLS de propiedad (cliente_id / pedido del cliente) + horario.
--    NO se le concede pago (esa tabla no tiene RLS; su vista del cobro es el
--    historial del pedido).
-- ============================================================================
BEGIN;

-- Personal del ciclo de venta
GRANT SELECT, INSERT ON pago             TO grp_vendedor;
GRANT SELECT, INSERT ON transaccion_pago TO grp_vendedor;
GRANT SELECT, INSERT ON historial_estado_pedido TO grp_vendedor;
GRANT SELECT         ON envio            TO grp_vendedor;

-- Bug latente: el pedido del vendedor descuenta stock vía StockService
-- (upsert inventario + kardex), igual que el checkout del cliente (script 34),
-- pero grp_vendedor nunca recibió esos privilegios (solo admin podía crear
-- pedidos). pol_horario en inventario/movimiento_inventario ya lo cubre.
GRANT INSERT, UPDATE ON inventario            TO grp_vendedor;  -- ya tenía SELECT
GRANT INSERT         ON movimiento_inventario TO grp_vendedor;
GRANT SELECT         ON tipo_movimiento       TO grp_vendedor;
GRANT SELECT         ON bodega                TO grp_vendedor;  -- selector + mensajes de stock

GRANT UPDATE ON pedido        TO grp_despacho;
GRANT SELECT ON factura_venta TO grp_despacho;
GRANT SELECT ON pago          TO grp_despacho;
GRANT SELECT ON metodo_pago   TO grp_despacho;  -- el detalle del pedido muestra pagos con su método

-- Cliente: solo LECTURA de los documentos de sus propios pedidos
GRANT SELECT ON factura_venta         TO grp_cliente;
GRANT SELECT ON factura_venta_detalle TO grp_cliente;
GRANT SELECT ON envio                 TO grp_cliente;
GRANT SELECT ON envio_detalle         TO grp_cliente;
GRANT SELECT ON seguimiento_envio     TO grp_cliente;

-- RLS: las cinco tablas ya tienen row security habilitado (pol_horario para
-- el personal); sin política propia el cliente vería 0 filas. Se agregan
-- políticas de propiedad + horario (mismo patrón que pedido/historial).
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_policies
                   WHERE schemaname = 'public' AND tablename = 'factura_venta'
                     AND policyname = 'pol_cliente_propio') THEN
        CREATE POLICY pol_cliente_propio ON factura_venta FOR SELECT TO grp_cliente
            USING (esta_en_horario('grp_cliente')
                   AND cliente_id = fn_cliente_actual());
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_policies
                   WHERE schemaname = 'public' AND tablename = 'factura_venta_detalle'
                     AND policyname = 'pol_cliente_propio') THEN
        CREATE POLICY pol_cliente_propio ON factura_venta_detalle FOR SELECT TO grp_cliente
            USING (esta_en_horario('grp_cliente')
                   AND factura_venta_id IN (SELECT id FROM factura_venta
                                            WHERE cliente_id = fn_cliente_actual()));
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_policies
                   WHERE schemaname = 'public' AND tablename = 'envio'
                     AND policyname = 'pol_cliente_propio') THEN
        CREATE POLICY pol_cliente_propio ON envio FOR SELECT TO grp_cliente
            USING (esta_en_horario('grp_cliente')
                   AND pedido_id IN (SELECT id FROM pedido
                                     WHERE cliente_id = fn_cliente_actual()));
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_policies
                   WHERE schemaname = 'public' AND tablename = 'envio_detalle'
                     AND policyname = 'pol_cliente_propio') THEN
        CREATE POLICY pol_cliente_propio ON envio_detalle FOR SELECT TO grp_cliente
            USING (esta_en_horario('grp_cliente')
                   AND envio_id IN (SELECT e.id FROM envio e
                                    JOIN pedido p ON p.id = e.pedido_id
                                    WHERE p.cliente_id = fn_cliente_actual()));
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_policies
                   WHERE schemaname = 'public' AND tablename = 'seguimiento_envio'
                     AND policyname = 'pol_cliente_propio') THEN
        CREATE POLICY pol_cliente_propio ON seguimiento_envio FOR SELECT TO grp_cliente
            USING (esta_en_horario('grp_cliente')
                   AND envio_id IN (SELECT e.id FROM envio e
                                    JOIN pedido p ON p.id = e.pedido_id
                                    WHERE p.cliente_id = fn_cliente_actual()));
    END IF;
END$$;

COMMIT;
