-- =====================================================================
-- 65_seed_bloque_c_marketing.sql
-- SIEMBRA DE VOLUMEN HISTORICO - BLOQUE C / Parte 2:
--   PROMOCIONES + promocion_producto + CAMPANAS + BANNERS
-- ---------------------------------------------------------------------
-- Objetivos tacticos OTD-GER-06 (marketing) / OTD-GER-07 (efectividad de
-- promociones). Hoy: 1 promocion, 1 campana, 1 banner => nada con que
-- comparar.
--
-- Que hace:
--   * 18 PROMOCIONES repartidas en los 18 meses, concentradas en las
--     temporadas altas ya sembradas (Reyes, San Valentin, vuelta a clases
--     Costa, Dia de la Madre/Padre, Black Friday, Navidad, fin de ano).
--   * CORRELACION OTD-GER-07: los PRODUCTOS de cada promocion se eligen
--     como los MAS VENDIDOS dentro de su PROPIA ventana (top-N por unidades
--     reales en [ini,fin]). Asi cada promocion cubre productos que
--     EFECTIVAMENTE se vendieron en esas fechas y el informe puede comparar
--     la venta del producto antes vs. durante la promocion.
--   * ~13 CAMPANAS (email/redes/web/sms/mixto) con presupuesto y estado
--     (finalizada las pasadas, activa la vigente, borrador/pausada algunas)
--     repartidas en el tiempo.
--   * ~16 BANNERS con vigencias, posiciones y orden; varios enganchados a
--     su campana (campana_id).
--
-- Invariantes: promocion.id/valor/etc NO GENERATED; sin triggers de INSERT
-- (solo touch on UPDATE). No toca pedidos/pagos/stock: las promociones son
-- historicas (fecha_fin < hoy) => NINGUNA queda vigente, no auto-aplican a
-- pedidos nuevos, y los pedidos de Bloque B ya existian con sus importes.
-- Tag [SEED-BC]. Marca 'seed_bc_65_marketing'. Idempotente/transaccional.
-- Ejecutar como postgres.
-- =====================================================================

BEGIN;

DO $$
DECLARE
    v_thr  jsonb;
    rec    record;
    v_pid  bigint;
    v_np   int;
    v_tot_pp int := 0;
    v_nprom int; v_ncamp int; v_nban int;
BEGIN
    IF EXISTS (SELECT 1 FROM configuracion_tienda WHERE clave='seed_bc_65_marketing') THEN
        RAISE NOTICE 'Bloque C / 65 (marketing) ya sembrado; se omite.';
        RETURN;
    END IF;

    v_thr := jsonb_build_object(
        'promocion',          (SELECT COALESCE(max(id),0) FROM promocion),
        'promocion_producto', (SELECT COALESCE(max(id),0) FROM promocion_producto),
        'campana',            (SELECT COALESCE(max(id),0) FROM campana),
        'banner',             (SELECT COALESCE(max(id),0) FROM banner)
    );

    -- ================= PROMOCIONES =================
    CREATE TEMP TABLE seed_promo_def (
        ord int, nombre text, tipo text, valor numeric,
        ini timestamptz, fin timestamptz, prioridad smallint,
        acumulable boolean, activo boolean, nprod int, descr text
    ) ON COMMIT DROP;
    INSERT INTO seed_promo_def (ord,nombre,tipo,valor,ini,fin,prioridad,acumulable,activo,nprod,descr) VALUES
     ( 1,'Reyes Magos 2025',            'porcentaje',10,'2025-01-06 00:00:00-05','2025-01-20 23:59:59-05', 5,false,true, 8,'Arranque de ano - Reyes (1er pedido sembrado = 13-ene-2025)'),
     ( 2,'San Valentin 2025',           'porcentaje',12,'2025-02-05 00:00:00-05','2025-02-16 23:59:59-05', 5,false,true, 8,'Regalos San Valentin'),
     ( 3,'Vuelta a Clases Costa 2025',  'porcentaje', 8,'2025-04-01 00:00:00-05','2025-05-05 23:59:59-05', 3,true, true,12,'Inicio escolar regimen Costa'),
     ( 4,'Dia de la Madre 2025',        'porcentaje',15,'2025-05-01 00:00:00-05','2025-05-12 23:59:59-05', 8,false,true,10,'Especial Dia de la Madre'),
     ( 5,'Dia del Padre 2025',          'porcentaje',12,'2025-06-08 00:00:00-05','2025-06-20 23:59:59-05', 6,false,true, 8,'Especial Dia del Padre'),
     ( 6,'Ofertas de Invierno 2025',    'monto_fijo',10,'2025-07-05 00:00:00-05','2025-07-25 23:59:59-05', 4,true, true, 8,'Liquidacion de invierno'),
     ( 7,'Fiestas Patrias 2025',        'porcentaje',10,'2025-08-05 00:00:00-05','2025-08-14 23:59:59-05', 5,false,true, 8,'Fiestas patrias de agosto'),
     ( 8,'Regreso a Clases Sierra 2025','porcentaje', 8,'2025-09-01 00:00:00-05','2025-09-20 23:59:59-05', 3,true, true, 8,'Inicio escolar regimen Sierra'),
     ( 9,'Preventa Black Friday 2025',  'porcentaje',12,'2025-11-10 00:00:00-05','2025-11-23 23:59:59-05', 6,false,true,10,'Anticipo Black Friday'),
     (10,'Black Friday 2025',           'porcentaje',22,'2025-11-24 00:00:00-05','2025-11-30 23:59:59-05',10,false,true,15,'Black Friday - mayor descuento'),
     (11,'Navidad 2025',                'porcentaje',15,'2025-12-05 00:00:00-05','2025-12-24 23:59:59-05', 9,false,true,15,'Campana de Navidad'),
     (12,'Fin de Ano 2025',             'porcentaje',12,'2025-12-26 00:00:00-05','2025-12-31 23:59:59-05', 6,false,true, 8,'Liquidacion fin de ano'),
     (13,'Reyes Magos 2026',            'porcentaje',10,'2026-01-02 00:00:00-05','2026-01-12 23:59:59-05', 5,false,true, 8,'Arranque de ano - Reyes'),
     (14,'San Valentin 2026',           'porcentaje',12,'2026-02-05 00:00:00-05','2026-02-16 23:59:59-05', 5,false,true, 8,'Regalos San Valentin'),
     (15,'Vuelta a Clases Costa 2026',  'porcentaje', 8,'2026-04-01 00:00:00-05','2026-05-05 23:59:59-05', 3,true, true,12,'Inicio escolar regimen Costa'),
     (16,'Dia de la Madre 2026',        'porcentaje',15,'2026-05-01 00:00:00-05','2026-05-12 23:59:59-05', 8,false,true,12,'Especial Dia de la Madre'),
     (17,'Ofertas de Temporada 2026',   'monto_fijo',10,'2026-06-10 00:00:00-05','2026-06-30 23:59:59-05', 4,true, true, 8,'Ofertas de media temporada'),
     (18,'Especial Julio 2026',         'porcentaje',10,'2026-07-01 00:00:00-05','2026-07-20 23:59:59-05', 5,false,true, 8,'Especial mitad de ano');

    FOR rec IN SELECT * FROM seed_promo_def ORDER BY ord LOOP
        INSERT INTO promocion (nombre, descripcion, tipo_descuento, valor,
                               fecha_inicio, fecha_fin, prioridad, acumulable, activo, fecha_creacion)
        VALUES (rec.nombre, '[SEED-BC] '||rec.descr, rec.tipo, rec.valor,
                rec.ini, rec.fin, rec.prioridad, rec.acumulable, rec.activo, rec.ini)
        RETURNING id INTO v_pid;

        -- productos = top-N mas vendidos DENTRO de la ventana de la promocion
        INSERT INTO promocion_producto (promocion_id, producto_id, fecha_creacion)
        SELECT v_pid, s.producto_id, rec.ini
        FROM (
            SELECT pv.producto_id, sum(pd.cantidad) AS u
            FROM pedido p
            JOIN pedido_detalle pd ON pd.pedido_id = p.id
            JOIN producto_variante pv ON pv.id = pd.producto_variante_id
            JOIN estado_pedido ep ON ep.id = p.estado_pedido_id
            WHERE ep.codigo <> 'cancelado'
              AND p.fecha_pedido >= rec.ini
              AND p.fecha_pedido <= rec.fin
            GROUP BY pv.producto_id
            ORDER BY u DESC, pv.producto_id
            LIMIT rec.nprod
        ) s;
        GET DIAGNOSTICS v_np = ROW_COUNT;
        v_tot_pp := v_tot_pp + v_np;
    END LOOP;

    -- ================= CAMPANAS =================
    CREATE TEMP TABLE seed_campana_def (
        nombre text, canal text, presupuesto numeric, estado text,
        ini date, fin date, descr text
    ) ON COMMIT DROP;
    INSERT INTO seed_campana_def (nombre,canal,presupuesto,estado,ini,fin,descr) VALUES
     ('Lanzamiento tienda online 2025','mixto', 3500,'finalizada','2025-01-05','2025-02-05','Campana de lanzamiento multicanal'),
     ('Email San Valentin 2025',       'email',  800,'finalizada','2025-02-03','2025-02-16','Newsletter de regalos'),
     ('Vuelta a Clases Costa 2025',    'mixto', 2500,'finalizada','2025-04-01','2025-05-05','Push escolar Costa'),
     ('Dia de la Madre 2025',          'redes', 1800,'finalizada','2025-04-28','2025-05-12','Social ads Dia de la Madre'),
     ('Ofertas de Invierno 2025',      'web',   1200,'finalizada','2025-07-05','2025-07-25','Banners on-site invierno'),
     ('Regreso a Clases Sierra 2025',  'sms',    600,'finalizada','2025-09-01','2025-09-20','SMS a clientes Sierra'),
     ('Black Friday 2025',             'mixto', 6000,'finalizada','2025-11-10','2025-11-30','Gran campana Black Friday'),
     ('Navidad 2025',                  'mixto', 5000,'finalizada','2025-12-01','2025-12-26','Campana navidena multicanal'),
     ('Reactivacion Q1 2026',          'email', 1500,'finalizada','2026-01-15','2026-02-28','Reactivacion de clientes inactivos'),
     ('Vuelta a Clases Costa 2026',    'mixto', 2800,'finalizada','2026-04-01','2026-05-05','Push escolar Costa 2026'),
     ('Dia de la Madre 2026',          'redes', 2000,'finalizada','2026-04-28','2026-05-12','Social ads Dia de la Madre 2026'),
     ('Mitad de Ano 2026',             'web',   1600,'activa',    '2026-07-01','2026-07-31','Campana on-site julio'),
     ('Preparacion Black Friday 2026', 'mixto', 4000,'borrador',  '2026-11-01','2026-11-30','Planificacion BF 2026');

    INSERT INTO campana (nombre, descripcion, canal, presupuesto, estado, fecha_inicio, fecha_fin, fecha_creacion)
    SELECT nombre, '[SEED-BC] '||descr, canal, presupuesto, estado, ini, fin,
           (ini::timestamptz - interval '5 days')
    FROM seed_campana_def;

    -- ================= BANNERS =================
    CREATE TEMP TABLE seed_banner_def (
        ord int, titulo text, imagen_url text, url_destino text, posicion text,
        orden int, ini timestamptz, fin timestamptz, activo boolean, campana_nombre text
    ) ON COMMIT DROP;
    INSERT INTO seed_banner_def (ord,titulo,imagen_url,url_destino,posicion,orden,ini,fin,activo,campana_nombre) VALUES
     ( 1,'Bienvenido a RetailMind',        '/assets/banners/lanzamiento.jpg','/shop','home_principal',   1,'2025-01-05 00:00:00-05','2025-02-05 23:59:59-05',false,'Lanzamiento tienda online 2025'),
     ( 2,'Regalos de San Valentin',        '/assets/banners/valentin25.jpg', '/shop?promo=amor','home_principal',1,'2025-02-05 00:00:00-05','2025-02-16 23:59:59-05',false,'Email San Valentin 2025'),
     ( 3,'Vuelta a Clases Costa',          '/assets/banners/escolar25.jpg',  '/shop?promo=escolar','home_principal',1,'2025-04-01 00:00:00-05','2025-05-05 23:59:59-05',false,'Vuelta a Clases Costa 2025'),
     ( 4,'Feliz Dia Mama',                 '/assets/banners/madre25.jpg',    '/shop?promo=mama','home_secundario',2,'2025-04-28 00:00:00-05','2025-05-12 23:59:59-05',false,'Dia de la Madre 2025'),
     ( 5,'Ofertas de Invierno',            '/assets/banners/invierno25.jpg', '/shop?promo=invierno','home_principal',1,'2025-07-05 00:00:00-05','2025-07-25 23:59:59-05',false,'Ofertas de Invierno 2025'),
     ( 6,'Regreso a Clases Sierra',        '/assets/banners/sierra25.jpg',   '/shop?promo=sierra','sidebar',        3,'2025-09-01 00:00:00-05','2025-09-20 23:59:59-05',false,'Regreso a Clases Sierra 2025'),
     ( 7,'Black Friday hasta -22%',        '/assets/banners/bf25.jpg',       '/shop?promo=blackfriday','home_principal',1,'2025-11-24 00:00:00-05','2025-11-30 23:59:59-05',false,'Black Friday 2025'),
     ( 8,'Preventa Black Friday',          '/assets/banners/prebf25.jpg',    '/shop?promo=prebf','home_secundario',2,'2025-11-10 00:00:00-05','2025-11-23 23:59:59-05',false,'Black Friday 2025'),
     ( 9,'Navidad RetailMind',             '/assets/banners/navidad25.jpg',  '/shop?promo=navidad','home_principal',1,'2025-12-05 00:00:00-05','2025-12-24 23:59:59-05',false,'Navidad 2025'),
     (10,'Ofertas Fin de Ano',             '/assets/banners/findeano25.jpg', '/shop?promo=findeano','home_principal',1,'2025-12-26 00:00:00-05','2025-12-31 23:59:59-05',false,NULL),
     (11,'Reyes Magos 2026',               '/assets/banners/reyes26.jpg',    '/shop?promo=reyes','home_secundario',2,'2026-01-02 00:00:00-05','2026-01-12 23:59:59-05',false,'Reactivacion Q1 2026'),
     (12,'San Valentin 2026',              '/assets/banners/valentin26.jpg', '/shop?promo=amor','home_principal',1,'2026-02-05 00:00:00-05','2026-02-16 23:59:59-05',false,'Reactivacion Q1 2026'),
     (13,'Vuelta a Clases Costa 2026',     '/assets/banners/escolar26.jpg',  '/shop?promo=escolar','home_principal',1,'2026-04-01 00:00:00-05','2026-05-05 23:59:59-05',false,'Vuelta a Clases Costa 2026'),
     (14,'Feliz Dia Mama 2026',            '/assets/banners/madre26.jpg',    '/shop?promo=mama','home_principal',1,'2026-05-01 00:00:00-05','2026-05-12 23:59:59-05',false,'Dia de la Madre 2026'),
     (15,'Ofertas de Temporada',           '/assets/banners/temporada26.jpg','/shop?promo=temporada','sidebar',   3,'2026-06-10 00:00:00-05','2026-06-30 23:59:59-05',false,NULL),
     (16,'Especial Julio - Mitad de Ano',  '/assets/banners/julio26.jpg',    '/shop?promo=julio','home_principal',1,'2026-07-01 00:00:00-05','2026-07-31 23:59:59-05',true, 'Mitad de Ano 2026');

    INSERT INTO banner (campana_id, titulo, imagen_url, url_destino, posicion, orden,
                        fecha_inicio, fecha_fin, activo, fecha_creacion)
    SELECT c.id, b.titulo, b.imagen_url, b.url_destino, b.posicion, b.orden,
           b.ini, b.fin, b.activo, b.ini
    FROM seed_banner_def b
    LEFT JOIN campana c ON c.nombre = b.campana_nombre
                       AND c.id > (v_thr->>'campana')::bigint    -- solo campanas de este bloque
    ORDER BY b.ord;

    -- ================= MARCA / REVERSION =================
    INSERT INTO configuracion_tienda (clave, valor, tipo_dato, descripcion, fecha_creacion)
    VALUES ('seed_bc_65_marketing', v_thr::text, 'json',
            'Bloque C/65: promociones (productos = top ventas de su ventana) + campanas + banners. Reversion: borrar id>umbral.',
            now());

    SELECT count(*) INTO v_nprom FROM promocion WHERE id > (v_thr->>'promocion')::bigint;
    SELECT count(*) INTO v_ncamp FROM campana   WHERE id > (v_thr->>'campana')::bigint;
    SELECT count(*) INTO v_nban  FROM banner     WHERE id > (v_thr->>'banner')::bigint;
    RAISE NOTICE 'Bloque C / 65 OK. Promociones: %, promocion_producto: %, campanas: %, banners: %.',
        v_nprom, v_tot_pp, v_ncamp, v_nban;
END $$;

COMMIT;
