-- =====================================================================
-- 60_seed_bloque_b_ventas.sql
-- SIEMBRA DE VOLUMEN HISTORICO - BLOQUE B / Parte 1: CICLO DE VENTA
-- ---------------------------------------------------------------------
-- Siembra el ciclo de venta completo ene-2025 .. jul-2026 respetando la
-- secuencia real: pedido -> pago -> factura -> preparacion -> despacho ->
-- entrega. Incluye historial paso a paso, pagos (con fallidos), facturas
-- de venta, envios (costo desde tarifa + peso real, puntualidad variada)
-- y el consumo de stock (salida_venta) con kardex continuo.
--
-- ANCLAJE DE VOLUMEN (documentado):
--   Compras recibidas Bloque A (a costo) = $3,183,337. En un minorista en
--   regimen, se vende aprox. lo que se compra, de modo que COGS_vendido ~
--   $3.18M y el inventario ni se agota ni se acumula (variacion neta ~0
--   sobre la apertura). El catalogo tiene margen ESTRUCTURAL del 40 %
--   (costo = 0.60 x precio). Vendiendo a precio de lista con una pequena
--   variacion negociada f in [0.90,1.00] (media ~0.95), el margen bruto
--   realizado cae en ~36 % (dentro del rango retail 25-40 %). Con
--   COGS ~$3.18M => ingresos ~ $3.18M / (1-0.36) ~ $4.97M y unidades
--   vendidas ~ 3.18M / 150 ~ 21,200. Esas unidades son ~17.6 % de la
--   apertura en bodega 4 (120,160 uds), asi que hay holgura de sobra.
--
-- INVARIANTE DE STOCK (clave):
--   Cada variante se vende COMO MAXIMO su apertura (B_v <= apertura_base_v,
--   con la apertura fechada 2025-01-01, ANTERIOR a toda venta). Como
--   ademas entran compras a lo largo del tiempo, el saldo cronologico de
--   cada variante = apertura + compras(<=t) - ventas(<=t) >=
--   apertura - ventas_totales >= 0 SIEMPRE. Es imposible el negativo en
--   cualquier punto de la linea de tiempo. El presupuesto por variante se
--   controla con una tabla temporal (seed_budget).
--
-- INVARIANTES DE TRIGGERS / GENERATED:
--   * pedido_detalle.subtotal y factura_venta_detalle.subtotal son
--     GENERATED: no se escriben.
--   * pedido.subtotal/monto_impuesto/total los pone el trigger de detalle
--     (fn_recalcular_total_pedido); costo_envio y monto_descuento (0) van
--     en la cabecera. factura_venta.total lo pone fn_recalcular_total_
--     factura_venta desde el detalle. No se escriben esos totales.
--   * fecha_actualizacion la fuerza el trigger touch: no se escribe.
--   * IVA 15 % por linea sobre la base (aqui sin descuentos de linea:
--     Bloque C sembrara promociones/cupones).
--
-- AUDITORIA (mismo criterio que Bloque A: solo lo que la app audita):
--   * Creacion de pedido INTERNO (canal tienda/telefono) -> log_auditoria
--     INSERT sobre 'pedido' (el checkout web NO se audita: grp_cliente sin
--     INSERT). Formato real {canal,numero,cliente_id,vendedor_id}.
--   * Despacho -> log_auditoria INSERT sobre 'envio'
--     {pedido_id,numero_guia,estado_pedido:'despachado',transportista_id}.
--
-- Numeracion: PED-/FV-/EN-/GUIA- + nextval(seq_numero_documento).
-- Marca/reversion: umbrales max(id) en configuracion_tienda
--   (clave 'seed_bb_60_ventas'). Ejecutar como superusuario postgres
--   (grp_administrador: exento de RLS y de los triggers de horario).
-- =====================================================================

DO $$
DECLARE
    v_thr jsonb;
    IVA         constant numeric := 0.15;
    v_now       constant timestamptz := timestamptz '2026-07-23 12:00:00-05';
    v_recent    constant date := date '2026-07-02';
    v_base      constant numeric := 205;          -- ordenes base por unidad-peso
    sales_wt    numeric[] := ARRAY[0.75,0.80,1.05,1.20,1.25,0.85,0.80,0.90,1.00,1.05,1.35,1.55];
    vend_ids    bigint[] := ARRAY[7,13,14,15,16,17];
    vend_w      numeric[] := ARRAY[1.6,1.2,1.0,0.8,0.6,0.35];   -- desempeno distinto
    vend_wtot   numeric;
    -- puntualidad por transportista (prob. de entrega tardia); ids reales
    -- 1 Tramaco, 2 Servientrega, 6 Laar, 7 Urbano, 8 Speed Mail
    v_late_prob numeric;

    y int; mo int; o int; n_ord int; wt numeric; yearf numeric;
    v_day int; v_ts timestamptz;
    v_cli bigint; v_reg timestamptz; v_dir bigint; v_razon text; v_ident text;
    v_dirtxt text; v_zona int; v_metodo bigint; v_transp bigint; v_pop numeric;
    v_canal text; v_shipped boolean; v_vend bigint; r numeric; s int; wcum numeric;
    v_costo_base numeric; v_costo_kg numeric;
    v_isrecent boolean; v_kind text; v_stage int;
    v_ped bigint; v_num text; v_seq bigint; v_estado_final bigint;
    n_lines int; li int; v_var bigint; v_qty int; v_rem int; v_price numeric;
    v_costo numeric; v_peso numeric; v_sku text; v_nom text;
    v_weight numeric; v_env_costo numeric;
    v_subtotal numeric; v_total numeric;
    -- timestamps del ciclo
    t_conf timestamptz; t_pag timestamptz; t_fact timestamptz;
    t_prep timestamptz; t_prepd timestamptz; t_desp timestamptz; t_ent timestamptz;
    v_fac bigint; v_pago bigint; v_env bigint;
    v_metpago bigint; v_ref text; v_pasarela jsonb;
    v_guia text; v_estimada date; v_real timestamptz; v_env_estado text;
    v_dias_min int; v_dias_max int; v_dias int; v_late boolean;
    v_ant int; v_new int; v_goods_left boolean; v_picked bigint[];
    -- salida stock helper
    cur record;
    -- contadores
    n_ped int:=0; n_web int:=0; n_tienda int:=0; n_tel int:=0;
    n_deliv int:=0; n_cancel int:=0; n_noent int:=0; n_prog int:=0;
    n_fac int:=0; n_pago int:=0; n_env int:=0; n_sal int:=0; n_units int:=0;
    n_fail int:=0;
    v_fmot text;
BEGIN
    IF EXISTS (SELECT 1 FROM configuracion_tienda WHERE clave='seed_bb_60_ventas') THEN
        RAISE NOTICE 'Bloque B / 60 (ventas) ya sembrado; se omite.';
        RETURN;
    END IF;

    PERFORM setseed(0.6001);

    v_thr := jsonb_build_object(
        'pedido',                  (SELECT COALESCE(max(id),0) FROM pedido),
        'pedido_detalle',          (SELECT COALESCE(max(id),0) FROM pedido_detalle),
        'historial_estado_pedido', (SELECT COALESCE(max(id),0) FROM historial_estado_pedido),
        'pago',                    (SELECT COALESCE(max(id),0) FROM pago),
        'transaccion_pago',        (SELECT COALESCE(max(id),0) FROM transaccion_pago),
        'factura_venta',           (SELECT COALESCE(max(id),0) FROM factura_venta),
        'factura_venta_detalle',   (SELECT COALESCE(max(id),0) FROM factura_venta_detalle),
        'envio',                   (SELECT COALESCE(max(id),0) FROM envio),
        'seguimiento_envio',       (SELECT COALESCE(max(id),0) FROM seguimiento_envio),
        'movimiento_inventario',   (SELECT COALESCE(max(id),0) FROM movimiento_inventario),
        'log_auditoria',           (SELECT COALESCE(max(id),0) FROM log_auditoria)
    );

    vend_wtot := 0; FOR s IN 1..array_length(vend_w,1) LOOP vend_wtot := vend_wtot + vend_w[s]; END LOOP;

    -- ============ presupuesto de venta por variante (bodega 4) =========
    -- Activas ~70 %; el resto son 'hueso' (nunca se venden). B_v = apertura
    -- x f skewed (few winners), tope 0.85 de la apertura. peso = B_v (pick
    -- ponderado => Pareto en unidades vendidas). Holgura ~40 % sobre la
    -- demanda para que ninguna orden se quede sin stock.
    CREATE TEMP TABLE seed_budget ON COMMIT DROP AS
    WITH base AS (
        SELECT mi.producto_variante_id AS variante_id,
               mi.stock_nuevo AS apertura, pv.precio, pv.costo,
               COALESCE(pv.peso_kg,0.5) AS peso, pv.sku, pr.nombre AS nombre,
               random() AS q, random() AS f
        FROM movimiento_inventario mi
        JOIN producto_variante pv ON pv.id = mi.producto_variante_id
        JOIN producto pr ON pr.id = pv.producto_id
        WHERE mi.referencia_tipo='inventario_inicial' AND mi.bodega_id=4
          AND mi.stock_nuevo>0 AND pv.precio>0 AND pv.costo>0
    ), calc AS (
        SELECT variante_id, apertura, precio, costo, peso, sku, nombre,
               GREATEST(1, LEAST(floor(apertura*0.85)::int,
                   floor(apertura*(CASE WHEN f<0.55 THEN 0.10+f*0.20
                                        WHEN f<0.85 THEN 0.20+f*0.30
                                        ELSE 0.45+f*0.40 END))::int)) AS budget
        FROM base WHERE q < 0.70
    )
    SELECT variante_id, apertura, precio, costo, peso, sku, nombre,
           budget AS remaining, budget::numeric AS pop
    FROM calc;
    CREATE INDEX ON seed_budget(variante_id);

    -- ============ clientes vendibles (con Pareto y fecha de registro) ===
    CREATE TEMP TABLE seed_cliente ON COMMIT DROP AS
    SELECT c.id AS cliente_id, u.fecha_creacion AS reg, d.id AS direccion_id,
           (c.nombre||' '||c.apellido) AS razon, c.numero_identificacion AS ident,
           (d.calle_principal||' '||COALESCE(d.numero,'')||', '||ci.nombre) AS dirtxt,
           CASE WHEN ci.nombre='Quevedo' THEN 1
                WHEN ci.provincia_id=25 THEN 2 ELSE 3 END AS zona,
           (CASE WHEN random()<0.12 THEN 6+random()*10
                 WHEN random()<0.50 THEN 1.5+random()*3
                 ELSE 0.2+random()*1.0 END) AS pop
    FROM cliente c
    JOIN usuario u ON u.id = c.usuario_id
    JOIN direccion d ON d.usuario_id = c.usuario_id AND d.es_predeterminada
    JOIN ciudad ci ON ci.id = d.ciudad_id
    WHERE c.activo;

    -- ==================== LOOP MENSUAL ===============================
    FOR y IN 2025..2026 LOOP
      FOR mo IN 1..12 LOOP
        EXIT WHEN (y=2026 AND mo>7);
        wt := sales_wt[mo];
        yearf := CASE WHEN y=2026 THEN 1.18 ELSE 1.0 END;
        n_ord := GREATEST(1, round(v_base * wt * yearf * (0.9+random()*0.2))::int);

        FOR o IN 1..n_ord LOOP
            IF y=2026 AND mo=7 THEN v_day := 1 + floor(random()*22)::int;
            ELSE v_day := 1 + floor(random()*27)::int; END IF;
            v_ts := make_timestamptz(y, mo, v_day, 8+floor(random()*11)::int,
                                     floor(random()*60)::int, 0, 'America/Guayaquil');
            IF v_ts > v_now THEN v_ts := v_now - interval '2 hours'; END IF;

            -- cliente (ponderado, registrado antes del pedido)
            SELECT cliente_id, reg, direccion_id, razon, ident, dirtxt, zona
              INTO v_cli, v_reg, v_dir, v_razon, v_ident, v_dirtxt, v_zona
            FROM seed_cliente WHERE reg <= v_ts
            ORDER BY -ln(random())/pop LIMIT 1;
            CONTINUE WHEN v_cli IS NULL;    -- orden muy temprana sin clientes aun

            -- canal
            r := random();
            IF r < 0.55 THEN v_canal:='web'; n_web:=n_web+1;
            ELSIF r < 0.80 THEN v_canal:='tienda'; n_tienda:=n_tienda+1;
            ELSE v_canal:='telefono'; n_tel:=n_tel+1; END IF;
            v_shipped := (v_canal IN ('web','telefono'));

            -- vendedor: NULL en web (por diseno); ponderado en internos
            IF v_canal='web' THEN v_vend := NULL;
            ELSE
                r := random()*vend_wtot; wcum:=0; v_vend:=vend_ids[1];
                FOR s IN 1..array_length(vend_ids,1) LOOP
                    wcum := wcum + vend_w[s];
                    IF r <= wcum THEN v_vend := vend_ids[s]; EXIT; END IF;
                END LOOP;
            END IF;

            -- envio: metodo, transportista, tarifa por zona
            IF v_zona=1 THEN v_metodo:=2; v_transp:=1; v_costo_base:=2.50; v_costo_kg:=0.30; v_dias_min:=1; v_dias_max:=2;
            ELSIF v_zona=2 THEN v_metodo:=1; v_transp:=2; v_costo_base:=4.50; v_costo_kg:=0.55; v_dias_min:=2; v_dias_max:=5;
            ELSE v_metodo:=1; v_transp:=2; v_costo_base:=6.50; v_costo_kg:=0.85; v_dias_min:=2; v_dias_max:=5; END IF;
            IF v_zona=3 AND random()<0.25 THEN v_transp := (ARRAY[2,6,7,8])[1+floor(random()*4)::int]; END IF;

            v_isrecent := v_ts::date >= v_recent;

            -- ==== estado final del pedido ====
            r := random();
            IF r < 0.04 THEN
                v_kind := 'cancel'; n_cancel := n_cancel+1;
            ELSIF v_isrecent THEN
                v_kind := 'progress'; n_prog := n_prog+1;
                r := random();
                IF v_shipped THEN
                    v_stage := CASE WHEN r<0.40 THEN 7 WHEN r<0.52 THEN 6 WHEN r<0.62 THEN 5
                                    WHEN r<0.72 THEN 4 WHEN r<0.82 THEN 3 WHEN r<0.92 THEN 2 ELSE 1 END;
                ELSE
                    v_stage := CASE WHEN r<0.55 THEN 4 WHEN r<0.75 THEN 3 WHEN r<0.90 THEN 2 ELSE 1 END;
                END IF;
                IF (v_shipped AND v_stage=7) OR ((NOT v_shipped) AND v_stage=4) THEN
                    v_kind := 'delivered'; n_prog:=n_prog-1; n_deliv:=n_deliv+1;
                END IF;
            ELSIF v_shipped AND random() < 0.04 THEN
                v_kind := 'no_entregado'; n_noent := n_noent+1;
            ELSE
                v_kind := 'delivered'; n_deliv := n_deliv+1;
            END IF;

            -- estado_pedido_id final
            v_estado_final := CASE v_kind
                WHEN 'cancel' THEN 7
                WHEN 'no_entregado' THEN 11
                WHEN 'delivered' THEN 6
                ELSE (CASE WHEN v_shipped THEN (ARRAY[2,3,9,4,10,5])[v_stage]
                                          ELSE (ARRAY[2,3,9])[v_stage] END) END;

            v_goods_left := (v_shipped AND v_estado_final IN (5,6,11))
                            OR ((NOT v_shipped) AND v_estado_final = 6);

            -- goods_left: la mercancia salio fisicamente (consume stock).
            -- shipped: al despachar (despachado/entregado/no_entregado);
            -- pickup: al entregar en mostrador.
            -- Se decide ANTES de las lineas para tomar del presupuesto solo
            -- las ordenes que realmente descontaran stock.
            -- (v_estado_final ya esta fijado)
            -- ==== timestamps del ciclo ====
            t_conf := v_ts;
            t_pag  := t_conf + (CASE WHEN v_canal='web' THEN (interval '5 min' + random()*interval '3 hours')
                                     ELSE (interval '1 hour' + random()*interval '28 hours') END);
            t_fact := t_pag  + (interval '10 min' + random()*interval '8 hours');
            t_prep := t_fact + (interval '2 hours' + random()*interval '22 hours');
            t_prepd:= t_prep + (interval '1 hour' + random()*interval '11 hours');
            t_desp := t_prepd+ (interval '2 hours' + random()*interval '22 hours');
            v_dias := v_dias_min + floor(random()*(v_dias_max-v_dias_min+1))::int;
            t_ent  := t_desp + (v_dias * interval '1 day') + (random()*interval '8 hours');
            -- clamp al presente
            t_pag:=LEAST(t_pag,v_now); t_fact:=LEAST(t_fact,v_now); t_prep:=LEAST(t_prep,v_now);
            t_prepd:=LEAST(t_prepd,v_now); t_desp:=LEAST(t_desp,v_now); t_ent:=LEAST(t_ent,v_now);

            -- ============ INSERT pedido ============
            v_seq := nextval('public.seq_numero_documento');
            v_num := 'PED-' || to_char(v_ts,'YYYYMMDD') || '-' || v_seq::text;
            INSERT INTO pedido (numero, cliente_id, estado_pedido_id, moneda_id,
                    metodo_envio_id, direccion_envio_id, direccion_facturacion_id,
                    canal, costo_envio, fecha_pedido, fecha_creacion, transportista_id, vendedor_id)
            VALUES (v_num, v_cli, v_estado_final, 1,
                    CASE WHEN v_shipped THEN v_metodo ELSE NULL END, v_dir, v_dir,
                    v_canal, 0, v_ts, v_ts,
                    CASE WHEN v_shipped THEN v_transp ELSE NULL END, v_vend)
            RETURNING id INTO v_ped;
            n_ped := n_ped+1;

            -- ============ lineas ============
            n_lines := (ARRAY[1,1,2,2,2,3,3,4,5])[1+floor(random()*9)::int];
            v_weight := 0; v_picked := '{}';
            FOR li IN 1..n_lines LOOP
                v_qty := (ARRAY[1,1,1,2,2,3,4])[1+floor(random()*7)::int];
                IF v_goods_left THEN
                    -- orden CON salida: tomar de presupuesto (sin repetir variante)
                    SELECT variante_id, remaining, precio, costo, peso, sku, nombre
                      INTO v_var, v_rem, v_price, v_costo, v_peso, v_sku, v_nom
                    FROM seed_budget WHERE remaining>0 AND variante_id <> ALL(v_picked)
                    ORDER BY -ln(random())/pop LIMIT 1;
                    CONTINUE WHEN v_var IS NULL;
                    v_qty := LEAST(v_qty, v_rem);
                    UPDATE seed_budget SET remaining = remaining - v_qty WHERE variante_id = v_var;
                ELSE
                    -- orden sin salida (cancelada / en curso): variante libre
                    SELECT variante_id, precio, costo, peso, sku, nombre
                      INTO v_var, v_price, v_costo, v_peso, v_sku, v_nom
                    FROM seed_budget WHERE variante_id <> ALL(v_picked) ORDER BY random() LIMIT 1;
                    CONTINUE WHEN v_var IS NULL;
                END IF;
                v_picked := v_picked || v_var;
                v_price := round((v_price * (0.90 + random()*0.10))::numeric, 2);
                v_weight := v_weight + v_qty * v_peso;
                INSERT INTO pedido_detalle (pedido_id, producto_variante_id, nombre_producto,
                        sku, cantidad, precio_unitario, monto_descuento, monto_impuesto, fecha_creacion)
                VALUES (v_ped, v_var, left(v_nom,200), v_sku, v_qty, v_price, 0,
                        round(v_qty*v_price*IVA,2), v_ts);
            END LOOP;
            -- si por algun motivo no entro ninguna linea, saltar (no deberia)
            IF NOT EXISTS (SELECT 1 FROM pedido_detalle WHERE pedido_id=v_ped) THEN
                DELETE FROM pedido WHERE id=v_ped; n_ped:=n_ped-1; CONTINUE;
            END IF;

            -- costo de envio (desde tarifa + peso real) para shipped
            IF v_shipped THEN
                v_env_costo := round((v_costo_base + v_costo_kg * v_weight)::numeric, 2);
                UPDATE pedido SET costo_envio = v_env_costo WHERE id = v_ped;
            ELSE
                v_env_costo := 0;
            END IF;
            SELECT total INTO v_total FROM pedido WHERE id=v_ped;

            -- ============ auditoria: creacion de pedido INTERNO ============
            IF v_canal IN ('tienda','telefono') THEN
                INSERT INTO log_auditoria (usuario_id, tabla, registro_id, accion, datos_nuevos, fecha_creacion)
                VALUES (v_vend, 'pedido', v_ped, 'INSERT',
                        jsonb_build_object('canal',v_canal,'numero',v_num,'cliente_id',v_cli,'vendedor_id',v_vend),
                        v_ts);
            END IF;

            -- ============ historial: confirmado ============
            INSERT INTO historial_estado_pedido (pedido_id, estado_pedido_id, usuario_id, comentario, fecha_creacion)
            VALUES (v_ped, 2, v_vend, 'Pedido confirmado', t_conf);

            IF v_kind='cancel' THEN
                INSERT INTO historial_estado_pedido (pedido_id, estado_pedido_id, usuario_id, comentario, fecha_creacion)
                VALUES (v_ped, 7, v_vend, 'Pedido cancelado', t_conf + interval '3 hours' + random()*interval '20 hours');
                CONTINUE;   -- sin pago/factura/envio/salida
            END IF;

            -- ============ pagado (stage>=2) ============
            IF v_estado_final IN (3,9,4,10,5,6,11) THEN
                -- metodo de pago segun canal
                IF v_canal='web' THEN v_metpago := CASE WHEN random()<0.7 THEN 3 ELSE 2 END;
                ELSE v_metpago := (ARRAY[1,1,2,3])[1+floor(random()*4)::int]; END IF;
                IF v_metpago=3 THEN
                    v_ref := 'VISA ****' || lpad((1000+floor(random()*9000))::int::text,4,'0');
                    v_pasarela := jsonb_build_object('marca','VISA','ultimos4',right(v_ref,4),'estado','APROBADA');
                ELSIF v_metpago=2 THEN
                    v_ref := 'TRF-'||to_char(t_pag,'YYYYMMDD')||'-'||(floor(random()*100000))::int::text; v_pasarela:=NULL;
                ELSE v_ref := NULL; v_pasarela:=NULL; END IF;

                INSERT INTO pago (pedido_id, metodo_pago_id, moneda_id, monto, estado, referencia_externa, fecha_pago, fecha_creacion)
                VALUES (v_ped, v_metpago, 1, v_total, 'completado', v_ref, t_pag, t_pag)
                RETURNING id INTO v_pago;
                n_pago := n_pago+1;
                INSERT INTO transaccion_pago (pago_id, tipo, estado, monto, codigo_autorizacion, respuesta_pasarela, fecha_creacion)
                VALUES (v_pago, 'captura', 'exitosa', v_total,
                        'AUTH-'||(floor(random()*1000000))::int::text, v_pasarela, t_pag);

                INSERT INTO historial_estado_pedido (pedido_id, estado_pedido_id, usuario_id, comentario, fecha_creacion)
                VALUES (v_ped, 3, CASE WHEN v_canal='web' THEN NULL ELSE v_vend END, 'Pago recibido', t_pag);
            END IF;

            -- ============ facturado (stage>=3) ============
            IF v_estado_final IN (9,4,10,5,6,11) THEN
                v_seq := nextval('public.seq_numero_documento');
                INSERT INTO factura_venta (numero, pedido_id, cliente_id, moneda_id, razon_social,
                        identificacion, direccion_facturacion, estado, fecha_emision, fecha_creacion)
                VALUES ('FV-'||to_char(t_fact,'YYYYMMDD')||'-'||v_seq::text, v_ped, v_cli, 1, v_razon,
                        v_ident, v_dirtxt, CASE WHEN random()<0.6 THEN 'autorizada' ELSE 'emitida' END, t_fact, t_fact)
                RETURNING id INTO v_fac;
                INSERT INTO factura_venta_detalle (factura_venta_id, pedido_detalle_id, producto_variante_id,
                        descripcion, cantidad, precio_unitario, monto_descuento, monto_impuesto, fecha_creacion)
                SELECT v_fac, pd.id, pd.producto_variante_id, left(pd.nombre_producto,200), pd.cantidad,
                       pd.precio_unitario, pd.monto_descuento, pd.monto_impuesto, t_fact
                FROM pedido_detalle pd WHERE pd.pedido_id = v_ped;
                n_fac := n_fac+1;

                INSERT INTO historial_estado_pedido (pedido_id, estado_pedido_id, usuario_id, comentario, fecha_creacion)
                VALUES (v_ped, 9, CASE WHEN v_canal='web' THEN NULL ELSE v_vend END, 'Factura emitida', t_fact);
            END IF;

            -- ============ preparacion (shipped, stage>=4) ============
            IF v_shipped AND v_estado_final IN (4,10,5,6,11) THEN
                INSERT INTO historial_estado_pedido (pedido_id, estado_pedido_id, usuario_id, comentario, fecha_creacion)
                VALUES (v_ped, 4, 9, 'En preparacion en bodega', t_prep);
            END IF;
            IF v_shipped AND v_estado_final IN (10,5,6,11) THEN
                INSERT INTO historial_estado_pedido (pedido_id, estado_pedido_id, usuario_id, comentario, fecha_creacion)
                VALUES (v_ped, 10, 9, 'Pedido preparado', t_prepd);
            END IF;

            -- ============ salida de stock (goods left) ============
            -- shipped: al despachar (estado>=despachado incl. no_entregado)
            -- pickup:  al entregar (mostrador)
            IF v_goods_left THEN
                FOR cur IN SELECT producto_variante_id AS var, cantidad AS qty, precio_unitario AS pu
                           FROM pedido_detalle WHERE pedido_id=v_ped LOOP
                    SELECT stock_actual INTO v_ant FROM inventario
                      WHERE producto_variante_id=cur.var AND bodega_id=4 FOR UPDATE;
                    IF v_ant IS NULL OR v_ant < cur.qty THEN
                        -- salvaguarda (no deberia ocurrir por el presupuesto): omitir salida
                        CONTINUE;
                    END IF;
                    v_new := v_ant - cur.qty;
                    INSERT INTO movimiento_inventario (producto_variante_id, bodega_id, tipo_movimiento_id,
                            usuario_id, cantidad, stock_anterior, stock_nuevo, costo_unitario,
                            referencia_tipo, referencia_id, observacion, fecha_creacion)
                    VALUES (cur.var, 4, 5, 9, cur.qty, v_ant, v_new,
                            (SELECT costo FROM seed_budget WHERE variante_id=cur.var),
                            'pedido', v_ped, '[SEED-BB] salida por venta',
                            CASE WHEN v_shipped THEN t_desp ELSE t_ent END);
                    UPDATE inventario SET stock_actual = v_new
                      WHERE producto_variante_id=cur.var AND bodega_id=4;
                    n_sal := n_sal+1; n_units := n_units + cur.qty;
                END LOOP;
            END IF;

            -- ============ envio (shipped, stage>=despachado) ============
            IF v_shipped AND v_estado_final IN (5,6,11) THEN
                v_estimada := (t_desp::date) + v_dias;
                v_late_prob := CASE v_transp WHEN 1 THEN 0.15 WHEN 2 THEN 0.40
                                             WHEN 6 THEN 0.30 WHEN 7 THEN 0.35 ELSE 0.55 END;
                v_late := random() < v_late_prob;
                IF v_estado_final=6 THEN
                    IF v_late THEN v_real := (v_estimada + (1+floor(random()*4))::int) + (random()*interval '9 hours');
                    ELSE v_real := (v_estimada - (floor(random()*2))::int)::timestamptz + (random()*interval '9 hours'); END IF;
                    IF v_real < t_desp THEN v_real := t_desp + interval '20 hours'; END IF;
                    IF v_real > v_now THEN v_real := v_now; END IF;
                    v_env_estado := 'entregado';
                ELSIF v_estado_final=11 THEN
                    v_env_estado := 'devuelto'; v_real := NULL;
                ELSE
                    v_env_estado := 'en_transito'; v_real := NULL;
                END IF;
                v_seq := nextval('public.seq_numero_documento');
                v_guia := 'GUIA-'||to_char(t_desp,'YYYYMMDD')||'-'||v_seq::text;
                INSERT INTO envio (numero, pedido_id, transportista_id, metodo_envio_id, bodega_id,
                        direccion_entrega, numero_guia, estado, costo, peso_total_kg,
                        fecha_despacho, fecha_entrega_estimada, fecha_entrega_real, fecha_creacion, despachado_por)
                VALUES ('EN-'||to_char(t_desp,'YYYYMMDD')||'-'||v_seq::text, v_ped, v_transp, v_metodo, 4,
                        v_dirtxt, v_guia, v_env_estado, v_env_costo, round(v_weight::numeric,3),
                        t_desp, v_estimada, v_real, t_desp, 10)
                RETURNING id INTO v_env;
                n_env := n_env+1;

                -- seguimiento
                INSERT INTO seguimiento_envio (envio_id, estado, descripcion, ubicacion, fecha_evento, fecha_creacion)
                VALUES (v_env,'despachado','Paquete despachado desde bodega','Quevedo', t_desp, t_desp);
                INSERT INTO seguimiento_envio (envio_id, estado, descripcion, ubicacion, fecha_evento, fecha_creacion)
                VALUES (v_env,'en_transito','En ruta hacia destino', v_dirtxt, t_desp+interval '12 hours', t_desp+interval '12 hours');
                IF v_env_estado='entregado' THEN
                    INSERT INTO seguimiento_envio (envio_id, estado, descripcion, ubicacion, fecha_evento, fecha_creacion)
                    VALUES (v_env,'entregado','Entregado al destinatario', v_dirtxt, v_real, v_real);
                ELSIF v_env_estado='devuelto' THEN
                    INSERT INTO seguimiento_envio (envio_id, estado, descripcion, ubicacion, fecha_evento, fecha_creacion)
                    VALUES (v_env,'fallido','Entrega fallida', v_dirtxt, t_desp+interval '1 day', t_desp+interval '1 day');
                END IF;

                -- auditoria de despacho (formato real)
                INSERT INTO log_auditoria (usuario_id, tabla, registro_id, accion, datos_anteriores, datos_nuevos, fecha_creacion)
                VALUES (10, 'envio', v_env, 'INSERT',
                        jsonb_build_object('estado_pedido','preparado'),
                        jsonb_build_object('pedido_id',v_ped,'numero_guia',v_guia,'estado_pedido','despachado','transportista_id',v_transp,'costo_envio',v_env_costo),
                        t_desp);

                -- historial: despachado (+ entregado / no_entregado)
                INSERT INTO historial_estado_pedido (pedido_id, estado_pedido_id, usuario_id, comentario, fecha_creacion)
                VALUES (v_ped, 5, 10, 'Pedido despachado - guia '||v_guia, t_desp);
                IF v_estado_final=6 THEN
                    INSERT INTO historial_estado_pedido (pedido_id, estado_pedido_id, usuario_id, comentario, fecha_creacion)
                    VALUES (v_ped, 6, 10, 'Pedido entregado', v_real);
                ELSIF v_estado_final=11 THEN
                    INSERT INTO historial_estado_pedido (pedido_id, estado_pedido_id, usuario_id, comentario, fecha_creacion)
                    VALUES (v_ped, 11, 10, 'Entrega fallida - devuelto al almacen', t_desp+interval '2 days');
                END IF;

            -- pickup entregado (mostrador): salida ya hecha arriba
            ELSIF (NOT v_shipped) AND v_estado_final=6 THEN
                INSERT INTO historial_estado_pedido (pedido_id, estado_pedido_id, usuario_id, comentario, fecha_creacion)
                VALUES (v_ped, 6, v_vend, 'Retirado en tienda', t_fact + interval '1 hour' + random()*interval '6 hours');
            END IF;

        END LOOP;   -- ordenes del mes
      END LOOP;
    END LOOP;

    -- ==================== INTENTOS DE PAGO FALLIDOS ====================
    -- pago sin pedido (pedido_id NULL, estado 'fallido') + transaccion
    -- fallida con motivo. Distribuidos en los 18 meses. ~4-5% del exito.
    FOR o IN 1..round(n_pago*0.045)::int LOOP
        y := 2025 + floor(random()*2)::int;
        mo := 1 + floor(random()*12)::int;
        IF y=2026 AND mo>7 THEN mo := 1+floor(random()*7)::int; END IF;
        v_ts := make_timestamptz(y, mo, 1+floor(random()*27)::int, 9+floor(random()*10)::int, floor(random()*60)::int, 0, 'America/Guayaquil');
        IF v_ts > v_now THEN v_ts := v_now - interval '1 day'; END IF;
        v_total := round((30 + random()*1500)::numeric, 2);
        v_metpago := CASE WHEN random()<0.8 THEN 3 ELSE 2 END;
        v_fmot := (ARRAY['fondos_insuficientes','tarjeta_rechazada','error_pasarela','datos_incorrectos','limite_excedido'])[1+floor(random()*5)::int];
        INSERT INTO pago (pedido_id, metodo_pago_id, moneda_id, monto, estado, referencia_externa, fecha_creacion)
        VALUES (NULL, v_metpago, 1, v_total, 'fallido',
                CASE WHEN v_metpago=3 THEN 'VISA ****'||lpad((1000+floor(random()*9000))::int::text,4,'0') ELSE NULL END, v_ts)
        RETURNING id INTO v_pago;
        INSERT INTO transaccion_pago (pago_id, tipo, estado, monto, respuesta_pasarela, fecha_creacion)
        VALUES (v_pago, 'autorizacion', 'fallida', v_total,
                jsonb_build_object('estado','RECHAZADA','motivo',v_fmot), v_ts);
        n_fail := n_fail+1;
    END LOOP;

    -- ==================== MARCA / REVERSION ==========================
    INSERT INTO configuracion_tienda (clave, valor, tipo_dato, descripcion, fecha_creacion)
    VALUES ('seed_bb_60_ventas', v_thr::text, 'json',
            'Bloque B/60: ciclo de venta 18 meses. Reversion: restaurar stock (sumar salidas sembradas) y borrar id>umbral.', now());

    RAISE NOTICE 'Bloque B / 60 OK. Pedidos: % (web % / tienda % / telefono %). Entregados: %, en curso: %, cancelados: %, no_entregado: %. Facturas: %, pagos: %, fallidos: %, envios: %, salidas: % (% uds).',
        n_ped, n_web, n_tienda, n_tel, n_deliv, n_prog, n_cancel, n_noent, n_fac, n_pago, n_fail, n_env, n_sal, n_units;
END $$;
