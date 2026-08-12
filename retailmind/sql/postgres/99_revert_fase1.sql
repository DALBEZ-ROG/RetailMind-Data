-- ============================================================================
-- 99_revert_fase1.sql — RetailMind · reversion COMPLETA de la Fase 1 de la
--                       carga masiva (script 97, el piloto de 10.000 pedidos)
--
--   Ensayo en seco (ejecuta, VERIFICA y despues deshace: no borra nada):
--   docker compose exec -T postgres psql -U postgres -d retailmind \
--       -v ON_ERROR_STOP=1 -v ensayo=1 < retailmind/sql/postgres/99_revert_fase1.sql
--
--   En firme:
--   docker compose exec -T postgres psql -U postgres -d retailmind \
--       -v ON_ERROR_STOP=1 < retailmind/sql/postgres/99_revert_fase1.sql
--
-- ---------------------------------------------------------------------------
-- POR QUE ESTA REVERSION ES BARATA, Y POR QUE ESO NO ES CASUALIDAD
-- ---------------------------------------------------------------------------
-- Todo lo que escribio la Fase 1 vive en ids >= 1.000.000.000 (ver la cabecera
-- del script 97), asi que borrar es una condicion sobre la clave primaria y no
-- una busqueda de marcadores en texto libre.
--
-- Pero hay una segunda razon, y es la que de verdad importa: **la Fase 1 no
-- modifico ni una fila preexistente**. No hay `stock_actual` que restaurar, ni
-- saldos de kardex que reencadenar, ni totales de cabecera que recalcular,
-- porque el metodo de reposicion elegido —comprar en cada posicion exactamente
-- lo que se vende en ella— deja el neto en CERO. Una carga que solo ANADE se
-- revierte con un DELETE; una que ademas TOCA lo que ya habia necesitaria un
-- respaldo fila a fila y dejaria de ser reversible en cuanto creciera. Esa es
-- la propiedad que el piloto existia para demostrar.
--
-- ORDEN DE BORRADO: al reves de las dependencias.
--   transaccion_pago -> pago -> factura_venta_detalle -> factura_venta
--   -> movimiento_inventario -> pago_proveedor -> cuenta_por_pagar
--   -> factura_compra_detalle -> factura_compra -> recepcion_detalle
--   -> recepcion_mercancia -> orden_compra_detalle -> orden_compra
--   -> historial_estado_pedido -> pedido_detalle -> pedido
--
-- CUIDADO CON LOS TRIGGERS AL BORRAR EL DETALLE: `trg_pedido_detalle_total` y
-- `trg_factura_venta_detalle_total` disparan tambien en DELETE y reescriben la
-- cabecera. Da igual —la cabecera se borra a continuacion—, pero explica que
-- borrar 25.582 lineas cueste mas que insertarlas.
--
-- LO QUE NO BORRA, A PROPOSITO: `carga_fase_registro` y `carga_fase_parametro`
-- y la funcion `fn_carga_registrar` (script 92) son infraestructura compartida
-- con las demas fases. Se limpian sus filas de `fase1` y las tablas se quedan.
--
-- SE AUTOVERIFICA: al terminar comprueba que no queda ni una fila del tramo,
-- que los 4.083 pedidos originales siguen ahi con sus importes al centavo, que
-- el kardex cuadra posicion por posicion y que la cadena no tiene un solo
-- enlace roto. Si algo falla, ABORTA y la transaccion entera se va abajo.
--
-- IDEMPOTENTE: ejecutarlo dos veces es inofensivo. TRANSACCIONAL: todo o nada.
-- ============================================================================
\set ON_ERROR_STOP on
\if :{?ensayo}
\else
  \set ensayo 0
\endif

BEGIN;

-- ── 0. Foto previa de lo que NO se debe mover ───────────────────────────────
CREATE TEMP TABLE rev1_antes ON COMMIT DROP AS
SELECT 'pedido' t, count(*) n, COALESCE(round(sum(total),2),0) v FROM pedido WHERE id < 1000000000
UNION ALL SELECT 'pedido_detalle', count(*), COALESCE(round(sum(subtotal),2),0) FROM pedido_detalle WHERE id < 1000000000
UNION ALL SELECT 'factura_venta',  count(*), COALESCE(round(sum(total),2),0)    FROM factura_venta  WHERE id < 1000000000
UNION ALL SELECT 'pago',           count(*), COALESCE(round(sum(monto),2),0)    FROM pago           WHERE id < 1000000000
UNION ALL SELECT 'movimiento_inventario', count(*), COALESCE(sum(cantidad),0)   FROM movimiento_inventario WHERE id < 1000000000
UNION ALL SELECT 'inventario',     count(*), COALESCE(sum(stock_actual),0)      FROM inventario
UNION ALL SELECT 'grupo_horario',  count(*), 0 FROM grupo_horario;

-- ── 1. Borrado ──────────────────────────────────────────────────────────────
DELETE FROM transaccion_pago        WHERE id >= 1000000000;
DELETE FROM pago                    WHERE id >= 1000000000;
DELETE FROM factura_venta_detalle   WHERE id >= 1000000000;
DELETE FROM factura_venta           WHERE id >= 1000000000;
DELETE FROM movimiento_inventario   WHERE id >= 1000000000;
DELETE FROM pago_proveedor          WHERE id >= 1000000000;
DELETE FROM cuenta_por_pagar        WHERE id >= 1000000000;
DELETE FROM factura_compra_detalle  WHERE id >= 1000000000;
DELETE FROM factura_compra          WHERE id >= 1000000000;
DELETE FROM recepcion_detalle       WHERE id >= 1000000000;
DELETE FROM recepcion_mercancia     WHERE id >= 1000000000;
DELETE FROM orden_compra_detalle    WHERE id >= 1000000000;
DELETE FROM orden_compra            WHERE id >= 1000000000;
DELETE FROM historial_estado_pedido WHERE id >= 1000000000;
DELETE FROM pedido_detalle          WHERE id >= 1000000000;
DELETE FROM pedido                  WHERE id >= 1000000000;

DELETE FROM carga_fase_registro  WHERE fase = 'fase1';
DELETE FROM carga_fase_parametro WHERE fase = 'fase1';

-- ── 2. Autoverificacion. Si algo no cuadra, ABORTA. ─────────────────────────
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
        EXECUTE format('SELECT count(*) FROM public.%I WHERE id >= 1000000000', t) INTO n;
        IF n > 0 THEN restos := restos || format('  %s: %s%s', t, n, E'\n'); END IF;
    END LOOP;
    IF restos <> '' THEN
        RAISE EXCEPTION 'ABORTA: quedan filas de la Fase 1 sin borrar:%', E'\n' || restos;
    END IF;

    -- Lo preexistente, intacto y al centavo.
    SELECT string_agg(format('  %s: %s/%s -> %s/%s', a.t, a.n, a.v, b.n, b.v), E'\n') INTO d
    FROM rev1_antes a
    JOIN (SELECT 'pedido' t, count(*) n, COALESCE(round(sum(total),2),0) v FROM pedido WHERE id < 1000000000
          UNION ALL SELECT 'pedido_detalle', count(*), COALESCE(round(sum(subtotal),2),0) FROM pedido_detalle WHERE id < 1000000000
          UNION ALL SELECT 'factura_venta',  count(*), COALESCE(round(sum(total),2),0)    FROM factura_venta  WHERE id < 1000000000
          UNION ALL SELECT 'pago',           count(*), COALESCE(round(sum(monto),2),0)    FROM pago           WHERE id < 1000000000
          UNION ALL SELECT 'movimiento_inventario', count(*), COALESCE(sum(cantidad),0)   FROM movimiento_inventario WHERE id < 1000000000
          UNION ALL SELECT 'inventario',     count(*), COALESCE(sum(stock_actual),0)      FROM inventario
          UNION ALL SELECT 'grupo_horario',  count(*), 0 FROM grupo_horario) b ON b.t = a.t
    WHERE a.n <> b.n OR a.v <> b.v;
    IF d IS NOT NULL THEN
        RAISE EXCEPTION 'ABORTA: la reversion movio datos que no debia:%', E'\n' || d;
    END IF;

    -- Kardex cuadrado posicion por posicion.
    SELECT count(*), count(*) FILTER (WHERE i.stock_actual = COALESCE(k.saldo, 0))
      INTO v_pos, v_cuad
    FROM inventario i
    LEFT JOIN (SELECT mi.producto_variante_id v, mi.bodega_id b,
                      sum(mi.cantidad * tm.factor) saldo
               FROM movimiento_inventario mi
               JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id
               GROUP BY 1, 2) k
           ON k.v = i.producto_variante_id AND k.b = i.bodega_id;
    IF v_pos <> v_cuad THEN
        RAISE EXCEPTION 'ABORTA: el kardex quedo descuadrado tras revertir (% de % posiciones).',
                        v_cuad, v_pos;
    END IF;

    -- Encadenamiento leido por (fecha_creacion, id), como manda C-2.
    SELECT count(*) INTO v_rotos FROM (
        SELECT lag(stock_nuevo) OVER w prev, stock_anterior, row_number() OVER w rn
        FROM movimiento_inventario
        WINDOW w AS (PARTITION BY producto_variante_id, bodega_id ORDER BY fecha_creacion, id)) z
    WHERE (rn > 1 AND prev <> stock_anterior) OR (rn = 1 AND stock_anterior <> 0);
    IF v_rotos > 0 THEN
        RAISE EXCEPTION 'ABORTA: % enlaces rotos en el kardex tras revertir.', v_rotos;
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
\echo 'FASE 1 REVERTIDA. La base vuelve al estado previo al script 97.'
\endif

\echo ''
SELECT 'pedido' t, count(*) n FROM pedido
UNION ALL SELECT 'pedido_detalle', count(*) FROM pedido_detalle
UNION ALL SELECT 'movimiento_inventario', count(*) FROM movimiento_inventario
UNION ALL SELECT 'inventario (unidades)', sum(stock_actual) FROM inventario
ORDER BY 1;
