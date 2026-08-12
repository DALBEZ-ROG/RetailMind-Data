-- ============================================================================
-- 99_revert_reparacion_kardex.sql — RetailMind (2026-08-12)
--
-- Reversion de `107_reparacion_encadenamiento_kardex.sql` (el recalculo del
-- encadenamiento) y de `108_kardex_apendice.sql` (la guarda de prevencion).
-- Deja el sistema EXACTAMENTE como estaba antes de los dos — enlaces rotos
-- incluidos.
--
-- MODO ENSAYO POR DEFECTO: sin argumentos hace todo el trabajo, verifica y
-- termina en ROLLBACK. Para revertir DE VERDAD hay que pedirlo:
--
--   -- ensayo (no cambia nada):
--   docker compose exec -T postgres psql -U postgres -d retailmind \
--       -v ON_ERROR_STOP=1 < retailmind/sql/postgres/99_revert_reparacion_kardex.sql
--
--   -- en firme:
--   docker compose exec -T postgres psql -U postgres -d retailmind \
--       -v ON_ERROR_STOP=1 -v ensayo=0 \
--       -f /ruta/en/el/contenedor/99_revert_reparacion_kardex.sql
--
-- Es IDEMPOTENTE: revertir dos veces deja el mismo estado. El respaldo
-- `seed_backup.rep107_movimiento_inventario` NO se borra, para poder repetir.
--
-- ---------------------------------------------------------------------------
-- LO QUE ESTA REVERSION **NO** DESHACE, A PROPOSITO
-- ---------------------------------------------------------------------------
-- Los movimientos que la aplicacion haya escrito DESPUES de aplicar 107 son
-- hechos de negocio reales (las transferencias de la verificacion V7, por
-- ejemplo): no estan en el respaldo —no existian cuando se tomo— y no se
-- borran. Revertir devuelve los SALDOS de las filas respaldadas a su valor
-- previo y retira la guarda; no anula operaciones posteriores ni les cambia la
-- `fecha_creacion` que la guarda les asigno.
--
-- Consecuencia practica: si se revierte con operaciones nuevas encima, el
-- recuento de enlaces rotos de R3 sera el de antes de 107 MAS los que
-- introduzcan esas operaciones. R3 no exige un numero: lo informa.
-- ============================================================================

\timing on
\set ON_ERROR_STOP on

-- Por defecto, ensayo.
\if :{?ensayo}
\else
  \set ensayo 1
\endif

BEGIN;

SET LOCAL work_mem = '512MB';

DO $$
BEGIN
    IF to_regclass('seed_backup.rep107_movimiento_inventario') IS NULL THEN
        RAISE EXCEPTION
            'No existe seed_backup.rep107_movimiento_inventario: no hay nada que revertir (o el respaldo se borro).';
    END IF;
END $$;

CREATE TEMP TABLE rev107_base ON COMMIT DROP AS
SELECT (SELECT count(*)          FROM movimiento_inventario) AS movs,
       (SELECT count(*)          FROM inventario)            AS posiciones,
       (SELECT sum(stock_actual) FROM inventario)            AS unidades;

-- ---------------------------------------------------------------------------
-- 1. Deshacer 108 (la guarda). Se quita ANTES del restore para que la red no
--    opine sobre unas filas que, por definicion, vuelven a estar rotas.
--    (De hecho no opinaria: solo mira INSERT y solo bajo `retailmind_app`.)
-- ---------------------------------------------------------------------------
DROP TRIGGER  IF EXISTS trg_kardex_apendice_ins ON public.movimiento_inventario;
DROP FUNCTION IF EXISTS public.fn_kardex_apendice();
DROP INDEX    IF EXISTS public.idx_movimiento_inventario_cadena;

-- ---------------------------------------------------------------------------
-- 2. Deshacer 107 (el recalculo): restaurar los saldos originales
-- ---------------------------------------------------------------------------
UPDATE public.movimiento_inventario m
SET stock_anterior = b.stock_anterior,
    stock_nuevo    = b.stock_nuevo
FROM seed_backup.rep107_movimiento_inventario b
WHERE m.id = b.id
  AND (m.stock_anterior <> b.stock_anterior OR m.stock_nuevo <> b.stock_nuevo);

-- ---------------------------------------------------------------------------
-- 3. Verificacion de la reversion
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_difs  bigint;
    v_falta bigint;
    v_movs  bigint;
    v_pos   bigint;
    v_uds   bigint;
    v_rotos bigint;
    -- OJO: la variable NO se puede llamar `b` — choca con el alias de tabla
    -- `b` de las consultas de abajo y PL/pgSQL responde «record is not
    -- assigned yet», que no dice nada del choque.
    v_base  record;
BEGIN
    -- R1: cada fila respaldada vuelve a ser bit-identica en las dos columnas
    SELECT count(*) INTO v_difs
    FROM seed_backup.rep107_movimiento_inventario b
    JOIN public.movimiento_inventario m ON m.id = b.id
    WHERE m.stock_anterior <> b.stock_anterior OR m.stock_nuevo <> b.stock_nuevo;

    SELECT count(*) INTO v_falta
    FROM seed_backup.rep107_movimiento_inventario b
    WHERE NOT EXISTS (SELECT 1 FROM public.movimiento_inventario m WHERE m.id = b.id);

    IF v_difs > 0 OR v_falta > 0 THEN
        RAISE EXCEPTION 'R1: la reversion no es exacta (% filas distintas, % filas ausentes).', v_difs, v_falta;
    END IF;
    RAISE NOTICE 'R1 OK: las % filas respaldadas vuelven a su valor original.',
        (SELECT count(*) FROM seed_backup.rep107_movimiento_inventario);

    -- R2: inventario y censo intactos (la reversion tampoco toca el total)
    SELECT count(*) INTO v_movs FROM public.movimiento_inventario;
    SELECT count(*), sum(stock_actual) INTO v_pos, v_uds FROM inventario;
    SELECT * INTO v_base FROM rev107_base;
    IF v_movs <> v_base.movs OR v_pos <> v_base.posiciones OR v_uds <> v_base.unidades THEN
        RAISE EXCEPTION 'R2: la reversion movio inventario o el censo de movimientos.';
    END IF;
    RAISE NOTICE 'R2 OK: inventario intacto (% posiciones, % unidades) y % movimientos.',
        v_pos, v_uds, v_movs;

    -- R3: la prueba de que la reversion es de verdad — vuelven los enlaces rotos
    SELECT count(*) INTO v_rotos
    FROM (
        SELECT stock_anterior,
               lag(stock_nuevo) OVER w AS prev_nuevo,
               row_number()     OVER w AS rn
        FROM public.movimiento_inventario
        WINDOW w AS (PARTITION BY producto_variante_id, bodega_id
                     ORDER BY fecha_creacion, id)
    ) e
    WHERE (rn > 1 AND stock_anterior <> prev_nuevo) OR (rn = 1 AND stock_anterior <> 0);
    RAISE NOTICE 'R3: enlaces rotos tras revertir = % (el estado previo a 107 tenia 3).', v_rotos;

    -- R4: la guarda quedo desinstalada
    IF EXISTS (SELECT 1 FROM pg_trigger
               WHERE tgrelid = 'public.movimiento_inventario'::regclass
                 AND tgname  = 'trg_kardex_apendice_ins') THEN
        RAISE EXCEPTION 'R4: el trigger trg_kardex_apendice_ins sigue instalado.';
    END IF;
    RAISE NOTICE 'R4 OK: trigger, funcion e indice de 108 retirados.';
END $$;

\echo ''
\if :ensayo
  \echo '*** MODO ENSAYO: se deshace todo lo anterior (ROLLBACK). Nada ha cambiado. ***'
  \echo '*** Para revertir en firme: -v ensayo=0                                   ***'
  ROLLBACK;
\else
  \echo '*** REVERSION EN FIRME: se confirma (COMMIT). ***'
  COMMIT;
\endif

\echo ''
\echo '--- estado al terminar ---'
SELECT (SELECT count(*) FROM movimiento_inventario)       AS movimientos,
       (SELECT count(*) FROM inventario)                  AS posiciones,
       (SELECT sum(stock_actual) FROM inventario)         AS unidades,
       (SELECT count(*) FROM pg_trigger
         WHERE tgrelid = 'public.movimiento_inventario'::regclass
           AND tgname  = 'trg_kardex_apendice_ins')       AS guarda_instalada;
