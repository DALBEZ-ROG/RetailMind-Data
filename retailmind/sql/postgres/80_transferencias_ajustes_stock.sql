-- ============================================================================
-- 80_transferencias_ajustes_stock.sql
-- OBJETIVOS TACTICOS OTD-INV-06 (transferencias entre bodegas) y OTD-INV-05
-- (ajustes de inventario con motivo) — seccion 8 de
-- docs/tactico/AUDITORIA_TRANSVERSAL_SEED.md.
--
-- ANTES: 10 transferencias (todas 'recibida', todas de julio-2026, cero en
--        camino) y 3 ajustes. Ningun bloque del seed las toco.
--
-- ESTOS SON LOS DOS UNICOS OBJETIVOS QUE MUEVEN STOCK. Se siembran por el
-- MISMO camino que el sistema real (InventarioService + StockService):
--   * transferencia_bodega y ajuste_inventario son SOLO CABECERA (no tienen
--     tabla de detalle en el esquema): la variante y la cantidad viven en el
--     kardex (referencia_tipo = 'transferencia_bodega' / 'ajuste_inventario')
--     y, en texto, en la observacion/motivo con el formato "[SKU xN] ...".
--   * transferencia 'recibida'  -> salida_transferencia en origen (fecha_envio)
--                                  + entrada_transferencia en destino (fecha_recepcion)
--     transferencia 'en_transito' -> SOLO la salida en origen: la mercaderia
--                                  salio y todavia no llego (eso es, literalmente,
--                                  stock en transito). El sistema real no tiene
--                                  endpoint de recepcion diferida (crea la
--                                  transferencia ya 'recibida'), asi que este
--                                  estado es dato historico, no un flujo vivo.
--     transferencia 'pendiente' / 'cancelada' -> NINGUN movimiento de kardex
--                                  (solicitada o anulada antes de despachar).
--   * ajuste 'aplicado' -> un movimiento (entrada_ajuste / salida_ajuste).
--     ajuste 'anulado'  -> su movimiento + el CONTRAMOVIMIENTO que escribe
--                          InventarioService.anularAjuste (neto cero), con el
--                          motivo concatenado " · ANULADO: ..." tal cual lo
--                          hace el UPDATE real.
--
-- GARANTIA DE INTEGRIDAD DEL KARDEX (lo que manda sobre poblar el objetivo):
--   (a) NO-NEGATIVIDAD CRONOLOGICA. Para cada SALIDA nueva en el pasado se
--       calcula, sobre la cadena PRISTINA de esa (variante, bodega), el
--       "max_seguro" = minimo saldo desde esa fecha hasta hoy (suffix-min).
--       Solo se siembra si cantidad <= max_seguro. El balance final NO basta:
--       hay que respetar la cronologia completa (misma leccion del script 78).
--   (b) UNA SOLA SALIDA POR (variante, bodega). Asi el max_seguro calculado
--       sobre la cadena pristina sigue siendo valido para todos los eventos
--       (las ENTRADAS solo suben el saldo, nunca lo bajan).
--   (c) REENCADENADO. El kardex se encadena por (fecha_creacion, id) y toda
--       cadena arranca en 0; insertar en el pasado obliga a recomputar
--       stock_anterior/stock_nuevo de la cadena completa de cada (variante,
--       bodega) afectada.
--   (d) inventario.stock_actual SI cambia aqui (a diferencia del script 78):
--       un ajuste de merma y una transferencia en transito son perdidas y
--       traslados REALES de stock. Se reescribe con el saldo final de la
--       cadena, que es lo que hace StockService.
--   (e) Verificacion en la MISMA transaccion: 0 saldos negativos, 0 eslabones
--       rotos, kardex = stock_actual en las 1.372 filas y huellas md5 de
--       ventas/compras/dinero intactas. Cualquier fallo -> EXCEPTION -> ROLLBACK.
--
-- Marca 'seed_op_80_transferencias_ajustes' en configuracion_tienda
-- (idempotente). Reversion: 99_revert_objetivos_pendientes.sql.
-- Ejecutar como postgres (superusuario) sobre la BD retailmind.
-- ============================================================================
\set ON_ERROR_STOP on

BEGIN;

DO $$
DECLARE
    -- catalogo de motivos de ajuste (paralelos: tipo de cabecera / signo / texto)
    v_mot_tipo  text[] := ARRAY['negativo','negativo','negativo','conteo','conteo','positivo','negativo'];
    v_mot_signo int[]  := ARRAY[-1, -1, -1, -1, 1, 1, -1];
    v_mot_texto text[] := ARRAY[
        'Merma por manipulacion en bodega',
        'Producto roto durante el almacenamiento',
        'Producto caducado retirado del stock vendible',
        'Diferencia de conteo fisico: faltante',
        'Diferencia de conteo fisico: sobrante',
        'Correccion de error de registro en recepcion',
        'Correccion de error de registro: unidades duplicadas'
    ];
    v_obs_transf text[] := ARRAY[
        'Reposicion por bajo stock en destino',
        'Rebalanceo de existencias entre bodegas',
        'Consolidacion de saldo para despacho',
        'Traslado por pedido comprometido en destino',
        'Devolucion de excedente a bodega de origen',
        'Reubicacion por reorganizacion de bodega'
    ];
    v_seq      int := 0;
    v_m        int;
    v_i        int;
    v_n        int;
    v_n2       int;
    v_r        bigint;
    v_fecha    timestamptz;
    v_fecha2   timestamptz;
    v_bo       bigint;
    v_bd       bigint;
    v_cant     int;
    v_estado   text;
    v_usuario  bigint;
    v_tope     timestamptz := timestamptz '2026-07-24 18:00:00-05';
    v_base     date;
    v_ev       record;
    v_var      bigint;
    v_sku      text;
    v_maxseg   int;
    v_id       bigint;
    v_omitidos int := 0;
    v_transf   int := 0;
    v_ajustes  int := 0;
    v_movs     int := 0;
    v_pares    int := 0;
    v_bad      int;
    v_txt      text;
BEGIN
    IF EXISTS (SELECT 1 FROM configuracion_tienda WHERE clave = 'seed_op_80_transferencias_ajustes') THEN
        RAISE NOTICE 'Objetivos 1 y 5 (transferencias/ajustes) ya sembrados; se omite.';
        RETURN;
    END IF;

    -- ── 1. Cadena PRISTINA por (variante, bodega) con su suffix-min ─────────
    CREATE TEMP TABLE op80_sfx ON COMMIT DROP AS
    WITH ch AS (
        SELECT m.producto_variante_id AS v, m.bodega_id AS b, m.fecha_creacion, m.id,
               sum(tm.factor * m.cantidad) OVER (
                   PARTITION BY m.producto_variante_id, m.bodega_id
                   ORDER BY m.fecha_creacion, m.id ROWS UNBOUNDED PRECEDING) AS saldo
        FROM movimiento_inventario m
        JOIN tipo_movimiento tm ON tm.id = m.tipo_movimiento_id)
    SELECT v, b, fecha_creacion, id, saldo,
           min(saldo) OVER (PARTITION BY v, b
                            ORDER BY fecha_creacion DESC, id DESC
                            ROWS UNBOUNDED PRECEDING)::int AS suffix_min
    FROM ch;
    CREATE INDEX ON op80_sfx (v, b, fecha_creacion DESC, id DESC);
    CREATE INDEX ON op80_sfx (b, v);

    -- ── 2. Plan de eventos ──────────────────────────────────────────────────
    CREATE TEMP TABLE op80_plan (
        seq        int PRIMARY KEY,
        clase      text NOT NULL,          -- 'transferencia' | 'ajuste'
        estado     text NOT NULL,
        tipo       text,                   -- cabecera de ajuste
        fecha      timestamptz NOT NULL,   -- salida / aplicacion
        fecha2     timestamptz,            -- recepcion / anulacion
        b_origen   bigint NOT NULL,
        b_destino  bigint,
        signo      int NOT NULL,           -- signo del movimiento en b_origen
        mueve      boolean NOT NULL,
        cantidad   int NOT NULL,
        motivo     text,
        usuario_id bigint,
        variante_id bigint,
        sku        text
    ) ON COMMIT DROP;

    -- 2a. TRANSFERENCIAS 'recibida': 19 meses (ene-2025 .. jul-2026), 2-3/mes
    FOR v_m IN 0..18 LOOP
        v_base := (date '2025-01-01' + (v_m || ' month')::interval)::date;
        v_n := 2 + (v_m % 2);                                    -- 2 o 3 al mes
        FOR v_i IN 1..v_n LOOP
            v_seq := v_seq + 1;
            v_r := ('x' || substr(md5('t' || v_seq::text), 1, 8))::bit(32)::bigint & 2147483647;
            v_fecha := v_base
                     + ((3 + (v_r % 24)) || ' day')::interval
                     + ((8 + (v_r / 7 % 9)) || ' hour')::interval
                     + ((v_r / 13 % 60) || ' minute')::interval
                     + ((v_r / 17 % 60) || ' second')::interval;
            IF extract(dow FROM v_fecha) = 0 THEN                -- domingo: bodega no opera
                v_fecha := v_fecha + interval '1 day';
            END IF;
            CONTINUE WHEN v_fecha > v_tope;
            -- 1 de cada 5 va de Bodega Norte a Central (devolucion de excedente)
            IF v_seq % 5 = 0 THEN v_bo := 3; v_bd := 4; ELSE v_bo := 4; v_bd := 3; END IF;
            v_cant := CASE WHEN v_bo = 3 THEN 3 + (v_r % 9) ELSE 5 + (v_r % 24) END;
            INSERT INTO op80_plan (seq, clase, estado, fecha, fecha2, b_origen, b_destino,
                                   signo, mueve, cantidad, motivo, usuario_id)
            VALUES (v_seq, 'transferencia', 'recibida', v_fecha,
                    v_fecha + ((1 + (v_r % 3)) || ' day')::interval + interval '4 hour',
                    v_bo, v_bd, -1, true, v_cant,
                    v_obs_transf[1 + (v_r % array_length(v_obs_transf, 1))],
                    CASE WHEN v_r % 4 = 0 THEN 2 ELSE 9 END);
        END LOOP;
    END LOOP;

    -- 2b. TRANSFERENCIAS 'en_transito' (recientes: julio-2026) -> solo la salida
    FOR v_i IN 1..7 LOOP
        v_seq := v_seq + 1;
        v_r := ('x' || substr(md5('e' || v_seq::text), 1, 8))::bit(32)::bigint & 2147483647;
        v_fecha := timestamptz '2026-07-13 08:00:00-05'
                 + ((v_i - 1) * 36 + (v_r % 20) || ' hour')::interval
                 + ((v_r / 11 % 60) || ' minute')::interval;
        IF v_i % 4 = 0 THEN v_bo := 3; v_bd := 4; ELSE v_bo := 4; v_bd := 3; END IF;
        INSERT INTO op80_plan (seq, clase, estado, fecha, b_origen, b_destino,
                               signo, mueve, cantidad, motivo, usuario_id)
        VALUES (v_seq, 'transferencia', 'en_transito', v_fecha, v_bo, v_bd, -1, true,
                CASE WHEN v_bo = 3 THEN 3 + (v_r % 7) ELSE 6 + (v_r % 18) END,
                v_obs_transf[1 + (v_r % array_length(v_obs_transf, 1))], 9);
    END LOOP;

    -- 2c. TRANSFERENCIAS 'pendiente' (solicitadas, aun sin despachar) -> sin kardex
    FOR v_i IN 1..4 LOOP
        v_seq := v_seq + 1;
        v_r := ('x' || substr(md5('p' || v_seq::text), 1, 8))::bit(32)::bigint & 2147483647;
        INSERT INTO op80_plan (seq, clase, estado, fecha, b_origen, b_destino,
                               signo, mueve, cantidad, motivo, usuario_id)
        VALUES (v_seq, 'transferencia', 'pendiente',
                timestamptz '2026-07-21 09:00:00-05' + ((v_i * 17 + (v_r % 9)) || ' hour')::interval,
                4, 3, -1, false, 4 + (v_r % 15),
                v_obs_transf[1 + (v_r % array_length(v_obs_transf, 1))], 9);
    END LOOP;

    -- 2d. TRANSFERENCIAS 'cancelada' (anuladas antes de despachar) -> sin kardex
    FOREACH v_txt IN ARRAY ARRAY['2025-06-11 10:20:00-05','2025-11-19 15:05:00-05',
                                 '2026-04-08 11:40:00-05'] LOOP
        v_seq := v_seq + 1;
        v_r := ('x' || substr(md5('c' || v_seq::text), 1, 8))::bit(32)::bigint & 2147483647;
        INSERT INTO op80_plan (seq, clase, estado, fecha, b_origen, b_destino,
                               signo, mueve, cantidad, motivo, usuario_id)
        VALUES (v_seq, 'transferencia', 'cancelada', v_txt::timestamptz, 4, 3, -1, false,
                4 + (v_r % 12), 'Cancelada: el destino cubrio la necesidad con stock propio', 9);
    END LOOP;

    -- 2e. AJUSTES 'aplicado': 19 meses, 2-3/mes
    FOR v_m IN 0..18 LOOP
        v_base := (date '2025-01-01' + (v_m || ' month')::interval)::date;
        v_n := 2 + ((v_m + 1) % 2);
        FOR v_i IN 1..v_n LOOP
            v_seq := v_seq + 1;
            v_r := ('x' || substr(md5('a' || v_seq::text), 1, 8))::bit(32)::bigint & 2147483647;
            v_fecha := v_base
                     + ((2 + (v_r % 25)) || ' day')::interval
                     + ((8 + (v_r / 5 % 9)) || ' hour')::interval
                     + ((v_r / 19 % 60) || ' minute')::interval
                     + ((7 + v_r / 23 % 50) || ' second')::interval;
            IF extract(dow FROM v_fecha) = 0 THEN
                v_fecha := v_fecha + interval '1 day';
            END IF;
            CONTINUE WHEN v_fecha > v_tope;
            v_n2 := 1 + (v_r % array_length(v_mot_tipo, 1));
            INSERT INTO op80_plan (seq, clase, estado, tipo, fecha, b_origen,
                                   signo, mueve, cantidad, motivo, usuario_id)
            VALUES (v_seq, 'ajuste', 'aplicado', v_mot_tipo[v_n2], v_fecha,
                    CASE WHEN v_r % 5 = 0 THEN 3 ELSE 4 END,
                    v_mot_signo[v_n2], true, 1 + (v_r % 8), v_mot_texto[v_n2],
                    CASE WHEN v_r % 7 = 0 THEN 2 ELSE 9 END);
        END LOOP;
    END LOOP;

    -- 2f. AJUSTES 'anulado' (movimiento + contramovimiento, neto cero)
    FOREACH v_txt IN ARRAY ARRAY['2025-05-14 11:15:00-05','2025-12-03 16:25:00-05',
                                 '2026-05-27 09:45:00-05'] LOOP
        v_seq := v_seq + 1;
        v_r := ('x' || substr(md5('n' || v_seq::text), 1, 8))::bit(32)::bigint & 2147483647;
        INSERT INTO op80_plan (seq, clase, estado, tipo, fecha, fecha2, b_origen,
                               signo, mueve, cantidad, motivo, usuario_id)
        VALUES (v_seq, 'ajuste', 'anulado', 'negativo', v_txt::timestamptz,
                v_txt::timestamptz + ((4 + (v_r % 11)) || ' day')::interval + interval '3 hour',
                4, -1, true, 1 + (v_r % 6),
                'Merma reportada por el operador de bodega', 9);
    END LOOP;

    -- ── 3. Asignacion de variante ───────────────────────────────────────────
    -- Regla (b): una (variante, bodega) no puede tener mas de una SALIDA nueva.
    -- La asignacion se acumula aparte y se aplica DESPUES del recorrido (no se
    -- modifica la tabla que alimenta el cursor).
    CREATE TEMP TABLE op80_usados (v bigint, b bigint, PRIMARY KEY (v, b)) ON COMMIT DROP;
    CREATE TEMP TABLE op80_asig (seq int PRIMARY KEY, variante_id bigint, sku text,
                                 cantidad int, descartado boolean NOT NULL DEFAULT false)
        ON COMMIT DROP;

    FOR v_ev IN SELECT * FROM op80_plan ORDER BY seq LOOP
        v_var := NULL;
        IF v_ev.mueve AND v_ev.signo = -1 THEN
            -- SALIDA: exige max_seguro >= cantidad sobre la cadena pristina
            v_cant := v_ev.cantidad;
            FOR v_i IN 1..2 LOOP     -- 2do intento con cantidad minima
                SELECT c.v, COALESCE((SELECT s.suffix_min FROM op80_sfx s
                                      WHERE s.v = c.v AND s.b = v_ev.b_origen
                                        AND s.fecha_creacion < v_ev.fecha
                                      ORDER BY s.fecha_creacion DESC, s.id DESC LIMIT 1), 0)
                INTO v_var, v_maxseg
                FROM (SELECT DISTINCT v FROM op80_sfx WHERE b = v_ev.b_origen) c
                WHERE NOT EXISTS (SELECT 1 FROM op80_usados u
                                  WHERE u.v = c.v AND u.b = v_ev.b_origen)
                  AND COALESCE((SELECT s.suffix_min FROM op80_sfx s
                                WHERE s.v = c.v AND s.b = v_ev.b_origen
                                  AND s.fecha_creacion < v_ev.fecha
                                ORDER BY s.fecha_creacion DESC, s.id DESC LIMIT 1), 0) >= v_cant
                ORDER BY md5(c.v::text || '#' || v_ev.seq::text)
                LIMIT 1;
                EXIT WHEN v_var IS NOT NULL;
                v_cant := 2;         -- reintento con el minimo defendible
            END LOOP;
            IF v_var IS NULL THEN
                v_omitidos := v_omitidos + 1;
                INSERT INTO op80_asig (seq, descartado) VALUES (v_ev.seq, true);
                CONTINUE;
            END IF;
            INSERT INTO op80_usados VALUES (v_var, v_ev.b_origen);
            v_cant := v_ev.cantidad;
        ELSIF v_ev.mueve AND v_ev.signo = 1 THEN
            -- ENTRADA pura (ajuste positivo): cualquier variante con stock en esa bodega
            SELECT c.v INTO v_var
            FROM (SELECT DISTINCT v FROM op80_sfx WHERE b = v_ev.b_origen) c
            ORDER BY md5(c.v::text || '#' || v_ev.seq::text)
            LIMIT 1;
            v_cant := v_ev.cantidad;
        ELSE
            -- Sin kardex ('pendiente'/'cancelada'): solo hace falta un SKU creible
            SELECT i.producto_variante_id INTO v_var
            FROM inventario i
            WHERE i.bodega_id = v_ev.b_origen AND i.stock_actual >= v_ev.cantidad
            ORDER BY md5(i.producto_variante_id::text || '#' || v_ev.seq::text)
            LIMIT 1;
            v_cant := v_ev.cantidad;
        END IF;

        SELECT sku INTO v_sku FROM producto_variante WHERE id = v_var;
        INSERT INTO op80_asig (seq, variante_id, sku, cantidad)
        VALUES (v_ev.seq, v_var, v_sku, v_cant);
    END LOOP;

    UPDATE op80_plan p SET variante_id = a.variante_id, sku = a.sku, cantidad = a.cantidad
      FROM op80_asig a WHERE a.seq = p.seq AND NOT a.descartado;
    DELETE FROM op80_plan p USING op80_asig a WHERE a.seq = p.seq AND a.descartado;

    IF EXISTS (SELECT 1 FROM op80_plan WHERE variante_id IS NULL) THEN
        RAISE EXCEPTION 'Plan incompleto: hay eventos sin variante asignada';
    END IF;

    -- ── 4. Cabeceras + kardex ───────────────────────────────────────────────
    FOR v_ev IN SELECT * FROM op80_plan ORDER BY seq LOOP
        IF v_ev.clase = 'transferencia' THEN
            INSERT INTO transferencia_bodega
                (bodega_origen_id, bodega_destino_id, usuario_solicita_id, estado,
                 fecha_envio, fecha_recepcion, observacion, fecha_creacion)
            VALUES (v_ev.b_origen, v_ev.b_destino, v_ev.usuario_id, v_ev.estado,
                    CASE WHEN v_ev.estado IN ('recibida','en_transito') THEN v_ev.fecha END,
                    CASE WHEN v_ev.estado = 'recibida' THEN v_ev.fecha2 END,
                    '[' || v_ev.sku || ' x' || v_ev.cantidad || '] ' || v_ev.motivo,
                    v_ev.fecha)
            RETURNING id INTO v_id;
            v_transf := v_transf + 1;

            IF v_ev.mueve THEN
                INSERT INTO movimiento_inventario
                    (producto_variante_id, bodega_id, tipo_movimiento_id, usuario_id,
                     cantidad, stock_anterior, stock_nuevo, costo_unitario,
                     referencia_tipo, referencia_id, observacion, fecha_creacion)
                VALUES (v_ev.variante_id, v_ev.b_origen,
                        (SELECT id FROM tipo_movimiento WHERE codigo = 'salida_transferencia'),
                        v_ev.usuario_id, v_ev.cantidad, 0, 0, NULL,
                        'transferencia_bodega', v_id, NULL, v_ev.fecha);
                v_movs := v_movs + 1;
                IF v_ev.estado = 'recibida' THEN
                    INSERT INTO movimiento_inventario
                        (producto_variante_id, bodega_id, tipo_movimiento_id, usuario_id,
                         cantidad, stock_anterior, stock_nuevo, costo_unitario,
                         referencia_tipo, referencia_id, observacion, fecha_creacion)
                    VALUES (v_ev.variante_id, v_ev.b_destino,
                            (SELECT id FROM tipo_movimiento WHERE codigo = 'entrada_transferencia'),
                            v_ev.usuario_id, v_ev.cantidad, 0, 0, NULL,
                            'transferencia_bodega', v_id, NULL, v_ev.fecha2);
                    v_movs := v_movs + 1;
                END IF;
            END IF;
        ELSE
            INSERT INTO ajuste_inventario
                (bodega_id, usuario_id, tipo, estado, motivo, fecha_aplicacion,
                 fecha_creacion, fecha_actualizacion)
            VALUES (v_ev.b_origen, v_ev.usuario_id, v_ev.tipo, v_ev.estado,
                    '[' || v_ev.sku || ' x' || v_ev.cantidad || '] ' || v_ev.motivo
                        || CASE WHEN v_ev.estado = 'anulado'
                                THEN ' · ANULADO: El conteo posterior no confirmo la merma'
                                ELSE '' END,
                    v_ev.fecha, v_ev.fecha,
                    CASE WHEN v_ev.estado = 'anulado' THEN v_ev.fecha2 END)
            RETURNING id INTO v_id;
            v_ajustes := v_ajustes + 1;

            INSERT INTO movimiento_inventario
                (producto_variante_id, bodega_id, tipo_movimiento_id, usuario_id,
                 cantidad, stock_anterior, stock_nuevo, costo_unitario,
                 referencia_tipo, referencia_id, observacion, fecha_creacion)
            VALUES (v_ev.variante_id, v_ev.b_origen,
                    (SELECT id FROM tipo_movimiento
                      WHERE codigo = CASE WHEN v_ev.signo = 1 THEN 'entrada_ajuste'
                                          ELSE 'salida_ajuste' END),
                    v_ev.usuario_id, v_ev.cantidad, 0, 0, NULL,
                    'ajuste_inventario', v_id, v_ev.motivo, v_ev.fecha);
            v_movs := v_movs + 1;

            IF v_ev.estado = 'anulado' THEN   -- contramovimiento (InventarioService.anularAjuste)
                INSERT INTO movimiento_inventario
                    (producto_variante_id, bodega_id, tipo_movimiento_id, usuario_id,
                     cantidad, stock_anterior, stock_nuevo, costo_unitario,
                     referencia_tipo, referencia_id, observacion, fecha_creacion)
                VALUES (v_ev.variante_id, v_ev.b_origen,
                        (SELECT id FROM tipo_movimiento
                          WHERE codigo = CASE WHEN v_ev.signo = 1 THEN 'salida_ajuste'
                                              ELSE 'entrada_ajuste' END),
                        v_ev.usuario_id, v_ev.cantidad, 0, 0, NULL,
                        'ajuste_inventario', v_id,
                        'Anulacion del ajuste #' || v_id
                            || ': El conteo posterior no confirmo la merma', v_ev.fecha2);
                v_movs := v_movs + 1;
            END IF;
        END IF;
    END LOOP;

    -- ── 5. Reencadenado de las cadenas afectadas ────────────────────────────
    CREATE TEMP TABLE op80_pares ON COMMIT DROP AS
    SELECT DISTINCT producto_variante_id AS v, bodega_id AS b
    FROM movimiento_inventario
    WHERE id > (SELECT max_id FROM seed_backup.op79_umbral WHERE tabla = 'movimiento_inventario');
    SELECT count(*) INTO v_pares FROM op80_pares;

    -- fila de inventario para los destinos que aun no la tenian (upsert de StockService)
    INSERT INTO inventario (producto_variante_id, bodega_id, stock_actual)
    SELECT p.v, p.b, 0 FROM op80_pares p
    ON CONFLICT (producto_variante_id, bodega_id) DO NOTHING;

    WITH ch AS (
        SELECT m.id,
               sum(tm.factor * m.cantidad) OVER (
                   PARTITION BY m.producto_variante_id, m.bodega_id
                   ORDER BY m.fecha_creacion, m.id ROWS UNBOUNDED PRECEDING) AS saldo,
               tm.factor * m.cantidad AS delta
        FROM movimiento_inventario m
        JOIN tipo_movimiento tm ON tm.id = m.tipo_movimiento_id
        JOIN op80_pares p ON p.v = m.producto_variante_id AND p.b = m.bodega_id)
    UPDATE movimiento_inventario m
       SET stock_anterior = (ch.saldo - ch.delta)::int,
           stock_nuevo    = ch.saldo::int
      FROM ch
     WHERE ch.id = m.id
       AND (m.stock_anterior <> (ch.saldo - ch.delta)::int OR m.stock_nuevo <> ch.saldo::int);

    -- ── 6. inventario.stock_actual = saldo final de la cadena ───────────────
    UPDATE inventario i
       SET stock_actual = k.saldo
      FROM (SELECT m.producto_variante_id v, m.bodega_id b,
                   sum(tm.factor * m.cantidad)::int saldo
            FROM movimiento_inventario m
            JOIN tipo_movimiento tm ON tm.id = m.tipo_movimiento_id
            JOIN op80_pares p ON p.v = m.producto_variante_id AND p.b = m.bodega_id
            GROUP BY 1, 2) k
     WHERE i.producto_variante_id = k.v AND i.bodega_id = k.b
       AND i.stock_actual <> k.saldo;

    -- ── 7. Verificacion dura (todo dentro de la misma transaccion) ──────────
    WITH ch AS (
        SELECT m.id, m.stock_anterior, m.stock_nuevo, tm.factor * m.cantidad AS delta,
               sum(tm.factor * m.cantidad) OVER (
                   PARTITION BY m.producto_variante_id, m.bodega_id
                   ORDER BY m.fecha_creacion, m.id ROWS UNBOUNDED PRECEDING) AS saldo
        FROM movimiento_inventario m
        JOIN tipo_movimiento tm ON tm.id = m.tipo_movimiento_id)
    SELECT count(*) FILTER (WHERE saldo < 0)
         + count(*) FILTER (WHERE stock_nuevo <> saldo)
         + count(*) FILTER (WHERE stock_nuevo - stock_anterior <> delta)
      INTO v_bad FROM ch;
    IF v_bad <> 0 THEN
        RAISE EXCEPTION 'Kardex inconsistente tras el reencadenado: % filas en falta', v_bad;
    END IF;

    SELECT count(*) INTO v_bad
    FROM inventario i
    LEFT JOIN (SELECT m.producto_variante_id v, m.bodega_id b,
                      sum(tm.factor * m.cantidad)::int saldo
               FROM movimiento_inventario m
               JOIN tipo_movimiento tm ON tm.id = m.tipo_movimiento_id
               GROUP BY 1, 2) k ON k.v = i.producto_variante_id AND k.b = i.bodega_id
    WHERE i.stock_actual <> COALESCE(k.saldo, 0);
    IF v_bad <> 0 THEN
        RAISE EXCEPTION 'kardex <> inventario.stock_actual en % filas', v_bad;
    END IF;

    SELECT count(*) INTO v_bad FROM inventario WHERE stock_actual < 0;
    IF v_bad <> 0 THEN
        RAISE EXCEPTION 'inventario con stock negativo en % filas', v_bad;
    END IF;

    -- las ventas, las compras y el dinero no se tocaron: huellas identicas
    SELECT string_agg(h.tabla, ', ') INTO v_txt
    FROM seed_backup.op79_huella h
    WHERE h.tabla = 'pedido' AND h.huella <> (
        SELECT md5(string_agg(id || '|' || cliente_id || '|' || estado_pedido_id || '|' || canal
                   || '|' || fecha_pedido::text || '|' || subtotal || '|' || monto_descuento
                   || '|' || monto_impuesto || '|' || costo_envio || '|' || total,
                   E'\n' ORDER BY id)) FROM pedido);
    IF v_txt IS NOT NULL THEN
        RAISE EXCEPTION 'La huella de pedido cambio: el script toco ventas';
    END IF;

    SELECT count(*) INTO v_bad FROM movimiento_inventario
    WHERE tipo_movimiento_id IN (1, 5)     -- entrada_compra / salida_venta
      AND id > (SELECT max_id FROM seed_backup.op79_umbral WHERE tabla = 'movimiento_inventario');
    IF v_bad <> 0 THEN
        RAISE EXCEPTION 'Se crearon % movimientos de compra/venta (no corresponde)', v_bad;
    END IF;

    -- ── 8. Marca ────────────────────────────────────────────────────────────
    INSERT INTO configuracion_tienda (clave, valor, tipo_dato, descripcion)
    VALUES ('seed_op_80_transferencias_ajustes',
            jsonb_build_object('fecha', now(), 'transferencia_bodega', v_transf,
                               'ajuste_inventario', v_ajustes,
                               'movimiento_inventario', v_movs,
                               'cadenas_reencadenadas', v_pares,
                               'eventos_omitidos_por_stock', v_omitidos)::text,
            'json', 'OTD-INV-06 (transferencias) y OTD-INV-05 (ajustes) — script 80')
    ON CONFLICT (clave) DO UPDATE SET valor = EXCLUDED.valor;

    RAISE NOTICE 'Transferencias: %, ajustes: %, movimientos: %, cadenas reencadenadas: %, omitidos: %',
                 v_transf, v_ajustes, v_movs, v_pares, v_omitidos;
END $$;

COMMIT;

\echo '--- OTD-INV-06: transferencias por estado y por anio ---'
SELECT estado, count(*) filas, min(fecha_creacion)::date desde, max(fecha_creacion)::date hasta
FROM transferencia_bodega GROUP BY estado ORDER BY estado;

\echo '--- OTD-INV-05: ajustes por tipo/estado y motivos ---'
SELECT tipo, estado, count(*) FROM ajuste_inventario GROUP BY 1,2 ORDER BY 1,2;
SELECT regexp_replace(split_part(motivo, '] ', 2), ' · ANULADO.*$', '') motivo, count(*)
FROM ajuste_inventario GROUP BY 1 ORDER BY 2 DESC;

\echo '--- Invariantes de kardex ---'
WITH ch AS (
  SELECT m.stock_anterior, m.stock_nuevo, tm.factor*m.cantidad delta,
         sum(tm.factor*m.cantidad) OVER (PARTITION BY m.producto_variante_id, m.bodega_id
                                         ORDER BY m.fecha_creacion, m.id ROWS UNBOUNDED PRECEDING) saldo
  FROM movimiento_inventario m JOIN tipo_movimiento tm ON tm.id=m.tipo_movimiento_id)
SELECT count(*) movs, count(*) FILTER (WHERE saldo<0) negativos,
       count(*) FILTER (WHERE stock_nuevo<>saldo) desalineados,
       count(*) FILTER (WHERE stock_nuevo-stock_anterior<>delta) eslabon_roto FROM ch;
