-- =====================================================================
-- 63_seed_bloque_b_soporte.sql
-- SIEMBRA DE VOLUMEN HISTORICO - BLOQUE B / Parte 4:
--   TICKETS DE SOPORTE + RESENAS + DEVOLUCION A PROVEEDOR
-- ---------------------------------------------------------------------
-- PARTE A - TICKETS: ~240 repartidos en 18 meses y en las 8 categorias,
--   con prioridad automatica (prioridad_defecto de la categoria, con
--   algun override), agentes asignados distintos, mensajes, fecha_limite
--   por SLA (urgente 2h/alta 4h/media 24h/baja 72h), una parte cerrada
--   (fecha_cierre) y otra abierta, y producto_variante_id en los que
--   tratan sobre un producto (habilita "productos mas reclamados").
--   Numeracion TICK-YYYY-NNNN (correlativo por ano; se actualiza
--   correlativo_ticket para que la app continue la serie).
--
-- PARTE B - RESENAS: solo de clientes que compraron el producto (pedido
--   entregado/devuelto del propio cliente => compra_verificada=true),
--   calificacion sesgada a lo positivo pero con negativas suficientes, y
--   una fraccion PENDIENTE de moderacion (cola no vacia). La moderacion
--   se audita como en la app (log_auditoria UPDATE estado).
--
-- PARTE C - DEVOLUCION A PROVEEDOR: agrupa el pool de item_defectuoso
--   (origen rma, sembrado en 62) por proveedor en devolucion_proveedor,
--   con estados registrada/enviada/resuelta/cerrada (minoria en curso),
--   resolucion nota_credito o reposicion (reingreso de stock via
--   entrada_reposicion_proveedor SOLO en reposicion) e historial. Deja
--   una minoria de item_defectuoso pendiente. Se audita como la app
--   (INSERT registrar + UPDATE transiciones).
--
-- INVARIANTES: reingreso solo SUMA stock (kardex cuadra, nunca negativo);
--   secuencia temporal respetada; unique (producto,cliente) en resena y
--   unique(item_defectuoso) en el detalle respetados; sin GENERATED.
-- Marca/reversion: 'seed_bb_63_soporte'. Ejecutar como postgres.
-- =====================================================================

DO $$
DECLARE
    v_thr jsonb;
    v_now constant timestamptz := timestamptz '2026-07-24 12:00:00-05';
    sop_ids bigint[] := ARRAY[12,18,19,20];
    v_sop bigint; v_cli bigint; v_cat bigint; v_prio text; v_estado text;
    v_ts timestamptz; v_num text; v_seq bigint; v_tk bigint; v_sla int;
    v_pvar bigint; v_ped bigint; v_cierre timestamptz; v_lim timestamptz;
    v_cnt2025 int := 0; v_cnt2026 int := 0; v_anio int; v_corr int;
    i int; r numeric;
    n_tk int:=0; n_cerr int:=0; n_abie int:=0;
    -- resenas
    rc record; v_cal int; v_res text; v_mod bigint; v_res_estado text; v_resid bigint;
    n_res int:=0; n_pend int:=0;
    v_titulos text[] := ARRAY['Excelente','Muy bueno','Cumple','Regular','Decepcionante','Recomendado','Buena compra','No era lo esperado'];
    -- devolucion proveedor
    dp record; v_dp bigint; v_dpnum text; v_prov bigint; v_dpstate text;
    v_tipo_res text; v_credito numeric; v_ant int; v_new int; v_costo numeric;
    t_reg timestamptz; t_env timestamptz; t_res timestamptz; t_cie timestamptz;
    n_dp int:=0; n_repo int:=0; n_nc int:=0; n_itproc int:=0;
BEGIN
    IF EXISTS (SELECT 1 FROM configuracion_tienda WHERE clave='seed_bb_63_soporte') THEN
        RAISE NOTICE 'Bloque B / 63 (soporte) ya sembrado; se omite.';
        RETURN;
    END IF;

    PERFORM setseed(0.6301);

    v_thr := jsonb_build_object(
        'ticket_soporte',   (SELECT COALESCE(max(id),0) FROM ticket_soporte),
        'mensaje_ticket',   (SELECT COALESCE(max(id),0) FROM mensaje_ticket),
        'resena',           (SELECT COALESCE(max(id),0) FROM resena),
        'devolucion_proveedor',        (SELECT COALESCE(max(id),0) FROM devolucion_proveedor),
        'devolucion_proveedor_detalle',(SELECT COALESCE(max(id),0) FROM devolucion_proveedor_detalle),
        'historial_devolucion_proveedor',(SELECT COALESCE(max(id),0) FROM historial_devolucion_proveedor),
        'movimiento_inventario', (SELECT COALESCE(max(id),0) FROM movimiento_inventario),
        'log_auditoria',    (SELECT COALESCE(max(id),0) FROM log_auditoria));

    -- =================================================================
    -- PARTE A: TICKETS DE SOPORTE
    -- =================================================================
    FOR i IN 1..240 LOOP
        v_anio := 2025 + floor(random()*2)::int;
        v_ts := make_timestamptz(v_anio, 1+floor(random()*12)::int, 1+floor(random()*27)::int,
                                 8+floor(random()*11)::int, floor(random()*60)::int, 0, 'America/Guayaquil');
        IF v_ts > v_now THEN v_ts := v_now - interval '2 days'; END IF;
        v_anio := extract(year from v_ts)::int;

        v_cat := 1+floor(random()*8)::int;
        SELECT prioridad_defecto INTO v_prio FROM categoria_ticket WHERE id=v_cat;
        IF random()<0.20 THEN v_prio := (ARRAY['baja','media','alta','urgente'])[1+floor(random()*4)::int]; END IF;
        v_sla := CASE v_prio WHEN 'urgente' THEN 2 WHEN 'alta' THEN 4 WHEN 'media' THEN 24 ELSE 72 END;

        -- cliente registrado antes del ticket
        SELECT id INTO v_cli FROM cliente WHERE activo AND fecha_creacion<=v_ts ORDER BY random() LIMIT 1;
        CONTINUE WHEN v_cli IS NULL;

        -- estado (mezcla; mayoria resuelta/cerrada pero cola viva)
        r := random();
        v_estado := CASE WHEN r<0.35 THEN 'cerrado' WHEN r<0.55 THEN 'resuelto'
                         WHEN r<0.63 THEN 'esperando_cliente' WHEN r<0.78 THEN 'en_proceso' ELSE 'abierto' END;

        -- asignado: siempre salvo parte de los abiertos
        IF v_estado='abierto' AND random()<0.5 THEN v_sop := NULL;
        ELSE v_sop := sop_ids[1+floor(random()*array_length(sop_ids,1))::int]; END IF;

        -- producto / pedido relacionados
        v_pvar := NULL; v_ped := NULL;
        IF v_cat IN (3,6,7) OR random()<0.30 THEN
            SELECT pd.producto_variante_id, p.id INTO v_pvar, v_ped
            FROM pedido p JOIN pedido_detalle pd ON pd.pedido_id=p.id
            WHERE p.cliente_id=v_cli AND p.id>35 AND p.estado_pedido_id IN (5,6,8)
            ORDER BY random() LIMIT 1;
        END IF;
        IF v_cat IN (2,4,7) AND v_ped IS NULL THEN
            SELECT id INTO v_ped FROM pedido WHERE cliente_id=v_cli AND id>35 ORDER BY random() LIMIT 1;
        END IF;

        v_cierre := CASE WHEN v_estado='cerrado' THEN LEAST(v_ts + (v_sla*(0.5+random()*3))*interval '1 hour', v_now) ELSE NULL END;
        v_lim := v_ts + (v_sla * interval '1 hour');

        -- numero TICK-YYYY-NNNN
        IF v_anio=2025 THEN v_cnt2025:=v_cnt2025+1; v_corr:=v_cnt2025;
        ELSE v_cnt2026:=v_cnt2026+1; v_corr:=v_cnt2026+7; END IF;   -- 7 ya usados en 2026
        v_num := 'TICK-'||v_anio||'-'||lpad(v_corr::text,4,'0');

        INSERT INTO ticket_soporte (numero, cliente_id, categoria_ticket_id, pedido_id, asignado_usuario_id,
                asunto, descripcion, prioridad, estado, fecha_cierre, fecha_creacion, fecha_limite, producto_variante_id)
        VALUES (v_num, v_cli, v_cat, v_ped, v_sop,
                (SELECT nombre FROM categoria_ticket WHERE id=v_cat)||' - caso '||i,
                'Consulta / incidencia reportada por el cliente.', v_prio, v_estado, v_cierre, v_ts, v_lim, v_pvar)
        RETURNING id INTO v_tk;
        n_tk := n_tk+1;
        IF v_estado='cerrado' THEN n_cerr:=n_cerr+1; END IF;
        IF v_estado IN ('abierto','en_proceso','esperando_cliente') THEN n_abie:=n_abie+1; END IF;

        -- mensajes: cliente + (soporte si asignado/avanzado)
        INSERT INTO mensaje_ticket (ticket_soporte_id, cliente_id, mensaje, es_interno, fecha_creacion)
        VALUES (v_tk, v_cli, 'Buenos dias, tengo un inconveniente con mi pedido/producto.', false, v_ts);
        IF v_sop IS NOT NULL AND v_estado<>'abierto' THEN
            INSERT INTO mensaje_ticket (ticket_soporte_id, usuario_id, mensaje, es_interno, fecha_creacion)
            VALUES (v_tk, v_sop, 'Gracias por contactarnos, estamos revisando su caso.', false,
                    LEAST(v_ts + interval '1 hour' + random()*interval '20 hours', v_now));
            IF random()<0.4 THEN
                INSERT INTO mensaje_ticket (ticket_soporte_id, usuario_id, mensaje, es_interno, fecha_creacion)
                VALUES (v_tk, v_sop, 'Nota interna: verificar con bodega/logistica.', true,
                        LEAST(v_ts + interval '2 hours' + random()*interval '20 hours', v_now));
            END IF;
        END IF;
    END LOOP;

    -- actualizar correlativo_ticket para que la app continue la serie
    IF v_cnt2025>0 THEN
        INSERT INTO correlativo_ticket (anio, ultimo) VALUES (2025, v_cnt2025)
        ON CONFLICT (anio) DO UPDATE SET ultimo=GREATEST(correlativo_ticket.ultimo, EXCLUDED.ultimo);
    END IF;
    IF v_cnt2026>0 THEN
        UPDATE correlativo_ticket SET ultimo=GREATEST(ultimo, v_cnt2026+7) WHERE anio=2026;
    END IF;

    -- =================================================================
    -- PARTE B: RESENAS (compra verificada)
    -- =================================================================
    CREATE TEMP TABLE resena_cand ON COMMIT DROP AS
    SELECT DISTINCT ON (pr.id, p.cliente_id)
           pr.id AS producto_id, p.cliente_id, p.id AS pedido_id, p.fecha_pedido
    FROM pedido p
    JOIN pedido_detalle pd ON pd.pedido_id=p.id
    JOIN producto_variante pv ON pv.id=pd.producto_variante_id
    JOIN producto pr ON pr.id=pv.producto_id
    WHERE p.id>35 AND p.estado_pedido_id IN (6,8)
    ORDER BY pr.id, p.cliente_id, random();

    FOR rc IN SELECT * FROM resena_cand ORDER BY random() LIMIT 340 LOOP
        -- calificacion sesgada positiva
        r := random();
        v_cal := CASE WHEN r<0.45 THEN 5 WHEN r<0.75 THEN 4 WHEN r<0.87 THEN 3 WHEN r<0.95 THEN 2 ELSE 1 END;
        -- estado moderacion: aprobada mayoria, pendiente (cola), rechazada minoria
        r := random();
        v_res_estado := CASE WHEN r<0.78 THEN 'aprobada' WHEN r<0.93 THEN 'pendiente' ELSE 'rechazada' END;
        v_mod := CASE WHEN random()<0.5 THEN 2 ELSE 6 END;
        v_ts := LEAST(rc.fecha_pedido + (5+random()*70)*interval '1 day', v_now - interval '1 day');

        INSERT INTO resena (producto_id, cliente_id, pedido_id, calificacion, titulo, comentario,
                compra_verificada, estado, moderado_por, fecha_moderacion, fecha_creacion)
        VALUES (rc.producto_id, rc.cliente_id, rc.pedido_id, v_cal,
                v_titulos[LEAST(8,GREATEST(1,6-v_cal+ floor(random()*2)::int))],
                CASE WHEN v_cal>=4 THEN 'Muy conforme con el producto, buena calidad.'
                     WHEN v_cal=3 THEN 'Cumple lo basico, sin mas.'
                     ELSE 'No qued conforme, esperaba mas por el precio.' END,
                true, v_res_estado,
                CASE WHEN v_res_estado='pendiente' THEN NULL ELSE v_mod END,
                CASE WHEN v_res_estado='pendiente' THEN NULL ELSE LEAST(v_ts+interval '1 day'+random()*interval '3 days', v_now) END,
                v_ts)
        ON CONFLICT (producto_id, cliente_id) DO NOTHING
        RETURNING id INTO v_resid;
        IF NOT FOUND THEN CONTINUE; END IF;
        n_res := n_res+1;
        IF v_res_estado='pendiente' THEN n_pend:=n_pend+1;
        ELSE
            -- auditoria de moderacion (formato real accion UPDATE)
            INSERT INTO log_auditoria (usuario_id, tabla, registro_id, accion, datos_anteriores, datos_nuevos, fecha_creacion)
            VALUES (v_mod, 'resena', v_resid, 'UPDATE',
                    jsonb_build_object('estado','pendiente'), jsonb_build_object('estado',v_res_estado),
                    LEAST(v_ts+interval '1 day', v_now));
        END IF;
    END LOOP;

    -- =================================================================
    -- PARTE C: DEVOLUCION A PROVEEDOR (desde el pool item_defectuoso rma)
    -- =================================================================
    -- asignar proveedor a ~70% de los pendientes (COMPRAS); agrupar por
    -- proveedor en devolucion_proveedor. El resto queda pendiente en el pool.
    CREATE TEMP TABLE def_proc ON COMMIT DROP AS
    SELECT itd.id AS item_id, itd.producto_variante_id AS var, itd.cantidad, itd.costo_unitario, itd.bodega_id,
           itd.fecha_creacion,
           COALESCE((SELECT pp.proveedor_id FROM producto_proveedor pp
                     WHERE pp.producto_variante_id=itd.producto_variante_id AND pp.es_preferido AND pp.activo LIMIT 1),
                    (SELECT id FROM proveedor WHERE ruc LIKE '%2500%' ORDER BY random() LIMIT 1)) AS prov,
           random() AS rnd
    FROM item_defectuoso itd
    WHERE itd.origen='rma' AND itd.estado='pendiente';
    DELETE FROM def_proc WHERE rnd >= 0.70;   -- 30% se quedan pendientes fuera del proceso
    UPDATE item_defectuoso itd SET proveedor_id = d.prov
      FROM def_proc d WHERE d.item_id=itd.id;

    FOR dp IN SELECT prov, min(fecha_creacion) fmin, count(*) nitems FROM def_proc GROUP BY prov LOOP
        n_dp := n_dp+1;
        t_reg := LEAST(dp.fmin + interval '3 days' + random()*interval '7 days', v_now - interval '1 day');
        -- estado final del DP (minoria en curso)
        r := random();
        v_dpstate := CASE WHEN r<0.20 THEN 'registrada' WHEN r<0.35 THEN 'enviada'
                          WHEN r<0.70 THEN 'resuelta' ELSE 'cerrada' END;
        v_tipo_res := CASE WHEN v_dpstate IN ('resuelta','cerrada')
                           THEN (CASE WHEN random()<0.5 THEN 'nota_credito' ELSE 'reposicion' END) ELSE NULL END;
        t_env := LEAST(t_reg + interval '1 day' + random()*interval '4 days', v_now);
        t_res := LEAST(t_env + interval '2 days' + random()*interval '6 days', v_now);
        t_cie := LEAST(t_res + interval '1 day' + random()*interval '3 days', v_now);

        v_credito := CASE WHEN v_tipo_res='nota_credito'
                          THEN (SELECT round(sum(cantidad*costo_unitario)::numeric,2) FROM def_proc WHERE prov=dp.prov)
                          ELSE NULL END;

        v_seq := nextval('public.seq_numero_documento');
        v_dpnum := 'DP-'||to_char(t_reg,'YYYYMMDD')||'-'||v_seq::text;
        INSERT INTO devolucion_proveedor (numero, proveedor_id, estado, tipo_resolucion, monto_credito,
                nota_resolucion, observacion, registrado_por, fecha_envio, fecha_resolucion, fecha_creacion)
        VALUES (v_dpnum, dp.prov, v_dpstate, v_tipo_res, v_credito,
                CASE WHEN v_tipo_res IS NOT NULL THEN 'Resolucion '||v_tipo_res ELSE NULL END,
                '[SEED-BB] devolucion a proveedor de defectuosos RMA', 11,
                CASE WHEN v_dpstate IN ('enviada','resuelta','cerrada') THEN t_env ELSE NULL END,
                CASE WHEN v_dpstate IN ('resuelta','cerrada') THEN t_res ELSE NULL END, t_reg)
        RETURNING id INTO v_dp;

        -- detalle (todos los items del proveedor) + estado del item
        INSERT INTO devolucion_proveedor_detalle (devolucion_proveedor_id, item_defectuoso_id, cantidad, costo_unitario, fecha_creacion)
        SELECT v_dp, item_id, cantidad, costo_unitario, t_reg FROM def_proc WHERE prov=dp.prov;

        UPDATE item_defectuoso itd
          SET estado = CASE WHEN v_dpstate IN ('resuelta','cerrada') THEN 'resuelto' ELSE 'en_devolucion' END
          FROM def_proc d WHERE d.item_id=itd.id AND d.prov=dp.prov;
        n_itproc := n_itproc + dp.nitems;

        -- reposicion: reingreso de stock (entrada_reposicion_proveedor, tipo 9)
        IF v_tipo_res='reposicion' THEN
            FOR rc IN SELECT item_id, var, cantidad, bodega_id, costo_unitario FROM def_proc WHERE prov=dp.prov LOOP
                SELECT stock_actual INTO v_ant FROM inventario
                  WHERE producto_variante_id=rc.var AND bodega_id=rc.bodega_id FOR UPDATE;
                IF v_ant IS NULL THEN
                    INSERT INTO inventario (producto_variante_id, bodega_id, stock_actual, stock_reservado, stock_minimo)
                    VALUES (rc.var, rc.bodega_id, 0,0,0) ON CONFLICT DO NOTHING; v_ant:=0;
                END IF;
                v_new := v_ant + rc.cantidad;
                INSERT INTO movimiento_inventario (producto_variante_id, bodega_id, tipo_movimiento_id,
                        usuario_id, cantidad, stock_anterior, stock_nuevo, costo_unitario,
                        referencia_tipo, referencia_id, observacion, fecha_creacion)
                VALUES (rc.var, rc.bodega_id, 9, 11, rc.cantidad, v_ant, v_new, COALESCE(rc.costo_unitario,0),
                        'devolucion_proveedor', v_dp, '[SEED-BB] reposicion de proveedor', t_res);
                UPDATE inventario SET stock_actual=v_new WHERE producto_variante_id=rc.var AND bodega_id=rc.bodega_id;
            END LOOP;
            n_repo := n_repo+1;
        ELSIF v_tipo_res='nota_credito' THEN
            n_nc := n_nc+1;
        END IF;

        -- historial + auditoria
        INSERT INTO historial_devolucion_proveedor (devolucion_proveedor_id, estado, usuario_id, comentario, fecha_creacion)
        VALUES (v_dp, 'registrada', 11, 'Devolucion registrada', t_reg);
        INSERT INTO log_auditoria (usuario_id, tabla, registro_id, accion, datos_nuevos, fecha_creacion)
        VALUES (11, 'devolucion_proveedor', v_dp, 'INSERT',
                jsonb_build_object('numero',v_dpnum,'proveedor_id',dp.prov,'estado','registrada'), t_reg);
        IF v_dpstate IN ('enviada','resuelta','cerrada') THEN
            INSERT INTO historial_devolucion_proveedor (devolucion_proveedor_id, estado, usuario_id, comentario, fecha_creacion)
            VALUES (v_dp, 'enviada', 11, 'Enviada al proveedor', t_env);
            INSERT INTO log_auditoria (usuario_id, tabla, registro_id, accion, datos_anteriores, datos_nuevos, fecha_creacion)
            VALUES (11,'devolucion_proveedor',v_dp,'UPDATE', jsonb_build_object('estado','registrada'), jsonb_build_object('estado','enviada'), t_env);
        END IF;
        IF v_dpstate IN ('resuelta','cerrada') THEN
            INSERT INTO historial_devolucion_proveedor (devolucion_proveedor_id, estado, usuario_id, comentario, fecha_creacion)
            VALUES (v_dp, 'resuelta', 11, 'Resuelta: '||v_tipo_res, t_res);
            INSERT INTO log_auditoria (usuario_id, tabla, registro_id, accion, datos_anteriores, datos_nuevos, fecha_creacion)
            VALUES (11,'devolucion_proveedor',v_dp,'UPDATE', jsonb_build_object('estado','enviada'), jsonb_build_object('estado','resuelta','tipo_resolucion',v_tipo_res), t_res);
        END IF;
        IF v_dpstate='cerrada' THEN
            INSERT INTO historial_devolucion_proveedor (devolucion_proveedor_id, estado, usuario_id, comentario, fecha_creacion)
            VALUES (v_dp, 'cerrada', 11, 'Caso cerrado', t_cie);
            INSERT INTO log_auditoria (usuario_id, tabla, registro_id, accion, datos_anteriores, datos_nuevos, fecha_creacion)
            VALUES (11,'devolucion_proveedor',v_dp,'UPDATE', jsonb_build_object('estado','resuelta'), jsonb_build_object('estado','cerrada'), t_cie);
        END IF;
    END LOOP;

    INSERT INTO configuracion_tienda (clave, valor, tipo_dato, descripcion, fecha_creacion)
    VALUES ('seed_bb_63_soporte', v_thr::text, 'json',
            'Bloque B/63: tickets+resenas+devolucion_proveedor. Reversion: restaurar stock (reposiciones), item_defectuoso a pendiente/proveedor NULL, correlativo_ticket, y borrar id>umbral.', now());

    RAISE NOTICE 'Bloque B / 63 OK. Tickets: % (cerrados %, en cola %). Resenas: % (pendientes moderacion %). Dev.proveedor: % (nota_credito %, reposicion %, items procesados %).',
        n_tk, n_cerr, n_abie, n_res, n_pend, n_dp, n_nc, n_repo, n_itproc;
END $$;
