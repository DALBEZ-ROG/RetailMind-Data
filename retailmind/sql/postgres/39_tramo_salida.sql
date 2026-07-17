-- ============================================================================
-- 39 — TRAMO DE SALIDA del ciclo de venta (2026-07-16). Idempotente.
-- Ejecutar como superusuario sobre la BD retailmind.
--
-- Flujo objetivo (compuertas en backend, privilegios en BD):
--   pagado → facturado (AUTOMÁTICO si canal='web'; manual VENDEDOR/ADMIN si
--   interno) → en_preparacion → preparado (BODEGA) → despachado (DESPACHO,
--   con transportista asignado por zona y override manual) → entregado
-- ============================================================================

-- ── 1) Estados nuevos del pedido: 'facturado' y 'preparado' ─────────────────
INSERT INTO estado_pedido (codigo, nombre, descripcion, orden, es_final, activo)
SELECT 'facturado', 'Facturado',
       'Factura de venta emitida; en cola de preparación de bodega', 4, false, true
WHERE NOT EXISTS (SELECT 1 FROM estado_pedido WHERE codigo = 'facturado');

INSERT INTO estado_pedido (codigo, nombre, descripcion, orden, es_final, activo)
SELECT 'preparado', 'Preparado',
       'Picking y empaque completados por bodega; listo para despachar', 6, false, true
WHERE NOT EXISTS (SELECT 1 FROM estado_pedido WHERE codigo = 'preparado');

-- Renumeración estable del orden de la línea de tiempo
UPDATE estado_pedido ep SET orden = v.orden
FROM (VALUES ('pendiente',1),('confirmado',2),('pagado',3),('facturado',4),
             ('en_preparacion',5),('preparado',6),('despachado',7),
             ('entregado',8),('cancelado',9),('devuelto',10)) AS v(codigo, orden)
WHERE ep.codigo = v.codigo AND ep.orden IS DISTINCT FROM v.orden;

-- ── 2) Transportista asignado al pedido (asignación automática por zona) ────
ALTER TABLE pedido ADD COLUMN IF NOT EXISTS transportista_id BIGINT
    REFERENCES transportista(id);
CREATE INDEX IF NOT EXISTS idx_pedido_transportista ON pedido(transportista_id);

-- Los privilegios de pedido son por columna: la columna nueva necesita grant
-- explícito para cada rol que la lee/escribe.
GRANT SELECT (transportista_id) ON pedido TO grp_administrador, grp_gerente,
    grp_vendedor, grp_bodega, grp_despacho, grp_cliente, grp_analista, grp_soporte;
GRANT UPDATE (transportista_id) ON pedido TO grp_administrador, grp_gerente,
    grp_vendedor, grp_despacho, grp_cliente;
GRANT INSERT (transportista_id) ON pedido TO grp_administrador, grp_vendedor, grp_cliente;

-- ── 3) Factura AUTOMÁTICA online: el checkout (grp_cliente) emite SU factura ─
GRANT INSERT ON factura_venta TO grp_cliente;
GRANT INSERT ON factura_venta_detalle TO grp_cliente;

DROP POLICY IF EXISTS pol_cliente_emision ON factura_venta;
CREATE POLICY pol_cliente_emision ON factura_venta FOR INSERT TO grp_cliente
    WITH CHECK (esta_en_horario('grp_cliente') AND cliente_id = fn_cliente_actual());

DROP POLICY IF EXISTS pol_cliente_emision ON factura_venta_detalle;
CREATE POLICY pol_cliente_emision ON factura_venta_detalle FOR INSERT TO grp_cliente
    WITH CHECK (esta_en_horario('grp_cliente') AND factura_venta_id IN
        (SELECT fv.id FROM factura_venta fv WHERE fv.cliente_id = fn_cliente_actual()));

-- Recalcular los totales de la cabecera es un invariante del modelo, no un
-- privilegio del invocador (mismo patrón que fn_recalcular_total_orden_compra
-- y fn_recalcular_total_devolucion): DEFINER + search_path fijo.
ALTER FUNCTION fn_recalcular_total_factura_venta() SECURITY DEFINER;
ALTER FUNCTION fn_recalcular_total_factura_venta() SET search_path = public, pg_temp;

-- ── 4) Preparación por BODEGA (cola de picking) ──────────────────────────────
-- Ya tenía: SELECT pedido + UPDATE(estado_pedido_id), SELECT pedido_detalle,
-- SELECT cliente/estado_pedido/transportista e INSERT historial_estado_pedido.
GRANT SELECT ON factura_venta TO grp_bodega;            -- cola = pedidos facturados
GRANT SELECT ON historial_estado_pedido TO grp_bodega;  -- línea de tiempo del pedido
GRANT SELECT ON direccion  TO grp_bodega;               -- destino del paquete
GRANT SELECT ON ciudad     TO grp_bodega;
GRANT SELECT ON provincia  TO grp_bodega;
GRANT SELECT ON pais       TO grp_bodega;
GRANT SELECT ON metodo_envio TO grp_bodega;             -- método/transportista asignado
-- (grp_bodega ya está en pol_horario de todas esas tablas: sin políticas nuevas)

-- ── 5) Zonas y tarifas de envío (regla de asignación documentada) ────────────
-- Resolución de zona por especificidad: ciudad → provincia → país. La tarifa
-- activa más barata de la zona define método de envío y, vía
-- metodo_envio.transportista_id, el transportista asignado.
INSERT INTO zona_envio (nombre, pais_id, provincia_id, ciudad_id, descripcion, activo)
SELECT 'Quevedo (local)', pa.id, pr.id, ci.id,
       'Entrega local same-day/next-day con courier express', true
FROM ciudad ci JOIN provincia pr ON pr.id = ci.provincia_id
               JOIN pais pa ON pa.id = pr.pais_id
WHERE ci.nombre = 'Quevedo'
  AND NOT EXISTS (SELECT 1 FROM zona_envio WHERE nombre = 'Quevedo (local)');

INSERT INTO zona_envio (nombre, pais_id, provincia_id, ciudad_id, descripcion, activo)
SELECT 'Los Rios (provincial)', pa.id, pr.id, NULL,
       'Resto de la provincia: envío estándar', true
FROM provincia pr JOIN pais pa ON pa.id = pr.pais_id
WHERE pr.nombre = 'Los Rios'
  AND NOT EXISTS (SELECT 1 FROM zona_envio WHERE nombre = 'Los Rios (provincial)');

INSERT INTO zona_envio (nombre, pais_id, provincia_id, ciudad_id, descripcion, activo)
SELECT 'Ecuador (nacional)', pa.id, NULL, NULL,
       'Resto del país: envío estándar nacional', true
FROM pais pa
WHERE pa.nombre = 'Ecuador'
  AND NOT EXISTS (SELECT 1 FROM zona_envio WHERE nombre = 'Ecuador (nacional)');

INSERT INTO tarifa_envio (zona_envio_id, metodo_envio_id, costo_base, costo_por_kg, activo)
SELECT z.id, m.id, t.costo, 0, true
FROM (VALUES ('Quevedo (local)',      'EXP', 2.50),
             ('Los Rios (provincial)','EST', 4.50),
             ('Ecuador (nacional)',   'EST', 6.50)) AS t(zona, metodo, costo)
JOIN zona_envio z ON z.nombre = t.zona
JOIN metodo_envio m ON m.codigo = t.metodo
WHERE NOT EXISTS (SELECT 1 FROM tarifa_envio te
                  WHERE te.zona_envio_id = z.id AND te.metodo_envio_id = m.id);

-- ── 6) Migración de datos legacy ─────────────────────────────────────────────
-- Pedidos ya pagados y facturados bajo el flujo viejo entran a la cola de
-- preparación como 'facturado' (el estado nuevo entre pagado y en_preparacion).
UPDATE pedido p
SET estado_pedido_id = (SELECT id FROM estado_pedido WHERE codigo = 'facturado')
WHERE p.estado_pedido_id = (SELECT id FROM estado_pedido WHERE codigo = 'pagado')
  AND EXISTS (SELECT 1 FROM factura_venta fv WHERE fv.pedido_id = p.id);
