-- ============================================================================
-- 83_marketing_vigente.sql
-- OBJETIVO TACTICO OTD-GER-06 (acciones de marketing VIGENTES) — tambien
-- cierra el hallazgo M10. Seccion 8 de
-- docs/tactico/AUDITORIA_TRANSVERSAL_SEED.md.
--
-- ANTES: el Bloque C repartio las vigencias por los 18 meses HISTORICOS y dejo
-- el presente casi vacio: 1 promocion + 2 campanas + 2 banners vigentes hoy.
-- El panel de acciones vigentes muestra practicamente lo mismo que antes del seed.
--
-- Que hace: crea el marketing VIVO de hoy (2026-07-25) — 5 promociones,
-- 4 campanas, 6 banners y 4 cupones con fecha_inicio pasada/actual y fecha_fin
-- futura. NO toca ni una fila del marketing historico ya sembrado.
--
-- Coherencia de negocio (distribuidora mayorista B2B en Quevedo):
--   * El calendario es el real de julio en la Costa ecuatoriana: mitad de ano
--     mayorista, liquidacion de media temporada y arranque del "regreso a
--     clases" de la Sierra (que empieza en septiembre).
--   * El PORCENTAJE de cada promocion se fijo POR DEBAJO del margen real de su
--     categoria (script 67): Electronica 9,29 % -> 4 %; Abarrotes 15,45 % y
--     Hogar 21,65 % -> 7 %; Deportes 24,05 % y Accesorios 33,94 % -> 10 %;
--     Calzado 27,32 % y Ropa 31,43 % -> 15 %; Belleza 35,50 % -> 16 %.
--     Ninguna deja margen negativo si un evaluador crea un pedido nuevo.
--   * Cada promocion lleva sus productos (promocion_producto) = los mas
--     vendidos de su categoria, que es como el Bloque C eligio los suyos.
--
-- ALCANCE DECLARADO: estas promociones son de VIGENCIA FUTURA. NO rebajan
-- ninguna venta pasada (eso lo hicieron los scripts 72-73 sobre las promos
-- historicas); solo figuran como vigentes y aplicarian, por el motor real
-- (marketing/DescuentosService), a los pedidos que se creen desde hoy.
--
-- Marca 'seed_op_83_marketing_vigente'. Idempotente y transaccional.
-- Ejecutar como postgres sobre la BD retailmind.
-- ============================================================================
\set ON_ERROR_STOP on

BEGIN;

DO $$
DECLARE
    v_promo_id bigint;
    v_id       bigint;
    v_n_promo  int := 0;
    v_n_pp     int := 0;
    v_n_camp   int := 0;
    v_n_ban    int := 0;
    v_n_cup    int := 0;
    v_rec      record;
BEGIN
    IF EXISTS (SELECT 1 FROM configuracion_tienda WHERE clave = 'seed_op_83_marketing_vigente') THEN
        RAISE NOTICE 'Objetivo 4 (marketing vigente) ya sembrado; se omite.';
        RETURN;
    END IF;

    -- ── 1. Campanas vigentes ────────────────────────────────────────────────
    CREATE TEMP TABLE op83_camp (n int, id bigint) ON COMMIT DROP;

    INSERT INTO campana (nombre, descripcion, canal, presupuesto, estado,
                         fecha_inicio, fecha_fin, fecha_creacion)
    VALUES ('Julio Mayorista 2026',
            'Impulso de venta mayorista de mitad de ano: abarrotes y hogar con precio de volumen.',
            'mixto', 3500.00, 'activa', date '2026-07-06', date '2026-08-05',
            timestamptz '2026-07-02 09:15:00-05')
    RETURNING id INTO v_id;  INSERT INTO op83_camp VALUES (1, v_id);

    INSERT INTO campana (nombre, descripcion, canal, presupuesto, estado,
                         fecha_inicio, fecha_fin, fecha_creacion)
    VALUES ('Liquidacion media temporada 2026',
            'Rotacion de saldos de ropa y calzado antes del cierre del trimestre.',
            'web', 1900.00, 'activa', date '2026-07-13', date '2026-08-16',
            timestamptz '2026-07-09 11:40:00-05')
    RETURNING id INTO v_id;  INSERT INTO op83_camp VALUES (2, v_id);

    INSERT INTO campana (nombre, descripcion, canal, presupuesto, estado,
                         fecha_inicio, fecha_fin, fecha_creacion)
    VALUES ('Regreso a clases Sierra 2026',
            'Campana anticipada para el ciclo lectivo de la Sierra: deportes, accesorios y hogar.',
            'redes', 2800.00, 'activa', date '2026-07-20', date '2026-09-15',
            timestamptz '2026-07-15 08:50:00-05')
    RETURNING id INTO v_id;  INSERT INTO op83_camp VALUES (3, v_id);

    INSERT INTO campana (nombre, descripcion, canal, presupuesto, estado,
                         fecha_inicio, fecha_fin, fecha_creacion)
    VALUES ('Fidelizacion mayorista Q3 2026',
            'Correo segmentado a clientes con compra recurrente: cupon de envio y descuento por volumen.',
            'email', 1200.00, 'activa', date '2026-07-01', date '2026-09-30',
            timestamptz '2026-06-26 16:05:00-05')
    RETURNING id INTO v_id;  INSERT INTO op83_camp VALUES (4, v_id);

    SELECT count(*) INTO v_n_camp FROM op83_camp;

    -- ── 2. Promociones vigentes + sus productos ─────────────────────────────
    -- (nombre, tipo, valor, prioridad, acumulable, inicio, fin, categorias, n_prod)
    CREATE TEMP TABLE op83_promo (
        nombre text, tipo text, valor numeric, prioridad smallint, acumulable boolean,
        inicio timestamptz, fin timestamptz, cats bigint[], n_prod int, descripcion text
    ) ON COMMIT DROP;

    INSERT INTO op83_promo VALUES
      ('Julio Mayorista - Abarrotes y Hogar', 'porcentaje', 7.00, 12, false,
       timestamptz '2026-07-06 00:00:00-05', timestamptz '2026-08-05 23:59:00-05',
       ARRAY[5,11]::bigint[], 12,
       'Descuento de volumen sobre la canasta de abarrotes y hogar durante Julio Mayorista.'),
      ('Liquidacion media temporada - Ropa y Calzado', 'porcentaje', 15.00, 20, false,
       timestamptz '2026-07-13 00:00:00-05', timestamptz '2026-08-16 23:59:00-05',
       ARRAY[12,4]::bigint[], 12,
       'Saldos de ropa y calzado con el mayor descuento del trimestre.'),
      ('Electronica seleccionada', 'porcentaje', 4.00, 15, false,
       timestamptz '2026-07-20 00:00:00-05', timestamptz '2026-09-01 23:59:00-05',
       ARRAY[10]::bigint[], 10,
       'Descuento acotado en electronica: la categoria de menor margen del catalogo.'),
      ('Belleza mayorista', 'porcentaje', 16.00, 8, true,
       timestamptz '2026-07-01 00:00:00-05', timestamptz '2026-08-31 23:59:00-05',
       ARRAY[7]::bigint[], 10,
       'Promocion acumulable de belleza para pedidos de reventa.'),
      ('Regreso a clases Sierra - Deportes y Accesorios', 'porcentaje', 10.00, 10, false,
       timestamptz '2026-07-22 00:00:00-05', timestamptz '2026-09-15 23:59:00-05',
       ARRAY[9,2]::bigint[], 12,
       'Anticipo del ciclo lectivo de la Sierra en deportes y accesorios.');

    FOR v_rec IN SELECT * FROM op83_promo LOOP
        INSERT INTO promocion (nombre, descripcion, tipo_descuento, valor, fecha_inicio,
                               fecha_fin, prioridad, acumulable, activo, fecha_creacion)
        VALUES (v_rec.nombre, v_rec.descripcion, v_rec.tipo, v_rec.valor, v_rec.inicio,
                v_rec.fin, v_rec.prioridad, v_rec.acumulable, true,
                v_rec.inicio - interval '3 day')
        RETURNING id INTO v_promo_id;
        v_n_promo := v_n_promo + 1;

        -- los mas vendidos de la(s) categoria(s) de la promocion
        INSERT INTO promocion_producto (promocion_id, producto_id, fecha_creacion)
        SELECT v_promo_id, t.producto_id, v_rec.inicio - interval '3 day'
        FROM (SELECT pv.producto_id, sum(pd.cantidad) uds
              FROM pedido_detalle pd
              JOIN producto_variante pv ON pv.id = pd.producto_variante_id
              JOIN producto p ON p.id = pv.producto_id AND p.activo
              JOIN producto_categoria pc ON pc.producto_id = pv.producto_id
              WHERE pc.categoria_id = ANY (v_rec.cats)
              GROUP BY 1 ORDER BY 2 DESC, 1 LIMIT v_rec.n_prod) t;
        GET DIAGNOSTICS v_id = ROW_COUNT;
        v_n_pp := v_n_pp + v_id;
    END LOOP;

    -- ── 3. Banners vigentes (colgados de las campanas) ──────────────────────
    INSERT INTO banner (campana_id, titulo, imagen_url, url_destino, posicion, orden,
                        fecha_inicio, fecha_fin, activo, fecha_creacion)
    SELECT c.id, b.titulo, b.img, b.url, b.pos, b.orden, b.ini, b.fin, true, b.ini - interval '2 day'
    FROM (VALUES
      (1, 'Julio Mayorista: hasta 7% en abarrotes y hogar',
          '/assets/banners/julio-mayorista-2026.jpg', '/shop?promo=julio-mayorista',
          'home_principal', 1, timestamptz '2026-07-06 00:00:00-05', timestamptz '2026-08-05 23:59:00-05'),
      (2, 'Liquidacion de media temporada: 15% en ropa y calzado',
          '/assets/banners/liquidacion-media-temporada-2026.jpg', '/shop?promo=liquidacion',
          'home_principal', 2, timestamptz '2026-07-13 00:00:00-05', timestamptz '2026-08-16 23:59:00-05'),
      (3, 'Regreso a clases Sierra: prepara tu stock',
          '/assets/banners/clases-sierra-2026.jpg', '/shop?promo=clases-sierra',
          'home_principal', 3, timestamptz '2026-07-20 00:00:00-05', timestamptz '2026-09-15 23:59:00-05'),
      (4, 'Envio gratis en pedidos mayoristas desde $200',
          '/assets/banners/envio-gratis-q3.jpg', '/shop/carrito',
          'home_secundario', 1, timestamptz '2026-07-01 00:00:00-05', timestamptz '2026-09-30 23:59:00-05'),
      (1, 'Belleza mayorista: 16% acumulable',
          '/assets/banners/belleza-mayorista-2026.jpg', '/shop?categoria=belleza',
          'home_secundario', 2, timestamptz '2026-07-01 00:00:00-05', timestamptz '2026-08-31 23:59:00-05'),
      (3, 'Cupon CLASES2026: 8% adicional',
          '/assets/banners/cupon-clases-2026.jpg', '/shop/checkout',
          'sidebar', 1, timestamptz '2026-07-20 00:00:00-05', timestamptz '2026-09-15 23:59:00-05')
    ) AS b(camp, titulo, img, url, pos, orden, ini, fin)
    JOIN op83_camp c ON c.n = b.camp;
    GET DIAGNOSTICS v_n_ban = ROW_COUNT;

    -- ── 4. Cupones vigentes ─────────────────────────────────────────────────
    INSERT INTO cupon (codigo, descripcion, tipo_descuento, valor, monto_minimo_pedido,
                       usos_maximos, usos_por_cliente, usos_actuales, fecha_inicio,
                       fecha_fin, activo, fecha_creacion)
    VALUES
      ('JULIOMAYOR12', 'Julio Mayorista: 12% de descuento en pedidos desde $300',
       'porcentaje', 12.00, 300.00, 200, 2, 0,
       timestamptz '2026-07-06 00:00:00-05', timestamptz '2026-08-05 23:59:00-05', true,
       timestamptz '2026-07-02 09:20:00-05'),
      ('CLASES2026', 'Regreso a clases Sierra: 8% de descuento desde $150',
       'porcentaje', 8.00, 150.00, 300, 1, 0,
       timestamptz '2026-07-20 00:00:00-05', timestamptz '2026-09-15 23:59:00-05', true,
       timestamptz '2026-07-15 08:55:00-05'),
      ('ENVIOQ3', 'Envio gratis en pedidos mayoristas desde $200 (Q3 2026)',
       'envio_gratis', 0.00, 200.00, 400, 3, 0,
       timestamptz '2026-07-01 00:00:00-05', timestamptz '2026-09-30 23:59:00-05', true,
       timestamptz '2026-06-26 16:10:00-05'),
      ('MAYOR25USD', 'Fidelizacion mayorista: $25 de descuento en pedidos desde $400',
       'monto_fijo', 25.00, 400.00, 150, 1, 0,
       timestamptz '2026-07-13 00:00:00-05', timestamptz '2026-08-31 23:59:00-05', true,
       timestamptz '2026-07-09 11:45:00-05');
    GET DIAGNOSTICS v_n_cup = ROW_COUNT;

    INSERT INTO configuracion_tienda (clave, valor, tipo_dato, descripcion)
    VALUES ('seed_op_83_marketing_vigente',
            jsonb_build_object('fecha', now(), 'promocion', v_n_promo,
                               'promocion_producto', v_n_pp, 'campana', v_n_camp,
                               'banner', v_n_ban, 'cupon', v_n_cup)::text,
            'json', 'OTD-GER-06 / M10 (marketing vigente hoy) — script 83')
    ON CONFLICT (clave) DO UPDATE SET valor = EXCLUDED.valor;

    RAISE NOTICE 'Promociones: % (% productos), campanas: %, banners: %, cupones: %',
                 v_n_promo, v_n_pp, v_n_camp, v_n_ban, v_n_cup;
END $$;

COMMIT;

\echo '--- OTD-GER-06: acciones de marketing VIGENTES hoy ---'
SELECT 'promocion' entidad, count(*) vigentes FROM promocion
 WHERE activo AND fecha_inicio <= now() AND (fecha_fin IS NULL OR fecha_fin >= now())
UNION ALL SELECT 'campana', count(*) FROM campana
 WHERE fecha_inicio <= current_date AND fecha_fin >= current_date
UNION ALL SELECT 'banner', count(*) FROM banner
 WHERE activo AND (fecha_inicio IS NULL OR fecha_inicio <= now())
   AND (fecha_fin IS NULL OR fecha_fin >= now())
UNION ALL SELECT 'cupon', count(*) FROM cupon
 WHERE activo AND fecha_inicio <= now() AND (fecha_fin IS NULL OR fecha_fin >= now());

\echo '--- Promociones vigentes con su descuento y su categoria ---'
SELECT p.nombre, p.tipo_descuento, p.valor, p.prioridad, p.acumulable,
       p.fecha_inicio::date, p.fecha_fin::date,
       (SELECT count(*) FROM promocion_producto pp WHERE pp.promocion_id = p.id) productos
FROM promocion p
WHERE p.activo AND p.fecha_inicio <= now() AND (p.fecha_fin IS NULL OR p.fecha_fin >= now())
ORDER BY p.prioridad DESC;
