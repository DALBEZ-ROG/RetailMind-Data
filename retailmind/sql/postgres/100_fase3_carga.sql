-- ============================================================================
-- 100_fase3_carga.sql — RetailMind · FASE 3: la decada completa (2026-08-11)
--
--   Un solo script, DIEZ BLOQUES. Cada invocacion carga UN bloque:
--
--   docker compose exec -T postgres psql -U postgres -d retailmind \
--       -v ON_ERROR_STOP=1 \
--       -v bloque=A1 -v base=1200000000 -v offnum=3000000 \
--       -v ini=2025-01-01 -v fin=2025-12-31 -v tasa=300000 \
--       -f - < retailmind/sql/postgres/100_fase3_carga.sql
--
--   Reanudar tras un corte: anadir  -v reanudar=1
--   Reversion por bloque:   99_revert_fase3.sql -v base=...
--
--   OJO: se ejecuta con `-f -`. El lote va en un PROCEDIMIENTO que hace COMMIT,
--   y eso solo es legal si el CALL no viene envuelto en una transaccion.
--
-- ---------------------------------------------------------------------------
-- QUE ARREGLA ESTA FASE
-- ---------------------------------------------------------------------------
-- El modelo de prevision se niega a publicar —MAPE 225,29 % contra 87,58 % del
-- ingenuo, cobertura 99,0 % contra el 65-90 % que exige §5.1.6— y hace bien: la
-- serie tiene 227 pedidos en 2026-07, CERO en 2026-08 y 23.680 en 2026-09. Un
-- modelo que descompone nivel x estacionalidad no puede estimar sigma sobre un
-- escalon de 100x. El criterio NO se toca: lo que esta mal son los datos.
--
-- Esta fase pone TODOS los meses de la decada a la misma densidad.
--
-- ---------------------------------------------------------------------------
-- EL OBJETIVO SE FIJA POR MES, NO POR BLOQUE
-- ---------------------------------------------------------------------------
-- Un bloque no recibe «N pedidos repartidos por el perfil»: recibe una TASA
-- ANUAL, y de ahi cada mes saca su objetivo
--
--     objetivo(mes) = tasa_anual x w(mes) / Σw          (w = perfil de 2025)
--     nuevos(mes)   = objetivo(mes) − LOS QUE YA HAY EN ESE MES
--
-- Esa resta es la clave de la redensificacion y es lo que hace que 2025 —donde
-- ya viven 12.463 pedidos repartidos de forma desigual— acabe con exactamente
-- el mismo perfil que 2031, donde no habia nada. Repartir un total por el
-- perfil sin restar lo existente dejaria los meses que ya tenian datos por
-- encima de los demas, que es el defecto que venimos a corregir.
--
-- Tambien resuelve el hueco de 2026-08: ese mes tiene cero pedidos, asi que
-- `nuevos` = objetivo entero y se rellena sin ningun caso especial.
--
-- ---------------------------------------------------------------------------
-- EL STOCK: UN SOLO MECANISMO, NETO CERO POR GRUPO
-- ---------------------------------------------------------------------------
-- La Fase 1 cargaba ANTES de la apertura del stock e intercalaba; la Fase 2
-- cargaba DESPUES y anexaba. Aqui hay bloques de los dos tipos —A1 y A2 caen
-- sobre 2025-2026, donde ya hay 65.000 movimientos y la apertura de la Fase 0
-- (2026-08-09) esta DENTRO de la ventana de A2— asi que se usa un mecanismo
-- unico que cubre ambos casos:
--
--   El grupo de reposicion es (posicion, bimestre, ULTIMO MOVIMIENTO EXISTENTE
--   ANTERIOR). Ese tercer componente parte el bimestre cada vez que un
--   movimiento preexistente se cruza, de modo que **entre la entrada de un
--   grupo y su ultima salida no cae jamas un eslabon ajeno**. Como el grupo
--   suma cero, la cadena preexistente no se entera de nada.
--
--       ... M_previo ... [ entrada +Q, ventas −Q ] ... M_siguiente ...
--                        └──────── suma 0 ────────┘
--
-- Con eso:
--   · `inventario.stock_actual` NO se escribe: 426.722 unidades antes y despues.
--   · El saldo minimo de una posicion es su saldo de partida, nunca menos.
--   · La apertura de la Fase 0 sigue valida SIN TOCARLA, igual que en la Fase 1.
--   · Es O(1) por linea: ni reencadenado global ni segunda pasada.
--
-- Cuando la ventana va detras de todo lo existente (bloques C2..C8) el tercer
-- componente del grupo es NULL para todos y el mecanismo DEGENERA solo en el
-- bimestral de la Fase 2. No hay dos caminos de codigo: hay uno que se adapta.
--
-- ---------------------------------------------------------------------------
-- TRAMOS RESERVADOS
-- ---------------------------------------------------------------------------
-- IDs: un tramo de 100.000.000 por bloque, de 1.200.000.000 a 2.199.999.999.
--   El techo real NO es el bigint de PostgreSQL: es el **UInt32** del almacen
--   (4.294.967.295), que reciben `pedido_id`, `cliente_id`,
--   `producto_variante_id`, `documento_id` (=`pago.id`), `contraparte_id`,
--   `orden_compra_id` y `envio_id`. El id mas alto que esta fase escribe es
--   ~2.100.800.000 (historial del bloque C8): queda al 49 % del techo.
--   La tabla mas voluminosa por bloque —`historial_estado_pedido`, 8 filas por
--   pedido— consume como mucho 2,4 M ids de los 100 M del tramo.
--
-- NUMEROS DE DOCUMENTO: siete digitos con la forma **9BB SSSS** — `BB` es el
--   bloque (930..939) y `SSSS` la secuencia DE ESE DIA (la Fase 0 uso 90xxxxx,
--   la 1 el 91xxxxx, la 2 el 92xxxxx).
--
--   La primera version daba una sola banda a los diez bloques, razonando que
--   sus ventanas de fechas son disjuntas y que el numero ya lleva la fecha.
--   ES FALSO, y el guardia lo cazo: el numero de la orden de compra lleva su
--   fecha de EMISION, cinco dias antes de la recepcion, asi que las ordenes de
--   principios de enero de 2026 se emiten en diciembre de 2025 —dentro de la
--   ventana del bloque anterior— y chocan. Medido: 183 colisiones al arrancar
--   A2. Las ventanas de PEDIDOS no se solapan; las de DOCUMENTOS sobresalen
--   por los dos bordes.
--
--   Con `BB` por bloque el solape deja de importar. Quedan 9.999 documentos por
--   dia y bloque, contra los ~500 que produce un anio a 300.000 pedidos.
--   `pedido.numero` y `factura_venta.numero` usan su propio desplazamiento
--   (`offnum`), disjunto por bloque. Y no se razona: un guardia compara los
--   numeros generados contra los existentes en las cinco tablas y ABORTA.
--
-- IDEMPOTENTE (ON CONFLICT DO NOTHING) · POR LOTES CON COMMIT
-- ============================================================================
\set ON_ERROR_STOP on
\timing on

\if :{?reanudar}
\else
  \set reanudar 0
\endif
SELECT set_config('retailmind.reanudar', :'reanudar', false),
       set_config('retailmind.base',     :'base',     false),
       set_config('retailmind.bloque',   :'bloque',   false),
       set_config('retailmind.banda',    :'banda',    false);

-- Memoria de trabajo SOLO de esta sesion (no ALTER SYSTEM; muere con el psql).
SET work_mem = '256MB';
SET maintenance_work_mem = '512MB';
SET synchronous_commit = off;

\echo ''
\echo '=== FASE 3 · BLOQUE :bloque · :ini a :fin ================================='

-- ════════════════════════════════════════════════════════════════════════════
-- 0. AZAR DETERMINISTA · CRONOMETRO · GUARDIA DEL TRAMO
-- ════════════════════════════════════════════════════════════════════════════
-- EL GENERADOR LLEVA SAL POR BLOQUE, y no es un detalle estetico.
--
-- Hasta la Fase 2 el sorteo dependia solo de (clave, i), y bastaba: cada fase
-- ocupaba una ventana temporal distinta, asi que dos pedidos con el mismo `i`
-- caian en anios distintos y no se estorbaban. **La redensificacion rompe esa
-- suposicion**: el bloque A1 vuelve a 2025, donde ya vive la Fase 1, y como `i`
-- reempieza en 1 en cada bloque, el pedido n.º 39 de A1 sacaba EXACTAMENTE los
-- mismos numeros que el n.º 39 de la Fase 1 — mismo dia, misma hora, mismo
-- minuto, mismo microsegundo. Medido: el movimiento 1200000039 aterrizo en
-- 2024-12-31 16:50:03.553314, el mismo instante que el 1000000001, y con el
-- desempate por id la cadena fusionada quedo con 53.074 enlaces rotos.
--
-- La sal se HORNEA en el cuerpo de la funcion con `format`, no se lee con
-- `current_setting`: asi la funcion sigue siendo IMMUTABLE y el planificador
-- puede insertarla en linea, que es de donde sale su velocidad.
DO $salar$
BEGIN
    EXECUTE format($f$
        CREATE OR REPLACE FUNCTION pg_temp.u(k text, i bigint) RETURNS numeric
        LANGUAGE sql IMMUTABLE PARALLEL SAFE AS
        $b$ SELECT ((('x' || substr(md5(%L || k || i::text), 1, 7))::bit(28)::int)
                    %% 1000000)::numeric / 1000000.0 $b$
    $f$, current_setting('retailmind.bloque'));
END
$salar$;

CREATE TEMP TABLE f3_medicion(
    orden serial, paso text, lote int, inicio timestamptz, fin timestamptz, filas bigint);
CREATE OR REPLACE PROCEDURE pg_temp.medir(p_paso text, p_lote int, p_ini timestamptz, p_filas bigint)
LANGUAGE sql AS
$$ INSERT INTO f3_medicion(paso, lote, inicio, fin, filas)
   VALUES (p_paso, p_lote, p_ini, clock_timestamp(), p_filas) $$;

DO $guardia$
DECLARE t text; n bigint; sucias text := '';
        v_reanudar int  := current_setting('retailmind.reanudar', true)::int;
        v_base     bigint := current_setting('retailmind.base')::bigint;
        v_tope     bigint := current_setting('retailmind.base')::bigint + 100000000;
BEGIN
    FOREACH t IN ARRAY ARRAY['pedido','pedido_detalle','historial_estado_pedido',
                             'factura_venta','factura_venta_detalle','pago','transaccion_pago',
                             'movimiento_inventario','orden_compra','orden_compra_detalle',
                             'recepcion_mercancia','recepcion_detalle','factura_compra',
                             'factura_compra_detalle','cuenta_por_pagar','pago_proveedor']
    LOOP
        EXECUTE format('SELECT count(*) FROM public.%I WHERE id >= %s AND id < %s', t, v_base, v_tope)
           INTO n;
        IF n > 0 THEN sucias := sucias || format('  %s: %s filas%s', t, n, E'\n'); END IF;
    END LOOP;
    IF sucias <> '' AND v_reanudar = 0 THEN
        RAISE EXCEPTION 'El tramo del bloque % YA tiene filas. Revierte ese bloque o reanuda '
                        'con -v reanudar=1:%', current_setting('retailmind.bloque'), E'\n' || sucias;
    ELSIF sucias <> '' THEN
        RAISE NOTICE 'REANUDANDO el bloque % sobre una carga incompleta:%',
                     current_setting('retailmind.bloque'), E'\n' || sucias;
    ELSE
        RAISE NOTICE 'Guardia OK: el tramo [%, %) esta vacio.', v_base, v_tope;
    END IF;
END
$guardia$;

-- ════════════════════════════════════════════════════════════════════════════
-- 1. PERFILES Y OBJETIVO MENSUAL
-- ════════════════════════════════════════════════════════════════════════════
-- El perfil sale de los 4.083 pedidos ORIGINALES y se filtra a 2025: esos
-- pedidos abarcan diecinueve meses, y agrupar por numero de mes sin acotar el
-- anio suma enero de 2025 con enero de 2026 y deja los meses 1-7 con el doble
-- de peso. Ya paso una vez y no rompe ninguna verificacion de integridad —por
-- eso es peligroso—, solo falsea lo unico que el perfil existe para reproducir.
CREATE TEMP TABLE f3_perfil AS
SELECT extract(month FROM fecha_pedido AT TIME ZONE 'America/Guayaquil')::int AS m,
       count(*)::numeric AS w
FROM pedido
WHERE id < 900000000
  AND fecha_pedido >= timestamptz '2025-01-01 00:00:00-05'
  AND fecha_pedido <  timestamptz '2026-01-01 00:00:00-05'
GROUP BY 1;

-- Objetivo por mes menos lo que ya existe. Aqui vive la redensificacion.
CREATE TEMP TABLE f3_mes AS
WITH meses AS (
    SELECT generate_series(date :'ini', date :'fin', interval '1 month')::date AS mes_inicio
),
obj AS (
    SELECT ms.mes_inicio,
           round(:tasa * p.w / (SELECT sum(w) FROM f3_perfil))::bigint AS objetivo,
           COALESCE((SELECT count(*) FROM pedido pe
                     WHERE pe.fecha_pedido >= ms.mes_inicio::timestamptz
                       AND pe.fecha_pedido <  (ms.mes_inicio + interval '1 month')::timestamptz), 0)
             AS existentes,
           (ms.mes_inicio + interval '1 month' - interval '1 day')::date AS mes_fin
    FROM meses ms JOIN f3_perfil p ON p.m = extract(month FROM ms.mes_inicio)::int
)
SELECT mes_inicio, mes_fin, objetivo, existentes,
       GREATEST(0, objetivo - existentes) AS nuevos,
       (mes_fin - mes_inicio + 1)          AS dias
FROM obj ORDER BY mes_inicio;

-- Rangos acumulados: el pedido i cae en el mes cuyo tramo lo contiene.
ALTER TABLE f3_mes ADD COLUMN lo bigint, ADD COLUMN hi bigint;
UPDATE f3_mes t SET lo = z.lo, hi = z.hi
FROM (SELECT mes_inicio,
             sum(nuevos) OVER (ORDER BY mes_inicio) - nuevos AS lo,
             sum(nuevos) OVER (ORDER BY mes_inicio)          AS hi
      FROM f3_mes) z
WHERE z.mes_inicio = t.mes_inicio;
CREATE INDEX ON f3_mes(lo, hi);

\echo '--- objetivo mensual del bloque ---'
SELECT to_char(mes_inicio,'YYYY-MM') mes, objetivo, existentes, nuevos FROM f3_mes ORDER BY 1;
SELECT sum(nuevos) AS pedidos_a_cargar FROM f3_mes;

CREATE TEMP TABLE f3_hora AS
WITH h AS (SELECT extract(hour FROM fecha_pedido AT TIME ZONE 'America/Guayaquil')::int AS hh,
                  count(*)::numeric AS w
           FROM pedido WHERE id < 900000000 GROUP BY 1 HAVING count(*) >= 50)
SELECT hh, (sum(w) OVER (ORDER BY hh) - w)/sum(w) OVER () AS lo,
           sum(w) OVER (ORDER BY hh)      /sum(w) OVER () AS hi
FROM h;

CREATE TEMP TABLE f3_env AS
SELECT row_number() OVER (ORDER BY id) AS rn, costo_envio AS costo, count(*) OVER () AS tot
FROM pedido WHERE id < 900000000 AND costo_envio > 0;
CREATE INDEX ON f3_env(rn);

CREATE TEMP TABLE f3_canal AS
WITH c(codigo, w, ord) AS (VALUES
    ('web', 54.20::numeric, 1), ('tienda', 25.23::numeric, 2), ('telefono', 20.57::numeric, 3))
SELECT codigo, (sum(w) OVER (ORDER BY ord) - w)/sum(w) OVER () AS lo,
               sum(w) OVER (ORDER BY ord)      /sum(w) OVER () AS hi FROM c;

CREATE TEMP TABLE f3_estado AS
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
                     'devuelto','no_entregado'))              AS mueve_stock,
       (e.codigo IN ('facturado','en_preparacion','preparado','despachado',
                     'entregado','devuelto','no_entregado'))  AS factura,
       (e.codigo NOT IN ('cancelado','confirmado'))           AS cobra
FROM e JOIN estado_pedido ep ON ep.codigo = e.codigo;

CREATE TEMP TABLE f3_cli AS
SELECT c.id AS cliente_id,
       trim(c.nombre || COALESCE(' ' || c.apellido, '')) AS razon_social,
       c.numero_identificacion,
       (SELECT d.id FROM direccion d WHERE d.usuario_id = c.usuario_id
         ORDER BY d.es_predeterminada DESC, d.id LIMIT 1) AS direccion_id,
       row_number() OVER (ORDER BY c.id) AS rn, count(*) OVER () AS tot
FROM cliente c;
CREATE INDEX ON f3_cli(rn);

CREATE TEMP TABLE f3_vend AS
SELECT row_number() OVER (ORDER BY u.id) AS rn, u.id, count(*) OVER () AS tot
FROM usuario u JOIN usuario_rol ur ON ur.usuario_id = u.id
JOIN rol r ON r.id = ur.rol_id WHERE r.codigo = 'VENDEDOR';
CREATE INDEX ON f3_vend(rn);

CREATE TEMP TABLE f3_pos AS
SELECT inv.producto_variante_id AS variante_id, inv.bodega_id,
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

CREATE TEMP TABLE f3_posn AS
SELECT p.*, row_number() OVER (PARTITION BY banda, bodega_id ORDER BY variante_id) AS rn
FROM f3_pos p WHERE banda <> 'historico';
CREATE INDEX ON f3_posn(banda, bodega_id, rn);
CREATE TEMP TABLE f3_posh AS
SELECT p.*, row_number() OVER (ORDER BY variante_id, bodega_id) AS rn
FROM f3_pos p WHERE banda = 'historico';
CREATE INDEX ON f3_posh(rn);

-- Los totales van APARTE y como escalares. Con `tot` leido como columna dentro
-- del LATERAL el indice no se puede usar para buscar `rn` —el valor buscado
-- dependeria de la fila candidata— y el sorteo pasa de O(lineas) a
-- O(lineas x variantes). Medido en la Fase 2: de 12 s a mas de 3:24 sin acabar.
CREATE TEMP TABLE f3_totn AS
SELECT banda, bodega_id, count(*) AS tot FROM f3_posn GROUP BY banda, bodega_id;
CREATE TEMP TABLE f3_toth AS SELECT count(*) AS tot FROM f3_posh;

CREATE TEMP TABLE f3_banda AS
SELECT m[1] AS banda,
       (sum(m[2]::numeric) OVER (ORDER BY m[1]) - m[2]::numeric)/100.0 AS lo,
        sum(m[2]::numeric) OVER (ORDER BY m[1])/100.0                  AS hi
FROM carga_fase_parametro p,
     LATERAL regexp_matches(p.valor, '([A-Za-z0-9]+)=([0-9.]+)%', 'g') AS m
WHERE p.fase = 'fase0' AND p.clave = 'pesos_demanda_lineas';

\echo '--- 1. Perfiles y objetivo mensual: OK'

-- ════════════════════════════════════════════════════════════════════════════
-- 2. LOS PEDIDOS DEL BLOQUE
-- ════════════════════════════════════════════════════════════════════════════
CREATE TEMP TABLE f3_ped AS
WITH n AS (SELECT generate_series(1, (SELECT sum(nuevos) FROM f3_mes))::bigint AS i),
sorteo AS (
    SELECT n.i, mm.mes_inicio, mm.dias, h.hh, c.codigo AS canal,
           e.codigo AS estado_codigo, e.estado_id, e.mueve_stock, e.factura, e.cobra
    FROM n
    JOIN LATERAL (SELECT mes_inicio, dias FROM f3_mes
                   WHERE n.i > lo AND n.i <= hi LIMIT 1) mm ON true
    JOIN LATERAL (SELECT hh FROM f3_hora
                   WHERE pg_temp.u('hora', n.i) >= lo AND pg_temp.u('hora', n.i) < hi LIMIT 1) h ON true
    JOIN LATERAL (SELECT codigo FROM f3_canal
                   WHERE pg_temp.u('canal', n.i) >= lo AND pg_temp.u('canal', n.i) < hi LIMIT 1) c ON true
    JOIN LATERAL (SELECT codigo, estado_id, mueve_stock, factura, cobra FROM f3_estado
                   WHERE pg_temp.u('est', n.i) >= lo AND pg_temp.u('est', n.i) < hi LIMIT 1) e ON true
),
fechado AS (
    -- Dia dentro del mes: uniforme. El dia de la semana va PLANO a proposito —
    -- la serie original lo es (551-604 sobre 4.083) y fabricarle un ciclo
    -- semanal al bloque lo haria distinguible con un GROUP BY.
    SELECT s.*,
           (s.mes_inicio + (floor(pg_temp.u('dia', s.i) * s.dias)::int || ' days')::interval)::date AS dia
    FROM sorteo s
)
SELECT
    :base + f.i AS id, f.i, f.dia,
    ((f.dia + (f.hh || ' hours')::interval
            + (floor(pg_temp.u('min', f.i) * 60) || ' minutes')::interval
            + (floor(pg_temp.u('seg', f.i) * 60) || ' seconds')::interval
            + (f.i || ' microseconds')::interval) AT TIME ZONE 'America/Guayaquil') AS fecha_pedido,
    f.canal, f.estado_codigo, f.estado_id, f.mueve_stock, f.factura, f.cobra,
    cl.cliente_id, cl.direccion_id, cl.razon_social, cl.numero_identificacion,
    'PED-' || to_char(f.dia, 'YYYYMMDD') || '-' || lpad((:offnum + f.i)::text, 7, '0') AS numero,
    CASE WHEN f.canal = 'web' THEN NULL ELSE v.id END AS vendedor_id,
    CASE WHEN f.canal = 'tienda' THEN NULL
         WHEN pg_temp.u('me', f.i) < 0.75 THEN 1::bigint ELSE 2::bigint END AS metodo_envio_id,
    CASE WHEN f.canal = 'tienda' THEN NULL
         WHEN pg_temp.u('me', f.i) < 0.75 THEN 2::bigint ELSE 1::bigint END AS transportista_id,
    CASE WHEN f.canal = 'tienda' THEN 0::numeric ELSE en.costo END AS costo_envio,
    CASE WHEN pg_temp.u('nl', f.i) < 0.2302 THEN 1
         WHEN pg_temp.u('nl', f.i) < 0.5625 THEN 2
         WHEN pg_temp.u('nl', f.i) < 0.7768 THEN 3
         WHEN pg_temp.u('nl', f.i) < 0.8870 THEN 4
         ELSE 5 END AS n_lineas,
    ((f.i - 1) / 25000)::int + 1 AS lote
FROM fechado f
JOIN LATERAL (SELECT cliente_id, direccion_id, razon_social, numero_identificacion FROM f3_cli
              WHERE rn = 1 + floor(pg_temp.u('cli', f.i) * (SELECT tot FROM f3_cli LIMIT 1))::bigint) cl ON true
JOIN LATERAL (SELECT id FROM f3_vend
              WHERE rn = 1 + floor(pg_temp.u('vnd', f.i) * (SELECT tot FROM f3_vend LIMIT 1))::bigint) v ON true
JOIN LATERAL (SELECT costo FROM f3_env
              WHERE rn = 1 + floor(pg_temp.u('env', f.i) * (SELECT tot FROM f3_env LIMIT 1))::bigint) en ON true;

CREATE UNIQUE INDEX ON f3_ped(id);
CREATE INDEX ON f3_ped(lote);

ALTER TABLE f3_ped
    ADD COLUMN t_pagado timestamptz, ADD COLUMN t_facturado timestamptz,
    ADD COLUMN t_preparacion timestamptz, ADD COLUMN t_preparado timestamptz,
    ADD COLUMN t_despachado timestamptz, ADD COLUMN t_entregado timestamptz,
    ADD COLUMN t_final timestamptz;
UPDATE f3_ped p SET
    t_pagado      = p.fecha_pedido + (0.5 + 5.5 * pg_temp.u('h1', p.i)) * interval '1 hour',
    t_facturado   = p.fecha_pedido + (0.7 + 8.5 * pg_temp.u('h1', p.i)) * interval '1 hour',
    t_preparacion = p.fecha_pedido + (3   + 30  * pg_temp.u('h2', p.i)) * interval '1 hour',
    t_preparado   = p.fecha_pedido + (7   + 36  * pg_temp.u('h2', p.i)) * interval '1 hour'
                                   + (1   +  7  * pg_temp.u('h3', p.i)) * interval '1 hour',
    t_despachado  = p.fecha_pedido + (10  + 40  * pg_temp.u('h2', p.i)) * interval '1 hour'
                                   + (2   + 22  * pg_temp.u('h4', p.i)) * interval '1 hour';
UPDATE f3_ped p SET t_entregado = p.t_despachado + (1 + 5 * pg_temp.u('h5', p.i)) * interval '1 day';
UPDATE f3_ped p SET t_final = CASE p.estado_codigo
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

\echo '--- 2. Pedidos del bloque sorteados: OK'

-- ════════════════════════════════════════════════════════════════════════════
-- 3. LAS LINEAS
-- ════════════════════════════════════════════════════════════════════════════
CREATE TEMP TABLE f3_lin AS
WITH ln AS (
    SELECT p.i, p.id AS pedido_id, p.lote, p.mueve_stock, p.factura, g.j, p.i * 10 + g.j AS k
    FROM f3_ped p CROSS JOIN LATERAL generate_series(1, p.n_lineas) g(j)
),
conbanda AS (
    SELECT ln.*, b.banda,
           CASE WHEN pg_temp.u('bod', ln.k) < 0.80 THEN 4::bigint ELSE 3::bigint END AS bod
    FROM ln JOIN LATERAL (SELECT banda FROM f3_banda
                          WHERE pg_temp.u('bnd', ln.k) >= lo AND pg_temp.u('bnd', ln.k) < hi LIMIT 1) b ON true
),
conrn AS (
    SELECT c.*,
           CASE WHEN c.banda = 'historico'
                THEN 1 + floor(pg_temp.u('pos', c.k) * (SELECT tot FROM f3_toth))::bigint
                ELSE 1 + floor(pg_temp.u('pos', c.k) * t.tot)::bigint
           END AS rn_obj
    FROM conbanda c
    LEFT JOIN f3_totn t ON t.banda = c.banda AND t.bodega_id = c.bod
),
conpos AS (
    SELECT c.*, pos.variante_id, pos.bodega_id, pos.precio, pos.costo, pos.sku, pos.nombre_producto
    FROM conrn c
    JOIN LATERAL (
        SELECT variante_id, bodega_id, precio, costo, sku, nombre_producto FROM f3_posn n
        WHERE c.banda <> 'historico' AND n.banda = c.banda AND n.bodega_id = c.bod AND n.rn = c.rn_obj
        UNION ALL
        SELECT variante_id, bodega_id, precio, costo, sku, nombre_producto FROM f3_posh h
        WHERE c.banda = 'historico' AND h.rn = c.rn_obj
    ) pos ON true
),
dedup AS (
    -- `uq_pedido_detalle (pedido_id, producto_variante_id)`: la variante no se
    -- repite en un pedido, y la restriccion no mira la bodega.
    SELECT DISTINCT ON (pedido_id, variante_id) * FROM conpos
    ORDER BY pedido_id, variante_id, j
),
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
    SELECT c.*, (CASE WHEN pg_temp.u('dsc', c.k) < 0.012
                      THEN round(c.cantidad * c.precio_unitario * 0.10, 2)
                      ELSE 0 END)::numeric(12,2) AS monto_descuento
    FROM calc c
)
SELECT c.*,
       round((c.cantidad * c.precio_unitario - c.monto_descuento) * 0.15, 2)::numeric(12,2)
         AS monto_impuesto,
       row_number() OVER (ORDER BY c.pedido_id, c.variante_id) AS lin_seq
FROM condsc c;
CREATE UNIQUE INDEX ON f3_lin(lin_seq);
CREATE INDEX ON f3_lin(lote);

\echo '--- 3. Lineas generadas: OK'

-- ════════════════════════════════════════════════════════════════════════════
-- 4. EL PLAN DE KARDEX
-- ════════════════════════════════════════════════════════════════════════════
CREATE TEMP TABLE f3_sal AS
SELECT l.lin_seq, l.pedido_id, l.lote, l.variante_id, l.bodega_id, l.cantidad, l.costo,
       p.t_preparacion + (l.lin_seq || ' microseconds')::interval AS ts
FROM f3_lin l JOIN f3_ped p ON p.id = l.pedido_id
WHERE p.mueve_stock;
CREATE INDEX ON f3_sal(variante_id, bodega_id, ts);
ALTER TABLE f3_sal ADD COLUMN ult timestamptz, ADD COLUMN bim int;

-- EL CORTE DE LAS DOS TABLAS TIENE QUE SER EL MISMO INSTANTE, y ese instante
-- NO es el inicio de la ventana de pedidos.
--
-- La entrada de un grupo se fecha UN DIA ANTES de su primera venta, y la
-- primera venta de la ventana ocurre pocas horas despues de su arranque: la
-- entrada acaba cayendo ANTES de `ini`. Si `f3_base0` corta en `ini`, un
-- movimiento preexistente de esas horas previas se cuenta como «saldo de
-- partida» y ademas queda cronologicamente DESPUES de la entrada: el saldo se
-- suma dos veces y la cadena fusionada se rompe. Medido en el bloque A1: 4.350
-- enlaces rotos, detectados por el guardia antes de escribir una sola fila
-- —justo lo que el guardia existe para hacer—.
--
-- Se retrasa el corte dos dias, con margen de sobra sobre el dia de adelanto.
CREATE TEMP TABLE f3_corte AS SELECT (date :'ini' - 2)::timestamptz AS t;

-- Saldo de cada posicion JUSTO ANTES del corte. Una sola pasada agregada.
CREATE TEMP TABLE f3_base0 AS
SELECT mi.producto_variante_id AS v, mi.bodega_id AS b,
       sum(mi.cantidad * tm.factor) AS s
FROM movimiento_inventario mi JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id
WHERE mi.fecha_creacion < (SELECT t FROM f3_corte)
GROUP BY 1, 2;
CREATE UNIQUE INDEX ON f3_base0(v, b);

-- Movimientos EXISTENTES desde el corte: son los unicos que pueden
-- intercalarse con lo que este bloque escribe. Acotarlos a la ventana es lo que
-- evita copiar los 8 millones del kardex en cada bloque.
CREATE TEMP TABLE f3_prev AS
SELECT mi.producto_variante_id AS v, mi.bodega_id AS b, mi.fecha_creacion AS ts,
       mi.id, mi.cantidad * tm.factor AS q
FROM movimiento_inventario mi JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id
WHERE mi.fecha_creacion >= (SELECT t FROM f3_corte)
  AND mi.fecha_creacion <  (date :'fin' + 30)::timestamptz;
CREATE INDEX ON f3_prev(v, b, ts);

-- `ult` = ultimo movimiento EXISTENTE anterior a la venta. Se calcula una sola
-- vez por venta y viaja en la fila.
UPDATE f3_sal s SET
    ult = (SELECT max(p.ts) FROM f3_prev p
            WHERE p.v = s.variante_id AND p.b = s.bodega_id AND p.ts < s.ts),
    bim = (extract(year FROM s.ts AT TIME ZONE 'America/Guayaquil')::int * 6
           + (extract(month FROM s.ts AT TIME ZONE 'America/Guayaquil')::int - 1) / 2);

-- EL GRUPO ES (posicion, bimestre, ULTIMO ESLABON AJENO ANTERIOR), y ese tercer
-- componente es EL PUNTO ENTERO DE LA REDENSIFICACION.
--
-- Un movimiento del kardex guarda su saldo corrido. Meter filas en mitad de una
-- cadena viva deja obsoleto el saldo de TODO lo que venga detras… salvo que lo
-- insertado sume cero ENTRE DOS ESLABONES CONSECUTIVOS. Sin este corte, la
-- entrada de un bimestre se colocaba antes de un movimiento de la Fase 1 y sus
-- ventas despues: la fila existente decia «0 → 1» cuando el saldo real ya era
-- 9. Medido: 52.398 enlaces rotos, la mitad exacta en filas ajenas — la firma
-- inconfundible de este error.
--
-- Al agrupar tambien por `ult`, todas las ventas del grupo comparten el mismo
-- eslabon ajeno por delante, asi que ninguno cae dentro del tramo del grupo, y
-- la entrada se ancla DESPUES de el:
--
--        ... M_previo ... [ entrada +Q, ventas −Q ] ... M_siguiente ...
--                         └──────── suma 0 ────────┘   (no se entera)
CREATE TEMP TABLE f3_grp AS
SELECT s.variante_id, s.bodega_id, s.bim, s.ult,
       sum(s.cantidad)::int AS cantidad,
       min(s.ts) AS primera,
       max(s.costo) AS costo
FROM f3_sal s
GROUP BY 1, 2, 3, 4;

-- Entrada: un dia antes de la primera venta, pero NUNCA por delante del eslabon
-- ajeno que delimita el grupo, ni por detras de la venta que financia.
ALTER TABLE f3_grp ADD COLUMN ts timestamptz;
UPDATE f3_grp g SET ts = LEAST(
    GREATEST(g.primera - interval '1 day',
             COALESCE(g.ult, g.primera - interval '1 day') + interval '1 millisecond'),
    g.primera - interval '1 microsecond');
CREATE INDEX ON f3_grp(variante_id, bodega_id, bim);

CREATE TEMP TABLE f3_mov AS
SELECT row_number() OVER (ORDER BY ts, variante_id, bodega_id, tipo, ref_id) AS mov_seq,
       (extract(year  FROM ts AT TIME ZONE 'America/Guayaquil')::int * 6
      + (extract(month FROM ts AT TIME ZONE 'America/Guayaquil')::int - 1) / 2) AS bim_lote, *
FROM (
    SELECT g.variante_id, g.bodega_id, 1 AS tipo, 1 AS factor, g.cantidad, g.ts, g.costo,
           NULL::bigint AS ref_id, NULL::int AS lote
    FROM f3_grp g
    UNION ALL
    SELECT s.variante_id, s.bodega_id, 5, -1, s.cantidad, s.ts, s.costo, s.pedido_id, s.lote
    FROM f3_sal s
) z;
CREATE INDEX ON f3_mov(variante_id, bodega_id, ts, mov_seq);

-- LA CADENA SE CALCULA SOBRE LA SECUENCIA FUSIONADA, POR POSICION.
--
-- El intento anterior particionaba el acumulado por GRUPO, suponiendo que cada
-- grupo arranca de un saldo asentado. NO es cierto: la entrada del bimestre
-- siguiente se fecha un dia antes de su primera venta y cae DENTRO del tramo
-- todavia activo del bimestre anterior. Los dos grupos calculaban su base por
-- separado y se pisaban. Medido: 4.350 enlaces rotos, con el patron a la vista
-- —entrada de mayo el 30 de abril, en mitad de las ventas de marzo-abril—.
--
-- La forma correcta es un solo acumulado por (variante, bodega) sobre la union
-- de lo que ESTE bloque escribe y lo que YA EXISTE en la ventana. Los
-- movimientos existentes no se escriben: entran solo para que el saldo corrido
-- sea el real. Asi el solape entre grupos deja de importar, y de paso
-- desaparece la necesidad de trocear el grupo por el eslabon ajeno.
--
-- La no-negatividad se conserva: dentro de cada grupo la entrada precede a
-- todas sus ventas, asi que la aportacion neta de esta fase nunca es negativa
-- en ningun instante, y el saldo real solo puede quedar por ENCIMA del que
-- habia sin ella.
CREATE TEMP TABLE f3_kar AS
WITH fusion AS (
    SELECT m.variante_id AS v, m.bodega_id AS b, m.ts,
           current_setting('retailmind.base')::bigint + m.mov_seq AS ord,
           m.factor * m.cantidad AS q, m.mov_seq
    FROM f3_mov m
    UNION ALL
    SELECT p.v, p.b, p.ts, p.id, p.q, NULL::bigint
    FROM f3_prev p
    WHERE EXISTS (SELECT 1 FROM f3_grp g WHERE g.variante_id = p.v AND g.bodega_id = p.b)
),
acum AS (
    SELECT f.*, sum(f.q) OVER (PARTITION BY f.v, f.b ORDER BY f.ts, f.ord
                               ROWS UNBOUNDED PRECEDING) AS cum
    FROM fusion f
)
SELECT mv.*,
       (COALESCE(b0.s, 0) + a.cum - mv.factor * mv.cantidad)::int AS stock_anterior,
       (COALESCE(b0.s, 0) + a.cum)::int                           AS stock_nuevo
FROM f3_mov mv
JOIN acum a ON a.mov_seq = mv.mov_seq
LEFT JOIN f3_base0 b0 ON b0.v = mv.variante_id AND b0.b = mv.bodega_id;
CREATE INDEX ON f3_kar(mov_seq);
CREATE INDEX ON f3_kar(tipo, variante_id, bodega_id, ts);

-- GUARDIA DURA, antes de escribir. La cadena se comprueba FUSIONADA con lo que
-- ya existe: comprobar solo las filas nuevas fue el error que en la Fase 1
-- marco 87 roturas que no lo eran.
DO $guardia_kardex$
DECLARE v_neg bigint; v_rotos bigint; v_resto bigint; v_base bigint;
BEGIN
    v_base := current_setting('retailmind.base')::bigint;

    SELECT count(*) INTO v_neg FROM f3_kar WHERE stock_anterior < 0 OR stock_nuevo < 0;
    IF v_neg > 0 THEN RAISE EXCEPTION 'ABORTA: % movimientos dejarian negativo.', v_neg; END IF;

    SELECT count(*) INTO v_resto FROM (
        SELECT variante_id, bodega_id, sum(factor*cantidad) AS neto FROM f3_kar GROUP BY 1,2) z
    WHERE neto <> 0;
    IF v_resto > 0 THEN
        RAISE EXCEPTION 'ABORTA: % posiciones no vuelven a su saldo.', v_resto;
    END IF;

    -- Cadena fusionada: lo existente MAS lo planeado, leido por (fecha, id).
    SELECT count(*) INTO v_rotos FROM (
        SELECT lag(sn) OVER w AS prev, sa, row_number() OVER w AS rn
        FROM (
            SELECT mi.producto_variante_id v, mi.bodega_id b, mi.fecha_creacion ts, mi.id,
                   mi.stock_anterior sa, mi.stock_nuevo sn
            FROM movimiento_inventario mi
            WHERE mi.producto_variante_id IN (SELECT DISTINCT variante_id FROM f3_kar)
            UNION ALL
            SELECT variante_id, bodega_id, ts, v_base + mov_seq, stock_anterior, stock_nuevo
            FROM f3_kar
        ) u WINDOW w AS (PARTITION BY v, b ORDER BY ts, id)) z
    WHERE (rn > 1 AND prev <> sa) OR (rn = 1 AND sa <> 0);
    IF v_rotos > 0 THEN
        RAISE EXCEPTION 'ABORTA: % enlaces rotos en la cadena FUSIONADA.', v_rotos;
    END IF;

    RAISE NOTICE 'Plan de kardex OK: 0 negativos, 0 residuos, 0 enlaces rotos.';
END
$guardia_kardex$;

\echo '--- 4. Plan de kardex construido y verificado: OK'

-- ════════════════════════════════════════════════════════════════════════════
-- 5. PLAN DE COMPRA + GUARDIA DE NUMEROS DE DOCUMENTO
-- ════════════════════════════════════════════════════════════════════════════
CREATE TEMP TABLE f3_entp AS
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
FROM f3_kar k WHERE k.tipo = 1;

CREATE TEMP TABLE f3_ocl AS
WITH agg AS (
    SELECT proveedor_id, bodega_id, dia, variante_id,
           sum(cantidad)::int AS cantidad, min(ts) AS ts, max(costo) AS costo
    FROM f3_entp GROUP BY 1, 2, 3, 4
),
numerado AS (
    SELECT a.*, (row_number() OVER (PARTITION BY a.proveedor_id, a.bodega_id, a.dia
                                    ORDER BY a.variante_id) - 1) / 25 AS chunk
    FROM agg a
),
conoc AS (
    SELECT n.*, dense_rank() OVER (ORDER BY n.proveedor_id, n.bodega_id, n.dia, n.chunk) AS oc_seq,
           row_number() OVER (ORDER BY n.proveedor_id, n.bodega_id, n.dia, n.chunk, n.variante_id) AS ocd_seq
    FROM numerado n
)
SELECT c.*, dense_rank() OVER (PARTITION BY c.dia ORDER BY c.oc_seq) AS seq_dia FROM conoc c;
CREATE INDEX ON f3_ocl(oc_seq);

CREATE TEMP TABLE f3_entoc AS
SELECT e.variante_id, e.bodega_id, e.ts, l.oc_seq
FROM f3_entp e
JOIN f3_ocl l ON l.proveedor_id = e.proveedor_id AND l.bodega_id = e.bodega_id
             AND l.dia = e.dia AND l.variante_id = e.variante_id;
CREATE INDEX ON f3_entoc(variante_id, bodega_id, ts);

DO $guardia_numeros$
DECLARE v_max int; v_choque bigint;
        v_base  bigint := current_setting('retailmind.base')::bigint;
        v_banda int    := current_setting('retailmind.banda')::int;
BEGIN
    -- La secuencia diaria vive en los CUATRO ultimos digitos; los tres de
    -- delante identifican el bloque (930..939). Ver la cabecera.
    SELECT max(seq_dia) INTO v_max FROM f3_ocl;
    IF v_max >= 10000 THEN
        RAISE EXCEPTION 'ABORTA: % documentos en un dia no caben en la banda del bloque.', v_max;
    END IF;

    -- `id < v_base` excluye las filas de ESTE bloque: sin ese filtro el guardia
    -- se dispara contra si mismo y deja la valvula de reanudacion inservible
    -- (medido en la Fase 2: 243.919 «choques» que eran sus propios numeros).
    SELECT count(*) INTO v_choque FROM (
        SELECT oc.numero AS n FROM orden_compra oc
        JOIN (SELECT DISTINCT 'OC-' || to_char(dia - 5,'YYYYMMDD') || '-' ||
                     lpad((v_banda * 10000 + seq_dia)::text, 7, '0') AS n FROM f3_ocl) g ON g.n = oc.numero
        WHERE oc.id < v_base
        UNION ALL
        SELECT rm.numero FROM recepcion_mercancia rm
        JOIN (SELECT DISTINCT 'RM-' || to_char(dia,'YYYYMMDD') || '-' ||
                     lpad((v_banda * 10000 + seq_dia)::text, 7, '0') AS n FROM f3_ocl) g ON g.n = rm.numero
        WHERE rm.id < v_base
        UNION ALL
        SELECT fc.numero_factura FROM factura_compra fc
        JOIN (SELECT DISTINCT 'FC-' || to_char(dia + 1,'YYYYMMDD') || '-' ||
                     lpad((v_banda * 10000 + seq_dia)::text, 7, '0') AS n FROM f3_ocl) g ON g.n = fc.numero_factura
        WHERE fc.id < v_base
        UNION ALL
        SELECT p.numero FROM pedido p
        JOIN (SELECT DISTINCT numero AS n FROM f3_ped) g ON g.n = p.numero WHERE p.id < v_base
        UNION ALL
        SELECT fv.numero FROM factura_venta fv
        JOIN (SELECT DISTINCT replace(numero,'PED-','FV-') AS n FROM f3_ped) g ON g.n = fv.numero
        WHERE fv.id < v_base
    ) z;
    IF v_choque > 0 THEN
        RAISE EXCEPTION 'ABORTA: % numeros de documento CHOCAN con los existentes.', v_choque;
    END IF;
    RAISE NOTICE 'Numeros de documento: 0 choques. Maximo por dia: % (tope 99.999).', v_max;
END
$guardia_numeros$;

\echo '--- 5. Plan de compras y numeros verificados: OK'

-- ════════════════════════════════════════════════════════════════════════════
-- 6. LA CARGA, EN LOTES DE 25.000 PEDIDOS CON COMMIT
-- ════════════════════════════════════════════════════════════════════════════
CREATE OR REPLACE PROCEDURE pg_temp.cargar()
LANGUAGE plpgsql AS $carga$
DECLARE v_lote int; v_ini timestamptz; v_n bigint; v_tot int;
        v_base bigint := current_setting('retailmind.base')::bigint;
BEGIN
SELECT max(lote) INTO v_tot FROM f3_ped;
FOR v_lote IN 1..v_tot LOOP
    v_ini := clock_timestamp();
    INSERT INTO pedido (id, numero, cliente_id, estado_pedido_id, moneda_id, metodo_envio_id,
                        direccion_envio_id, direccion_facturacion_id, canal,
                        subtotal, monto_descuento, monto_impuesto, costo_envio,
                        fecha_pedido, fecha_creacion, transportista_id, vendedor_id)
    OVERRIDING SYSTEM VALUE
    SELECT p.id, p.numero, p.cliente_id, p.estado_id, 1, p.metodo_envio_id,
           p.direccion_id, p.direccion_id, p.canal, 0, 0, 0, p.costo_envio,
           p.fecha_pedido, p.fecha_pedido, p.transportista_id, p.vendedor_id
    FROM f3_ped p WHERE p.lote = v_lote ON CONFLICT (id) DO NOTHING;
    GET DIAGNOSTICS v_n = ROW_COUNT; CALL pg_temp.medir('pedido', v_lote, v_ini, v_n);

    v_ini := clock_timestamp();
    INSERT INTO pedido_detalle (id, pedido_id, producto_variante_id, nombre_producto, sku,
                                cantidad, precio_unitario, monto_descuento, monto_impuesto, fecha_creacion)
    OVERRIDING SYSTEM VALUE
    SELECT v_base + l.lin_seq, l.pedido_id, l.variante_id,
           left(l.nombre_producto,200), left(l.sku,50),
           l.cantidad, l.precio_unitario, l.monto_descuento, l.monto_impuesto, p.fecha_pedido
    FROM f3_lin l JOIN f3_ped p ON p.id = l.pedido_id
    WHERE l.lote = v_lote ON CONFLICT (id) DO NOTHING;
    GET DIAGNOSTICS v_n = ROW_COUNT; CALL pg_temp.medir('pedido_detalle', v_lote, v_ini, v_n);

    v_ini := clock_timestamp();
    INSERT INTO historial_estado_pedido (id, pedido_id, estado_pedido_id, usuario_id, comentario, fecha_creacion)
    OVERRIDING SYSTEM VALUE
    SELECT v_base + (p.i - 1) * 8 + x.ord, p.id, ep.id,
           CASE x.codigo WHEN 'confirmado' THEN p.vendedor_id WHEN 'pagado' THEN p.vendedor_id
                         WHEN 'facturado' THEN 6::bigint
                         WHEN 'en_preparacion' THEN 9::bigint WHEN 'preparado' THEN 9::bigint
                         WHEN 'despachado' THEN 10::bigint WHEN 'entregado' THEN 10::bigint
                         ELSE NULL END,
           '[FASE3] ' || x.codigo, x.ts
    FROM f3_ped p
    CROSS JOIN LATERAL (VALUES
        ('confirmado', p.fecha_pedido, 1), ('pagado', p.t_pagado, 2),
        ('facturado', p.t_facturado, 3),   ('en_preparacion', p.t_preparacion, 4),
        ('preparado', p.t_preparado, 5),   ('despachado', p.t_despachado, 6),
        ('entregado', p.t_entregado, 7),   ('final', p.t_final, 8)
    ) x(codigo, ts, ord)
    JOIN estado_pedido ep ON ep.codigo = CASE WHEN x.ord = 8 THEN p.estado_codigo ELSE x.codigo END
    WHERE p.lote = v_lote
      AND CASE p.estado_codigo
            WHEN 'cancelado' THEN x.ord IN (1,8) WHEN 'confirmado' THEN x.ord IN (1)
            WHEN 'pagado' THEN x.ord IN (1,2)    WHEN 'facturado' THEN x.ord IN (1,2,3)
            WHEN 'en_preparacion' THEN x.ord IN (1,2,3,4) WHEN 'preparado' THEN x.ord IN (1,2,3,4,5)
            WHEN 'despachado' THEN x.ord IN (1,2,3,4,5,6) WHEN 'entregado' THEN x.ord IN (1,2,3,4,5,6,7)
            WHEN 'no_entregado' THEN x.ord IN (1,2,3,4,5,6,8)
            WHEN 'devuelto' THEN x.ord IN (1,2,3,4,5,6,7,8) END
    ON CONFLICT (id) DO NOTHING;
    GET DIAGNOSTICS v_n = ROW_COUNT; CALL pg_temp.medir('historial_estado_pedido', v_lote, v_ini, v_n);

    v_ini := clock_timestamp();
    INSERT INTO factura_venta (id, numero, pedido_id, cliente_id, moneda_id, razon_social,
                               identificacion, direccion_facturacion, subtotal, monto_descuento,
                               monto_impuesto, total, estado, fecha_emision, fecha_creacion)
    OVERRIDING SYSTEM VALUE
    SELECT p.id, replace(p.numero,'PED-','FV-'), p.id, p.cliente_id, 1,
           left(p.razon_social,200), left(p.numero_identificacion,20),
           (SELECT left(d.calle_principal || ', ' || ci.nombre, 300)
              FROM direccion d JOIN ciudad ci ON ci.id = d.ciudad_id WHERE d.id = p.direccion_id),
           0, 0, 0, 0, 'emitida', p.t_facturado, p.t_facturado
    FROM f3_ped p WHERE p.lote = v_lote AND p.factura ON CONFLICT (id) DO NOTHING;
    GET DIAGNOSTICS v_n = ROW_COUNT; CALL pg_temp.medir('factura_venta', v_lote, v_ini, v_n);

    v_ini := clock_timestamp();
    INSERT INTO factura_venta_detalle (id, factura_venta_id, pedido_detalle_id, producto_variante_id,
                                       descripcion, cantidad, precio_unitario, monto_descuento,
                                       monto_impuesto, fecha_creacion)
    OVERRIDING SYSTEM VALUE
    SELECT v_base + l.lin_seq, l.pedido_id, v_base + l.lin_seq, l.variante_id,
           left(l.nombre_producto,255), l.cantidad, l.precio_unitario,
           l.monto_descuento, l.monto_impuesto, p.t_facturado
    FROM f3_lin l JOIN f3_ped p ON p.id = l.pedido_id
    WHERE l.lote = v_lote AND p.factura ON CONFLICT (id) DO NOTHING;
    GET DIAGNOSTICS v_n = ROW_COUNT; CALL pg_temp.medir('factura_venta_detalle', v_lote, v_ini, v_n);

    v_ini := clock_timestamp();
    INSERT INTO pago (id, pedido_id, metodo_pago_id, moneda_id, monto, estado,
                      referencia_externa, fecha_pago, fecha_creacion)
    OVERRIDING SYSTEM VALUE
    SELECT p.id, p.id,
           CASE WHEN p.canal='tienda' THEN 1 WHEN pg_temp.u('mp',p.i) < 0.62 THEN 3 ELSE 2 END,
           1, ped.total, 'completado',
           'F3-' || lpad(p.i::text, 8, '0'), p.t_pagado, p.t_pagado
    FROM f3_ped p JOIN pedido ped ON ped.id = p.id
    WHERE p.lote = v_lote AND p.cobra AND ped.total > 0 ON CONFLICT (id) DO NOTHING;
    GET DIAGNOSTICS v_n = ROW_COUNT; CALL pg_temp.medir('pago', v_lote, v_ini, v_n);

    v_ini := clock_timestamp();
    INSERT INTO transaccion_pago (id, pago_id, tipo, estado, monto, codigo_autorizacion,
                                  respuesta_pasarela, fecha_creacion)
    OVERRIDING SYSTEM VALUE
    SELECT pg.id, pg.id, 'captura', 'exitosa', pg.monto,
           'AUT' || lpad((pg.id - v_base)::text, 9, '0'),
           jsonb_build_object('origen','FASE3','resultado','aprobado'), pg.fecha_pago
    FROM pago pg JOIN f3_ped p ON p.id = pg.id
    WHERE p.lote = v_lote AND pg.id >= v_base ON CONFLICT (id) DO NOTHING;
    GET DIAGNOSTICS v_n = ROW_COUNT; CALL pg_temp.medir('transaccion_pago', v_lote, v_ini, v_n);

    COMMIT;
END LOOP;
RAISE NOTICE 'Ciclo de venta: % lotes commiteados.', v_tot;
END
$carga$;
CALL pg_temp.cargar();

\echo '--- 6. Ciclo de venta cargado: OK'

-- ════════════════════════════════════════════════════════════════════════════
-- 7. REPOSICION Y KARDEX
-- ════════════════════════════════════════════════════════════════════════════
CREATE OR REPLACE PROCEDURE pg_temp.cargar_repo()
LANGUAGE plpgsql AS $repo$
DECLARE v_ini timestamptz; v_n bigint;
        v_base  bigint := current_setting('retailmind.base')::bigint;
        v_banda int    := current_setting('retailmind.banda')::int;
BEGIN
    v_ini := clock_timestamp();
    INSERT INTO orden_compra (id, numero, proveedor_id, bodega_id, moneda_id, usuario_id, estado,
                              fecha_emision, fecha_entrega_esperada, subtotal, monto_impuesto, total,
                              observacion, fecha_creacion)
    OVERRIDING SYSTEM VALUE
    SELECT v_base + l.oc_seq,
           'OC-' || to_char(min(l.dia)-5,'YYYYMMDD') || '-' || lpad((v_banda * 10000 + min(l.seq_dia))::text,7,'0'),
           min(l.proveedor_id), min(l.bodega_id), 1, 11, 'recibida',
           min(l.dia)-5, min(l.dia), 0, 0, 0, '[FASE3] Reposicion', min(l.ts) - interval '5 days'
    FROM f3_ocl l GROUP BY l.oc_seq ON CONFLICT (id) DO NOTHING;
    GET DIAGNOSTICS v_n = ROW_COUNT; CALL pg_temp.medir('orden_compra', 0, v_ini, v_n);

    v_ini := clock_timestamp();
    INSERT INTO orden_compra_detalle (id, orden_compra_id, producto_variante_id, cantidad,
                                      precio_unitario, monto_impuesto, cantidad_recibida, fecha_creacion)
    OVERRIDING SYSTEM VALUE
    SELECT v_base + l.ocd_seq, v_base + l.oc_seq, l.variante_id, l.cantidad,
           l.costo, round(l.cantidad*l.costo*0.15,2), l.cantidad, l.ts - interval '5 days'
    FROM f3_ocl l ON CONFLICT (id) DO NOTHING;
    GET DIAGNOSTICS v_n = ROW_COUNT; CALL pg_temp.medir('orden_compra_detalle', 0, v_ini, v_n);
    COMMIT;

    v_ini := clock_timestamp();
    INSERT INTO recepcion_mercancia (id, numero, orden_compra_id, bodega_id, usuario_id, estado,
                                     fecha_recepcion, observacion, fecha_creacion)
    OVERRIDING SYSTEM VALUE
    SELECT v_base + l.oc_seq,
           'RM-' || to_char(min(l.dia),'YYYYMMDD') || '-' || lpad((v_banda * 10000 + min(l.seq_dia))::text,7,'0'),
           v_base + l.oc_seq, min(l.bodega_id), 9, 'confirmada',
           min(l.ts), '[FASE3] Recepcion completa', min(l.ts)
    FROM f3_ocl l GROUP BY l.oc_seq ON CONFLICT (id) DO NOTHING;
    GET DIAGNOSTICS v_n = ROW_COUNT; CALL pg_temp.medir('recepcion_mercancia', 0, v_ini, v_n);

    v_ini := clock_timestamp();
    INSERT INTO recepcion_detalle (id, recepcion_mercancia_id, orden_compra_detalle_id,
                                   cantidad_recibida, cantidad_rechazada, fecha_creacion)
    OVERRIDING SYSTEM VALUE
    SELECT v_base + l.ocd_seq, v_base + l.oc_seq, v_base + l.ocd_seq, l.cantidad, 0, l.ts
    FROM f3_ocl l ON CONFLICT (id) DO NOTHING;
    GET DIAGNOSTICS v_n = ROW_COUNT; CALL pg_temp.medir('recepcion_detalle', 0, v_ini, v_n);
    COMMIT;

    v_ini := clock_timestamp();
    INSERT INTO factura_compra (id, proveedor_id, orden_compra_id, moneda_id, numero_factura,
                                fecha_emision, fecha_vencimiento, subtotal, monto_impuesto, total,
                                estado, registrado_por, fecha_creacion)
    OVERRIDING SYSTEM VALUE
    SELECT v_base + l.oc_seq, min(l.proveedor_id), v_base + l.oc_seq, 1,
           'FC-' || to_char(min(l.dia)+1,'YYYYMMDD') || '-' || lpad((v_banda * 10000 + min(l.seq_dia))::text,7,'0'),
           min(l.dia)+1,
           min(l.dia)+1 + (SELECT p.dias_credito FROM proveedor p WHERE p.id = min(l.proveedor_id)),
           0, 0, 0, 'pagada', 11, min(l.ts) + interval '1 day'
    FROM f3_ocl l GROUP BY l.oc_seq ON CONFLICT (id) DO NOTHING;
    GET DIAGNOSTICS v_n = ROW_COUNT; CALL pg_temp.medir('factura_compra', 0, v_ini, v_n);

    v_ini := clock_timestamp();
    INSERT INTO factura_compra_detalle (id, factura_compra_id, producto_variante_id, cantidad,
                                        precio_unitario, monto_impuesto, fecha_creacion)
    OVERRIDING SYSTEM VALUE
    SELECT v_base + l.ocd_seq, v_base + l.oc_seq, l.variante_id, l.cantidad,
           l.costo, round(l.cantidad*l.costo*0.15,2), l.ts + interval '1 day'
    FROM f3_ocl l ON CONFLICT (id) DO NOTHING;
    GET DIAGNOSTICS v_n = ROW_COUNT; CALL pg_temp.medir('factura_compra_detalle', 0, v_ini, v_n);
    COMMIT;

    v_ini := clock_timestamp();
    INSERT INTO cuenta_por_pagar (id, factura_compra_id, proveedor_id, monto_original,
                                  saldo_pendiente, fecha_vencimiento, estado, fecha_creacion)
    OVERRIDING SYSTEM VALUE
    SELECT fc.id, fc.id, fc.proveedor_id, fc.total, 0, fc.fecha_vencimiento, 'pagada', fc.fecha_creacion
    FROM factura_compra fc WHERE fc.id >= v_base AND fc.id < v_base + 100000000
    ON CONFLICT (id) DO NOTHING;
    GET DIAGNOSTICS v_n = ROW_COUNT; CALL pg_temp.medir('cuenta_por_pagar', 0, v_ini, v_n);

    v_ini := clock_timestamp();
    INSERT INTO pago_proveedor (id, cuenta_por_pagar_id, metodo_pago_id, usuario_id,
                                monto, fecha_pago, referencia, observacion, fecha_creacion)
    OVERRIDING SYSTEM VALUE
    SELECT c.id, c.id, 2, 11, c.monto_original, c.fecha_vencimiento,
           'TR-F3-' || lpad((c.id - v_base)::text, 8, '0'), '[FASE3] Pago de reposicion',
           c.fecha_vencimiento::timestamptz
    FROM cuenta_por_pagar c WHERE c.id >= v_base AND c.id < v_base + 100000000 AND c.monto_original > 0
    ON CONFLICT (id) DO NOTHING;
    GET DIAGNOSTICS v_n = ROW_COUNT; CALL pg_temp.medir('pago_proveedor', 0, v_ini, v_n);
    COMMIT;
END
$repo$;
CALL pg_temp.cargar_repo();

CREATE OR REPLACE PROCEDURE pg_temp.cargar_kardex()
LANGUAGE plpgsql AS $kar$
DECLARE v_b int; v_ini timestamptz; v_n bigint; v_i int := 0;
        v_base bigint := current_setting('retailmind.base')::bigint;
BEGIN
-- Se recorren los bimestres QUE HAY, no un rango calculado a mano: la entrada
-- de un bimestre se fecha un dia antes de su primera salida y puede caer en el
-- bimestre anterior, y un filtro por resta dejaria esas filas fuera de todos
-- los lotes sin error y sin que nada avisara.
FOR v_b IN SELECT DISTINCT bim_lote FROM f3_kar ORDER BY bim_lote LOOP
    v_i := v_i + 1; v_ini := clock_timestamp();
    INSERT INTO movimiento_inventario (id, producto_variante_id, bodega_id, tipo_movimiento_id,
                                       usuario_id, cantidad, stock_anterior, stock_nuevo,
                                       costo_unitario, referencia_tipo, referencia_id,
                                       observacion, fecha_creacion)
    OVERRIDING SYSTEM VALUE
    SELECT v_base + k.mov_seq, k.variante_id, k.bodega_id, k.tipo, 9,
           k.cantidad, k.stock_anterior, k.stock_nuevo, k.costo,
           CASE k.tipo WHEN 1 THEN 'recepcion_mercancia' ELSE 'pedido' END,
           CASE k.tipo WHEN 1 THEN (SELECT v_base + o.oc_seq FROM f3_entoc o
                                     WHERE o.variante_id = k.variante_id AND o.bodega_id = k.bodega_id
                                       AND o.ts = k.ts LIMIT 1)
                       ELSE k.ref_id END,
           CASE k.tipo WHEN 1 THEN '[FASE3] Reposicion' ELSE '[FASE3] Salida por venta' END,
           k.ts                       -- fecha EXPLICITA, nunca el DEFAULT
    FROM f3_kar k WHERE k.bim_lote = v_b ON CONFLICT (id) DO NOTHING;
    GET DIAGNOSTICS v_n = ROW_COUNT; CALL pg_temp.medir('movimiento_inventario', v_i, v_ini, v_n);
    COMMIT;
END LOOP;
RAISE NOTICE 'Kardex: % bimestres commiteados.', v_i;
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
        v_base bigint := current_setting('retailmind.base')::bigint;
        v_bloque text := current_setting('retailmind.bloque');
BEGIN
    FOREACH t IN ARRAY ARRAY['pedido','pedido_detalle','historial_estado_pedido','factura_venta',
                             'factura_venta_detalle','pago','transaccion_pago','movimiento_inventario',
                             'orden_compra','orden_compra_detalle','recepcion_mercancia',
                             'recepcion_detalle','factura_compra','factura_compra_detalle',
                             'cuenta_por_pagar','pago_proveedor']
    LOOP
        EXECUTE format('SELECT count(*), COALESCE(max(id), %s) FROM public.%I WHERE id >= %s AND id < %s',
                       v_base + 1, t, v_base, v_base + 100000000) INTO v_n, v_mx;
        PERFORM fn_carga_registrar('fase3', '100_fase3_' || v_bloque, t, v_base + 1, v_mx, v_n, NULL);
    END LOOP;
END
$bit$;
COMMIT;

\echo ''
\echo '=== MEDICIONES DEL BLOQUE =================================================='
SELECT paso, sum(filas) filas, round(sum(extract(epoch FROM (fin-inicio)))::numeric,2) seg,
       round((sum(filas)/NULLIF(sum(extract(epoch FROM (fin-inicio))),0))::numeric,0) filas_seg
FROM f3_medicion GROUP BY paso ORDER BY 3 DESC;

\echo ''
SELECT :'bloque' AS bloque,
       (SELECT count(*) FROM pedido WHERE id>=:base AND id<(:base::bigint + 100000000)) pedidos,
       (SELECT count(*) FROM pedido_detalle WHERE id>=:base AND id<(:base::bigint + 100000000)) lineas,
       (SELECT count(*) FROM movimiento_inventario WHERE id>=:base AND id<(:base::bigint + 100000000)) movimientos,
       (SELECT count(*) FROM orden_compra WHERE id>=:base AND id<(:base::bigint + 100000000)) ordenes,
       (SELECT round(avg(total),2) FROM pedido WHERE id>=:base AND id<(:base::bigint + 100000000)) ticket;
