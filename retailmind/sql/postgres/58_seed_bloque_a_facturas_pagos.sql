-- =====================================================================
-- 58_seed_bloque_a_facturas_pagos.sql
-- SIEMBRA DE VOLUMEN HISTORICO — BLOQUE A / Parte 4:
--   FACTURAS DE COMPRA + CUENTAS POR PAGAR + PAGOS A PROVEEDOR
-- ---------------------------------------------------------------------
-- Para cada orden sembrada RECIBIDA (total o parcial) emite su factura
-- de compra desde las lineas efectivamente recibidas, genera la CxP y
-- registra los pagos. Puntualidad de pago VARIADA (a tiempo / tarde /
-- vigente por vencer / vencida) para dar contraste a los informes.
--
-- INVARIANTES:
--   * factura_compra.subtotal/impuesto/total los pone el trigger desde el
--     detalle (se insertan en 0). factura_compra_detalle.subtotal es
--     GENERATED. monto_impuesto = 15 % IVA sobre lo recibido.
--   * Secuencia temporal: factura >= recepcion; pago >= factura. Nunca
--     pago en el futuro (> 2026-07-23): facturas demasiado recientes
--     quedan pendientes.
--   * cuenta_por_pagar.saldo_pendiente y estado, y factura_compra.estado,
--     se mantienen coherentes con los pagos (no hay trigger que lo haga:
--     el flujo real lo hace en Java, aqui se replica en la misma tx).
--   * Auditoria: registro de factura_compra (accion INSERT) con el mismo
--     formato real. Los pagos NO se auditan (el sistema real tampoco).
--   * Numeracion FC- + nextval(seq_numero_documento).
--
-- Marca/reversion: pago_proveedor.observacion '[SEED-BA]' + umbrales en
--   configuracion_tienda (clave 'seed_ba_58_facturas').
-- =====================================================================

DO $$
DECLARE
    v_thr jsonb;
    cur record;
    v_femit date; v_fvenc date; v_fnum text; v_reg bigint; v_fc bigint; v_cxp bigint;
    v_total numeric; v_bucket int; v_kind text; v_late boolean; v_pdate date;
    v_metodo bigint; v_amt1 numeric; v_paid numeric; v_seq bigint;
    v_hoy date := date '2026-07-23';
    n_fac int := 0; n_pay int := 0; n_venc int := 0; n_pend int := 0; n_parc int := 0;
BEGIN
    IF EXISTS (SELECT 1 FROM configuracion_tienda WHERE clave = 'seed_ba_58_facturas') THEN
        RAISE NOTICE 'Bloque A / 58 (facturas) ya sembrado; se omite.';
        RETURN;
    END IF;

    PERFORM setseed(0.5801);

    v_thr := jsonb_build_object(
        'factura_compra',         (SELECT COALESCE(max(id),0) FROM factura_compra),
        'factura_compra_detalle', (SELECT COALESCE(max(id),0) FROM factura_compra_detalle),
        'cuenta_por_pagar',       (SELECT COALESCE(max(id),0) FROM cuenta_por_pagar),
        'pago_proveedor',         (SELECT COALESCE(max(id),0) FROM pago_proveedor),
        'log_auditoria',          (SELECT COALESCE(max(id),0) FROM log_auditoria)
    );

    FOR cur IN
        SELECT o.id AS oc, o.proveedor_id AS prov, o.estado,
               MAX(r.fecha_recepcion)::date AS recep, pr.dias_credito AS credito
        FROM orden_compra o
        JOIN proveedor pr ON pr.id = o.proveedor_id
        JOIN recepcion_mercancia r ON r.orden_compra_id = o.id
        WHERE o.observacion LIKE '[SEED-BA]%' AND o.estado IN ('recibida','recibida_parcial')
        GROUP BY o.id, o.proveedor_id, o.estado, pr.dias_credito
        ORDER BY o.id
    LOOP
        v_femit := cur.recep + (floor(random()*4))::int;              -- 0..3 dias tras recepcion
        IF v_femit > v_hoy THEN v_femit := v_hoy; END IF;
        v_fvenc := v_femit + cur.credito;
        v_seq := nextval('public.seq_numero_documento');
        v_fnum := 'FC-' || to_char(v_femit,'YYYYMMDD') || '-' || v_seq::text;
        v_reg := CASE WHEN random() < 0.5 THEN 11 ELSE 2 END;         -- COMPRAS / ADMIN

        INSERT INTO factura_compra (proveedor_id, orden_compra_id, moneda_id, numero_factura,
                                    fecha_emision, fecha_vencimiento, estado, registrado_por, fecha_creacion)
        VALUES (cur.prov, cur.oc, 1, v_fnum, v_femit, v_fvenc, 'registrada', v_reg,
                (v_femit::timestamptz + time '12:00'))
        RETURNING id INTO v_fc;

        INSERT INTO factura_compra_detalle (factura_compra_id, producto_variante_id, cantidad,
                                            precio_unitario, monto_impuesto)
        SELECT v_fc, d.producto_variante_id, d.cantidad_recibida, d.precio_unitario,
               round((d.cantidad_recibida * d.precio_unitario * 0.15)::numeric, 2)
        FROM orden_compra_detalle d
        WHERE d.orden_compra_id = cur.oc AND d.cantidad_recibida > 0;

        SELECT total INTO v_total FROM factura_compra WHERE id = v_fc;
        IF v_total IS NULL OR v_total <= 0 THEN
            -- sin lineas recibidas facturables: no deberia pasar, pero por seguridad
            CONTINUE;
        END IF;
        n_fac := n_fac + 1;

        -- auditoria de registro de factura (formato real accion INSERT)
        INSERT INTO log_auditoria (usuario_id, tabla, registro_id, accion, datos_nuevos, fecha_creacion)
        VALUES (v_reg, 'factura_compra', v_fc, 'INSERT',
                jsonb_build_object('estado','registrada','numero_factura',v_fnum,'orden_compra_id',cur.oc),
                (v_femit::timestamptz + time '12:00'));

        -- CxP (una por factura)
        INSERT INTO cuenta_por_pagar (factura_compra_id, proveedor_id, monto_original, saldo_pendiente,
                                      fecha_vencimiento, estado, fecha_creacion)
        VALUES (v_fc, cur.prov, v_total, v_total, v_fvenc, 'pendiente', (v_femit::timestamptz + time '12:05'))
        RETURNING id INTO v_cxp;

        -- comportamiento de pago (determinista por hash del OC)
        v_bucket := abs(hashtext(cur.oc::text)) % 100;
        IF    v_bucket < 70 THEN v_kind := 'full';
        ELSIF v_bucket < 85 THEN v_kind := 'partial';
        ELSE  v_kind := 'none'; END IF;

        IF v_kind IN ('full','partial') THEN
            v_late := (abs(hashtext(cur.oc::text || 'l')) % 100) < 35;   -- ~35% tardios
            IF v_late THEN
                v_pdate := v_fvenc + (2 + (abs(hashtext(cur.oc::text || 'd')) % 18));
            ELSE
                v_pdate := v_fvenc - (abs(hashtext(cur.oc::text || 'd')) % 7);
            END IF;
            IF v_pdate < v_femit + 1 THEN v_pdate := v_femit + 1; END IF;
            IF v_pdate > v_hoy THEN
                IF v_fvenc < v_hoy THEN v_pdate := v_hoy;   -- pago con retraso, dentro de plazo real
                ELSE v_kind := 'none'; END IF;              -- factura muy reciente: aun sin pagar
            END IF;
        END IF;

        v_metodo := CASE WHEN random() < 0.75 THEN 2 ELSE 1 END;        -- TRANSF / EFECT

        IF v_kind = 'full' THEN
            IF v_total > 500 AND random() < 0.4 THEN
                v_amt1 := round(v_total * 0.5, 2);
                INSERT INTO pago_proveedor (cuenta_por_pagar_id, metodo_pago_id, usuario_id, monto,
                                            fecha_pago, referencia, observacion)
                VALUES (v_cxp, v_metodo, v_reg, v_amt1, greatest(v_femit + 1, v_pdate - 3),
                        'TRF-' || to_char(v_pdate,'YYYYMMDD') || '-' || (abs(hashtext(cur.oc::text||'a'))%100000)::text,
                        '[SEED-BA] abono 1/2');
                INSERT INTO pago_proveedor (cuenta_por_pagar_id, metodo_pago_id, usuario_id, monto,
                                            fecha_pago, referencia, observacion)
                VALUES (v_cxp, v_metodo, v_reg, v_total - v_amt1, v_pdate,
                        'TRF-' || to_char(v_pdate,'YYYYMMDD') || '-' || (abs(hashtext(cur.oc::text||'b'))%100000)::text,
                        '[SEED-BA] abono 2/2');
                n_pay := n_pay + 2;
            ELSE
                INSERT INTO pago_proveedor (cuenta_por_pagar_id, metodo_pago_id, usuario_id, monto,
                                            fecha_pago, referencia, observacion)
                VALUES (v_cxp, v_metodo, v_reg, v_total, v_pdate,
                        'TRF-' || to_char(v_pdate,'YYYYMMDD') || '-' || (abs(hashtext(cur.oc::text))%100000)::text,
                        '[SEED-BA] pago total');
                n_pay := n_pay + 1;
            END IF;
            UPDATE cuenta_por_pagar SET saldo_pendiente = 0, estado = 'pagada' WHERE id = v_cxp;
            UPDATE factura_compra   SET estado = 'pagada' WHERE id = v_fc;

        ELSIF v_kind = 'partial' THEN
            v_paid := round((v_total * (0.3 + random()*0.4))::numeric, 2);
            IF v_paid <= 0 THEN v_paid := round(v_total*0.3,2); END IF;
            IF v_paid >= v_total THEN v_paid := round(v_total*0.5,2); END IF;
            INSERT INTO pago_proveedor (cuenta_por_pagar_id, metodo_pago_id, usuario_id, monto,
                                        fecha_pago, referencia, observacion)
            VALUES (v_cxp, v_metodo, v_reg, v_paid, v_pdate,
                    'TRF-' || to_char(v_pdate,'YYYYMMDD') || '-' || (abs(hashtext(cur.oc::text))%100000)::text,
                    '[SEED-BA] abono parcial');
            UPDATE cuenta_por_pagar SET saldo_pendiente = v_total - v_paid, estado = 'parcial' WHERE id = v_cxp;
            UPDATE factura_compra   SET estado = 'pagada_parcial' WHERE id = v_fc;
            n_pay := n_pay + 1; n_parc := n_parc + 1;

        ELSE  -- none: vigente por vencer o vencida
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
    VALUES ('seed_ba_58_facturas', v_thr::text, 'json',
            'Bloque A/58: facturas+CxP+pagos. Reversion: borrar id>umbral en orden pago->cxp->fac_detalle->factura.', now());

    INSERT INTO configuracion_tienda (clave, valor, tipo_dato, descripcion, fecha_creacion)
    VALUES ('seed_bloque_a', jsonb_build_object('completado', now())::text, 'json',
            'Bloque A (siembra ciclo de compra 18 meses) completado. Scripts 55-58.', now());

    RAISE NOTICE 'Bloque A / 58 OK. Facturas: %, pagos: %, parciales: %, vencidas: %, por-vencer: %',
                 n_fac, n_pay, n_parc, n_venc, n_pend;
END $$;
