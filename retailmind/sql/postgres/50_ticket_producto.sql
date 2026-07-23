-- ============================================================================
-- 50_ticket_producto.sql — Producto asociado al reclamo (2026-07-22,
-- OTD-SOP-08, cierre de brecha del catálogo táctico § 11.1)
--
-- ticket_soporte solo referenciaba cliente y pedido: no se podía saber qué
-- productos generan más reclamos. Se agrega la referencia OPCIONAL a la
-- VARIANTE (el id público de producto en todo el sistema es el de la
-- variante, igual que pedido_detalle y resena): no todos los reclamos son
-- sobre un producto, por eso la FK admite NULL.
--
-- GRANTs de la columna nueva: NINGUNO — los privilegios de ticket_soporte
-- (script 37) son de tabla completa y la cubren automáticamente (mismo
-- criterio que el script 49).
--
-- GRANTs de APOYO para grp_soporte: la bandeja muestra el producto del ticket
-- (JOIN a producto_variante/producto) y el agente puede buscar en el selector,
-- pero grp_soporte no tenía SELECT sobre el catálogo. Se le conceden SOLO las
-- columnas sin dinero que necesita (patrón del script 47: nada de
-- precio/costo). producto y producto_variante NO tienen RLS (verificado
-- 2026-07-22): basta el GRANT de columna. Los demás roles que crean tickets
-- (admin, gerente, cliente) ya leen el catálogo completo.
-- Idempotente y transaccional.
-- ============================================================================

BEGIN;

-- Si la columna ya existe, la cláusula completa (incluida la FK) se omite
ALTER TABLE ticket_soporte
    ADD COLUMN IF NOT EXISTS producto_variante_id bigint REFERENCES producto_variante(id);

CREATE INDEX IF NOT EXISTS idx_ticket_soporte_variante
    ON ticket_soporte (producto_variante_id);

COMMENT ON COLUMN ticket_soporte.producto_variante_id IS
    'Variante de producto sobre la que trata el reclamo (OPCIONAL: no todos '
    'los tickets refieren un producto). Id público de producto = variante. '
    'Permite medir qué productos generan más reclamos (OTD-SOP-08).';

-- Soporte lee el producto del ticket y busca en el selector: columnas sin
-- dinero (GRANT idempotente por naturaleza)
GRANT SELECT (id, producto_id, sku, activo) ON producto_variante TO grp_soporte;
GRANT SELECT (id, nombre, activo) ON producto TO grp_soporte;

COMMIT;
