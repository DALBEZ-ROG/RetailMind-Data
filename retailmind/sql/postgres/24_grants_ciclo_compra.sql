-- ============================================================================
-- 24_grants_ciclo_compra.sql — RetailMind · Addendum de privilegios
--  La recepcion de mercancia corre bajo grp_bodega (o admin). El flujo, ademas
--  de escribir recepcion_* / inventario / movimiento_inventario (ya
--  concedidos en 19), necesita reflejar lo recibido en la orden de compra:
--    - orden_compra_detalle.cantidad_recibida  (acumulado recibido)
--    - orden_compra.estado                     (recibida / recibida_parcial)
--  Se concede UPDATE A NIVEL DE COLUMNA: bodega no puede tocar precios,
--  cantidades pedidas ni montos. Idempotente.
-- ============================================================================

GRANT UPDATE (cantidad_recibida)           ON orden_compra_detalle TO grp_bodega;
GRANT UPDATE (estado, fecha_actualizacion) ON orden_compra         TO grp_bodega;

-- Bodega necesita leer factura_compra (subconsulta EXISTS en listarOrdenes)
GRANT SELECT ON factura_compra TO grp_bodega;

-- El pago a proveedor lo registra COMPRAS (tiene INSERT en pago_proveedor); para
-- elegir el metodo en la pantalla necesita LEER el catalogo metodo_pago. Sin esto
-- el <select> de metodos llega vacio (GET /api/referencias/metodos-pago -> 403).
GRANT SELECT ON metodo_pago TO grp_compras;
