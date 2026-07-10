-- ============================================================================
-- 31_nota_pedido.sql — RetailMind · Activación de la tabla nota_pedido
--  Notas/observaciones internas sobre un pedido (con opción de hacerlas
--  visibles al cliente vía es_visible_cliente). Hasta ahora la tabla estaba
--  huérfana: solo grp_administrador (ALL) y grp_gerente/analista (SELECT)
--  podían tocarla y ninguna pantalla la usaba.
--    - Personal del ciclo de venta (gerente, vendedor, despacho): lee y crea
--      notas. Sin UPDATE/DELETE: la nota es bitácora, no se edita.
--    - grp_cliente: SOLO lectura de las notas de SUS pedidos marcadas
--      es_visible_cliente (RLS de propiedad heredada de pedido, patrón
--      pol_cliente_propio del script 21).
--    - Para mostrar el autor de la nota, vendedor y despacho reciben SELECT
--      por columnas sobre usuario (id, nombre, apellido) — mismo patrón de
--      columnas curadas que grp_analista/grp_gerente (scripts 19 y 30).
--  Idempotente.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1) Privilegios de tabla y secuencia
-- ----------------------------------------------------------------------------
GRANT SELECT, INSERT ON nota_pedido TO grp_gerente, grp_vendedor, grp_despacho;
GRANT SELECT         ON nota_pedido TO grp_cliente;

DO $$
DECLARE
    v_seq text := pg_get_serial_sequence('public.nota_pedido', 'id');
BEGIN
    IF v_seq IS NOT NULL THEN
        EXECUTE format(
            'GRANT USAGE, SELECT ON SEQUENCE %s TO grp_gerente, grp_vendedor, grp_despacho',
            v_seq);
    END IF;
END $$;

-- Autor de la nota: columnas no sensibles de usuario para el personal de venta
GRANT SELECT (id, nombre, apellido) ON usuario TO grp_vendedor, grp_despacho;

-- ----------------------------------------------------------------------------
-- 2) RLS: horario para los grupos internos + propiedad para el cliente
-- ----------------------------------------------------------------------------
ALTER TABLE nota_pedido ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS pol_horario ON nota_pedido;
CREATE POLICY pol_horario ON nota_pedido
    FOR ALL
    TO grp_administrador, grp_gerente, grp_vendedor, grp_compras,
       grp_bodega, grp_despacho, grp_analista
    USING (esta_en_horario(fn_grupo_actual()))
    WITH CHECK (esta_en_horario(fn_grupo_actual()));

DROP POLICY IF EXISTS pol_cliente_propio ON nota_pedido;
CREATE POLICY pol_cliente_propio ON nota_pedido
    FOR SELECT TO grp_cliente
    USING (esta_en_horario('grp_cliente')
           AND es_visible_cliente
           AND pedido_id IN (SELECT id FROM pedido WHERE cliente_id = fn_cliente_actual()));
