-- ============================================================================
-- 105_fase6_quita_duplicadas.sql — RetailMind · corrección de la Fase 6 (2/2)
--
--   docker compose exec -T postgres psql -U postgres -d retailmind \
--       -v ON_ERROR_STOP=1 -f - < retailmind/sql/postgres/105_fase6_quita_duplicadas.sql
--
-- ---------------------------------------------------------------------------
-- QUÉ ARREGLA
-- ---------------------------------------------------------------------------
-- La regla de elegibilidad de la Fase 6 —«todo pedido 'devuelto' lleva
-- devolución»— se aplicó a los 79.054 pedidos devueltos SIN excluir los que YA
-- tenían una de la siembra original. Resultado: **105 pedidos con dos
-- devoluciones**, y como cada una escoge sus líneas del mismo pedido, 88
-- líneas acabaron devolviendo MÁS unidades de las que se vendieron (130 de
-- exceso).
--
-- Lo detectó el control del ETL —«88 líneas devuelven más unidades de las que
-- se vendieron»—, no una revisión visual. El invariante «no se devuelve más de
-- lo comprado» es de los que nunca se comprueban a mano porque parecen
-- imposibles de violar: aquí lo violó una regla correcta aplicada a una
-- población que ya estaba parcialmente servida.
--
-- Se quita la devolución NUEVA y se conserva la ORIGINAL, que es la que tiene
-- historia real detrás. 101 de las líneas retiradas eran 'apto_reventa', así
-- que hay que deshacer su reingreso al kardex y reencadenar sus posiciones.
--
-- VA EN UNA SOLA TRANSACCIÓN. IDEMPOTENTE. SE AUTOVERIFICA Y ABORTA.
-- ============================================================================
\set ON_ERROR_STOP on
\timing on

-- Las devoluciones sobrantes y las posiciones de kardex que tocan, calculadas
-- ANTES de borrar nada.
CREATE TEMP TABLE dup_dev AS
SELECT d.id FROM devolucion d
WHERE d.id >= 2600000000
  AND EXISTS (SELECT 1 FROM devolucion o
              WHERE o.pedido_id = d.pedido_id AND o.id < 2600000000);
CREATE UNIQUE INDEX ON dup_dev (id);

CREATE TEMP TABLE dup_pos AS
SELECT DISTINCT mi.producto_variante_id v, mi.bodega_id b
FROM movimiento_inventario mi
WHERE mi.id >= 3300000000 AND mi.referencia_tipo = 'devolucion'
  AND mi.referencia_id IN (SELECT id FROM dup_dev);
CREATE UNIQUE INDEX ON dup_pos (v, b);
ANALYZE dup_dev;
ANALYZE dup_pos;

\echo ''
\echo '-- alcance --'
SELECT (SELECT count(*) FROM dup_dev) devoluciones_a_quitar,
       (SELECT count(*) FROM dup_pos) posiciones_a_reencadenar,
       (SELECT count(*) FROM movimiento_inventario WHERE id>=3300000000
          AND referencia_tipo='devolucion' AND referencia_id IN (SELECT id FROM dup_dev)) movs_a_quitar;

BEGIN;

-- ── 1. Borrado, al revés de las dependencias ────────────────────────────────
DELETE FROM item_defectuoso WHERE devolucion_detalle_id IN
    (SELECT dd.id FROM devolucion_detalle dd WHERE dd.devolucion_id IN (SELECT id FROM dup_dev));
DELETE FROM reembolso                   WHERE devolucion_id IN (SELECT id FROM dup_dev);
DELETE FROM historial_estado_devolucion WHERE devolucion_id IN (SELECT id FROM dup_dev);
DELETE FROM movimiento_inventario       WHERE id >= 3300000000
    AND referencia_tipo = 'devolucion' AND referencia_id IN (SELECT id FROM dup_dev);
DELETE FROM devolucion_detalle          WHERE devolucion_id IN (SELECT id FROM dup_dev);
DELETE FROM devolucion                  WHERE id IN (SELECT id FROM dup_dev);

-- ── 2. Reencadenar las posiciones que perdieron entradas ────────────────────
WITH calc AS (
    SELECT mi.id,
           mi.cantidad * tm.factor AS delta,
           sum(mi.cantidad * tm.factor) OVER (
               PARTITION BY mi.producto_variante_id, mi.bodega_id
               ORDER BY mi.fecha_creacion, mi.id
               ROWS UNBOUNDED PRECEDING) AS nuevo
    FROM movimiento_inventario mi
    JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id
    JOIN dup_pos p ON p.v = mi.producto_variante_id AND p.b = mi.bodega_id
)
UPDATE movimiento_inventario m
   SET stock_anterior = c.nuevo - c.delta,
       stock_nuevo    = c.nuevo
FROM calc c
WHERE m.id = c.id
  AND (m.stock_nuevo    IS DISTINCT FROM c.nuevo
    OR m.stock_anterior IS DISTINCT FROM c.nuevo - c.delta);

-- ── 3. El saldo de esas posiciones ──────────────────────────────────────────
UPDATE inventario i SET stock_actual = k.saldo
FROM (SELECT mi.producto_variante_id v, mi.bodega_id b,
             sum(mi.cantidad * tm.factor) saldo
      FROM movimiento_inventario mi
      JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id
      JOIN dup_pos p ON p.v = mi.producto_variante_id AND p.b = mi.bodega_id
      GROUP BY 1, 2) k
WHERE i.producto_variante_id = k.v AND i.bodega_id = k.b
  AND i.stock_actual IS DISTINCT FROM k.saldo;

-- ── 4. Autoverificación ─────────────────────────────────────────────────────
DO $v$
DECLARE n bigint; v_pos bigint; v_cuad bigint;
BEGIN
    SELECT count(*) INTO n FROM devolucion d WHERE d.id >= 2600000000
      AND EXISTS (SELECT 1 FROM devolucion o WHERE o.pedido_id = d.pedido_id AND o.id <> d.id);
    IF n > 0 THEN RAISE EXCEPTION 'ABORTA: quedan % pedidos con dos devoluciones.', n; END IF;

    -- EL INVARIANTE: no se devuelve más de lo que se vendió.
    SELECT count(*) INTO n FROM (
        SELECT dd.pedido_detalle_id, sum(dd.cantidad) dev
        FROM devolucion_detalle dd GROUP BY 1) x
    JOIN pedido_detalle pd ON pd.id = x.pedido_detalle_id WHERE x.dev > pd.cantidad;
    IF n > 0 THEN RAISE EXCEPTION 'ABORTA: % lineas devuelven mas de lo vendido.', n; END IF;

    SELECT count(*), count(*) FILTER (WHERE i.stock_actual = COALESCE(k.saldo, 0))
      INTO v_pos, v_cuad
    FROM inventario i
    LEFT JOIN (SELECT mi.producto_variante_id v, mi.bodega_id b,
                      sum(mi.cantidad * tm.factor) saldo
               FROM movimiento_inventario mi
               JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id
               GROUP BY 1, 2) k ON k.v = i.producto_variante_id AND k.b = i.bodega_id;
    IF v_pos <> v_cuad THEN
        RAISE EXCEPTION 'ABORTA: kardex descuadrado (% de %).', v_cuad, v_pos;
    END IF;

    SELECT count(*) INTO n FROM (
        SELECT mi.stock_anterior,
               lag(mi.stock_nuevo) OVER (PARTITION BY mi.producto_variante_id, mi.bodega_id
                                         ORDER BY mi.fecha_creacion, mi.id) prev
        FROM movimiento_inventario mi
        JOIN dup_pos p ON p.v = mi.producto_variante_id AND p.b = mi.bodega_id) x
    WHERE (prev IS NULL AND stock_anterior <> 0) OR (prev IS NOT NULL AND stock_anterior <> prev);
    IF n > 0 THEN RAISE EXCEPTION 'ABORTA: % enlaces rotos.', n; END IF;

    RAISE NOTICE 'Duplicadas retiradas: 0 pedidos con dos devoluciones, 0 lineas '
                 'sobre-devueltas, % de % posiciones cuadradas, 0 enlaces rotos.',
                 v_cuad, v_pos;
END
$v$;

COMMIT;

\echo ''
SELECT (SELECT count(*) FROM devolucion) devoluciones,
       (SELECT count(*) FROM devolucion_detalle) lineas,
       (SELECT count(*) FROM movimiento_inventario) kardex,
       (SELECT sum(stock_actual) FROM inventario) unidades;
