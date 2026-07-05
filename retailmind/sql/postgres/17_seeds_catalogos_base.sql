-- ============================================================
-- RetailMind - Script 17: Seeds de catalogos base minimos
-- Solo lo indispensable para operar: estados de pedido, tipos de
-- movimiento de kardex, moneda base, pais/provincias de Ecuador,
-- idiomas e IVA vigente.
-- Depende de: 01..16
-- ============================================================

BEGIN;

-- Idiomas
INSERT INTO idioma (codigo, nombre, es_predeterminado) VALUES
    ('es', 'Español', true),
    ('en', 'English', false);

-- Moneda base (Ecuador usa USD)
INSERT INTO moneda (codigo, nombre, simbolo, decimales, es_base) VALUES
    ('USD', 'Dólar estadounidense', '$', 2, true);

-- Pais y provincias de Ecuador
INSERT INTO pais (codigo_iso2, codigo_iso3, nombre, prefijo_telefonico) VALUES
    ('EC', 'ECU', 'Ecuador', '+593');

INSERT INTO provincia (pais_id, codigo, nombre)
SELECT p.id, v.codigo, v.nombre
FROM pais p,
     (VALUES
        ('01', 'Azuay'),
        ('02', 'Bolívar'),
        ('03', 'Cañar'),
        ('04', 'Carchi'),
        ('05', 'Cotopaxi'),
        ('06', 'Chimborazo'),
        ('07', 'El Oro'),
        ('08', 'Esmeraldas'),
        ('09', 'Guayas'),
        ('10', 'Imbabura'),
        ('11', 'Loja'),
        ('12', 'Los Ríos'),
        ('13', 'Manabí'),
        ('14', 'Morona Santiago'),
        ('15', 'Napo'),
        ('16', 'Pastaza'),
        ('17', 'Pichincha'),
        ('18', 'Tungurahua'),
        ('19', 'Zamora Chinchipe'),
        ('20', 'Galápagos'),
        ('21', 'Sucumbíos'),
        ('22', 'Orellana'),
        ('23', 'Santo Domingo de los Tsáchilas'),
        ('24', 'Santa Elena')
     ) AS v (codigo, nombre)
WHERE p.codigo_iso2 = 'EC';

-- Impuestos vigentes en Ecuador (IVA via producto_impuesto, no hardcodeado)
INSERT INTO impuesto (codigo, nombre, tipo, porcentaje, pais_id)
SELECT v.codigo, v.nombre, 'iva', v.porcentaje, p.id
FROM pais p,
     (VALUES
        ('IVA15', 'IVA 15%', 15.00),
        ('IVA0',  'IVA 0%',   0.00)
     ) AS v (codigo, nombre, porcentaje)
WHERE p.codigo_iso2 = 'EC';

-- Maquina de estados del pedido
INSERT INTO estado_pedido (codigo, nombre, descripcion, orden, es_final) VALUES
    ('pendiente',      'Pendiente',      'Pedido creado, pendiente de confirmacion o pago', 1, false),
    ('confirmado',     'Confirmado',     'Pedido confirmado por la tienda',                 2, false),
    ('pagado',         'Pagado',         'Pago recibido y verificado',                      3, false),
    ('en_preparacion', 'En preparación', 'Bodega preparando el pedido',                     4, false),
    ('despachado',     'Despachado',     'Entregado al transportista',                      5, false),
    ('entregado',      'Entregado',      'Recibido por el cliente',                         6, true),
    ('cancelado',      'Cancelado',      'Pedido cancelado antes del despacho',             7, true),
    ('devuelto',       'Devuelto',       'Pedido devuelto tras la entrega',                 8, true);

-- Tipos de movimiento del kardex (factor: +1 suma stock, -1 resta)
INSERT INTO tipo_movimiento (codigo, nombre, naturaleza, factor, descripcion) VALUES
    ('entrada_compra',              'Entrada por compra',                'entrada',       1,  'Recepcion de mercancia de orden de compra'),
    ('entrada_devolucion_cliente',  'Entrada por devolución de cliente', 'entrada',       1,  'Reingreso de producto devuelto por cliente'),
    ('entrada_transferencia',       'Entrada por transferencia',         'transferencia', 1,  'Ingreso desde otra bodega'),
    ('entrada_ajuste',              'Ajuste positivo',                   'ajuste',        1,  'Ajuste de inventario que suma stock'),
    ('salida_venta',                'Salida por venta',                  'salida',        -1, 'Despacho de pedido de venta'),
    ('salida_devolucion_proveedor', 'Salida por devolución a proveedor', 'salida',        -1, 'Devolucion de mercancia al proveedor'),
    ('salida_transferencia',        'Salida por transferencia',          'transferencia', -1, 'Egreso hacia otra bodega'),
    ('salida_ajuste',               'Ajuste negativo',                   'ajuste',        -1, 'Ajuste de inventario que resta stock (merma, dano)');

COMMIT;
