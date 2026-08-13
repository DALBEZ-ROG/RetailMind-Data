-- ============================================================================
-- 99_revert_par_invertido.sql — RetailMind (2026-08-12)
--
-- Reversión de `109_par_invertido_seed.sql`: devuelve la `fecha_creacion`
-- original a los movimientos que el 109 empujó un segundo.
--
-- MODO ENSAYO POR DEFECTO: hace el trabajo, verifica y termina en ROLLBACK.
--
--   -- ensayo (no cambia nada):
--   docker compose exec -T postgres psql -U postgres -d retailmind \
--       -v ON_ERROR_STOP=1 < retailmind/sql/postgres/99_revert_par_invertido.sql
--
--   -- en firme:
--   ... -v ensayo=0 -f /ruta/en/el/contenedor/99_revert_par_invertido.sql
--
-- IDEMPOTENTE. El respaldo `seed_backup.inv109_fechas` NO se borra.
--
-- OJO a lo que significa revertir: vuelve a haber un par que el ALMACÉN lee al
-- revés. Es el estado previo —inofensivo hoy, latente mañana— y R3 lo informa
-- para que se vea que la reversión hizo su trabajo.
-- ============================================================================

\timing on
\set ON_ERROR_STOP on

\if :{?ensayo}
\else
  \set ensayo 1
\endif

BEGIN;

SET LOCAL work_mem = '512MB';

DO $$
BEGIN
    IF to_regclass('seed_backup.inv109_fechas') IS NULL THEN
        RAISE EXCEPTION
            'No existe seed_backup.inv109_fechas: no hay nada que revertir.';
    END IF;
END $$;

CREATE TEMP TABLE rev109_base ON COMMIT DROP AS
SELECT (SELECT count(*)          FROM movimiento_inventario) AS movs,
       (SELECT count(*)          FROM inventario)            AS posiciones,
       (SELECT sum(stock_actual) FROM inventario)            AS unidades;

UPDATE movimiento_inventario m
SET fecha_creacion = b.fecha_original
FROM seed_backup.inv109_fechas b
WHERE m.id = b.id
  AND m.fecha_creacion <> b.fecha_original;

DO $$
DECLARE
    v_difs  bigint;
    v_rotos bigint;
    v_inv   bigint;
    v_movs  bigint;
    v_pos   bigint;
    v_uds   bigint;
    v_base  record;   -- NO llamarla `b`: chocaria con el alias de tabla
BEGIN
    -- R1: cada fila respaldada vuelve exactamente a su fecha original
    SELECT count(*) INTO v_difs
    FROM seed_backup.inv109_fechas b
    JOIN movimiento_inventario m ON m.id = b.id
    WHERE m.fecha_creacion <> b.fecha_original;
    IF v_difs > 0 THEN
        RAISE EXCEPTION 'R1: la reversion no es exacta (% filas distintas).', v_difs;
    END IF;
    RAISE NOTICE 'R1 OK: las % filas vuelven a su fecha original.',
        (SELECT count(*) FROM seed_backup.inv109_fechas);

    -- R2: el encadenamiento sigue intacto (revertir tampoco puede romperlo)
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
        RAISE EXCEPTION 'R2: la reversion rompio el encadenamiento (% enlaces).', v_rotos;
    END IF;
    RAISE NOTICE 'R2 OK: cero enlaces rotos.';

    -- R3: la prueba de que revirtio de verdad — vuelve el par invertido
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
    RAISE NOTICE 'R3: pares invertidos tras revertir = % (antes del 109 habia 1).', v_inv;

    -- R4: nada creado, nada borrado, inventario intacto
    SELECT count(*) INTO v_movs FROM movimiento_inventario;
    SELECT count(*), sum(stock_actual) INTO v_pos, v_uds FROM inventario;
    SELECT * INTO v_base FROM rev109_base;
    IF v_movs <> v_base.movs OR v_pos <> v_base.posiciones OR v_uds <> v_base.unidades THEN
        RAISE EXCEPTION 'R4: cambio el censo de movimientos o el inventario.';
    END IF;
    RAISE NOTICE 'R4 OK: inventario intacto (% posiciones, % unidades) y % movimientos.',
        v_pos, v_uds, v_movs;
END $$;

\echo ''
\if :ensayo
  \echo '*** MODO ENSAYO: se deshace todo (ROLLBACK). Nada ha cambiado. ***'
  \echo '*** Para revertir en firme: -v ensayo=0                        ***'
  ROLLBACK;
\else
  \echo '*** REVERSION EN FIRME: se confirma (COMMIT). ***'
  COMMIT;
\endif
