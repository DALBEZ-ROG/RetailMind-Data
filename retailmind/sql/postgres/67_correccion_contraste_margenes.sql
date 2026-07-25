-- =====================================================================
-- 67_correccion_contraste_margenes.sql
-- CORRECCION DE CONTRASTE DE MARGENES  (fecha: 2026-07-24)
-- ---------------------------------------------------------------------
-- Corrige los hallazgos A2 / A3 / B4 de
-- docs/tactico/AUDITORIA_TRANSVERSAL_SEED.md.
--
-- PROBLEMA
--   producto_variante.costo estaba clavado en 0,60 x precio: 1.200 de
--   1.221 variantes con ratio en [0,5995 ; 0,6005]. Margen teorico plano
--   de 40 % en TODO el catalogo => OTD-GER-03 (ganancia por categoria) y
--   OTD-GER-10 (margen por producto) salen planos (1,35 pp de rango entre
--   las 8 categorias con venta; 0,60 pp entre los 18 meses). Ademas 4
--   variantes legacy tenian costo MAYOR que precio (ratios 1,80 / 1,60 /
--   1,52 => margen hasta -80 %), los unicos outliers del catalogo.
--
-- POR QUE LA CORRECCION ES CONTENIDA
--   Se verifico que NO existe COGS almacenado: ni pedido_detalle ni
--   factura_venta_detalle tienen columna de costo (solo cantidad,
--   precio_unitario, subtotal GENERATED, monto_descuento, monto_impuesto).
--   El margen de los informes se computa EN VIVO uniendo la linea de
--   venta contra producto_variante.costo. Por lo tanto reasignar el costo
--   vigente NO altera ningun total, ninguna factura, ningun pago y ningun
--   movimiento de kardex. Solo cambia el margen que los informes derivan.
--
-- COSTOS HISTORICOS: SE DEJAN INTACTOS (decision documentada)
--   movimiento_inventario.costo_unitario y factura_compra_detalle.
--   precio_unitario son HECHOS del momento del movimiento/compra (el
--   kardex se valoriza con el costo de la OC, no con el de la variante).
--   Reescribirlos falsearia la historia contable y romperia la cuadratura
--   de CxP que la auditoria certifico al centavo. Aqui solo se reasigna el
--   costo VIGENTE de la variante, que es el que usan los informes de
--   margen. item_defectuoso.costo_unitario y producto_proveedor.costo
--   tampoco se tocan por la misma razon.
--
-- CRITERIO DE MARGENES (reencuadre a DISTRIBUIDORA MAYORISTA / B2B)
--   Un mayorista margina MAS BAJO que un minorista porque vive del
--   volumen. Las bandas son de distribucion, no de tienda al detalle.
--
--   OJO A LA BRECHA CATALOGO -> REALIZADO: pedido_detalle.precio_unitario
--   corre ~4-5 % por debajo de producto_variante.precio (ruido de venta
--   que el seed ya introdujo), asi que el margen REALIZADO sobre la venta
--   sale ~4-5 pp por debajo del margen de catalogo. Las bandas de abajo
--   estan calibradas para que el REALIZADO —que es lo que muestra el
--   informe— caiga donde debe:
--
--     cat  categoria       base   +-amp   realizado   logica
--     ---  --------------  -----  -----  ----------  ----------------------
--      10  Electronica     13 %   4,5pp    ~ 8,6 %   commodity, precio de
--                                                    referencia publico
--       5  Abarrotes       19 %   5,5pp    ~14,5 %   consumo masivo, alta
--                                                    rotacion, margen fino
--      11  Hogar           24 %   6,0pp    ~19,7 %   medio
--       9  Deportes        27 %   6,5pp    ~22,6 %   medio
--       4  Calzado         30 %   7,0pp    ~25,6 %   medio-alto (tallas =
--                                                    riesgo de saldo)
--      12  Ropa            34 %   7,5pp    ~30,5 %   moda, riesgo de
--       1  Ropa Hombre     34 %   7,5pp     (n/a)    temporada
--       3  Ropa Mujer      34 %   7,5pp     (n/a)
--       2  Accesorios      36 %   7,5pp    ~32,6 %   ticket bajo, impulso
--       7  Belleza         38 %   7,5pp    ~34,5 %   marca y rotacion alta
--      21  Especieria      24 %   6,0pp     (n/a)    (0 variantes hoy;
--                                                    banda por completitud)
--
--   La escalera deja 3-6 pp entre cada categoria y su vecina: ninguna
--   pareja queda empatada. En particular Electronica y Abarrotes —las dos
--   que la auditoria senala como indistinguibles (0,02 pp)— quedan a ~6 pp.
--   Separacion Electronica <-> Belleza = ~26 puntos realizados, muy por
--   encima de los 12-15 pp exigidos para que el informe DISCRIMINE.
--   Global emergente esperado ~23-24 %, dentro del rango sano de
--   distribucion mayorista (15-30 %).
--
-- DISPERSION INTRA-CATEGORIA (determinista, NO aleatoria)
--   margen(v) = base(cat) + amplitud(cat) * z(v)
--   z(v) = u1 + u2 - 1, con u1,u2 = hash md5 del id de la variante
--   normalizado a [0,1] con dos semillas distintas. La suma de dos
--   uniformes da una distribucion TRIANGULAR centrada en 0 (sd ~0,40,
--   rango [-0,94 ; +0,99]): la mayoria de los productos cerca de la media
--   de su familia y colas escasas, que es como se comporta un surtido
--   real. Reproducible bit a bit: el mismo id da siempre el mismo margen.
--
-- IDEMPOTENCIA
--   El costo se deriva SIEMPRE de precio (nunca del costo actual), asi que
--   reejecutar no deriva sobre un valor ya modificado: converge al mismo
--   numero. El backup de costos originales solo se escribe la PRIMERA vez.
--
-- QUE NO SE TOCA
--   * 17 variantes legacy con costo legitimo distinto del plano 0,60
--     (ids 2-17 y 19, ratios 0,40-0,57: costos capturados a mano en el
--     catalogo demo original). Se PRESERVAN por instruccion. Consecuencia
--     documentada: las categorias 1 (Ropa Hombre) y 3 (Ropa Mujer) se
--     componen SOLO de esas variantes legacy, asi que conservan margenes
--     de epoca minorista (~55-56 %). Pesan 34 de 20.687 unidades vendidas
--     (0,16 %), de modo que no mueven ningun informe ponderado por venta.
--   * precio, y cualquier otra columna de cualquier otra tabla.
--
-- SOLO se escribe producto_variante.costo. Marca 'seed_bd_67_margenes'
-- en configuracion_tienda (incluye el backup para revertir exacto).
-- Transaccional. Ejecutar como postgres.
-- =====================================================================

BEGIN;

-- ---------------------------------------------------------------------
-- 0. BACKUP de los costos originales (solo la primera ejecucion)
-- ---------------------------------------------------------------------
INSERT INTO configuracion_tienda (clave, valor, tipo_dato, descripcion)
SELECT 'seed_bd_67_margenes_backup',
       (SELECT jsonb_object_agg(id::text, costo)::text FROM producto_variante),
       'json',
       'Script 67: costo ORIGINAL de las 1.221 variantes ANTES de la '
       || 'reasignacion de margenes por categoria. Reversion: '
       || 'UPDATE producto_variante pv SET costo = (b.v)::numeric '
       || 'FROM jsonb_each_text(valor::jsonb) b(k,v) WHERE pv.id = b.k::bigint.'
WHERE NOT EXISTS (SELECT 1 FROM configuracion_tienda
                  WHERE clave = 'seed_bd_67_margenes_backup');

-- ---------------------------------------------------------------------
-- 1. REASIGNACION DEL COSTO POR BANDA DE CATEGORIA
--    Alcance: todas las variantes MENOS las 17 legacy preservadas.
--    Incluye los 4 outliers de B4 (ids 20, 21, 22, 2423), que quedan con
--    el margen coherente de su categoria (Accesorios / Calzado).
-- ---------------------------------------------------------------------
WITH bandas(categoria_id, base, amplitud) AS (
    VALUES (1::bigint, 0.34::numeric, 0.075::numeric), -- Ropa Hombre
           (2,         0.36,          0.075),          -- Accesorios
           (3,         0.34,          0.075),          -- Ropa Mujer
           (4,         0.30,          0.070),          -- Calzado
           (5,         0.19,          0.055),          -- Abarrotes
           (7,         0.38,          0.075),          -- Belleza
           (9,         0.27,          0.065),          -- Deportes
           (10,        0.13,          0.045),          -- Electronica
           (11,        0.24,          0.060),          -- Hogar
           (12,        0.34,          0.075),          -- Ropa
           (21,        0.24,          0.060)           -- Especieria (vacia)
),
objetivo AS (
    SELECT pv.id,
           pv.precio,
           coalesce(b.base, 0.22)     AS base,
           coalesce(b.amplitud, 0.05) AS amplitud,
           -- z triangular en [-1,1], determinista por id
           ( (('x'||substr(md5('rm67a:'||pv.id::text),1,7))::bit(28)::int)::numeric / 268435455.0
           + (('x'||substr(md5('rm67b:'||pv.id::text),1,7))::bit(28)::int)::numeric / 268435455.0
           - 1 )                      AS z
    FROM producto_variante pv
    LEFT JOIN producto_categoria pc ON pc.producto_id = pv.producto_id
    LEFT JOIN bandas b              ON b.categoria_id = pc.categoria_id
    -- 17 variantes legacy con costo legitimo propio: NO se tocan
    WHERE pv.id NOT IN (2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,19)
)
UPDATE producto_variante pv
SET costo = GREATEST(round(o.precio * (1 - (o.base + o.amplitud * o.z)), 2), 0.01)
FROM objetivo o
WHERE pv.id = o.id;

-- ---------------------------------------------------------------------
-- 2. PRODUCTOS GANCHO (loss leaders) — 3 variantes deliberadas
--    Practica estandar de distribucion: el basico de TICKET BAJO y
--    altisima rotacion se vende al costo o levemente por debajo para
--    amarrar el pedido; el margen se recupera en el resto de la canasta.
--    Se eligen tres articulos de Abarrotes que cumplen las DOS
--    condiciones (precio bajo + mucha rotacion):
--      921  SKU-P1899  $11,44  90 uds  <- el mas vendido de todo el catalogo
--      844  SKU-P1822  $ 7,29  36 uds
--      897  SKU-P1875  $17,57  29 uds
--    Entre los tres suman ~$1.700 de los $570.000 de Abarrotes (0,3 %),
--    asi que dan el detalle realista SIN distorsionar el margen de la
--    categoria. Margen negativo LEVE (-3,0 % / -2,0 % / -1,5 %), nada
--    parecido a los -80 % que se acaban de eliminar.
-- ---------------------------------------------------------------------
UPDATE producto_variante SET costo = round(precio * 1.030, 2) WHERE id = 921;  -- margen -3,0 %
UPDATE producto_variante SET costo = round(precio * 1.020, 2) WHERE id = 844;  -- margen -2,0 %
UPDATE producto_variante SET costo = round(precio * 1.015, 2) WHERE id = 897;  -- margen -1,5 %

-- ---------------------------------------------------------------------
-- 3. MARCA DE EJECUCION
-- ---------------------------------------------------------------------
INSERT INTO configuracion_tienda (clave, valor, tipo_dato, descripcion)
VALUES ('seed_bd_67_margenes',
        (SELECT jsonb_build_object(
                    'fecha', now(),
                    'variantes_reasignadas', count(*) FILTER (
                        WHERE id NOT IN (2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,19)),
                    'variantes_preservadas', count(*) FILTER (
                        WHERE id IN (2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,19)),
                    'loss_leaders', jsonb_build_array(921, 844, 897)
                )::text
         FROM producto_variante),
        'json',
        'Script 67 (2026-07-24): correccion de contraste de margenes (A2/A3/B4). '
        || 'Reasigna SOLO producto_variante.costo con banda por categoria '
        || '(mayorista: Electronica 12 % .. Belleza 34 %) y dispersion '
        || 'triangular determinista por id. No toca precios, ni transacciones, '
        || 'ni costos historicos de kardex/compras. Reversion: ver '
        || 'seed_bd_67_margenes_backup.')
ON CONFLICT (clave) DO UPDATE
   SET valor = EXCLUDED.valor, descripcion = EXCLUDED.descripcion;

COMMIT;
