-- ============================================================================
-- 107_reparacion_encadenamiento_kardex.sql — RetailMind (2026-08-12)
--
-- Repara el ENCADENAMIENTO del kardex de las posiciones cuya cadena quedo
-- rota, y SOLO de esas. No toca `inventario.stock_actual` de ninguna posicion.
--
--   docker compose exec -T postgres psql -U postgres -d retailmind \
--       -v ON_ERROR_STOP=1 < retailmind/sql/postgres/107_reparacion_encadenamiento_kardex.sql
--
-- ---------------------------------------------------------------------------
-- QUE SE ROMPIO
-- ---------------------------------------------------------------------------
-- El kardex es una cadena: leyendo cada posicion (variante, bodega) por
-- (fecha_creacion, id), el `stock_anterior` de cada fila tiene que ser el
-- `stock_nuevo` de la anterior, y la cadena arranca en 0.
--
-- La carga de la decada dejo movimientos sembrados hasta 2035-01. Por eso la
-- FECHA REAL DE HOY cae EN MITAD de la cadena de cualquier posicion. Cuando la
-- aplicacion escribio la transferencia 75 (2026-08-12 09:22:59, variante
-- 900001108, 1 unidad de la bodega 4 a la 3), `StockService` tomo el
-- `stock_anterior` de `inventario.stock_actual` — que es el saldo al FINAL de
-- la cadena (97) y no el saldo corrido en esa fecha (58) — y ademas dejo
-- obsoleto el `stock_anterior` de TODO lo posterior de esa posicion.
--
-- `trg_kardex_ecuacion_ins` no lo vio porque valida la FILA
-- (stock_nuevo = stock_anterior + factor*cantidad, que si se cumplia), no el
-- ENLACE con la fila anterior. El ETL si lo vio y NO publico
-- `fact_movimiento_inventario`: hizo lo correcto.
--
-- Dano medido antes de reparar: 3 enlaces rotos en 2 posiciones
-- (900001108/bodega 3 y 900001108/bodega 4), y ningun otro en los 8.008.384
-- movimientos.
--
-- ---------------------------------------------------------------------------
-- COMO SE REPARA
-- ---------------------------------------------------------------------------
-- Se RECALCULA el saldo corrido de las posiciones afectadas como suma
-- acumulada de `tipo_movimiento.factor * cantidad` en el orden
-- (fecha_creacion, id), y se reescriben `stock_anterior` / `stock_nuevo`.
-- No se recalcula desde el punto de insercion sino desde el arranque de la
-- cadena: es el mismo resultado (la cadena arranca en 0 y es continua antes
-- del punto), cuesta lo mismo a esta escala y hace el script IDEMPOTENTE —
-- recalcular una cadena ya sana no cambia una sola fila.
--
-- NO se reescriben los 8 millones de movimientos: solo las posiciones con al
-- menos un enlace roto. Hoy son 2 (2.352 movimientos, de los que cambian
-- 1.974).
--
-- ---------------------------------------------------------------------------
-- POR QUE ESTO NO MUEVE EL TOTAL
-- ---------------------------------------------------------------------------
-- `inventario.stock_actual` es, por construccion del sistema, el saldo al
-- final de la cadena. La suma `factor*cantidad` de una posicion ya coincide
-- con su `stock_actual` (verificado: diferencia 0 en las dos posiciones
-- afectadas) porque el movimiento de la aplicacion SI actualizo el total.
-- Lo unico que estaba mal era el saldo corrido INTERMEDIO. El script
-- COMPRUEBA esa igualdad ANTES de escribir y ABORTA si no se cumple: si el
-- total estuviera mal, esta reparacion no es la que corresponde.
--
-- Guardas internas (cualquiera de ellas aborta la transaccion entera):
--   G1  la suma factor*cantidad de cada posicion afectada = stock_actual
--   G2  el recalculo no deja ningun saldo negativo en ningun instante
--   G3  tras escribir, cero enlaces rotos en TODA la tabla
--   G4  tras escribir, el ultimo stock_nuevo de cada posicion = stock_actual
--   G5  `inventario` intacto: mismas posiciones y mismas unidades
--   G6  ni un movimiento creado ni borrado
--
-- Respaldo para la reversion: `seed_backup.rep107_movimiento_inventario`
-- (filas COMPLETAS de las posiciones tocadas). Reversion:
-- `99_revert_reparacion_kardex.sql`.
-- ============================================================================

\timing on
\set ON_ERROR_STOP on

BEGIN;

SET LOCAL work_mem = '512MB';

-- ---------------------------------------------------------------------------
-- 0. Linea base, para las guardas G5/G6
-- ---------------------------------------------------------------------------
CREATE TEMP TABLE rep107_base ON COMMIT DROP AS
SELECT (SELECT count(*)           FROM movimiento_inventario) AS movs,
       (SELECT count(*)           FROM inventario)            AS posiciones,
       (SELECT sum(stock_actual)  FROM inventario)            AS unidades;

-- ---------------------------------------------------------------------------
-- 1. Localizar las posiciones con la cadena rota (barrido completo)
-- ---------------------------------------------------------------------------
CREATE TEMP TABLE rep107_roturas ON COMMIT DROP AS
WITH enc AS (
    SELECT id, producto_variante_id, bodega_id, fecha_creacion,
           stock_anterior,
           lag(stock_nuevo) OVER w AS prev_stock_nuevo,
           lag(id)          OVER w AS prev_id,
           row_number()     OVER w AS rn
    FROM movimiento_inventario
    WINDOW w AS (PARTITION BY producto_variante_id, bodega_id
                 ORDER BY fecha_creacion, id)
)
SELECT id, producto_variante_id, bodega_id, fecha_creacion,
       stock_anterior, prev_stock_nuevo, prev_id, rn
FROM enc
WHERE (rn > 1 AND stock_anterior <> prev_stock_nuevo)   -- enlace roto
   OR (rn = 1 AND stock_anterior <> 0);                 -- cadena que no arranca en 0

CREATE TEMP TABLE rep107_posiciones ON COMMIT DROP AS
SELECT DISTINCT producto_variante_id, bodega_id FROM rep107_roturas;

CREATE UNIQUE INDEX ON rep107_posiciones (producto_variante_id, bodega_id);
ANALYZE rep107_posiciones;

\echo ''
\echo '--- enlaces rotos encontrados ---'
SELECT id, producto_variante_id AS variante, bodega_id AS bodega, fecha_creacion,
       stock_anterior, prev_stock_nuevo,
       stock_anterior - prev_stock_nuevo AS delta, prev_id
FROM rep107_roturas
ORDER BY producto_variante_id, bodega_id, fecha_creacion, id;

\echo '--- posiciones a reparar ---'
SELECT count(*) AS posiciones_a_reparar FROM rep107_posiciones;

-- ---------------------------------------------------------------------------
-- 2. G1 — el TOTAL de cada posicion afectada ya es correcto
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_malas integer;
    v_txt   text;
BEGIN
    SELECT count(*), string_agg(format('variante %s / bodega %s: suma %s vs stock_actual %s',
                                       t.producto_variante_id, t.bodega_id, t.suma, t.stock_actual),
                                E'\n  ')
      INTO v_malas, v_txt
    FROM (
        SELECT p.producto_variante_id, p.bodega_id,
               sum(tm.factor * m.cantidad) AS suma,
               i.stock_actual
        FROM rep107_posiciones p
        JOIN movimiento_inventario m ON m.producto_variante_id = p.producto_variante_id
                                    AND m.bodega_id            = p.bodega_id
        JOIN tipo_movimiento tm      ON tm.id = m.tipo_movimiento_id
        JOIN inventario i            ON i.producto_variante_id = p.producto_variante_id
                                    AND i.bodega_id            = p.bodega_id
        GROUP BY 1, 2, i.stock_actual
        HAVING sum(tm.factor * m.cantidad) <> i.stock_actual
    ) t;

    IF v_malas > 0 THEN
        RAISE EXCEPTION
            'G1: % posicion(es) afectada(s) NO cuadran su total. Esta reparacion solo arregla el ENCADENAMIENTO, no el total; el descuadre es otro problema y hay que verlo antes.%  %',
            v_malas, E'\n  ', v_txt;
    END IF;
    RAISE NOTICE 'G1 OK: el total de cada posicion afectada ya coincide con inventario.stock_actual.';
END $$;

-- ---------------------------------------------------------------------------
-- 3. Recalculo propuesto (aun sin escribir) + G2 (nada negativo)
-- ---------------------------------------------------------------------------
CREATE TEMP TABLE rep107_recalculo ON COMMIT DROP AS
SELECT m.id,
       m.producto_variante_id,
       m.bodega_id,
       m.fecha_creacion,
       m.stock_anterior AS ant_viejo,
       m.stock_nuevo    AS nue_viejo,
       (sum(tm.factor * m.cantidad) OVER w) - tm.factor * m.cantidad AS ant_nuevo,
       (sum(tm.factor * m.cantidad) OVER w)                          AS nue_nuevo
FROM movimiento_inventario m
JOIN tipo_movimiento tm ON tm.id = m.tipo_movimiento_id
JOIN rep107_posiciones p ON p.producto_variante_id = m.producto_variante_id
                        AND p.bodega_id            = m.bodega_id
WINDOW w AS (PARTITION BY m.producto_variante_id, m.bodega_id
             ORDER BY m.fecha_creacion, m.id
             ROWS UNBOUNDED PRECEDING);

CREATE UNIQUE INDEX ON rep107_recalculo (id);
ANALYZE rep107_recalculo;

DO $$
DECLARE v_neg integer;
BEGIN
    SELECT count(*) INTO v_neg FROM rep107_recalculo WHERE ant_nuevo < 0 OR nue_nuevo < 0;
    IF v_neg > 0 THEN
        RAISE EXCEPTION
            'G2: el recalculo dejaria % movimiento(s) con saldo negativo. Se aborta: la cadena no admite esta reparacion tal cual.', v_neg;
    END IF;
    RAISE NOTICE 'G2 OK: el recalculo no deja ningun saldo negativo.';
END $$;

\echo '--- alcance del recalculo ---'
SELECT producto_variante_id AS variante, bodega_id AS bodega,
       count(*) AS movs_en_la_posicion,
       count(*) FILTER (WHERE ant_viejo <> ant_nuevo OR nue_viejo <> nue_nuevo) AS movs_a_reescribir
FROM rep107_recalculo
GROUP BY 1, 2 ORDER BY 1, 2;

-- ---------------------------------------------------------------------------
-- 4. Respaldo de las filas COMPLETAS de las posiciones tocadas
-- ---------------------------------------------------------------------------
CREATE SCHEMA IF NOT EXISTS seed_backup;

CREATE TABLE IF NOT EXISTS seed_backup.rep107_movimiento_inventario
    (LIKE public.movimiento_inventario);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_indexes
                   WHERE schemaname = 'seed_backup'
                     AND tablename  = 'rep107_movimiento_inventario'
                     AND indexname  = 'rep107_movimiento_inventario_pkey') THEN
        ALTER TABLE seed_backup.rep107_movimiento_inventario
            ADD CONSTRAINT rep107_movimiento_inventario_pkey PRIMARY KEY (id);
    END IF;
END $$;

-- Solo se respalda lo que aun no este respaldado: en una segunda corrida el
-- respaldo conserva el estado ORIGINAL y no el ya reparado.
INSERT INTO seed_backup.rep107_movimiento_inventario
SELECT m.*
FROM public.movimiento_inventario m
JOIN rep107_posiciones p ON p.producto_variante_id = m.producto_variante_id
                        AND p.bodega_id            = m.bodega_id
WHERE NOT EXISTS (SELECT 1 FROM seed_backup.rep107_movimiento_inventario b
                  WHERE b.id = m.id);

\echo '--- respaldo ---'
SELECT count(*) AS filas_respaldadas FROM seed_backup.rep107_movimiento_inventario;

-- ---------------------------------------------------------------------------
-- 5. Escribir el recalculo
-- ---------------------------------------------------------------------------
UPDATE public.movimiento_inventario m
SET stock_anterior = r.ant_nuevo,
    stock_nuevo    = r.nue_nuevo
FROM rep107_recalculo r
WHERE m.id = r.id
  AND (m.stock_anterior <> r.ant_nuevo OR m.stock_nuevo <> r.nue_nuevo);

-- ---------------------------------------------------------------------------
-- 6. G3 a G6 — verificacion interna
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_rotos integer;
    v_desc  integer;
    v_movs  bigint;
    v_pos   bigint;
    v_uds   bigint;
    b       record;
BEGIN
    -- G3: cero enlaces rotos en TODA la tabla
    SELECT count(*) INTO v_rotos
    FROM (
        SELECT stock_anterior,
               lag(stock_nuevo) OVER w AS prev_nuevo,
               row_number()     OVER w AS rn
        FROM public.movimiento_inventario
        WINDOW w AS (PARTITION BY producto_variante_id, bodega_id
                     ORDER BY fecha_creacion, id)
    ) e
    WHERE (rn > 1 AND stock_anterior <> prev_nuevo)
       OR (rn = 1 AND stock_anterior <> 0);

    IF v_rotos > 0 THEN
        RAISE EXCEPTION 'G3: siguen habiendo % enlace(s) roto(s) despues de reparar.', v_rotos;
    END IF;
    RAISE NOTICE 'G3 OK: cero enlaces rotos en toda la tabla.';

    -- G4: el ultimo stock_nuevo de cada posicion = inventario.stock_actual
    SELECT count(*) INTO v_desc
    FROM (
        SELECT DISTINCT ON (m.producto_variante_id, m.bodega_id)
               m.producto_variante_id, m.bodega_id, m.stock_nuevo
        FROM public.movimiento_inventario m
        ORDER BY m.producto_variante_id, m.bodega_id, m.fecha_creacion DESC, m.id DESC
    ) u
    JOIN inventario i ON i.producto_variante_id = u.producto_variante_id
                     AND i.bodega_id            = u.bodega_id
    WHERE u.stock_nuevo <> i.stock_actual;

    IF v_desc > 0 THEN
        RAISE EXCEPTION 'G4: % posicion(es) cierran la cadena en un saldo distinto de inventario.stock_actual.', v_desc;
    END IF;
    RAISE NOTICE 'G4 OK: cada posicion cierra su cadena en su stock_actual.';

    -- G5/G6: inventario y censo de movimientos intactos
    SELECT count(*) INTO v_movs FROM public.movimiento_inventario;
    SELECT count(*), sum(stock_actual) INTO v_pos, v_uds FROM inventario;
    SELECT * INTO b FROM rep107_base;

    IF v_movs <> b.movs THEN
        RAISE EXCEPTION 'G6: el censo de movimientos cambio (% -> %).', b.movs, v_movs;
    END IF;
    IF v_pos <> b.posiciones OR v_uds <> b.unidades THEN
        RAISE EXCEPTION 'G5: inventario cambio (% pos / % uds -> % pos / % uds).',
              b.posiciones, b.unidades, v_pos, v_uds;
    END IF;
    RAISE NOTICE 'G5/G6 OK: inventario intacto (% posiciones, % unidades) y % movimientos.',
          v_pos, v_uds, v_movs;
END $$;

COMMIT;

\echo ''
\echo '=== 107 aplicado. Verificacion posterior ==='
SELECT (SELECT count(*) FROM movimiento_inventario)            AS movimientos,
       (SELECT count(*) FROM inventario)                       AS posiciones,
       (SELECT sum(stock_actual) FROM inventario)              AS unidades,
       (SELECT count(*) FROM seed_backup.rep107_movimiento_inventario) AS filas_respaldadas;
