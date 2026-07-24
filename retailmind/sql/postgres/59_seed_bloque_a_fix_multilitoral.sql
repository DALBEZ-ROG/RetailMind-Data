-- =====================================================================
-- 59_seed_bloque_a_fix_multilitoral.sql
-- SIEMBRA DE VOLUMEN HISTORICO — BLOQUE A / Correccion:
--   El proveedor MultiLitoral (slot 9) quedo SOLO como fuente secundaria
--   (es_preferido=false) en el script 55, de modo que en el 57 sus
--   ordenes salieron SIN lineas (el 57 compra desde el catalogo PREFERIDO
--   del proveedor). Resultado: 22 ordenes vacias + 20 facturas huerfanas
--   (total 0, sin detalle ni CxP).
--
-- Este script:
--   A) BORRA las 22 ordenes vacias de MultiLitoral y todo lo que colgaba
--      de ellas (facturas vacias, recepciones vacias, auditorias de
--      aprobacion). No tocaban stock ni CxP: eliminacion limpia.
--   B) Da a MultiLitoral un ASSORTMENT PREFERIDO real (variantes de
--      Abarrotes/Hogar aun sin proveedor preferido) — enriquece ademas
--      OTD-COM-10.
--   C) REGENERA el ciclo de compra completo de MultiLitoral (~20 ordenes
--      con estacionalidad, recepciones, kardex/stock, factura, CxP y pago,
--      proveedor cronicamente TARDIO para dar contraste).
--
-- Cubierto por el mismo esquema de reversion (ids > umbral de 57/58).
-- Idempotencia: clave 'seed_ba_59_fix'.
-- =====================================================================

DO $$
DECLARE
    v_ml bigint;
    v_empty bigint[];
    v_lead int := 15; v_punt int := 8;      -- MultiLitoral: lejano y tardio
    v_motivos text[] := ARRAY['Empaque danado en transito','Producto con defecto de fabrica','No coincide con especificacion','Fecha de caducidad proxima','Unidades incompletas en caja'];
    y int; mo int; mi_idx int; wt numeric; yearf numeric; n_ord int;
    o int;
    v_emit date; v_esper date; v_recep date; v_off int; v_bod bigint;
    v_estado text; v_isrecent boolean; r numeric; v_seq bigint;
    v_oc bigint; v_ocnum text; n_lines int; li int;
    v_var bigint; v_costo numeric; v_qty int; v_price numeric; v_imp numeric; v_ocd bigint;
    v_rm bigint; v_recibida int; v_rech int; v_partial boolean; v_reject boolean;
    v_ant int; v_new int; v_appr_user bigint; v_appr_date timestamptz;
    v_femit date; v_fvenc date; v_fnum text; v_reg bigint; v_fc bigint; v_cxp bigint;
    v_total numeric; v_bucket int; v_kind text; v_late boolean; v_pdate date; v_metodo bigint; v_paid numeric;
    v_hoy date := date '2026-07-23';
    cur record;
    tot_ord int := 0; tot_fac int := 0;
BEGIN
    IF EXISTS (SELECT 1 FROM configuracion_tienda WHERE clave = 'seed_ba_59_fix') THEN
        RAISE NOTICE 'Bloque A / 59 (fix MultiLitoral) ya aplicado; se omite.';
        RETURN;
    END IF;

    PERFORM setseed(0.5901);
    SELECT id INTO v_ml FROM proveedor WHERE ruc = '0792500019001';

    -- ============ A) Borrar ordenes vacias de MultiLitoral ============
    SELECT array_agg(o.id) INTO v_empty
    FROM orden_compra o
    WHERE o.proveedor_id = v_ml AND o.observacion LIKE '[SEED-BA]%'
      AND NOT EXISTS (SELECT 1 FROM orden_compra_detalle d WHERE d.orden_compra_id = o.id);

    IF v_empty IS NOT NULL THEN
        DELETE FROM factura_compra      WHERE orden_compra_id = ANY(v_empty);
        DELETE FROM recepcion_mercancia WHERE orden_compra_id = ANY(v_empty);
        DELETE FROM log_auditoria       WHERE tabla = 'orden_compra' AND registro_id = ANY(v_empty);
        DELETE FROM orden_compra        WHERE id = ANY(v_empty);
        RAISE NOTICE 'Ordenes vacias de MultiLitoral eliminadas: %', array_length(v_empty,1);
    END IF;

    -- ============ B) Assortment PREFERIDO para MultiLitoral ============
    -- Variantes de Abarrotes(5)/Hogar(11) aun sin proveedor preferido y
    -- sin kardex real.
    WITH cand AS (
        SELECT pv.id AS variante_id, pv.costo, min(pc.categoria_id) AS cat
        FROM producto_variante pv
        JOIN producto p ON p.id = pv.producto_id
        JOIN producto_categoria pc ON pc.producto_id = p.id
        WHERE pv.costo IS NOT NULL AND pv.costo > 0
          -- excluir SOLO variantes con kardex REAL (no las de apertura/compra sembradas)
          AND pv.id NOT IN (SELECT producto_variante_id FROM movimiento_inventario
                            WHERE referencia_tipo NOT IN ('recepcion_mercancia','inventario_inicial'))
          AND NOT EXISTS (SELECT 1 FROM producto_proveedor pp WHERE pp.producto_variante_id = pv.id AND pp.es_preferido)
        GROUP BY pv.id, pv.costo
    ),
    ranked AS (
        SELECT *, row_number() OVER (PARTITION BY cat ORDER BY variante_id) rn FROM cand WHERE cat IN (5,11)
    )
    INSERT INTO producto_proveedor (proveedor_id, producto_variante_id, codigo_proveedor, costo,
                                    tiempo_entrega_dias, cantidad_minima, es_preferido, activo)
    SELECT v_ml, r.variante_id, 'MLT-'||lpad(r.variante_id::text,5,'0'), round(r.costo,2), 15, 1, true, true
    FROM ranked r WHERE r.rn <= 55
    ON CONFLICT (proveedor_id, producto_variante_id) DO UPDATE SET es_preferido = true;

    -- ============ C) Regenerar ciclo de compra de MultiLitoral =========
    FOR y IN 2025..2026 LOOP
      FOR mo IN 1..12 LOOP
        EXIT WHEN (y = 2026 AND mo > 7);
        mi_idx := (y-2025)*12 + (mo-1);
        wt := CASE mo WHEN 3 THEN 1.3 WHEN 4 THEN 1.2 WHEN 10 THEN 1.4 WHEN 11 THEN 1.5
                      WHEN 1 THEN 0.7 WHEN 6 THEN 0.7 ELSE 1.0 END;
        yearf := CASE WHEN y = 2026 THEN 1.15 ELSE 1.0 END;
        n_ord := round(wt * yearf * 1.05)::int;   -- ~0-2 por mes, ~20 total

        FOR o IN 1..n_ord LOOP
            tot_ord := tot_ord + 1;
            v_bod := CASE WHEN random() < 0.85 THEN 4 ELSE 3 END;
            IF y = 2026 AND mo = 7 THEN v_emit := date '2026-07-01' + (floor(random()*21))::int;
            ELSE v_emit := make_date(y, mo, 1) + (floor(random()*28))::int; END IF;
            v_esper := v_emit + v_lead;
            v_isrecent := v_emit >= date '2026-05-20';

            r := random();
            IF r < 0.05 THEN v_estado := 'cancelada';
            ELSIF v_isrecent THEN
                r := random();
                v_estado := CASE WHEN r < 0.22 THEN 'enviada' WHEN r < 0.44 THEN 'confirmada'
                                 WHEN r < 0.60 THEN 'recibida_parcial' ELSE 'recibida' END;
            ELSE v_estado := CASE WHEN random() < 0.90 THEN 'recibida' ELSE 'recibida_parcial' END;
            END IF;

            v_seq := nextval('public.seq_numero_documento');
            v_ocnum := 'OC-' || to_char(v_emit,'YYYYMMDD') || '-' || v_seq::text;
            INSERT INTO orden_compra (numero, proveedor_id, bodega_id, moneda_id, usuario_id, estado,
                                      fecha_emision, fecha_entrega_esperada, observacion, fecha_creacion)
            VALUES (v_ocnum, v_ml, v_bod, 1, 11, v_estado, v_emit, v_esper,
                    '[SEED-BA] abastecimiento historico', (v_emit::timestamptz + time '09:15'))
            RETURNING id INTO v_oc;

            v_rm := NULL;
            IF v_estado IN ('recibida','recibida_parcial') THEN
                v_off := v_punt + (floor(random()*5) - 2)::int;
                v_recep := v_esper + v_off;
                IF v_recep > date '2026-07-22' THEN v_recep := date '2026-07-22'; END IF;
                IF v_recep <= v_emit THEN v_recep := v_emit + 1; END IF;
                v_seq := nextval('public.seq_numero_documento');
                INSERT INTO recepcion_mercancia (numero, orden_compra_id, bodega_id, usuario_id, estado,
                                                 fecha_recepcion, observacion, fecha_creacion)
                VALUES ('RM-'||to_char(v_recep,'YYYYMMDD')||'-'||v_seq::text, v_oc, v_bod, 9, 'confirmada',
                        (v_recep::timestamptz + time '10:30'), '[SEED-BA] recepcion', (v_recep::timestamptz + time '10:30'))
                RETURNING id INTO v_rm;
            END IF;

            v_partial := (v_estado = 'recibida_parcial');
            v_reject  := (v_estado = 'recibida' AND random() < 0.12);
            n_lines := 2 + floor(random()*4)::int;   -- 2..5
            li := 0;
            FOR cur IN
                SELECT pp.producto_variante_id AS var, pp.costo AS costo
                FROM producto_proveedor pp
                WHERE pp.proveedor_id = v_ml AND pp.es_preferido AND pp.activo
                ORDER BY random() LIMIT n_lines
            LOOP
                li := li + 1; v_var := cur.var; v_costo := cur.costo;
                IF v_costo < 20 THEN v_qty := 40 + floor(random()*111)::int;
                ELSIF v_costo < 80 THEN v_qty := 15 + floor(random()*46)::int;
                ELSE v_qty := 5 + floor(random()*21)::int; END IF;
                v_price := round((v_costo * power(1.006, mi_idx) * (0.98 + random()*0.05))::numeric, 2);
                IF v_price <= 0 THEN v_price := round(v_costo,2) + 0.01; END IF;
                v_imp := round(v_qty * v_price * 0.15, 2);
                INSERT INTO orden_compra_detalle (orden_compra_id, producto_variante_id, cantidad,
                                                  precio_unitario, monto_impuesto, cantidad_recibida)
                VALUES (v_oc, v_var, v_qty, v_price, v_imp, 0) RETURNING id INTO v_ocd;

                IF v_rm IS NOT NULL THEN
                    v_rech := 0;
                    IF v_partial AND (li = 1 OR random() < 0.5) THEN
                        v_recibida := greatest(1, floor(v_qty * (0.4 + random()*0.4))::int);
                    ELSE v_recibida := v_qty; END IF;
                    IF v_reject AND li = 1 THEN v_rech := 1 + floor(random()*3)::int; END IF;
                    UPDATE orden_compra_detalle SET cantidad_recibida = v_recibida WHERE id = v_ocd;
                    INSERT INTO recepcion_detalle (recepcion_mercancia_id, orden_compra_detalle_id,
                                                   cantidad_recibida, cantidad_rechazada, motivo_rechazo)
                    VALUES (v_rm, v_ocd, v_recibida, v_rech,
                            CASE WHEN v_rech>0 THEN v_motivos[1 + floor(random()*array_length(v_motivos,1))::int] END);
                    SELECT stock_actual INTO v_ant FROM inventario
                      WHERE producto_variante_id = v_var AND bodega_id = v_bod FOR UPDATE;
                    IF v_ant IS NULL THEN
                        INSERT INTO inventario (producto_variante_id, bodega_id, stock_actual, stock_reservado, stock_minimo)
                        VALUES (v_var, v_bod, 0, 0, 0) ON CONFLICT DO NOTHING;
                        v_ant := 0;
                    END IF;
                    v_new := v_ant + v_recibida;
                    INSERT INTO movimiento_inventario (producto_variante_id, bodega_id, tipo_movimiento_id,
                        usuario_id, cantidad, stock_anterior, stock_nuevo, costo_unitario,
                        referencia_tipo, referencia_id, observacion, fecha_creacion)
                    VALUES (v_var, v_bod, 1, 9, v_recibida, v_ant, v_new, v_price,
                            'recepcion_mercancia', v_rm, '[SEED-BA] entrada por compra', (v_recep::timestamptz + time '10:35'));
                    UPDATE inventario SET stock_actual = v_new WHERE producto_variante_id = v_var AND bodega_id = v_bod;
                END IF;
            END LOOP;

            IF v_estado IN ('confirmada','recibida_parcial','recibida') THEN
                v_appr_user := CASE WHEN random() < 0.5 THEN 6 ELSE 2 END;
                v_appr_date := (v_emit::timestamptz + time '11:00') + (1 + floor(random()*3)) * interval '1 day';
                IF v_appr_date > timestamptz '2026-07-23 12:00-05' THEN v_appr_date := timestamptz '2026-07-23 12:00-05'; END IF;
                INSERT INTO log_auditoria (usuario_id, tabla, registro_id, accion, datos_anteriores, datos_nuevos, fecha_creacion)
                VALUES (v_appr_user, 'orden_compra', v_oc, 'UPDATE', '{"estado":"enviada"}'::jsonb, '{"estado":"confirmada"}'::jsonb, v_appr_date);
            END IF;

            -- factura + CxP + pago (para recibidas)
            IF v_estado IN ('recibida','recibida_parcial') THEN
                v_femit := v_recep + (floor(random()*4))::int;
                IF v_femit > v_hoy THEN v_femit := v_hoy; END IF;
                v_fvenc := v_femit + 60;   -- credito de MultiLitoral
                v_seq := nextval('public.seq_numero_documento');
                v_fnum := 'FC-'||to_char(v_femit,'YYYYMMDD')||'-'||v_seq::text;
                v_reg := CASE WHEN random()<0.5 THEN 11 ELSE 2 END;
                INSERT INTO factura_compra (proveedor_id, orden_compra_id, moneda_id, numero_factura,
                                            fecha_emision, fecha_vencimiento, estado, registrado_por, fecha_creacion)
                VALUES (v_ml, v_oc, 1, v_fnum, v_femit, v_fvenc, 'registrada', v_reg, (v_femit::timestamptz + time '12:00'))
                RETURNING id INTO v_fc;
                INSERT INTO factura_compra_detalle (factura_compra_id, producto_variante_id, cantidad, precio_unitario, monto_impuesto)
                SELECT v_fc, d.producto_variante_id, d.cantidad_recibida, d.precio_unitario,
                       round((d.cantidad_recibida * d.precio_unitario * 0.15)::numeric, 2)
                FROM orden_compra_detalle d WHERE d.orden_compra_id = v_oc AND d.cantidad_recibida > 0;
                SELECT total INTO v_total FROM factura_compra WHERE id = v_fc;
                IF v_total IS NULL OR v_total <= 0 THEN CONTINUE; END IF;
                tot_fac := tot_fac + 1;
                INSERT INTO log_auditoria (usuario_id, tabla, registro_id, accion, datos_nuevos, fecha_creacion)
                VALUES (v_reg, 'factura_compra', v_fc, 'INSERT',
                        jsonb_build_object('estado','registrada','numero_factura',v_fnum,'orden_compra_id',v_oc),
                        (v_femit::timestamptz + time '12:00'));
                INSERT INTO cuenta_por_pagar (factura_compra_id, proveedor_id, monto_original, saldo_pendiente,
                                              fecha_vencimiento, estado, fecha_creacion)
                VALUES (v_fc, v_ml, v_total, v_total, v_fvenc, 'pendiente', (v_femit::timestamptz + time '12:05'))
                RETURNING id INTO v_cxp;

                v_bucket := abs(hashtext(v_oc::text)) % 100;
                IF v_bucket < 70 THEN v_kind := 'full';
                ELSIF v_bucket < 85 THEN v_kind := 'partial'; ELSE v_kind := 'none'; END IF;
                IF v_kind IN ('full','partial') THEN
                    v_late := (abs(hashtext(v_oc::text||'l')) % 100) < 55;   -- MultiLitoral paga tarde mas seguido
                    IF v_late THEN v_pdate := v_fvenc + (2 + (abs(hashtext(v_oc::text||'d'))%18));
                    ELSE v_pdate := v_fvenc - (abs(hashtext(v_oc::text||'d'))%7); END IF;
                    IF v_pdate < v_femit + 1 THEN v_pdate := v_femit + 1; END IF;
                    IF v_pdate > v_hoy THEN
                        IF v_fvenc < v_hoy THEN v_pdate := v_hoy; ELSE v_kind := 'none'; END IF;
                    END IF;
                END IF;
                v_metodo := CASE WHEN random()<0.75 THEN 2 ELSE 1 END;
                IF v_kind = 'full' THEN
                    INSERT INTO pago_proveedor (cuenta_por_pagar_id, metodo_pago_id, usuario_id, monto, fecha_pago, referencia, observacion)
                    VALUES (v_cxp, v_metodo, v_reg, v_total, v_pdate,
                            'TRF-'||to_char(v_pdate,'YYYYMMDD')||'-'||(abs(hashtext(v_oc::text))%100000)::text, '[SEED-BA] pago total');
                    UPDATE cuenta_por_pagar SET saldo_pendiente=0, estado='pagada' WHERE id=v_cxp;
                    UPDATE factura_compra SET estado='pagada' WHERE id=v_fc;
                ELSIF v_kind = 'partial' THEN
                    v_paid := round((v_total*(0.3+random()*0.4))::numeric,2);
                    IF v_paid<=0 THEN v_paid:=round(v_total*0.3,2); END IF;
                    IF v_paid>=v_total THEN v_paid:=round(v_total*0.5,2); END IF;
                    INSERT INTO pago_proveedor (cuenta_por_pagar_id, metodo_pago_id, usuario_id, monto, fecha_pago, referencia, observacion)
                    VALUES (v_cxp, v_metodo, v_reg, v_paid, v_pdate,
                            'TRF-'||to_char(v_pdate,'YYYYMMDD')||'-'||(abs(hashtext(v_oc::text))%100000)::text, '[SEED-BA] abono parcial');
                    UPDATE cuenta_por_pagar SET saldo_pendiente=v_total-v_paid, estado='parcial' WHERE id=v_cxp;
                    UPDATE factura_compra SET estado='pagada_parcial' WHERE id=v_fc;
                ELSE
                    IF v_fvenc < v_hoy THEN UPDATE cuenta_por_pagar SET estado='vencida' WHERE id=v_cxp;
                    ELSE UPDATE cuenta_por_pagar SET estado='pendiente' WHERE id=v_cxp; END IF;
                END IF;
            END IF;
        END LOOP;
      END LOOP;
    END LOOP;

    INSERT INTO configuracion_tienda (clave, valor, tipo_dato, descripcion, fecha_creacion)
    VALUES ('seed_ba_59_fix', jsonb_build_object('ordenes_ml', tot_ord, 'facturas_ml', tot_fac)::text, 'json',
            'Bloque A/59: correccion MultiLitoral (assortment preferido + ciclo regenerado).', now());

    RAISE NOTICE 'Bloque A / 59 OK. Ordenes MultiLitoral: %, facturas: %', tot_ord, tot_fac;
END $$;
