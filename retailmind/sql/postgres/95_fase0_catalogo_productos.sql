-- ============================================================================
-- 95_fase0_catalogo_productos.sql — RetailMind · Fase 0 (3/4): 5.000 productos
--                                   y sus variantes (2026-08-10)
--
--   docker compose exec -T postgres psql -U postgres -d retailmind \
--       -v ON_ERROR_STOP=1 < retailmind/sql/postgres/95_fase0_catalogo_productos.sql
--
-- Procedencia: ids >= 900.000.000 (script 92). IDEMPOTENTE y por lotes.
--
-- ---------------------------------------------------------------------------
-- POR QUE EL CATALOGO CRECE **HACIA ABAJO**
-- ---------------------------------------------------------------------------
-- El proyecto se define en su mision y en OE-01 como comercio minorista «de
-- ticket alto», y hoy lo es de verdad: las 1.221 variantes existentes tienen
-- precio entre $7,29 y $499,86, con MEDIANA $253,81, y el ticket medio es
-- $1.400,05. A 3.000.000 de pedidos eso serian ~$420 millones al ano, que para
-- una empresa de Quevedo no se sostiene delante de nadie.
--
-- La salida NO es tocar los precios existentes —eso desmentiria los 4.083
-- pedidos ya cargados, cuyos importes salieron de esos precios— sino AMPLIAR
-- EL CATALOGO HACIA ABAJO: incorporar las lineas de consumo que un minorista
-- multicategoria vende todos los dias. Asi el negocio se lee como lo que seria
-- en la vida real —una casa comercial de electrodomesticos que suma abarrotes,
-- limpieza y repuestos— y no como una empresa a la que le cambiaron los
-- precios sin explicacion.
--
--   Banda  n      precio            media    costo   linea comercial
--   L1     2.100  $0,45 –    $9,90  ~$4,20    66 %   consumo diario
--   L2     1.600  $10,00 –  $59,90  ~$26,00   70 %   accesorios y repuestos
--   L3       800  $60,00 – $249,00  ~$128,00  78 %   hogar y ferreteria
--   L4       500  $250,00–$2.400,00 ~$780,00  88 %   linea blanca (el ADN)
--
-- La L4 existe para que el ticket alto SIGA TENIENDO donde apoyarse: el
-- catalogo no se abarata, se ENSANCHA. Los margenes por banda replican la
-- logica del script 67 (mayorista: electronica margen fino, consumo margen
-- ancho) y en conjunto dan ~72 %, que es exactamente la razon costo/precio
-- medida hoy sobre las variantes existentes (72,1 %).
--
-- EL TICKET MEDIO NO LO DECIDE ESTE SCRIPT SOLO. Depende tambien de con que
-- frecuencia se venda cada banda, y eso lo deciden las fases 1-3. Por eso los
-- pesos de demanda quedaron escritos en `carga_fase_parametro` (script 93):
-- L1 65 %, L2 25 %, L3 6 %, L4 0,8 %, historico 3,2 % de las LINEAS.
--
-- ---------------------------------------------------------------------------
-- EL PRECIO DENTRO DE CADA BANDA: DISTRIBUCION SESGADA Y ORDEN PERMUTADO
-- ---------------------------------------------------------------------------
-- `precio = min + (max-min) * u^k` con u recorriendo los cuantiles (i-0,5)/n.
-- El exponente k se despeja para que la MEDIA caiga en el valor de la tabla, y
-- k > 1 sesga hacia abajo, que es la forma real de un catalogo: muchas
-- referencias baratas y pocas caras.
--
-- El cuantil NO se asigna en orden de id, sino permutado con `(i*1279) mod n`
-- (1279 es primo y no divide a ningun n, luego es una PERMUTACION: el conjunto
-- de precios es exactamente el disenado y solo cambia el reparto). Sin eso, el
-- precio crecia monotonamente con el id y el catalogo delataba que estaba
-- generado con solo ordenarlo.
--
-- ---------------------------------------------------------------------------
-- LA MARCA Y LA PRESENTACION CUELGAN DEL TIPO, NO DEL INDICE
-- ---------------------------------------------------------------------------
-- La primera version de este script sorteaba marca y presentacion de forma
-- independiente del producto, y salieron nombres como «Laptop La Costena 300 L»,
-- «Harina CablePro 900 ml» y «Funda protectora 3/8"». Eso no es un detalle
-- estetico: es la firma inconfundible de un dato generado, y basta ordenar el
-- catalogo por nombre para verla.
--
-- Ahora cada TIPO de producto declara dos cosas: a que FAMILIA COMERCIAL
-- pertenece (alimentos, aseo, ferreteria, electro, tecnologia, repuestos,
-- hogar) y que GRUPO DE PRESENTACION admite (kilos, litros, voltaje, pulgadas
-- de TV, capacidad en litros, gigas…). La marca se elige solo entre las de su
-- familia —una marca puede estar en varias— y la presentacion solo entre las
-- de su grupo. El peso tambien sale del grupo, que es lo que de verdad dice si
-- algo pesa 300 g o 80 kg.
-- ============================================================================
\set ON_ERROR_STOP on

CREATE TEMP TABLE f0_tipo(banda text, i int, tipo text, familia text, grupo text);
CREATE TEMP TABLE f0_pres(grupo text, i int, v text, factor numeric);
CREATE TEMP TABLE f0_marca(familia text, i int, marca_id bigint);
CREATE TEMP TABLE f0_peso(grupo text, pmin numeric, pmax numeric);
CREATE TEMP TABLE f0_cat(banda text, i int, categoria_id bigint, hasta int);
CREATE TEMP TABLE f0_vari(familia text, i int, v text);

-- ── Tipos de producto: banda · familia comercial · grupo de presentacion ────
INSERT INTO f0_tipo(banda,i,tipo,familia,grupo) SELECT 'L1', row_number() OVER (), t,f,g FROM (VALUES
 ('Arroz','alim','seco'),('Azucar','alim','seco'),('Aceite vegetal','alim','liq'),
 ('Fideo','alim','seco'),('Atun','alim','lata'),('Leche','alim','liq'),
 ('Cafe','alim','seco'),('Harina','alim','seco'),('Sal','alim','seco'),
 ('Lenteja','alim','seco'),('Frejol','alim','seco'),('Avena','alim','seco'),
 ('Galletas','alim','paqcons'),('Jabon de tocador','aseo','paqcons'),
 ('Detergente','aseo','seco'),('Cloro','aseo','liq'),('Desinfectante','aseo','liq'),
 ('Papel higienico','aseo','paqcons'),('Servilletas','aseo','paqcons'),
 ('Shampoo','aseo','liq'),('Acondicionador','aseo','liq'),('Pasta dental','aseo','gpers'),
 ('Cepillo dental','aseo','paqcons'),('Desodorante','aseo','gpers'),
 ('Toallas humedas','aseo','paqcons'),('Panales','aseo','paqcons'),
 ('Esponja','aseo','paqcons'),('Ambientador','aseo','liq'),
 ('Suavizante','aseo','liq'),('Lavavajillas liquido','aseo','liq')) x(t,f,g);

INSERT INTO f0_tipo(banda,i,tipo,familia,grupo) SELECT 'L2', row_number() OVER (), t,f,g FROM (VALUES
 ('Filtro de aceite','repu','aplic'),('Bujia','repu','paq'),
 ('Correa de distribucion','repu','aplic'),('Pastillas de freno','repu','aplic'),
 ('Foco halogeno','repu','volt'),('Cable USB','tecno','metro'),
 ('Cargador','tecno','volt'),('Audifonos','tecno','calidad'),
 ('Mouse','tecno','calidad'),('Teclado','tecno','calidad'),
 ('Funda protectora','tecno','calidad'),('Protector de pantalla','tecno','paq'),
 ('Pila alcalina','tecno','paq'),('Extension electrica','ferre','metro'),
 ('Enchufe','ferre','volt'),('Interruptor','ferre','volt'),
 ('Cinta aislante','ferre','paq'),('Brocha','ferre','pulg'),
 ('Rodillo','ferre','pulg'),('Guantes de trabajo','ferre','paq'),
 ('Mascarilla','ferre','paq'),('Candado','ferre','pulg'),
 ('Bisagra','ferre','pulg'),('Manija','ferre','calidad'),
 ('Tornillos','ferre','paq'),('Tacos de expansion','ferre','paq'),
 ('Silicona','ferre','aplic'),('Pegamento','ferre','aplic'),
 ('Llavero','hogar','calidad'),('Linterna','tecno','volt')) x(t,f,g);

INSERT INTO f0_tipo(banda,i,tipo,familia,grupo) SELECT 'L3', row_number() OVER (), t,f,g FROM (VALUES
 ('Taladro percutor','ferre','pot'),('Amoladora','ferre','pot'),
 ('Sierra circular','ferre','pot'),('Juego de llaves','ferre','pzas'),
 ('Compresor','ferre','cap'),('Escalera','ferre','pzas'),
 ('Hidrolavadora','ferre','pot'),('Soldadora','ferre','pot'),
 ('Caja de herramientas','ferre','pzas'),('Motobomba','ferre','pot'),
 ('Ventilador de pie','electro','pot'),('Licuadora','electro','cap'),
 ('Olla arrocera','electro','cap'),('Sarten antiadherente','hogar','cm'),
 ('Juego de ollas','hogar','pzas'),('Aspiradora','electro','pot'),
 ('Plancha','electro','pot'),('Cafetera','electro','cap'),
 ('Batidora','electro','pot'),('Horno electrico','electro','cap')) x(t,f,g);

INSERT INTO f0_tipo(banda,i,tipo,familia,grupo) SELECT 'L4', row_number() OVER (), t,f,g FROM (VALUES
 ('Refrigeradora','electro','litros'),('Cocina de induccion','electro','quemad'),
 ('Lavadora','electro','kg'),('Secadora','electro','kg'),
 ('Televisor LED','electro','pulgtv'),('Aire acondicionado','electro','btu'),
 ('Microondas','electro','litros'),('Congelador','electro','litros'),
 ('Lavavajillas','electro','cubiertos'),('Calefon','electro','litrosmin'),
 ('Laptop','tecno','info'),('Monitor','tecno','pulgtv'),
 ('Impresora multifuncion','tecno','impre'),('Consola de videojuego','tecno','info'),
 ('Equipo de sonido','electro','pot4')) x(t,f,g);

-- ── Presentaciones: solo las que su grupo admite ────────────────────────────
INSERT INTO f0_pres(grupo,i,v,factor)
SELECT g, row_number() OVER (PARTITION BY g), v, f FROM (VALUES
 ('seco','250 g',0.30),('seco','500 g',0.55),('seco','1 kg',1.00),('seco','2 kg',1.80),('seco','5 kg',4.00),
 ('liq','400 ml',0.45),('liq','750 ml',0.80),('liq','900 ml',0.95),('liq','1 L',1.00),('liq','3 L',2.60),
 ('lata','170 g',0.50),('lata','x2',1.00),('lata','x3',1.45),('lata','x4',1.90),
 ('paqcons','x2',0.40),('paqcons','x4',0.75),('paqcons','x6',1.00),('paqcons','x12',1.90),('paqcons','x24',3.60),
 ('gpers','75 g',0.60),('gpers','100 g',0.80),('gpers','150 g',1.15),
 ('metro','1,5 m',0.70),('metro','2 m',0.90),('metro','3 m',1.20),('metro','5 m',1.80),
 ('paq','x10',0.50),('paq','x25',1.00),('paq','x50',1.80),('paq','x100',3.20),
 ('volt','12 V',0.90),('volt','110 V',1.00),('volt','220 V',1.15),
 ('pulg','3/8 pulg',0.70),('pulg','1/2 pulg',0.90),('pulg','3/4 pulg',1.20),('pulg','1 pulg',1.50),
 ('calidad','estandar',0.70),('calidad','reforzado',1.00),('calidad','universal',1.10),('calidad','premium',1.60),
 ('aplic','universal',1.00),('aplic','linea liviana',0.85),('aplic','linea pesada',1.30),
 ('pot','650 W',0.70),('pot','850 W',0.95),('pot','1200 W',1.30),('pot','1800 W',1.80),
 ('cap','3 L',0.60),('cap','5 L',0.85),('cap','7 L',1.10),('cap','10 L',1.40),('cap','20 L',2.20),
 ('pzas','3 piezas',0.50),('pzas','5 piezas',0.80),('pzas','12 piezas',1.50),('pzas','20 piezas',2.30),
 ('cm','20 cm',0.70),('cm','24 cm',0.90),('cm','28 cm',1.15),('cm','30 cm',1.30),
 ('litros','250 L',0.70),('litros','300 L',0.85),('litros','420 L',1.15),('litros','500 L',1.40),
 ('quemad','4 quemadores',0.90),('quemad','5 quemadores',1.15),
 ('kg','12 kg',0.75),('kg','15 kg',0.90),('kg','18 kg',1.10),('kg','22 kg',1.40),
 ('pulgtv','32 pulgadas',0.50),('pulgtv','43 pulgadas',0.75),('pulgtv','50 pulgadas',1.00),
 ('pulgtv','55 pulgadas',1.30),('pulgtv','65 pulgadas',2.00),
 ('btu','12000 BTU',0.75),('btu','18000 BTU',1.00),('btu','24000 BTU',1.35),
 ('cubiertos','12 cubiertos',0.90),('cubiertos','14 cubiertos',1.10),
 ('litrosmin','6 L/min',0.70),('litrosmin','10 L/min',1.00),('litrosmin','16 L/min',1.40),
 ('info','8 GB',0.70),('info','16 GB',1.00),('info','512 GB',1.10),('info','1 TB',1.50),
 ('impre','tinta continua',0.90),('impre','laser',1.30),('impre','multifuncion',1.00),
 ('pot4','200 W',0.60),('pot4','500 W',1.00),('pot4','1000 W',1.60)) x(g,v,f);

-- ── Peso por grupo de presentacion: es el grupo, y no la banda, el que dice
--    si una referencia pesa 300 g o 80 kg. ────────────────────────────────────
INSERT INTO f0_peso(grupo,pmin,pmax) VALUES
 ('seco',0.25,5.20),('liq',0.45,3.10),('lata',0.18,0.90),('paqcons',0.12,1.60),
 ('gpers',0.08,0.30),('metro',0.05,0.60),('paq',0.06,1.40),('volt',0.10,0.85),
 ('pulg',0.15,1.20),('calidad',0.09,0.95),('aplic',0.20,2.40),
 ('pot',2.10,9.50),('cap',1.40,12.00),('pzas',1.00,8.50),('cm',0.60,3.00),
 ('litros',48.00,92.00),('quemad',38.00,55.00),('kg',52.00,78.00),
 ('pulgtv',4.20,22.00),('btu',26.00,46.00),('cubiertos',42.00,56.00),
 ('litrosmin',12.00,20.00),('info',1.40,8.00),('impre',5.50,14.00),('pot4',3.00,12.00);

-- ── Marcas por familia. Una marca puede vivir en varias familias. ───────────
INSERT INTO f0_marca(familia,i,marca_id) SELECT f, row_number() OVER (PARTITION BY f), 900000000+m FROM (VALUES
 ('alim',1),('alim',2),('alim',3),('alim',8),('alim',9),('alim',10),
 ('aseo',2),('aseo',4),('aseo',5),('aseo',6),('aseo',7),
 ('ferre',11),('ferre',12),('ferre',13),('ferre',17),('ferre',24),('ferre',25),
 ('electro',14),('electro',15),('electro',16),('electro',17),
 ('tecno',18),('tecno',14),('tecno',17),('tecno',24),
 ('repu',19),('repu',20),('repu',18),('repu',12),
 ('hogar',21),('hogar',22),('hogar',23),('hogar',16),('hogar',2)) x(f,m);

-- ── Variedad comercial: lo que distingue dos referencias del mismo tipo,
--    marca y presentacion. Sin ella el catalogo repetia nombre exacto con dos
--    precios distintos (2.100 productos de consumo sobre ~600 combinaciones),
--    que es la clase de duplicado que delata el dato generado.
INSERT INTO f0_vari(familia,i,v) SELECT f, row_number() OVER (PARTITION BY f), v FROM (VALUES
 ('alim','Tradicional'),('alim','Integral'),('alim','Premium'),('alim','Light'),
 ('alim','Extra'),('alim','Familiar'),('alim','Clasico'),('alim','Selecto'),
 ('alim','Organico'),('alim','Sin Gluten'),('alim','Economico'),('alim','Gourmet'),
 ('aseo','Aloe'),('aseo','Citrico'),('aseo','Lavanda'),('aseo','Manzanilla'),
 ('aseo','Original'),('aseo','Bebe'),('aseo','Antibacterial'),('aseo','Floral'),
 ('aseo','Coco'),('aseo','Menta'),('aseo','Neutro'),('aseo','Marino'),
 ('ferre','Serie 100'),('ferre','Serie 200'),('ferre','Serie 300'),('ferre','Pro'),
 ('ferre','Heavy Duty'),('ferre','Compacto'),('ferre','Plus'),('ferre','Industrial'),
 ('electro','Blanco'),('electro','Gris'),('electro','Acero'),('electro','Negro'),
 ('electro','Inverter'),('electro','Digital'),('electro','Smart'),('electro','Eco'),
 ('tecno','Negro'),('tecno','Blanco'),('tecno','Azul'),('tecno','Pro'),
 ('tecno','Slim'),('tecno','Gamer'),('tecno','Basico'),('tecno','Plus'),
 ('repu','OEM'),('repu','Reforzado'),('repu','Original'),('repu','Economico'),
 ('repu','Alta Duracion'),('repu','Estandar'),
 ('hogar','Azul'),('hogar','Rojo'),('hogar','Verde'),('hogar','Beige'),
 ('hogar','Clasico'),('hogar','Moderno')) x(f,v);

-- ── Reparto de categorias por banda (`hasta` = tope acumulado dentro de la banda)
INSERT INTO f0_cat(banda,i,categoria_id,hasta) VALUES
 ('L1',1,60001, 900),('L1',2,60002,1500),('L1',3,60003,1900),('L1',4,    5,2100),
 ('L2',1,60004, 900),('L2',2,    2,1300),('L2',3,    9,1600),
 ('L3',1,60005, 500),('L3',2,   11, 800),
 ('L4',1,60006, 300),('L4',2,   10, 500);

-- ── 1. Tabla de trabajo: las 5.000 variantes ya calculadas ──────────────────
--
--    COMO SE ELIGE LA COMBINACION (tipo · presentacion · marca · variedad)
--    -------------------------------------------------------------------
--    La version anterior sorteaba cada dimension con `(i * k) mod n` y un
--    multiplicador distinto por dimension. Parecia independiente y no lo era:
--    todas se mueven con el MISMO i, asi que la tupla completa es periodica y
--    su periodo es el minimo comun multiplo de los tamanos. Medido: 5.000
--    productos daban **370 nombres distintos**, con «Cafe La Costena
--    Tradicional 1 kg» repetido 35 veces a 35 precios diferentes.
--
--    Ahora se ENUMERA el espacio de combinaciones licitas —cada tipo solo con
--    las presentaciones de SU grupo, las marcas de SU familia y las variedades
--    de SU familia—, se baraja con un md5 y se toman las n primeras. Como el
--    espacio de cada banda es mayor que su n (L1: ~9.800 combinaciones para
--    2.100 productos), la unicidad del nombre queda GARANTIZADA por
--    construccion y el barajado impide que los ids consecutivos compartan tipo.
CREATE TEMP TABLE f0_var AS
WITH banda AS (
    SELECT * FROM (VALUES
      ('L1',2100,     1, 0.45::numeric,    9.90::numeric, 1.52::numeric, 0.66::numeric),
      ('L2',1600,  2101,10.00::numeric,   59.90::numeric, 2.12::numeric, 0.70::numeric),
      ('L3', 800,  3701,60.00::numeric,  249.00::numeric, 1.78::numeric, 0.78::numeric),
      ('L4', 500,  4501,250.00::numeric,2400.00::numeric, 3.06::numeric, 0.88::numeric)
    ) AS b(banda, n, base, pmin, pmax, k, ratio)
),
combos AS (
    SELECT t.banda, t.tipo, t.familia, t.grupo, p.v AS presentacion, p.factor,
           mk.marca_id, vr.v AS variedad,
           row_number() OVER (PARTITION BY t.banda
                              ORDER BY md5(t.tipo || '|' || p.v || '|' || mk.marca_id::text
                                           || '|' || vr.v)) AS rn
    FROM f0_tipo  t
    JOIN f0_pres  p  ON p.grupo    = t.grupo
    JOIN f0_marca mk ON mk.familia = t.familia
    JOIN f0_vari  vr ON vr.familia = t.familia
),
precios AS (
    -- El CONJUNTO de precios de la banda es exactamente el disenado. Lo unico
    -- que se decide abajo es a QUE producto le toca cada uno.
    SELECT b.banda, b.base, b.n, b.ratio, i,
           b.pmin + (b.pmax - b.pmin)
                  * power((i - 0.5) / b.n, b.k) AS precio_raw,
           row_number() OVER (PARTITION BY b.banda
                              ORDER BY b.pmin + (b.pmax - b.pmin)
                                       * power((i - 0.5) / b.n, b.k)) AS pr
    FROM banda b, generate_series(1, b.n) i
),
productos_ord AS (
    -- …y le toca en un orden CORRELACIONADO CON EL TAMANO del envase, con
    -- ruido: sin esto el arroz de 2 kg salia a $0,55 y el de 250 g a $3,15.
    -- El factor se altera +-40 % con un hash, de modo que la correlacion es
    -- fuerte pero no mecanica, que es como se comporta un catalogo real.
    SELECT k.*, row_number() OVER (
             PARTITION BY k.banda
             ORDER BY k.factor * (0.60 + 0.80 *
                      ((('x' || substr(md5('jit' || k.banda || k.rn::text), 1, 7))::bit(28)::int % 1000) / 999.0)),
                      k.rn) AS sr
    FROM combos k
    WHERE k.rn <= (SELECT b.n FROM banda b WHERE b.banda = k.banda)
),
crudo AS (
    SELECT p.banda, p.base + k.rn - 1 AS nglobal, k.rn AS i, p.n, p.precio_raw, p.ratio,
           k.tipo, k.familia, k.grupo, k.presentacion, k.marca_id, k.variedad
    FROM productos_ord k
    JOIN precios p ON p.banda = k.banda AND p.pr = k.sr
)
SELECT c.banda, c.nglobal, c.i, c.tipo, c.familia, c.grupo,
       -- Redondeo COMERCIAL, distinto por banda: a $0,05 en consumo (donde
       -- forzar `,90` distorsionaria un producto de $0,62 un 45 %), a terminacion
       -- comercial en las medias, y a multiplo de $5 menos diez centavos en la alta.
       CASE c.banda
         WHEN 'L1' THEN GREATEST(0.45, round(c.precio_raw / 0.05) * 0.05)
         WHEN 'L4' THEN GREATEST(250.00, round(c.precio_raw / 5) * 5 - 0.10)
         ELSE floor(c.precio_raw) + (ARRAY[0.50,0.90,0.95,0.99])[(c.i % 4) + 1]
       END::numeric(12,2) AS precio,
       c.ratio, c.presentacion, c.marca_id,
       (SELECT fc.categoria_id FROM f0_cat fc
         WHERE fc.banda = c.banda AND fc.hasta >= c.i ORDER BY fc.hasta LIMIT 1) AS categoria_id,
       -- El peso sale del GRUPO de presentacion, que es lo que de verdad dice
       -- si algo pesa 300 g o 80 kg, y se dispersa con un hash independiente.
       round((pe.pmin + (pe.pmax - pe.pmin)
              * ((('x' || substr(md5('peso' || c.nglobal::text), 1, 7))::bit(28)::int % 100) / 99.0)
             )::numeric, 3) AS peso_kg,
       c.variedad
FROM crudo c
JOIN f0_peso pe ON pe.grupo = c.grupo;

CREATE INDEX ON f0_var(nglobal);

-- ── 2. producto ─────────────────────────────────────────────────────────────
BEGIN;
INSERT INTO producto (id, marca_id, nombre, slug, descripcion_corta, descripcion,
                      publicado, destacado, activo, fecha_creacion)
OVERRIDING SYSTEM VALUE
SELECT 900000000 + v.nglobal, v.marca_id,
       left(v.tipo || ' ' || m.nombre || ' ' || v.variedad || ' ' || v.presentacion, 200),
       left(lower(regexp_replace(
              translate(v.tipo || '-' || m.nombre || '-' || v.variedad || '-' || v.presentacion,
                        'áéíóúÁÉÍÓÚñÑ",/', 'aeiouAEIOUnN   '),
              '[^a-zA-Z0-9]+', '-', 'g')) || '-' || v.nglobal::text, 220),
       left(v.tipo || ' ' || m.nombre || ' ' || v.variedad, 500),
       v.tipo || ' ' || m.nombre || ' ' || v.variedad || ', presentacion ' || v.presentacion
         || '. Incorporado en la ampliacion de catalogo de 2026.',
       true, (v.nglobal % 250) = 0, true, timestamptz '2026-08-08 09:30:00-05'
FROM f0_var v JOIN marca m ON m.id = v.marca_id
ON CONFLICT (id) DO NOTHING;

-- `es_principal` NO es decorativo y su DEFECTO es `false`: el ETL de
-- `dim_producto` une con `AND pc.es_principal`, asi que sin marcarlo las 5.000
-- variantes nuevas llegan al almacen con `categoria = 'sin_categoria'`. Lo
-- detecto la validacion de la carga —«Categorias distintas: origen 10 vs
-- destino 11»— y la tabla NO se publico, que es exactamente para lo que esta.
INSERT INTO producto_categoria (id, producto_id, categoria_id, es_principal, fecha_creacion)
OVERRIDING SYSTEM VALUE
SELECT 900000000 + v.nglobal, 900000000 + v.nglobal, v.categoria_id, true,
       timestamptz '2026-08-08 09:30:00-05'
FROM f0_var v
ON CONFLICT (id) DO NOTHING;
COMMIT;

-- ── 3. producto_variante ────────────────────────────────────────────────────
--    SKU y codigo de barras UNICOS por construccion. El codigo es un EAN-13
--    valido (prefijo 786 = Ecuador) con su digito de control real.
BEGIN;
INSERT INTO producto_variante (id, producto_id, sku, codigo_barras, precio, precio_comparacion,
                               costo, peso_kg, es_predeterminada, activo, fecha_creacion)
OVERRIDING SYSTEM VALUE
SELECT 900000000 + v.nglobal, 900000000 + v.nglobal,
       upper(left(translate(v.tipo,'áéíóú ','aeiou'), 3)) || '-'
         || lpad(v.nglobal::text, 4, '0') || '-'
         || upper(left(regexp_replace(translate(v.presentacion,' /','') ,'[^a-zA-Z0-9]','','g') || 'XX', 3)),
       b.base12 || ((10 - (
            (substr(b.base12, 1,1)::int + substr(b.base12, 3,1)::int + substr(b.base12, 5,1)::int
           + substr(b.base12, 7,1)::int + substr(b.base12, 9,1)::int + substr(b.base12,11,1)::int)
          + 3*(substr(b.base12, 2,1)::int + substr(b.base12, 4,1)::int + substr(b.base12, 6,1)::int
             + substr(b.base12, 8,1)::int + substr(b.base12,10,1)::int + substr(b.base12,12,1)::int)
        ) % 10) % 10)::text,
       v.precio,
       CASE WHEN v.nglobal % 7 = 0 THEN round(v.precio * 1.18, 2) ELSE NULL END,
       round(v.precio * v.ratio, 2),
       v.peso_kg, true, true, timestamptz '2026-08-08 09:30:00-05'
FROM f0_var v
JOIN LATERAL (SELECT '786' || lpad(((v.nglobal::bigint * 486187) % 1000000000)::text, 9, '0') AS base12) b ON true
ON CONFLICT (id) DO NOTHING;
COMMIT;

-- ── 4. Bitacora ─────────────────────────────────────────────────────────────
BEGIN;
SELECT fn_carga_registrar('fase0','95_fase0_catalogo_productos','producto',
       900000001, 900005000, (SELECT count(*) FROM producto WHERE id >= 900000000),
       'Bandas L1-L4; una variante por producto; marca y presentacion atadas al tipo.');
SELECT fn_carga_registrar('fase0','95_fase0_catalogo_productos','producto_categoria',
       900000001, 900005000, (SELECT count(*) FROM producto_categoria WHERE id >= 900000000), NULL);
SELECT fn_carga_registrar('fase0','95_fase0_catalogo_productos','producto_variante',
       900000001, 900005000, (SELECT count(*) FROM producto_variante WHERE id >= 900000000),
       'Precio sesgado por banda con cuantil permutado; EAN-13 valido; peso por grupo.');
COMMIT;

\echo ''
\echo '=== distribucion REAL de precios por banda (medida, no la de diseno) ==='
SELECT v.banda, count(*) n, round(min(pv.precio),2) minimo, round(avg(pv.precio),2) media,
       round(percentile_cont(0.5) WITHIN GROUP (ORDER BY pv.precio)::numeric,2) mediana,
       round(max(pv.precio),2) maximo, round(avg(pv.costo/pv.precio)*100,1) pct_costo,
       round(min(pv.peso_kg),2) peso_min, round(max(pv.peso_kg),2) peso_max
FROM f0_var v JOIN producto_variante pv ON pv.id = 900000000 + v.nglobal
GROUP BY v.banda ORDER BY v.banda;
