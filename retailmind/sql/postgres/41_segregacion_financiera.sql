-- ============================================================================
-- 41 · SEGREGACIÓN FINANCIERA DE BODEGA Y DESPACHO (grants por columna)
-- ----------------------------------------------------------------------------
-- BODEGA y DESPACHO son roles 100% operativos: preparan, despachan, reciben e
-- inspeccionan. Su trabajo necesita QUÉ, CUÁNTO, QUIÉN y A DÓNDE — nunca
-- precios de costo, montos de factura, totales ni saldos. Hasta ahora sus
-- GRANTs de tabla completa les dejaban leer esas columnas; este script los
-- reescribe con el patrón de grant POR COLUMNA (mismo patrón del analista).
--
-- Columnas de dinero retiradas:
--   pedido:          subtotal, monto_descuento, monto_impuesto, costo_envio, total
--   pedido_detalle:  precio_unitario*, subtotal, monto_descuento, monto_impuesto
--   factura_venta:   subtotal, monto_descuento, monto_impuesto, total
--   factura_compra:  subtotal, monto_impuesto, total            (solo bodega la leía)
--   orden_compra:    subtotal, monto_impuesto, total            (solo bodega la leía)
--   orden_compra_detalle: precio_unitario*, subtotal, monto_impuesto
--   devolucion:      monto_total, monto_reembolsado, metodo_reembolso, fecha_reembolso
--   pago:            REVOKE COMPLETO a grp_despacho (ya no lee pagos: la
--                    respuesta de "entregar" es ligera y obtenerPedido no es suyo)
--
-- (*) EXCEPCIÓN documentada: grp_bodega CONSERVA SELECT sobre
--     pedido_detalle.precio_unitario y orden_compra_detalle.precio_unitario
--     porque la app los usa BAJO SU ROL para valorizar el kardex
--     (movimiento_inventario.costo_unitario) en la recepción de compra y en el
--     reingreso de la devolución (StockService). Las consultas de pantalla y la
--     UI de bodega NO los muestran; el costo del kardex es dato operativo de
--     inventario que bodega ya posee (movimiento_inventario intacto).
--
-- No se toca: inventario, movimiento_inventario (kardex = herramienta de
-- bodega), envio (su columna costo está sin uso, siempre NULL), ni ningún
-- otro rol de la matriz (admin/gerente/vendedor/compras/analista/soporte/
-- cliente conservan exactamente lo que tenían).
--
-- Idempotente: REVOKE + GRANT por columna; se puede re-ejecutar sin efectos.
-- ============================================================================

BEGIN;

-- ── grp_bodega ──────────────────────────────────────────────────────────────

-- pedido: lee la cola de preparación; actualiza SOLO el estado
REVOKE SELECT, UPDATE ON pedido FROM grp_bodega;
GRANT SELECT (id, numero, cliente_id, estado_pedido_id, moneda_id, metodo_envio_id,
              direccion_envio_id, direccion_facturacion_id, canal, fecha_pedido,
              fecha_creacion, fecha_actualizacion, transportista_id)
    ON pedido TO grp_bodega;
GRANT UPDATE (estado_pedido_id, fecha_actualizacion) ON pedido TO grp_bodega;

-- pedido_detalle: picking por cantidades (precio_unitario solo para el kardex)
REVOKE SELECT ON pedido_detalle FROM grp_bodega;
GRANT SELECT (id, pedido_id, producto_variante_id, nombre_producto, sku,
              cantidad, fecha_creacion, precio_unitario)
    ON pedido_detalle TO grp_bodega;

-- factura_venta: solo la referencia documental (número/estado), sin montos
REVOKE SELECT ON factura_venta FROM grp_bodega;
GRANT SELECT (id, numero, pedido_id, cliente_id, moneda_id, clave_acceso,
              razon_social, identificacion, direccion_facturacion, estado,
              fecha_emision, fecha_creacion, fecha_actualizacion)
    ON factura_venta TO grp_bodega;

-- factura_compra: referencia documental para la trazabilidad de la orden
REVOKE SELECT ON factura_compra FROM grp_bodega;
GRANT SELECT (id, proveedor_id, orden_compra_id, moneda_id, numero_factura,
              fecha_emision, fecha_vencimiento, estado, fecha_creacion,
              fecha_actualizacion)
    ON factura_compra TO grp_bodega;

-- orden_compra: recibe contra la orden (estado/fechas), sin totales.
-- Conserva UPDATE(estado, fecha_actualizacion) del script 24 (recepción).
REVOKE SELECT, UPDATE ON orden_compra FROM grp_bodega;
GRANT SELECT (id, numero, proveedor_id, bodega_id, moneda_id, usuario_id,
              estado, fecha_emision, fecha_entrega_esperada, observacion,
              fecha_creacion, fecha_actualizacion)
    ON orden_compra TO grp_bodega;
GRANT UPDATE (estado, fecha_actualizacion) ON orden_compra TO grp_bodega;

-- orden_compra_detalle: cantidades a recibir (precio_unitario solo kardex)
REVOKE SELECT, UPDATE ON orden_compra_detalle FROM grp_bodega;
GRANT SELECT (id, orden_compra_id, producto_variante_id, cantidad,
              cantidad_recibida, fecha_creacion, precio_unitario)
    ON orden_compra_detalle TO grp_bodega;
GRANT UPDATE (cantidad_recibida) ON orden_compra_detalle TO grp_bodega;

-- devolucion: inspecciona y transiciona, sin montos de reembolso
REVOKE SELECT, UPDATE ON devolucion FROM grp_bodega;
GRANT SELECT (id, numero, pedido_id, motivo_devolucion_id, usuario_gestiona_id,
              estado, descripcion, fecha_creacion, fecha_actualizacion,
              cliente_id, ticket_soporte_id, transportista_id, bodega_id,
              guia_retorno, motivo_rechazo)
    ON devolucion TO grp_bodega;
GRANT UPDATE (estado, bodega_id, fecha_actualizacion) ON devolucion TO grp_bodega;

-- ── grp_despacho ────────────────────────────────────────────────────────────

-- pedido: despacha/entrega; actualiza estado y el override de transportista
REVOKE SELECT, UPDATE ON pedido FROM grp_despacho;
GRANT SELECT (id, numero, cliente_id, estado_pedido_id, moneda_id, metodo_envio_id,
              direccion_envio_id, direccion_facturacion_id, canal, fecha_pedido,
              fecha_creacion, fecha_actualizacion, transportista_id)
    ON pedido TO grp_despacho;
GRANT UPDATE (estado_pedido_id, transportista_id, metodo_envio_id, fecha_actualizacion)
    ON pedido TO grp_despacho;

-- pedido_detalle: arma el envío por cantidades; SIN precios
REVOKE SELECT ON pedido_detalle FROM grp_despacho;
GRANT SELECT (id, pedido_id, producto_variante_id, nombre_producto, sku,
              cantidad, fecha_creacion)
    ON pedido_detalle TO grp_despacho;

-- factura_venta: referencia documental, sin montos
REVOKE SELECT ON factura_venta FROM grp_despacho;
GRANT SELECT (id, numero, pedido_id, cliente_id, moneda_id, clave_acceso,
              razon_social, identificacion, direccion_facturacion, estado,
              fecha_emision, fecha_creacion, fecha_actualizacion)
    ON factura_venta TO grp_despacho;

-- pago: despacho no tiene por qué ver cobros (monto, referencia, método)
REVOKE ALL ON pago FROM grp_despacho;

-- devolucion: mueve el retorno físico (tránsito/recepción), sin montos
REVOKE SELECT, UPDATE ON devolucion FROM grp_despacho;
GRANT SELECT (id, numero, pedido_id, motivo_devolucion_id, usuario_gestiona_id,
              estado, descripcion, fecha_creacion, fecha_actualizacion,
              cliente_id, ticket_soporte_id, transportista_id, bodega_id,
              guia_retorno, motivo_rechazo)
    ON devolucion TO grp_despacho;
GRANT UPDATE (estado, fecha_actualizacion) ON devolucion TO grp_despacho;

COMMIT;
