-- ============================================================================
-- 93_fase0_catalogo_base.sql — RetailMind · Fase 0 (1/4): parametros de diseno,
--                              marcas, categorias y proveedores (2026-08-10)
--
--   docker compose exec -T postgres psql -U postgres -d retailmind \
--       -v ON_ERROR_STOP=1 < retailmind/sql/postgres/93_fase0_catalogo_base.sql
--
-- Procedencia: TODO va a ids >= 900.000.000 (ver el script 92).
-- IDEMPOTENTE (ON CONFLICT DO NOTHING sobre la PK) y TRANSACCIONAL.
--
-- ---------------------------------------------------------------------------
-- LOS PARAMETROS DE DISENO SE GUARDAN, NO SE SUPONEN
-- ---------------------------------------------------------------------------
-- El ticket medio que produciran las fases 1-3 no depende solo del catalogo
-- que se crea aqui: depende TAMBIEN de con que frecuencia se venda cada banda.
-- Esta fase controla lo primero y solo puede DECLARAR lo segundo. Por eso los
-- pesos de demanda quedan escritos en `carga_fase_parametro`: son una entrada
-- OBLIGATORIA de las fases siguientes, no una sugerencia.
-- ============================================================================
\set ON_ERROR_STOP on

BEGIN;

-- ── 1. Parametros de diseno de la carga ─────────────────────────────────────
INSERT INTO carga_fase_parametro (fase, clave, valor, nota) VALUES
 ('fase0','ventana_temporal','2025-01-01 .. 2034-12-31',
  'Diez anios contados HACIA ADELANTE. Se extiende el futuro y no el pasado porque '
  'insertar movimientos anteriores a los existentes obligaria a reencadenar el kardex '
  'de las 1.406 posiciones vivas (scripts 78/80/84). Hacia adelante todo movimiento '
  'nuevo se anexa al final de su cadena.'),
 ('fase0','arranque_ventas_futuras','2026-09-01',
  'Primer pedido de la Fase 2. El stock inicial de la Fase 0 se fecha ANTES (2026-08-08/10) '
  'y despues del ultimo movimiento existente (2026-08-07 17:11), para que ninguna cadena '
  'de kardex tenga que reordenarse.'),
 ('fase0','banda_L1','n=2100; precio 0.45-9.90; media~4.20; costo 66% del precio',
  'Consumo diario: abarrotes, limpieza, cuidado personal. Es la banda que baja el ticket.'),
 ('fase0','banda_L2','n=1600; precio 10.00-59.90; media~26.00; costo 70% del precio',
  'Accesorios y repuestos.'),
 ('fase0','banda_L3','n=800; precio 60.00-249.00; media~128.00; costo 78% del precio',
  'Hogar, ferreteria y herramienta.'),
 ('fase0','banda_L4','n=500; precio 250.00-2400.00; media~780.00; costo 88% del precio',
  'Gama alta: linea blanca y electronica. Mantiene el ADN de ticket alto de OE-01.'),
 ('fase0','pesos_demanda_lineas','L1=65%; L2=25%; L3=6%; L4=0.8%; historico=3.2%',
  'REPARTO OBLIGATORIO DE LAS LINEAS DE VENTA en las fases 1-3. Es la mitad de la '
  'ecuacion del ticket medio: el catalogo por si solo no lo determina. Con este reparto '
  'el precio medio por unidad baja de $241,53 a ~$30,9 y el ticket de $1.400,05 a ~$184.'),
 ('fase0','unidades_por_pedido','5.061',
  'Se MANTIENE el valor medido hoy, para que el cambio de ticket sea atribuible solo a los '
  'precios y no a una cesta inventada. Si las fases siguientes lo suben, el ticket sube '
  'proporcionalmente.'),
 ('fase0','cobertura_stock_inicial','60 dias de demanda proyectada',
  'Politica de reposicion declarada. El stock inicial no es un numero redondo: sale de la '
  'demanda que las fases siguientes van a generar sobre cada banda.')
ON CONFLICT (fase, clave) DO UPDATE SET valor = EXCLUDED.valor, nota = EXCLUDED.nota;

-- ── 2. Marcas nuevas (25) ───────────────────────────────────────────────────
INSERT INTO marca (id, nombre, slug, descripcion, activo, fecha_creacion)
OVERRIDING SYSTEM VALUE
SELECT 900000000 + g.n, g.nombre,
       lower(regexp_replace(translate(g.nombre,'áéíóúÁÉÍÓÚñÑ','aeiouAEIOUnN'),'[^a-zA-Z0-9]+','-','g')),
       'Marca incorporada en la ampliacion de catalogo de 2026.', true,
       timestamptz '2026-08-08 09:00:00-05'
FROM (VALUES
 (1,'La Costena'),(2,'Supermaxi Basico'),(3,'Nutri Andes'),(4,'Aseo Total'),(5,'Brillo Hogar'),
 (6,'Dental Fresh'),(7,'Bebe Sano'),(8,'Cafe Loja'),(9,'Arroz del Valle'),(10,'Aceite Palma Real'),
 (11,'FerroQuevedo'),(12,'Herracorp'),(13,'ToolMaster'),(14,'ElectroAndina'),(15,'FrioTropic'),
 (15+1,'CocinaPlus'),(17,'LumenEc'),(18,'CablePro'),(19,'AutoParts Ecuador'),(20,'MotoRepuestos GYE'),
 (21,'Plastihogar'),(22,'TextilManabi'),(23,'PapelUio'),(24,'SeguriMax'),(25,'AgroSierra')
) AS g(n,nombre)
ON CONFLICT (id) DO NOTHING;

-- ── 3. Categorias nuevas (6) ────────────────────────────────────────────────
-- BASE 60.000 Y NO 900.000.000, y no es un capricho: `dim_producto` declara
-- `categoria_id UInt16` en ClickHouse (tope 65.535). Con la base ancha la carga
-- del almacen revienta con «Unable to create Python array», y el ETL esta fuera
-- del alcance de esta fase, asi que quien se adapta es el rango. Sigue siendo
-- inconfundible: las categorias organicas van del 1 al 21.
INSERT INTO categoria (id, nombre, slug, descripcion, orden, activo, fecha_creacion)
OVERRIDING SYSTEM VALUE
SELECT 60000 + g.n, g.nombre, g.slug, g.descripcion, 100 + g.n, true,
       timestamptz '2026-08-08 09:00:00-05'
FROM (VALUES
 (1,'Consumo Diario','consumo-diario','Abarrotes y productos de rotacion diaria.'),
 (2,'Limpieza del Hogar','limpieza-del-hogar','Detergentes, desinfectantes y utiles de aseo.'),
 (3,'Cuidado Personal','cuidado-personal','Higiene y cuidado personal de rotacion alta.'),
 (4,'Repuestos y Accesorios','repuestos-y-accesorios','Repuestos, accesorios y consumibles tecnicos.'),
 (5,'Ferreteria y Herramienta','ferreteria-y-herramienta','Herramienta manual, electrica y ferreteria.'),
 (6,'Linea Blanca y Electrodomesticos','linea-blanca-y-electrodomesticos','Electrodomesticos de gama media y alta.')
) AS g(n,nombre,slug,descripcion)
ON CONFLICT (id) DO NOTHING;

-- ── 4. Proveedores nuevos (19; con los 11 existentes quedan 30) ─────────────
-- BASE 60.000 por el mismo motivo que las categorias: CUATRO tablas del almacen
-- —dim_proveedor, fact_orden_compra, fact_compra_linea y
-- fact_devolucion_proveedor— declaran `proveedor_id UInt16`.
INSERT INTO proveedor (id, ruc, razon_social, nombre_comercial, email, telefono,
                       ciudad_id, direccion, dias_credito, activo, fecha_creacion)
OVERRIDING SYSTEM VALUE
SELECT 60000 + g.n,
       g.prov || '9' || lpad((2500100 + g.n)::text, 7, '0') || '001',
       g.razon, g.comercial,
       'compras@' || g.dominio || '.ec',
       g.tel, g.ciudad,
       g.calle || ' ' || (100 + g.n * 7)::text,
       g.credito, true, timestamptz '2026-08-08 09:00:00-05'
FROM (VALUES
 (1 ,'09','Distribuidora Alimenticia del Litoral S.A.'   ,'DALSA'        ,'dalsa'        ,'04-2510110',22,'Av. Juan Tanca Marengo',30),
 (2 ,'09','Comercializadora La Fortaleza Cia. Ltda.'     ,'La Fortaleza' ,'lafortaleza'  ,'04-2510220',22,'Km 12 Via Daule'       ,30),
 (3 ,'12','Abastos del Rio Quevedo S.A.'                 ,'AbastoRio'    ,'abastorio'    ,'05-2760330', 1,'Av. Walter Andrade'    ,15),
 (4 ,'17','Quimica y Aseo Andino Cia. Ltda.'             ,'QuimiAndino'  ,'quimiandino'  ,'02-2470440',25,'Av. Maldonado'         ,45),
 (5 ,'09','Higiene Profesional del Ecuador S.A.'         ,'HigiPro'      ,'higipro'      ,'04-2510550',22,'Av. Las Americas'      ,30),
 (6 ,'17','Importadora Ferretera Nacional Cia. Ltda.'    ,'FerreNacional','ferrenacional','02-2470660',25,'Av. 10 de Agosto'      ,45),
 (7 ,'01','Herramientas del Austro S.A.'                 ,'HerrAustro'   ,'herraustro'   ,'07-2880770',27,'Av. Espana'            ,30),
 (8 ,'09','ElectroImport del Pacifico S.A.'              ,'ElectroPac'   ,'electropac'   ,'04-2510880',22,'Av. Francisco de Orellana',60),
 (9 ,'17','Linea Blanca Andina Cia. Ltda.'               ,'LineAndina'   ,'lineandina'   ,'02-2470990',25,'Av. Eloy Alfaro'       ,60),
 (10,'09','Repuestos Automotrices del Guayas S.A.'       ,'RepuGuayas'   ,'repuguayas'   ,'04-2511100',22,'Av. Domingo Comin'     ,30),
 (11,'12','Motopartes Los Rios Cia. Ltda.'               ,'MotoRios'     ,'motorios'     ,'05-2761210', 2,'Av. Universitaria'     ,15),
 (12,'13','Plasticos y Envases de Manabi S.A.'           ,'PlastiManabi' ,'plastimanabi' ,'05-2622320',28,'Via Circunvalacion'    ,30),
 (13,'18','Textiles del Centro Cia. Ltda.'               ,'TextilCentro' ,'textilcentro' ,'03-2823430',31,'Av. Cevallos'          ,45),
 (14,'17','Papelera y Suministros Quito S.A.'            ,'PapelSuministros','papelsuministros','02-2471540',25,'Av. America'    ,30),
 (15,'09','Seguridad Industrial del Ecuador Cia. Ltda.'  ,'SegurIndustrial','segurindustrial','04-2511650',22,'Av. Barcelona'    ,30),
 (16,'06','Agroinsumos de la Sierra S.A.'                ,'AgroSierra'   ,'agrosierraec' ,'06-2941760',32,'Av. Daniel Leon Borja',45),
 (17,'12','Consumo Masivo Quevedo Cia. Ltda.'            ,'ConsuMasivo'  ,'consumasivo'  ,'05-2761870', 1,'Av. June Guzman'       ,15),
 (18,'11','Comercial del Sur Loja S.A.'                  ,'ComerSur'     ,'comersur'     ,'07-2571980',33,'Av. Universitaria'     ,30),
 (19,'23','Distribuidora Santo Domingo Cia. Ltda.'       ,'DistriSanto'  ,'distrisanto'  ,'02-3712090',35,'Av. Quito'             ,30)
) AS g(n,prov,razon,comercial,dominio,tel,ciudad,calle,credito)
ON CONFLICT (id) DO NOTHING;

-- ── 5. Bitacora ─────────────────────────────────────────────────────────────
SELECT fn_carga_registrar('fase0','93_fase0_catalogo_base','marca',
       900000001, 900000025, (SELECT count(*) FROM marca WHERE id >= 900000000),
       'Marcas de la ampliacion de catalogo.');
SELECT fn_carga_registrar('fase0','93_fase0_catalogo_base','categoria',
       60001, 60006, (SELECT count(*) FROM categoria WHERE id >= 60000),
       'Categorias de las bandas L1-L4.');
SELECT fn_carga_registrar('fase0','93_fase0_catalogo_base','proveedor',
       60001, 60019, (SELECT count(*) FROM proveedor WHERE id >= 60000),
       'Proveedores nuevos; con los 11 existentes quedan 30.');

COMMIT;

\echo ''
SELECT 'marcas' t, count(*) FROM marca UNION ALL
SELECT 'categorias', count(*) FROM categoria UNION ALL
SELECT 'proveedores', count(*) FROM proveedor;
