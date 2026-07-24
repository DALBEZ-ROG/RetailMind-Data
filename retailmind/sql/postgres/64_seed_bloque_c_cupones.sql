-- =====================================================================
-- 64_seed_bloque_c_cupones.sql
-- SIEMBRA DE VOLUMEN HISTORICO - BLOQUE C / Parte 1: CUPONES + USOS
-- ---------------------------------------------------------------------
-- Objetivos tacticos OTD-GER-04 / OTD-GER-05 (informe de cupones
-- efectivamente usados). Hoy: 6 cupones y solo 3 usos => informe inerte.
--
-- Que hace:
--   * Siembra 23 cupones nuevos (porcentaje / monto_fijo / envio_gratis)
--     repartidos en los 18 meses (ene-2025 .. jul-2026), con vigencias,
--     limites de uso (usos_maximos, usos_por_cliente) y estado.
--   * Liga sus USOS a pedidos WEB REALES ya sembrados (Bloque B) que caen
--     DENTRO de la vigencia del cupon y cumplen el monto minimo.
--   * Distribucion NO plana: cupones muy usados (bienvenida/fidelidad/
--     Black Friday/Navidad, ~100-140 usos) y cupones que casi nadie uso
--     (min alto o ventana flash, 3-6 usos), para que el informe discrimine.
--
-- CORRELACION MONETARIA (decision de alcance, ver cabecera del bloque):
--   Los pedidos de Bloque B nacieron con monto_descuento = 0 y su pago /
--   factura cuadran a ese total. La invariante "no modifica pedidos, pagos
--   ni stock" es ABSOLUTA, asi que NO se retrofitea descuento en el pedido.
--   uso_cupon.monto_descontado se calcula EXACTO por la REGLA del cupon
--   sobre el SUBTOTAL REAL (neto, sin IVA) del pedido ligado:
--     - porcentaje : round(subtotal * valor/100, 2)
--     - monto_fijo : LEAST(valor, subtotal)          (min >= valor => = valor)
--     - envio_gratis: round(costo_envio, 2)           (ahorro real de envio)
--   Es coherente con los importes reales del pedido (<= subtotal, min
--   respetado). pedido.monto_descuento permanece intacto (0). Los 3 usos
--   reales/originales NO se tocan (se excluyen sus pedidos).
--
-- TRIGGER: fn_registrar_uso_cupon (BEFORE INSERT en uso_cupon) valida la
-- vigencia contra now() y mantiene cupon.usos_actuales. Como sembramos
-- usos HISTORICOS de cupones ya vencidos, se ejecuta bajo
-- session_replication_role='replica' (SET LOCAL, superusuario, NO es DDL:
-- se reestablece solo al COMMIT) y usos_actuales se fija al final con el
-- CONTEO real por cupon (solo cupones sembrados).
--
-- Marca/reversion: umbrales max(id) en configuracion_tienda
--   (clave 'seed_bc_64_cupones'). Tag [SEED-BC] en cupon.descripcion.
-- Idempotente (guarda por la marca) y transaccional. Ejecutar como postgres.
-- =====================================================================

BEGIN;

SET LOCAL session_replication_role = 'replica';   -- bypass fn_registrar_uso_cupon (vigencia now())

DO $$
DECLARE
    v_thr    jsonb;
    v_now    constant timestamptz := timestamptz '2026-07-24 12:00:00-05';
    rec      record;
    v_ins    int;
    v_tot    int := 0;
    v_ncup   int;
BEGIN
    IF EXISTS (SELECT 1 FROM configuracion_tienda WHERE clave='seed_bc_64_cupones') THEN
        RAISE NOTICE 'Bloque C / 64 (cupones) ya sembrado; se omite.';
        RETURN;
    END IF;

    PERFORM setseed(0.64);

    v_thr := jsonb_build_object(
        'cupon',     (SELECT COALESCE(max(id),0) FROM cupon),
        'uso_cupon', (SELECT COALESCE(max(id),0) FROM uso_cupon)
    );

    -- ---- pedidos web elegibles (SOLO lectura; excluye los 3 usos reales) ----
    CREATE TEMP TABLE seed_ped_web ON COMMIT DROP AS
    SELECT p.id, p.cliente_id, p.fecha_pedido, p.subtotal, p.costo_envio,
           false AS used
    FROM pedido p
    JOIN estado_pedido ep ON ep.id = p.estado_pedido_id
    WHERE p.canal = 'web'
      AND ep.codigo <> 'cancelado'
      AND p.subtotal > 0
      AND NOT EXISTS (SELECT 1 FROM uso_cupon uc WHERE uc.pedido_id = p.id);
    CREATE INDEX ON seed_ped_web (used);

    -- ---- definicion de cupones (ord = orden de asignacion: primero los de
    --      ventana estrecha / monto alto, para que no se los coman los amplios) ----
    CREATE TEMP TABLE seed_cupon_def (
        ord    int,
        codigo text,
        tipo   text,
        valor  numeric,
        minp   numeric,
        ini    timestamptz,
        fin    timestamptz,
        umax   int,
        upc    int,
        activo boolean,
        target int,
        descr  text
    ) ON COMMIT DROP;
    INSERT INTO seed_cupon_def (ord,codigo,tipo,valor,minp,ini,fin,umax,upc,activo,target,descr) VALUES
     ( 1,'PREMIUM1000','monto_fijo', 50,1000,'2025-01-01 00:00:00-05','2026-07-24 12:00:00-05',NULL,1,true,  3,'Cupon premium para pedidos grandes (min $1000)'),
     ( 2,'VIP500',     'porcentaje', 15, 500,'2025-01-01 00:00:00-05','2026-07-24 12:00:00-05',NULL,1,true,  6,'Descuento VIP pedidos sobre $500'),
     ( 3,'FLASH24H',   'porcentaje', 25, 150,'2025-09-15 00:00:00-05','2025-09-16 23:59:59-05',  30,1,true,  4,'Venta flash 24 horas'),
     ( 4,'MADRUGADOR', 'monto_fijo', 15, 200,'2025-10-01 00:00:00-05','2025-10-05 23:59:59-05',  30,1,true,  5,'Oferta madrugadores inicio de octubre'),
     ( 5,'REYES2025',  'porcentaje', 12,  50,'2025-01-02 00:00:00-05','2025-01-10 23:59:59-05',  50,1,true, 18,'Reyes Magos 2025'),
     ( 6,'AMOR2025',   'monto_fijo',  8,  60,'2025-02-07 00:00:00-05','2025-02-16 23:59:59-05',  50,1,true, 20,'San Valentin 2025'),
     ( 7,'MAMA2025',   'porcentaje', 15,  80,'2025-05-01 00:00:00-05','2025-05-12 23:59:59-05',  60,1,true, 30,'Dia de la Madre 2025'),
     ( 8,'PAPA2025',   'porcentaje', 12,  70,'2025-06-10 00:00:00-05','2025-06-21 23:59:59-05',  50,1,true, 22,'Dia del Padre 2025'),
     ( 9,'INVIERNO2025','monto_fijo',10,  90,'2025-07-01 00:00:00-05','2025-07-31 23:59:59-05',  40,1,true, 18,'Ofertas de invierno 2025'),
     (10,'PATRIAS2025','porcentaje', 10,  45,'2025-08-05 00:00:00-05','2025-08-15 23:59:59-05',  40,1,true, 20,'Fiestas Patrias 2025'),
     (11,'CYBER2025',  'porcentaje', 18,  50,'2025-12-01 00:00:00-05','2025-12-02 23:59:59-05',  40,1,true, 15,'Cyber Monday 2025'),
     (12,'NAVIDAD2025','porcentaje', 15,  70,'2025-12-10 00:00:00-05','2025-12-26 23:59:59-05', 120,1,true, 50,'Campana de Navidad 2025'),
     (13,'FINDEANO2025','monto_fijo',12, 100,'2025-12-27 00:00:00-05','2025-12-31 23:59:59-05',  40,1,true, 14,'Fin de Ano 2025'),
     (14,'ESCOLAR2025','porcentaje', 10,  40,'2025-04-01 00:00:00-05','2025-05-15 23:59:59-05', 100,1,true, 45,'Vuelta a clases Costa 2025'),
     (15,'BLACKFRIDAY2025','porcentaje',20,60,'2025-11-24 00:00:00-05','2025-11-30 23:59:59-05',150,1,true, 55,'Black Friday 2025'),
     (16,'REYES2026',  'porcentaje', 12,  50,'2026-01-02 00:00:00-05','2026-01-10 23:59:59-05',  50,1,true, 16,'Reyes Magos 2026'),
     (17,'AMOR2026',   'monto_fijo',  8,  60,'2026-02-07 00:00:00-05','2026-02-16 23:59:59-05',  50,1,true, 20,'San Valentin 2026'),
     (18,'MAMA2026',   'porcentaje', 15,  80,'2026-05-01 00:00:00-05','2026-05-12 23:59:59-05',  60,1,true, 32,'Dia de la Madre 2026'),
     (19,'ESCOLAR2026','porcentaje', 10,  40,'2026-04-01 00:00:00-05','2026-05-15 23:59:59-05', 100,1,true, 48,'Vuelta a clases Costa 2026'),
     (20,'ENVIOGRATIS26','envio_gratis',0,120,'2026-03-01 00:00:00-05','2026-07-24 12:00:00-05',80,2,true, 40,'Envio gratis pedidos sobre $120'),
     (21,'WEB5OFF',    'monto_fijo',  5,  40,'2025-03-01 00:00:00-05','2026-07-24 12:00:00-05',NULL,2,true,100,'Descuento web recurrente $5'),
     (22,'BIENVENIDO2025','porcentaje',10,30,'2025-01-01 00:00:00-05','2025-12-31 23:59:59-05',NULL,1,true,140,'Descuento de bienvenida primer pedido'),
     (23,'CLIENTEFIEL','porcentaje',  8,  25,'2025-01-01 00:00:00-05','2026-07-24 12:00:00-05',NULL,3,true,130,'Programa cliente fiel');

    -- ---- insertar cupones (tag [SEED-BC]; usos_actuales arranca en 0) ----
    INSERT INTO cupon (codigo, descripcion, tipo_descuento, valor, monto_minimo_pedido,
                       usos_maximos, usos_por_cliente, usos_actuales,
                       fecha_inicio, fecha_fin, activo, fecha_creacion)
    SELECT codigo, '[SEED-BC] '||descr, tipo, valor, minp, umax, upc, 0,
           ini, fin, activo, ini
    FROM seed_cupon_def;

    -- ---- por cada cupon: ligar usos a pedidos web reales dentro de vigencia ----
    FOR rec IN
        SELECT d.*, c.id AS cid
        FROM seed_cupon_def d
        JOIN cupon c ON c.codigo = d.codigo
        ORDER BY d.ord
    LOOP
        DROP TABLE IF EXISTS _pick;
        CREATE TEMP TABLE _pick AS
        WITH elig AS (
            SELECT sp.id, sp.cliente_id, sp.subtotal, sp.costo_envio, sp.fecha_pedido,
                   row_number() OVER (PARTITION BY sp.cliente_id ORDER BY random()) AS rn,
                   random() AS r
            FROM seed_ped_web sp
            WHERE NOT sp.used
              AND sp.fecha_pedido >= rec.ini
              AND sp.fecha_pedido <= COALESCE(rec.fin, v_now)
              AND sp.subtotal >= rec.minp
              AND (rec.tipo <> 'envio_gratis' OR sp.costo_envio > 0)
        )
        SELECT id, cliente_id, subtotal, costo_envio, fecha_pedido
        FROM elig
        WHERE rn <= rec.upc                       -- respeta usos_por_cliente
        ORDER BY r
        LIMIT LEAST(rec.target, COALESCE(rec.umax, 1000000));

        INSERT INTO uso_cupon (cupon_id, pedido_id, cliente_id, monto_descontado, fecha_creacion)
        SELECT rec.cid, pk.id, pk.cliente_id,
               CASE rec.tipo
                   WHEN 'porcentaje'   THEN round(pk.subtotal * rec.valor / 100.0, 2)
                   WHEN 'monto_fijo'   THEN LEAST(rec.valor, pk.subtotal)
                   ELSE round(pk.costo_envio, 2)          -- envio_gratis
               END,
               LEAST(pk.fecha_pedido + interval '3 minutes', v_now)
        FROM _pick pk;
        GET DIAGNOSTICS v_ins = ROW_COUNT;

        UPDATE seed_ped_web sp SET used = true FROM _pick pk WHERE sp.id = pk.id;
        v_tot := v_tot + v_ins;
    END LOOP;
    DROP TABLE IF EXISTS _pick;

    -- ---- usos_actuales = conteo real por cupon (SOLO cupones sembrados) ----
    UPDATE cupon c SET usos_actuales = sub.n
    FROM (SELECT cupon_id, count(*) AS n FROM uso_cupon GROUP BY cupon_id) sub
    WHERE c.id = sub.cupon_id
      AND c.id > (v_thr->>'cupon')::bigint;

    -- ---- marca / reversion ----
    INSERT INTO configuracion_tienda (clave, valor, tipo_dato, descripcion, fecha_creacion)
    VALUES ('seed_bc_64_cupones', v_thr::text, 'json',
            'Bloque C/64: cupones + usos ligados a pedidos web reales (regla exacta sobre subtotal, sin tocar pedidos). Reversion: borrar id>umbral.',
            now());

    SELECT count(*) INTO v_ncup FROM cupon WHERE id > (v_thr->>'cupon')::bigint;
    RAISE NOTICE 'Bloque C / 64 OK. Cupones nuevos: %, usos ligados: %.', v_ncup, v_tot;
END $$;

COMMIT;
