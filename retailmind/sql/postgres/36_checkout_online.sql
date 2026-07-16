-- ============================================================================
-- 36_checkout_online.sql — Checkout online que nace PAGADO (2026-07-15)
--
-- El checkout de la tienda del cliente deja de crear pedidos "pendientes de
-- cobro": el cliente PAGA (simulado) como parte del checkout y el pedido nace
-- en estado 'pagado'. El cobro manual del back-office ("registrar pago del
-- cliente") queda reservado a pedidos INTERNOS (canal 'tienda'/'telefono').
-- El discriminador online/interno es la columna existente pedido.canal
-- ('web' = tienda online; 'tienda'/'telefono' = internos). Idempotente.
--
--  * Se agrega el método de pago TARJETA (tipo 'tarjeta'): el checkout online
--    ofrece tarjeta (simulada: solo se guarda marca + últimos 4 dígitos en
--    referencia_externa/respuesta_pasarela, NUNCA el número completo ni CVV)
--    y transferencia. NO se toca el CHECK de metodo_pago (ya admite 'tarjeta').
--  * grp_cliente registra su pago en el checkout: INSERT sobre pago y
--    transaccion_pago + SELECT (id) sobre pago para el RETURNING. Igual que
--    con grp_vendedor (script 35), esas tablas no tienen RLS; la app solo
--    inserta el pago del pedido recién creado dentro de la MISMA transacción.
--    NO se concede SELECT de filas de negocio: la vista del cliente sigue
--    siendo el historial del pedido.
--  * Datos legacy: los pedidos canal 'web' creados con el flujo viejo que
--    quedaron sin pagar (pendiente/confirmado y sin pago completado) se
--    reclasifican a canal 'telefono' para que el vendedor pueda cobrarlos;
--    con la nueva guardia un pedido 'web' sin pagar sería incobrable.
-- ============================================================================
BEGIN;

-- Método de pago con tarjeta (débito/crédito) para el checkout online
INSERT INTO metodo_pago (codigo, nombre, tipo, requiere_pasarela, orden, activo)
SELECT 'TARJETA', 'Tarjeta de débito/crédito', 'tarjeta', false, 0, true
WHERE NOT EXISTS (SELECT 1 FROM metodo_pago WHERE codigo = 'TARJETA');

-- El checkout del cliente registra su propio pago (simulado) en la misma
-- transacción que crea el pedido. SELECT (id) es lo mínimo para RETURNING id.
GRANT INSERT        ON pago             TO grp_cliente;
GRANT SELECT (id)   ON pago             TO grp_cliente;
GRANT INSERT        ON transaccion_pago TO grp_cliente;

-- Reclasificación one-time de pedidos web del flujo viejo que quedaron sin
-- pagar: pasan a canal 'telefono' (internos) para seguir siendo cobrables.
UPDATE pedido p
SET canal = 'telefono'
WHERE p.canal = 'web'
  AND p.estado_pedido_id IN (SELECT id FROM estado_pedido
                             WHERE codigo IN ('pendiente', 'confirmado'))
  AND NOT EXISTS (SELECT 1 FROM pago pa
                  WHERE pa.pedido_id = p.id AND pa.estado = 'completado');

COMMIT;
