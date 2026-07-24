-- =====================================================================
-- 62_seed_bloque_b_devoluciones.sql
-- SIEMBRA DE VOLUMEN HISTORICO - BLOQUE B / Parte 3: POSVENTA LOGISTICA
--   RMA (devoluciones de cliente) + reembolsos + novedades de envio
-- ---------------------------------------------------------------------
-- PARTE A - RMA: ~5 % de los pedidos ENTREGADOS generan una devolucion,
-- recorriendo el ciclo real solicitada -> en_revision ->
-- aprobada|rechazada -> en_transito -> recibida -> inspeccionada ->
-- reembolsada -> cerrada, con historial paso a paso (autor cliente o
-- usuario segun el rol de la transicion), motivos variados, resultado de
-- inspeccion POR LINEA, reingreso de stock SOLO en lineas 'apto_reventa'
-- (entrada_devolucion_cliente, el pedido pasa a 'devuelto'), pool de
-- 'defectuoso' en item_defectuoso (origen rma, para OTD-COM-08) y fila en
-- 'reembolso' para las reembolsadas. Una minoria queda EN CURSO.
--
-- PARTE B - NOVEDADES DE ENVIO: incidencias de entrega variadas con sus
-- intentos y resolucion (reprogramada / devuelto_almacen) y una minoria
-- todavia ABIERTA. Fuente: envios devueltos (no_entregado ->
-- devuelto_almacen), entregados (reprogramada) y en_transito (abierta).
--
-- INVARIANTES:
--   * devolucion.monto_total lo pone el trigger fn_recalcular_total_
--     devolucion (SECURITY DEFINER): NUNCA se escribe.
--   * Reingreso: movimiento_inventario tipo 2 (entrada_devolucion_cliente,
--     factor +1) con stock_anterior/nuevo continuos y stock_actual += qty.
--     El kardex sigue cuadrando; el reingreso solo SUMA, jamas negativo.
--   * Secuencia temporal del ciclo respetada; devolucion dentro del plazo
--     de 30 dias desde la entrega; nada en el futuro.
--   * item_defectuoso origen 'rma' exige devolucion_detalle_id NOT NULL y
--     recepcion_detalle_id NULL (CHECK origen_coherente).
--   * novedad coherencia: (estado='abierta') = (accion IS NULL).
--   * Auditoria: la app audita novedad_envio (INSERT registrar + UPDATE
--     resolver): se replica su formato real. El RMA no se audita via
--     log_auditoria en la app, asi que aqui tampoco.
--
-- Numeracion: DV-/RET- + nextval(seq_numero_documento).
-- Marca/reversion: umbrales en configuracion_tienda 'seed_bb_62_posventa'
--   (+ restaurar stock y estado de pedido en la reversion). Como postgres.
-- =====================================================================

DO $$
DECLARE
    v_thr jsonb;
    v_now constant timestamptz := timestamptz '2026-07-24 12:00:00-05';
    sop_ids bigint[] := ARRAY[12,18,19,20];      -- soporte
    v_sop bigint; v_ger bigint;
    -- RMA
    rec record; lin record;
    v_dev bigint; v_num text; v_seq bigint; v_final int; v_rech boolean;
    v_ts1 timestamptz; v_ts2 timestamptz; v_ts3 timestamptz; v_ts4 timestamptz;
    v_ts5 timestamptz; v_ts6 timestamptz; v_ts7 timestamptz; v_ts8 timestamptz;
    v_motivo bigint; v_transp bigint; v_guia text; v_estado_dev text;
    v_ddet bigint; v_result text; v_costo numeric; v_ant int; v_new int;
    v_reingresa boolean; v_reembolso numeric; v_pago bigint; v_metreem text;
    v_pedido_devuelto boolean;
    n_dev int:=0; n_reemb int:=0; n_reingreso int:=0; n_defect int:=0; n_rechaz int:=0;
    -- Novedades
    v_env record; v_nov bigint; v_tipo text; v_intentos int; v_accion text;
    v_freg timestamptz; v_fres timestamptz; v_kind text;
    n_nov int:=0; n_open int:=0; n_reprog int:=0; n_devalm int:=0;
BEGIN
    IF EXISTS (SELECT 1 FROM configuracion_tienda WHERE clave='seed_bb_62_posventa') THEN
        RAISE NOTICE 'Bloque B / 62 (posventa) ya sembrado; se omite.';
        RETURN;
    END IF;

    PERFORM setseed(0.6201);

    v_thr := jsonb_build_object(
        'devolucion',                 (SELECT COALESCE(max(id),0) FROM devolucion),
        'devolucion_detalle',         (SELECT COALESCE(max(id),0) FROM devolucion_detalle),
        'historial_estado_devolucion',(SELECT COALESCE(max(id),0) FROM historial_estado_devolucion),
        'reembolso',                  (SELECT COALESCE(max(id),0) FROM reembolso),
        'movimiento_inventario',      (SELECT COALESCE(max(id),0) FROM movimiento_inventario),
        'item_defectuoso',            (SELECT COALESCE(max(id),0) FROM item_defectuoso),
        'novedad_envio',              (SELECT COALESCE(max(id),0) FROM novedad_envio),
        'log_auditoria',              (SELECT COALESCE(max(id),0) FROM log_auditoria));

    -- =================================================================
    -- PARTE A: RMA  (~5% de los pedidos entregados sembrados)
    -- =================================================================
    CREATE TEMP TABLE rma_ped ON COMMIT DROP AS
    SELECT p.id AS pedido_id, p.cliente_id,
           (SELECT max(h.fecha_creacion) FROM historial_estado_pedido h
             WHERE h.pedido_id=p.id AND h.estado_pedido_id=6) AS entregado_en
    FROM pedido p
    WHERE p.id>35 AND p.estado_pedido_id=6
    ORDER BY random() LIMIT 220;

    -- solo pedidos entregados hace >=3 dias: da margen para todo el ciclo
    -- RMA sin que ninguna transicion caiga antes de la entrega.
    DELETE FROM rma_ped WHERE entregado_en IS NULL OR entregado_en > (v_now - interval '3 days');

    FOR rec IN SELECT * FROM rma_ped LIMIT 190 LOOP
        v_sop := sop_ids[1+floor(random()*array_length(sop_ids,1))::int];
        v_ger := CASE WHEN random()<0.5 THEN 6 ELSE 2 END;
        v_motivo := 1+floor(random()*4)::int;

        -- estado final del ciclo
        v_rech := false;
        v_final := (CASE
            WHEN random()<0.12 THEN 0     -- rechazada (via en_revision)
            WHEN random()<0.20 THEN (1+floor(random()*2))::int   -- solicitada/en_revision
            WHEN random()<0.38 THEN (3+floor(random()*4))::int   -- aprobada..inspeccionada
            WHEN random()<0.70 THEN 7     -- reembolsada
            ELSE 8 END);                  -- cerrada
        IF v_final=0 THEN v_rech:=true; END IF;

        -- timestamps del ciclo (dentro del plazo de 30 dias)
        v_ts1 := rec.entregado_en + ((1+random()*24) * interval '1 day');
        IF v_ts1 > v_now THEN v_ts1 := v_now - interval '2 days'; END IF;
        v_ts2 := LEAST(v_ts1 + interval '2 hours' + random()*interval '22 hours', v_now);
        v_ts3 := LEAST(v_ts2 + interval '4 hours' + random()*interval '44 hours', v_now);
        v_ts4 := LEAST(v_ts3 + interval '4 hours' + random()*interval '44 hours', v_now);
        v_ts5 := LEAST(v_ts4 + interval '1 day'   + random()*interval '3 days', v_now);
        v_ts6 := LEAST(v_ts5 + interval '4 hours' + random()*interval '44 hours', v_now);
        v_ts7 := LEAST(v_ts6 + interval '4 hours' + random()*interval '3 days', v_now);
        v_ts8 := LEAST(v_ts7 + interval '2 hours' + random()*interval '46 hours', v_now);

        -- estado textual final
        v_estado_dev := CASE WHEN v_rech THEN 'rechazada'
            ELSE (ARRAY['solicitada','en_revision','aprobada','en_transito','recibida','inspeccionada','reembolsada','cerrada'])[GREATEST(v_final,1)] END;

        v_seq := nextval('public.seq_numero_documento');
        v_num := 'DV-'||to_char(v_ts1,'YYYYMMDD')||'-'||v_seq::text;
        -- aprobacion (>=3) fija guia/transportista/bodega
        v_transp := CASE WHEN (NOT v_rech) AND v_final>=3 THEN (ARRAY[1,2,6,7])[1+floor(random()*4)::int] ELSE NULL END;
        v_guia   := CASE WHEN (NOT v_rech) AND v_final>=3 THEN 'RET-'||to_char(v_ts3,'YYYYMMDD')||'-'||nextval('public.seq_numero_documento')::text ELSE NULL END;

        INSERT INTO devolucion (numero, pedido_id, motivo_devolucion_id, usuario_gestiona_id, estado,
                descripcion, cliente_id, transportista_id, bodega_id, guia_retorno, motivo_rechazo, fecha_creacion)
        VALUES (v_num, rec.pedido_id, v_motivo, CASE WHEN v_final>=1 OR v_rech THEN v_sop ELSE NULL END, v_estado_dev,
                'Solicitud de devolucion del cliente', rec.cliente_id, v_transp,
                CASE WHEN (NOT v_rech) AND v_final>=3 THEN 4 ELSE NULL END, v_guia,
                CASE WHEN v_rech THEN 'No cumple politica de devolucion (producto usado)' ELSE NULL END,
                v_ts1)
        RETURNING id INTO v_dev;
        n_dev := n_dev+1;
        v_pedido_devuelto := false;

        -- lineas: 1-2 lineas del pedido
        FOR lin IN
            SELECT pd.id AS pdet, pd.producto_variante_id AS var, pd.cantidad AS qty,
                   pd.precio_unitario AS pu
            FROM pedido_detalle pd WHERE pd.pedido_id=rec.pedido_id
            ORDER BY random() LIMIT (1+floor(random()*2)::int)
        LOOP
            -- resultado de inspeccion (solo si alcanzo inspeccionada)
            IF (NOT v_rech) AND v_final>=6 THEN
                IF v_final IN (7,8) THEN
                    v_result := CASE WHEN random()<0.70 THEN 'apto_reventa' ELSE 'defectuoso' END;
                ELSE
                    v_result := (ARRAY['apto_reventa','apto_reventa','defectuoso','rechazado'])[1+floor(random()*4)::int];
                END IF;
            ELSE
                v_result := NULL;
            END IF;

            INSERT INTO devolucion_detalle (devolucion_id, pedido_detalle_id, cantidad, estado_producto,
                    accion, resultado_inspeccion, nota_inspeccion, fecha_creacion)
            VALUES (v_dev, lin.pdet, GREATEST(1, LEAST(lin.qty, 1+floor(random()*lin.qty)::int)),
                    (ARRAY['nuevo','abierto','danado'])[1+floor(random()*3)::int],
                    'reembolso', v_result,
                    CASE WHEN v_result IS NOT NULL THEN 'Inspeccion en bodega' ELSE NULL END, v_ts1)
            RETURNING id INTO v_ddet;

            -- efectos de inspeccion
            IF v_result='apto_reventa' THEN
                -- reingreso de stock (bodega 4)
                SELECT stock_actual INTO v_ant FROM inventario
                  WHERE producto_variante_id=lin.var AND bodega_id=4 FOR UPDATE;
                IF v_ant IS NULL THEN
                    INSERT INTO inventario (producto_variante_id, bodega_id, stock_actual, stock_reservado, stock_minimo)
                    VALUES (lin.var, 4, 0, 0, 0) ON CONFLICT DO NOTHING; v_ant:=0;
                END IF;
                v_new := v_ant + LEAST(lin.qty, (SELECT cantidad FROM devolucion_detalle WHERE id=v_ddet));
                SELECT costo INTO v_costo FROM producto_variante WHERE id=lin.var;
                INSERT INTO movimiento_inventario (producto_variante_id, bodega_id, tipo_movimiento_id,
                        usuario_id, cantidad, stock_anterior, stock_nuevo, costo_unitario,
                        referencia_tipo, referencia_id, observacion, fecha_creacion)
                VALUES (lin.var, 4, 2, 9, (SELECT cantidad FROM devolucion_detalle WHERE id=v_ddet),
                        v_ant, v_new, COALESCE(v_costo,0), 'devolucion', v_dev,
                        '[SEED-BB] reingreso por devolucion apta', v_ts6);
                UPDATE inventario SET stock_actual=v_new WHERE producto_variante_id=lin.var AND bodega_id=4;
                n_reingreso := n_reingreso+1;
                v_pedido_devuelto := true;
            ELSIF v_result='defectuoso' THEN
                -- pool de defectuosos (origen rma) pendiente de devolver a proveedor
                SELECT costo INTO v_costo FROM producto_variante WHERE id=lin.var;
                INSERT INTO item_defectuoso (producto_variante_id, bodega_id, cantidad, origen,
                        devolucion_detalle_id, recepcion_detalle_id, proveedor_id, costo_unitario,
                        estado, nota, registrado_por, fecha_creacion)
                VALUES (lin.var, 4, (SELECT cantidad FROM devolucion_detalle WHERE id=v_ddet), 'rma',
                        v_ddet, NULL, NULL, COALESCE(v_costo,0), 'pendiente',
                        'Defectuoso detectado en inspeccion RMA', 9, v_ts6);
                n_defect := n_defect+1;
                v_pedido_devuelto := true;
            ELSIF v_result='rechazado' THEN
                n_rechaz := n_rechaz+1;
            END IF;
        END LOOP;

        -- ============ historial paso a paso ============
        INSERT INTO historial_estado_devolucion (devolucion_id, estado, cliente_id, comentario, fecha_creacion)
        VALUES (v_dev, 'solicitada', rec.cliente_id, 'Cliente solicita devolucion', v_ts1);
        IF v_rech THEN
            INSERT INTO historial_estado_devolucion (devolucion_id, estado, usuario_id, comentario, fecha_creacion)
            VALUES (v_dev, 'en_revision', v_sop, 'Soporte revisa', v_ts2);
            INSERT INTO historial_estado_devolucion (devolucion_id, estado, usuario_id, comentario, fecha_creacion)
            VALUES (v_dev, 'rechazada', v_sop, 'Rechazada: no cumple politica', v_ts3);
        ELSE
            IF v_final>=2 THEN INSERT INTO historial_estado_devolucion (devolucion_id, estado, usuario_id, comentario, fecha_creacion)
                VALUES (v_dev,'en_revision',v_sop,'Soporte revisa la solicitud',v_ts2); END IF;
            IF v_final>=3 THEN INSERT INTO historial_estado_devolucion (devolucion_id, estado, usuario_id, comentario, fecha_creacion)
                VALUES (v_dev,'aprobada',v_sop,'Aprobada - guia '||COALESCE(v_guia,''),v_ts3); END IF;
            IF v_final>=4 THEN INSERT INTO historial_estado_devolucion (devolucion_id, estado, usuario_id, comentario, fecha_creacion)
                VALUES (v_dev,'en_transito',10,'Producto en transito de retorno',v_ts4); END IF;
            IF v_final>=5 THEN INSERT INTO historial_estado_devolucion (devolucion_id, estado, usuario_id, comentario, fecha_creacion)
                VALUES (v_dev,'recibida',10,'Recibido en bodega',v_ts5); END IF;
            IF v_final>=6 THEN INSERT INTO historial_estado_devolucion (devolucion_id, estado, usuario_id, comentario, fecha_creacion)
                VALUES (v_dev,'inspeccionada',9,'Inspeccion por item',v_ts6); END IF;
            IF v_final>=7 THEN INSERT INTO historial_estado_devolucion (devolucion_id, estado, usuario_id, comentario, fecha_creacion)
                VALUES (v_dev,'reembolsada',v_ger,'Reembolso procesado',v_ts7); END IF;
            IF v_final>=8 THEN INSERT INTO historial_estado_devolucion (devolucion_id, estado, usuario_id, comentario, fecha_creacion)
                VALUES (v_dev,'cerrada',v_sop,'Caso cerrado',v_ts8); END IF;
        END IF;

        -- pedido -> devuelto cuando hubo reingreso/defectuoso (inspeccion con recuperacion)
        IF v_pedido_devuelto AND v_final>=6 THEN
            UPDATE pedido SET estado_pedido_id=8 WHERE id=rec.pedido_id;
            INSERT INTO historial_estado_pedido (pedido_id, estado_pedido_id, usuario_id, comentario, fecha_creacion)
            VALUES (rec.pedido_id, 8, 9, 'Pedido devuelto (RMA)', v_ts6);
        END IF;

        -- reembolso (reembolsada / cerrada): apto+defectuoso, no rechazado
        IF (NOT v_rech) AND v_final>=7 THEN
            SELECT COALESCE(sum(dd.cantidad*(pd.precio_unitario)),0) INTO v_reembolso
            FROM devolucion_detalle dd JOIN pedido_detalle pd ON pd.id=dd.pedido_detalle_id
            WHERE dd.devolucion_id=v_dev AND COALESCE(dd.resultado_inspeccion,'')<>'rechazado';
            IF v_reembolso>0 THEN
                SELECT id INTO v_pago FROM pago WHERE pedido_id=rec.pedido_id AND estado='completado' ORDER BY id LIMIT 1;
                v_metreem := (ARRAY['transferencia','tarjeta','efectivo'])[1+floor(random()*3)::int];
                UPDATE devolucion SET monto_reembolsado=round(v_reembolso,2), metodo_reembolso=v_metreem, fecha_reembolso=v_ts7
                  WHERE id=v_dev;
                IF v_pago IS NOT NULL THEN
                    INSERT INTO reembolso (pago_id, devolucion_id, monto, motivo, estado, referencia_externa, fecha_procesado, fecha_creacion)
                    VALUES (v_pago, v_dev, round(v_reembolso,2),
                            'Devolucion '||v_num||' via '||v_metreem, 'procesado',
                            'REEMB-'||to_char(v_ts7,'YYYYMMDD')||'-'||(floor(random()*100000))::int::text, v_ts7, v_ts7);
                    n_reemb := n_reemb+1;
                END IF;
            END IF;
        END IF;
    END LOOP;

    -- =================================================================
    -- PARTE B: NOVEDADES DE ENVIO
    -- =================================================================
    -- (1) devuelto_almacen: sobre envios 'devuelto' (pedido no_entregado)
    -- (2) reprogramada: sobre envios 'entregado' (hubo un intento fallido)
    -- (3) abierta: sobre envios 'en_transito' recientes
    FOR v_env IN
        SELECT e.id, e.pedido_id, e.estado, e.fecha_despacho,
               CASE WHEN e.estado='devuelto' THEN 'devuelto_almacen'
                    WHEN e.estado='entregado' AND random()<0.03 THEN 'reprogramada'
                    WHEN e.estado='en_transito' AND random()<0.55 THEN 'abierta'
                    ELSE NULL END AS kind
        FROM envio e WHERE e.id>25
    LOOP
        CONTINUE WHEN v_env.kind IS NULL;
        -- limitar reprogramadas para no inundar
        IF v_env.kind='reprogramada' AND n_reprog>=45 THEN CONTINUE; END IF;
        v_kind := v_env.kind;
        v_tipo := (ARRAY['cliente_ausente','direccion_incorrecta','cliente_rechazo','zona_dificil_acceso','dano_en_transito'])[1+floor(random()*5)::int];
        v_freg := COALESCE(v_env.fecha_despacho, v_now-interval '5 days') + interval '1 day' + random()*interval '2 days';
        IF v_freg > v_now THEN v_freg := v_now - interval '1 day'; END IF;

        IF v_kind='abierta' THEN
            v_intentos := 1; v_accion := NULL;
            INSERT INTO novedad_envio (envio_id, pedido_id, tipo, descripcion, intento_numero, estado, accion,
                    registrado_por, fecha_registro)
            VALUES (v_env.id, v_env.pedido_id, v_tipo, 'Incidencia reportada por el transportista',
                    v_intentos, 'abierta', NULL, 10, v_freg)
            RETURNING id INTO v_nov;
            n_open := n_open+1;
        ELSE
            v_accion := v_kind;   -- 'reprogramada' o 'devuelto_almacen'
            v_intentos := CASE WHEN v_kind='reprogramada' THEN 1+floor(random()*2)::int ELSE 1+floor(random()*3)::int END;
            v_fres := LEAST(v_freg + interval '4 hours' + random()*interval '2 days', v_now);
            INSERT INTO novedad_envio (envio_id, pedido_id, tipo, descripcion, intento_numero, estado, accion,
                    registrado_por, resuelto_por, fecha_registro, fecha_resolucion)
            VALUES (v_env.id, v_env.pedido_id, v_tipo, 'Incidencia reportada por el transportista',
                    v_intentos, 'resuelta', v_accion, 10, 10, v_freg, v_fres)
            RETURNING id INTO v_nov;
            IF v_kind='reprogramada' THEN n_reprog:=n_reprog+1; ELSE n_devalm:=n_devalm+1; END IF;
        END IF;
        n_nov := n_nov+1;

        -- auditoria (formato real): INSERT registrar
        INSERT INTO log_auditoria (usuario_id, tabla, registro_id, accion, datos_anteriores, datos_nuevos, fecha_creacion)
        VALUES (10,'novedad_envio',v_nov,'INSERT',
                jsonb_build_object('envio_estado', v_env.estado),
                jsonb_build_object('tipo',v_tipo,'intento',v_intentos,'envio_id',v_env.id,'pedido_id',v_env.pedido_id,'envio_estado','fallido'),
                v_freg);
        -- auditoria: UPDATE resolver (solo resueltas)
        IF v_kind<>'abierta' THEN
            INSERT INTO log_auditoria (usuario_id, tabla, registro_id, accion, datos_anteriores, datos_nuevos, fecha_creacion)
            VALUES (10,'novedad_envio',v_nov,'UPDATE',
                    jsonb_build_object('estado','abierta','envio_estado','fallido'),
                    jsonb_build_object('accion',v_accion,'envio_estado',
                        CASE WHEN v_kind='reprogramada' THEN 'en_transito' ELSE 'devuelto' END,
                        'intento_nuevo',v_intentos+1),
                    v_fres);
        END IF;
    END LOOP;

    INSERT INTO configuracion_tienda (clave, valor, tipo_dato, descripcion, fecha_creacion)
    VALUES ('seed_bb_62_posventa', v_thr::text, 'json',
            'Bloque B/62: RMA+reembolsos+novedades. Reversion: restaurar stock (restar reingresos), estado pedido (devuelto->entregado) y borrar id>umbral.', now());

    RAISE NOTICE 'Bloque B / 62 OK. Devoluciones: % (reembolsos %, reingresos %, defectuosos %, lineas rechazadas %). Novedades: % (abiertas %, reprogramadas %, devuelto_almacen %).',
        n_dev, n_reemb, n_reingreso, n_defect, n_rechaz, n_nov, n_open, n_reprog, n_devalm;
END $$;
