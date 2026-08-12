-- ============================================================================
-- 99_revert_fase2.sql — RetailMind · reversion COMPLETA de la Fase 2 de la
--                       carga masiva (script 98, los 300.000 pedidos)
--
--   Ensayo en seco (ejecuta, VERIFICA y despues deshace):
--   docker compose exec -T postgres psql -U postgres -d retailmind \
--       -v ON_ERROR_STOP=1 -v ensayo=1 < retailmind/sql/postgres/99_revert_fase2.sql
--
--   En firme: lo mismo sin `-v ensayo=1`.
--
-- ---------------------------------------------------------------------------
-- POR QUE SIGUE SIENDO UN DELETE Y NO UNA MIGRACION
-- ---------------------------------------------------------------------------
-- Todo lo que escribio la Fase 2 vive en ids >= 1.100.000.000, y —lo que de
-- verdad importa— la fase NO MODIFICO NI UNA FILA PREEXISTENTE. No hay
-- `stock_actual` que restaurar ni saldos que reencadenar, porque el metodo es
-- neto cero por posicion: cada bimestre compra exactamente lo que vende.
--
-- Y hay una segunda propiedad, propia de esta fase: al ir la ventana ENTERA
-- por detras del ultimo movimiento existente (2026-08-10), lo que se borra es
-- la COLA de cada cadena. Borrar la cola no obliga a recalcular nada de lo que
-- queda delante. Una fase que se intercala —como la 1— tampoco obligaba,
-- porque sumaba cero; una que ni se intercala ni suma cero si lo haria, y
-- entonces la reversion dejaria de ser barata.
--
-- ORDEN DE BORRADO: al reves de las dependencias.
--
-- CUIDADO: `trg_pedido_detalle_total` y `trg_factura_venta_detalle_total`
-- disparan tambien en DELETE y reescriben la cabecera fila a fila. Con 767.000
-- lineas eso es el grueso del tiempo de la reversion. Es correcto y no se
-- desactiva; solo conviene saber por que tarda.
--
-- SE AUTOVERIFICA y ABORTA si algo no cuadra. IDEMPOTENTE. TRANSACCIONAL.
-- ============================================================================
\set ON_ERROR_STOP on
\if :{?ensayo}
\else
  \set ensayo 0
\endif

BEGIN;

-- ── 0. Foto previa de lo que NO se debe mover ───────────────────────────────
CREATE TEMP TABLE rev2_antes ON COMMIT DROP AS
SELECT 'pedido' t, count(*) n, COALESCE(round(sum(total),2),0) v FROM pedido WHERE id < 1100000000
UNION ALL SELECT 'pedido_detalle', count(*), COALESCE(round(sum(subtotal),2),0) FROM pedido_detalle WHERE id < 1100000000
UNION ALL SELECT 'factura_venta',  count(*), COALESCE(round(sum(total),2),0)    FROM factura_venta  WHERE id < 1100000000
UNION ALL SELECT 'pago',           count(*), COALESCE(round(sum(monto),2),0)    FROM pago           WHERE id < 1100000000
UNION ALL SELECT 'historial',      count(*), 0                                  FROM historial_estado_pedido WHERE id < 1100000000
UNION ALL SELECT 'movimiento_inventario', count(*), COALESCE(sum(cantidad),0)   FROM movimiento_inventario WHERE id < 1100000000
UNION ALL SELECT 'orden_compra',   count(*), 0                                  FROM orden_compra   WHERE id < 1100000000
UNION ALL SELECT 'inventario',     count(*), COALESCE(sum(stock_actual),0)      FROM inventario
UNION ALL SELECT 'grupo_horario',  count(*), 0 FROM grupo_horario;

-- ── 1. Borrado ──────────────────────────────────────────────────────────────
DELETE FROM transaccion_pago        WHERE id >= 1100000000;
DELETE FROM pago                    WHERE id >= 1100000000;
DELETE FROM factura_venta_detalle   WHERE id >= 1100000000;
DELETE FROM factura_venta           WHERE id >= 1100000000;
DELETE FROM movimiento_inventario   WHERE id >= 1100000000;
DELETE FROM pago_proveedor          WHERE id >= 1100000000;
DELETE FROM cuenta_por_pagar        WHERE id >= 1100000000;
DELETE FROM factura_compra_detalle  WHERE id >= 1100000000;
DELETE FROM factura_compra          WHERE id >= 1100000000;
DELETE FROM recepcion_detalle       WHERE id >= 1100000000;
DELETE FROM recepcion_mercancia     WHERE id >= 1100000000;
DELETE FROM orden_compra_detalle    WHERE id >= 1100000000;
DELETE FROM orden_compra            WHERE id >= 1100000000;
DELETE FROM historial_estado_pedido WHERE id >= 1100000000;
DELETE FROM pedido_detalle          WHERE id >= 1100000000;
DELETE FROM pedido                  WHERE id >= 1100000000;

DELETE FROM carga_fase_registro  WHERE fase = 'fase2';
DELETE FROM carga_fase_parametro WHERE fase = 'fase2';

-- ── 2. Autoverificacion ─────────────────────────────────────────────────────
DO $verif$
DECLARE
    t text; n bigint; restos text := ''; d text;
    v_pos bigint; v_cuad bigint; v_rotos bigint;
BEGIN
    FOREACH t IN ARRAY ARRAY['pedido','pedido_detalle','historial_estado_pedido',
                             'factura_venta','factura_venta_detalle','pago','transaccion_pago',
                             'movimiento_inventario','orden_compra','orden_compra_detalle',
                             'recepcion_mercancia','recepcion_detalle','factura_compra',
                             'factura_compra_detalle','cuenta_por_pagar','pago_proveedor']
    LOOP
        EXECUTE format('SELECT count(*) FROM public.%I WHERE id >= 1100000000', t) INTO n;
        IF n > 0 THEN restos := restos || format('  %s: %s%s', t, n, E'\n'); END IF;
    END LOOP;
    IF restos <> '' THEN
        RAISE EXCEPTION 'ABORTA: quedan filas de la Fase 2 sin borrar:%', E'\n' || restos;
    END IF;

    SELECT string_agg(format('  %s: %s/%s -> %s/%s', a.t, a.n, a.v, b.n, b.v), E'\n') INTO d
    FROM rev2_antes a
    JOIN (SELECT 'pedido' t, count(*) n, COALESCE(round(sum(total),2),0) v FROM pedido WHERE id < 1100000000
          UNION ALL SELECT 'pedido_detalle', count(*), COALESCE(round(sum(subtotal),2),0) FROM pedido_detalle WHERE id < 1100000000
          UNION ALL SELECT 'factura_venta',  count(*), COALESCE(round(sum(total),2),0)    FROM factura_venta  WHERE id < 1100000000
          UNION ALL SELECT 'pago',           count(*), COALESCE(round(sum(monto),2),0)    FROM pago           WHERE id < 1100000000
          UNION ALL SELECT 'historial',      count(*), 0                                  FROM historial_estado_pedido WHERE id < 1100000000
          UNION ALL SELECT 'movimiento_inventario', count(*), COALESCE(sum(cantidad),0)   FROM movimiento_inventario WHERE id < 1100000000
          UNION ALL SELECT 'orden_compra',   count(*), 0                                  FROM orden_compra   WHERE id < 1100000000
          UNION ALL SELECT 'inventario',     count(*), COALESCE(sum(stock_actual),0)      FROM inventario
          UNION ALL SELECT 'grupo_horario',  count(*), 0 FROM grupo_horario) b ON b.t = a.t
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

    RAISE NOTICE 'Reversion verificada: 0 residuos, % de % posiciones cuadradas, 0 enlaces rotos, '
                 'y lo preexistente intacto al centavo.', v_cuad, v_pos;
END
$verif$;

\if :ensayo
ROLLBACK;
\echo ''
\echo 'ENSAYO EN SECO: la reversion se ejecuto ENTERA, se VERIFICO y despues se deshizo.'
\echo 'La base queda EXACTAMENTE como estaba.'
\else
COMMIT;
\echo ''
\echo 'FASE 2 REVERTIDA. La base vuelve al estado previo al script 98.'
\endif

\echo ''
SELECT 'pedido' t, count(*) n FROM pedido
UNION ALL SELECT 'pedido_detalle', count(*) FROM pedido_detalle
UNION ALL SELECT 'movimiento_inventario', count(*) FROM movimiento_inventario
UNION ALL SELECT 'inventario (unidades)', sum(stock_actual) FROM inventario
ORDER BY 1;
