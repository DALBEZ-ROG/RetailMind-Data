-- ============================================================================
-- 19_privilegios.sql — RetailMind · Matriz de privilegios por GRUPO
-- Principio de minimo privilegio: primero REVOKE ALL (incluido PUBLIC),
-- despues se concede SOLO lo necesario a cada rol de grupo.
-- Idempotente: GRANT/REVOKE se pueden re-ejecutar.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 0) PISO CERO: nadie tiene nada (salvo el owner/postgres)
-- ----------------------------------------------------------------------------
REVOKE ALL ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON ALL TABLES    IN SCHEMA public FROM PUBLIC;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM PUBLIC;

DO $$
DECLARE
    v_rol text;
BEGIN
    FOREACH v_rol IN ARRAY ARRAY['grp_administrador','grp_gerente','grp_vendedor',
                                 'grp_compras','grp_bodega','grp_despacho',
                                 'grp_cliente','grp_analista'] LOOP
        EXECUTE format('REVOKE ALL ON ALL TABLES    IN SCHEMA public FROM %I', v_rol);
        EXECUTE format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM %I', v_rol);
    END LOOP;
END $$;

-- Todos los grupos necesitan USAGE sobre el esquema para resolver objetos
GRANT USAGE ON SCHEMA public TO grp_administrador, grp_gerente, grp_vendedor,
                                grp_compras, grp_bodega, grp_despacho,
                                grp_cliente, grp_analista;

-- ----------------------------------------------------------------------------
-- 1) grp_administrador: ALL PRIVILEGES sobre todo el esquema
-- ----------------------------------------------------------------------------
GRANT ALL PRIVILEGES ON ALL TABLES    IN SCHEMA public TO grp_administrador;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO grp_administrador;
GRANT EXECUTE        ON ALL FUNCTIONS IN SCHEMA public TO grp_administrador;

-- ----------------------------------------------------------------------------
-- 2) grp_gerente: SELECT sobre todo; UPDATE solo estados/aprobaciones
-- ----------------------------------------------------------------------------
GRANT SELECT ON ALL TABLES IN SCHEMA public TO grp_gerente;
GRANT UPDATE ON orden_compra, pedido, historial_estado_pedido TO grp_gerente;

-- ----------------------------------------------------------------------------
-- 3) grp_vendedor
--    Lectura: catalogo, precios, inventario, catalogos geograficos/comerciales
--    Escritura: ciclo de venta (cliente, carrito, pedido, factura de venta)
-- ----------------------------------------------------------------------------
GRANT SELECT ON producto, producto_variante, producto_categoria, producto_imagen,
                producto_especificacion, producto_etiqueta, producto_relacionado,
                producto_impuesto, categoria, marca, etiqueta, atributo,
                valor_atributo, variante_valor_atributo, promocion,
                promocion_producto, cupon, impuesto, moneda, tipo_cambio,
                metodo_pago, metodo_envio, tarifa_envio, zona_envio,
                estado_pedido, pais, provincia, ciudad, grupo_cliente,
                inventario, direccion
    TO grp_vendedor;

GRANT SELECT, INSERT, UPDATE ON cliente, carrito, carrito_item,
                                pedido, pedido_detalle,
                                factura_venta, factura_venta_detalle
    TO grp_vendedor;

-- ----------------------------------------------------------------------------
-- 4) grp_compras
-- ----------------------------------------------------------------------------
GRANT SELECT ON producto, producto_variante, inventario, moneda, impuesto,
                bodega, pais, provincia, ciudad
    TO grp_compras;

GRANT ALL PRIVILEGES ON proveedor, contacto_proveedor, producto_proveedor,
                        orden_compra, orden_compra_detalle,
                        recepcion_mercancia, recepcion_detalle,
                        factura_compra, factura_compra_detalle,
                        cuenta_por_pagar, pago_proveedor
    TO grp_compras;

-- ----------------------------------------------------------------------------
-- 5) grp_bodega
-- ----------------------------------------------------------------------------
GRANT SELECT ON producto, producto_variante, pedido, pedido_detalle,
                tipo_movimiento, proveedor, orden_compra, orden_compra_detalle
    TO grp_bodega;

GRANT SELECT, INSERT, UPDATE ON bodega, ubicacion_bodega, inventario,
                                movimiento_inventario, recepcion_mercancia,
                                recepcion_detalle, transferencia_bodega,
                                ajuste_inventario, reserva_stock, lote
    TO grp_bodega;

-- ----------------------------------------------------------------------------
-- 6) grp_despacho
--    UPDATE de pedido restringido A NIVEL DE COLUMNA: solo puede cambiar el
--    estado del pedido, no montos ni datos comerciales.
-- ----------------------------------------------------------------------------
GRANT SELECT ON pedido, pedido_detalle, cliente, direccion, transportista,
                metodo_envio, zona_envio, tarifa_envio, estado_pedido,
                ciudad, provincia, pais
    TO grp_despacho;

GRANT SELECT, INSERT, UPDATE ON envio, envio_detalle, seguimiento_envio
    TO grp_despacho;

GRANT UPDATE (estado_pedido_id, fecha_actualizacion) ON pedido TO grp_despacho;
GRANT SELECT, INSERT ON historial_estado_pedido TO grp_despacho;

-- ----------------------------------------------------------------------------
-- 7) grp_cliente
--    Lectura: catalogo publico. Escritura: SUS PROPIAS filas (lo garantiza RLS
--    en 21_rls.sql; aqui solo se da el privilegio de tabla).
-- ----------------------------------------------------------------------------
GRANT SELECT ON producto, producto_variante, producto_categoria, producto_imagen,
                producto_especificacion, producto_etiqueta, producto_relacionado,
                categoria, marca, etiqueta, atributo, valor_atributo,
                variante_valor_atributo, promocion, promocion_producto,
                banner, faq, impuesto, moneda, metodo_pago, metodo_envio,
                tarifa_envio, zona_envio, estado_pedido, pais, provincia, ciudad
    TO grp_cliente;

GRANT SELECT, INSERT, UPDATE ON carrito, carrito_item, wishlist, wishlist_item,
                                pedido, pedido_detalle, resena, direccion
    TO grp_cliente;

-- Su propia ficha: puede verla y actualizarla (RLS la limita a su fila)
GRANT SELECT, UPDATE ON cliente TO grp_cliente;

-- ----------------------------------------------------------------------------
-- 8) grp_analista: SOLO SELECT sobre tablas de negocio. CERO escritura.
--    Se excluyen tablas sensibles de autenticacion; de usuario solo ve
--    columnas no sensibles (nunca password_hash).
-- ----------------------------------------------------------------------------
DO $$
DECLARE
    v_tabla text;
BEGIN
    FOR v_tabla IN
        SELECT tablename FROM pg_tables
        WHERE schemaname = 'public'
          AND tablename NOT IN ('usuario', 'refresh_token', 'token_recuperacion')
    LOOP
        EXECUTE format('GRANT SELECT ON public.%I TO grp_analista', v_tabla);
    END LOOP;
END $$;

GRANT SELECT (id, email, nombre, apellido, activo, fecha_creacion)
    ON usuario TO grp_analista;

-- ----------------------------------------------------------------------------
-- 9) SECUENCIAS DE IDENTIDAD: USAGE/SELECT solo donde el grupo INSERTA
-- ----------------------------------------------------------------------------
DO $$
DECLARE
    v_grupo  text;
    v_tabla  text;
    v_seq    text;
    v_mapa   jsonb := jsonb_build_object(
        'grp_vendedor', ARRAY['cliente','carrito','carrito_item','pedido',
                              'pedido_detalle','factura_venta','factura_venta_detalle'],
        'grp_compras',  ARRAY['proveedor','contacto_proveedor','producto_proveedor',
                              'orden_compra','orden_compra_detalle','recepcion_mercancia',
                              'recepcion_detalle','factura_compra','factura_compra_detalle',
                              'cuenta_por_pagar','pago_proveedor'],
        'grp_bodega',   ARRAY['bodega','ubicacion_bodega','inventario','movimiento_inventario',
                              'recepcion_mercancia','recepcion_detalle','transferencia_bodega',
                              'ajuste_inventario','reserva_stock','lote'],
        'grp_despacho', ARRAY['envio','envio_detalle','seguimiento_envio','historial_estado_pedido'],
        'grp_cliente',  ARRAY['carrito','carrito_item','wishlist','wishlist_item',
                              'pedido','pedido_detalle','resena','direccion']
    );
BEGIN
    FOR v_grupo IN SELECT jsonb_object_keys(v_mapa) LOOP
        FOR v_tabla IN SELECT jsonb_array_elements_text(v_mapa -> v_grupo) LOOP
            v_seq := pg_get_serial_sequence('public.' || quote_ident(v_tabla), 'id');
            IF v_seq IS NOT NULL THEN
                EXECUTE format('GRANT USAGE, SELECT ON SEQUENCE %s TO %I', v_seq, v_grupo);
            END IF;
        END LOOP;
    END LOOP;
END $$;
