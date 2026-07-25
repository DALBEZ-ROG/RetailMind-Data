-- ============================================================================
-- 73_descuentos_promocion.sql   ·   FASE 3 — hallazgo A9 (+ cupón de los pedidos con solape)
--
-- Aplica el descuento de las PROMOCIONES vigentes a las líneas de venta cuyo
-- producto estaba en promoción en la FECHA DEL PEDIDO, replicando exactamente
-- la regla de marketing/DescuentosService.descuentoPromocional:
--
--   · gana la promoción de mayor `prioridad` (empate: mayor `valor`);
--   · a la ganadora se le SUMAN las demás vigentes solo si la ganadora Y ellas
--     son `acumulable`;
--   · 'porcentaje' = round(subtotal_linea * valor/100, 2);
--     'monto_fijo'  = valor POR UNIDAD (valor * cantidad);
--   · nunca se descuenta más que el bruto de la línea.
--
-- ORDEN DE APLICACIÓN cupón + promoción (regla documentada, sin doble descuento)
-- ---------------------------------------------------------------------------
--   1) La PROMOCIÓN va a la LÍNEA (pedido_detalle.monto_descuento). El trigger
--      fn_recalcular_total_pedido deja pedido.subtotal NETO de promociones.
--   2) El CUPÓN va a la CABECERA (pedido.monto_descuento) y se calcula por su
--      propia regla sobre ese subtotal YA REBAJADO por la promoción — nunca
--      sobre el bruto. Combinan, pero cada capa muerde una base distinta, así
--      que no hay doble descuento sobre el mismo dinero.
--   3) El IVA se reescala UNA sola vez por línea sobre la base realmente
--      cobrada: (subtotal - promoción - prorrateo del cupón), conservando la
--      tasa implícita de la línea. 'envio_gratis' no toca base imponible.
--   4) La FACTURA lleva promoción + prorrateo del cupón en el descuento de
--      línea; sus totales los rehace su trigger SECURITY DEFINER.
--
-- CONSECUENCIA declarada: en los 24 pedidos con promoción Y cupón, el cupón
-- porcentual muerde una base menor, así que su descuento baja. Para que
-- uso_cupon NO vuelva a contradecir al pedido (que es justo el defecto A8),
-- este script ACTUALIZA uso_cupon.monto_descontado al valor recalculado.
--
-- ALCANCE: pedidos con pago 'completado' y al menos una línea con promoción
-- vigente pendiente de aplicar. Los pedidos con cupón SIN promoción ya se
-- resolvieron en el script 72; ningún pedido se toca en las dos fases.
--
-- Requiere 71 y 72 ejecutados. Reversión: 99_revert_descuentos.sql
-- Ejecutar como postgres (superusuario) sobre la BD retailmind.
-- ============================================================================
\set ON_ERROR_STOP on

BEGIN;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.tables
                   WHERE table_schema='seed_backup' AND table_name='dsc72_plan') THEN
        RAISE EXCEPTION 'Falta la fase 2: ejecute 71 y 72 antes del 73';
    END IF;
END $$;

-- ── 1. PLAN ─────────────────────────────────────────────────────────────────

DROP TABLE IF EXISTS seed_backup.dsc73_plan;
CREATE TABLE seed_backup.dsc73_plan AS
WITH elig AS (
    SELECT p.id AS pedido_id, p.costo_envio, p.monto_descuento AS dc_prev
    FROM pedido p
    WHERE EXISTS (SELECT 1 FROM pago g WHERE g.pedido_id = p.id AND g.estado='completado')
),
-- Todas las promociones vigentes a la fecha del pedido para cada línea
cand AS (
    SELECT pd.id AS lin_id, pd.pedido_id, pd.subtotal, pd.cantidad,
           pr.id AS promo_id, pr.nombre, pr.tipo_descuento, pr.valor,
           pr.prioridad, pr.acumulable,
           row_number() OVER (PARTITION BY pd.id
                              ORDER BY pr.prioridad DESC, pr.valor DESC) AS rn
    FROM pedido_detalle pd
    JOIN elig e ON e.pedido_id = pd.pedido_id
    JOIN pedido p ON p.id = pd.pedido_id
    JOIN producto_variante pv ON pv.id = pd.producto_variante_id
    JOIN promocion_producto pp ON pp.producto_id = pv.producto_id
    JOIN promocion pr ON pr.id = pp.promocion_id AND pr.activo
         AND pr.fecha_inicio <= p.fecha_pedido
         AND (pr.fecha_fin IS NULL OR pr.fecha_fin > p.fecha_pedido)
    WHERE pd.monto_descuento = 0          -- no re-aplicar sobre líneas ya rebajadas
),
gana AS (SELECT * FROM cand WHERE rn = 1),
-- Ganadora + acumulables (solo si la ganadora es acumulable)
promo AS (
    SELECT g.lin_id, g.pedido_id,
           LEAST(g.subtotal, SUM(CASE WHEN c.tipo_descuento = 'porcentaje'
                    THEN round(g.subtotal * c.valor / 100, 2)
                    ELSE round(c.valor * g.cantidad, 2) END)) AS promo_desc,
           string_agg(c.nombre, ' + ' ORDER BY c.prioridad DESC, c.valor DESC) AS promociones
    FROM gana g
    JOIN cand c ON c.lin_id = g.lin_id
         AND (c.rn = 1 OR (g.acumulable AND c.acumulable))
    GROUP BY g.lin_id, g.pedido_id, g.subtotal
),
ped AS (SELECT DISTINCT pedido_id FROM promo WHERE promo_desc > 0),
-- TODAS las líneas de esos pedidos (el cupón se prorratea sobre todas)
lin AS (
    SELECT pd.id AS lin_id, pd.pedido_id, pd.subtotal,
           pd.monto_descuento AS desc_prev, pd.monto_impuesto AS imp_prev,
           COALESCE(pm.promo_desc, 0) AS promo_desc, pm.promociones,
           pd.subtotal - pd.monto_descuento - COALESCE(pm.promo_desc, 0) AS neto,
           pd.subtotal - pd.monto_descuento                              AS neto_prev
    FROM pedido_detalle pd
    JOIN ped ON ped.pedido_id = pd.pedido_id
    LEFT JOIN promo pm ON pm.lin_id = pd.id
),
base AS (SELECT pedido_id, sum(neto) AS subtotal_neto FROM lin GROUP BY 1),
-- Cupón RECALCULADO sobre el subtotal ya rebajado por promociones
cup AS (
    SELECT b.pedido_id, uc.id AS uso_id, c.codigo, c.tipo_descuento,
           uc.monto_descontado AS d_prev, b.subtotal_neto,
           CASE c.tipo_descuento
               WHEN 'porcentaje' THEN round(b.subtotal_neto * c.valor / 100, 2)
               WHEN 'monto_fijo' THEN least(c.valor, b.subtotal_neto)
               ELSE e.costo_envio
           END AS d_new
    FROM base b
    JOIN uso_cupon uc ON uc.pedido_id = b.pedido_id
    JOIN cupon c ON c.id = uc.cupon_id
    JOIN elig e ON e.pedido_id = b.pedido_id
    WHERE e.dc_prev = 0                   -- cupón todavía sin aplicar
),
sh AS (
    SELECT l.*, b.subtotal_neto, cu.uso_id, cu.codigo, cu.tipo_descuento,
           cu.d_prev, cu.d_new,
           row_number() OVER (PARTITION BY l.pedido_id ORDER BY l.lin_id) AS rn,
           count(*)     OVER (PARTITION BY l.pedido_id)                   AS nl
    FROM lin l
    JOIN base b ON b.pedido_id = l.pedido_id
    LEFT JOIN cup cu ON cu.pedido_id = l.pedido_id
)
SELECT sh.lin_id, sh.pedido_id, sh.subtotal, sh.desc_prev, sh.imp_prev,
       sh.promo_desc, sh.promociones, sh.neto, sh.neto_prev, sh.subtotal_neto,
       sh.uso_id, sh.codigo, sh.tipo_descuento, sh.d_prev, sh.d_new,
       -- Prorrateo del cupón: SIEMPRE, incluido envio_gratis (emitirFactura no
       -- mira el tipo). La última línea absorbe el redondeo.
       CASE WHEN sh.d_new IS NULL OR sh.subtotal_neto <= 0 THEN 0::numeric
            WHEN sh.rn < sh.nl THEN round(sh.d_new * sh.neto / sh.subtotal_neto, 2)
            ELSE sh.d_new - COALESCE(sum(round(sh.d_new * sh.neto / sh.subtotal_neto, 2))
                     OVER (PARTITION BY sh.pedido_id ORDER BY sh.lin_id
                           ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0)
       END AS cupon_linea
FROM sh;

ALTER TABLE seed_backup.dsc73_plan ADD PRIMARY KEY (lin_id);

-- Parte del cupón que reduce la BASE IMPONIBLE (envio_gratis acredita flete
-- y no toca IVA).
ALTER TABLE seed_backup.dsc73_plan ADD COLUMN cupon_iva numeric;
UPDATE seed_backup.dsc73_plan
   SET cupon_iva = CASE WHEN tipo_descuento = 'envio_gratis' THEN 0 ELSE cupon_linea END;

-- IVA nuevo: una sola reescala desde la base original de la línea a la base
-- realmente cobrada (promoción + cupón), conservando la tasa implícita.
ALTER TABLE seed_backup.dsc73_plan ADD COLUMN imp_new numeric;
UPDATE seed_backup.dsc73_plan SET imp_new =
    CASE WHEN neto_prev <= 0 THEN imp_prev
         ELSE GREATEST(0, round(imp_prev * (neto - cupon_iva) / neto_prev, 2)) END;

-- ── 2. PRE-FLIGHT ───────────────────────────────────────────────────────────

DO $$
DECLARE n bigint; v_p numeric; v_c numeric;
BEGIN
    SELECT count(*) INTO n FROM (
        SELECT pedido_id FROM seed_backup.dsc73_plan
        WHERE d_new IS NOT NULL
        GROUP BY pedido_id, d_new HAVING round(sum(cupon_linea),2) <> round(d_new,2)) x;
    IF n > 0 THEN RAISE EXCEPTION 'Prorrateo del cupón descuadrado en % pedidos', n; END IF;

    SELECT count(*) INTO n FROM seed_backup.dsc73_plan
     WHERE promo_desc < 0 OR promo_desc > subtotal + 0.005
        OR cupon_linea < 0 OR cupon_linea > neto + 0.005
        OR neto < 0 OR imp_new < 0;
    IF n > 0 THEN RAISE EXCEPTION 'Plan inválido en % líneas', n; END IF;

    SELECT count(*) INTO n FROM (
        SELECT pedido_id, max(d_new) d, sum(neto) s FROM seed_backup.dsc73_plan
        GROUP BY 1 HAVING max(d_new) > sum(neto) + 0.005) x;
    IF n > 0 THEN RAISE EXCEPTION '% pedidos con cupón mayor que su subtotal', n; END IF;

    SELECT count(*) INTO n FROM (
        SELECT p.pedido_id FROM (SELECT DISTINCT pedido_id FROM seed_backup.dsc73_plan) p
        JOIN pago g ON g.pedido_id = p.pedido_id AND g.estado='completado'
        GROUP BY 1 HAVING count(*) <> 1) x;
    IF n > 0 THEN RAISE EXCEPTION '% pedidos sin exactamente un pago completado', n; END IF;

    -- ningún pedido tocado ya por la fase 2
    SELECT count(*) INTO n FROM seed_backup.dsc73_plan a
     WHERE EXISTS (SELECT 1 FROM seed_backup.dsc72_plan b WHERE b.pedido_id = a.pedido_id);
    IF n > 0 THEN RAISE EXCEPTION 'SOLAPE DE FASES: % líneas ya tratadas en la fase 2', n; END IF;

    SELECT COALESCE(sum(promo_desc),0) INTO v_p FROM seed_backup.dsc73_plan;
    SELECT COALESCE(sum(d_new),0) INTO v_c
      FROM (SELECT DISTINCT pedido_id, d_new FROM seed_backup.dsc73_plan WHERE d_new IS NOT NULL) y;
    RAISE NOTICE 'FASE 3 · plan: % pedidos, % líneas con promoción ($%), % cupones ($%)',
          (SELECT count(DISTINCT pedido_id) FROM seed_backup.dsc73_plan),
          (SELECT count(*) FROM seed_backup.dsc73_plan WHERE promo_desc > 0), round(v_p,2),
          (SELECT count(DISTINCT pedido_id) FROM seed_backup.dsc73_plan WHERE d_new IS NOT NULL),
          round(v_c,2);
END $$;

-- ── 3. ESCRITURA ────────────────────────────────────────────────────────────

-- 3.1 Promoción a la línea + IVA reescalado en un solo UPDATE
--     (dispara fn_recalcular_total_pedido con los valores finales).
UPDATE pedido_detalle pd
SET monto_descuento = pl.desc_prev + pl.promo_desc,
    monto_impuesto  = pl.imp_new
FROM seed_backup.dsc73_plan pl
WHERE pl.lin_id = pd.id
  AND (pd.monto_descuento, pd.monto_impuesto)
   IS DISTINCT FROM (pl.desc_prev + pl.promo_desc, pl.imp_new);

-- 3.2 Cupón de cabecera sobre el subtotal ya rebajado.
UPDATE pedido p
SET monto_descuento = pl.d_new
FROM (SELECT DISTINCT pedido_id, d_new FROM seed_backup.dsc73_plan
      WHERE d_new IS NOT NULL) pl
WHERE pl.pedido_id = p.id AND p.monto_descuento IS DISTINCT FROM pl.d_new;

-- 3.3 uso_cupon queda coherente con lo realmente descontado (evita reabrir A8).
UPDATE uso_cupon uc
SET monto_descontado = pl.d_new
FROM (SELECT DISTINCT uso_id, d_new FROM seed_backup.dsc73_plan
      WHERE uso_id IS NOT NULL) pl
WHERE pl.uso_id = uc.id AND uc.monto_descontado IS DISTINCT FROM pl.d_new;

-- 3.4 Factura: promoción + prorrateo del cupón en el descuento de línea.
UPDATE factura_venta_detalle fvd
SET monto_descuento = pl.desc_prev + pl.promo_desc + pl.cupon_linea,
    monto_impuesto  = pl.imp_new
FROM seed_backup.dsc73_plan pl, factura_venta fv
WHERE fvd.pedido_detalle_id = pl.lin_id
  AND fv.id = fvd.factura_venta_id
  AND fv.pedido_id = pl.pedido_id
  AND fv.estado <> 'anulada'
  AND (fvd.monto_descuento, fvd.monto_impuesto)
   IS DISTINCT FROM (pl.desc_prev + pl.promo_desc + pl.cupon_linea, pl.imp_new);

-- 3.5 Pago al total ya descontado.
UPDATE pago g
SET monto = p.total
FROM pedido p
WHERE p.id = g.pedido_id
  AND g.estado = 'completado'
  AND p.id IN (SELECT DISTINCT pedido_id FROM seed_backup.dsc73_plan)
  AND g.monto IS DISTINCT FROM p.total;

-- ── 4. VERIFICACIÓN DE CUADRE ───────────────────────────────────────────────

DO $$
DECLARE
    n bigint; v_promo numeric; v_cup numeric; v_iva numeric; v_baja numeric;
BEGIN
    -- 4.1 Nada fuera de las dos fases cambió
    SELECT count(*) INTO n
    FROM pedido p JOIN seed_backup.dsc71_pedido b ON b.id = p.id
    WHERE p.id NOT IN (SELECT pedido_id FROM seed_backup.dsc72_plan)
      AND p.id NOT IN (SELECT pedido_id FROM seed_backup.dsc73_plan)
      AND (p.subtotal, p.monto_descuento, p.monto_impuesto, p.costo_envio, p.total)
       IS DISTINCT FROM (b.subtotal, b.monto_descuento, b.monto_impuesto, b.costo_envio, b.total);
    IF n > 0 THEN RAISE EXCEPTION 'DAÑO COLATERAL: % pedidos ajenos a las fases cambiaron', n; END IF;

    SELECT count(*) INTO n
    FROM pedido_detalle d JOIN seed_backup.dsc71_pedido_detalle b ON b.id = d.id
    WHERE d.id NOT IN (SELECT lin_id FROM seed_backup.dsc72_plan)
      AND d.id NOT IN (SELECT lin_id FROM seed_backup.dsc73_plan)
      AND (d.monto_descuento, d.monto_impuesto)
       IS DISTINCT FROM (b.monto_descuento, b.monto_impuesto);
    IF n > 0 THEN RAISE EXCEPTION 'DAÑO COLATERAL: % líneas ajenas a las fases cambiaron', n; END IF;

    -- 4.2 pago = total
    SELECT count(*) INTO n
    FROM (SELECT DISTINCT pedido_id FROM seed_backup.dsc73_plan) x
    JOIN pedido p ON p.id = x.pedido_id
    LEFT JOIN LATERAL (SELECT COALESCE(sum(monto),0) m FROM pago g
                       WHERE g.pedido_id=p.id AND g.estado='completado') g ON true
    WHERE round(g.m,2) <> round(p.total,2);
    IF n > 0 THEN RAISE EXCEPTION 'CUADRE ROTO: % pedidos con pago <> total', n; END IF;

    -- 4.3 factura = total - envío
    SELECT count(*) INTO n
    FROM (SELECT DISTINCT pedido_id FROM seed_backup.dsc73_plan) x
    JOIN pedido p ON p.id = x.pedido_id
    JOIN factura_venta f ON f.pedido_id = p.id AND f.estado <> 'anulada'
    WHERE round(f.total,2) <> round(p.total - p.costo_envio,2);
    IF n > 0 THEN RAISE EXCEPTION 'CUADRE ROTO: % facturas desalineadas', n; END IF;

    -- 4.4 Sin negativos ni descuento > subtotal
    SELECT count(*) INTO n FROM pedido p
    WHERE p.id IN (SELECT DISTINCT pedido_id FROM seed_backup.dsc73_plan)
      AND (p.total < 0 OR p.monto_descuento > p.subtotal);
    IF n > 0 THEN RAISE EXCEPTION 'INVARIANTE ROTA en % pedidos', n; END IF;

    SELECT count(*) INTO n FROM pedido_detalle d
    WHERE d.id IN (SELECT lin_id FROM seed_backup.dsc73_plan)
      AND d.monto_descuento > d.subtotal;
    IF n > 0 THEN RAISE EXCEPTION 'INVARIANTE ROTA: % líneas con descuento > subtotal', n; END IF;

    -- 4.5 Cuadre: la baja es EXACTAMENTE promoción + cupón + IVA liberado
    SELECT COALESCE(sum(b.total - p.total),0) INTO v_baja
    FROM pedido p JOIN seed_backup.dsc71_pedido b ON b.id = p.id
    WHERE p.id IN (SELECT DISTINCT pedido_id FROM seed_backup.dsc73_plan);

    SELECT COALESCE(sum(promo_desc),0) INTO v_promo FROM seed_backup.dsc73_plan;
    SELECT COALESCE(sum(d_new),0) INTO v_cup
      FROM (SELECT DISTINCT pedido_id, d_new FROM seed_backup.dsc73_plan
            WHERE d_new IS NOT NULL) z;
    SELECT COALESCE(sum(b.monto_impuesto - p.monto_impuesto),0) INTO v_iva
    FROM pedido p JOIN seed_backup.dsc71_pedido b ON b.id = p.id
    WHERE p.id IN (SELECT DISTINCT pedido_id FROM seed_backup.dsc73_plan);

    IF round(v_baja,2) <> round(v_promo + v_cup + v_iva, 2) THEN
        RAISE EXCEPTION 'DESCUADRE: ingreso -$% vs promo $% + cupón $% + IVA $% (dif $%)',
              round(v_baja,2), round(v_promo,2), round(v_cup,2), round(v_iva,2),
              round(v_baja - v_promo - v_cup - v_iva, 2);
    END IF;

    -- 4.6 uso_cupon coherente con pedido.monto_descuento en TODO el sistema
    SELECT count(*) INTO n
    FROM uso_cupon uc JOIN pedido p ON p.id = uc.pedido_id
    WHERE EXISTS (SELECT 1 FROM pago g WHERE g.pedido_id = p.id AND g.estado='completado')
      AND round(uc.monto_descontado,2) <> round(p.monto_descuento,2);
    IF n > 0 THEN RAISE EXCEPTION 'A8 SIGUE ABIERTO: % usos de cupón con monto <> pedido.monto_descuento', n; END IF;

    -- 4.7 Intocables
    SELECT count(*) INTO n FROM seed_backup.dsc71_testigo_intocable t
    JOIN (
        SELECT 'movimiento_inventario_filas' m, count(*)::numeric v FROM movimiento_inventario
        UNION ALL SELECT 'movimiento_inventario_unidades', COALESCE(sum(cantidad),0) FROM movimiento_inventario
        UNION ALL SELECT 'inventario_stock_actual', COALESCE(sum(stock_actual),0) FROM inventario
        UNION ALL SELECT 'inventario_stock_reservado', COALESCE(sum(stock_reservado),0) FROM inventario
        UNION ALL SELECT 'pago_fallido_filas', count(*)::numeric FROM pago WHERE estado='fallido'
        UNION ALL SELECT 'pago_fallido_monto', COALESCE(sum(monto),0) FROM pago WHERE estado='fallido'
        UNION ALL SELECT 'cupon_usos_actuales', COALESCE(sum(usos_actuales),0) FROM cupon
        UNION ALL SELECT 'uso_cupon_filas', count(*)::numeric FROM uso_cupon
        UNION ALL SELECT 'pedido_filas', count(*)::numeric FROM pedido
        UNION ALL SELECT 'pedido_detalle_filas', count(*)::numeric FROM pedido_detalle
        UNION ALL SELECT 'factura_venta_filas', count(*)::numeric FROM factura_venta
        UNION ALL SELECT 'factura_venta_detalle_filas', count(*)::numeric FROM factura_venta_detalle
    ) a ON a.m = t.metrica
    WHERE round(a.v,2) <> round(t.valor,2);
    IF n > 0 THEN RAISE EXCEPTION 'SE MOVIÓ ALGO INTOCABLE (% métricas)', n; END IF;

    RAISE NOTICE 'FASE 3 OK · ingreso -$% = promoción $% + cupón $% + IVA liberado $%',
          round(v_baja,2), round(v_promo,2), round(v_cup,2), round(v_iva,2);
END $$;

COMMIT;

\echo '--- FASE 3 (A9 promocion): resultado ---'
SELECT (SELECT count(DISTINCT pedido_id) FROM seed_backup.dsc73_plan)                      AS pedidos,
       (SELECT count(*) FROM seed_backup.dsc73_plan WHERE promo_desc > 0)                  AS lineas_con_promocion,
       (SELECT round(sum(promo_desc),2) FROM seed_backup.dsc73_plan)                       AS promocion_aplicada,
       (SELECT count(DISTINCT pedido_id) FROM seed_backup.dsc73_plan WHERE d_new IS NOT NULL) AS pedidos_con_cupon,
       (SELECT round(sum(d_new),2) FROM (SELECT DISTINCT pedido_id, d_new
          FROM seed_backup.dsc73_plan WHERE d_new IS NOT NULL) z)                          AS cupon_aplicado,
       (SELECT round(sum(d_prev - d_new),2) FROM (SELECT DISTINCT pedido_id, d_prev, d_new
          FROM seed_backup.dsc73_plan WHERE d_new IS NOT NULL) y)                          AS cupon_reducido_por_promo;

\echo '--- FASE 3: promociones que efectivamente rebajaron venta ---'
SELECT promociones, count(*) AS lineas, round(sum(promo_desc),2) AS descuento
FROM seed_backup.dsc73_plan WHERE promo_desc > 0
GROUP BY 1 ORDER BY 3 DESC;
