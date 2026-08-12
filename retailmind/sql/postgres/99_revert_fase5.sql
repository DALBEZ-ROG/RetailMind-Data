-- ============================================================================
-- 99_revert_fase5.sql — RetailMind · reversión de la Fase 5 (los envíos)
--
--   Ensayo en seco (ejecuta, VERIFICA y deshace):
--   docker compose exec -T postgres psql -U postgres -d retailmind \
--       -v ON_ERROR_STOP=1 -v ensayo=1 < retailmind/sql/postgres/99_revert_fase5.sql
--
--   En firme: lo mismo sin `-v ensayo=1`.
--
-- ---------------------------------------------------------------------------
-- POR QUÉ ESTA REVERSIÓN ES ESPECIALMENTE LIMPIA
-- ---------------------------------------------------------------------------
-- Los envíos son HOJAS del modelo: cuelgan de pedidos que ya existían y nada
-- cuelga de ellos salvo su propio seguimiento, su detalle y sus novedades, que
-- se borran aquí. La Fase 5 **no tocó stock, kardex ni inventario** —los
-- pedidos ya habían movido el suyo—, así que no hay saldo que restaurar ni
-- cadena que reencadenar: el kardex ni se entera.
--
-- Todo lo escrito vive en tramos reservados:
--     envio              >= 2.200.000.000
--     envio_detalle      >= 2.300.000.000
--     seguimiento_envio  >= 2.400.000.000
--     novedad_envio      >= 2.500.000.000
--
-- Los 2.872 envíos originales (ids <= 2.945) y sus 8.578 seguimientos quedan
-- intactos por construcción: ninguna condición los alcanza.
--
-- ORDEN DE BORRADO: al revés de las dependencias. `envio_detalle`,
-- `seguimiento_envio` y `novedad_envio` referencian a `envio`; las dos
-- primeras con ON DELETE CASCADE y la tercera SIN cascada, así que la novedad
-- tiene que irse ANTES que su envío o la FK lo impide.
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
CREATE TEMP TABLE rev5_antes ON COMMIT DROP AS
SELECT 'pedido' t, count(*) n, COALESCE(round(sum(total),2),0) v FROM pedido
UNION ALL SELECT 'pedido_detalle', count(*), COALESCE(round(sum(subtotal),2),0) FROM pedido_detalle
UNION ALL SELECT 'factura_venta', count(*), COALESCE(round(sum(total),2),0) FROM factura_venta
UNION ALL SELECT 'pago', count(*), COALESCE(round(sum(monto),2),0) FROM pago
UNION ALL SELECT 'movimiento_inventario', count(*), COALESCE(sum(cantidad),0) FROM movimiento_inventario
UNION ALL SELECT 'inventario', count(*), COALESCE(sum(stock_actual),0) FROM inventario
UNION ALL SELECT 'envio ORIGINALES', count(*), COALESCE(round(sum(costo),2),0)
  FROM envio WHERE id < 2200000000
UNION ALL SELECT 'seguimiento ORIGINALES', count(*), 0
  FROM seguimiento_envio WHERE id < 2400000000
UNION ALL SELECT 'grupo_horario', count(*), 0 FROM grupo_horario;

-- ── 1. Borrado ──────────────────────────────────────────────────────────────
DELETE FROM novedad_envio     WHERE id >= 2500000000;
DELETE FROM seguimiento_envio WHERE id >= 2400000000;
DELETE FROM envio_detalle     WHERE id >= 2300000000;
DELETE FROM envio             WHERE id >= 2200000000;

DELETE FROM carga_fase_registro  WHERE fase = 'fase5';
DELETE FROM carga_fase_parametro WHERE fase = 'fase5';

-- ── 2. Autoverificación ─────────────────────────────────────────────────────
DO $verif$
DECLARE
    n bigint; restos text := ''; d text;
    v_pos bigint; v_cuad bigint;
BEGIN
    SELECT count(*) INTO n FROM envio             WHERE id >= 2200000000;
    IF n > 0 THEN restos := restos || format('  envio: %s%s', n, E'\n'); END IF;
    SELECT count(*) INTO n FROM envio_detalle     WHERE id >= 2300000000;
    IF n > 0 THEN restos := restos || format('  envio_detalle: %s%s', n, E'\n'); END IF;
    SELECT count(*) INTO n FROM seguimiento_envio WHERE id >= 2400000000;
    IF n > 0 THEN restos := restos || format('  seguimiento_envio: %s%s', n, E'\n'); END IF;
    SELECT count(*) INTO n FROM novedad_envio     WHERE id >= 2500000000;
    IF n > 0 THEN restos := restos || format('  novedad_envio: %s%s', n, E'\n'); END IF;
    IF restos <> '' THEN
        RAISE EXCEPTION 'ABORTA: quedan filas de la Fase 5 sin borrar:%', E'\n' || restos;
    END IF;

    SELECT string_agg(format('  %s: %s/%s -> %s/%s', a.t, a.n, a.v, b.n, b.v), E'\n') INTO d
    FROM rev5_antes a
    JOIN (SELECT 'pedido' t, count(*) n, COALESCE(round(sum(total),2),0) v FROM pedido
          UNION ALL SELECT 'pedido_detalle', count(*), COALESCE(round(sum(subtotal),2),0) FROM pedido_detalle
          UNION ALL SELECT 'factura_venta', count(*), COALESCE(round(sum(total),2),0) FROM factura_venta
          UNION ALL SELECT 'pago', count(*), COALESCE(round(sum(monto),2),0) FROM pago
          UNION ALL SELECT 'movimiento_inventario', count(*), COALESCE(sum(cantidad),0) FROM movimiento_inventario
          UNION ALL SELECT 'inventario', count(*), COALESCE(sum(stock_actual),0) FROM inventario
          UNION ALL SELECT 'envio ORIGINALES', count(*), COALESCE(round(sum(costo),2),0)
            FROM envio WHERE id < 2200000000
          UNION ALL SELECT 'seguimiento ORIGINALES', count(*), 0
            FROM seguimiento_envio WHERE id < 2400000000
          UNION ALL SELECT 'grupo_horario', count(*), 0 FROM grupo_horario) b ON b.t = a.t
    WHERE a.n <> b.n OR a.v <> b.v;
    IF d IS NOT NULL THEN
        RAISE EXCEPTION 'ABORTA: la reversion movio datos que no debia:%', E'\n' || d;
    END IF;

    -- El kardex no se tocó en la carga, así que tampoco puede moverse aquí.
    SELECT count(*), count(*) FILTER (WHERE i.stock_actual = COALESCE(k.saldo, 0))
      INTO v_pos, v_cuad
    FROM inventario i
    LEFT JOIN (SELECT mi.producto_variante_id v, mi.bodega_id b, sum(mi.cantidad * tm.factor) saldo
               FROM movimiento_inventario mi JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id
               GROUP BY 1, 2) k ON k.v = i.producto_variante_id AND k.b = i.bodega_id;
    IF v_pos <> v_cuad THEN
        RAISE EXCEPTION 'ABORTA: kardex descuadrado (% de %).', v_cuad, v_pos;
    END IF;

    RAISE NOTICE 'Reversion verificada: 0 residuos, % de % posiciones cuadradas, '
                 'y lo preexistente intacto al centavo (envios originales incluidos).',
                 v_cuad, v_pos;
END
$verif$;

\if :ensayo
ROLLBACK;
\echo ''
\echo 'ENSAYO EN SECO: la reversion se ejecuto ENTERA, se VERIFICO y se deshizo.'
\else
COMMIT;
\echo ''
\echo 'FASE 5 REVERTIDA.'
\endif

\echo ''
SELECT (SELECT count(*) FROM envio) envios, (SELECT count(*) FROM seguimiento_envio) seguimientos,
       (SELECT count(*) FROM novedad_envio) novedades, (SELECT count(*) FROM envio_detalle) detalle;
