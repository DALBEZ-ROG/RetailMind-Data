-- =====================================================================
-- 57_seed_bloque_a_ordenes_recepciones.sql
-- SIEMBRA DE VOLUMEN HISTORICO — BLOQUE A / Parte 3:
--   ORDENES DE COMPRA + APROBACIONES + RECEPCIONES + KARDEX + STOCK
-- ---------------------------------------------------------------------
-- Genera ~320 ordenes de compra distribuidas ene-2025 .. jul-2026 con
-- ESTACIONALIDAD ecuatoriana (picos de compra que anticipan Navidad,
-- inicio escolar Costa, Dia de la Madre y promos de noviembre), montos
-- y proveedores NO planos, costos con leve inflacion, y una minoria en
-- estados intermedios / cancelados.
--
-- INVARIANTES RESPETADAS:
--   * Totales de cabecera los pone el trigger fn_recalcular_total_orden_compra
--     desde el detalle (se insertan en 0). orden_compra_detalle.subtotal es
--     GENERATED: no se escribe. monto_impuesto = 15 % (IVA vigente).
--   * Cada recepcion confirmada genera movimiento_inventario 'entrada_compra'
--     con stock_anterior/nuevo continuos (leidos de inventario) y sube
--     inventario.stock_actual en la cantidad recibida (solo unidades buenas;
--     las rechazadas NUNCA entran a stock). Kardex y stock siguen cuadrando.
--   * Secuencia temporal: recepcion >= emision+1; nunca antes de emitir.
--   * Solo se compran variantes SIN kardex real (assortment sembrado en 55),
--     por lo que las cadenas demo reales quedan intactas.
--   * Auditoria: aprobacion enviada->confirmada en log_auditoria con el
--     mismo formato real (accion UPDATE, jsonb estado). (Las recepciones NO
--     se auditan porque el sistema real tampoco las audita.)
--   * Numeracion: OC-/RM- + nextval(seq_numero_documento), formato real.
--
-- Marca/reversion: observacion '[SEED-BA]' + umbrales en configuracion_tienda
--   (clave 'seed_ba_57_ordenes').
-- =====================================================================

DO $$
DECLARE
    v_thr jsonb;
    prov_ids  bigint[];
    prov_w    numeric[] := ARRAY[1.1, 1.6, 1.1, 1.0, 1.2, 1.0, 0.9, 1.0, 0.6];
    prov_lead int[]     := ARRAY[12,  5,   8,   10,  9,   7,   6,   8,   15 ];
    prov_punt int[]     := ARRAY[1,  -1,   3,   6,   2,  -2,   4,   1,   8  ];
    n_prov int; wtot numeric; wcum numeric;
    v_motivos text[] := ARRAY['Empaque danado en transito','Producto con defecto de fabrica','No coincide con especificacion','Fecha de caducidad proxima','Unidades incompletas en caja'];

    y int; mo int; mi_idx int; wt numeric; yearf numeric; n_ord int;
    o int; s int;
    v_prov bigint; v_slot int; v_lead int; v_punt int; v_bod bigint;
    v_emit date; v_esper date; v_recep date; v_off int;
    v_estado text; v_isrecent boolean; r numeric;
    v_oc bigint; v_ocnum text; v_seq bigint;
    n_lines int; li int; got int;
    v_var bigint; v_costo numeric; v_qty int; v_price numeric; v_imp numeric; v_ocd bigint;
    v_rm bigint; v_recibida int; v_rech int; v_partial boolean; v_reject boolean;
    v_ant int; v_new int;
    v_appr_user bigint; v_appr_date timestamptz;
    cur record;
    tot_ord int := 0; tot_recep int := 0; tot_mov int := 0;
BEGIN
    IF EXISTS (SELECT 1 FROM configuracion_tienda WHERE clave = 'seed_ba_57_ordenes') THEN
        RAISE NOTICE 'Bloque A / 57 (ordenes) ya sembrado; se omite.';
        RETURN;
    END IF;

    PERFORM setseed(0.5701);

    v_thr := jsonb_build_object(
        'orden_compra',          (SELECT COALESCE(max(id),0) FROM orden_compra),
        'orden_compra_detalle',  (SELECT COALESCE(max(id),0) FROM orden_compra_detalle),
        'recepcion_mercancia',   (SELECT COALESCE(max(id),0) FROM recepcion_mercancia),
        'recepcion_detalle',     (SELECT COALESCE(max(id),0) FROM recepcion_detalle),
        'movimiento_inventario', (SELECT COALESCE(max(id),0) FROM movimiento_inventario),
        'log_auditoria',         (SELECT COALESCE(max(id),0) FROM log_auditoria)
    );

    -- proveedores sembrados en orden de creacion (slots 1..9)
    SELECT array_agg(id ORDER BY id) INTO prov_ids
    FROM proveedor WHERE ruc LIKE '%2500%' AND ruc LIKE '%001';
    n_prov := array_length(prov_ids,1);
    wtot := 0; FOR s IN 1..n_prov LOOP wtot := wtot + prov_w[s]; END LOOP;

    -- ================= LOOP MENSUAL (ene-2025 .. jul-2026) =============
    FOR y IN 2025..2026 LOOP
      FOR mo IN 1..12 LOOP
        EXIT WHEN (y = 2026 AND mo > 7);
        mi_idx := (y-2025)*12 + (mo-1);
        -- peso estacional de COMPRAS (anticipan los picos de venta)
        wt := CASE mo
                WHEN 1 THEN 0.7 WHEN 2 THEN 0.9 WHEN 3 THEN 1.3 WHEN 4 THEN 1.2
                WHEN 5 THEN 0.9 WHEN 6 THEN 0.7 WHEN 7 THEN 0.8 WHEN 8 THEN 0.9
                WHEN 9 THEN 1.0 WHEN 10 THEN 1.4 WHEN 11 THEN 1.5 ELSE 1.0 END;
        yearf := CASE WHEN y = 2026 THEN 1.15 ELSE 1.0 END;   -- negocio en crecimiento
        n_ord := greatest(1, round(wt * yearf * 16.2)::int);

        FOR o IN 1..n_ord LOOP
            tot_ord := tot_ord + 1;
            -- proveedor (seleccion ponderada, no plana)
            r := random() * wtot; wcum := 0; v_slot := 1;
            FOR s IN 1..n_prov LOOP
                wcum := wcum + prov_w[s];
                IF r <= wcum THEN v_slot := s; EXIT; END IF;
            END LOOP;
            v_prov := prov_ids[v_slot]; v_lead := prov_lead[v_slot]; v_punt := prov_punt[v_slot];
            v_bod  := CASE WHEN random() < 0.85 THEN 4 ELSE 3 END;

            IF y = 2026 AND mo = 7 THEN
                v_emit := date '2026-07-01' + (floor(random()*21))::int;   -- hasta 2026-07-21 ("hoy" es 07-23)
            ELSE
                v_emit := make_date(y, mo, 1) + (floor(random()*28))::int;
            END IF;
            v_esper := v_emit + v_lead;
            v_isrecent := v_emit >= date '2026-05-20';

            -- estado / ciclo de vida
            r := random();
            IF r < 0.05 THEN
                v_estado := 'cancelada';
            ELSIF v_isrecent THEN
                r := random();
                v_estado := CASE WHEN r < 0.22 THEN 'enviada'
                                 WHEN r < 0.44 THEN 'confirmada'
                                 WHEN r < 0.60 THEN 'recibida_parcial'
                                 ELSE 'recibida' END;
            ELSE
                v_estado := CASE WHEN random() < 0.90 THEN 'recibida' ELSE 'recibida_parcial' END;
            END IF;

            v_seq := nextval('public.seq_numero_documento');
            v_ocnum := 'OC-' || to_char(v_emit,'YYYYMMDD') || '-' || v_seq::text;

            INSERT INTO orden_compra (numero, proveedor_id, bodega_id, moneda_id, usuario_id, estado,
                                      fecha_emision, fecha_entrega_esperada, observacion, fecha_creacion)
            VALUES (v_ocnum, v_prov, v_bod, 1, 11, v_estado, v_emit, v_esper,
                    '[SEED-BA] abastecimiento historico', (v_emit::timestamptz + time '09:15'))
            RETURNING id INTO v_oc;

            -- recepcion (solo si recibida / recibida_parcial)
            v_rm := NULL;
            IF v_estado IN ('recibida','recibida_parcial') THEN
                v_off := v_punt + (floor(random()*5) - 2)::int;   -- variacion de entrega
                v_recep := v_esper + v_off;
                IF v_recep > date '2026-07-22' THEN v_recep := date '2026-07-22'; END IF;
                IF v_recep <= v_emit THEN v_recep := v_emit + 1; END IF;
                v_seq := nextval('public.seq_numero_documento');
                INSERT INTO recepcion_mercancia (numero, orden_compra_id, bodega_id, usuario_id, estado,
                                                 fecha_recepcion, observacion, fecha_creacion)
                VALUES ('RM-' || to_char(v_recep,'YYYYMMDD') || '-' || v_seq::text, v_oc, v_bod, 9,
                        'confirmada', (v_recep::timestamptz + time '10:30'),
                        '[SEED-BA] recepcion', (v_recep::timestamptz + time '10:30'))
                RETURNING id INTO v_rm;
                tot_recep := tot_recep + 1;
            END IF;

            v_partial := (v_estado = 'recibida_parcial');
            v_reject  := (v_estado = 'recibida' AND random() < 0.12);

            n_lines := 2 + floor(random()*5)::int;   -- 2..6
            li := 0;
            FOR cur IN
                SELECT pp.producto_variante_id AS var, pp.costo AS costo
                FROM producto_proveedor pp
                WHERE pp.proveedor_id = v_prov AND pp.es_preferido AND pp.activo
                ORDER BY random() LIMIT n_lines
            LOOP
                li := li + 1;
                v_var := cur.var; v_costo := cur.costo;
                IF v_costo < 20 THEN v_qty := 40 + floor(random()*111)::int;
                ELSIF v_costo < 80 THEN v_qty := 15 + floor(random()*46)::int;
                ELSE v_qty := 5 + floor(random()*21)::int; END IF;
                v_price := round((v_costo * power(1.006, mi_idx) * (0.98 + random()*0.05))::numeric, 2);
                IF v_price <= 0 THEN v_price := round(v_costo,2) + 0.01; END IF;
                v_imp := round(v_qty * v_price * 0.15, 2);

                INSERT INTO orden_compra_detalle (orden_compra_id, producto_variante_id, cantidad,
                                                  precio_unitario, monto_impuesto, cantidad_recibida)
                VALUES (v_oc, v_var, v_qty, v_price, v_imp, 0)
                RETURNING id INTO v_ocd;

                IF v_rm IS NOT NULL THEN
                    -- cantidad recibida (buena) y rechazada
                    v_rech := 0;
                    IF v_partial AND (li = 1 OR random() < 0.5) THEN
                        v_recibida := greatest(1, floor(v_qty * (0.4 + random()*0.4))::int);
                    ELSE
                        v_recibida := v_qty;
                    END IF;
                    IF v_reject AND li = 1 THEN
                        v_rech := 1 + floor(random()*3)::int;   -- unidades rechazadas en puerta
                    END IF;

                    UPDATE orden_compra_detalle SET cantidad_recibida = v_recibida WHERE id = v_ocd;

                    INSERT INTO recepcion_detalle (recepcion_mercancia_id, orden_compra_detalle_id,
                                                   cantidad_recibida, cantidad_rechazada, motivo_rechazo)
                    VALUES (v_rm, v_ocd, v_recibida, v_rech,
                            CASE WHEN v_rech > 0 THEN v_motivos[1 + floor(random()*array_length(v_motivos,1))::int] END);

                    -- movimiento de kardex (entrada por compra) + stock
                    SELECT stock_actual INTO v_ant FROM inventario
                      WHERE producto_variante_id = v_var AND bodega_id = v_bod FOR UPDATE;
                    IF v_ant IS NULL THEN
                        -- no habia fila de inventario en esa bodega: crearla en 0
                        INSERT INTO inventario (producto_variante_id, bodega_id, stock_actual, stock_reservado, stock_minimo)
                        VALUES (v_var, v_bod, 0, 0, 0)
                        ON CONFLICT DO NOTHING;
                        v_ant := 0;
                    END IF;
                    v_new := v_ant + v_recibida;
                    INSERT INTO movimiento_inventario (producto_variante_id, bodega_id, tipo_movimiento_id,
                        usuario_id, cantidad, stock_anterior, stock_nuevo, costo_unitario,
                        referencia_tipo, referencia_id, observacion, fecha_creacion)
                    VALUES (v_var, v_bod, 1, 9, v_recibida, v_ant, v_new, v_price,
                            'recepcion_mercancia', v_rm, '[SEED-BA] entrada por compra',
                            (v_recep::timestamptz + time '10:35'));
                    UPDATE inventario SET stock_actual = v_new
                      WHERE producto_variante_id = v_var AND bodega_id = v_bod;
                    tot_mov := tot_mov + 1;
                END IF;
            END LOOP;

            -- auditoria de aprobacion (enviada -> confirmada) para estados aprobados
            IF v_estado IN ('confirmada','recibida_parcial','recibida') THEN
                v_appr_user := CASE WHEN random() < 0.5 THEN 6 ELSE 2 END;   -- GERENTE / ADMIN
                v_appr_date := (v_emit::timestamptz + time '11:00') + (1 + floor(random()*3)) * interval '1 day';
                IF v_appr_date > timestamptz '2026-07-23 12:00-05' THEN v_appr_date := timestamptz '2026-07-23 12:00-05'; END IF;
                INSERT INTO log_auditoria (usuario_id, tabla, registro_id, accion,
                                           datos_anteriores, datos_nuevos, fecha_creacion)
                VALUES (v_appr_user, 'orden_compra', v_oc, 'UPDATE',
                        '{"estado":"enviada"}'::jsonb, '{"estado":"confirmada"}'::jsonb, v_appr_date);
            END IF;
        END LOOP;
      END LOOP;
    END LOOP;

    INSERT INTO configuracion_tienda (clave, valor, tipo_dato, descripcion, fecha_creacion)
    VALUES ('seed_ba_57_ordenes', v_thr::text, 'json',
            'Bloque A/57: ordenes+recepciones+kardex. Reversion: revertir stock por movimientos entrada compra sembrados y borrar id>umbral.', now());

    RAISE NOTICE 'Bloque A / 57 OK. Ordenes: %, recepciones: %, movimientos entrada: %', tot_ord, tot_recep, tot_mov;
END $$;
