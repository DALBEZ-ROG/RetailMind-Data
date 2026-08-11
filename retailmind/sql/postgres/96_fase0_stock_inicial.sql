-- ============================================================================
-- 96_fase0_stock_inicial.sql — RetailMind · Fase 0 (4/4): existencias iniciales
--                              de las 5.000 variantes nuevas (2026-08-10)
--
--   docker compose exec -T postgres psql -U postgres -d retailmind \
--       -v ON_ERROR_STOP=1 < retailmind/sql/postgres/96_fase0_stock_inicial.sql
--
-- Procedencia: ids >= 900.000.000 (script 92). IDEMPOTENTE y por lotes.
--
-- ---------------------------------------------------------------------------
-- COMO ENTRA EL STOCK: OPCION (b) — CON SU ORDEN DE COMPRA
-- ---------------------------------------------------------------------------
-- Habia tres formas de dar existencias a las variantes nuevas:
--
--   (a) Escribir `inventario.stock_actual` y no tocar el kardex.
--       DESCARTADA. Rompe en el acto el invariante que hoy cuadra
--       1.406/1.406: `inventario` diria 293.500 unidades que ningun movimiento
--       explica. Y no es solo cosmetico: `fact_stock_mensual` se RECONSTRUYE
--       desde el kardex y se contrasta posicion por posicion contra
--       `inventario.stock_actual`; `validar_dwh.py` ABORTA la publicacion si
--       una sola posicion difiere. La carga entera quedaria sin almacen.
--
--   (b) Movimientos `entrada_compra` con su orden de compra detras.  ← ELEGIDA
--
--   (c) `entrada_ajuste`, como la apertura de 2025 (343 movimientos / 34.210
--       unidades). DESCARTADA por dos razones: el script 74-78 ya se dedico
--       precisamente a QUITAR esa apertura ficticia porque falseaba el origen
--       de la mercancia, y ademas `entrada_ajuste` contamina OTD-INV-10: ese
--       informe filtra por `es_ajuste_real` justamente porque la apertura se
--       registro como ajuste, y anadir 10.000 ajustes mas volveria a inflar la
--       merma aparente.
--
-- Se elige (b) porque es la UNICA que deja el dato defendible: cada unidad
-- tiene una orden, una recepcion, una factura y un proveedor con nombre. Es
-- ademas el mismo camino que recorreran las fases 1-3, asi que lo prueba.
--
-- SE CARGA EL CICLO HASTA LA CUENTA POR PAGAR, Y NO MAS. No hay `pago_proveedor`
-- a proposito: la mercancia se recibio el 9 y el 10 de agosto de 2026 y los
-- plazos de credito de estos proveedores van de 15 a 60 dias, asi que a fecha
-- de hoy NINGUNA de estas facturas ha vencido. Inventar el pago seria inventar
-- una salida de caja que no ha ocurrido. El cuadre contable
-- (facturas − pagos = saldo CxP) sigue cerrando exacto: sube el mismo importe
-- en factura y en saldo.
--
-- TAMPOCO HAY RECHAZOS EN PUERTA. El sistema real crea un `item_defectuoso`
-- automaticamente por cada `cantidad_rechazada` (script 45), y eso lo hace el
-- servicio Java, no un trigger. Escribir rechazos aqui dejaria recepciones con
-- mercancia rechazada que no aparece en ningun pool de devolucion a proveedor:
-- una incoherencia. Una recepcion completa de un pedido de apertura de
-- catalogo es, ademas, lo normal.
--
-- ---------------------------------------------------------------------------
-- LA FECHA: EXPLICITA, Y DESPUES DEL ULTIMO MOVIMIENTO QUE YA EXISTE
-- ---------------------------------------------------------------------------
-- `movimiento_inventario.fecha_creacion` se ESCRIBE (nunca se deja el
-- `DEFAULT now()`). Con `now()` todas las filas de una transaccion comparten
-- instante y el unico desempate del encadenamiento pasa a ser el `id`: si el
-- orden de los ids no coincide con el de la cadena, esta queda invertida y el
-- trigger del kardex NO lo detecta —valida la fila, no el enlace—.
--
-- Aqui el riesgo es nulo porque cada (variante, bodega) recibe UN solo
-- movimiento sobre una posicion que arranca en 0, pero la disciplina se aplica
-- igual: es la que las fases 1-3 tendran que sostener con 7,6 millones de
-- movimientos.
--
-- Las fechas van del 2026-08-09 al 2026-08-10, o sea DESPUES del ultimo
-- movimiento existente (2026-08-07 17:11) y ANTES del primer pedido de la
-- Fase 2 (2026-09-01). Asi ninguna cadena viva tiene que reordenarse.
--
-- ---------------------------------------------------------------------------
-- CUANTO STOCK: 60 DIAS DE LA DEMANDA QUE LAS FASES SIGUIENTES VAN A GENERAR
-- ---------------------------------------------------------------------------
-- No es un numero redondo elegido a ojo. Sale de la demanda proyectada:
-- 2.996.000 pedidos × 5,061 unidades = 15,16 millones de unidades en 100 meses,
-- repartidas por los pesos de `carga_fase_parametro` (L1 65 %, L2 25 %, L3 6 %,
-- L4 0,8 %).
--
--   Banda  uds/variante/mes   60 dias   Central (80 %)   Norte (20 %)
--   L1           46,9            94           75              19
--   L2           23,7            47           38               9
--   L3           11,4            23           18               5
--   L4            2,4             5            4               1
--
-- El reparto 80/20 respeta que Bodega Norte es la secundaria (hoy tiene 186 de
-- las 1.406 posiciones). Cada variante se desvia +-40 % de su base con un hash,
-- porque un stock identico en 2.100 variantes seria otro patron a la vista.
-- ============================================================================
\set ON_ERROR_STOP on

-- ── Proveedor por familia comercial ─────────────────────────────────────────
CREATE TEMP TABLE f0_prov(familia text, i int, proveedor_id bigint);
INSERT INTO f0_prov(familia,i,proveedor_id)
SELECT f, row_number() OVER (PARTITION BY f), 60000 + p FROM (VALUES
 ('alim',1),('alim',2),('alim',3),('alim',17),('alim',19),
 ('aseo',4),('aseo',5),('aseo',18),
 ('ferre',6),('ferre',7),('ferre',15),('ferre',16),
 ('electro',8),('electro',9),
 ('tecno',8),('tecno',9),
 ('repu',10),('repu',11),
 ('hogar',12),('hogar',13),('hogar',14)) x(f,p);

-- ── Banda y familia de cada variante nueva (se rederivan del catalogo) ──────
CREATE TEMP TABLE f0_v AS
SELECT pv.id AS variante_id, pv.id - 900000000 AS n, pv.costo, pv.precio,
       CASE WHEN pv.id - 900000000 <= 2100 THEN 'L1'
            WHEN pv.id - 900000000 <= 3700 THEN 'L2'
            WHEN pv.id - 900000000 <= 4500 THEN 'L3'
            ELSE 'L4' END AS banda,
       CASE c.id
         WHEN 60001 THEN 'alim' WHEN 60002 THEN 'aseo' WHEN 60003 THEN 'aseo'
         WHEN 5 THEN 'alim'
         WHEN 60004 THEN 'repu' WHEN 2 THEN 'tecno' WHEN 9 THEN 'tecno'
         WHEN 60005 THEN 'ferre' WHEN 11 THEN 'hogar'
         WHEN 60006 THEN 'electro' WHEN 10 THEN 'electro'
       END AS familia
FROM producto_variante pv
JOIN producto_categoria pc ON pc.producto_id = pv.producto_id
JOIN categoria c ON c.id = pc.categoria_id
WHERE pv.id >= 900000000;

-- ── Las 10.000 lineas (variante x bodega) con su stock y su proveedor ───────
CREATE TEMP TABLE f0_lin AS
WITH base AS (
    SELECT v.*, b.bodega_id, b.cuota,
           CASE v.banda WHEN 'L1' THEN 94 WHEN 'L2' THEN 47 WHEN 'L3' THEN 23 ELSE 5 END AS uds_banda
    FROM f0_v v
    CROSS JOIN (VALUES (4::bigint, 0.80), (3::bigint, 0.20)) AS b(bodega_id, cuota)
),
conqty AS (
    SELECT base.*,
           GREATEST(1, round(base.uds_banda * base.cuota
                 * (0.60 + 0.80 * ((('x' || substr(md5('stk' || base.variante_id::text
                                                       || base.bodega_id::text), 1, 7))::bit(28)::int
                                    % 1000) / 999.0)))::int) AS cantidad,
           p.proveedor_id
    FROM base
    JOIN LATERAL (
        SELECT fp.proveedor_id FROM f0_prov fp
        WHERE fp.familia = base.familia
          AND fp.i = ((('x' || substr(md5('prv' || base.variante_id::text), 1, 7))::bit(28)::int
                       % (SELECT count(*) FROM f0_prov WHERE familia = base.familia)) + 1)
    ) p ON true
),
numerado AS (
    -- El troceado necesita el numero de linea dentro del grupo ANTES de poder
    -- agrupar por lote, y una funcion de ventana no puede anidarse dentro de
    -- otra: hace falta este nivel intermedio.
    SELECT c.*,
           (row_number() OVER (PARTITION BY c.proveedor_id, c.bodega_id, c.banda
                               ORDER BY c.variante_id) - 1) / 25 AS lote
    FROM conqty c
)
SELECT n.*,
       -- Una orden de compra por (proveedor, bodega, banda), troceada en lotes
       -- de 25 lineas: una sola orden de 263 lineas seria un valor atipico de
       -- 77x la media actual (3,40 lineas por orden) y distorsionaria COM-01/06.
       dense_rank() OVER (ORDER BY n.proveedor_id, n.bodega_id, n.banda, n.lote) AS oc_seq,
       row_number() OVER (ORDER BY n.proveedor_id, n.bodega_id, n.banda, n.variante_id) AS lin_seq
FROM numerado n;

CREATE INDEX ON f0_lin(oc_seq);
CREATE INDEX ON f0_lin(lin_seq);

-- ── 1. Ordenes de compra + detalle ──────────────────────────────────────────
BEGIN;
INSERT INTO orden_compra (id, numero, proveedor_id, bodega_id, moneda_id, usuario_id, estado,
                          fecha_emision, fecha_entrega_esperada, subtotal, monto_impuesto, total,
                          observacion, fecha_creacion)
OVERRIDING SYSTEM VALUE
SELECT 900000000 + l.oc_seq,
       'OC-20260808-' || lpad((9000000 + l.oc_seq)::text, 7, '0'),
       min(l.proveedor_id), min(l.bodega_id), 1, 5, 'recibida',
       date '2026-08-08', date '2026-08-11', 0, 0, 0,
       'Apertura de catalogo 2026 - Fase 0', timestamptz '2026-08-08 10:00:00-05'
FROM f0_lin l GROUP BY l.oc_seq
ON CONFLICT (id) DO NOTHING;

INSERT INTO orden_compra_detalle (id, orden_compra_id, producto_variante_id, cantidad,
                                  precio_unitario, monto_impuesto, cantidad_recibida, fecha_creacion)
OVERRIDING SYSTEM VALUE
SELECT 900000000 + l.lin_seq, 900000000 + l.oc_seq, l.variante_id, l.cantidad,
       l.costo, round(l.cantidad * l.costo * 0.15, 2), l.cantidad,
       timestamptz '2026-08-08 10:00:00-05'
FROM f0_lin l
ON CONFLICT (id) DO NOTHING;
COMMIT;

-- ── 2. Recepciones ──────────────────────────────────────────────────────────
BEGIN;
INSERT INTO recepcion_mercancia (id, numero, orden_compra_id, bodega_id, usuario_id, estado,
                                 fecha_recepcion, observacion, fecha_creacion)
OVERRIDING SYSTEM VALUE
SELECT 900000000 + l.oc_seq,
       'RM-2026081' || (l.oc_seq % 2)::text || '-' || lpad((9000000 + l.oc_seq)::text, 7, '0'),
       900000000 + l.oc_seq, min(l.bodega_id), 6, 'confirmada',
       timestamptz '2026-08-09 08:00:00-05' + ((l.oc_seq % 2) * interval '1 day')
                                            + ((l.oc_seq % 480) * interval '1 minute'),
       'Recepcion completa de apertura de catalogo', timestamptz '2026-08-09 08:00:00-05'
FROM f0_lin l GROUP BY l.oc_seq
ON CONFLICT (id) DO NOTHING;

INSERT INTO recepcion_detalle (id, recepcion_mercancia_id, orden_compra_detalle_id,
                               cantidad_recibida, cantidad_rechazada, fecha_creacion)
OVERRIDING SYSTEM VALUE
SELECT 900000000 + l.lin_seq, 900000000 + l.oc_seq, 900000000 + l.lin_seq,
       l.cantidad, 0, timestamptz '2026-08-09 08:00:00-05'
FROM f0_lin l
ON CONFLICT (id) DO NOTHING;
COMMIT;

-- ── 3. Inventario (arranca en 0) y kardex de entrada ────────────────────────
--    El orden es el del sistema real: la posicion existe, el movimiento la
--    mueve, y despues `stock_actual` refleja el saldo del movimiento.
BEGIN;
INSERT INTO inventario (id, producto_variante_id, bodega_id, stock_actual, stock_reservado,
                        stock_minimo, stock_maximo, fecha_creacion)
OVERRIDING SYSTEM VALUE
SELECT 900000000 + l.lin_seq, l.variante_id, l.bodega_id, 0, 0,
       GREATEST(1, round(l.cantidad * 0.30)::int),
       GREATEST(2, round(l.cantidad * 1.80)::int),
       timestamptz '2026-08-09 07:00:00-05'
FROM f0_lin l
ON CONFLICT (id) DO NOTHING;

INSERT INTO movimiento_inventario (id, producto_variante_id, bodega_id, tipo_movimiento_id,
                                   usuario_id, cantidad, stock_anterior, stock_nuevo,
                                   costo_unitario, referencia_tipo, referencia_id,
                                   observacion, fecha_creacion)
OVERRIDING SYSTEM VALUE
SELECT 900000000 + l.lin_seq, l.variante_id, l.bodega_id, 1, 6,
       l.cantidad, 0, l.cantidad, l.costo, 'recepcion', 900000000 + l.oc_seq,
       '[FASE0] Apertura de catalogo 2026',
       -- fecha EXPLICITA y distinta fila a fila: nunca el DEFAULT now()
       timestamptz '2026-08-09 09:00:00-05' + ((l.oc_seq % 2) * interval '1 day')
                                            + (l.lin_seq * interval '3 second')
FROM f0_lin l
ON CONFLICT (id) DO NOTHING;

UPDATE inventario i SET stock_actual = l.cantidad
FROM f0_lin l
WHERE i.id = 900000000 + l.lin_seq AND i.stock_actual = 0;
COMMIT;

-- ── 4. Facturas de compra + cuentas por pagar ───────────────────────────────
BEGIN;
INSERT INTO factura_compra (id, proveedor_id, orden_compra_id, moneda_id, numero_factura,
                            fecha_emision, fecha_vencimiento, subtotal, monto_impuesto, total,
                            estado, registrado_por, fecha_creacion)
OVERRIDING SYSTEM VALUE
SELECT 900000000 + l.oc_seq, min(l.proveedor_id), 900000000 + l.oc_seq, 1,
       'FC-20260810-' || lpad((9000000 + l.oc_seq)::text, 7, '0'),
       date '2026-08-10',
       date '2026-08-10' + (SELECT p.dias_credito FROM proveedor p WHERE p.id = min(l.proveedor_id)),
       0, 0, 0, 'registrada', 5, timestamptz '2026-08-10 11:00:00-05'
FROM f0_lin l GROUP BY l.oc_seq
ON CONFLICT (id) DO NOTHING;

INSERT INTO factura_compra_detalle (id, factura_compra_id, producto_variante_id, cantidad,
                                    precio_unitario, monto_impuesto, fecha_creacion)
OVERRIDING SYSTEM VALUE
SELECT 900000000 + l.lin_seq, 900000000 + l.oc_seq, l.variante_id, l.cantidad,
       l.costo, round(l.cantidad * l.costo * 0.15, 2), timestamptz '2026-08-10 11:00:00-05'
FROM f0_lin l
ON CONFLICT (id) DO NOTHING;
COMMIT;

BEGIN;
INSERT INTO cuenta_por_pagar (id, factura_compra_id, proveedor_id, monto_original,
                              saldo_pendiente, fecha_vencimiento, estado, fecha_creacion)
OVERRIDING SYSTEM VALUE
SELECT 900000000 + fc.id - 900000000, fc.id, fc.proveedor_id, fc.total, fc.total,
       fc.fecha_vencimiento, 'pendiente', timestamptz '2026-08-10 11:30:00-05'
FROM factura_compra fc WHERE fc.id >= 900000000
ON CONFLICT (id) DO NOTHING;
COMMIT;

-- ── 5. producto_proveedor (el proveedor preferido de cada variante) ─────────
BEGIN;
INSERT INTO producto_proveedor (id, proveedor_id, producto_variante_id, codigo_proveedor,
                                costo, tiempo_entrega_dias, cantidad_minima, es_preferido,
                                activo, fecha_creacion)
OVERRIDING SYSTEM VALUE
SELECT 900000000 + l.n, l.proveedor_id, l.variante_id,
       'P' || lpad(l.n::text, 6, '0'), l.costo,
       3 + (l.n % 12), GREATEST(1, l.cantidad / 4), true, true,
       timestamptz '2026-08-09 12:00:00-05'
FROM f0_lin l WHERE l.bodega_id = 4
ON CONFLICT (id) DO NOTHING;
COMMIT;

-- ── 6. Bitacora ─────────────────────────────────────────────────────────────
BEGIN;
SELECT fn_carga_registrar('fase0','96_fase0_stock_inicial','orden_compra',
       900000001, 900000000 + (SELECT max(oc_seq) FROM f0_lin),
       (SELECT count(*) FROM orden_compra WHERE id >= 900000000), 'Apertura de catalogo, estado recibida.');
SELECT fn_carga_registrar('fase0','96_fase0_stock_inicial','orden_compra_detalle',
       900000001, 900010000, (SELECT count(*) FROM orden_compra_detalle WHERE id >= 900000000), NULL);
SELECT fn_carga_registrar('fase0','96_fase0_stock_inicial','recepcion_mercancia',
       900000001, 900000000 + (SELECT max(oc_seq) FROM f0_lin),
       (SELECT count(*) FROM recepcion_mercancia WHERE id >= 900000000), NULL);
SELECT fn_carga_registrar('fase0','96_fase0_stock_inicial','recepcion_detalle',
       900000001, 900010000, (SELECT count(*) FROM recepcion_detalle WHERE id >= 900000000), NULL);
SELECT fn_carga_registrar('fase0','96_fase0_stock_inicial','inventario',
       900000001, 900010000, (SELECT count(*) FROM inventario WHERE id >= 900000000),
       '5.000 variantes x 2 bodegas.');
SELECT fn_carga_registrar('fase0','96_fase0_stock_inicial','movimiento_inventario',
       900000001, 900010000, (SELECT count(*) FROM movimiento_inventario WHERE id >= 900000000),
       'entrada_compra, fecha explicita, cadena de longitud 1 desde 0.');
SELECT fn_carga_registrar('fase0','96_fase0_stock_inicial','factura_compra',
       900000001, 900000000 + (SELECT max(oc_seq) FROM f0_lin),
       (SELECT count(*) FROM factura_compra WHERE id >= 900000000), NULL);
SELECT fn_carga_registrar('fase0','96_fase0_stock_inicial','factura_compra_detalle',
       900000001, 900010000, (SELECT count(*) FROM factura_compra_detalle WHERE id >= 900000000), NULL);
SELECT fn_carga_registrar('fase0','96_fase0_stock_inicial','cuenta_por_pagar',
       900000001, 900000000 + (SELECT max(oc_seq) FROM f0_lin),
       (SELECT count(*) FROM cuenta_por_pagar WHERE id >= 900000000), 'Pendientes: ninguna ha vencido.');
SELECT fn_carga_registrar('fase0','96_fase0_stock_inicial','producto_proveedor',
       900000001, 900005000, (SELECT count(*) FROM producto_proveedor WHERE id >= 900000000), NULL);
COMMIT;

\echo ''
SELECT (SELECT count(*) FROM orden_compra WHERE id>=900000000) ordenes,
       (SELECT count(*) FROM orden_compra_detalle WHERE id>=900000000) lineas_oc,
       (SELECT count(*) FROM inventario WHERE id>=900000000) posiciones,
       (SELECT sum(stock_actual) FROM inventario WHERE id>=900000000) unidades,
       (SELECT round(sum(total),2) FROM factura_compra WHERE id>=900000000) facturado,
       (SELECT round(sum(saldo_pendiente),2) FROM cuenta_por_pagar WHERE id>=900000000) saldo_cxp;
