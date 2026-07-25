-- ============================================================================
-- 77_rebalanceo_facturas_pagos.sql
-- REBALANCEO DEL ABASTECIMIENTO — CAPA 3 y 4:
--   FACTURAS DE COMPRA + DETALLE  y  CUENTAS POR PAGAR + PAGOS A PROVEEDOR
--
-- Para cada orden creada por el script 76 emite su factura desde las lineas
-- efectivamente RECIBIDAS, abre la CxP y registra los pagos con puntualidad
-- variada (a tiempo / con retraso / vigente por vencer / vencida), igual que
-- el script 58, para que los informes de puntualidad de pago sigan teniendo
-- contraste.
--
-- INVARIANTES:
--   * factura_compra.subtotal/monto_impuesto/total los pone el trigger
--     fn_recalcular_total_factura_compra desde el detalle (se insertan en 0).
--     factura_compra_detalle.subtotal es GENERATED: no se escribe.
--   * Secuencia temporal: factura >= recepcion; pago >= factura; nunca en el
--     futuro (> 2026-07-23).
--   * cuenta_por_pagar.saldo_pendiente / estado y factura_compra.estado se
--     mantienen coherentes con los pagos (no hay trigger: el flujo real lo
--     hace en Java y aqui se replica en la misma transaccion).
--   * CUADRE CONTABLE: suma(factura_compra.total) - suma(pago_proveedor.monto)
--     = suma(cuenta_por_pagar.saldo_pendiente), al centavo. Se verifica al
--     final de la capa y aborta si no cuadra.
--
-- Marca/reversion: observacion '[SEED-REB]' + 99_revert_abastecimiento.sql.
-- Idempotencia: clave configuracion_tienda 'seed_reb77_facturas'.
-- Ejecutar como postgres (superusuario).
-- ============================================================================
\set ON_ERROR_STOP on

BEGIN;

DO $$
DECLARE
    cur     record;
    v_hoy   date := date '2026-07-23';
    v_femit date; v_fvenc date; v_pdate date;
    v_seq   bigint; v_fnum text; v_reg bigint; v_fc bigint; v_cxp bigint;
    v_total numeric; v_amt1 numeric; v_paid numeric; v_metodo bigint;
    v_bucket int; v_kind text; v_late boolean;
    n_fac int := 0; n_pay int := 0; n_parc int := 0; n_venc int := 0; n_pend int := 0;
BEGIN
    IF EXISTS (SELECT 1 FROM configuracion_tienda WHERE clave = 'seed_reb77_facturas') THEN
        RAISE NOTICE 'Rebalanceo / 77 (facturas) ya aplicado; se omite.';
        RETURN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM configuracion_tienda WHERE clave = 'seed_reb76_ordenes') THEN
        RAISE EXCEPTION 'Falta la capa 1-2: ejecute antes 76_rebalanceo_ordenes_recepciones.sql';
    END IF;

    FOR cur IN
        SELECT p.oc_key, p.orden_compra_id AS oc, p.proveedor_id AS prov, p.fecha_recep,
               pr.dias_credito AS credito
        FROM seed_backup.reb75_oc p
        JOIN proveedor pr ON pr.id = p.proveedor_id
        WHERE p.orden_compra_id IS NOT NULL
        ORDER BY p.fecha_recep, p.oc_key
    LOOP
        v_femit := cur.fecha_recep + (abs(hashtext('fe#' || cur.oc_key)) % 4);   -- 0..3 dias
        IF v_femit > v_hoy THEN v_femit := v_hoy; END IF;
        v_fvenc := v_femit + cur.credito;
        v_seq   := nextval('public.seq_numero_documento');
        v_fnum  := 'FC-' || to_char(v_femit,'YYYYMMDD') || '-' || v_seq::text;
        v_reg   := CASE WHEN abs(hashtext('rg#' || cur.oc_key)) % 2 = 0 THEN 11 ELSE 2 END;

        INSERT INTO factura_compra (proveedor_id, orden_compra_id, moneda_id, numero_factura,
                                    fecha_emision, fecha_vencimiento, estado, registrado_por,
                                    fecha_creacion)
        VALUES (cur.prov, cur.oc, 1, v_fnum, v_femit, v_fvenc, 'registrada', v_reg,
                (v_femit::timestamptz + time '12:00'))
        RETURNING id INTO v_fc;

        INSERT INTO factura_compra_detalle (factura_compra_id, producto_variante_id, cantidad,
                                            precio_unitario, monto_impuesto, fecha_creacion)
        SELECT v_fc, d.producto_variante_id, d.cantidad_recibida, d.precio_unitario,
               round((d.cantidad_recibida * d.precio_unitario * 0.15)::numeric, 2),
               (v_femit::timestamptz + time '12:00')
        FROM orden_compra_detalle d
        WHERE d.orden_compra_id = cur.oc AND d.cantidad_recibida > 0;

        SELECT total INTO v_total FROM factura_compra WHERE id = v_fc;
        IF v_total IS NULL OR v_total <= 0 THEN
            RAISE EXCEPTION 'Orden % sin lineas facturables', cur.oc;
        END IF;
        n_fac := n_fac + 1;

        INSERT INTO log_auditoria (usuario_id, tabla, registro_id, accion, datos_nuevos, fecha_creacion)
        VALUES (v_reg, 'factura_compra', v_fc, 'INSERT',
                jsonb_build_object('estado','registrada','numero_factura',v_fnum,
                                   'orden_compra_id',cur.oc),
                (v_femit::timestamptz + time '12:00'));

        INSERT INTO cuenta_por_pagar (factura_compra_id, proveedor_id, monto_original,
                                      saldo_pendiente, fecha_vencimiento, estado, fecha_creacion)
        VALUES (v_fc, cur.prov, v_total, v_total, v_fvenc, 'pendiente',
                (v_femit::timestamptz + time '12:05'))
        RETURNING id INTO v_cxp;

        UPDATE seed_backup.reb75_oc SET factura_compra_id = v_fc, cuenta_por_pagar_id = v_cxp
         WHERE oc_key = cur.oc_key;

        -- comportamiento de pago (determinista, mismo reparto que el script 58)
        v_bucket := abs(hashtext(cur.oc_key)) % 100;
        IF    v_bucket < 70 THEN v_kind := 'full';
        ELSIF v_bucket < 85 THEN v_kind := 'partial';
        ELSE  v_kind := 'none'; END IF;

        IF v_kind IN ('full','partial') THEN
            v_late := (abs(hashtext(cur.oc_key || 'l')) % 100) < 35;   -- ~35 % tardios
            IF v_late THEN
                v_pdate := v_fvenc + (2 + abs(hashtext(cur.oc_key || 'd')) % 18);
            ELSE
                v_pdate := v_fvenc - (abs(hashtext(cur.oc_key || 'd')) % 7);
            END IF;
            IF v_pdate < v_femit + 1 THEN v_pdate := v_femit + 1; END IF;
            IF v_pdate > v_hoy THEN
                IF v_fvenc < v_hoy THEN v_pdate := v_hoy;    -- pago con retraso, ya ocurrido
                ELSE v_kind := 'none'; END IF;               -- factura muy reciente: sin pagar
            END IF;
        END IF;

        v_metodo := CASE WHEN abs(hashtext('mp#' || cur.oc_key)) % 100 < 75 THEN 2 ELSE 1 END;

        IF v_kind = 'full' THEN
            IF v_total > 500 AND abs(hashtext('ab#' || cur.oc_key)) % 100 < 40 THEN
                v_amt1 := round(v_total * 0.5, 2);
                INSERT INTO pago_proveedor (cuenta_por_pagar_id, metodo_pago_id, usuario_id, monto,
                                            fecha_pago, referencia, observacion, fecha_creacion)
                VALUES (v_cxp, v_metodo, v_reg, v_amt1, greatest(v_femit + 1, v_pdate - 3),
                        'TRF-' || to_char(v_pdate,'YYYYMMDD') || '-'
                                || (abs(hashtext(cur.oc_key||'a')) % 100000)::text,
                        '[SEED-REB] abono 1/2',
                        (greatest(v_femit + 1, v_pdate - 3)::timestamptz + time '15:00'));
                INSERT INTO pago_proveedor (cuenta_por_pagar_id, metodo_pago_id, usuario_id, monto,
                                            fecha_pago, referencia, observacion, fecha_creacion)
                VALUES (v_cxp, v_metodo, v_reg, v_total - v_amt1, v_pdate,
                        'TRF-' || to_char(v_pdate,'YYYYMMDD') || '-'
                                || (abs(hashtext(cur.oc_key||'b')) % 100000)::text,
                        '[SEED-REB] abono 2/2',
                        (v_pdate::timestamptz + time '15:00'));
                n_pay := n_pay + 2;
            ELSE
                INSERT INTO pago_proveedor (cuenta_por_pagar_id, metodo_pago_id, usuario_id, monto,
                                            fecha_pago, referencia, observacion, fecha_creacion)
                VALUES (v_cxp, v_metodo, v_reg, v_total, v_pdate,
                        'TRF-' || to_char(v_pdate,'YYYYMMDD') || '-'
                                || (abs(hashtext(cur.oc_key)) % 100000)::text,
                        '[SEED-REB] pago total', (v_pdate::timestamptz + time '15:00'));
                n_pay := n_pay + 1;
            END IF;
            UPDATE cuenta_por_pagar SET saldo_pendiente = 0, estado = 'pagada' WHERE id = v_cxp;
            UPDATE factura_compra   SET estado = 'pagada' WHERE id = v_fc;

        ELSIF v_kind = 'partial' THEN
            v_paid := round((v_total * (30 + abs(hashtext('pp#' || cur.oc_key)) % 40) / 100.0)::numeric, 2);
            IF v_paid <= 0      THEN v_paid := round(v_total * 0.3, 2); END IF;
            IF v_paid >= v_total THEN v_paid := round(v_total * 0.5, 2); END IF;
            INSERT INTO pago_proveedor (cuenta_por_pagar_id, metodo_pago_id, usuario_id, monto,
                                        fecha_pago, referencia, observacion, fecha_creacion)
            VALUES (v_cxp, v_metodo, v_reg, v_paid, v_pdate,
                    'TRF-' || to_char(v_pdate,'YYYYMMDD') || '-'
                            || (abs(hashtext(cur.oc_key)) % 100000)::text,
                    '[SEED-REB] abono parcial', (v_pdate::timestamptz + time '15:00'));
            UPDATE cuenta_por_pagar SET saldo_pendiente = v_total - v_paid, estado = 'parcial'
             WHERE id = v_cxp;
            UPDATE factura_compra   SET estado = 'pagada_parcial' WHERE id = v_fc;
            n_pay := n_pay + 1; n_parc := n_parc + 1;

        ELSE   -- sin pagar: vencida o vigente por vencer
            IF v_fvenc < v_hoy THEN
                UPDATE cuenta_por_pagar SET estado = 'vencida' WHERE id = v_cxp;
                n_venc := n_venc + 1;
            ELSE
                UPDATE cuenta_por_pagar SET estado = 'pendiente' WHERE id = v_cxp;
                n_pend := n_pend + 1;
            END IF;
        END IF;
    END LOOP;

    INSERT INTO configuracion_tienda (clave, valor, tipo_dato, descripcion, fecha_creacion)
    VALUES ('seed_reb77_facturas',
            jsonb_build_object('facturas',n_fac,'pagos',n_pay,'parciales',n_parc,
                               'vencidas',n_venc,'por_vencer',n_pend)::text,
            'json',
            'Rebalanceo abastecimiento/77: facturas+CxP+pagos. Reversion: 99_revert_abastecimiento.sql', now());

    RAISE NOTICE 'Rebalanceo / 77 OK. Facturas: %, pagos: %, parciales: %, vencidas: %, por vencer: %',
                 n_fac, n_pay, n_parc, n_venc, n_pend;
END $$;

-- ── Verificacion de la capa ────────────────────────────────────────────────
DO $$
DECLARE
    v_bad bigint; v_fac numeric; v_pag numeric; v_sal numeric;
BEGIN
    -- una factura y una CxP por cada orden nueva
    SELECT count(*) INTO v_bad FROM seed_backup.reb75_oc
    WHERE factura_compra_id IS NULL OR cuenta_por_pagar_id IS NULL;
    IF v_bad > 0 THEN RAISE EXCEPTION 'CAPA 3-4: % ordenes sin factura o sin CxP', v_bad; END IF;

    -- la factura factura EXACTAMENTE lo recibido
    SELECT count(*) INTO v_bad FROM factura_compra f
    JOIN seed_backup.reb75_oc p ON p.factura_compra_id = f.id
    WHERE (SELECT COALESCE(sum(fd.cantidad),0) FROM factura_compra_detalle fd
            WHERE fd.factura_compra_id = f.id)
       <> (SELECT COALESCE(sum(d.cantidad_recibida),0) FROM orden_compra_detalle d
            WHERE d.orden_compra_id = p.orden_compra_id);
    IF v_bad > 0 THEN RAISE EXCEPTION 'CAPA 3-4: % facturas no cuadran con lo recibido', v_bad; END IF;

    -- cabecera de factura coherente con su detalle (trigger)
    SELECT count(*) INTO v_bad FROM factura_compra f
    JOIN seed_backup.reb75_oc p ON p.factura_compra_id = f.id
    WHERE round(f.total,2) <> round((SELECT COALESCE(sum(fd.cantidad*fd.precio_unitario + fd.monto_impuesto),0)
                                     FROM factura_compra_detalle fd WHERE fd.factura_compra_id = f.id),2);
    IF v_bad > 0 THEN RAISE EXCEPTION 'CAPA 3-4: % facturas con total descuadrado', v_bad; END IF;

    -- saldo de cada CxP = monto original - pagos
    SELECT count(*) INTO v_bad FROM cuenta_por_pagar c
    WHERE round(c.saldo_pendiente,2) <> round(c.monto_original
          - (SELECT COALESCE(sum(pp.monto),0) FROM pago_proveedor pp WHERE pp.cuenta_por_pagar_id = c.id),2);
    IF v_bad > 0 THEN RAISE EXCEPTION 'CAPA 3-4: % CxP con saldo descuadrado', v_bad; END IF;

    -- pagos nunca antes de la factura ni en el futuro
    SELECT count(*) INTO v_bad FROM pago_proveedor pp
    JOIN cuenta_por_pagar c ON c.id = pp.cuenta_por_pagar_id
    JOIN factura_compra f ON f.id = c.factura_compra_id
    WHERE pp.fecha_pago < f.fecha_emision OR pp.fecha_pago > date '2026-07-23';
    IF v_bad > 0 THEN RAISE EXCEPTION 'CAPA 3-4: % pagos con fecha imposible', v_bad; END IF;

    -- CUADRE CONTABLE GLOBAL
    SELECT COALESCE(sum(total),0)           INTO v_fac FROM factura_compra;
    SELECT COALESCE(sum(monto),0)           INTO v_pag FROM pago_proveedor;
    SELECT COALESCE(sum(saldo_pendiente),0) INTO v_sal FROM cuenta_por_pagar;
    IF round(v_fac - v_pag, 2) <> round(v_sal, 2) THEN
        RAISE EXCEPTION 'CAPA 3-4: CxP DESCUADRADA. facturas % - pagos % = % <> saldo %',
                        v_fac, v_pag, v_fac - v_pag, v_sal;
    END IF;

    -- el kardex y el stock siguen intactos
    IF (SELECT count(*) FROM movimiento_inventario)
       <> (SELECT filas FROM seed_backup.reb74_umbral WHERE tabla='movimiento_inventario') THEN
        RAISE EXCEPTION 'CAPA 3-4: el kardex cambio y no deberia';
    END IF;

    RAISE NOTICE 'CAPA 3-4 verificada. Cuadre contable: facturas % - pagos % = saldo CxP %',
                 round(v_fac,2), round(v_pag,2), round(v_sal,2);
END $$;

COMMIT;

\echo '=== CAPA 3-4 · Cuadre contable de compras (ANTES vs DESPUES) ==='
SELECT 'factura_compra_total' AS metrica,
       (SELECT round(valor,2) FROM seed_backup.reb74_agregados WHERE metrica='cxp_factura_compra_total') AS antes,
       (SELECT round(sum(total),2) FROM factura_compra) AS despues
UNION ALL SELECT 'pago_proveedor_total',
       (SELECT round(valor,2) FROM seed_backup.reb74_agregados WHERE metrica='cxp_pago_proveedor_total'),
       (SELECT round(sum(monto),2) FROM pago_proveedor)
UNION ALL SELECT 'saldo_cuenta_por_pagar',
       (SELECT round(valor,2) FROM seed_backup.reb74_agregados WHERE metrica='cxp_saldo_pendiente'),
       (SELECT round(sum(saldo_pendiente),2) FROM cuenta_por_pagar)
UNION ALL SELECT 'diferencia_facturas_menos_pagos',
       (SELECT round(a.valor - b.valor,2) FROM seed_backup.reb74_agregados a, seed_backup.reb74_agregados b
         WHERE a.metrica='cxp_factura_compra_total' AND b.metrica='cxp_pago_proveedor_total'),
       (SELECT round((SELECT sum(total) FROM factura_compra) - (SELECT sum(monto) FROM pago_proveedor),2));

\echo '=== CAPA 3-4 · Puntualidad de pago de las CxP nuevas ==='
SELECT c.estado, count(*) AS cxp, round(sum(c.monto_original),2) AS monto
FROM cuenta_por_pagar c
WHERE c.id > (SELECT max_id FROM seed_backup.reb74_umbral WHERE tabla='cuenta_por_pagar')
GROUP BY 1 ORDER BY 2 DESC;
