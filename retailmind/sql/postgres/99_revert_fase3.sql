-- ============================================================================
-- 99_revert_fase3.sql — RetailMind · reversion de la Fase 3, POR BLOQUE
--
--   Ensayo en seco de UN bloque (ejecuta, verifica y deshace):
--   docker compose exec -T postgres psql -U postgres -d retailmind \
--       -v ON_ERROR_STOP=1 -v ensayo=1 -v base=1200000000 \
--       < retailmind/sql/postgres/99_revert_fase3.sql
--
--   En firme: lo mismo sin `-v ensayo=1`.
--   TODA la fase: repetir con cada base (1200000000 .. 2100000000).
--
-- ---------------------------------------------------------------------------
-- POR QUE SE REVIERTE BLOQUE A BLOQUE
-- ---------------------------------------------------------------------------
-- La Fase 3 carga diez bloques con tramos de ids disjuntos de 100.000.000. Un
-- `DELETE ... WHERE id >= base AND id < base + 100000000` toca EXACTAMENTE un
-- bloque y ninguno mas. Eso importa porque la fase dura mas de una hora: si el
-- bloque C5 sale mal, se deshace C5 y no los cuatro anteriores, que costaron
-- veinte minutos y estan bien.
--
-- Y sigue siendo un DELETE y no una migracion por la misma razon que en las
-- fases anteriores: **ningun bloque modifica una fila preexistente**. El
-- metodo de reposicion es neto cero por grupo, asi que no hay `stock_actual`
-- que restaurar ni saldos que reencadenar. Una carga que solo ANADE se
-- revierte con una condicion sobre la clave primaria; una que ademas toca lo
-- que ya habia necesitaria un respaldo fila a fila.
--
-- CUIDADO: `trg_pedido_detalle_total` y `trg_factura_venta_detalle_total`
-- disparan tambien en DELETE y reescriben la cabecera fila a fila. Es correcto
-- y no se desactiva; explica por que borrar cuesta lo que cuesta.
--
-- SE AUTOVERIFICA y ABORTA si algo no cuadra. IDEMPOTENTE. TRANSACCIONAL.
-- ============================================================================
\set ON_ERROR_STOP on
\if :{?ensayo}
\else
  \set ensayo 0
\endif
\if :{?base}
\else
  \echo 'ERROR: falta -v base=<tramo del bloque>'
  \quit 1
\endif

BEGIN;

SELECT set_config('retailmind.base', :'base', true);

-- ── 0. Foto previa de lo que NO se debe mover ───────────────────────────────
CREATE TEMP TABLE rev3_antes ON COMMIT DROP AS
SELECT 'pedido' t, count(*) n, COALESCE(round(sum(total),2),0) v
  FROM pedido WHERE id < :base OR id >= (:base::bigint + 100000000)
UNION ALL SELECT 'pedido_detalle', count(*), COALESCE(round(sum(subtotal),2),0)
  FROM pedido_detalle WHERE id < :base OR id >= (:base::bigint + 100000000)
UNION ALL SELECT 'factura_venta', count(*), COALESCE(round(sum(total),2),0)
  FROM factura_venta WHERE id < :base OR id >= (:base::bigint + 100000000)
UNION ALL SELECT 'pago', count(*), COALESCE(round(sum(monto),2),0)
  FROM pago WHERE id < :base OR id >= (:base::bigint + 100000000)
UNION ALL SELECT 'movimiento_inventario', count(*), COALESCE(sum(cantidad),0)
  FROM movimiento_inventario WHERE id < :base OR id >= (:base::bigint + 100000000)
UNION ALL SELECT 'inventario', count(*), COALESCE(sum(stock_actual),0) FROM inventario
UNION ALL SELECT 'grupo_horario', count(*), 0 FROM grupo_horario;

-- ── 1. Borrado, al reves de las dependencias ────────────────────────────────
DELETE FROM transaccion_pago        WHERE id >= :base AND id < (:base::bigint + 100000000);
DELETE FROM pago                    WHERE id >= :base AND id < (:base::bigint + 100000000);
DELETE FROM factura_venta_detalle   WHERE id >= :base AND id < (:base::bigint + 100000000);
DELETE FROM factura_venta           WHERE id >= :base AND id < (:base::bigint + 100000000);
DELETE FROM movimiento_inventario   WHERE id >= :base AND id < (:base::bigint + 100000000);
DELETE FROM pago_proveedor          WHERE id >= :base AND id < (:base::bigint + 100000000);
DELETE FROM cuenta_por_pagar        WHERE id >= :base AND id < (:base::bigint + 100000000);
DELETE FROM factura_compra_detalle  WHERE id >= :base AND id < (:base::bigint + 100000000);
DELETE FROM factura_compra          WHERE id >= :base AND id < (:base::bigint + 100000000);
DELETE FROM recepcion_detalle       WHERE id >= :base AND id < (:base::bigint + 100000000);
DELETE FROM recepcion_mercancia     WHERE id >= :base AND id < (:base::bigint + 100000000);
DELETE FROM orden_compra_detalle    WHERE id >= :base AND id < (:base::bigint + 100000000);
DELETE FROM orden_compra            WHERE id >= :base AND id < (:base::bigint + 100000000);
DELETE FROM historial_estado_pedido WHERE id >= :base AND id < (:base::bigint + 100000000);
DELETE FROM pedido_detalle          WHERE id >= :base AND id < (:base::bigint + 100000000);
DELETE FROM pedido                  WHERE id >= :base AND id < (:base::bigint + 100000000);

DELETE FROM carga_fase_registro
 WHERE fase = 'fase3' AND id_desde >= :base AND id_desde < (:base::bigint + 100000000);

-- ── 2. Autoverificacion ─────────────────────────────────────────────────────
DO $verif$
DECLARE
    t text; n bigint; restos text := ''; d text;
    v_pos bigint; v_cuad bigint; v_rotos bigint;
    v_base bigint := current_setting('retailmind.base')::bigint;
BEGIN
    FOREACH t IN ARRAY ARRAY['pedido','pedido_detalle','historial_estado_pedido',
                             'factura_venta','factura_venta_detalle','pago','transaccion_pago',
                             'movimiento_inventario','orden_compra','orden_compra_detalle',
                             'recepcion_mercancia','recepcion_detalle','factura_compra',
                             'factura_compra_detalle','cuenta_por_pagar','pago_proveedor']
    LOOP
        EXECUTE format('SELECT count(*) FROM public.%I WHERE id >= %s AND id < %s',
                       t, v_base, v_base + 100000000) INTO n;
        IF n > 0 THEN restos := restos || format('  %s: %s%s', t, n, E'\n'); END IF;
    END LOOP;
    IF restos <> '' THEN
        RAISE EXCEPTION 'ABORTA: quedan filas del bloque sin borrar:%', E'\n' || restos;
    END IF;

    SELECT string_agg(format('  %s: %s/%s -> %s/%s', a.t, a.n, a.v, b.n, b.v), E'\n') INTO d
    FROM rev3_antes a
    JOIN (SELECT 'pedido' t, count(*) n, COALESCE(round(sum(total),2),0) v
            FROM pedido WHERE id < v_base OR id >= v_base + 100000000
          UNION ALL SELECT 'pedido_detalle', count(*), COALESCE(round(sum(subtotal),2),0)
            FROM pedido_detalle WHERE id < v_base OR id >= v_base + 100000000
          UNION ALL SELECT 'factura_venta', count(*), COALESCE(round(sum(total),2),0)
            FROM factura_venta WHERE id < v_base OR id >= v_base + 100000000
          UNION ALL SELECT 'pago', count(*), COALESCE(round(sum(monto),2),0)
            FROM pago WHERE id < v_base OR id >= v_base + 100000000
          UNION ALL SELECT 'movimiento_inventario', count(*), COALESCE(sum(cantidad),0)
            FROM movimiento_inventario WHERE id < v_base OR id >= v_base + 100000000
          UNION ALL SELECT 'inventario', count(*), COALESCE(sum(stock_actual),0) FROM inventario
          UNION ALL SELECT 'grupo_horario', count(*), 0 FROM grupo_horario) b ON b.t = a.t
    WHERE a.n <> b.n OR a.v <> b.v;
    IF d IS NOT NULL THEN
        RAISE EXCEPTION 'ABORTA: la reversion movio datos que no debia:%', E'\n' || d;
    END IF;

    SELECT count(*), count(*) FILTER (WHERE i.stock_actual = COALESCE(k.saldo, 0))
      INTO v_pos, v_cuad
    FROM inventario i
    LEFT JOIN (SELECT mi.producto_variante_id v, mi.bodega_id b, sum(mi.cantidad * tm.factor) saldo
               FROM movimiento_inventario mi JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id
               GROUP BY 1, 2) k ON k.v = i.producto_variante_id AND k.b = i.bodega_id;
    IF v_pos <> v_cuad THEN
        RAISE EXCEPTION 'ABORTA: kardex descuadrado tras revertir (% de %).', v_cuad, v_pos;
    END IF;

    SELECT count(*) INTO v_rotos FROM (
        SELECT lag(stock_nuevo) OVER w prev, stock_anterior, row_number() OVER w rn
        FROM movimiento_inventario
        WINDOW w AS (PARTITION BY producto_variante_id, bodega_id ORDER BY fecha_creacion, id)) z
    WHERE (rn > 1 AND prev <> stock_anterior) OR (rn = 1 AND stock_anterior <> 0);
    IF v_rotos > 0 THEN
        RAISE EXCEPTION 'ABORTA: % enlaces rotos tras revertir.', v_rotos;
    END IF;

    RAISE NOTICE 'Bloque revertido y verificado: 0 residuos, % de % posiciones cuadradas, '
                 '0 enlaces rotos, resto intacto al centavo.', v_cuad, v_pos;
END
$verif$;

\if :ensayo
ROLLBACK;
\echo ''
\echo 'ENSAYO EN SECO: la reversion del bloque se ejecuto ENTERA, se VERIFICO y se deshizo.'
\else
COMMIT;
\echo ''
\echo 'BLOQUE REVERTIDO.'
\endif

\echo ''
SELECT count(*) AS pedidos_totales FROM pedido;
