-- ============================================================================
-- 42 · TRAZABILIDAD DE AUTOR EN PROCESOS CRÍTICOS + log_auditoria EXTENDIDA
-- ----------------------------------------------------------------------------
-- Cierra los 4 huecos de "quién lo hizo" detectados en el diagnóstico de
-- trazabilidad (2026-07-17). Columnas de autor DIRECTAS (consultables sin
-- reconstruir desde historial) + grants para que los roles ejecutores puedan
-- escribir en log_auditoria (rastro centralizado, mismo patrón que ya usaba
-- la aprobación de orden de compra en ComprasService).
--
-- Columnas nuevas (todas NULL-ables, FK a usuario, ON DELETE SET NULL como
-- log_auditoria.usuario_id; NO tocan triggers ni columnas GENERATED):
--   pedido.vendedor_id            usuario que CREÓ el pedido INTERNO.
--                                 CRITERIO pedido ONLINE (canal 'web'): queda
--                                 NULL — el autor es el CLIENTE y ya está
--                                 trazado por cliente_id + canal + la 1ª fila
--                                 de historial_estado_pedido. vendedor_id
--                                 solo aplica a canal 'tienda'/'telefono'.
--   envio.despachado_por          usuario que ejecutó el despacho.
--   factura_compra.registrado_por usuario que registró la factura.
--   resena.moderado_por           usuario que aprobó/rechazó + fecha_moderacion.
--   pregunta_producto.moderado_por / fecha_moderacion (misma moderación;
--                                 respuesta_pregunta YA tenía usuario_id).
--
-- Backfill (solo filas con la columna NULL — re-ejecutable):
--   pedido.vendedor_id  ← usuario_id de la PRIMERA fila de
--                         historial_estado_pedido, solo pedidos NO 'web'.
--   envio.despachado_por ← usuario_id de la fila 'despachado' del historial
--                          del pedido del envío.
--   factura_compra.registrado_por, resena/pregunta.moderado_por: SIN fuente
--   histórica (no había historial) → quedan NULL, documentado.
--
-- Grants: log_auditoria solo tenía INSERT para admin y gerente (scripts
-- 19/30). Los ejecutores de los procesos ahora auditados necesitan INSERT:
--   grp_vendedor (crear pedido), grp_despacho (despachar),
--   grp_compras (registrar factura). Moderación = ADMIN/GERENTE (ya lo tenían).
--   grp_cliente NO recibe INSERT: el checkout online NO escribe log_auditoria
--   (decisión: no se le da a un rol de internet un canal de escritura al log;
--   su rastro es pedido.cliente_id + canal='web' + historial con RLS).
--
-- Columnas nuevas y matriz de roles (no-regresión):
--   * Los roles que INSERTan/UPDATEan estas tablas lo hacen con GRANT DE
--     TABLA (pedido: admin/vendedor/cliente; envio: admin/despacho;
--     factura_compra: admin/compras; resena/pregunta: admin/gerente) → las
--     columnas nuevas quedan cubiertas SIN grants adicionales.
--   * grp_bodega/grp_despacho leen pedido y factura_compra POR COLUMNA
--     (script 41): NO se les agregan las columnas nuevas (no las necesitan;
--     la segregación queda intacta).
--   * RLS: las políticas existentes no dependen de columnas → sin cambios.
--     log_auditoria no tiene RLS (append-only vía grants).
--
-- Idempotente: ADD COLUMN IF NOT EXISTS (la FK inline solo se crea junto con
-- la columna), backfill con WHERE ... IS NULL, GRANT re-ejecutable.
-- Ejecutar como postgres:
--   psql -h localhost -U postgres -d retailmind -f 42_trazabilidad_auditoria.sql
-- ============================================================================

BEGIN;

-- ── 1 · Columnas de autor ───────────────────────────────────────────────────

ALTER TABLE pedido
    ADD COLUMN IF NOT EXISTS vendedor_id bigint
        REFERENCES usuario(id) ON UPDATE CASCADE ON DELETE SET NULL;
COMMENT ON COLUMN pedido.vendedor_id IS
    'Usuario (staff) que creó el pedido interno. NULL en pedidos online '
    '(canal web): ahí el autor es el cliente (cliente_id + historial).';

ALTER TABLE envio
    ADD COLUMN IF NOT EXISTS despachado_por bigint
        REFERENCES usuario(id) ON UPDATE CASCADE ON DELETE SET NULL;
COMMENT ON COLUMN envio.despachado_por IS
    'Usuario que ejecutó el despacho (autor del JWT, nunca del body).';

ALTER TABLE factura_compra
    ADD COLUMN IF NOT EXISTS registrado_por bigint
        REFERENCES usuario(id) ON UPDATE CASCADE ON DELETE SET NULL;
COMMENT ON COLUMN factura_compra.registrado_por IS
    'Usuario que registró la factura de compra (autor del JWT).';

ALTER TABLE resena
    ADD COLUMN IF NOT EXISTS moderado_por bigint
        REFERENCES usuario(id) ON UPDATE CASCADE ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS fecha_moderacion timestamptz;
COMMENT ON COLUMN resena.moderado_por IS
    'Usuario que aprobó/rechazó la reseña (última moderación).';

ALTER TABLE pregunta_producto
    ADD COLUMN IF NOT EXISTS moderado_por bigint
        REFERENCES usuario(id) ON UPDATE CASCADE ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS fecha_moderacion timestamptz;
COMMENT ON COLUMN pregunta_producto.moderado_por IS
    'Usuario que publicó/rechazó la pregunta (o la publicó al responderla).';

-- ── 2 · Backfill desde historial (solo donde es trivial y confiable) ────────

-- Pedidos internos: el autor de la 1ª fila del historial es quien lo creó.
UPDATE pedido p
SET vendedor_id = h.usuario_id
FROM (
    SELECT DISTINCT ON (pedido_id) pedido_id, usuario_id
    FROM historial_estado_pedido
    WHERE usuario_id IS NOT NULL
    ORDER BY pedido_id, id
) h
WHERE h.pedido_id = p.id
  AND p.canal <> 'web'
  AND p.vendedor_id IS NULL;

-- Envíos: el autor de la fila 'despachado' del historial del pedido.
UPDATE envio e
SET despachado_por = h.usuario_id
FROM (
    SELECT DISTINCT ON (h.pedido_id) h.pedido_id, h.usuario_id
    FROM historial_estado_pedido h
    JOIN estado_pedido ep ON ep.id = h.estado_pedido_id
    WHERE ep.codigo = 'despachado' AND h.usuario_id IS NOT NULL
    ORDER BY h.pedido_id, h.id
) h
WHERE h.pedido_id = e.pedido_id
  AND e.despachado_por IS NULL;

-- factura_compra.registrado_por, resena/pregunta_producto.moderado_por:
-- sin fuente histórica → permanecen NULL (documentado arriba).

-- ── 3 · Grants de log_auditoria para los roles ejecutores ───────────────────

GRANT INSERT ON log_auditoria TO grp_vendedor;   -- crear pedido interno
GRANT INSERT ON log_auditoria TO grp_despacho;   -- despachar
GRANT INSERT ON log_auditoria TO grp_compras;    -- registrar factura de compra
-- ADMIN/GERENTE ya tenían INSERT (scripts 19/30); analista conserva solo
-- SELECT; grp_cliente y grp_bodega quedan SIN acceso (ver cabecera).

COMMIT;

-- Verificación rápida:
-- SELECT column_name FROM information_schema.columns
--  WHERE table_name IN ('pedido','envio','factura_compra','resena','pregunta_producto')
--    AND column_name IN ('vendedor_id','despachado_por','registrado_por','moderado_por','fecha_moderacion');
-- SELECT grantee, privilege_type FROM information_schema.role_table_grants
--  WHERE table_name = 'log_auditoria' AND privilege_type = 'INSERT';
