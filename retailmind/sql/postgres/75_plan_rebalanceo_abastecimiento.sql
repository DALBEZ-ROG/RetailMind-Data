-- ============================================================================
-- 75_plan_rebalanceo_abastecimiento.sql
-- REBALANCEO DEL ABASTECIMIENTO — FASE 1 (DISENO) + FASE 2 (FACTIBILIDAD).
-- NO ESCRIBE NI UNA FILA DE public: solo construye el plan en seed_backup y
-- demuestra que es temporalmente factible. Los scripts 76-78 lo aplican.
--
-- PROBLEMA (A1/M1/M2/B2 de docs/tactico/AUDITORIA_TRANSVERSAL_SEED.md):
--   120.160 uds (78,5 % de todo lo que entro al almacen en 18 meses) entraron
--   como 1.216 movimientos 'entrada_ajuste' / referencia_tipo='inventario_inicial'
--   el 2025-01-01, en un dia sin ninguna otra operacion. Las compras reales solo
--   aportaron 32.523 uds y 561 variantes vendidas nunca se compraron.
--
-- PRINCIPIO RECTOR (no negociable): el stock_actual de cada variante NO cambia.
--   Se recompone el ORIGEN de sus entradas: por cada unidad de apertura que se
--   convierte en compra, se resta de la apertura y se suma como entrada_compra
--   ANTES de la primera venta de esa variante. El balance por variante es
--   identico; cambian el tipo y la fecha de las entradas.
--
-- SEGMENTACION (decision de alcance):
--   * temprana   (343 var, 34.210 uds): primera salida de kardex ANTES de
--     2025-03-01. NO hay espacio temporal para una compra previa creible:
--     CONSERVAN toda su apertura. Es el inventario inicial legitimo.
--   * tardia     (487 var, 47.980 uds): primera salida >= 2025-03-01.
--     MIGRAN el 100 % a ordenes de compra recibidas antes de esa primera salida.
--   * sin_salida (386 var, 37.970 uds): nunca salieron del almacen.
--     MIGRAN el 100 %, con recepcion en cualquier punto de los 18 meses.
--   Resultado esperado: apertura 34.210 / 152.999 entradas = 22,4 % (banda 15-25 %).
--
-- FACTIBILIDAD TEMPORAL (Fase 2), demostrada al final de este script:
--   Sea A la apertura de la variante, d1 la unidad migrada al lote 1 (fecha T1)
--   y d2 la migrada al lote 2 (fecha T2 > T1), con d1 + d2 = A. El balance nuevo
--   B'(t) frente al original B(t) es:
--       t < T1        -> B'(t) = B(t) - A
--       T1 <= t < T2  -> B'(t) = B(t) - d2
--       t >= T2       -> B'(t) = B(t)
--   * T1 se elige SIEMPRE anterior a la primera salida de la variante, luego
--     en [t0,T1) el balance original es A + entradas previas >= A  =>  B' >= 0.
--   * En [T1,T2) basta con d2 <= min B(t) en ese tramo (columna max_seguro).
--   * Desde T2 el kardex es identico al original.
--   Por construccion NO existe ningun instante con stock negativo.
--
-- Ejecutar como postgres (superusuario). Requiere 74_respaldo_abastecimiento.sql.
-- ============================================================================
\set ON_ERROR_STOP on

BEGIN;

DO $$
BEGIN
    IF to_regclass('seed_backup.reb74_movimiento_inventario') IS NULL THEN
        RAISE EXCEPTION 'Falta el respaldo: ejecute antes 74_respaldo_abastecimiento.sql';
    END IF;
END $$;

-- ── 0. Parametros del plan ──────────────────────────────────────────────────
DROP TABLE IF EXISTS seed_backup.reb75_param;
CREATE TABLE seed_backup.reb75_param (clave text PRIMARY KEY, valor text NOT NULL);
INSERT INTO seed_backup.reb75_param VALUES
 ('corte_temprana', '2025-03-01'),   -- primera salida < corte => conserva apertura
 ('inicio_compras', '2025-01-06'),   -- primera recepcion posible del rebalanceo
 ('fin_compras',    '2026-07-20'),   -- ultima recepcion posible (hoy = 2026-07-23)
 ('margen_dias',    '7'),            -- dias minimos entre recepcion y primera salida
 ('bodega',         '4');            -- toda la apertura vive en Bodega Central Quevedo

-- ── 1. Proveedor por categoria (giro real de cada proveedor sembrado) ───────
DROP TABLE IF EXISTS seed_backup.reb75_prov_cat;
CREATE TABLE seed_backup.reb75_prov_cat (
    categoria_id bigint, proveedor_id bigint, lo int, hi int, lead_dias int);
INSERT INTO seed_backup.reb75_prov_cat VALUES
 (10, 12,  0, 99, 14),                                   -- Electronica  -> TecnoAndes
 ( 5, 13,  0, 59,  6), ( 5, 20, 60, 99,  7),             -- Abarrotes    -> El Costeno / Multimarca
 ( 7, 14,  0, 99,  9),                                   -- Belleza      -> BellaVida
 ( 4, 15,  0, 74, 11), ( 4,  2, 75, 89, 10), ( 4, 1, 90, 99, 12),  -- Calzado
 (12, 16,  0, 99, 13), ( 1, 16,  0, 99, 13), ( 3, 16, 0, 99, 13),  -- Ropa
 (11, 17,  0, 59,  8), (11, 20, 60, 99,  7),             -- Hogar        -> HogarPlus / Multimarca
 ( 2, 18,  0, 84, 10), ( 2,  1, 85, 99, 12),             -- Accesorios
 ( 9, 19,  0, 99,  9);                                   -- Deportes

-- ── 2. Variantes con apertura: medicion y segmentacion ──────────────────────
DROP TABLE IF EXISTS seed_backup.reb75_variante;
CREATE TABLE seed_backup.reb75_variante AS
WITH ap AS (
    SELECT mi.id AS apertura_mov_id, mi.producto_variante_id AS v, mi.cantidad AS apertura_qty,
           mi.costo_unitario AS costo_apertura
    FROM movimiento_inventario mi
    WHERE mi.referencia_tipo = 'inventario_inicial'
), sal AS (
    SELECT mi.producto_variante_id AS v, min(mi.fecha_creacion) AS primera_salida,
           sum(mi.cantidad) AS uds_salida
    FROM movimiento_inventario mi JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id
    WHERE tm.factor = -1 AND mi.bodega_id = 4
    GROUP BY 1
), otro AS (
    SELECT mi.producto_variante_id AS v, min(mi.fecha_creacion) AS primer_mov_no_apertura
    FROM movimiento_inventario mi
    WHERE mi.bodega_id = 4 AND mi.referencia_tipo IS DISTINCT FROM 'inventario_inicial'
    GROUP BY 1
), comp AS (
    SELECT d.producto_variante_id AS v, sum(d.cantidad_recibida) AS uds_compradas
    FROM orden_compra_detalle d WHERE d.cantidad_recibida > 0 GROUP BY 1
)
SELECT ap.v                                        AS variante_id,
       ap.apertura_mov_id,
       ap.apertura_qty,
       ap.costo_apertura,
       pv.costo                                    AS costo_vigente,
       COALESCE(inv.stock_actual, 0)               AS stock_actual,
       sal.primera_salida,
       COALESCE(sal.uds_salida, 0)                 AS uds_salida,
       COALESCE(comp.uds_compradas, 0)             AS uds_compradas,
       otro.primer_mov_no_apertura,
       (SELECT pc.categoria_id FROM producto_categoria pc
         WHERE pc.producto_id = pv.producto_id
         ORDER BY (pc.categoria_id IN (2,4,5,7,9,10,11,12)) DESC, pc.categoria_id
         LIMIT 1)                                  AS categoria_id,
       CASE WHEN sal.primera_salida IS NULL                          THEN 'sin_salida'
            WHEN sal.primera_salida <  timestamptz '2025-03-01 00:00-05' THEN 'temprana'
            ELSE 'tardia' END                      AS grupo
FROM ap
JOIN producto_variante pv ON pv.id = ap.v
LEFT JOIN inventario inv ON inv.producto_variante_id = ap.v AND inv.bodega_id = 4
LEFT JOIN sal  ON sal.v  = ap.v
LEFT JOIN otro ON otro.v = ap.v
LEFT JOIN comp ON comp.v = ap.v;
ALTER TABLE seed_backup.reb75_variante ADD PRIMARY KEY (variante_id);

-- proveedor asignado: se respeta el que YA ofrece el producto; si no hay, se
-- elige por giro de categoria con reparto determinista (hash de la variante).
ALTER TABLE seed_backup.reb75_variante
    ADD COLUMN proveedor_id bigint,
    ADD COLUMN lead_dias    int,
    ADD COLUMN pp_existente boolean NOT NULL DEFAULT false;

UPDATE seed_backup.reb75_variante t
   SET proveedor_id = (SELECT p.proveedor_id FROM producto_proveedor p
                        WHERE p.producto_variante_id = t.variante_id AND p.activo
                        ORDER BY p.es_preferido DESC, p.id LIMIT 1),
       pp_existente = EXISTS (SELECT 1 FROM producto_proveedor p
                        WHERE p.producto_variante_id = t.variante_id AND p.activo);

UPDATE seed_backup.reb75_variante t
   SET proveedor_id = pc.proveedor_id
FROM seed_backup.reb75_prov_cat pc
WHERE t.proveedor_id IS NULL
  AND pc.categoria_id = t.categoria_id
  AND (abs(hashtext('prov#' || t.variante_id::text)) % 100) BETWEEN pc.lo AND pc.hi;

-- fallback: variante sin categoria mapeada -> multimarca del Litoral
UPDATE seed_backup.reb75_variante SET proveedor_id = 20 WHERE proveedor_id IS NULL;

UPDATE seed_backup.reb75_variante t
   SET lead_dias = COALESCE((SELECT max(pc.lead_dias) FROM seed_backup.reb75_prov_cat pc
                             WHERE pc.proveedor_id = t.proveedor_id), 10);

-- ── 3. Lineas del plan: fechas candidatas y reparto en lotes ───────────────
-- lote 1: siempre ANTES de la primera salida de la variante.
-- lote 2 (solo si apertura >= 40): mas adelante en el historico, para que el
--   abastecimiento no se amontone en el arranque. Su cantidad se recorta
--   despues con la prueba de factibilidad (max_seguro).
DROP TABLE IF EXISTS seed_backup.reb75_linea;
CREATE TABLE seed_backup.reb75_linea (
    variante_id   bigint  NOT NULL,
    lote          int     NOT NULL,
    proveedor_id  bigint  NOT NULL,
    lead_dias     int     NOT NULL,
    fecha_cand    date    NOT NULL,   -- fecha de recepcion candidata
    fecha_tope    date,               -- deadline duro (primera salida - margen)
    cantidad      int,                -- se calcula tras fijar las fechas
    precio        numeric(14,2),
    oc_key        text,
    fecha_recep   date,               -- fecha final (la de su orden)
    PRIMARY KEY (variante_id, lote));

-- lote 1
INSERT INTO seed_backup.reb75_linea (variante_id, lote, proveedor_id, lead_dias, fecha_cand, fecha_tope)
SELECT t.variante_id, 1, t.proveedor_id, t.lead_dias,
       CASE WHEN t.grupo = 'tardia' THEN
              greatest(date '2025-01-06',
                       least((t.primera_salida AT TIME ZONE 'America/Guayaquil')::date - 7
                               - (5 + abs(hashtext('f1#' || t.variante_id::text)) % 70),
                            (t.primera_salida AT TIME ZONE 'America/Guayaquil')::date - 7))
            ELSE
              date '2025-01-06' + (abs(hashtext('f1#' || t.variante_id::text)) % 550)
       END,
       CASE WHEN t.grupo = 'tardia'
            THEN (t.primera_salida AT TIME ZONE 'America/Guayaquil')::date - 7 END
FROM seed_backup.reb75_variante t
WHERE t.grupo IN ('tardia','sin_salida');

-- lote 2 (>= 60 dias despues del lote 1 => siempre en un mes posterior)
INSERT INTO seed_backup.reb75_linea (variante_id, lote, proveedor_id, lead_dias, fecha_cand, fecha_tope)
SELECT l.variante_id, 2, l.proveedor_id, l.lead_dias,
       l.fecha_cand + 60 + (abs(hashtext('f2#' || l.variante_id::text)) % 300),
       NULL
FROM seed_backup.reb75_linea l
JOIN seed_backup.reb75_variante t ON t.variante_id = l.variante_id
WHERE l.lote = 1
  AND t.apertura_qty >= 40
  AND l.fecha_cand + 60 + (abs(hashtext('f2#' || l.variante_id::text)) % 300) <= date '2026-07-20';

-- saneo de fechas candidatas dentro de la ventana operativa
UPDATE seed_backup.reb75_linea
   SET fecha_cand = least(greatest(fecha_cand, date '2025-01-06'), date '2026-07-20');

-- ── 4. Agrupacion en ordenes de compra ─────────────────────────────────────
-- Clave = (proveedor, mes de la recepcion candidata). Dentro de cada grupo las
-- lineas se ordenan por fecha y se parten en tandas de 2..6 lineas; la fecha de
-- recepcion de la orden es la MENOR de su tanda, de modo que NINGUNA linea puede
-- llegar despues de su deadline (solo puede llegar antes, que siempre es seguro).
WITH num AS (
    SELECT variante_id, lote, proveedor_id,
           to_char(fecha_cand,'YYYY-MM') AS ym,
           row_number() OVER (PARTITION BY proveedor_id, to_char(fecha_cand,'YYYY-MM')
                              ORDER BY fecha_cand, variante_id, lote) AS rn
    FROM seed_backup.reb75_linea
), k AS (
    SELECT DISTINCT proveedor_id, ym,
           2 + abs(hashtext('k#' || proveedor_id::text || ym)) % 5 AS tam
    FROM num
)
UPDATE seed_backup.reb75_linea l
   SET oc_key = num.proveedor_id::text || '|' || num.ym || '|'
                || ceil(num.rn::numeric / k.tam)::int::text
FROM num JOIN k ON k.proveedor_id = num.proveedor_id AND k.ym = num.ym
WHERE num.variante_id = l.variante_id AND num.lote = l.lote;

DROP TABLE IF EXISTS seed_backup.reb75_oc;
CREATE TABLE seed_backup.reb75_oc AS
SELECT l.oc_key,
       l.proveedor_id,
       min(l.fecha_cand)  AS fecha_recep,
       max(l.lead_dias)   AS lead_dias,
       count(*)           AS n_lineas
FROM seed_backup.reb75_linea l
GROUP BY 1,2;
ALTER TABLE seed_backup.reb75_oc ADD PRIMARY KEY (oc_key);

ALTER TABLE seed_backup.reb75_oc
    ADD COLUMN fecha_emision date,
    ADD COLUMN fecha_esperada date,
    ADD COLUMN estado text,
    ADD COLUMN con_rechazo boolean NOT NULL DEFAULT false,
    ADD COLUMN orden_compra_id bigint,
    ADD COLUMN recepcion_id bigint,
    ADD COLUMN factura_compra_id bigint,
    ADD COLUMN cuenta_por_pagar_id bigint;

UPDATE seed_backup.reb75_oc
   SET fecha_emision = greatest(date '2025-01-02',
                                fecha_recep - lead_dias
                                            - (abs(hashtext('em#' || oc_key)) % 4));
UPDATE seed_backup.reb75_oc
   SET fecha_emision  = least(fecha_emision, fecha_recep - 1),
       fecha_esperada = fecha_recep - 2 + (abs(hashtext('es#' || oc_key)) % 6);
UPDATE seed_backup.reb75_oc
   SET fecha_esperada = greatest(fecha_esperada, fecha_emision + 1);

-- 8 % de las ordenes se reciben parcialmente y 8 % traen unidades rechazadas en
-- puerta: en ambos casos la cantidad PEDIDA sube, la RECIBIDA (la que entra al
-- stock) es exactamente la unidad migrada.
UPDATE seed_backup.reb75_oc
   SET estado      = CASE WHEN abs(hashtext('st#' || oc_key)) % 100 < 8
                          THEN 'recibida_parcial' ELSE 'recibida' END,
       con_rechazo = (abs(hashtext('rz#' || oc_key)) % 100) < 8;

UPDATE seed_backup.reb75_linea l
   SET fecha_recep = o.fecha_recep
FROM seed_backup.reb75_oc o WHERE o.oc_key = l.oc_key;

-- ── 5. Cantidades: reparto entre lote 1 y lote 2 con la prueba de factibilidad
-- max_seguro(v) = minimo balance ORIGINAL de la variante en [T1, T2). El lote 2
-- no puede llevarse mas que eso o el stock quedaria negativo en ese tramo.
DROP TABLE IF EXISTS seed_backup.reb75_factibilidad;
CREATE TABLE seed_backup.reb75_factibilidad AS
WITH pares AS (
    SELECT l1.variante_id,
           t.apertura_qty,
           (l1.fecha_recep::timestamptz + time '11:05') AS t1,
           (l2.fecha_recep::timestamptz + time '11:05') AS t2
    FROM seed_backup.reb75_linea l1
    JOIN seed_backup.reb75_linea l2
      ON l2.variante_id = l1.variante_id AND l2.lote = 2
    JOIN seed_backup.reb75_variante t ON t.variante_id = l1.variante_id
    WHERE l1.lote = 1
), bal AS (
    SELECT p.variante_id, p.apertura_qty, p.t1, p.t2,
           -- balance justo en T1 (ultimo movimiento original <= T1)
           COALESCE((SELECT b.stock_nuevo FROM seed_backup.reb74_movimiento_inventario b
                      WHERE b.producto_variante_id = p.variante_id AND b.bodega_id = 4
                        AND b.fecha_creacion <= p.t1
                      ORDER BY b.fecha_creacion DESC, b.id DESC LIMIT 1), 0) AS bal_t1,
           -- minimo balance en (T1, T2)
           (SELECT min(b.stock_nuevo) FROM seed_backup.reb74_movimiento_inventario b
             WHERE b.producto_variante_id = p.variante_id AND b.bodega_id = 4
               AND b.fecha_creacion > p.t1 AND b.fecha_creacion < p.t2) AS min_tramo
    FROM pares p
)
SELECT variante_id, apertura_qty, t1, t2, bal_t1, min_tramo,
       least(bal_t1, COALESCE(min_tramo, bal_t1)) AS max_seguro
FROM bal;
ALTER TABLE seed_backup.reb75_factibilidad ADD PRIMARY KEY (variante_id);

-- deseado: 30 %..49 % de la apertura al lote 2; recortado por max_seguro.
ALTER TABLE seed_backup.reb75_factibilidad
    ADD COLUMN deseado int, ADD COLUMN asignado int, ADD COLUMN recortado boolean;

UPDATE seed_backup.reb75_factibilidad f
   SET deseado = greatest(1, floor(f.apertura_qty
                 * (30 + abs(hashtext('d2#' || f.variante_id::text)) % 20) / 100.0)::int);
UPDATE seed_backup.reb75_factibilidad
   SET asignado  = CASE WHEN least(deseado, max_seguro) < 5 THEN 0
                        ELSE least(deseado, max_seguro) END,
       recortado = (max_seguro < deseado);

-- cantidad definitiva por linea
UPDATE seed_backup.reb75_linea l
   SET cantidad = t.apertura_qty
FROM seed_backup.reb75_variante t
WHERE t.variante_id = l.variante_id AND l.lote = 1;

UPDATE seed_backup.reb75_linea l
   SET cantidad = f.asignado
FROM seed_backup.reb75_factibilidad f
WHERE f.variante_id = l.variante_id AND l.lote = 2;

UPDATE seed_backup.reb75_linea l
   SET cantidad = l.cantidad - f.asignado
FROM seed_backup.reb75_factibilidad f
WHERE f.variante_id = l.variante_id AND l.lote = 1;

-- lineas vacias fuera (CHECK cantidad > 0)
DELETE FROM seed_backup.reb75_linea WHERE cantidad IS NULL OR cantidad <= 0;

-- ── 6. Precio de compra: costo vigente + tendencia inflacionaria + ruido ────
UPDATE seed_backup.reb75_linea l
   SET precio = greatest(0.01, round((t.costo_vigente
                 * power(1.006, (extract(year from l.fecha_recep) - 2025) * 12
                                + extract(month from l.fecha_recep) - 1)
                 * (0.970 + (abs(hashtext('pr#' || l.variante_id::text || l.lote::text)) % 60) / 1000.0)
                 )::numeric, 2))
FROM seed_backup.reb75_variante t WHERE t.variante_id = l.variante_id;

-- ── 7. Ordenes que se quedaron sin lineas ──────────────────────────────────
DELETE FROM seed_backup.reb75_oc o
 WHERE NOT EXISTS (SELECT 1 FROM seed_backup.reb75_linea l WHERE l.oc_key = o.oc_key);

UPDATE seed_backup.reb75_oc o
   SET n_lineas = (SELECT count(*) FROM seed_backup.reb75_linea l WHERE l.oc_key = o.oc_key);

-- ── 8. FASE 2 — VERIFICACION DE FACTIBILIDAD (aborta si algo no cuadra) ─────
DO $$
DECLARE
    v_bad bigint; v_txt text;
BEGIN
    -- 8.1 la suma migrada por variante es EXACTAMENTE su apertura
    SELECT count(*) INTO v_bad FROM (
        SELECT t.variante_id FROM seed_backup.reb75_variante t
        JOIN seed_backup.reb75_linea l ON l.variante_id = t.variante_id
        WHERE t.grupo IN ('tardia','sin_salida')
        GROUP BY t.variante_id, t.apertura_qty
        HAVING sum(l.cantidad) <> t.apertura_qty) x;
    IF v_bad > 0 THEN RAISE EXCEPTION 'FACTIBILIDAD: % variantes migran una cantidad distinta de su apertura', v_bad; END IF;

    -- 8.2 ninguna variante 'temprana' aparece en el plan
    SELECT count(*) INTO v_bad FROM seed_backup.reb75_linea l
    JOIN seed_backup.reb75_variante t ON t.variante_id = l.variante_id
    WHERE t.grupo = 'temprana';
    IF v_bad > 0 THEN RAISE EXCEPTION 'FACTIBILIDAD: % lineas sobre variantes de venta temprana', v_bad; END IF;

    -- 8.3 el lote 1 llega SIEMPRE antes de la primera salida (con margen)
    SELECT count(*) INTO v_bad FROM seed_backup.reb75_linea l
    JOIN seed_backup.reb75_variante t ON t.variante_id = l.variante_id
    WHERE l.lote = 1 AND t.primera_salida IS NOT NULL
      AND (l.fecha_recep::timestamptz + time '11:05') >= t.primera_salida;
    IF v_bad > 0 THEN RAISE EXCEPTION 'FACTIBILIDAD: % lotes 1 llegan despues de la primera salida', v_bad; END IF;

    -- 8.4 el lote 2 llega siempre despues del lote 1
    SELECT count(*) INTO v_bad FROM seed_backup.reb75_linea l1
    JOIN seed_backup.reb75_linea l2 ON l2.variante_id = l1.variante_id AND l2.lote = 2
    WHERE l1.lote = 1 AND l2.fecha_recep <= l1.fecha_recep;
    IF v_bad > 0 THEN RAISE EXCEPTION 'FACTIBILIDAD: % lotes 2 no son posteriores al lote 1', v_bad; END IF;

    -- 8.5 el lote 2 nunca excede el minimo balance del tramo (stock negativo)
    SELECT count(*) INTO v_bad FROM seed_backup.reb75_linea l
    JOIN seed_backup.reb75_factibilidad f ON f.variante_id = l.variante_id
    WHERE l.lote = 2 AND l.cantidad > f.max_seguro;
    IF v_bad > 0 THEN RAISE EXCEPTION 'FACTIBILIDAD: % lotes 2 exceden el balance disponible del tramo', v_bad; END IF;

    -- 8.6 fechas dentro de la ventana operativa
    SELECT count(*) INTO v_bad FROM seed_backup.reb75_oc
    WHERE fecha_recep < date '2025-01-02' OR fecha_recep > date '2026-07-20'
       OR fecha_emision >= fecha_recep OR fecha_emision < date '2025-01-02';
    IF v_bad > 0 THEN RAISE EXCEPTION 'FACTIBILIDAD: % ordenes con fechas fuera de ventana', v_bad; END IF;

    -- 8.7 todas las lineas tienen orden, cantidad y precio positivos
    SELECT count(*) INTO v_bad FROM seed_backup.reb75_linea
    WHERE oc_key IS NULL OR fecha_recep IS NULL OR cantidad <= 0 OR precio IS NULL OR precio <= 0;
    IF v_bad > 0 THEN RAISE EXCEPTION 'FACTIBILIDAD: % lineas incompletas', v_bad; END IF;

    -- 8.8 proveedor coherente: ofrece esa categoria (o ya ofrecia el producto)
    SELECT count(*) INTO v_bad FROM seed_backup.reb75_variante t
    JOIN seed_backup.reb75_linea l ON l.variante_id = t.variante_id
    WHERE NOT t.pp_existente
      AND NOT EXISTS (SELECT 1 FROM seed_backup.reb75_prov_cat pc
                      WHERE pc.categoria_id = t.categoria_id AND pc.proveedor_id = t.proveedor_id);
    IF v_bad > 0 THEN RAISE EXCEPTION 'FACTIBILIDAD: % lineas con proveedor ajeno al giro', v_bad; END IF;

    SELECT 'ordenes=' || (SELECT count(*) FROM seed_backup.reb75_oc)
        || ' lineas=' || (SELECT count(*) FROM seed_backup.reb75_linea)
        || ' uds=' || (SELECT sum(cantidad) FROM seed_backup.reb75_linea)
      INTO v_txt;
    RAISE NOTICE 'Plan 75 FACTIBLE. %', v_txt;
END $$;

INSERT INTO configuracion_tienda (clave, valor, tipo_dato, descripcion, fecha_creacion)
SELECT 'seed_reb75_plan',
       jsonb_build_object('ordenes', (SELECT count(*) FROM seed_backup.reb75_oc),
                          'lineas',  (SELECT count(*) FROM seed_backup.reb75_linea),
                          'unidades',(SELECT sum(cantidad) FROM seed_backup.reb75_linea))::text,
       'json', 'Rebalanceo abastecimiento/75: plan calculado en seed_backup (no escribe public).', now()
WHERE NOT EXISTS (SELECT 1 FROM configuracion_tienda WHERE clave = 'seed_reb75_plan');

COMMIT;

\echo '=== FASE 1 · Segmentacion de la apertura ==='
SELECT t.grupo, count(*) AS variantes, sum(t.apertura_qty) AS uds_apertura,
       round(sum(t.apertura_qty * t.costo_vigente),2) AS valor_costo,
       sum(t.uds_salida) AS uds_vendidas
FROM seed_backup.reb75_variante t GROUP BY 1 ORDER BY 3 DESC;

\echo '=== FASE 1 · Ordenes de compra nuevas por trimestre ==='
SELECT to_char(date_trunc('quarter', l.fecha_recep),'YYYY"Q"Q') AS trimestre,
       count(DISTINCT l.oc_key) AS ordenes, count(*) AS lineas,
       sum(l.cantidad) AS uds, round(sum(l.cantidad * l.precio),2) AS neto
FROM seed_backup.reb75_linea l GROUP BY 1 ORDER BY 1;

\echo '=== FASE 1 · Reparto por proveedor ==='
SELECT l.proveedor_id, p.razon_social, count(DISTINCT l.oc_key) AS ordenes,
       count(*) AS lineas, sum(l.cantidad) AS uds
FROM seed_backup.reb75_linea l JOIN proveedor p ON p.id = l.proveedor_id
GROUP BY 1,2 ORDER BY 5 DESC;

\echo '=== FASE 2 · Factibilidad del lote 2 (recortes por stock disponible) ==='
SELECT count(*) AS variantes_con_lote2,
       count(*) FILTER (WHERE recortado)          AS recortadas_por_balance,
       count(*) FILTER (WHERE asignado = 0)       AS lote2_anulado,
       min(max_seguro) AS min_balance_disponible,
       sum(asignado)   AS uds_al_lote2
FROM seed_backup.reb75_factibilidad;

\echo '=== FASE 1 · Proporcion de abastecimiento resultante ==='
WITH ent AS (SELECT (SELECT valor FROM seed_backup.reb74_agregados WHERE metrica='uds_entradas_todas') AS total,
                    (SELECT valor FROM seed_backup.reb74_agregados WHERE metrica='uds_apertura')      AS ap0,
                    (SELECT sum(cantidad) FROM seed_backup.reb75_linea)::numeric                      AS migra)
SELECT total AS uds_entradas_totales, ap0 AS apertura_antes,
       round(100*ap0/total,2) AS pct_apertura_antes,
       migra AS uds_migradas, ap0 - migra AS apertura_despues,
       round(100*(ap0-migra)/total,2) AS pct_apertura_despues,
       round(100*(total-(ap0-migra))/total,2) AS pct_compras_despues
FROM ent;
