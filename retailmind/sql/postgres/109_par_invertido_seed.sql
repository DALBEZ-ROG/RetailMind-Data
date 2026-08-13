-- ============================================================================
-- 109_par_invertido_seed.sql — RetailMind (2026-08-12)
--
-- Cierra el ÚLTIMO par de movimientos que el ALMACÉN leería al revés.
--
--   docker compose exec -T postgres psql -U postgres -d retailmind \
--       -v ON_ERROR_STOP=1 < retailmind/sql/postgres/109_par_invertido_seed.sql
--
-- ---------------------------------------------------------------------------
-- QUÉ PASA
-- ---------------------------------------------------------------------------
-- PostgreSQL ordena la cadena del kardex por `(fecha_creacion, id)` con
-- precisión de MICROsegundo. El almacén guarda `fact_movimiento_inventario
-- .fecha` como `DateTime` de ClickHouse —resolución de SEGUNDO— y desempata con
-- `argMax(stock_nuevo, (fecha, movimiento_id))`. Los dos órdenes coinciden
-- salvo que dos movimientos consecutivos de una misma posición caigan en el
-- MISMO SEGUNDO y el id RETROCEDA: ahí el almacén los lee invertidos.
--
-- El script 108 normalizó los que cerraban su posición. Quedó UNO, sembrado, a
-- mitad de cadena:
--
--   3300014980  2025-12-18 21:22:58.174892  (31 -> 32)
--   1200736356  2025-12-18 21:22:58.464328  (32 -> 31)   <- id MENOR, mismo segundo
--
-- Hoy es INOFENSIVO —`argMax` toma el máximo del MES y este par está a mitad de
-- diciembre, así que el cierre del mes lo decide un movimiento posterior— y por
-- eso el 108 lo dejó declarado en vez de tocarlo. Se cierra ahora porque es una
-- trampa latente: en cuanto alguien agregue por DÍA, o el par acabe cerrando su
-- ventana, el almacén publicaría el saldo equivocado sin dar un solo error.
--
-- ---------------------------------------------------------------------------
-- POR QUÉ AHORA SÍ SE PUEDE TOCAR
-- ---------------------------------------------------------------------------
-- El 108 no lo tocó porque empujar un movimiento de MITAD de cadena puede
-- colocarlo por delante del siguiente y romper el encadenamiento. Aquí eso no
-- ocurre, y está MEDIDO: el siguiente movimiento de esa posición
-- (`1200741315`) llega el 2025-12-20 14:29, o sea **1 día y 17 horas después**.
-- Empujar un SEGUNDO deja la fila exactamente donde estaba en el orden.
--
-- El script lo comprueba igualmente antes de escribir (G1) y aborta si el hueco
-- no diera. No se toca `stock_anterior` ni `stock_nuevo`: la cadena no cambia,
-- solo la marca de tiempo — así que `trg_kardex_ecuacion_upd` ni siquiera se
-- dispara (su `WHEN` mira saldos, cantidad y tipo, no la fecha).
--
-- Guardas (cualquiera aborta la transacción):
--   G1  el hueco al siguiente movimiento es mayor que el empujón
--   G2  tras escribir, CERO pares invertidos en toda la tabla
--   G3  tras escribir, CERO enlaces rotos en toda la tabla
--   G4  `inventario` intacto y ni un movimiento creado o borrado
--
-- IDEMPOTENTE: en una segunda corrida no encuentra ningún par y no escribe.
-- Reversión: `99_revert_par_invertido.sql`.
-- ============================================================================

\timing on
\set ON_ERROR_STOP on

BEGIN;

SET LOCAL work_mem = '512MB';

CREATE TEMP TABLE inv109_base ON COMMIT DROP AS
SELECT (SELECT count(*)          FROM movimiento_inventario) AS movs,
       (SELECT count(*)          FROM inventario)            AS posiciones,
       (SELECT sum(stock_actual) FROM inventario)            AS unidades;

-- ---------------------------------------------------------------------------
-- 1. Localizar los pares invertidos que quedan (barrido completo)
-- ---------------------------------------------------------------------------
CREATE TEMP TABLE inv109_pares ON COMMIT DROP AS
WITH ord AS (
    SELECT id, producto_variante_id, bodega_id, fecha_creacion,
           lag(id)             OVER w AS prev_id,
           lag(fecha_creacion) OVER w AS prev_fecha,
           lead(id)            OVER w AS sig_id,
           lead(fecha_creacion) OVER w AS sig_fecha
    FROM movimiento_inventario
    WINDOW w AS (PARTITION BY producto_variante_id, bodega_id
                 ORDER BY fecha_creacion, id)
)
SELECT id, producto_variante_id, bodega_id, fecha_creacion, prev_id, prev_fecha,
       sig_id, sig_fecha,
       date_trunc('second', prev_fecha) + interval '1 second' AS fecha_nueva
FROM ord
WHERE prev_id IS NOT NULL
  AND id < prev_id
  AND date_trunc('second', fecha_creacion) = date_trunc('second', prev_fecha);

\echo ''
\echo '--- pares que el almacen leeria al reves ---'
SELECT id, producto_variante_id AS variante, bodega_id AS bodega, prev_id,
       fecha_creacion, fecha_nueva, sig_id,
       sig_fecha - fecha_nueva AS holgura_tras_empujar
FROM inv109_pares ORDER BY producto_variante_id, bodega_id, fecha_creacion;

-- ---------------------------------------------------------------------------
-- 2. G1 — el empujón no puede alcanzar al siguiente movimiento
-- ---------------------------------------------------------------------------
DO $$
DECLARE v_malos integer;
BEGIN
    SELECT count(*) INTO v_malos
    FROM inv109_pares
    WHERE sig_fecha IS NOT NULL AND fecha_nueva >= sig_fecha;

    IF v_malos > 0 THEN
        RAISE EXCEPTION
            'G1: % par(es) no tienen hueco para el empujon de un segundo; empujarlos reordenaria la cadena. Se aborta.',
            v_malos;
    END IF;
    RAISE NOTICE 'G1 OK: los % par(es) tienen hueco de sobra.',
        (SELECT count(*) FROM inv109_pares);
END $$;

-- ---------------------------------------------------------------------------
-- 3. Respaldo y escritura
-- ---------------------------------------------------------------------------
CREATE SCHEMA IF NOT EXISTS seed_backup;

CREATE TABLE IF NOT EXISTS seed_backup.inv109_fechas (
    id             bigint PRIMARY KEY,
    fecha_original timestamptz NOT NULL,
    fecha_nueva    timestamptz NOT NULL,
    respaldado_en  timestamptz NOT NULL DEFAULT now()
);

INSERT INTO seed_backup.inv109_fechas (id, fecha_original, fecha_nueva)
SELECT p.id, p.fecha_creacion, p.fecha_nueva
FROM inv109_pares p
WHERE NOT EXISTS (SELECT 1 FROM seed_backup.inv109_fechas b WHERE b.id = p.id);

UPDATE movimiento_inventario m
SET fecha_creacion = p.fecha_nueva
FROM inv109_pares p
WHERE m.id = p.id;

-- ---------------------------------------------------------------------------
-- 4. G2 a G4 — verificación
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_inv   bigint;
    v_rotos bigint;
    v_movs  bigint;
    v_pos   bigint;
    v_uds   bigint;
    v_base  record;
BEGIN
    -- G2: ni un par invertido en toda la tabla
    SELECT count(*) INTO v_inv
    FROM (
        SELECT id, lag(id) OVER w AS prev_id, fecha_creacion,
               lag(fecha_creacion) OVER w AS prev_fecha
        FROM movimiento_inventario
        WINDOW w AS (PARTITION BY producto_variante_id, bodega_id
                     ORDER BY fecha_creacion, id)
    ) o
    WHERE prev_id IS NOT NULL AND id < prev_id
      AND date_trunc('second', fecha_creacion) = date_trunc('second', prev_fecha);

    IF v_inv > 0 THEN
        RAISE EXCEPTION 'G2: siguen quedando % par(es) invertidos.', v_inv;
    END IF;
    RAISE NOTICE 'G2 OK: cero pares que el almacen leeria al reves.';

    -- G3: el encadenamiento sigue intacto
    SELECT count(*) INTO v_rotos
    FROM (
        SELECT stock_anterior,
               lag(stock_nuevo) OVER w AS prev_nuevo,
               row_number()     OVER w AS rn
        FROM movimiento_inventario
        WINDOW w AS (PARTITION BY producto_variante_id, bodega_id
                     ORDER BY fecha_creacion, id)
    ) e
    WHERE (rn > 1 AND stock_anterior <> prev_nuevo) OR (rn = 1 AND stock_anterior <> 0);

    IF v_rotos > 0 THEN
        RAISE EXCEPTION 'G3: el empujon rompio el encadenamiento (% enlaces rotos).', v_rotos;
    END IF;
    RAISE NOTICE 'G3 OK: cero enlaces rotos en toda la tabla.';

    -- G4: nada creado, nada borrado, inventario intacto
    SELECT count(*) INTO v_movs FROM movimiento_inventario;
    SELECT count(*), sum(stock_actual) INTO v_pos, v_uds FROM inventario;
    SELECT * INTO v_base FROM inv109_base;
    IF v_movs <> v_base.movs OR v_pos <> v_base.posiciones OR v_uds <> v_base.unidades THEN
        RAISE EXCEPTION 'G4: cambio el censo de movimientos o el inventario.';
    END IF;
    RAISE NOTICE 'G4 OK: inventario intacto (% posiciones, % unidades) y % movimientos.',
        v_pos, v_uds, v_movs;
END $$;

COMMIT;

\echo ''
\echo '=== 109 aplicado ==='
SELECT (SELECT count(*) FROM movimiento_inventario)          AS movimientos,
       (SELECT count(*) FROM inventario)                     AS posiciones,
       (SELECT sum(stock_actual) FROM inventario)            AS unidades,
       (SELECT count(*) FROM seed_backup.inv109_fechas)      AS filas_respaldadas;
