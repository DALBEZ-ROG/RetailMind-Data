-- ============================================================================
-- 98_fase2_carga.sql — RetailMind · FASE 2 de la carga masiva:
--                      300.000 pedidos, un anio a densidad real (2026-08-11)
--
--   docker compose exec -T postgres psql -U postgres -d retailmind \
--       -v ON_ERROR_STOP=1 -f - < retailmind/sql/postgres/98_fase2_carga.sql
--
--   Reanudar tras un corte:   ... -v reanudar=1 ...
--   Reversion:                99_revert_fase2.sql   (ensayo con -v ensayo=1)
--
-- OJO: se ejecuta con `-f -` y NO con `<` a secas. El lote va dentro de un
-- PROCEDIMIENTO que hace COMMIT, y un COMMIT dentro de un procedimiento solo
-- es legal si el CALL no viene envuelto en una transaccion implicita.
--
-- ---------------------------------------------------------------------------
-- LA VENTANA: 2026-09-01 .. 2027-08-31, Y POR QUE NO 2024
-- ---------------------------------------------------------------------------
-- El enunciado ofrecia 2024 «hacia atras». NO SE PUEDE, y el motivo no esta en
-- PostgreSQL sino en el almacen: `dim_fecha` es un calendario GENERADO con
-- rango fijo 2025-01-01 .. 2026-12-31, y `fact_prevision_demanda._malla()`
-- LEVANTA UNA EXCEPCION si el calendario no cubre el periodo del hecho sin
-- huecos. Un pedido en 2024 aborta la tarea de prevision y con ella el DAG.
-- Verificado en el codigo antes de escribir una sola fila.
--
-- Se elige 2026-09-01 .. 2027-08-31 por cuatro razones, en este orden:
--
--   1. ES LA FECHA QUE LA FASE 0 DEJO ESCRITA. `carga_fase_parametro` dice
--      `arranque_ventas_futuras = 2026-09-01`, con la nota «Primer pedido de
--      la Fase 2». No se inventa una ventana: se honra la que ya estaba.
--
--   2. ES LA UNICA EN LA QUE EL STOCK EXISTE. Las 10.000 posiciones de la
--      Fase 0 tienen su apertura el 2026-08-09; ANTES de esa fecha su saldo es
--      CERO. Cualquier ventana anterior obliga al truco de la Fase 1 —comprar
--      y vender en el mismo instante— y deja las posiciones sin cobertura. Es
--      exactamente el problema que T5 pide resolver, y solo se resuelve
--      cargando DESPUES de la apertura.
--
--   3. TODO MOVIMIENTO DE ESTA FASE ES POSTERIOR AL ULTIMO EXISTENTE
--      (2026-08-10 17:19:45). La cadena del kardex se ANEXA: no hay
--      interpolacion, no hay que consultar el saldo previo instante a
--      instante, y el saldo de partida de cada posicion es una constante que
--      ya esta calculada y verificada — `inventario.stock_actual`.
--
--   4. DEJA 2027-09 .. 2034-12 CONTIGUO para la Fase 3: 7 anios y 4 meses que
--      a esta densidad (300.000/anio) son ~2,5 millones de pedidos. Con los
--      314.083 que quedan cargados al terminar esta fase, el plan de 3.000.000
--      cierra sin solapar ninguna ventana.
--
-- CONSECUENCIA DECLARADA: `dim_fecha` hay que extenderla a la decada declarada
-- (`ventana_temporal = 2025-01-01 .. 2034-12-31`). No es un capricho de esta
-- fase: la Fase 3 no puede correr sin ello, y vale mil veces mas descubrirlo
-- con 300.000 pedidos que con 2,7 millones.
--
-- ---------------------------------------------------------------------------
-- EL COLCHON DE STOCK (T5): SALE GRATIS, Y NO ES CASUALIDAD
-- ---------------------------------------------------------------------------
-- La Fase 1 dejo las posiciones a cero porque cargaba ANTES de la apertura de
-- la Fase 0: el cierre a cero no era una eleccion de politica de inventario,
-- era la unica forma de que el `stock_anterior = 0` de esa apertura siguiera
-- siendo cierto.
--
-- Aqui la apertura esta AGUAS ARRIBA. No hay ningun movimiento posterior cuyo
-- saldo dependa de que esta fase devuelva la posicion a su sitio. Y aun asi el
-- metodo SIGUE siendo neto cero por posicion, por una razon distinta y mejor:
--
--     saldo al empezar = S (lo que dejo la Fase 0)
--     entrada del bimestre = demanda del bimestre en esa posicion
--     saldo al acabar el bimestre = S
--
-- El colchon ES la apertura de la Fase 0 —426.722 unidades, unos 60 dias de
-- cobertura por diseno— y el neto cero lo CONSERVA intacto los doce meses en
-- vez de consumirlo. Coste en unidades adicionales: CERO. Coste en compras
-- adicionales: CERO. Y el saldo minimo de cualquier posicion en cualquier
-- instante es S, nunca menos, porque la entrada del bimestre se coloca antes
-- de la primera salida del bimestre.
--
-- No rompe nada de la Fase 0: al no escribirse `inventario.stock_actual`, la
-- apertura sigue cuadrando y la reversion sigue siendo un DELETE a secas.
--
-- ---------------------------------------------------------------------------
-- LOS DOS TRAMOS RESERVADOS
-- ---------------------------------------------------------------------------
-- (a) IDs: [1.100.000.000, 1.200.000.000).
--     Fase 0 -> 9xx.xxx.xxx · Fase 1 -> 1.0xx.xxx.xxx · Fase 2 -> 1.1xx.xxx.xxx
--     El techo NO es el bigint de PostgreSQL: es el **UInt32** del almacen
--     (4.294.967.295), que reciben `pedido_id`, `cliente_id`,
--     `producto_variante_id`, `documento_id` (=`pago.id`), `contraparte_id`,
--     `orden_compra_id` y `envio_id`. La tabla mas voluminosa de esta fase
--     —`historial_estado_pedido`, ~2,0 millones— cabe de sobra en los 100
--     millones del tramo.
--
-- (b) NUMEROS DE DOCUMENTO — la trampa que encontro la Fase 1. El tramo de
--     claves PRIMARIAS **no reserva** el de las claves UNICAS DE NEGOCIO:
--     `orden_compra.numero`, `recepcion_mercancia.numero` y
--     `factura_compra.numero_factura` son UNIQUE con formato
--     `XX-YYYYMMDD-NNNNNN`, y el seed ya ocupa ese espacio desde 100026. En la
--     Fase 1 la recepcion 39 choco de frente.
--
--     Esta fase reserva la banda **92xxxxx** (siete digitos, Fase 0 uso 90,
--     Fase 1 uso 91) y ademas numera **por DIA**, no globalmente: el numero ya
--     lleva la fecha, asi que una secuencia diaria basta para ser unica y no
--     se agota nunca — que es justo lo que la Fase 3 necesita, porque una
--     secuencia global de siete digitos se queda corta a diez anios.
--     Y no se confia en el razonamiento: hay un GUARDIA que compara los
--     numeros generados contra los ya existentes y ABORTA si coincide uno.
-- ---------------------------------------------------------------------------
\set ON_ERROR_STOP on
-- MEDIR es la mitad del objetivo de esta fase: el cronometro por sentencia va
-- encendido y su salida es parte del entregable, no ruido.
\timing on

\if :{?reanudar}
\else
  \set reanudar 0
\endif
SELECT set_config('retailmind.reanudar', :'reanudar', false);

-- Memoria de trabajo SOLO PARA ESTA SESION (no es ALTER SYSTEM, muere con el
-- psql). El plan ordena 767.000 lineas para el `DISTINCT ON` de
-- `uq_pedido_detalle`; con los 4 MB por defecto esa ordenacion se vuelca a
-- disco. No se toca nada global: el resto de la base sigue con su valor.
SET work_mem = '256MB';
SET maintenance_work_mem = '512MB';
SET synchronous_commit = off;   -- carga masiva; el respaldo T0 es la red

\echo ''
\echo '=== FASE 2 . 300.000 PEDIDOS . 2026-09-01 a 2027-08-31 ====================='

-- ════════════════════════════════════════════════════════════════════════════
-- 0. AZAR DETERMINISTA · GUARDIA DEL TRAMO · PARAMETROS
-- ════════════════════════════════════════════════════════════════════════════
CREATE OR REPLACE FUNCTION pg_temp.u(k text, i bigint) RETURNS numeric
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS
$$ SELECT ((('x' || substr(md5(k || i::text), 1, 7))::bit(28)::int) % 1000000)::numeric
          / 1000000.0 $$;

-- Cronometro: cada paso se mide, porque MEDIR es la mitad del objetivo.
CREATE TEMP TABLE f2_medicion(
    orden serial, paso text, lote int, inicio timestamptz, fin timestamptz, filas bigint);

CREATE OR REPLACE PROCEDURE pg_temp.medir(p_paso text, p_lote int, p_ini timestamptz, p_filas bigint)
LANGUAGE sql AS
$$ INSERT INTO f2_medicion(paso, lote, inicio, fin, filas)
   VALUES (p_paso, p_lote, p_ini, clock_timestamp(), p_filas) $$;

DO $guardia$
DECLARE t text; n bigint; sucias text := '';
        v_reanudar int := current_setting('retailmind.reanudar', true)::int;
BEGIN
    FOREACH t IN ARRAY ARRAY['pedido','pedido_detalle','historial_estado_pedido',
                             'factura_venta','factura_venta_detalle','pago','transaccion_pago',
                             'movimiento_inventario','orden_compra','orden_compra_detalle',
                             'recepcion_mercancia','recepcion_detalle','factura_compra',
                             'factura_compra_detalle','cuenta_por_pagar','pago_proveedor']
    LOOP
        EXECUTE format('SELECT count(*) FROM public.%I WHERE id >= 1100000000', t) INTO n;
        IF n > 0 THEN sucias := sucias || format('  %s: %s filas%s', t, n, E'\n'); END IF;
    END LOOP;
    IF sucias <> '' AND v_reanudar = 0 THEN
        RAISE EXCEPTION 'El tramo de la Fase 2 YA tiene filas. Revierte con 99_revert_fase2.sql, '
                        'o reanuda con -v reanudar=1 si la carga anterior se corto a medias:%',
                        E'\n' || sucias;
    ELSIF sucias <> '' THEN
        RAISE NOTICE 'REANUDANDO sobre una carga incompleta:%', E'\n' || sucias;
    ELSE
        RAISE NOTICE 'Guardia OK: el tramo [1.100.000.000, 1.200.000.000) esta vacio.';
    END IF;
END
$guardia$;

-- La ventana de esta fase tiene que ser POSTERIOR a todo movimiento existente,
-- o el metodo de anexado deja de ser valido y hay que volver al de la Fase 1.
DO $ventana$
DECLARE v_ult timestamptz;
BEGIN
    SELECT max(fecha_creacion) INTO v_ult FROM movimiento_inventario WHERE id < 1100000000;
    IF v_ult >= timestamptz '2026-09-01 00:00:00-05' THEN
        RAISE EXCEPTION 'ABORTA: hay kardex existente en % , dentro o despues de la ventana. '
                        'El anexado supone que la ventana esta VACIA.', v_ult;
    END IF;
    RAISE NOTICE 'Ventana libre: el ultimo movimiento existente es de %.', v_ult;
END
$ventana$;

-- Pesos de demanda: se LEEN de la tabla, como en la Fase 1.
CREATE TEMP TABLE f2_peso AS
SELECT m[1] AS banda, m[2]::numeric AS pct
FROM carga_fase_parametro p,
     LATERAL regexp_matches(p.valor, '([A-Za-z0-9]+)=([0-9.]+)%', 'g') AS m
WHERE p.fase = 'fase0' AND p.clave = 'pesos_demanda_lineas';

DO $chk$
DECLARE v_sum numeric; v_faltan text;
BEGIN
    SELECT sum(pct) INTO v_sum FROM f2_peso;
    SELECT string_agg(b, ', ') INTO v_faltan FROM unnest(ARRAY['L1','L2','L3','L4','historico']) b
    WHERE b NOT IN (SELECT banda FROM f2_peso);
    IF v_faltan IS NOT NULL THEN RAISE EXCEPTION 'Faltan bandas: %', v_faltan; END IF;
    IF abs(v_sum - 100) > 0.01 THEN RAISE EXCEPTION 'Los pesos suman %.', v_sum; END IF;
    RAISE NOTICE 'Pesos leidos de carga_fase_parametro: suman %.', v_sum;
END
$chk$;

CREATE TEMP TABLE f2_banda AS
SELECT banda, (sum(pct) OVER (ORDER BY banda) - pct)/100.0 AS lo,
              sum(pct) OVER (ORDER BY banda)/100.0        AS hi
FROM f2_peso;

-- ════════════════════════════════════════════════════════════════════════════
-- 1. PERFILES — derivados de los 4.083 pedidos ORIGINALES, como en la Fase 1
-- ════════════════════════════════════════════════════════════════════════════
-- Se toman de los pedidos anteriores a la carga masiva (id < 900.000.000) y no
-- de todo lo que hay: si cada fase derivara sus perfiles de la anterior, la
-- forma iria derivando carga tras carga y a la tercera nadie sabria de donde
-- salio. La referencia es siempre la misma serie original.
-- EL FILTRO DE ANIO NO ES OPCIONAL. Los 4.083 pedidos originales abarcan
-- 2025-01 .. 2026-07, o sea DIECINUEVE meses: agrupar por numero de mes sin
-- acotar el anio suma enero de 2025 con enero de 2026 y deja los meses 1-7 con
-- el doble de peso que los meses 8-12. Medido con el fallo puesto: enero-julio
-- se llevaban el 72 % del anio en vez del ~58 % que les toca. No rompe ninguna
-- verificacion de integridad —por eso es peligroso— pero falsea la unica cosa
-- que este perfil existe para reproducir.
CREATE TEMP TABLE f2_mes AS
SELECT extract(month FROM fecha_pedido AT TIME ZONE 'America/Guayaquil')::int AS m,
       count(*)::numeric AS w
FROM pedido
WHERE id < 900000000
  AND fecha_pedido >= timestamptz '2025-01-01 00:00:00-05'
  AND fecha_pedido <  timestamptz '2026-01-01 00:00:00-05'
GROUP BY 1;

CREATE TEMP TABLE f2_dia AS
WITH d AS (SELECT generate_series(date '2026-09-01', date '2027-08-31', interval '1 day')::date AS dia),
p AS (SELECT d.dia, m.w / count(*) OVER (PARTITION BY m.m) AS peso
      FROM d JOIN f2_mes m ON m.m = extract(month FROM d.dia)::int)
SELECT dia, peso,
       (sum(peso) OVER (ORDER BY dia) - peso)/sum(peso) OVER () AS lo,
        sum(peso) OVER (ORDER BY dia)        /sum(peso) OVER () AS hi
FROM p;
CREATE INDEX ON f2_dia(lo, hi);

CREATE TEMP TABLE f2_hora AS
WITH h AS (SELECT extract(hour FROM fecha_pedido AT TIME ZONE 'America/Guayaquil')::int AS hh,
                  count(*)::numeric AS w
           FROM pedido WHERE id < 900000000 GROUP BY 1 HAVING count(*) >= 50)
SELECT hh, (sum(w) OVER (ORDER BY hh) - w)/sum(w) OVER () AS lo,
           sum(w) OVER (ORDER BY hh)      /sum(w) OVER () AS hi
FROM h;

CREATE TEMP TABLE f2_env AS
SELECT row_number() OVER (ORDER BY id) AS rn, costo_envio AS costo, count(*) OVER () AS tot
FROM pedido WHERE id < 900000000 AND costo_envio > 0;
CREATE INDEX ON f2_env(rn);

CREATE TEMP TABLE f2_canal AS
WITH c(codigo, w, ord) AS (VALUES
    ('web', 54.20::numeric, 1), ('tienda', 25.23::numeric, 2), ('telefono', 20.57::numeric, 3))
SELECT codigo, (sum(w) OVER (ORDER BY ord) - w)/sum(w) OVER () AS lo,
               sum(w) OVER (ORDER BY ord)      /sum(w) OVER () AS hi
FROM c;

CREATE TEMP TABLE f2_estado AS
WITH e(codigo, w, ord) AS (VALUES
    ('entregado',     87.99::numeric,  1), ('cancelado',      3.89::numeric,  2),
    ('no_entregado',   2.96::numeric,  3), ('devuelto',       2.65::numeric,  4),
    ('pagado',         0.51::numeric,  5), ('facturado',      0.51::numeric,  6),
    ('confirmado',     0.44::numeric,  7), ('despachado',     0.39::numeric,  8),
    ('en_preparacion', 0.37::numeric,  9), ('preparado',      0.29::numeric, 10))
SELECT e.codigo, ep.id AS estado_id,
       (sum(e.w) OVER (ORDER BY e.ord) - e.w)/sum(e.w) OVER () AS lo,
        sum(e.w) OVER (ORDER BY e.ord)       /sum(e.w) OVER () AS hi,
       (e.codigo IN ('en_preparacion','preparado','despachado','entregado',
                     'devuelto','no_entregado'))                  AS mueve_stock,
       (e.codigo IN ('facturado','en_preparacion','preparado','despachado',
                     'entregado','devuelto','no_entregado'))      AS factura,
       (e.codigo NOT IN ('cancelado','confirmado'))               AS cobra
FROM e JOIN estado_pedido ep ON ep.codigo = e.codigo;

-- Todos los clientes existen ya antes de la ventana (el ultimo alta es del
-- 2026-08-11), asi que no hace falta el recorte por fecha de la Fase 1.
CREATE TEMP TABLE f2_cli AS
SELECT c.id AS cliente_id, c.usuario_id,
       trim(c.nombre || COALESCE(' ' || c.apellido, '')) AS razon_social,
       c.numero_identificacion,
       (SELECT d.id FROM direccion d WHERE d.usuario_id = c.usuario_id
         ORDER BY d.es_predeterminada DESC, d.id LIMIT 1) AS direccion_id,
       row_number() OVER (ORDER BY c.id) AS rn, count(*) OVER () AS tot
FROM cliente c
WHERE c.fecha_creacion < timestamptz '2026-09-01 00:00:00-05';
CREATE INDEX ON f2_cli(rn);

CREATE TEMP TABLE f2_vend AS
SELECT row_number() OVER (ORDER BY u.id) AS rn, u.id, count(*) OVER () AS tot
FROM usuario u JOIN usuario_rol ur ON ur.usuario_id = u.id
JOIN rol r ON r.id = ur.rol_id WHERE r.codigo = 'VENDEDOR';
CREATE INDEX ON f2_vend(rn);

-- Posiciones vendibles, CON su saldo de partida. Aqui esta la simplificacion
-- que regala la ventana hacia adelante: el saldo previo de cada posicion es un
-- NUMERO CONSTANTE ya calculado y verificado (11.406/11.406 cuadradas), no una
-- consulta por instante contra el kardex.
CREATE TEMP TABLE f2_pos AS
SELECT inv.producto_variante_id AS variante_id, inv.bodega_id, inv.stock_actual AS saldo0,
       pv.precio, pv.costo, pv.sku, pr.nombre AS nombre_producto,
       CASE WHEN pv.id <  900000000        THEN 'historico'
            WHEN pv.id - 900000000 <= 2100 THEN 'L1'
            WHEN pv.id - 900000000 <= 3700 THEN 'L2'
            WHEN pv.id - 900000000 <= 4500 THEN 'L3'
            ELSE 'L4' END AS banda
FROM inventario inv
JOIN producto_variante pv ON pv.id = inv.producto_variante_id
JOIN producto pr          ON pr.id = pv.producto_id
WHERE pv.activo AND pr.activo AND pv.precio > 0;

CREATE TEMP TABLE f2_posn AS
SELECT p.*, row_number() OVER (PARTITION BY banda, bodega_id ORDER BY variante_id) AS rn
FROM f2_pos p WHERE banda <> 'historico';
CREATE INDEX ON f2_posn(banda, bodega_id, rn);

CREATE TEMP TABLE f2_posh AS
SELECT p.*, row_number() OVER (ORDER BY variante_id, bodega_id) AS rn
FROM f2_pos p WHERE banda = 'historico';
CREATE INDEX ON f2_posh(rn);

-- LOS TOTALES VAN APARTE, Y ESTA ES **LA** LECCION DE RENDIMIENTO DE LA FASE 2.
--
-- La Fase 1 sorteaba la variante asi:
--     WHERE n.banda = c.banda AND n.bodega_id = c.bod
--       AND n.rn = 1 + floor(u(...) * n.tot)
-- con `tot` como COLUMNA de la propia tabla de posiciones. Y ahi el indice
-- (banda, bodega_id, rn) NO SE PUEDE USAR para buscar `rn`: el valor buscado
-- depende de una columna de la fila candidata, asi que el motor tiene que
-- LEER ENTERA la particion de esa banda y bodega y evaluar la condicion fila a
-- fila. Con 25.582 lineas y 2.100 variantes en L1 eso son 52 millones de
-- comparaciones: invisible, dos segundos. Con 767.000 lineas son ~1.600
-- millones, y el paso pasa de segundos a mas de tres minutos y medio sin
-- terminar. Medido: se aborto la carga a los 3:24 con el paso todavia corriendo.
--
-- La correccion es que el numero de fila buscado se calcule ANTES, con el total
-- como ESCALAR. Entonces la condicion es `n.rn = <valor>` y el indice hace una
-- busqueda directa. Es la diferencia entre O(lineas x variantes) y O(lineas).
--
-- Vale la pena decirlo claro porque es el patron que la Fase 3 multiplica por
-- diez: un sorteo por indice solo escala si el indice se puede USAR.
CREATE TEMP TABLE f2_totn AS
SELECT banda, bodega_id, count(*) AS tot FROM f2_posn GROUP BY banda, bodega_id;
CREATE TEMP TABLE f2_toth AS SELECT count(*) AS tot FROM f2_posh;

\echo '--- 1. Perfiles: OK'

-- ════════════════════════════════════════════════════════════════════════════
-- 2. EL PLAN: 300.000 PEDIDOS
-- ════════════════════════════════════════════════════════════════════════════
CREATE TEMP TABLE f2_ped AS
WITH n AS (SELECT generate_series(1, 300000)::bigint AS i),
sorteo AS (
    SELECT n.i, d.dia, h.hh, c.codigo AS canal,
           e.codigo AS estado_codigo, e.estado_id, e.mueve_stock, e.factura, e.cobra
    FROM n
    JOIN LATERAL (SELECT dia FROM f2_dia
                   WHERE pg_temp.u('dia', n.i) >= lo AND pg_temp.u('dia', n.i) < hi LIMIT 1) d ON true
    JOIN LATERAL (SELECT hh FROM f2_hora
                   WHERE pg_temp.u('hora', n.i) >= lo AND pg_temp.u('hora', n.i) < hi LIMIT 1) h ON true
    JOIN LATERAL (SELECT codigo FROM f2_canal
                   WHERE pg_temp.u('canal', n.i) >= lo AND pg_temp.u('canal', n.i) < hi LIMIT 1) c ON true
    JOIN LATERAL (SELECT codigo, estado_id, mueve_stock, factura, cobra FROM f2_estado
                   WHERE pg_temp.u('est', n.i) >= lo AND pg_temp.u('est', n.i) < hi LIMIT 1) e ON true
)
SELECT
    1100000000 + s.i AS id, s.i, s.dia,
    ((s.dia + (s.hh || ' hours')::interval
            + (floor(pg_temp.u('min', s.i) * 60) || ' minutes')::interval
            + (floor(pg_temp.u('seg', s.i) * 60) || ' seconds')::interval
            + (s.i || ' microseconds')::interval) AT TIME ZONE 'America/Guayaquil') AS fecha_pedido,
    s.canal, s.estado_codigo, s.estado_id, s.mueve_stock, s.factura, s.cobra,
    cl.cliente_id, cl.direccion_id, cl.razon_social, cl.numero_identificacion,
    -- 7 digitos desde 2.000.000: los del seed tienen 6 y los de la Fase 1
    -- empiezan por 09, asi que no hay forma de que dos cadenas coincidan.
    'PED-' || to_char(s.dia, 'YYYYMMDD') || '-' || lpad((2000000 + s.i)::text, 7, '0') AS numero,
    CASE WHEN s.canal = 'web' THEN NULL ELSE v.id END AS vendedor_id,
    CASE WHEN s.canal = 'tienda' THEN NULL
         WHEN pg_temp.u('me', s.i) < 0.75 THEN 1::bigint ELSE 2::bigint END AS metodo_envio_id,
    CASE WHEN s.canal = 'tienda' THEN NULL
         WHEN pg_temp.u('me', s.i) < 0.75 THEN 2::bigint ELSE 1::bigint END AS transportista_id,
    CASE WHEN s.canal = 'tienda' THEN 0::numeric ELSE en.costo END AS costo_envio,
    CASE WHEN pg_temp.u('nl', s.i) < 0.2302 THEN 1
         WHEN pg_temp.u('nl', s.i) < 0.5625 THEN 2
         WHEN pg_temp.u('nl', s.i) < 0.7768 THEN 3
         WHEN pg_temp.u('nl', s.i) < 0.8870 THEN 4
         ELSE 5 END AS n_lineas,
    -- Lote de carga: 12 tramos de 25.000 pedidos.
    ((s.i - 1) / 25000)::int + 1 AS lote
FROM sorteo s
JOIN LATERAL (SELECT cliente_id, direccion_id, razon_social, numero_identificacion
              FROM f2_cli WHERE rn = 1 + floor(pg_temp.u('cli', s.i) * (SELECT tot FROM f2_cli LIMIT 1))::bigint) cl ON true
JOIN LATERAL (SELECT id FROM f2_vend
              WHERE rn = 1 + floor(pg_temp.u('vnd', s.i) * (SELECT tot FROM f2_vend LIMIT 1))::bigint) v ON true
JOIN LATERAL (SELECT costo FROM f2_env
              WHERE rn = 1 + floor(pg_temp.u('env', s.i) * (SELECT tot FROM f2_env LIMIT 1))::bigint) en ON true;

CREATE UNIQUE INDEX ON f2_ped(id);
CREATE INDEX ON f2_ped(lote);

ALTER TABLE f2_ped
    ADD COLUMN t_pagado timestamptz, ADD COLUMN t_facturado timestamptz,
    ADD COLUMN t_preparacion timestamptz, ADD COLUMN t_preparado timestamptz,
    ADD COLUMN t_despachado timestamptz, ADD COLUMN t_entregado timestamptz,
    ADD COLUMN t_final timestamptz;

UPDATE f2_ped p SET
    t_pagado      = p.fecha_pedido + (0.5 + 5.5 * pg_temp.u('h1', p.i)) * interval '1 hour',
    t_facturado   = p.fecha_pedido + (0.7 + 8.5 * pg_temp.u('h1', p.i)) * interval '1 hour',
    t_preparacion = p.fecha_pedido + (3   + 30  * pg_temp.u('h2', p.i)) * interval '1 hour',
    t_preparado   = p.fecha_pedido + (7   + 36  * pg_temp.u('h2', p.i)) * interval '1 hour'
                                   + (1   +  7  * pg_temp.u('h3', p.i)) * interval '1 hour',
    t_despachado  = p.fecha_pedido + (10  + 40  * pg_temp.u('h2', p.i)) * interval '1 hour'
                                   + (2   + 22  * pg_temp.u('h4', p.i)) * interval '1 hour';
UPDATE f2_ped p SET t_entregado = p.t_despachado + (1 + 5 * pg_temp.u('h5', p.i)) * interval '1 day';
UPDATE f2_ped p SET t_final = CASE p.estado_codigo
    WHEN 'cancelado'      THEN p.fecha_pedido + (1 + 47 * pg_temp.u('h6', p.i)) * interval '1 hour'
    WHEN 'confirmado'     THEN p.fecha_pedido
    WHEN 'pagado'         THEN p.t_pagado
    WHEN 'facturado'      THEN p.t_facturado
    WHEN 'en_preparacion' THEN p.t_preparacion
    WHEN 'preparado'      THEN p.t_preparado
    WHEN 'despachado'     THEN p.t_despachado
    WHEN 'entregado'      THEN p.t_entregado
    WHEN 'no_entregado'   THEN p.t_despachado + (2 + 8  * pg_temp.u('h7', p.i)) * interval '1 day'
    WHEN 'devuelto'       THEN p.t_entregado  + (2 + 18 * pg_temp.u('h7', p.i)) * interval '1 day'
    END;

\echo '--- 2. 300.000 pedidos sorteados: OK'

-- ════════════════════════════════════════════════════════════════════════════
-- 3. LAS LINEAS
-- ════════════════════════════════════════════════════════════════════════════
CREATE TEMP TABLE f2_lin AS
WITH ln AS (
    SELECT p.i, p.id AS pedido_id, p.lote, p.mueve_stock, p.factura, g.j, p.i * 10 + g.j AS k
    FROM f2_ped p CROSS JOIN LATERAL generate_series(1, p.n_lineas) g(j)
),
conbanda AS (
    SELECT ln.*, b.banda,
           CASE WHEN pg_temp.u('bod', ln.k) < 0.80 THEN 4::bigint ELSE 3::bigint END AS bod
    FROM ln JOIN LATERAL (SELECT banda FROM f2_banda
                          WHERE pg_temp.u('bnd', ln.k) >= lo AND pg_temp.u('bnd', ln.k) < hi LIMIT 1) b ON true
),
-- El numero de fila buscado se resuelve AQUI, con el total ya como escalar.
conrn AS (
    SELECT c.*,
           CASE WHEN c.banda = 'historico'
                THEN 1 + floor(pg_temp.u('pos', c.k) * (SELECT tot FROM f2_toth))::bigint
                ELSE 1 + floor(pg_temp.u('pos', c.k) * t.tot)::bigint
           END AS rn_obj
    FROM conbanda c
    LEFT JOIN f2_totn t ON t.banda = c.banda AND t.bodega_id = c.bod
),
conpos AS (
    SELECT c.*, pos.variante_id, pos.bodega_id, pos.precio, pos.costo, pos.sku, pos.nombre_producto
    FROM conrn c
    JOIN LATERAL (
        -- `n.rn = c.rn_obj`: igualdad contra un valor de la fila externa, que es
        -- lo unico que el indice puede aprovechar.
        SELECT variante_id, bodega_id, precio, costo, sku, nombre_producto FROM f2_posn n
        WHERE c.banda <> 'historico' AND n.banda = c.banda AND n.bodega_id = c.bod
          AND n.rn = c.rn_obj
        UNION ALL
        SELECT variante_id, bodega_id, precio, costo, sku, nombre_producto FROM f2_posh h
        WHERE c.banda = 'historico' AND h.rn = c.rn_obj
    ) pos ON true
),
-- `uq_pedido_detalle (pedido_id, producto_variante_id)`: la variante no se
-- repite en un pedido, y la restriccion no mira la bodega.
dedup AS (
    SELECT DISTINCT ON (pedido_id, variante_id) * FROM conpos
    ORDER BY pedido_id, variante_id, j
),
-- Cantidad, precio, descuento e impuesto se calculan EN LA MISMA PASADA. La
-- Fase 1 los resolvia con cuatro UPDATE encadenados sobre la tabla temporal;
-- a 25.000 lineas daba igual, a 767.000 son cuatro recorridos completos que no
-- hacen falta: cada capa solo depende de la anterior, y para eso estan los CTE.
calc AS (
    SELECT d.*,
           CASE WHEN pg_temp.u('cnt', d.k) < 0.4318 THEN 1
                WHEN pg_temp.u('cnt', d.k) < 0.7204 THEN 2
                WHEN pg_temp.u('cnt', d.k) < 0.8593 THEN 3 ELSE 4 END AS cantidad,
           -- Invariante del seed, verificado vivo en la Fase 1 (10.384/10.384).
           GREATEST(0.01, round(d.precio * (0.90 + 0.10 * pg_temp.u('pu', d.k)), 2))::numeric(12,2)
             AS precio_unitario
    FROM dedup d
),
condsc AS (
    SELECT c.*,
           (CASE WHEN pg_temp.u('dsc', c.k) < 0.012
                 THEN round(c.cantidad * c.precio_unitario * 0.10, 2)
                 ELSE 0 END)::numeric(12,2) AS monto_descuento
    FROM calc c
)
SELECT c.*,
       round((c.cantidad * c.precio_unitario - c.monto_descuento) * 0.15, 2)::numeric(12,2)
         AS monto_impuesto,
       row_number() OVER (ORDER BY c.pedido_id, c.variante_id) AS lin_seq
FROM condsc c;

CREATE UNIQUE INDEX ON f2_lin(lin_seq);
CREATE INDEX ON f2_lin(lote);

\echo '--- 3. Lineas generadas: OK'

-- ════════════════════════════════════════════════════════════════════════════
-- 4. EL PLAN DE KARDEX — anexado puro
-- ════════════════════════════════════════════════════════════════════════════
CREATE TEMP TABLE f2_sal AS
SELECT l.lin_seq, l.pedido_id, l.lote, l.variante_id, l.bodega_id, l.cantidad, l.costo,
       p.t_preparacion + (l.lin_seq || ' microseconds')::interval AS ts
FROM f2_lin l JOIN f2_ped p ON p.id = l.pedido_id
WHERE p.mueve_stock;
CREATE INDEX ON f2_sal(variante_id, bodega_id, ts);

-- Entrada por (posicion, bimestre), colocada ANTES de la primera salida del
-- bimestre. Con el saldo de partida S por delante, el saldo minimo de la
-- posicion en todo el bimestre es S — nunca menos. Ese es el colchon.
CREATE TEMP TABLE f2_ent AS
SELECT s.variante_id, s.bodega_id, sum(s.cantidad)::int AS cantidad,
       min(s.ts) - interval '1 day' AS ts, max(s.costo) AS costo
FROM f2_sal s
GROUP BY s.variante_id, s.bodega_id,
         (extract(year FROM s.ts AT TIME ZONE 'America/Guayaquil')::int * 6
          + (extract(month FROM s.ts AT TIME ZONE 'America/Guayaquil')::int - 1) / 2);

-- El bimestre se calcula UNA VEZ y viaja en la fila. La entrada de un bimestre
-- se fecha un dia antes de su primera salida, asi que puede caer en el bimestre
-- ANTERIOR — con la ventana empezando el 1 de septiembre, la primera entrada
-- cae en agosto. Derivar el lote de la fecha con una resta contra el bimestre
-- de arranque dejaria esas filas FUERA de todos los lotes, sin error y sin que
-- nada avisara: el kardex saldria incompleto y la cadena, rota.
CREATE TEMP TABLE f2_mov AS
SELECT row_number() OVER (ORDER BY ts, variante_id, bodega_id, tipo, ref_id) AS mov_seq,
       (extract(year  FROM ts AT TIME ZONE 'America/Guayaquil')::int * 6
      + (extract(month FROM ts AT TIME ZONE 'America/Guayaquil')::int - 1) / 2) AS bim,
       *
FROM (
    SELECT e.variante_id, e.bodega_id, 1 AS tipo, 1 AS factor, e.cantidad, e.ts, e.costo,
           NULL::bigint AS ref_id, NULL::int AS lote
    FROM f2_ent e
    UNION ALL
    SELECT s.variante_id, s.bodega_id, 5, -1, s.cantidad, s.ts, s.costo, s.pedido_id, s.lote
    FROM f2_sal s
) z;
CREATE INDEX ON f2_mov(variante_id, bodega_id, ts, mov_seq);

-- El encadenamiento. `saldo0` es constante por posicion porque TODA esta fase
-- va detras del ultimo movimiento existente: no hay que preguntar el saldo
-- instante a instante, que es lo que en la Fase 1 costaba un LATERAL por fila.
CREATE TEMP TABLE f2_kar AS
SELECT m.*, (m.base + m.acum - m.factor * m.cantidad)::int AS stock_anterior,
            (m.base + m.acum)::int                         AS stock_nuevo
FROM (
    SELECT m.*, po.saldo0 AS base,
           sum(m.factor * m.cantidad) OVER (PARTITION BY m.variante_id, m.bodega_id
                                            ORDER BY m.ts, m.mov_seq
                                            ROWS UNBOUNDED PRECEDING) AS acum
    FROM f2_mov m
    JOIN f2_pos po ON po.variante_id = m.variante_id AND po.bodega_id = m.bodega_id
) m;
CREATE INDEX ON f2_kar(mov_seq);
CREATE INDEX ON f2_kar(tipo, variante_id, bodega_id, ts);

-- GUARDIA DURA, antes de escribir. La cadena se comprueba FUSIONADA con lo que
-- ya existe: comprobar solo las filas nuevas fue el error que en la Fase 1
-- marco 87 roturas que no lo eran.
DO $guardia_kardex$
DECLARE v_neg bigint; v_rotos bigint; v_resto bigint; v_min int;
BEGIN
    SELECT count(*) INTO v_neg FROM f2_kar WHERE stock_anterior < 0 OR stock_nuevo < 0;
    IF v_neg > 0 THEN RAISE EXCEPTION 'ABORTA: % movimientos dejarian negativo.', v_neg; END IF;

    SELECT count(*) INTO v_rotos FROM (
        SELECT lag(sn) OVER w AS prev, sa, row_number() OVER w AS rn
        FROM (
            SELECT mi.producto_variante_id v, mi.bodega_id b, mi.fecha_creacion ts, mi.id,
                   mi.stock_anterior sa, mi.stock_nuevo sn
            FROM movimiento_inventario mi WHERE mi.id < 1100000000
            UNION ALL
            SELECT variante_id, bodega_id, ts, 1100000000 + mov_seq, stock_anterior, stock_nuevo
            FROM f2_kar
        ) u WINDOW w AS (PARTITION BY v, b ORDER BY ts, id)) z
    WHERE (rn > 1 AND prev <> sa) OR (rn = 1 AND sa <> 0);
    IF v_rotos > 0 THEN
        RAISE EXCEPTION 'ABORTA: % enlaces rotos en la cadena FUSIONADA.', v_rotos;
    END IF;

    SELECT count(*) INTO v_resto FROM (
        SELECT variante_id, bodega_id, sum(factor*cantidad) AS neto FROM f2_kar GROUP BY 1,2) z
    WHERE neto <> 0;
    IF v_resto > 0 THEN
        RAISE EXCEPTION 'ABORTA: % posiciones no vuelven a su saldo (romperia el colchon).', v_resto;
    END IF;

    SELECT min(stock_nuevo) INTO v_min FROM f2_kar;
    RAISE NOTICE 'Plan de kardex OK: 0 negativos, 0 enlaces rotos, 0 residuos. Saldo minimo %.', v_min;
END
$guardia_kardex$;

\echo '--- 4. Plan de kardex construido y verificado: OK'

-- ════════════════════════════════════════════════════════════════════════════
-- 5. EL PLAN DE COMPRA DE REPOSICION + GUARDIA DE NUMEROS DE DOCUMENTO
-- ════════════════════════════════════════════════════════════════════════════
CREATE TEMP TABLE f2_entp AS
SELECT k.variante_id, k.bodega_id, k.cantidad, k.ts, k.costo,
       (k.ts AT TIME ZONE 'America/Guayaquil')::date AS dia,
       COALESCE(
         (SELECT pp.proveedor_id FROM producto_proveedor pp
           WHERE pp.producto_variante_id = k.variante_id AND pp.activo
           ORDER BY pp.es_preferido DESC, pp.id LIMIT 1),
         (SELECT pr.id FROM proveedor pr WHERE pr.activo ORDER BY pr.id
           OFFSET (('x' || substr(md5('pv' || k.variante_id::text), 1, 7))::bit(28)::int
                   % (SELECT count(*) FROM proveedor WHERE activo)) LIMIT 1)
       ) AS proveedor_id
FROM f2_kar k WHERE k.tipo = 1;

CREATE TEMP TABLE f2_ocl AS
WITH agg AS (
    SELECT proveedor_id, bodega_id, dia, variante_id,
           sum(cantidad)::int AS cantidad, min(ts) AS ts, max(costo) AS costo
    FROM f2_entp GROUP BY 1, 2, 3, 4
),
numerado AS (
    SELECT a.*, (row_number() OVER (PARTITION BY a.proveedor_id, a.bodega_id, a.dia
                                    ORDER BY a.variante_id) - 1) / 25 AS chunk
    FROM agg a
),
conoc AS (
    SELECT n.*, dense_rank() OVER (ORDER BY n.proveedor_id, n.bodega_id, n.dia, n.chunk) AS oc_seq,
           row_number() OVER (ORDER BY n.proveedor_id, n.bodega_id, n.dia, n.chunk,
                                       n.variante_id) AS ocd_seq
    FROM numerado n
)
-- El numero de documento se numera POR DIA dentro de la banda 92xxxxx: el
-- numero ya lleva la fecha, asi que una secuencia diaria basta para ser unica
-- y no se agota a diez anios, que es donde una secuencia global se rompe.
SELECT c.*, dense_rank() OVER (PARTITION BY c.dia ORDER BY c.oc_seq) AS seq_dia
FROM conoc c;
CREATE INDEX ON f2_ocl(oc_seq);

CREATE TEMP TABLE f2_entoc AS
SELECT e.variante_id, e.bodega_id, e.ts, l.oc_seq, l.dia, l.seq_dia
FROM f2_entp e
JOIN f2_ocl l ON l.proveedor_id = e.proveedor_id AND l.bodega_id = e.bodega_id
             AND l.dia = e.dia AND l.variante_id = e.variante_id;
CREATE INDEX ON f2_entoc(variante_id, bodega_id, ts);

-- ── GUARDIA DE NUMEROS DE DOCUMENTO (trampa 1 de la Fase 1) ────────────────
DO $guardia_numeros$
DECLARE v_max int; v_choque bigint; v_lista text;
BEGIN
    SELECT max(seq_dia) INTO v_max FROM f2_ocl;
    IF v_max >= 100000 THEN
        RAISE EXCEPTION 'ABORTA: % documentos en un solo dia no caben en 7 digitos.', v_max;
    END IF;

    -- No se razona: se COMPARA contra lo que ya existe, en las cinco tablas.
    --
    -- `id < 1100000000` NO es un detalle: sin ese filtro el guardia se dispara
    -- contra LAS FILAS DE ESTA MISMA FASE y la valvula de reanudacion queda
    -- inservible. Medido de verdad, no supuesto: al reanudar sobre una carga
    -- cortada en el lote 5, el guardia denuncio 243.919 «choques» que eran sus
    -- propios numeros ya escritos, y aborto la reanudacion entera.
    -- Un choque solo significa algo si es contra OTRA procedencia; contra la
    -- propia es la prueba de que el ON CONFLICT hara su trabajo.
    SELECT count(*), string_agg(DISTINCT n, ', ') INTO v_choque, v_lista FROM (
        SELECT oc.numero AS n FROM orden_compra oc
        JOIN (SELECT DISTINCT 'OC-' || to_char(dia - 5, 'YYYYMMDD') || '-' ||
                     lpad((9200000 + seq_dia)::text, 7, '0') AS n FROM f2_ocl) g ON g.n = oc.numero
        WHERE oc.id < 1100000000
        UNION ALL
        SELECT rm.numero FROM recepcion_mercancia rm
        JOIN (SELECT DISTINCT 'RM-' || to_char(dia, 'YYYYMMDD') || '-' ||
                     lpad((9200000 + seq_dia)::text, 7, '0') AS n FROM f2_ocl) g ON g.n = rm.numero
        WHERE rm.id < 1100000000
        UNION ALL
        SELECT fc.numero_factura FROM factura_compra fc
        JOIN (SELECT DISTINCT 'FC-' || to_char(dia + 1, 'YYYYMMDD') || '-' ||
                     lpad((9200000 + seq_dia)::text, 7, '0') AS n FROM f2_ocl) g ON g.n = fc.numero_factura
        WHERE fc.id < 1100000000
        UNION ALL
        SELECT p.numero FROM pedido p
        JOIN (SELECT DISTINCT numero AS n FROM f2_ped) g ON g.n = p.numero
        WHERE p.id < 1100000000
        UNION ALL
        SELECT fv.numero FROM factura_venta fv
        JOIN (SELECT DISTINCT 'FV-' || to_char(dia,'YYYYMMDD') || '-' ||
                     lpad((2000000 + i)::text, 7, '0') AS n FROM f2_ped) g ON g.n = fv.numero
        WHERE fv.id < 1100000000
    ) z;
    IF v_choque > 0 THEN
        RAISE EXCEPTION 'ABORTA: % numeros de documento CHOCAN con los existentes (%). '
                        'El tramo de ids no reserva las claves unicas de negocio.', v_choque, left(v_lista, 200);
    END IF;
    RAISE NOTICE 'Numeros de documento: 0 choques. Maximo por dia: % (tope 99.999).', v_max;
END
$guardia_numeros$;

\echo '--- 5. Plan de compras y numeros de documento verificados: OK'

-- ════════════════════════════════════════════════════════════════════════════
-- 6. LA CARGA, EN 12 LOTES DE 25.000 PEDIDOS CON COMMIT
-- ════════════════════════════════════════════════════════════════════════════
-- TAMANIO DE LOTE: 25.000 pedidos = ~460.000 filas por transaccion.
--   · Acota el WAL de cada transaccion y deja que el checkpoint respire.
--   · Un tropiezo cuesta como mucho el 8 % de la carga, no la carga entera.
--   · Doce puntos de medida son suficientes para VER si el ritmo se degrada
--     al avanzar, que es justo lo que hay que saber antes de la Fase 3.
-- Cada lote escribe el CICLO COMPLETO de sus pedidos (7 tablas). El kardex y
-- la compra de reposicion van aparte, porque se agrupan por posicion y
-- bimestre y no por pedido.
CREATE OR REPLACE PROCEDURE pg_temp.cargar_fase2()
LANGUAGE plpgsql AS $carga$
DECLARE
    v_lote int; v_ini timestamptz; v_n bigint;
BEGIN
FOR v_lote IN 1..12 LOOP

    v_ini := clock_timestamp();
    INSERT INTO pedido (id, numero, cliente_id, estado_pedido_id, moneda_id, metodo_envio_id,
                        direccion_envio_id, direccion_facturacion_id, canal,
                        subtotal, monto_descuento, monto_impuesto, costo_envio,
                        fecha_pedido, fecha_creacion, transportista_id, vendedor_id)
    OVERRIDING SYSTEM VALUE
    SELECT p.id, p.numero, p.cliente_id, p.estado_id, 1, p.metodo_envio_id,
           p.direccion_id, p.direccion_id, p.canal, 0, 0, 0, p.costo_envio,
           p.fecha_pedido, p.fecha_pedido, p.transportista_id, p.vendedor_id
    FROM f2_ped p WHERE p.lote = v_lote
    ON CONFLICT (id) DO NOTHING;
    GET DIAGNOSTICS v_n = ROW_COUNT;
    CALL pg_temp.medir('pedido', v_lote, v_ini, v_n);

    v_ini := clock_timestamp();
    INSERT INTO pedido_detalle (id, pedido_id, producto_variante_id, nombre_producto, sku,
                                cantidad, precio_unitario, monto_descuento, monto_impuesto,
                                fecha_creacion)
    OVERRIDING SYSTEM VALUE
    SELECT 1100000000 + l.lin_seq, l.pedido_id, l.variante_id,
           left(l.nombre_producto, 200), left(l.sku, 50),
           l.cantidad, l.precio_unitario, l.monto_descuento, l.monto_impuesto, p.fecha_pedido
    FROM f2_lin l JOIN f2_ped p ON p.id = l.pedido_id
    WHERE l.lote = v_lote
    ON CONFLICT (id) DO NOTHING;
    GET DIAGNOSTICS v_n = ROW_COUNT;
    CALL pg_temp.medir('pedido_detalle', v_lote, v_ini, v_n);

    v_ini := clock_timestamp();
    INSERT INTO historial_estado_pedido (id, pedido_id, estado_pedido_id, usuario_id,
                                         comentario, fecha_creacion)
    OVERRIDING SYSTEM VALUE
    SELECT 1100000000 + (p.i - 1) * 8 + x.ord, p.id, ep.id,
           CASE x.codigo WHEN 'confirmado' THEN p.vendedor_id
                         WHEN 'pagado'     THEN p.vendedor_id
                         WHEN 'facturado'  THEN 6::bigint
                         WHEN 'en_preparacion' THEN 9::bigint
                         WHEN 'preparado'      THEN 9::bigint
                         WHEN 'despachado'     THEN 10::bigint
                         WHEN 'entregado'      THEN 10::bigint
                         WHEN 'no_entregado'   THEN 10::bigint
                         WHEN 'devuelto'       THEN 9::bigint ELSE NULL END,
           '[FASE2] ' || x.codigo, x.ts
    FROM f2_ped p
    CROSS JOIN LATERAL (VALUES
        ('confirmado', p.fecha_pedido, 1), ('pagado', p.t_pagado, 2),
        ('facturado', p.t_facturado, 3),   ('en_preparacion', p.t_preparacion, 4),
        ('preparado', p.t_preparado, 5),   ('despachado', p.t_despachado, 6),
        ('entregado', p.t_entregado, 7),   ('final', p.t_final, 8)
    ) x(codigo, ts, ord)
    JOIN estado_pedido ep ON ep.codigo = CASE WHEN x.ord = 8 THEN p.estado_codigo ELSE x.codigo END
    WHERE p.lote = v_lote
      AND CASE p.estado_codigo
            WHEN 'cancelado'      THEN x.ord IN (1, 8)
            WHEN 'confirmado'     THEN x.ord IN (1)
            WHEN 'pagado'         THEN x.ord IN (1,2)
            WHEN 'facturado'      THEN x.ord IN (1,2,3)
            WHEN 'en_preparacion' THEN x.ord IN (1,2,3,4)
            WHEN 'preparado'      THEN x.ord IN (1,2,3,4,5)
            WHEN 'despachado'     THEN x.ord IN (1,2,3,4,5,6)
            WHEN 'entregado'      THEN x.ord IN (1,2,3,4,5,6,7)
            WHEN 'no_entregado'   THEN x.ord IN (1,2,3,4,5,6,8)
            WHEN 'devuelto'       THEN x.ord IN (1,2,3,4,5,6,7,8)
          END
    ON CONFLICT (id) DO NOTHING;
    GET DIAGNOSTICS v_n = ROW_COUNT;
    CALL pg_temp.medir('historial_estado_pedido', v_lote, v_ini, v_n);

    v_ini := clock_timestamp();
    INSERT INTO factura_venta (id, numero, pedido_id, cliente_id, moneda_id, razon_social,
                               identificacion, direccion_facturacion, subtotal, monto_descuento,
                               monto_impuesto, total, estado, fecha_emision, fecha_creacion)
    OVERRIDING SYSTEM VALUE
    SELECT p.id, 'FV-' || to_char(p.dia,'YYYYMMDD') || '-' || lpad((2000000 + p.i)::text, 7, '0'),
           p.id, p.cliente_id, 1, left(p.razon_social, 200), left(p.numero_identificacion, 20),
           (SELECT left(d.calle_principal || ', ' || ci.nombre, 300)
              FROM direccion d JOIN ciudad ci ON ci.id = d.ciudad_id WHERE d.id = p.direccion_id),
           0, 0, 0, 0, 'emitida', p.t_facturado, p.t_facturado
    FROM f2_ped p WHERE p.lote = v_lote AND p.factura
    ON CONFLICT (id) DO NOTHING;
    GET DIAGNOSTICS v_n = ROW_COUNT;
    CALL pg_temp.medir('factura_venta', v_lote, v_ini, v_n);

    v_ini := clock_timestamp();
    INSERT INTO factura_venta_detalle (id, factura_venta_id, pedido_detalle_id, producto_variante_id,
                                       descripcion, cantidad, precio_unitario, monto_descuento,
                                       monto_impuesto, fecha_creacion)
    OVERRIDING SYSTEM VALUE
    SELECT 1100000000 + l.lin_seq, l.pedido_id, 1100000000 + l.lin_seq, l.variante_id,
           left(l.nombre_producto, 255), l.cantidad, l.precio_unitario,
           l.monto_descuento, l.monto_impuesto, p.t_facturado
    FROM f2_lin l JOIN f2_ped p ON p.id = l.pedido_id
    WHERE l.lote = v_lote AND p.factura
    ON CONFLICT (id) DO NOTHING;
    GET DIAGNOSTICS v_n = ROW_COUNT;
    CALL pg_temp.medir('factura_venta_detalle', v_lote, v_ini, v_n);

    v_ini := clock_timestamp();
    INSERT INTO pago (id, pedido_id, metodo_pago_id, moneda_id, monto, estado,
                      referencia_externa, fecha_pago, fecha_creacion)
    OVERRIDING SYSTEM VALUE
    SELECT p.id, p.id,
           CASE WHEN p.canal = 'tienda' THEN 1
                WHEN pg_temp.u('mp', p.i) < 0.62 THEN 3 ELSE 2 END,
           1, ped.total, 'completado',
           'F2-' || lpad((2000000 + p.i)::text, 7, '0'), p.t_pagado, p.t_pagado
    FROM f2_ped p JOIN pedido ped ON ped.id = p.id
    WHERE p.lote = v_lote AND p.cobra AND ped.total > 0
    ON CONFLICT (id) DO NOTHING;
    GET DIAGNOSTICS v_n = ROW_COUNT;
    CALL pg_temp.medir('pago', v_lote, v_ini, v_n);

    v_ini := clock_timestamp();
    INSERT INTO transaccion_pago (id, pago_id, tipo, estado, monto, codigo_autorizacion,
                                  respuesta_pasarela, fecha_creacion)
    OVERRIDING SYSTEM VALUE
    SELECT pg.id, pg.id, 'captura', 'exitosa', pg.monto,
           'AUT' || lpad((pg.id - 1100000000)::text, 9, '0'),
           jsonb_build_object('origen','FASE2','resultado','aprobado'), pg.fecha_pago
    FROM pago pg JOIN f2_ped p ON p.id = pg.id
    WHERE p.lote = v_lote AND pg.id >= 1100000000
    ON CONFLICT (id) DO NOTHING;
    GET DIAGNOSTICS v_n = ROW_COUNT;
    CALL pg_temp.medir('transaccion_pago', v_lote, v_ini, v_n);

    COMMIT;
    RAISE NOTICE 'Lote %/12 commiteado.', v_lote;
END LOOP;
END
$carga$;

CALL pg_temp.cargar_fase2();

\echo '--- 6. Ciclo de venta cargado en 12 lotes: OK'

-- ════════════════════════════════════════════════════════════════════════════
-- 7. LA REPOSICION: compras y kardex
-- ════════════════════════════════════════════════════════════════════════════
CREATE OR REPLACE PROCEDURE pg_temp.cargar_reposicion()
LANGUAGE plpgsql AS $repo$
DECLARE v_ini timestamptz; v_n bigint;
BEGIN
    v_ini := clock_timestamp();
    INSERT INTO orden_compra (id, numero, proveedor_id, bodega_id, moneda_id, usuario_id, estado,
                              fecha_emision, fecha_entrega_esperada, subtotal, monto_impuesto, total,
                              observacion, fecha_creacion)
    OVERRIDING SYSTEM VALUE
    SELECT 1100000000 + l.oc_seq,
           'OC-' || to_char(min(l.dia) - 5, 'YYYYMMDD') || '-' || lpad((9200000 + min(l.seq_dia))::text, 7, '0'),
           min(l.proveedor_id), min(l.bodega_id), 1, 11, 'recibida',
           min(l.dia) - 5, min(l.dia), 0, 0, 0,
           '[FASE2] Reposicion bimestral', min(l.ts) - interval '5 days'
    FROM f2_ocl l GROUP BY l.oc_seq
    ON CONFLICT (id) DO NOTHING;
    GET DIAGNOSTICS v_n = ROW_COUNT; CALL pg_temp.medir('orden_compra', 0, v_ini, v_n);

    v_ini := clock_timestamp();
    INSERT INTO orden_compra_detalle (id, orden_compra_id, producto_variante_id, cantidad,
                                      precio_unitario, monto_impuesto, cantidad_recibida, fecha_creacion)
    OVERRIDING SYSTEM VALUE
    SELECT 1100000000 + l.ocd_seq, 1100000000 + l.oc_seq, l.variante_id, l.cantidad,
           l.costo, round(l.cantidad * l.costo * 0.15, 2), l.cantidad, l.ts - interval '5 days'
    FROM f2_ocl l ON CONFLICT (id) DO NOTHING;
    GET DIAGNOSTICS v_n = ROW_COUNT; CALL pg_temp.medir('orden_compra_detalle', 0, v_ini, v_n);
    COMMIT;

    v_ini := clock_timestamp();
    INSERT INTO recepcion_mercancia (id, numero, orden_compra_id, bodega_id, usuario_id, estado,
                                     fecha_recepcion, observacion, fecha_creacion)
    OVERRIDING SYSTEM VALUE
    SELECT 1100000000 + l.oc_seq,
           'RM-' || to_char(min(l.dia), 'YYYYMMDD') || '-' || lpad((9200000 + min(l.seq_dia))::text, 7, '0'),
           1100000000 + l.oc_seq, min(l.bodega_id), 9, 'confirmada',
           min(l.ts), '[FASE2] Recepcion completa', min(l.ts)
    FROM f2_ocl l GROUP BY l.oc_seq ON CONFLICT (id) DO NOTHING;
    GET DIAGNOSTICS v_n = ROW_COUNT; CALL pg_temp.medir('recepcion_mercancia', 0, v_ini, v_n);

    v_ini := clock_timestamp();
    INSERT INTO recepcion_detalle (id, recepcion_mercancia_id, orden_compra_detalle_id,
                                   cantidad_recibida, cantidad_rechazada, fecha_creacion)
    OVERRIDING SYSTEM VALUE
    SELECT 1100000000 + l.ocd_seq, 1100000000 + l.oc_seq, 1100000000 + l.ocd_seq,
           l.cantidad, 0, l.ts
    FROM f2_ocl l ON CONFLICT (id) DO NOTHING;
    GET DIAGNOSTICS v_n = ROW_COUNT; CALL pg_temp.medir('recepcion_detalle', 0, v_ini, v_n);
    COMMIT;

    v_ini := clock_timestamp();
    INSERT INTO factura_compra (id, proveedor_id, orden_compra_id, moneda_id, numero_factura,
                                fecha_emision, fecha_vencimiento, subtotal, monto_impuesto, total,
                                estado, registrado_por, fecha_creacion)
    OVERRIDING SYSTEM VALUE
    SELECT 1100000000 + l.oc_seq, min(l.proveedor_id), 1100000000 + l.oc_seq, 1,
           'FC-' || to_char(min(l.dia) + 1, 'YYYYMMDD') || '-' || lpad((9200000 + min(l.seq_dia))::text, 7, '0'),
           min(l.dia) + 1,
           min(l.dia) + 1 + (SELECT p.dias_credito FROM proveedor p WHERE p.id = min(l.proveedor_id)),
           0, 0, 0, 'pagada', 11, min(l.ts) + interval '1 day'
    FROM f2_ocl l GROUP BY l.oc_seq ON CONFLICT (id) DO NOTHING;
    GET DIAGNOSTICS v_n = ROW_COUNT; CALL pg_temp.medir('factura_compra', 0, v_ini, v_n);

    v_ini := clock_timestamp();
    INSERT INTO factura_compra_detalle (id, factura_compra_id, producto_variante_id, cantidad,
                                        precio_unitario, monto_impuesto, fecha_creacion)
    OVERRIDING SYSTEM VALUE
    SELECT 1100000000 + l.ocd_seq, 1100000000 + l.oc_seq, l.variante_id, l.cantidad,
           l.costo, round(l.cantidad * l.costo * 0.15, 2), l.ts + interval '1 day'
    FROM f2_ocl l ON CONFLICT (id) DO NOTHING;
    GET DIAGNOSTICS v_n = ROW_COUNT; CALL pg_temp.medir('factura_compra_detalle', 0, v_ini, v_n);
    COMMIT;

    v_ini := clock_timestamp();
    INSERT INTO cuenta_por_pagar (id, factura_compra_id, proveedor_id, monto_original,
                                  saldo_pendiente, fecha_vencimiento, estado, fecha_creacion)
    OVERRIDING SYSTEM VALUE
    SELECT fc.id, fc.id, fc.proveedor_id, fc.total, 0, fc.fecha_vencimiento, 'pagada', fc.fecha_creacion
    FROM factura_compra fc WHERE fc.id >= 1100000000 ON CONFLICT (id) DO NOTHING;
    GET DIAGNOSTICS v_n = ROW_COUNT; CALL pg_temp.medir('cuenta_por_pagar', 0, v_ini, v_n);

    v_ini := clock_timestamp();
    INSERT INTO pago_proveedor (id, cuenta_por_pagar_id, metodo_pago_id, usuario_id,
                                monto, fecha_pago, referencia, observacion, fecha_creacion)
    OVERRIDING SYSTEM VALUE
    SELECT c.id, c.id, 2, 11, c.monto_original, c.fecha_vencimiento,
           'TR-F2-' || lpad((c.id - 1100000000)::text, 8, '0'),
           '[FASE2] Pago de la reposicion', c.fecha_vencimiento::timestamptz
    FROM cuenta_por_pagar c WHERE c.id >= 1100000000 AND c.monto_original > 0
    ON CONFLICT (id) DO NOTHING;
    GET DIAGNOSTICS v_n = ROW_COUNT; CALL pg_temp.medir('pago_proveedor', 0, v_ini, v_n);
    COMMIT;
END
$repo$;

CALL pg_temp.cargar_reposicion();

-- El kardex, en 6 lotes por bimestre para acotar la transaccion.
CREATE OR REPLACE PROCEDURE pg_temp.cargar_kardex()
LANGUAGE plpgsql AS $kar$
DECLARE v_b int; v_ini timestamptz; v_n bigint; v_i int := 0; v_tot int;
BEGIN
SELECT count(DISTINCT bim) INTO v_tot FROM f2_kar;
-- Se recorren los bimestres QUE HAY, no un rango calculado a mano.
FOR v_b IN SELECT DISTINCT bim FROM f2_kar ORDER BY bim LOOP
    v_i := v_i + 1;
    v_ini := clock_timestamp();
    INSERT INTO movimiento_inventario (id, producto_variante_id, bodega_id, tipo_movimiento_id,
                                       usuario_id, cantidad, stock_anterior, stock_nuevo,
                                       costo_unitario, referencia_tipo, referencia_id,
                                       observacion, fecha_creacion)
    OVERRIDING SYSTEM VALUE
    SELECT 1100000000 + k.mov_seq, k.variante_id, k.bodega_id, k.tipo, 9,
           k.cantidad, k.stock_anterior, k.stock_nuevo, k.costo,
           CASE k.tipo WHEN 1 THEN 'recepcion_mercancia' ELSE 'pedido' END,
           CASE k.tipo WHEN 1 THEN (SELECT 1100000000 + o.oc_seq FROM f2_entoc o
                                     WHERE o.variante_id = k.variante_id AND o.bodega_id = k.bodega_id
                                       AND o.ts = k.ts LIMIT 1)
                       ELSE k.ref_id END,
           CASE k.tipo WHEN 1 THEN '[FASE2] Reposicion bimestral' ELSE '[FASE2] Salida por venta' END,
           k.ts                                   -- fecha EXPLICITA, nunca el DEFAULT
    FROM f2_kar k
    WHERE k.bim = v_b
    ON CONFLICT (id) DO NOTHING;
    GET DIAGNOSTICS v_n = ROW_COUNT;
    CALL pg_temp.medir('movimiento_inventario', v_i, v_ini, v_n);
    COMMIT;
    RAISE NOTICE 'Kardex bimestre %/% commiteado (% filas).', v_i, v_tot, v_n;
END LOOP;
END
$kar$;

CALL pg_temp.cargar_kardex();

\echo '--- 7. Reposicion y kardex cargados: OK'

-- ════════════════════════════════════════════════════════════════════════════
-- 8. BITACORA
-- ════════════════════════════════════════════════════════════════════════════
BEGIN;
DO $bit$
DECLARE t text; v_n bigint; v_mx bigint;
BEGIN
    FOREACH t IN ARRAY ARRAY['pedido','pedido_detalle','historial_estado_pedido','factura_venta',
                             'factura_venta_detalle','pago','transaccion_pago','movimiento_inventario',
                             'orden_compra','orden_compra_detalle','recepcion_mercancia',
                             'recepcion_detalle','factura_compra','factura_compra_detalle',
                             'cuenta_por_pagar','pago_proveedor']
    LOOP
        EXECUTE format('SELECT count(*), COALESCE(max(id),1100000001) FROM public.%I WHERE id >= 1100000000', t)
           INTO v_n, v_mx;
        PERFORM fn_carga_registrar('fase2','98_fase2_carga', t, 1100000001, v_mx, v_n, NULL);
    END LOOP;
END
$bit$;

INSERT INTO carga_fase_parametro (fase, clave, valor, nota) VALUES
 ('fase2','tramo_ids','1100000000 .. 1199999999',
  'Tope real = UInt32 del almacen (4.294.967.295), no el bigint de PostgreSQL.'),
 ('fase2','tramo_documentos','banda 92xxxxx, secuencia POR DIA',
  'Las claves UNICAS de negocio (orden_compra.numero, recepcion_mercancia.numero, '
  'factura_compra.numero_factura) NO las reserva el tramo de ids: la Fase 1 choco contra el seed. '
  'La secuencia va por DIA porque el numero ya lleva la fecha, y asi no se agota a diez anios. '
  'Un guardia compara los numeros generados contra los existentes y aborta si coincide uno.'),
 ('fase2','ventana','2026-09-01 .. 2027-08-31',
  'Honra arranque_ventas_futuras de la Fase 0. Es la unica ventana POSTERIOR a la apertura del '
  'stock (2026-08-09), que es lo que permite el colchon. 2024 es IMPOSIBLE: dim_fecha cubre '
  '2025-2026 y fact_prevision_demanda aborta si el calendario no cubre el hecho.'),
 ('fase2','metodo_stock','anexado con neto cero por posicion; colchon = apertura de la Fase 0',
  'Toda la fase va detras del ultimo movimiento existente, asi que el saldo de partida de cada '
  'posicion es la constante inventario.stock_actual. La entrada del bimestre precede a la primera '
  'salida del bimestre, de modo que el saldo minimo es S y nunca menos. Coste del colchon: CERO '
  'unidades y CERO compras adicionales.'),
 ('fase2','lote_carga','25.000 pedidos x 12 lotes',
  'Acota el WAL por transaccion y da doce puntos de medida para ver la degradacion.')
ON CONFLICT (fase, clave) DO UPDATE SET valor = EXCLUDED.valor, nota = EXCLUDED.nota;
COMMIT;

\echo ''
\echo '=== MEDICIONES ============================================================='
SELECT paso, count(*) lotes, sum(filas) filas,
       round(sum(extract(epoch FROM (fin - inicio)))::numeric, 2) seg,
       round((sum(filas) / NULLIF(sum(extract(epoch FROM (fin - inicio))), 0))::numeric, 0) filas_seg
FROM f2_medicion GROUP BY paso ORDER BY 4 DESC;

\echo ''
\echo '--- por lote (para ver si se degrada al avanzar) ---'
SELECT lote, sum(filas) filas,
       round(sum(extract(epoch FROM (fin - inicio)))::numeric, 2) seg
FROM f2_medicion WHERE lote BETWEEN 1 AND 12 AND paso NOT IN ('movimiento_inventario')
GROUP BY lote ORDER BY lote;

\echo ''
\echo '=== CARGA TERMINADA ========================================================'
SELECT (SELECT count(*) FROM pedido                WHERE id>=1100000000) pedidos,
       (SELECT count(*) FROM pedido_detalle        WHERE id>=1100000000) lineas,
       (SELECT count(*) FROM historial_estado_pedido WHERE id>=1100000000) historial,
       (SELECT count(*) FROM factura_venta         WHERE id>=1100000000) facturas,
       (SELECT count(*) FROM pago                  WHERE id>=1100000000) pagos,
       (SELECT count(*) FROM movimiento_inventario WHERE id>=1100000000) movimientos,
       (SELECT count(*) FROM orden_compra          WHERE id>=1100000000) ordenes,
       (SELECT round(avg(total),2) FROM pedido     WHERE id>=1100000000) ticket;
