-- ============================================================================
-- 76_rebalanceo_ordenes_recepciones.sql
-- REBALANCEO DEL ABASTECIMIENTO — CAPA 1 y 2:
--   ORDENES DE COMPRA + DETALLE  y  RECEPCIONES DE MERCANCIA + DETALLE
--   (+ producto_proveedor de las parejas proveedor/variante que faltaban)
--
-- Aplica el plan de seed_backup.reb75_oc / reb75_linea. NO toca el kardex ni
-- el inventario: eso es la capa 5 (script 78). Aqui solo nace el documento de
-- compra que respalda cada unidad migrada.
--
-- INVARIANTES:
--   * orden_compra.subtotal/monto_impuesto/total los pone el trigger
--     fn_recalcular_total_orden_compra desde el detalle (se insertan en 0).
--   * orden_compra_detalle.subtotal es GENERATED: no se escribe.
--     monto_impuesto = 15 % (IVA vigente) sobre lo PEDIDO.
--   * cantidad PEDIDA = recibida + faltante (recepcion parcial) + rechazada
--     (rechazo en puerta). La cantidad RECIBIDA es exactamente la unidad
--     migrada desde la apertura: ni una mas, ni una menos.
--   * Numeracion OC-/RM- + nextval(seq_numero_documento), formato real.
--   * Auditoria de aprobacion enviada->confirmada en log_auditoria, igual que
--     el script 57.
--
-- Marca/reversion: observacion '[SEED-REB]' + 99_revert_abastecimiento.sql.
-- Idempotencia: clave configuracion_tienda 'seed_reb76_ordenes'.
-- Ejecutar como postgres (superusuario).
-- ============================================================================
\set ON_ERROR_STOP on

BEGIN;

-- ids reales que devuelve cada capa, guardados junto al plan (fuera de public)
ALTER TABLE seed_backup.reb75_linea
    ADD COLUMN IF NOT EXISTS orden_compra_detalle_id bigint,
    ADD COLUMN IF NOT EXISTS recepcion_mercancia_id  bigint,
    ADD COLUMN IF NOT EXISTS movimiento_id           bigint;

DO $$
DECLARE
    cur      record;
    lin      record;
    v_seq    bigint;
    v_oc     bigint;
    v_rm     bigint;
    v_ocd    bigint;
    v_li     int;
    v_qty    int;
    v_falta  int;
    v_rech   int;
    v_appr_user bigint;
    v_appr_date timestamptz;
    v_motivos text[] := ARRAY['Empaque danado en transito','Producto con defecto de fabrica',
                              'No coincide con especificacion','Fecha de caducidad proxima',
                              'Unidades incompletas en caja'];
    n_oc int := 0; n_rm int := 0; n_lin int := 0; n_pp int := 0; n_uds bigint := 0;
BEGIN
    IF EXISTS (SELECT 1 FROM configuracion_tienda WHERE clave = 'seed_reb76_ordenes') THEN
        RAISE NOTICE 'Rebalanceo / 76 (ordenes) ya aplicado; se omite.';
        RETURN;
    END IF;
    IF to_regclass('seed_backup.reb75_oc') IS NULL THEN
        RAISE EXCEPTION 'Falta el plan: ejecute antes 75_plan_rebalanceo_abastecimiento.sql';
    END IF;

    -- ── producto_proveedor: la pareja proveedor/variante debe existir ───────
    INSERT INTO producto_proveedor (proveedor_id, producto_variante_id, codigo_proveedor,
                                    costo, tiempo_entrega_dias, cantidad_minima,
                                    es_preferido, activo, fecha_creacion)
    SELECT t.proveedor_id, t.variante_id,
           'PV-' || t.proveedor_id::text || '-' || t.variante_id::text,
           round(t.costo_vigente, 2), t.lead_dias,
           CASE WHEN t.costo_vigente < 20 THEN 24 WHEN t.costo_vigente < 80 THEN 12 ELSE 5 END,
           NOT EXISTS (SELECT 1 FROM producto_proveedor x
                       WHERE x.producto_variante_id = t.variante_id AND x.es_preferido),
           true,
           (SELECT min(l.fecha_recep)::timestamptz + time '09:00'
              FROM seed_backup.reb75_linea l WHERE l.variante_id = t.variante_id)
    FROM seed_backup.reb75_variante t
    WHERE EXISTS (SELECT 1 FROM seed_backup.reb75_linea l WHERE l.variante_id = t.variante_id)
      AND NOT EXISTS (SELECT 1 FROM producto_proveedor p
                      WHERE p.producto_variante_id = t.variante_id
                        AND p.proveedor_id = t.proveedor_id)
    ORDER BY t.variante_id;   -- ids deterministas: la re-aplicacion reproduce el mismo estado
    GET DIAGNOSTICS n_pp = ROW_COUNT;

    -- ── ordenes y recepciones, en orden cronologico ────────────────────────
    FOR cur IN
        SELECT o.* FROM seed_backup.reb75_oc o ORDER BY o.fecha_recep, o.oc_key
    LOOP
        v_seq := nextval('public.seq_numero_documento');
        INSERT INTO orden_compra (numero, proveedor_id, bodega_id, moneda_id, usuario_id, estado,
                                  fecha_emision, fecha_entrega_esperada, observacion, fecha_creacion)
        VALUES ('OC-' || to_char(cur.fecha_emision,'YYYYMMDD') || '-' || v_seq::text,
                cur.proveedor_id, 4, 1, 11, cur.estado,
                cur.fecha_emision, cur.fecha_esperada,
                '[SEED-REB] abastecimiento reconstruido',
                (cur.fecha_emision::timestamptz + time '09:15'))
        RETURNING id INTO v_oc;
        n_oc := n_oc + 1;

        v_seq := nextval('public.seq_numero_documento');
        INSERT INTO recepcion_mercancia (numero, orden_compra_id, bodega_id, usuario_id, estado,
                                         fecha_recepcion, observacion, fecha_creacion)
        VALUES ('RM-' || to_char(cur.fecha_recep,'YYYYMMDD') || '-' || v_seq::text,
                v_oc, 4, 9, 'confirmada',
                (cur.fecha_recep::timestamptz + time '10:30'),
                '[SEED-REB] recepcion', (cur.fecha_recep::timestamptz + time '10:30'))
        RETURNING id INTO v_rm;
        n_rm := n_rm + 1;

        v_li := 0;
        FOR lin IN
            SELECT l.* FROM seed_backup.reb75_linea l
            WHERE l.oc_key = cur.oc_key ORDER BY l.variante_id, l.lote
        LOOP
            v_li := v_li + 1;
            v_falta := 0; v_rech := 0;
            IF cur.estado = 'recibida_parcial' AND v_li = 1 THEN
                v_falta := greatest(1, ceil(lin.cantidad
                           * (15 + abs(hashtext('fa#' || cur.oc_key)) % 20) / 100.0)::int);
            END IF;
            IF cur.con_rechazo AND v_li = 1 THEN
                v_rech := 1 + abs(hashtext('rq#' || cur.oc_key)) % 3;
            END IF;
            v_qty := lin.cantidad + v_falta + v_rech;

            INSERT INTO orden_compra_detalle (orden_compra_id, producto_variante_id, cantidad,
                                              precio_unitario, monto_impuesto, cantidad_recibida,
                                              fecha_creacion)
            VALUES (v_oc, lin.variante_id, v_qty, lin.precio,
                    round(v_qty * lin.precio * 0.15, 2), lin.cantidad,
                    (cur.fecha_emision::timestamptz + time '09:15'))
            RETURNING id INTO v_ocd;

            INSERT INTO recepcion_detalle (recepcion_mercancia_id, orden_compra_detalle_id,
                                           cantidad_recibida, cantidad_rechazada, motivo_rechazo,
                                           fecha_creacion)
            VALUES (v_rm, v_ocd, lin.cantidad, v_rech,
                    CASE WHEN v_rech > 0
                         THEN v_motivos[1 + abs(hashtext('mo#' || cur.oc_key)) % 5] END,
                    (cur.fecha_recep::timestamptz + time '10:30'));

            UPDATE seed_backup.reb75_linea
               SET orden_compra_detalle_id = v_ocd, recepcion_mercancia_id = v_rm
             WHERE variante_id = lin.variante_id AND lote = lin.lote;

            n_lin := n_lin + 1; n_uds := n_uds + lin.cantidad;
        END LOOP;

        UPDATE seed_backup.reb75_oc
           SET orden_compra_id = v_oc, recepcion_id = v_rm
         WHERE oc_key = cur.oc_key;

        -- auditoria de aprobacion (enviada -> confirmada), formato del sistema real
        v_appr_user := CASE WHEN abs(hashtext('ap#' || cur.oc_key)) % 2 = 0 THEN 6 ELSE 2 END;
        v_appr_date := (cur.fecha_emision::timestamptz + time '11:00')
                       + ((abs(hashtext('ad#' || cur.oc_key)) % 3) + 1) * interval '1 day';
        IF v_appr_date > (cur.fecha_recep::timestamptz + time '10:00') THEN
            v_appr_date := (cur.fecha_recep::timestamptz + time '10:00');
        END IF;
        INSERT INTO log_auditoria (usuario_id, tabla, registro_id, accion,
                                   datos_anteriores, datos_nuevos, fecha_creacion)
        VALUES (v_appr_user, 'orden_compra', v_oc, 'UPDATE',
                '{"estado":"enviada"}'::jsonb, '{"estado":"confirmada"}'::jsonb, v_appr_date);
    END LOOP;

    INSERT INTO configuracion_tienda (clave, valor, tipo_dato, descripcion, fecha_creacion)
    VALUES ('seed_reb76_ordenes',
            jsonb_build_object('ordenes',n_oc,'recepciones',n_rm,'lineas',n_lin,
                               'unidades',n_uds,'producto_proveedor',n_pp)::text,
            'json',
            'Rebalanceo abastecimiento/76: OC + recepciones. Reversion: 99_revert_abastecimiento.sql', now());

    RAISE NOTICE 'Rebalanceo / 76 OK. OC: %, recepciones: %, lineas: %, uds recibidas: %, producto_proveedor nuevos: %',
                 n_oc, n_rm, n_lin, n_uds, n_pp;
END $$;

-- ── Verificacion de la capa ────────────────────────────────────────────────
DO $$
DECLARE v_bad bigint; v_uds bigint; v_plan bigint;
BEGIN
    -- toda linea del plan tiene su detalle de orden y su detalle de recepcion
    SELECT count(*) INTO v_bad FROM seed_backup.reb75_linea
    WHERE orden_compra_detalle_id IS NULL OR recepcion_mercancia_id IS NULL;
    IF v_bad > 0 THEN RAISE EXCEPTION 'CAPA 1-2: % lineas del plan sin documento', v_bad; END IF;

    -- lo recibido coincide EXACTAMENTE con lo planificado
    SELECT sum(d.cantidad_recibida) INTO v_uds FROM orden_compra_detalle d
    JOIN orden_compra o ON o.id = d.orden_compra_id WHERE o.observacion LIKE '[SEED-REB]%';
    SELECT sum(cantidad) INTO v_plan FROM seed_backup.reb75_linea;
    IF v_uds <> v_plan THEN
        RAISE EXCEPTION 'CAPA 1-2: recibido % <> planificado %', v_uds, v_plan;
    END IF;

    -- recibido + rechazado nunca supera lo pedido
    SELECT count(*) INTO v_bad FROM recepcion_detalle rd
    JOIN orden_compra_detalle d ON d.id = rd.orden_compra_detalle_id
    JOIN orden_compra o ON o.id = d.orden_compra_id
    WHERE o.observacion LIKE '[SEED-REB]%'
      AND rd.cantidad_recibida + rd.cantidad_rechazada > d.cantidad;
    IF v_bad > 0 THEN RAISE EXCEPTION 'CAPA 1-2: % recepciones exceden lo pedido', v_bad; END IF;

    -- el trigger de totales dejo cabeceras coherentes con su detalle
    SELECT count(*) INTO v_bad FROM orden_compra o
    WHERE o.observacion LIKE '[SEED-REB]%'
      AND round(o.total,2) <> round((SELECT COALESCE(sum(d.cantidad*d.precio_unitario + d.monto_impuesto),0)
                                     FROM orden_compra_detalle d WHERE d.orden_compra_id = o.id),2);
    IF v_bad > 0 THEN RAISE EXCEPTION 'CAPA 1-2: % ordenes con total descuadrado', v_bad; END IF;

    -- el kardex y el inventario NO se han tocado todavia
    IF (SELECT count(*) FROM movimiento_inventario)
       <> (SELECT filas FROM seed_backup.reb74_umbral WHERE tabla='movimiento_inventario') THEN
        RAISE EXCEPTION 'CAPA 1-2: el kardex cambio y no deberia';
    END IF;
    IF (SELECT COALESCE(sum(stock_actual),0) FROM inventario)
       <> (SELECT valor FROM seed_backup.reb74_agregados WHERE metrica='inventario_stock_actual') THEN
        RAISE EXCEPTION 'CAPA 1-2: el stock cambio y no deberia';
    END IF;

    RAISE NOTICE 'CAPA 1-2 verificada: documentos completos, cantidades exactas, totales por trigger.';
END $$;

COMMIT;

\echo '=== CAPA 1-2 · Ordenes y recepciones creadas ==='
SELECT count(*) AS ordenes, sum(o.total) AS total_oc,
       (SELECT count(*) FROM recepcion_mercancia r JOIN orden_compra x ON x.id=r.orden_compra_id
         WHERE x.observacion LIKE '[SEED-REB]%') AS recepciones,
       (SELECT sum(d.cantidad_recibida) FROM orden_compra_detalle d JOIN orden_compra x ON x.id=d.orden_compra_id
         WHERE x.observacion LIKE '[SEED-REB]%') AS uds_recibidas
FROM orden_compra o WHERE o.observacion LIKE '[SEED-REB]%';

\echo '=== CAPA 1-2 · Distribucion temporal de las ordenes nuevas ==='
SELECT to_char(date_trunc('quarter', o.fecha_emision),'YYYY"Q"Q') AS trimestre,
       count(*) AS ordenes, round(sum(o.total),2) AS total
FROM orden_compra o WHERE o.observacion LIKE '[SEED-REB]%' GROUP BY 1 ORDER BY 1;
