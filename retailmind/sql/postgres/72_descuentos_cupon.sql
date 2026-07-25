-- ============================================================================
-- 72_descuentos_cupon.sql   ·   FASE 2 — hallazgo A8
--
-- Hace que los cupones ya registrados en uso_cupon DESCUENTEN de verdad el
-- dinero del pedido, exactamente por el camino del sistema real
-- (marketing/DescuentosService.aplicarCupon + emitirFactura):
--
--   1) pedido.monto_descuento = descuento del cupón  → el trigger BEFORE
--      fn_recalcular_total_cabecera_pedido rehace pedido.total.
--   2) El cupón reduce la BASE IMPONIBLE: se prorratea entre las líneas en
--      proporción a su neto y se reescala pedido_detalle.monto_impuesto
--      conservando la tasa con la que se calculó (la última línea absorbe el
--      redondeo). EXCEPCIÓN: 'envio_gratis' NO toca base imponible.
--   3) factura_venta_detalle.monto_descuento += prorrateo del cupón y
--      monto_impuesto = el nuevo de la línea → el trigger SECURITY DEFINER
--      fn_recalcular_total_factura_venta rehace los totales de la factura.
--   4) pago.monto = pedido.total (tabla independiente, sin trigger de
--      recálculo: es el único ajuste manual del flujo).
--
-- NINGÚN total se escribe a mano: subtotal/monto_impuesto/total del pedido y
-- de la factura los ponen sus triggers. pedido_detalle.subtotal y
-- factura_venta_detalle.subtotal son GENERATED y no se tocan.
--
-- ALCANCE de esta fase: los pedidos con uso de cupón que (a) tienen un pago
-- 'completado', (b) todavía no llevan el descuento aplicado
-- (pedido.monto_descuento = 0) y (c) NO tienen ninguna línea con promoción
-- vigente pendiente de aplicar — esos van en el script 73, donde el cupón se
-- recalcula sobre el subtotal YA rebajado por la promoción. Así ningún pedido
-- se toca dos veces y cada fase cuadra por separado.
--
-- Requiere 71_respaldo_descuentos.sql ejecutado. Reversión: 99_revert_descuentos.sql
-- Ejecutar como postgres (superusuario) sobre la BD retailmind.
-- ============================================================================
\set ON_ERROR_STOP on

BEGIN;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.tables
                   WHERE table_schema='seed_backup' AND table_name='dsc71_pedido') THEN
        RAISE EXCEPTION 'Falta el respaldo: ejecute 71_respaldo_descuentos.sql';
    END IF;
END $$;

-- ── 1. PLAN: cálculo determinista, una sola vez, persistido como evidencia ──

DROP TABLE IF EXISTS seed_backup.dsc72_plan;
CREATE TABLE seed_backup.dsc72_plan AS
WITH elig AS (   -- pedidos efectivamente PAGADOS (excluye pagos fallidos y no cobrados)
    SELECT p.id AS pedido_id, p.costo_envio
    FROM pedido p
    WHERE p.monto_descuento = 0
      AND EXISTS (SELECT 1 FROM pago g
                  WHERE g.pedido_id = p.id AND g.estado = 'completado')
),
-- Pedidos que TIENEN promoción vigente pendiente: se excluyen (van al 73)
con_promo AS (
    SELECT DISTINCT pd.pedido_id
    FROM pedido_detalle pd
    JOIN pedido p  ON p.id = pd.pedido_id
    JOIN producto_variante pv ON pv.id = pd.producto_variante_id
    JOIN promocion_producto pp ON pp.producto_id = pv.producto_id
    JOIN promocion pr ON pr.id = pp.promocion_id AND pr.activo
         AND pr.fecha_inicio <= p.fecha_pedido
         AND (pr.fecha_fin IS NULL OR pr.fecha_fin > p.fecha_pedido)
    WHERE pd.monto_descuento = 0
),
cup AS (   -- un solo cupón por pedido (UNIQUE uso_cupon.pedido_id de facto)
    SELECT uc.id AS uso_id, uc.pedido_id, uc.cupon_id, c.codigo, c.tipo_descuento,
           c.valor, uc.monto_descontado AS d_prev, e.costo_envio
    FROM uso_cupon uc
    JOIN cupon c ON c.id = uc.cupon_id
    JOIN elig  e ON e.pedido_id = uc.pedido_id
    WHERE NOT EXISTS (SELECT 1 FROM con_promo cp WHERE cp.pedido_id = uc.pedido_id)
),
lin AS (
    SELECT pd.id AS lin_id, pd.pedido_id, pd.subtotal, pd.monto_descuento AS desc_prev,
           pd.monto_impuesto AS imp_prev,
           pd.subtotal - pd.monto_descuento AS neto
    FROM pedido_detalle pd
    WHERE pd.pedido_id IN (SELECT pedido_id FROM cup)
),
base AS (SELECT pedido_id, sum(neto) AS subtotal_neto FROM lin GROUP BY 1),
-- Descuento del cupón RECALCULADO por su propia regla sobre el subtotal neto
-- (sin IVA ni envío), igual que DescuentosService.validarCupon
dsc AS (
    SELECT c.*, b.subtotal_neto,
           CASE c.tipo_descuento
               WHEN 'porcentaje' THEN round(b.subtotal_neto * c.valor / 100, 2)
               WHEN 'monto_fijo' THEN least(c.valor, b.subtotal_neto)
               ELSE c.costo_envio                      -- envio_gratis
           END AS d_new
    FROM cup c JOIN base b ON b.pedido_id = c.pedido_id
),
sh AS (
    SELECT l.*, d.uso_id, d.cupon_id, d.codigo, d.tipo_descuento, d.d_prev, d.d_new,
           d.subtotal_neto,
           row_number() OVER (PARTITION BY l.pedido_id ORDER BY l.lin_id) AS rn,
           count(*)     OVER (PARTITION BY l.pedido_id)                   AS nl
    FROM lin l JOIN dsc d ON d.pedido_id = l.pedido_id
)
SELECT sh.lin_id, sh.pedido_id, sh.uso_id, sh.cupon_id, sh.codigo, sh.tipo_descuento,
       sh.subtotal, sh.desc_prev, sh.imp_prev, sh.neto, sh.subtotal_neto,
       sh.d_prev, sh.d_new,
       -- Prorrateo del cupón por línea; la última absorbe el redondeo.
       -- Se prorratea SIEMPRE, incluido envio_gratis: es lo que hace
       -- VentasService.emitirFactura (lee pedido.monto_descuento sin mirar el
       -- tipo) y es lo que mantiene factura.total = pedido.total - costo_envio.
       CASE WHEN sh.subtotal_neto <= 0 THEN 0::numeric
            WHEN sh.rn < sh.nl THEN round(sh.d_new * sh.neto / sh.subtotal_neto, 2)
            ELSE sh.d_new - COALESCE(sum(round(sh.d_new * sh.neto / sh.subtotal_neto, 2))
                     OVER (PARTITION BY sh.pedido_id ORDER BY sh.lin_id
                           ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0)
       END AS cupon_linea
FROM sh;

ALTER TABLE seed_backup.dsc72_plan ADD PRIMARY KEY (lin_id);

-- Parte del cupón que reduce la BASE IMPONIBLE: todo salvo envio_gratis, que
-- acredita el flete y no toca IVA (DescuentosService.aplicarCupon).
ALTER TABLE seed_backup.dsc72_plan ADD COLUMN cupon_iva numeric;
UPDATE seed_backup.dsc72_plan
   SET cupon_iva = CASE WHEN tipo_descuento = 'envio_gratis' THEN 0 ELSE cupon_linea END;

-- monto_impuesto nuevo: escala el IVA a la base realmente cobrada,
-- conservando la tasa implícita con la que se calculó la línea
ALTER TABLE seed_backup.dsc72_plan ADD COLUMN imp_new numeric;
UPDATE seed_backup.dsc72_plan SET imp_new =
    CASE WHEN neto <= 0 THEN imp_prev
         ELSE GREATEST(0, round(imp_prev * (neto - cupon_iva) / neto, 2)) END;

-- ── 2. PRE-FLIGHT: el plan debe ser sano ANTES de escribir nada ─────────────

DO $$
DECLARE n bigint; v numeric;
BEGIN
    -- prorrateo exacto: la suma de las partes es el descuento entero
    SELECT count(*) INTO n FROM (
        SELECT pedido_id FROM seed_backup.dsc72_plan
        GROUP BY pedido_id, d_new HAVING round(sum(cupon_linea),2) <> round(d_new,2)) x;
    IF n > 0 THEN RAISE EXCEPTION 'Prorrateo del cupón descuadrado en % pedidos', n; END IF;

    -- sin promoción en juego, la base no cambió: el cupón debe ser el registrado
    SELECT count(*) INTO n FROM seed_backup.dsc72_plan WHERE round(d_new,2) <> round(d_prev,2);
    IF n > 0 THEN
        RAISE EXCEPTION 'El cupón recalculado difiere del registrado en % líneas (regla inconsistente)', n;
    END IF;

    SELECT count(*) INTO n FROM seed_backup.dsc72_plan
     WHERE cupon_linea < 0 OR cupon_linea > neto + 0.005 OR d_new <= 0 OR d_new > subtotal_neto + 0.005;
    IF n > 0 THEN RAISE EXCEPTION 'Plan inválido (descuento negativo o mayor que la base) en % líneas', n; END IF;

    -- cada pedido del plan tiene exactamente UN pago completado
    SELECT count(*) INTO n FROM (
        SELECT p.pedido_id FROM (SELECT DISTINCT pedido_id FROM seed_backup.dsc72_plan) p
        JOIN pago g ON g.pedido_id = p.pedido_id AND g.estado='completado'
        GROUP BY 1 HAVING count(*) <> 1) x;
    IF n > 0 THEN RAISE EXCEPTION '% pedidos del plan no tienen exactamente un pago completado', n; END IF;

    SELECT count(DISTINCT pedido_id) INTO n FROM seed_backup.dsc72_plan;
    SELECT round(sum(d_new),2) INTO v
      FROM (SELECT DISTINCT pedido_id, d_new FROM seed_backup.dsc72_plan) y;
    RAISE NOTICE 'FASE 2 · plan: % pedidos, % líneas, cupón a aplicar $%',
          n, (SELECT count(*) FROM seed_backup.dsc72_plan), v;
END $$;

-- ── 3. ESCRITURA (los triggers hacen los totales) ───────────────────────────

-- 3.1 IVA por línea reescalado a la base efectivamente cobrada.
--     Dispara fn_recalcular_total_pedido → rehace pedido.subtotal/impuesto/total.
UPDATE pedido_detalle pd
SET monto_impuesto = pl.imp_new
FROM seed_backup.dsc72_plan pl
WHERE pl.lin_id = pd.id AND pd.monto_impuesto IS DISTINCT FROM pl.imp_new;

-- 3.2 Cupón de cabecera.
--     Dispara fn_recalcular_total_cabecera_pedido → rehace pedido.total.
UPDATE pedido p
SET monto_descuento = pl.d_new
FROM (SELECT DISTINCT pedido_id, d_new FROM seed_backup.dsc72_plan) pl
WHERE pl.pedido_id = p.id;

-- 3.3 Factura: el cupón prorrateado entra al descuento de línea y el IVA de la
--     línea se copia del pedido. Dispara fn_recalcular_total_factura_venta.
UPDATE factura_venta_detalle fvd
SET monto_descuento = pl.desc_prev + pl.cupon_linea,
    monto_impuesto  = pl.imp_new
FROM seed_backup.dsc72_plan pl, factura_venta fv
WHERE fvd.pedido_detalle_id = pl.lin_id
  AND fv.id = fvd.factura_venta_id
  AND fv.pedido_id = pl.pedido_id
  AND fv.estado <> 'anulada'
  AND (fvd.monto_descuento, fvd.monto_impuesto)
   IS DISTINCT FROM (pl.desc_prev + pl.cupon_linea, pl.imp_new);

-- 3.4 Pago: el cobro baja al total ya descontado (único ajuste manual).
UPDATE pago g
SET monto = p.total
FROM pedido p
WHERE p.id = g.pedido_id
  AND g.estado = 'completado'
  AND p.id IN (SELECT DISTINCT pedido_id FROM seed_backup.dsc72_plan)
  AND g.monto IS DISTINCT FROM p.total;

-- ── 4. VERIFICACIÓN DE CUADRE (aborta la fase si falla) ─────────────────────

DO $$
DECLARE
    n              bigint;
    v_cupon        numeric;
    v_iva          numeric;
    v_baja         numeric;
    v_esperado     numeric;
BEGIN
    -- 4.1 Ningún pedido fuera del plan cambió
    SELECT count(*) INTO n
    FROM pedido p JOIN seed_backup.dsc71_pedido b ON b.id = p.id
    WHERE p.id NOT IN (SELECT DISTINCT pedido_id FROM seed_backup.dsc72_plan)
      AND (p.subtotal, p.monto_descuento, p.monto_impuesto, p.costo_envio, p.total)
       IS DISTINCT FROM (b.subtotal, b.monto_descuento, b.monto_impuesto, b.costo_envio, b.total);
    IF n > 0 THEN RAISE EXCEPTION 'DAÑO COLATERAL: % pedidos fuera del plan cambiaron', n; END IF;

    SELECT count(*) INTO n
    FROM pedido_detalle d JOIN seed_backup.dsc71_pedido_detalle b ON b.id = d.id
    WHERE d.id NOT IN (SELECT lin_id FROM seed_backup.dsc72_plan)
      AND (d.monto_descuento, d.monto_impuesto)
       IS DISTINCT FROM (b.monto_descuento, b.monto_impuesto);
    IF n > 0 THEN RAISE EXCEPTION 'DAÑO COLATERAL: % líneas fuera del plan cambiaron', n; END IF;

    -- 4.2 pago = total del pedido en cada pedido del plan
    SELECT count(*) INTO n
    FROM (SELECT DISTINCT pedido_id FROM seed_backup.dsc72_plan) x
    JOIN pedido p ON p.id = x.pedido_id
    LEFT JOIN LATERAL (SELECT COALESCE(sum(monto),0) m FROM pago g
                       WHERE g.pedido_id = p.id AND g.estado='completado') g ON true
    WHERE round(g.m,2) <> round(p.total,2);
    IF n > 0 THEN RAISE EXCEPTION 'CUADRE ROTO: % pedidos con pago <> total', n; END IF;

    -- 4.3 factura = total del pedido - costo de envío (la factura NO factura el flete)
    SELECT count(*) INTO n
    FROM (SELECT DISTINCT pedido_id FROM seed_backup.dsc72_plan) x
    JOIN pedido p ON p.id = x.pedido_id
    JOIN factura_venta f ON f.pedido_id = p.id AND f.estado <> 'anulada'
    WHERE round(f.total,2) <> round(p.total - p.costo_envio, 2);
    IF n > 0 THEN RAISE EXCEPTION 'CUADRE ROTO: % facturas desalineadas del pedido', n; END IF;

    -- 4.4 Sin totales negativos ni descuento mayor que el subtotal
    SELECT count(*) INTO n FROM pedido p
    WHERE p.id IN (SELECT DISTINCT pedido_id FROM seed_backup.dsc72_plan)
      AND (p.total < 0 OR p.monto_descuento > p.subtotal);
    IF n > 0 THEN RAISE EXCEPTION 'INVARIANTE ROTA: % pedidos con total<0 o descuento>subtotal', n; END IF;

    -- 4.5 La baja de ingreso es EXACTAMENTE cupón + IVA liberado
    SELECT COALESCE(sum(b.total - p.total),0) INTO v_baja
    FROM pedido p JOIN seed_backup.dsc71_pedido b ON b.id = p.id
    WHERE p.id IN (SELECT DISTINCT pedido_id FROM seed_backup.dsc72_plan);

    SELECT COALESCE(sum(d_new),0) INTO v_cupon
    FROM (SELECT DISTINCT pedido_id, d_new FROM seed_backup.dsc72_plan) z;

    SELECT COALESCE(sum(b.monto_impuesto - p.monto_impuesto),0) INTO v_iva
    FROM pedido p JOIN seed_backup.dsc71_pedido b ON b.id = p.id
    WHERE p.id IN (SELECT DISTINCT pedido_id FROM seed_backup.dsc72_plan);

    v_esperado := v_cupon + v_iva;
    IF round(v_baja,2) <> round(v_esperado,2) THEN
        RAISE EXCEPTION 'DESCUADRE: el ingreso bajó $% pero cupón+IVA suman $% (dif $%)',
              round(v_baja,2), round(v_esperado,2), round(v_baja - v_esperado,2);
    END IF;

    -- 4.6 Nada de lo intocable se movió
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
    ) a ON a.m = t.metrica
    WHERE round(a.v,2) <> round(t.valor,2);
    IF n > 0 THEN RAISE EXCEPTION 'SE MOVIÓ ALGO INTOCABLE (% métricas): kardex/inventario/pagos fallidos', n; END IF;

    RAISE NOTICE 'FASE 2 OK · ingreso -$% = cupón $% + IVA liberado $%',
          round(v_baja,2), round(v_cupon,2), round(v_iva,2);
END $$;

COMMIT;

\echo '--- FASE 2 (A8 cupon): resultado ---'
WITH ped AS (SELECT DISTINCT pedido_id, tipo_descuento, d_new FROM seed_backup.dsc72_plan)
SELECT (SELECT count(*) FROM ped)                            AS pedidos,
       (SELECT count(*) FROM seed_backup.dsc72_plan)          AS lineas,
       (SELECT round(sum(d_new),2) FROM ped)                  AS cupon_aplicado,
       (SELECT round(sum(b.total - p.total),2)
          FROM pedido p JOIN seed_backup.dsc71_pedido b ON b.id = p.id
         WHERE p.id IN (SELECT pedido_id FROM ped))           AS baja_ingreso,
       (SELECT round(sum(b.monto_impuesto - p.monto_impuesto),2)
          FROM pedido p JOIN seed_backup.dsc71_pedido b ON b.id = p.id
         WHERE p.id IN (SELECT pedido_id FROM ped))           AS iva_liberado;

\echo '--- FASE 2: cupon aplicado por tipo ---'
SELECT tipo_descuento, count(*) AS pedidos, round(sum(d_new),2) AS monto
FROM (SELECT DISTINCT pedido_id, tipo_descuento, d_new FROM seed_backup.dsc72_plan) t
GROUP BY 1 ORDER BY 1;
