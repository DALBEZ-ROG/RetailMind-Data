-- ============================================================================
-- 78_rebalanceo_kardex.sql
-- REBALANCEO DEL ABASTECIMIENTO — CAPA 5 y 6: RECOMPOSICION DEL KARDEX
--
-- Es la capa que de verdad cambia el ORIGEN del stock. Por cada unidad migrada:
--   * se ANADE su movimiento 'entrada_compra' con fecha ANTERIOR a la primera
--     salida de la variante (referencia a la recepcion creada en el script 76),
--   * se RETIRA la misma unidad del asiento de apertura
--     ('entrada_ajuste' / referencia_tipo='inventario_inicial') del 2025-01-01,
--   * y se recalcula stock_anterior/stock_nuevo de TODA la cadena afectada en
--     orden cronologico (fecha_creacion, id), que es el orden con el que el
--     kardex del sistema esta encadenado.
-- La apertura que se conserva deja de estar toda en el 2025-01-01: se reparte
-- entre el 2 y el 11 de enero de 2025 (relato de "carga inicial de inventario
-- al arrancar operaciones"), siempre ANTES del primer movimiento real de esa
-- variante.
--
-- INVARIANTES (verificadas al final; la transaccion aborta si alguna falla):
--   * inventario.stock_actual NO se escribe: ni una fila cambia de valor.
--   * Para cada (variante, bodega): suma algebraica del kardex = stock_actual.
--   * Ningun stock_anterior/stock_nuevo negativo en ningun punto de la linea de
--     tiempo (ademas del CHECK del motor, se comprueba explicitamente).
--   * costo_unitario de las entradas nuevas = precio de compra de SU orden
--     (dato historico legitimo), no el costo vigente de la variante.
--   * Las SALIDAS no se crean, no se borran y no cambian de cantidad ni de
--     fecha: solo se reencadena su saldo corrido donde la composicion cambio.
--
-- Marca/reversion: observacion '[SEED-REB]' + 99_revert_abastecimiento.sql.
-- Idempotencia: clave configuracion_tienda 'seed_reb78_kardex'.
-- Ejecutar como postgres (superusuario).
-- ============================================================================
\set ON_ERROR_STOP on

BEGIN;

DO $$
DECLARE
    n_ent bigint; n_del bigint; n_red bigint; n_rec bigint;
BEGIN
    IF EXISTS (SELECT 1 FROM configuracion_tienda WHERE clave = 'seed_reb78_kardex') THEN
        RAISE NOTICE 'Rebalanceo / 78 (kardex) ya aplicado; se omite.';
        RETURN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM configuracion_tienda WHERE clave = 'seed_reb77_facturas') THEN
        RAISE EXCEPTION 'Falta la capa 3-4: ejecute antes 77_rebalanceo_facturas_pagos.sql';
    END IF;

    -- ── 5.1 Entradas por compra (saldo corrido provisional; se reencadena en 5.4)
    WITH ins AS (
        INSERT INTO movimiento_inventario
            (producto_variante_id, bodega_id, tipo_movimiento_id, usuario_id, cantidad,
             stock_anterior, stock_nuevo, costo_unitario, referencia_tipo, referencia_id,
             observacion, fecha_creacion)
        SELECT l.variante_id, 4, 1, 9, l.cantidad,
               0, l.cantidad, l.precio, 'recepcion_mercancia', l.recepcion_mercancia_id,
               '[SEED-REB] entrada por compra', (l.fecha_recep::timestamptz + time '11:05')
        FROM seed_backup.reb75_linea l
        ORDER BY l.fecha_recep, l.variante_id, l.lote
        RETURNING id, producto_variante_id, fecha_creacion
    )
    SELECT count(*) INTO n_ent FROM ins;

    -- enlazar el movimiento creado con su linea del plan (para trazabilidad)
    UPDATE seed_backup.reb75_linea l
       SET movimiento_id = m.id
    FROM movimiento_inventario m
    WHERE m.observacion = '[SEED-REB] entrada por compra'
      AND m.producto_variante_id = l.variante_id
      AND m.fecha_creacion = (l.fecha_recep::timestamptz + time '11:05')
      AND m.cantidad = l.cantidad
      AND l.movimiento_id IS NULL;

    -- ── 5.2 Retirar la apertura de las variantes que ahora se abastecen ────
    DELETE FROM movimiento_inventario mi
    USING seed_backup.reb75_variante t
    WHERE mi.referencia_tipo = 'inventario_inicial'
      AND mi.id = t.apertura_mov_id
      AND t.grupo IN ('tardia','sin_salida');
    GET DIAGNOSTICS n_del = ROW_COUNT;

    -- ── 5.3 La apertura conservada deja de ser un pico de un solo dia ──────
    UPDATE movimiento_inventario mi
       SET fecha_creacion = greatest(
               timestamptz '2025-01-01 08:00:00-05',
               least(
                   (date '2025-01-02' + (abs(hashtext('ap#' || t.variante_id::text)) % 10))::timestamptz
                       + time '08:00'
                       + ((abs(hashtext('ah#' || t.variante_id::text)) % 9) * interval '1 hour')
                       + ((abs(hashtext('am#' || t.variante_id::text)) % 60) * interval '1 minute'),
                   COALESCE(t.primer_mov_no_apertura - interval '2 hours', 'infinity'::timestamptz))),
           observacion = '[SEED-REB] carga inicial de inventario 2025'
    FROM seed_backup.reb75_variante t
    WHERE mi.id = t.apertura_mov_id AND t.grupo = 'temprana';
    GET DIAGNOSTICS n_red = ROW_COUNT;

    -- ── 5.4 Reencadenar stock_anterior/stock_nuevo de TODAS las cadenas ────
    -- El kardex del sistema se encadena por (fecha_creacion, id) y toda cadena
    -- arranca en 0. Se recalcula el saldo corrido completo; solo se escriben
    -- las filas cuyo saldo realmente cambia.
    WITH ord AS (
        SELECT mi.id,
               CASE WHEN tm.factor = 1 THEN mi.cantidad ELSE -mi.cantidad END AS delta,
               sum(CASE WHEN tm.factor = 1 THEN mi.cantidad ELSE -mi.cantidad END)
                   OVER (PARTITION BY mi.producto_variante_id, mi.bodega_id
                         ORDER BY mi.fecha_creacion, mi.id
                         ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS acum
        FROM movimiento_inventario mi
        JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id
    )
    UPDATE movimiento_inventario m
       SET stock_anterior = (ord.acum - ord.delta)::int,
           stock_nuevo    = ord.acum::int
    FROM ord
    WHERE ord.id = m.id
      AND (m.stock_anterior, m.stock_nuevo)
       IS DISTINCT FROM ((ord.acum - ord.delta)::int, ord.acum::int);
    GET DIAGNOSTICS n_rec = ROW_COUNT;

    INSERT INTO configuracion_tienda (clave, valor, tipo_dato, descripcion, fecha_creacion)
    VALUES ('seed_reb78_kardex',
            jsonb_build_object('entradas_compra_nuevas',n_ent,'aperturas_retiradas',n_del,
                               'aperturas_redistribuidas',n_red,'filas_reencadenadas',n_rec)::text,
            'json',
            'Rebalanceo abastecimiento/78: kardex recompuesto. Reversion: 99_revert_abastecimiento.sql', now());

    RAISE NOTICE 'Rebalanceo / 78 OK. Entradas nuevas: %, aperturas retiradas: %, aperturas redistribuidas: %, filas reencadenadas: %',
                 n_ent, n_del, n_red, n_rec;
END $$;

-- ── VERIFICACION DE LA CAPA (aborta la transaccion si algo no cuadra) ──────
DO $$
DECLARE v_bad bigint; v_n bigint;
BEGIN
    -- (a) kardex = stock_actual en TODAS las parejas (variante, bodega)
    SELECT count(*) INTO v_bad FROM (
        SELECT mi.producto_variante_id v, mi.bodega_id b,
               sum(CASE WHEN tm.factor = 1 THEN mi.cantidad ELSE -mi.cantidad END) neto
        FROM movimiento_inventario mi JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id
        GROUP BY 1,2) s
    JOIN inventario i ON i.producto_variante_id = s.v AND i.bodega_id = s.b
    WHERE i.stock_actual <> s.neto;
    IF v_bad > 0 THEN RAISE EXCEPTION 'CAPA 5-6: % parejas con kardex <> stock_actual', v_bad; END IF;

    -- (a bis) el stock_actual es IDENTICO al del respaldo previo
    SELECT count(*) INTO v_bad FROM inventario i
    JOIN seed_backup.reb74_inventario b ON b.id = i.id
    WHERE i.stock_actual <> b.stock_actual;
    IF v_bad > 0 THEN RAISE EXCEPTION 'CAPA 5-6: % filas de inventario cambiaron de stock', v_bad; END IF;
    SELECT count(*) INTO v_bad FROM inventario;
    IF v_bad <> (SELECT count(*) FROM seed_backup.reb74_inventario) THEN
        RAISE EXCEPTION 'CAPA 5-6: cambio el numero de filas de inventario';
    END IF;

    -- (b) ningun saldo negativo en ningun punto de la linea de tiempo
    SELECT count(*) INTO v_bad FROM movimiento_inventario
    WHERE stock_anterior < 0 OR stock_nuevo < 0;
    IF v_bad > 0 THEN RAISE EXCEPTION 'CAPA 5-6: % movimientos con saldo negativo', v_bad; END IF;

    -- (b bis) la cadena queda continua: stock_anterior = stock_nuevo previo
    SELECT count(*) INTO v_bad FROM (
        SELECT stock_anterior, lag(stock_nuevo) OVER (
                 PARTITION BY producto_variante_id, bodega_id
                 ORDER BY fecha_creacion, id) prev
        FROM movimiento_inventario) x
    WHERE prev IS NOT NULL AND prev <> stock_anterior;
    IF v_bad > 0 THEN RAISE EXCEPTION 'CAPA 5-6: % eslabones rotos en el kardex', v_bad; END IF;

    -- toda cadena arranca en 0
    SELECT count(*) INTO v_bad FROM (
        SELECT DISTINCT ON (producto_variante_id, bodega_id) stock_anterior
        FROM movimiento_inventario
        ORDER BY producto_variante_id, bodega_id, fecha_creacion, id) x
    WHERE stock_anterior <> 0;
    IF v_bad > 0 THEN RAISE EXCEPTION 'CAPA 5-6: % cadenas no arrancan en 0', v_bad; END IF;

    -- (g) las salidas siguen siendo exactamente las mismas (filas y cantidades)
    SELECT count(*) INTO v_bad FROM (
        SELECT id, cantidad, fecha_creacion FROM movimiento_inventario
         WHERE tipo_movimiento_id IN (5,6,7,8)
        EXCEPT
        SELECT id, cantidad, fecha_creacion FROM seed_backup.reb74_movimiento_inventario
         WHERE tipo_movimiento_id IN (5,6,7,8)) x;
    IF v_bad > 0 THEN RAISE EXCEPTION 'CAPA 5-6: % salidas de kardex alteradas', v_bad; END IF;
    SELECT count(*) INTO v_bad FROM seed_backup.reb74_movimiento_inventario
     WHERE tipo_movimiento_id IN (5,6,7,8);
    SELECT count(*) INTO v_n FROM movimiento_inventario WHERE tipo_movimiento_id IN (5,6,7,8);
    IF v_bad <> v_n THEN RAISE EXCEPTION 'CAPA 5-6: cambio el numero de salidas (% -> %)', v_bad, v_n; END IF;

    -- la apertura ya no es un pico de un solo dia
    SELECT count(DISTINCT (fecha_creacion AT TIME ZONE 'America/Guayaquil')::date) INTO v_n
    FROM movimiento_inventario WHERE referencia_tipo = 'inventario_inicial';
    IF v_n < 2 THEN RAISE EXCEPTION 'CAPA 5-6: la apertura sigue concentrada en % dia(s)', v_n; END IF;

    -- el costo de las entradas nuevas es el precio de SU orden, no el vigente
    SELECT count(*) INTO v_bad FROM movimiento_inventario m
    JOIN seed_backup.reb75_linea l ON l.movimiento_id = m.id
    WHERE m.costo_unitario IS DISTINCT FROM l.precio;
    IF v_bad > 0 THEN RAISE EXCEPTION 'CAPA 5-6: % entradas con costo distinto al de su orden', v_bad; END IF;

    RAISE NOTICE 'CAPA 5-6 verificada: kardex = stock_actual, cadena continua desde 0, sin negativos, salidas intactas.';
END $$;

INSERT INTO configuracion_tienda (clave, valor, tipo_dato, descripcion, fecha_creacion)
SELECT 'seed_rebalanceo_abastecimiento',
       jsonb_build_object('completado', now(), 'scripts', '74-78')::text, 'json',
       'Rebalanceo del abastecimiento (A1/M1/M2/B2) completado. Reversion: 99_revert_abastecimiento.sql', now()
WHERE NOT EXISTS (SELECT 1 FROM configuracion_tienda WHERE clave = 'seed_rebalanceo_abastecimiento');

COMMIT;

\echo '=== (c) Composicion del abastecimiento: ANTES vs DESPUES ==='
WITH ahora AS (
    SELECT COALESCE(sum(mi.cantidad) FILTER (WHERE mi.referencia_tipo='inventario_inicial'),0) AS apertura,
           COALESCE(sum(mi.cantidad) FILTER (WHERE mi.tipo_movimiento_id = 1),0)               AS compras,
           COALESCE(sum(mi.cantidad),0)                                                        AS entradas
    FROM movimiento_inventario mi JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id
    WHERE tm.factor = 1)
SELECT (SELECT valor FROM seed_backup.reb74_agregados WHERE metrica='uds_apertura')::bigint AS apertura_antes,
       round(100*(SELECT valor FROM seed_backup.reb74_agregados WHERE metrica='uds_apertura')
                /(SELECT valor FROM seed_backup.reb74_agregados WHERE metrica='uds_entradas_todas'),2) AS pct_antes,
       a.apertura AS apertura_despues, round(100.0*a.apertura/a.entradas,2) AS pct_despues,
       a.compras  AS compras_despues,  round(100.0*a.compras /a.entradas,2) AS pct_compras,
       a.entradas AS entradas_totales
FROM ahora a;

\echo '=== (d) Distribucion temporal de la apertura conservada ==='
SELECT (fecha_creacion AT TIME ZONE 'America/Guayaquil')::date AS dia,
       count(*) AS movimientos, sum(cantidad) AS unidades
FROM movimiento_inventario WHERE referencia_tipo = 'inventario_inicial'
GROUP BY 1 ORDER BY 1;

\echo '=== Tipos de movimiento tras el rebalanceo ==='
SELECT tm.codigo, count(*) AS movs, sum(mi.cantidad) AS uds
FROM movimiento_inventario mi JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id
GROUP BY 1 ORDER BY 3 DESC;
