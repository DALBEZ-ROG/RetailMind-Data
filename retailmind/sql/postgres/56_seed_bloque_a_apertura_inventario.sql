-- =====================================================================
-- 56_seed_bloque_a_apertura_inventario.sql
-- SIEMBRA DE VOLUMEN HISTORICO — BLOQUE A / Parte 2: APERTURA DE STOCK
-- ---------------------------------------------------------------------
-- PROBLEMA: hoy inventario.stock_actual (base sintetica del ETL, 100 plano
--   en el 97 %) NO tiene respaldo en el kardex: la invariante "suma
--   algebraica de los movimientos = stock_actual" NO se cumple.
--
-- CRITERIO DE STOCK DE APERTURA (documentado):
--   Para cada fila de inventario (variante, bodega) se define
--       base = stock_actual - net_real
--   donde net_real = suma(factor*cantidad) de los movimientos REALES
--   preexistentes de ese par. Ese "base" es exactamente el stock de
--   apertura implicito que el ETL cargo sin movimiento. Se respalda con
--   UN movimiento de apertura (entrada_ajuste, referencia_tipo
--   'inventario_inicial') fechado 2025-01-01, con stock_anterior=0 y
--   stock_nuevo=base. NO se modifica stock_actual: tras insertar la
--   apertura, sum(movimientos) = base + net_real = stock_actual EXACTO.
--
--   * Los 99 movimientos reales (jul-2026) se preservan intactos y
--     encajan: en bodega 4 las cadenas demo arrancan justo en 'base'
--     (su primer stock_anterior), de modo que la apertura enlaza sin
--     discontinuidad; en bodega 3 base=0 (ya cuadraban) y se omiten.
--   * No se crea apertura donde base<=0 (CHECK cantidad>0 / stock>=0).
--   * Valorizacion: costo_unitario = producto_variante.costo (habilita
--     OTD-INV-07/09 valor de inventario historico).
--
-- Reversion: borrar movimiento_inventario WHERE referencia_tipo
--   ='inventario_inicial'. NO altera stock_actual (la apertura no lo movio).
-- Idempotencia: clave configuracion_tienda 'seed_ba_56_apertura'.
-- =====================================================================

DO $$
DECLARE
    v_ins bigint;
    v_thr jsonb;
BEGIN
    IF EXISTS (SELECT 1 FROM configuracion_tienda WHERE clave = 'seed_ba_56_apertura') THEN
        RAISE NOTICE 'Bloque A / 56 (apertura) ya sembrado; se omite.';
        RETURN;
    END IF;

    v_thr := jsonb_build_object('movimiento_inventario', (SELECT COALESCE(max(id),0) FROM movimiento_inventario));

    WITH net AS (
        SELECT mi.producto_variante_id AS pv, mi.bodega_id AS bod,
               SUM(CASE WHEN tm.factor = 1 THEN mi.cantidad ELSE -mi.cantidad END) AS net_real
        FROM movimiento_inventario mi
        JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id
        WHERE mi.referencia_tipo IS DISTINCT FROM 'inventario_inicial'
        GROUP BY 1,2
    ),
    apertura AS (
        SELECT i.producto_variante_id AS pv, i.bodega_id AS bod,
               (i.stock_actual - COALESCE(n.net_real,0)) AS base,
               COALESCE(pv.costo,0)::numeric AS costo
        FROM inventario i
        LEFT JOIN net n ON n.pv = i.producto_variante_id AND n.bod = i.bodega_id
        JOIN producto_variante pv ON pv.id = i.producto_variante_id
    )
    INSERT INTO movimiento_inventario
        (producto_variante_id, bodega_id, tipo_movimiento_id, usuario_id, cantidad,
         stock_anterior, stock_nuevo, costo_unitario, referencia_tipo, referencia_id,
         observacion, fecha_creacion)
    SELECT a.pv, a.bod, 4, 2, a.base,
           0, a.base, a.costo, 'inventario_inicial', NULL,
           '[SEED-BA] carga inicial de inventario 2025', timestamptz '2025-01-01 08:00:00-05'
    FROM apertura a
    WHERE a.base > 0;

    GET DIAGNOSTICS v_ins = ROW_COUNT;

    INSERT INTO configuracion_tienda (clave, valor, tipo_dato, descripcion, fecha_creacion)
    VALUES ('seed_ba_56_apertura', v_thr::text, 'json',
            'Bloque A/56: apertura de inventario. Reversion: DELETE movimiento_inventario WHERE referencia_tipo=inventario_inicial (no toca stock_actual).', now());

    RAISE NOTICE 'Bloque A / 56 (apertura) OK. Movimientos de apertura insertados: %', v_ins;
END $$;
