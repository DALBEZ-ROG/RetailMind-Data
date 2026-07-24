-- =====================================================================
-- 61_seed_bloque_b_carritos.sql
-- SIEMBRA DE VOLUMEN HISTORICO - BLOQUE B / Parte 2: CARRITOS
-- ---------------------------------------------------------------------
-- Hoy los 19-20 carritos existentes estan todos 'convertido', dejando el
-- informe de CARRITOS ABANDONADOS vacio. Se siembran carritos 'activo' y
-- 'abandonado' (mayoria abandonados) con sus items y antiguedades variadas
-- a lo largo de los ultimos ~7 meses.
--
-- INVARIANTES:
--   * carrito exige cliente_id OR sesion_token (ck_carrito_propietario):
--     se usan clientes reales y algunos carritos "invitado" con token.
--   * carrito_item.cantidad>0, precio_unitario>=0 (del catalogo).
--   * fecha_actualizacion la fija el trigger touch; no se escribe.
--   * Sin efecto sobre stock (los carritos no reservan en este modelo).
--
-- Marca/reversion: umbrales max(id) en configuracion_tienda
--   (clave 'seed_bb_61_carritos'). Ejecutar como postgres.
-- =====================================================================

DO $$
DECLARE
    v_thr jsonb;
    v_now constant timestamptz := timestamptz '2026-07-24 12:00:00-05';
    i int; j int; n_items int; v_cart bigint; v_cli bigint; v_estado text;
    v_ts timestamptz; v_var bigint; v_precio numeric; v_token text;
    n_ab int:=0; n_ac int:=0; n_it int:=0;
    v_picked bigint[];
BEGIN
    IF EXISTS (SELECT 1 FROM configuracion_tienda WHERE clave='seed_bb_61_carritos') THEN
        RAISE NOTICE 'Bloque B / 61 (carritos) ya sembrado; se omite.';
        RETURN;
    END IF;

    PERFORM setseed(0.6101);
    v_thr := jsonb_build_object(
        'carrito',      (SELECT COALESCE(max(id),0) FROM carrito),
        'carrito_item', (SELECT COALESCE(max(id),0) FROM carrito_item));

    -- pool de variantes vendibles con precio (catalogo)
    CREATE TEMP TABLE cart_var ON COMMIT DROP AS
    SELECT pv.id AS variante_id, pv.precio
    FROM producto_variante pv
    WHERE pv.precio > 0 AND pv.activo
    ORDER BY random() LIMIT 400;

    FOR i IN 1..270 LOOP
        -- 78% abandonados, 22% activos
        IF random() < 0.78 THEN
            v_estado := 'abandonado'; n_ab := n_ab+1;
            -- antiguedad: repartida 2026-01 .. 2026-07 (varios meses)
            v_ts := timestamptz '2026-01-05 09:00:00-05' + (random()*195) * interval '1 day';
        ELSE
            v_estado := 'activo'; n_ac := n_ac+1;
            -- activos recientes (ultimos ~12 dias)
            v_ts := v_now - (random()*12) * interval '1 day';
        END IF;
        IF v_ts > v_now THEN v_ts := v_now - interval '1 day'; END IF;

        -- 82% de cliente registrado, 18% invitado (sesion_token)
        IF random() < 0.82 THEN
            SELECT id INTO v_cli FROM cliente WHERE activo
              AND fecha_creacion <= v_ts ORDER BY random() LIMIT 1;
            v_token := NULL;
        ELSE
            v_cli := NULL;
            v_token := 'guest-' || md5(random()::text || i::text);
        END IF;
        CONTINUE WHEN v_cli IS NULL AND v_token IS NULL;

        INSERT INTO carrito (cliente_id, sesion_token, estado, fecha_creacion)
        VALUES (v_cli, v_token, v_estado, v_ts)
        RETURNING id INTO v_cart;

        n_items := 1 + floor(random()*4)::int;   -- 1..4
        v_picked := '{}';
        FOR j IN 1..n_items LOOP
            SELECT variante_id, precio INTO v_var, v_precio
            FROM cart_var WHERE variante_id <> ALL(v_picked) ORDER BY random() LIMIT 1;
            CONTINUE WHEN v_var IS NULL;
            v_picked := v_picked || v_var;
            INSERT INTO carrito_item (carrito_id, producto_variante_id, cantidad, precio_unitario, fecha_creacion)
            VALUES (v_cart, v_var, 1+floor(random()*3)::int, round(v_precio::numeric,2), v_ts);
            n_it := n_it+1;
        END LOOP;
    END LOOP;

    INSERT INTO configuracion_tienda (clave, valor, tipo_dato, descripcion, fecha_creacion)
    VALUES ('seed_bb_61_carritos', v_thr::text, 'json',
            'Bloque B/61: carritos activo/abandonado. Reversion: borrar id>umbral (carrito_item, carrito).', now());

    RAISE NOTICE 'Bloque B / 61 OK. Carritos abandonados: %, activos: %, items: %.', n_ab, n_ac, n_it;
END $$;
