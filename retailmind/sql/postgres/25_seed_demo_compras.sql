-- ============================================================================
-- 25_seed_demo_compras.sql — RetailMind · Datos minimos de DEMO
--  Catalogo (4 categorias, 3 marcas, 8 productos, 16 variantes, atributos),
--  2 proveedores, 2 bodegas, metodos de pago y stock inicial (10 uds por
--  variante en la bodega principal) para probar el ciclo de compra y grabar
--  el video. Idempotente: WHERE NOT EXISTS / ON CONFLICT por claves naturales.
-- ============================================================================

-- 1) Categorias
INSERT INTO categoria (nombre, slug, descripcion)
SELECT v.nombre, v.slug, v.descripcion
FROM (VALUES
    ('Calzado',     'calzado',     'Zapatillas y calzado deportivo'),
    ('Ropa Hombre', 'ropa-hombre', 'Indumentaria masculina'),
    ('Ropa Mujer',  'ropa-mujer',  'Indumentaria femenina'),
    ('Accesorios',  'accesorios',  'Gorras, mochilas y complementos')
) AS v(nombre, slug, descripcion)
WHERE NOT EXISTS (SELECT 1 FROM categoria c WHERE c.slug = v.slug);

-- 2) Marcas
INSERT INTO marca (nombre, slug)
SELECT v.nombre, v.slug
FROM (VALUES ('Nike','nike'), ('Adidas','adidas'), ('Puma','puma')) AS v(nombre, slug)
WHERE NOT EXISTS (SELECT 1 FROM marca m WHERE m.slug = v.slug);

-- 3) Productos (publicados y activos)
INSERT INTO producto (marca_id, nombre, slug, descripcion_corta, publicado, activo)
SELECT m.id, v.nombre, v.slug, v.corta, true, true
FROM (VALUES
    ('nike',   'Zapatillas Air Max 270',  'zapatillas-air-max-270',  'Running con camara de aire'),
    ('adidas', 'Zapatillas Ultraboost 22','zapatillas-ultraboost-22','Amortiguacion Boost'),
    ('nike',   'Camiseta Dri-FIT',        'camiseta-dri-fit',        'Tela transpirable'),
    ('adidas', 'Hoodie Essentials',       'hoodie-essentials',       'Buzo con capucha'),
    ('nike',   'Leggings One Luxe',       'leggings-one-luxe',       'Ajuste de compresion'),
    ('puma',   'Top Studio',              'top-studio',              'Top deportivo'),
    ('nike',   'Gorra Classic 99',        'gorra-classic-99',        'Gorra ajustable'),
    ('puma',   'Mochila Phase',           'mochila-phase',           'Mochila urbana 22L')
) AS v(marca_slug, nombre, slug, corta)
JOIN marca m ON m.slug = v.marca_slug
WHERE NOT EXISTS (SELECT 1 FROM producto p WHERE p.slug = v.slug);

-- 3b) Producto-categoria (principal)
INSERT INTO producto_categoria (producto_id, categoria_id, es_principal)
SELECT p.id, c.id, true
FROM (VALUES
    ('zapatillas-air-max-270','calzado'), ('zapatillas-ultraboost-22','calzado'),
    ('camiseta-dri-fit','ropa-hombre'),   ('hoodie-essentials','ropa-hombre'),
    ('leggings-one-luxe','ropa-mujer'),   ('top-studio','ropa-mujer'),
    ('gorra-classic-99','accesorios'),    ('mochila-phase','accesorios')
) AS v(pslug, cslug)
JOIN producto p ON p.slug = v.pslug
JOIN categoria c ON c.slug = v.cslug
WHERE NOT EXISTS (SELECT 1 FROM producto_categoria pc
                  WHERE pc.producto_id = p.id AND pc.categoria_id = c.id);

-- 4) Variantes (16) con SKU, precio de venta y costo
INSERT INTO producto_variante (producto_id, sku, precio, costo, es_predeterminada)
SELECT p.id, v.sku, v.precio, v.costo, v.predet
FROM (VALUES
    ('zapatillas-air-max-270',  'AM270-40-NEG', 149.90, 85.00, true),
    ('zapatillas-air-max-270',  'AM270-42-NEG', 149.90, 85.00, false),
    ('zapatillas-air-max-270',  'AM270-44-BLA', 154.90, 88.00, false),
    ('zapatillas-ultraboost-22','UB22-40-NEG',  179.90, 98.00, true),
    ('zapatillas-ultraboost-22','UB22-42-AZU',  179.90, 98.00, false),
    ('camiseta-dri-fit',        'DRIFIT-M-NEG',  29.90, 12.50, true),
    ('camiseta-dri-fit',        'DRIFIT-L-BLA',  29.90, 12.50, false),
    ('camiseta-dri-fit',        'DRIFIT-XL-AZU', 31.90, 13.50, false),
    ('hoodie-essentials',       'HOOD-M-NEG',    54.90, 26.00, true),
    ('hoodie-essentials',       'HOOD-L-NEG',    54.90, 26.00, false),
    ('leggings-one-luxe',       'LEGG-S-NEG',    64.90, 30.00, true),
    ('leggings-one-luxe',       'LEGG-M-NEG',    64.90, 30.00, false),
    ('top-studio',              'TOP-S-BLA',     34.90, 15.00, true),
    ('top-studio',              'TOP-M-AZU',     34.90, 15.00, false),
    ('gorra-classic-99',        'GORRA-UNI-NEG', 24.90, 10.00, true),
    ('mochila-phase',           'MOCH-UNI-NEG',  39.90, 18.00, true)
) AS v(pslug, sku, precio, costo, predet)
JOIN producto p ON p.slug = v.pslug
WHERE NOT EXISTS (SELECT 1 FROM producto_variante pv WHERE pv.sku = v.sku);

-- 5) Atributos y valores
INSERT INTO atributo (codigo, nombre, tipo)
SELECT v.codigo, v.nombre, v.tipo
FROM (VALUES ('talla','Talla','texto'), ('color','Color','color')) AS v(codigo,nombre,tipo)
WHERE NOT EXISTS (SELECT 1 FROM atributo a WHERE a.codigo = v.codigo);

INSERT INTO valor_atributo (atributo_id, valor, orden)
SELECT a.id, v.valor, v.orden
FROM (VALUES
    ('talla','S',1),('talla','M',2),('talla','L',3),('talla','XL',4),
    ('talla','40',5),('talla','42',6),('talla','44',7),('talla','UNICA',8),
    ('color','Negro',1),('color','Blanco',2),('color','Azul',3)
) AS v(acod, valor, orden)
JOIN atributo a ON a.codigo = v.acod
WHERE NOT EXISTS (SELECT 1 FROM valor_atributo va
                  WHERE va.atributo_id = a.id AND va.valor = v.valor);

-- 5b) Asociacion variante-atributo derivada del SKU (TALLA y COLOR)
INSERT INTO variante_valor_atributo (producto_variante_id, valor_atributo_id)
SELECT pv.id, va.id
FROM producto_variante pv
JOIN atributo a  ON a.codigo = 'talla'
JOIN valor_atributo va ON va.atributo_id = a.id
                      AND va.valor = split_part(pv.sku, '-', 2)
ON CONFLICT (producto_variante_id, valor_atributo_id) DO NOTHING;

INSERT INTO variante_valor_atributo (producto_variante_id, valor_atributo_id)
SELECT pv.id, va.id
FROM producto_variante pv
JOIN atributo a  ON a.codigo = 'color'
JOIN valor_atributo va ON va.atributo_id = a.id
                      AND va.valor = CASE split_part(pv.sku, '-', 3)
                                        WHEN 'NEG' THEN 'Negro'
                                        WHEN 'BLA' THEN 'Blanco'
                                        WHEN 'AZU' THEN 'Azul' END
ON CONFLICT (producto_variante_id, valor_atributo_id) DO NOTHING;

-- 6) Proveedores
INSERT INTO proveedor (ruc, razon_social, nombre_comercial, email, telefono, direccion, dias_credito)
SELECT v.ruc, v.razon, v.comercial, v.email, v.telefono, v.direccion, v.dias
FROM (VALUES
    ('1790012345001','Distribuidora Deportiva Andina S.A.','DeportAndina',
     'ventas@deportandina.ec','02-2456789','Av. Amazonas N34-120, Quito', 30),
    ('0990876543001','Importadora Global Sport Cia. Ltda.','GlobalSport',
     'pedidos@globalsport.ec','04-2334455','Cdla. Kennedy Norte Mz 407, Guayaquil', 15)
) AS v(ruc, razon, comercial, email, telefono, direccion, dias)
WHERE NOT EXISTS (SELECT 1 FROM proveedor pr WHERE pr.ruc = v.ruc);

-- 7) Bodegas
INSERT INTO bodega (codigo, nombre, direccion, es_principal)
SELECT v.codigo, v.nombre, v.direccion, v.principal
FROM (VALUES
    ('BOD-01','Bodega Central Quevedo','Km 2 via Quevedo - El Empalme', true),
    ('BOD-02','Bodega Norte','Parque industrial Norte, nave 4', false)
) AS v(codigo, nombre, direccion, principal)
WHERE NOT EXISTS (SELECT 1 FROM bodega b WHERE b.codigo = v.codigo);

-- 8) Metodos de pago (pago a proveedor y futuro ciclo de venta)
INSERT INTO metodo_pago (codigo, nombre, tipo, orden)
SELECT v.codigo, v.nombre, v.tipo, v.orden
FROM (VALUES
    ('TRANSF','Transferencia bancaria','transferencia',1),
    ('EFECT', 'Efectivo',              'efectivo',      2)
) AS v(codigo, nombre, tipo, orden)
WHERE NOT EXISTS (SELECT 1 FROM metodo_pago mp WHERE mp.codigo = v.codigo);

-- 9) Stock inicial: 10 unidades por variante en la bodega principal
INSERT INTO inventario (producto_variante_id, bodega_id, stock_actual, stock_minimo)
SELECT pv.id, b.id, 10, 2
FROM producto_variante pv
CROSS JOIN bodega b
WHERE b.codigo = 'BOD-01'
ON CONFLICT (producto_variante_id, bodega_id) DO NOTHING;
