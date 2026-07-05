-- ============================================================================
-- 18_roles_grupo.sql — RetailMind · Capa de seguridad de motor (PostgreSQL)
-- Crea los 8 ROLES DE GRUPO (NOLOGIN). Los usuarios reales se vuelven miembros
-- con:  GRANT grp_x TO usuario_real;  y la sesión los asume por membresía o
-- con:  SET ROLE grp_x;
-- Idempotente: se puede re-ejecutar sin error.
-- ============================================================================

DO $$
DECLARE
    v_rol text;
    v_roles text[] := ARRAY[
        'grp_administrador',
        'grp_gerente',
        'grp_vendedor',
        'grp_compras',
        'grp_bodega',
        'grp_despacho',
        'grp_cliente',
        'grp_analista'
    ];
BEGIN
    FOREACH v_rol IN ARRAY v_roles LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = v_rol) THEN
            EXECUTE format('CREATE ROLE %I NOLOGIN', v_rol);
            RAISE NOTICE 'Rol de grupo creado: %', v_rol;
        ELSE
            RAISE NOTICE 'Rol de grupo ya existe: %', v_rol;
        END IF;
    END LOOP;
END $$;

COMMENT ON ROLE grp_administrador IS 'Grupo: acceso total al esquema. Exento de restriccion horaria.';
COMMENT ON ROLE grp_gerente       IS 'Grupo: lectura total; aprueba/actualiza estados (orden_compra, pedido, historial_estado_pedido).';
COMMENT ON ROLE grp_vendedor      IS 'Grupo: catalogo e inventario en lectura; opera clientes, carritos, pedidos y facturas de venta.';
COMMENT ON ROLE grp_compras       IS 'Grupo: ciclo de abastecimiento (proveedores, ordenes de compra, recepciones, facturas de compra, CxP).';
COMMENT ON ROLE grp_bodega        IS 'Grupo: inventario fisico (bodegas, movimientos, recepciones, transferencias, ajustes, lotes).';
COMMENT ON ROLE grp_despacho      IS 'Grupo: envios y seguimiento; actualiza estado de pedido para despacho.';
COMMENT ON ROLE grp_cliente       IS 'Grupo: catalogo publico en lectura; escribe SOLO sus propias filas (RLS). Sin restriccion horaria.';
COMMENT ON ROLE grp_analista      IS 'Grupo: SOLO lectura de tablas de negocio. Puente al nivel tactico/analitico.';
