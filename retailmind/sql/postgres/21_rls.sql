-- ============================================================================
-- 21_rls.sql — RetailMind · Row-Level Security
--  Completa la capa de seguridad:
--   - 19_privilegios.sql decide QUE tablas/comandos puede usar cada grupo.
--   - 20_horario.sql bloquea la ESCRITURA fuera de horario (triggers).
--   - Este script aplica el horario tambien a la LECTURA (SELECT devuelve
--     0 filas fuera de horario) y aisla a grp_cliente a SUS PROPIAS filas.
--  grp_administrador queda exento (esta_en_horario le devuelve true) y el
--  owner (postgres) hace bypass de RLS por definicion (sin FORCE).
--  La sesion del cliente se identifica con: SET app.cliente_id = '<id>';
--  Idempotente: DROP POLICY IF EXISTS + CREATE.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1) Identidad de la sesion cliente
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_cliente_actual()
RETURNS bigint
LANGUAGE sql STABLE
AS $$
    SELECT NULLIF(current_setting('app.cliente_id', true), '')::bigint
$$;

COMMENT ON FUNCTION fn_cliente_actual() IS
  'Id del cliente de la sesion actual, tomado de la variable app.cliente_id (SET app.cliente_id = ''N''). NULL si no esta definida.';

-- SECURITY DEFINER: resuelve usuario_id del cliente sin chocar con la RLS de cliente
CREATE OR REPLACE FUNCTION fn_usuario_actual()
RETURNS bigint
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
    SELECT usuario_id FROM cliente WHERE id = fn_cliente_actual()
$$;

GRANT EXECUTE ON FUNCTION fn_cliente_actual() TO PUBLIC;
GRANT EXECUTE ON FUNCTION fn_usuario_actual() TO PUBLIC;

-- ----------------------------------------------------------------------------
-- 2) Politica de HORARIO para los grupos internos (lectura y escritura)
--    Se aplica a las 34 tablas operativas. Fuera de horario:
--      - SELECT => 0 filas (RLS filtra todo)
--      - INSERT/UPDATE/DELETE => ya lo corta el trigger de 20_horario.sql
--    grp_cliente NO va aqui: tiene sus propias politicas de propiedad abajo.
-- ----------------------------------------------------------------------------
DO $$
DECLARE
    v_tabla text;
    v_tablas text[] := ARRAY[
        'cliente','carrito','carrito_item','wishlist','wishlist_item',
        'pedido','pedido_detalle','factura_venta','factura_venta_detalle',
        'resena','direccion','historial_estado_pedido',
        'proveedor','contacto_proveedor','producto_proveedor',
        'orden_compra','orden_compra_detalle','recepcion_mercancia',
        'recepcion_detalle','factura_compra','factura_compra_detalle',
        'cuenta_por_pagar','pago_proveedor',
        'bodega','ubicacion_bodega','inventario','movimiento_inventario',
        'transferencia_bodega','ajuste_inventario','reserva_stock','lote',
        'envio','envio_detalle','seguimiento_envio'
    ];
BEGIN
    FOREACH v_tabla IN ARRAY v_tablas LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', v_tabla);
        EXECUTE format('DROP POLICY IF EXISTS pol_horario ON public.%I', v_tabla);
        EXECUTE format(
            'CREATE POLICY pol_horario ON public.%I
             FOR ALL
             TO grp_administrador, grp_gerente, grp_vendedor, grp_compras,
                grp_bodega, grp_despacho, grp_analista
             USING (esta_en_horario(fn_grupo_actual()))
             WITH CHECK (esta_en_horario(fn_grupo_actual()))',
            v_tabla);
    END LOOP;
    RAISE NOTICE 'RLS habilitada y politica de horario creada en % tablas', array_length(v_tablas, 1);
END $$;

-- ----------------------------------------------------------------------------
-- 3) Politicas de PROPIEDAD para grp_cliente: solo SUS filas.
--    Ademas respeta su ventana en grupo_horario (sembrada 24/7).
-- ----------------------------------------------------------------------------
-- Su propia ficha
DROP POLICY IF EXISTS pol_cliente_propio ON cliente;
CREATE POLICY pol_cliente_propio ON cliente
    FOR ALL TO grp_cliente
    USING      (esta_en_horario('grp_cliente') AND id = fn_cliente_actual())
    WITH CHECK (esta_en_horario('grp_cliente') AND id = fn_cliente_actual());

-- Tablas con cliente_id directo
DO $$
DECLARE
    v_tabla text;
BEGIN
    FOREACH v_tabla IN ARRAY ARRAY['carrito','wishlist','pedido','resena'] LOOP
        EXECUTE format('DROP POLICY IF EXISTS pol_cliente_propio ON public.%I', v_tabla);
        EXECUTE format(
            'CREATE POLICY pol_cliente_propio ON public.%I
             FOR ALL TO grp_cliente
             USING      (esta_en_horario(''grp_cliente'') AND cliente_id = fn_cliente_actual())
             WITH CHECK (esta_en_horario(''grp_cliente'') AND cliente_id = fn_cliente_actual())',
            v_tabla);
    END LOOP;
END $$;

-- Detalles: propiedad heredada de la tabla padre
DROP POLICY IF EXISTS pol_cliente_propio ON carrito_item;
CREATE POLICY pol_cliente_propio ON carrito_item
    FOR ALL TO grp_cliente
    USING      (esta_en_horario('grp_cliente') AND carrito_id IN (SELECT id FROM carrito WHERE cliente_id = fn_cliente_actual()))
    WITH CHECK (esta_en_horario('grp_cliente') AND carrito_id IN (SELECT id FROM carrito WHERE cliente_id = fn_cliente_actual()));

DROP POLICY IF EXISTS pol_cliente_propio ON wishlist_item;
CREATE POLICY pol_cliente_propio ON wishlist_item
    FOR ALL TO grp_cliente
    USING      (esta_en_horario('grp_cliente') AND wishlist_id IN (SELECT id FROM wishlist WHERE cliente_id = fn_cliente_actual()))
    WITH CHECK (esta_en_horario('grp_cliente') AND wishlist_id IN (SELECT id FROM wishlist WHERE cliente_id = fn_cliente_actual()));

DROP POLICY IF EXISTS pol_cliente_propio ON pedido_detalle;
CREATE POLICY pol_cliente_propio ON pedido_detalle
    FOR ALL TO grp_cliente
    USING      (esta_en_horario('grp_cliente') AND pedido_id IN (SELECT id FROM pedido WHERE cliente_id = fn_cliente_actual()))
    WITH CHECK (esta_en_horario('grp_cliente') AND pedido_id IN (SELECT id FROM pedido WHERE cliente_id = fn_cliente_actual()));

-- Direccion pertenece al USUARIO del cliente
DROP POLICY IF EXISTS pol_cliente_propio ON direccion;
CREATE POLICY pol_cliente_propio ON direccion
    FOR ALL TO grp_cliente
    USING      (esta_en_horario('grp_cliente') AND usuario_id = fn_usuario_actual())
    WITH CHECK (esta_en_horario('grp_cliente') AND usuario_id = fn_usuario_actual());
