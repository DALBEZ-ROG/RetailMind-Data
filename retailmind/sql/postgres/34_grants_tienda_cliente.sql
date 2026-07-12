-- ============================================================================
-- 34_grants_tienda_cliente.sql — Tienda del cliente 100% PostgreSQL
--
-- La tienda (catálogo, carrito, wishlist, perfil, checkout) dejó de usar
-- ClickHouse y opera sobre las tablas transaccionales bajo grp_cliente.
-- Este script agrega SOLO los privilegios que faltaban. Idempotente.
--
-- Decisión documentada: el checkout del cliente descuenta stock vía
-- StockService (inventario + movimiento_inventario/kardex) DENTRO de la
-- transacción del cliente (SET LOCAL ROLE grp_cliente). Por eso grp_cliente
-- recibe INSERT/UPDATE sobre inventario e INSERT sobre movimiento_inventario,
-- con política RLS de horario (no hay filas "propias": el stock es global).
-- ============================================================================
BEGIN;

-- Carrito y wishlist: quitar items (faltaba DELETE; el resto ya existía)
GRANT DELETE ON carrito_item  TO grp_cliente;
GRANT DELETE ON wishlist_item TO grp_cliente;

-- Catálogo con stock visible + checkout con kardex
GRANT SELECT, INSERT, UPDATE ON inventario           TO grp_cliente;
GRANT INSERT                 ON movimiento_inventario TO grp_cliente;
GRANT SELECT                 ON tipo_movimiento       TO grp_cliente;
GRANT SELECT                 ON bodega                TO grp_cliente;

-- El checkout registra el historial del pedido (pol_cliente_propio ya
-- restringe el INSERT a pedidos del propio cliente)
GRANT INSERT ON historial_estado_pedido TO grp_cliente;

-- RLS: inventario/movimiento_inventario/bodega tienen RLS habilitado y no
-- tenían política para grp_cliente (todo quedaba filtrado). Se agregan
-- políticas de horario (sin aislamiento por fila: son datos globales).
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_policies
                   WHERE schemaname = 'public' AND tablename = 'inventario'
                     AND policyname = 'pol_cliente_tienda') THEN
        CREATE POLICY pol_cliente_tienda ON inventario FOR ALL TO grp_cliente
            USING (esta_en_horario('grp_cliente'))
            WITH CHECK (esta_en_horario('grp_cliente'));
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_policies
                   WHERE schemaname = 'public' AND tablename = 'movimiento_inventario'
                     AND policyname = 'pol_cliente_checkout') THEN
        CREATE POLICY pol_cliente_checkout ON movimiento_inventario FOR INSERT TO grp_cliente
            WITH CHECK (esta_en_horario('grp_cliente'));
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_policies
                   WHERE schemaname = 'public' AND tablename = 'bodega'
                     AND policyname = 'pol_cliente_lectura') THEN
        CREATE POLICY pol_cliente_lectura ON bodega FOR SELECT TO grp_cliente
            USING (esta_en_horario('grp_cliente'));
    END IF;

    -- historial_estado_pedido: pol_cliente_propio (script 30) es SOLO SELECT;
    -- el checkout inserta el historial del pedido recién creado. WITH CHECK
    -- de propiedad: solo sobre pedidos del propio cliente.
    IF NOT EXISTS (SELECT 1 FROM pg_policies
                   WHERE schemaname = 'public' AND tablename = 'historial_estado_pedido'
                     AND policyname = 'pol_cliente_checkout') THEN
        CREATE POLICY pol_cliente_checkout ON historial_estado_pedido FOR INSERT TO grp_cliente
            WITH CHECK (esta_en_horario('grp_cliente')
                        AND pedido_id IN (SELECT id FROM pedido
                                          WHERE cliente_id = fn_cliente_actual()));
    END IF;
END$$;

COMMIT;
