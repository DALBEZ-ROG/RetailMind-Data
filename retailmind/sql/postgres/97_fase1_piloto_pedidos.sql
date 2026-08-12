-- ============================================================================
-- 97_fase1_piloto_pedidos.sql — RetailMind · FASE 1 de la carga masiva:
--                               el PILOTO de 10.000 pedidos (2026-08-11)
--
--   docker compose exec -T postgres psql -U postgres -d retailmind \
--       -v ON_ERROR_STOP=1 < retailmind/sql/postgres/97_fase1_piloto_pedidos.sql
--
--   Reversion: 99_revert_fase1.sql   (ensayo en seco con -v ensayo=1)
--
-- ---------------------------------------------------------------------------
-- QUE ES ESTO, Y QUE NO ES
-- ---------------------------------------------------------------------------
-- No es una carga de volumen: 10.000 pedidos no prueban nada que 4.083 no
-- probaran ya. Es la prueba del METODO a una escala en la que un error todavia
-- es barato de deshacer. Lo que se valida aqui es lo que va a repetirse tres
-- millones de veces: el orden de escritura, el encadenamiento del kardex, el
-- cuadre de la factura y el paso por el ETL.
--
-- ---------------------------------------------------------------------------
-- EL TRAMO DE IDs: 1.000.000.000, Y POR QUE ESE Y NO OTRO
-- ---------------------------------------------------------------------------
-- La Fase 0 (script 92) fijo el mecanismo: procedencia por RANGO DE IDs
-- RESERVADO, no columna nueva ni marcador de texto. Esta fase lo hereda y
-- reserva su propio tramo, distinguible del de la Fase 0 de un vistazo:
--
--     Fase 0 -> [   900.000.000 , 1.000.000.000 )    (empieza por 9)
--     Fase 1 -> [ 1.000.000.000 , 1.100.000.000 )    (empieza por 10)
--
-- Cien millones por fase dejan sitio a 32 fases mas por debajo del techo real,
-- que NO es el `bigint` de PostgreSQL.
--
-- EL TECHO REAL ES **UInt32** Y ESTA EN CLICKHOUSE. La leccion que la Fase 0
-- aprendio a golpes —«antes de reservar un rango hay que mirar el tipo MAS
-- ESTRECHO por el que ese id va a pasar, y ese tipo puede estar en otro
-- motor»— se aplico ANTES de escribir, revisando el DDL de las 21 tablas del
-- almacen. Tipos comprobados, uno por uno:
--
--   UInt32 (tope 4.294.967.295) — es el que manda:
--       pedido_id             fact_pedido, fact_venta_linea, fact_envio,
--                             fact_devolucion, fact_ticket, fact_resena
--       cliente_id            dim_cliente, fact_pedido, fact_venta_linea,
--                             fact_alerta_cliente
--       producto_variante_id  dim_producto, fact_venta_linea,
--                             fact_movimiento_inventario, fact_stock_mensual
--       documento_id          fact_flujo_caja  <- ES `pago.id`
--       contraparte_id        fact_flujo_caja  <- ES `cliente.id`
--       orden_compra_id       fact_orden_compra, fact_compra_linea
--       envio_id              fact_envio, fact_novedad_envio
--   UInt64 (holgado):
--       pedido_detalle_id, movimiento_id, referencia_id, devolucion_detalle_id
--   UInt16 (tope 65.535) — NINGUNO recibe un id de esta fase:
--       categoria_id y proveedor_id (por eso la Fase 0 uso la base 60.000;
--       esta fase NO crea categorias ni proveedores, asi que no la necesita)
--       `lineas` y `movimientos_mes` son CONTADORES, no ids: 1-5 y decenas.
--
-- 1.000.000.000 + 10.000 pedidos deja 3.294.957.295 ids libres por debajo del
-- UInt32: espacio de sobra para los 3.000.000 de pedidos del plan completo.
--
-- ---------------------------------------------------------------------------
-- EL PROBLEMA CENTRAL DE ESTA FASE: VENDER EN 2025 UN STOCK QUE ENTRO EN 2026
-- ---------------------------------------------------------------------------
-- La Fase 0 fecho las existencias el 2026-08-09 y lo dejo escrito en
-- `carga_fase_parametro`: «arranque_ventas_futuras = 2026-09-01». Este piloto
-- vende en 2025, o sea VEINTE MESES ANTES de que esa mercancia entrara. Un
-- pedido de 2025 no puede consumir stock de 2026: la cadena del kardex se lee
-- por `(fecha_creacion, id)` y el saldo saldria cronologicamente imposible.
--
-- Se evaluaron tres salidas:
--
--   (a) MOVER LA FECHA DEL STOCK INICIAL a 2024-12. DESCARTADA. Arrastra las
--       ordenes, recepciones, facturas y cuentas por pagar de la Fase 0 —que
--       se emitieron con la fecha de hoy y cuyo credito no ha vencido— a un
--       pasado en el que todas estarian vencidas hace mas de un ano. Se
--       arregla el kardex y se rompe la cartera.
--
--   (b) REENCADENAR LOS 10.000 MOVIMIENTOS DE APERTURA de la Fase 0 para que
--       arranquen del saldo que dejara 2025. DESCARTADA, y esta es la razon de
--       fondo: reencadenar es O(cadena entera) y hay que repetirlo en CADA
--       fase. A 7,6 millones de movimientos eso deja de ser una carga y pasa a
--       ser una migracion. Un metodo que no escala no es el metodo.
--
--   (c) REPOSICION PREVIA POR POSICION, CON CIERRE A CERO.        <- ELEGIDA
--
-- COMO FUNCIONA (c): cada unidad que este piloto vende la COMPRA ANTES, en la
-- misma posicion (variante, bodega) y con su documento detras. Por posicion,
-- lo comprado en 2025 es EXACTAMENTE lo vendido en 2025, asi que el saldo
-- vuelve a CERO antes del 2026-08-09 y el movimiento de apertura de la Fase 0
-- —`stock_anterior = 0`— SIGUE SIENDO CORRECTO SIN TOCARLO.
--
-- Las tres consecuencias, que es lo que hace que el metodo escale:
--   · `inventario.stock_actual` NO se escribe ni una vez. Las 426.722 unidades
--     de hoy son las mismas al terminar. V2 no puede descuadrarse.
--   · NI UNA FILA PREEXISTENTE cambia de valor. El encadenamiento de las 1.406
--     posiciones vivas no se recalcula, porque cada bloque que esta fase
--     inserta suma cero.
--   · El coste es O(1) por linea: ni lookahead, ni reserva global, ni un
--     segundo recorrido. Es lo unico de aqui que se puede multiplicar por 300.
--
-- DOS RITMOS DE REPOSICION, porque las posiciones no son iguales:
--
--   · POSICIONES DE LA FASE 0 (variante >= 900.000.000) — el 96,8 % de las
--     lineas. En 2025 estan VIRGENES: su unico movimiento es la apertura de
--     2026-08-09. Como no hay nada con lo que interferir, la reposicion va
--     POR BIMESTRE: una entrada que cubre la demanda de la posicion en esos
--     dos meses, fechada un dia antes de su primera venta. El saldo sube,
--     baja durante ~60 dias y cierra en cero. Es reposicion de verdad, con
--     cobertura real, no un apunte.
--
--   · POSICIONES HISTORICAS (variante < 900.000.000) — el 3,2 % que exige el
--     peso `historico` de `carga_fase_parametro`. Aqui SI hay cadena viva:
--     8.505 movimientos en 2025. Una entrada por bimestre dejaria el saldo
--     elevado durante semanas y desplazaria el `stock_anterior` de cada
--     movimiento existente que cayera dentro. Asi que la reposicion va
--     EMPAREJADA: una entrada por linea, de la cantidad exacta, colocada
--     DESPUES del ultimo movimiento existente anterior a la venta. El par
--     suma cero entre dos movimientos consecutivos de la cadena original, de
--     modo que ningun eslabon preexistente se entera. Es cross-docking, y se
--     declara como tal.
--
-- LO QUE ESTE METODO CUESTA, DICHO SIN ADORNOS:
--   1. El piloto no deja stock al cerrar 2025: compra lo que vende. Las
--      posiciones quedan a cero entre su ultima venta y la apertura de la
--      Fase 0. `fact_stock_mensual` lo mostrara, y es cierto, no un fallo.
--   2. Las variantes de la Fase 0 se crearon el 2026-08-08 y aqui se venden en
--      2025. `producto_variante.fecha_creacion` queda por detras de la primera
--      venta. Es un anacronismo REAL y NO se corrige, porque corregirlo seria
--      reescribir un maestro de la Fase 0. Ninguna consulta del sistema ni del
--      almacen lee esa columna (`dim_producto` no la carga), asi que el efecto
--      es cosmetico; queda declarado y no escondido.
--
-- ---------------------------------------------------------------------------
-- LA FECHA DEL KARDEX, EXPLICITA — la mitigacion de C-2
-- ---------------------------------------------------------------------------
-- `fecha_creacion` se ESCRIBE en los 39.000 movimientos. Nunca el DEFAULT.
-- Y ademas —cinturon y tirantes— el `id` se asigna con un `row_number()`
-- ORDENADO POR ESA MISMA FECHA, de forma que el orden `(fecha_creacion, id)`
-- que lee la verificacion coincide con el orden en que se calculo el saldo.
-- El trigger `trg_kardex_ecuacion_ins` NO sirve para esto: valida la FILA
-- (stock_nuevo = stock_anterior +- cantidad) y no el ENLACE, y ya se demostro
-- que un lote entero puede pasarlo al 100 % con la cadena rota.
--
-- ---------------------------------------------------------------------------
-- IDEMPOTENTE (ON CONFLICT DO NOTHING) · POR LOTES CON COMMIT
-- ============================================================================
\set ON_ERROR_STOP on
\timing on

\echo ''
\echo '=== FASE 1 · PILOTO DE 10.000 PEDIDOS ======================================'

-- ════════════════════════════════════════════════════════════════════════════
-- 0. AZAR DETERMINISTA, GUARDIA DEL TRAMO Y PARAMETROS DE LA FASE 0
-- ════════════════════════════════════════════════════════════════════════════

-- Un unico generador para todo el script. Mismo `k` y mismo `i` -> mismo
-- numero, siempre: la carga es reproducible bit a bit.
CREATE OR REPLACE FUNCTION pg_temp.u(k text, i bigint) RETURNS numeric
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS
$$ SELECT ((('x' || substr(md5(k || i::text), 1, 7))::bit(28)::int) % 1000000)::numeric
          / 1000000.0 $$;

-- El guardia se salta con `-v reanudar=1`, y esa valvula no es un atajo: a tres
-- millones de pedidos una carga SE VA A CAER a mitad alguna vez, y exigir una
-- reversion completa para reintentar convierte un tropiezo en una tarde
-- perdida. Reanudar es seguro porque cada INSERT lleva ON CONFLICT DO NOTHING
-- y los datos se derivan de hashes: repetir el script produce exactamente las
-- mismas filas, asi que lo ya escrito se salta y solo entra lo que falta.
\if :{?reanudar}
\else
  \set reanudar 0
\endif
-- psql NO sustituye variables dentro de una cadena con comillas de dolar, asi
-- que el valor entra por un GUC de sesion en vez de por interpolacion.
SELECT set_config('retailmind.reanudar', :'reanudar', false);

DO $guardia$
DECLARE t text; n bigint; sucias text := '';
        v_reanudar int := current_setting('retailmind.reanudar', true)::int;
BEGIN
    FOREACH t IN ARRAY ARRAY['pedido','pedido_detalle','historial_estado_pedido',
                             'factura_venta','factura_venta_detalle','pago','transaccion_pago',
                             'movimiento_inventario','orden_compra','orden_compra_detalle',
                             'recepcion_mercancia','recepcion_detalle','factura_compra',
                             'factura_compra_detalle','cuenta_por_pagar']
    LOOP
        EXECUTE format('SELECT count(*) FROM public.%I WHERE id >= 1000000000', t) INTO n;
        IF n > 0 THEN sucias := sucias || format('  %s: %s filas%s', t, n, E'\n'); END IF;
    END LOOP;
    IF sucias <> '' AND v_reanudar = 0 THEN
        RAISE EXCEPTION 'El tramo de la Fase 1 YA tiene filas. Revierte con 99_revert_fase1.sql, '
                        'o reanuda con -v reanudar=1 si la carga anterior se corto a medias:%',
                        E'\n' || sucias;
    ELSIF sucias <> '' THEN
        RAISE NOTICE 'REANUDANDO sobre una carga incompleta:%', E'\n' || sucias;
    ELSE
        RAISE NOTICE 'Guardia OK: el tramo [1.000.000.000, 1.100.000.000) esta vacio en las 15 tablas.';
    END IF;
END
$guardia$;

-- ── Los pesos de demanda se LEEN de la tabla, no se copian ──────────────────
CREATE TEMP TABLE f1_peso AS
SELECT m[1] AS banda, m[2]::numeric AS pct
FROM carga_fase_parametro p,
     LATERAL regexp_matches(p.valor, '([A-Za-z0-9]+)=([0-9.]+)%', 'g') AS m
WHERE p.fase = 'fase0' AND p.clave = 'pesos_demanda_lineas';

DO $chk$
DECLARE v_n int; v_sum numeric; v_faltan text;
BEGIN
    SELECT count(*), sum(pct) INTO v_n, v_sum FROM f1_peso;
    SELECT string_agg(b, ', ') INTO v_faltan
    FROM unnest(ARRAY['L1','L2','L3','L4','historico']) b
    WHERE b NOT IN (SELECT banda FROM f1_peso);
    IF v_faltan IS NOT NULL THEN
        RAISE EXCEPTION 'carga_fase_parametro no trae las bandas: %', v_faltan;
    END IF;
    IF abs(v_sum - 100) > 0.01 THEN
        RAISE EXCEPTION 'Los pesos de demanda suman % y no 100.', v_sum;
    END IF;
    RAISE NOTICE 'Pesos leidos de carga_fase_parametro: % bandas, suman %.', v_n, v_sum;
END
$chk$;

-- Tramos acumulados de las bandas, para el sorteo de cada linea.
CREATE TEMP TABLE f1_banda AS
SELECT banda,
       (sum(pct) OVER (ORDER BY banda) - pct) / 100.0 AS lo,
        sum(pct) OVER (ORDER BY banda)        / 100.0 AS hi
FROM f1_peso;

-- ════════════════════════════════════════════════════════════════════════════
-- 1. PERFILES: se DERIVAN de la serie que ya existe, no se inventan
-- ════════════════════════════════════════════════════════════════════════════
-- La estacionalidad, la hora del dia y el coste de envio salen de los 4.083
-- pedidos que ya estan cargados. El motivo no es pereza: el nivel estrategico
-- tiene un modelo de prevision (Fase E2) que descompone la serie mensual, y un
-- piloto con una estacionalidad propia introduciria un escalon artificial en
-- 2025 que ese modelo leeria como senal.

-- Estacionalidad mensual observada en 2025.
CREATE TEMP TABLE f1_mes AS
SELECT extract(month FROM fecha_pedido AT TIME ZONE 'America/Guayaquil')::int AS m,
       count(*)::numeric AS w
FROM pedido
WHERE id < 1000000000
  AND fecha_pedido >= timestamptz '2025-01-01 00:00:00-05'
  AND fecha_pedido <  timestamptz '2026-01-01 00:00:00-05'
GROUP BY 1;

-- Calendario de 2025 con su peso diario y su tramo acumulado.
-- DIA DE LA SEMANA: PLANO, a proposito. Los 4.083 pedidos existentes se
-- reparten 551/575/590/601/579/583/604 entre lunes y domingo — un +/-4 % que
-- no es un ciclo semanal. Inventarle uno al piloto lo haria distinguible de la
-- serie base con un simple GROUP BY, que es exactamente lo que no se quiere.
CREATE TEMP TABLE f1_dia AS
WITH d AS (
    SELECT generate_series(date '2025-01-01', date '2025-12-31', interval '1 day')::date AS dia
),
p AS (
    SELECT d.dia, m.w / count(*) OVER (PARTITION BY m.m) AS peso
    FROM d JOIN f1_mes m ON m.m = extract(month FROM d.dia)::int
)
SELECT dia, peso,
       (sum(peso) OVER (ORDER BY dia) - peso) / sum(peso) OVER () AS lo,
        sum(peso) OVER (ORDER BY dia)         / sum(peso) OVER () AS hi
FROM p;
CREATE INDEX ON f1_dia(lo, hi);

-- Hora del dia observada. Se descarta la cola residual (16 pedidos sueltos de
-- madrugada): el piloto opera de 08 a 18, como el 99,6 % de la base.
CREATE TEMP TABLE f1_hora AS
WITH h AS (
    SELECT extract(hour FROM fecha_pedido AT TIME ZONE 'America/Guayaquil')::int AS hh,
           count(*)::numeric AS w
    FROM pedido WHERE id < 1000000000
    GROUP BY 1 HAVING count(*) >= 50
)
SELECT hh,
       (sum(w) OVER (ORDER BY hh) - w) / sum(w) OVER () AS lo,
        sum(w) OVER (ORDER BY hh)      / sum(w) OVER () AS hi
FROM h;

-- Coste de envio: remuestreo del conjunto real (3.026 valores, $2,59-$57,21).
-- Reconstruir la tarifa (base + peso x zona) daria una distribucion propia; el
-- remuestreo garantiza que la del piloto es la misma que la de la base.
CREATE TEMP TABLE f1_env AS
SELECT row_number() OVER (ORDER BY id) AS rn, costo_envio AS costo
FROM pedido WHERE id < 1000000000 AND costo_envio > 0;

-- Canal, coherente con lo actual: web 54,20 % · tienda 25,23 % · telefono 20,57 %.
-- Los internos (tienda/telefono) llevan vendedor; los web NO, porque el autor
-- del checkout es el cliente (regla del script 42).
CREATE TEMP TABLE f1_canal AS
WITH c(codigo, w, ord) AS (VALUES
    ('web', 54.20::numeric, 1), ('tienda', 25.23::numeric, 2), ('telefono', 20.57::numeric, 3))
SELECT codigo, ord,
       (sum(w) OVER (ORDER BY ord) - w) / sum(w) OVER () AS lo,
        sum(w) OVER (ORDER BY ord)      / sum(w) OVER () AS hi
FROM c;

-- Estado final. Proporciones tomadas de los 4.083 pedidos vivos. NO todos
-- 'entregado': la cola de estados intermedios y los tres desenlaces malos
-- (cancelado, no_entregado, devuelto) son el 9,5 % y son los que hacen que los
-- informes de la cartera y de la ultima milla tengan algo que contar.
CREATE TEMP TABLE f1_estado AS
WITH e(codigo, w, ord) AS (VALUES
    ('entregado',     87.99::numeric,  1), ('cancelado',      3.89::numeric,  2),
    ('no_entregado',   2.96::numeric,  3), ('devuelto',       2.65::numeric,  4),
    ('pagado',         0.51::numeric,  5), ('facturado',      0.51::numeric,  6),
    ('confirmado',     0.44::numeric,  7), ('despachado',     0.39::numeric,  8),
    ('en_preparacion', 0.37::numeric,  9), ('preparado',      0.29::numeric, 10))
SELECT e.codigo, ep.id AS estado_id, e.ord,
       (sum(e.w) OVER (ORDER BY e.ord) - e.w) / sum(e.w) OVER () AS lo,
        sum(e.w) OVER (ORDER BY e.ord)        / sum(e.w) OVER () AS hi,
       -- El ciclo que le corresponde a cada estado. Es una decision de
       -- COHERENCIA, no una copia: en los datos actuales hay 44 lineas en
       -- preparacion sin kardex y 19 en confirmado/facturado con el, ruido del
       -- seed viejo que este piloto no reproduce.
       (e.codigo IN ('en_preparacion','preparado','despachado','entregado',
                     'devuelto','no_entregado'))                       AS mueve_stock,
       (e.codigo IN ('facturado','en_preparacion','preparado','despachado',
                     'entregado','devuelto','no_entregado'))           AS factura,
       (e.codigo NOT IN ('cancelado','confirmado'))                    AS cobra
FROM e JOIN estado_pedido ep ON ep.codigo = e.codigo;

-- Clientes elegibles: solo los que ya EXISTIAN. Un pedido no puede ser de un
-- cliente que se dio de alta despues.
CREATE TEMP TABLE f1_cli AS
SELECT c.id AS cliente_id, c.usuario_id, c.fecha_creacion,
       -- 4.166 de los 50.072 clientes son EMPRESAS: llevan la razon social en
       -- `nombre` y `apellido` en NULL. Un `nombre || ' ' || apellido` a secas
       -- devuelve NULL entero y revienta contra el NOT NULL de la factura.
       trim(c.nombre || COALESCE(' ' || c.apellido, '')) AS razon_social,
       c.numero_identificacion,
       (SELECT d.id FROM direccion d WHERE d.usuario_id = c.usuario_id
         ORDER BY d.es_predeterminada DESC, d.id LIMIT 1) AS direccion_id,
       row_number() OVER (ORDER BY c.fecha_creacion, c.id) AS rn
FROM cliente c
WHERE c.fecha_creacion < timestamptz '2026-01-01 00:00:00-05';
CREATE INDEX ON f1_cli(rn);

ALTER TABLE f1_dia ADD COLUMN cli_max bigint;
UPDATE f1_dia d SET cli_max =
    (SELECT count(*) FROM f1_cli c WHERE c.fecha_creacion < (d.dia + 1)::timestamptz);

CREATE TEMP TABLE f1_vend AS
SELECT row_number() OVER (ORDER BY u.id) AS rn, u.id
FROM usuario u JOIN usuario_rol ur ON ur.usuario_id = u.id
JOIN rol r ON r.id = ur.rol_id WHERE r.codigo = 'VENDEDOR';

-- Posiciones vendibles: (variante, bodega) que YA EXISTEN en `inventario`.
-- Partir de `inventario` y no del catalogo garantiza que toda venta cae sobre
-- una posicion real; si no, el cuadre de V2 —que recorre `inventario`— dejaria
-- movimientos fuera de su alcance sin que nada avisara.
CREATE TEMP TABLE f1_pos AS
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

-- Bandas nuevas: se sortea bodega (80 % Central / 20 % Norte, el mismo reparto
-- con el que la Fase 0 distribuyo las existencias) y despues la variante.
CREATE TEMP TABLE f1_posn AS
SELECT p.*, row_number() OVER (PARTITION BY banda, bodega_id ORDER BY variante_id) AS rn,
       count(*)      OVER (PARTITION BY banda, bodega_id) AS tot
FROM f1_pos p WHERE banda <> 'historico';
CREATE INDEX ON f1_posn(banda, bodega_id, rn);

-- Banda historica: se sortea la POSICION directamente, sin forzar el reparto
-- por bodega — las 1.406 posiciones vivas ya lo llevan incorporado.
CREATE TEMP TABLE f1_posh AS
SELECT p.*, row_number() OVER (ORDER BY variante_id, bodega_id) AS rn,
       count(*)      OVER () AS tot
FROM f1_pos p WHERE banda = 'historico';
CREATE INDEX ON f1_posh(rn);

\echo '--- 1. Perfiles derivados de la serie existente: OK'

-- ════════════════════════════════════════════════════════════════════════════
-- 2. LOS 10.000 PEDIDOS
-- ════════════════════════════════════════════════════════════════════════════
CREATE TEMP TABLE f1_ped AS
WITH n AS (SELECT generate_series(1, 10000)::bigint AS i),
sorteo AS (
    SELECT n.i, d.dia, d.cli_max, h.hh,
           c.codigo AS canal, e.codigo AS estado_codigo, e.estado_id,
           e.mueve_stock, e.factura, e.cobra
    FROM n
    JOIN LATERAL (SELECT dia, cli_max FROM f1_dia
                   WHERE pg_temp.u('dia', n.i) >= lo AND pg_temp.u('dia', n.i) < hi LIMIT 1) d ON true
    JOIN LATERAL (SELECT hh FROM f1_hora
                   WHERE pg_temp.u('hora', n.i) >= lo AND pg_temp.u('hora', n.i) < hi LIMIT 1) h ON true
    JOIN LATERAL (SELECT codigo FROM f1_canal
                   WHERE pg_temp.u('canal', n.i) >= lo AND pg_temp.u('canal', n.i) < hi LIMIT 1) c ON true
    JOIN LATERAL (SELECT codigo, estado_id, mueve_stock, factura, cobra FROM f1_estado
                   WHERE pg_temp.u('est', n.i) >= lo AND pg_temp.u('est', n.i) < hi LIMIT 1) e ON true
),
fechado AS (
    SELECT s.*,
           ((s.dia + (s.hh || ' hours')::interval
                   + (floor(pg_temp.u('min', s.i) * 60) || ' minutes')::interval
                   + (floor(pg_temp.u('seg', s.i) * 60) || ' seconds')::interval
                   + (s.i || ' microseconds')::interval)   -- unicidad garantizada
             AT TIME ZONE 'America/Guayaquil') AS fecha_pedido
    FROM sorteo s
)
SELECT
    1000000000 + f.i AS id, f.i, f.dia, f.fecha_pedido,
    f.canal, f.estado_codigo, f.estado_id, f.mueve_stock, f.factura, f.cobra,
    cl.cliente_id, cl.usuario_id, cl.direccion_id, cl.razon_social, cl.numero_identificacion,
    -- Numero: PREFIJO-YYYYMMDD-secuencia, con la FECHA DEL PEDIDO. 20 chars justos.
    'PED-' || to_char(f.dia, 'YYYYMMDD') || '-' || lpad((900000 + f.i)::text, 7, '0') AS numero,
    CASE WHEN f.canal = 'web' THEN NULL ELSE v.id END AS vendedor_id,
    CASE WHEN f.canal = 'tienda' THEN NULL
         WHEN pg_temp.u('me', f.i) < 0.75 THEN 1::bigint ELSE 2::bigint END AS metodo_envio_id,
    CASE WHEN f.canal = 'tienda' THEN NULL
         WHEN pg_temp.u('me', f.i) < 0.75 THEN 2::bigint ELSE 1::bigint END AS transportista_id,
    -- La compra en tienda no paga flete: los 1.030 pedidos 'tienda' actuales
    -- tienen costo_envio = 0 sin excepcion.
    CASE WHEN f.canal = 'tienda' THEN 0::numeric ELSE en.costo END AS costo_envio,
    -- Numero de lineas: 1..5 con la frecuencia observada (media 2,543).
    CASE WHEN pg_temp.u('nl', f.i) < 0.2302 THEN 1
         WHEN pg_temp.u('nl', f.i) < 0.5625 THEN 2
         WHEN pg_temp.u('nl', f.i) < 0.7768 THEN 3
         WHEN pg_temp.u('nl', f.i) < 0.8870 THEN 4
         ELSE 5 END AS n_lineas
FROM fechado f
JOIN LATERAL (SELECT cliente_id, usuario_id, direccion_id, razon_social, numero_identificacion
              FROM f1_cli WHERE rn = 1 + floor(pg_temp.u('cli', f.i) * f.cli_max)::bigint) cl ON true
JOIN LATERAL (SELECT id FROM f1_vend
              WHERE rn = 1 + floor(pg_temp.u('vnd', f.i) * (SELECT count(*) FROM f1_vend))::bigint) v ON true
JOIN LATERAL (SELECT costo FROM f1_env
              WHERE rn = 1 + floor(pg_temp.u('env', f.i) * (SELECT count(*) FROM f1_env))::bigint) en ON true;

CREATE UNIQUE INDEX ON f1_ped(id);
CREATE INDEX ON f1_ped(i);

-- ── Los hitos del ciclo. Cada uno cuelga del anterior, nunca de la fecha del
--    pedido: asi ningun hito puede adelantarse al que lo precede.
ALTER TABLE f1_ped
    ADD COLUMN t_pagado      timestamptz,
    ADD COLUMN t_facturado   timestamptz,
    ADD COLUMN t_preparacion timestamptz,
    ADD COLUMN t_preparado   timestamptz,
    ADD COLUMN t_despachado  timestamptz,
    ADD COLUMN t_entregado   timestamptz,
    ADD COLUMN t_final       timestamptz;

UPDATE f1_ped p SET
    t_pagado      = p.fecha_pedido + (0.5 + 5.5 * pg_temp.u('h1', p.i)) * interval '1 hour',
    t_facturado   = p.fecha_pedido + (0.7 + 8.5 * pg_temp.u('h1', p.i)) * interval '1 hour',
    t_preparacion = p.fecha_pedido + (3   + 30  * pg_temp.u('h2', p.i)) * interval '1 hour',
    t_preparado   = p.fecha_pedido + (7   + 36  * pg_temp.u('h2', p.i)) * interval '1 hour'
                                   + (1   +  7  * pg_temp.u('h3', p.i)) * interval '1 hour',
    t_despachado  = p.fecha_pedido + (10  + 40  * pg_temp.u('h2', p.i)) * interval '1 hour'
                                   + (2   + 22  * pg_temp.u('h4', p.i)) * interval '1 hour';
UPDATE f1_ped p SET
    t_entregado = p.t_despachado + (1 + 5 * pg_temp.u('h5', p.i)) * interval '1 day';
UPDATE f1_ped p SET t_final = CASE p.estado_codigo
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

\echo '--- 2. 10.000 pedidos sorteados: OK'

-- ════════════════════════════════════════════════════════════════════════════
-- 3. LAS LINEAS
-- ════════════════════════════════════════════════════════════════════════════
CREATE TEMP TABLE f1_lin0 AS
WITH ln AS (
    SELECT p.i, p.id AS pedido_id, p.mueve_stock, p.factura, g.j,
           p.i * 10 + g.j AS k                       -- clave de linea, unica
    FROM f1_ped p CROSS JOIN LATERAL generate_series(1, p.n_lineas) g(j)
),
conbanda AS (
    SELECT ln.*, b.banda,
           CASE WHEN pg_temp.u('bod', ln.k) < 0.80 THEN 4::bigint ELSE 3::bigint END AS bod
    FROM ln JOIN LATERAL (SELECT banda FROM f1_banda
                          WHERE pg_temp.u('bnd', ln.k) >= lo AND pg_temp.u('bnd', ln.k) < hi
                          LIMIT 1) b ON true
),
conpos AS (
    SELECT c.*, pos.variante_id, pos.bodega_id, pos.precio, pos.costo, pos.sku, pos.nombre_producto
    FROM conbanda c
    JOIN LATERAL (
        SELECT variante_id, bodega_id, precio, costo, sku, nombre_producto
        FROM f1_posn n
        WHERE c.banda <> 'historico' AND n.banda = c.banda AND n.bodega_id = c.bod
          AND n.rn = 1 + floor(pg_temp.u('pos', c.k) * n.tot)::bigint
        UNION ALL
        SELECT variante_id, bodega_id, precio, costo, sku, nombre_producto
        FROM f1_posh h
        WHERE c.banda = 'historico'
          AND h.rn = 1 + floor(pg_temp.u('pos', c.k) * h.tot)::bigint
    ) pos ON true
)
SELECT * FROM conpos;

-- `uq_pedido_detalle (pedido_id, producto_variante_id)`: una variante no se
-- repite dentro de un pedido, y la restriccion NO mira la bodega. Con 65 % de
-- las lineas cayendo sobre 4.200 posiciones de la banda L1, el choque no es
-- hipotetico. Se resuelve descartando la linea repetida, no reintentando: un
-- pedido con una linea menos sigue siendo un pedido valido.
CREATE TEMP TABLE f1_lin AS
SELECT DISTINCT ON (pedido_id, variante_id) *
FROM f1_lin0 ORDER BY pedido_id, variante_id, j;

-- Cantidad, precio e impuesto.
--   · cantidad 1..4 con la frecuencia observada (media 1,992)
--   · precio_unitario = precio x [0,90 ; 1,00] — INVARIANTE DEL SEED, verificado
--     hoy sobre las 10.384 lineas existentes: 10.384 de 10.384 dentro de banda
--   · descuento de promocion en el 1,2 % de las lineas, como hoy (123/10.384)
--   · IVA 15 % sobre la base YA rebajada
ALTER TABLE f1_lin
    ADD COLUMN cantidad int, ADD COLUMN precio_unitario numeric(12,2),
    ADD COLUMN monto_descuento numeric(12,2), ADD COLUMN monto_impuesto numeric(12,2),
    ADD COLUMN lin_seq bigint;

UPDATE f1_lin l SET
    cantidad = CASE WHEN pg_temp.u('cnt', l.k) < 0.4318 THEN 1
                    WHEN pg_temp.u('cnt', l.k) < 0.7204 THEN 2
                    WHEN pg_temp.u('cnt', l.k) < 0.8593 THEN 3
                    ELSE 4 END,
    precio_unitario = GREATEST(0.01, round(l.precio * (0.90 + 0.10 * pg_temp.u('pu', l.k)), 2));

UPDATE f1_lin l SET
    monto_descuento = CASE WHEN pg_temp.u('dsc', l.k) < 0.012
                           THEN round(l.cantidad * l.precio_unitario * 0.10, 2)
                           ELSE 0 END;
UPDATE f1_lin l SET
    monto_impuesto = round((l.cantidad * l.precio_unitario - l.monto_descuento) * 0.15, 2);

UPDATE f1_lin l SET lin_seq = z.s
FROM (SELECT k, row_number() OVER (ORDER BY pedido_id, variante_id) AS s FROM f1_lin) z
WHERE z.k = l.k;
CREATE UNIQUE INDEX ON f1_lin(lin_seq);
CREATE INDEX ON f1_lin(pedido_id);

\echo '--- 3. Lineas generadas: OK'
SELECT (SELECT count(*) FROM f1_lin0) lineas_sorteadas,
       (SELECT count(*) FROM f1_lin)  lineas_finales,
       (SELECT count(*) FROM f1_lin0) - (SELECT count(*) FROM f1_lin) descartadas_por_uq;

-- ════════════════════════════════════════════════════════════════════════════
-- 4. LOTE A — pedido, pedido_detalle, historial_estado_pedido
-- ════════════════════════════════════════════════════════════════════════════
-- Los `id` son GENERATED ALWAYS: hace falta OVERRIDING SYSTEM VALUE. Como se
-- escribe con OVERRIDING, las secuencias IDENTITY NO se mueven y la aplicacion
-- sigue asignando ids desde 4219 en adelante.
-- `subtotal`, `monto_impuesto` y `total` de la cabecera se dejan en 0: los
-- ponen los triggers al insertar el detalle. Escribirlos seria romper la regla
-- de oro 1.
BEGIN;
INSERT INTO pedido (id, numero, cliente_id, estado_pedido_id, moneda_id, metodo_envio_id,
                    direccion_envio_id, direccion_facturacion_id, canal,
                    subtotal, monto_descuento, monto_impuesto, costo_envio,
                    fecha_pedido, fecha_creacion, transportista_id, vendedor_id)
OVERRIDING SYSTEM VALUE
SELECT p.id, p.numero, p.cliente_id, p.estado_id, 1, p.metodo_envio_id,
       p.direccion_id, p.direccion_id, p.canal,
       0, 0, 0, p.costo_envio,
       p.fecha_pedido, p.fecha_pedido, p.transportista_id, p.vendedor_id
FROM f1_ped p
ON CONFLICT (id) DO NOTHING;
COMMIT;

BEGIN;
INSERT INTO pedido_detalle (id, pedido_id, producto_variante_id, nombre_producto, sku,
                            cantidad, precio_unitario, monto_descuento, monto_impuesto,
                            fecha_creacion)
OVERRIDING SYSTEM VALUE
SELECT 1000000000 + l.lin_seq, l.pedido_id, l.variante_id,
       left(l.nombre_producto, 200), left(l.sku, 50),
       l.cantidad, l.precio_unitario, l.monto_descuento, l.monto_impuesto,
       p.fecha_pedido
FROM f1_lin l JOIN f1_ped p ON p.id = l.pedido_id
ON CONFLICT (id) DO NOTHING;
COMMIT;

-- El historial recorre los hitos por los que el pedido paso REALMENTE. No hay
-- fila 'pendiente' porque el sistema no la escribe (0 de 24.610 hoy).
BEGIN;
CREATE TEMP TABLE f1_hist AS
WITH pasos AS (
    SELECT p.id AS pedido_id, x.codigo, x.ts, x.ord
    FROM f1_ped p
    CROSS JOIN LATERAL (VALUES
        ('confirmado',     p.fecha_pedido, 1),
        ('pagado',         p.t_pagado,     2),
        ('facturado',      p.t_facturado,  3),
        ('en_preparacion', p.t_preparacion,4),
        ('preparado',      p.t_preparado,  5),
        ('despachado',     p.t_despachado, 6),
        ('entregado',      p.t_entregado,  7),
        ('cancelado',      p.t_final,      8),
        ('no_entregado',   p.t_final,      9),
        ('devuelto',       p.t_final,     10)
    ) x(codigo, ts, ord)
    WHERE CASE p.estado_codigo
            WHEN 'cancelado'      THEN x.ord IN (1, 8)
            WHEN 'confirmado'     THEN x.ord IN (1)
            WHEN 'pagado'         THEN x.ord IN (1,2)
            WHEN 'facturado'      THEN x.ord IN (1,2,3)
            WHEN 'en_preparacion' THEN x.ord IN (1,2,3,4)
            WHEN 'preparado'      THEN x.ord IN (1,2,3,4,5)
            WHEN 'despachado'     THEN x.ord IN (1,2,3,4,5,6)
            WHEN 'entregado'      THEN x.ord IN (1,2,3,4,5,6,7)
            WHEN 'no_entregado'   THEN x.ord IN (1,2,3,4,5,6,9)
            WHEN 'devuelto'       THEN x.ord IN (1,2,3,4,5,6,7,10)
          END
)
SELECT row_number() OVER (ORDER BY pedido_id, ord) AS h_seq, pasos.*
FROM pasos;

INSERT INTO historial_estado_pedido (id, pedido_id, estado_pedido_id, usuario_id,
                                     comentario, fecha_creacion)
OVERRIDING SYSTEM VALUE
SELECT 1000000000 + h.h_seq, h.pedido_id, ep.id,
       CASE h.codigo WHEN 'confirmado' THEN p.vendedor_id
                     WHEN 'pagado'     THEN p.vendedor_id
                     WHEN 'facturado'  THEN 6::bigint
                     WHEN 'en_preparacion' THEN 9::bigint
                     WHEN 'preparado'      THEN 9::bigint
                     WHEN 'despachado'     THEN 10::bigint
                     WHEN 'entregado'      THEN 10::bigint
                     WHEN 'no_entregado'   THEN 10::bigint
                     WHEN 'devuelto'       THEN 9::bigint
                     ELSE NULL END,
       '[FASE1] ' || h.codigo, h.ts
FROM f1_hist h
JOIN estado_pedido ep ON ep.codigo = h.codigo
JOIN f1_ped p ON p.id = h.pedido_id
ON CONFLICT (id) DO NOTHING;
COMMIT;

\echo '--- 4. Lote A (pedido / detalle / historial): OK'

-- ════════════════════════════════════════════════════════════════════════════
-- 5. EL PLAN DE KARDEX — la parte delicada
-- ════════════════════════════════════════════════════════════════════════════

-- 5.1 Las SALIDAS. El stock sale cuando bodega hace el picking, o sea en el
--     hito 'en_preparacion'. La fecha lleva un desplazamiento por linea para
--     que dos salidas de la misma posicion nunca compartan instante.
CREATE TEMP TABLE f1_sal AS
SELECT l.lin_seq, l.pedido_id, l.variante_id, l.bodega_id, l.cantidad, l.costo,
       p.t_preparacion + (l.lin_seq || ' microseconds')::interval AS ts,
       (l.variante_id >= 900000000) AS es_nueva
FROM f1_lin l JOIN f1_ped p ON p.id = l.pedido_id
WHERE p.mueve_stock;
CREATE INDEX ON f1_sal(variante_id, bodega_id, ts);

-- 5.2 Copia indexada del kardex EXISTENTE de las posiciones que esta fase va a
--     tocar. Sirve para tres cosas: colocar la entrada emparejada despues del
--     ultimo movimiento previo, calcular el saldo de partida, y —la que
--     importa— poder verificar la cadena COMPLETA antes de escribir. Se hace
--     sobre una copia temporal porque `movimiento_inventario` no tiene indice
--     compuesto (variante, bodega, fecha) y 41.000 busquedas contra un indice
--     simple serian 41.000 recorridos completos.
--     Se traen TODAS las posiciones, nuevas incluidas: en una posicion de la
--     Fase 0 el saldo previo sale 0 por si solo (su unico movimiento es la
--     apertura de 2026-08-09, posterior a todo esto), asi que no hace falta un
--     caso especial — y la formula queda valida para las fases que carguen
--     DESPUES de esa apertura.
CREATE TEMP TABLE f1_prev AS
SELECT mi.id, mi.producto_variante_id AS v, mi.bodega_id AS b, mi.fecha_creacion AS ts,
       mi.cantidad * tm.factor AS q, mi.stock_anterior, mi.stock_nuevo
FROM movimiento_inventario mi
JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id
WHERE mi.id < 1000000000
  AND EXISTS (SELECT 1 FROM f1_sal s
              WHERE s.variante_id = mi.producto_variante_id AND s.bodega_id = mi.bodega_id);
CREATE INDEX ON f1_prev(v, b, ts);

-- 5.3 Las ENTRADAS de reposicion, con los dos ritmos.
--
--     (a) POSICIONES NUEVAS: una entrada por (posicion, bimestre) que cubre la
--         demanda de esos dos meses, fechada un dia antes de la primera venta
--         del bimestre. Es reposicion con cobertura real.
CREATE TEMP TABLE f1_ent AS
SELECT s.variante_id, s.bodega_id, sum(s.cantidad)::int AS cantidad,
       min(s.ts) - interval '1 day' AS ts, max(s.costo) AS costo, true AS es_nueva
FROM f1_sal s
WHERE s.es_nueva
GROUP BY s.variante_id, s.bodega_id,
         -- bimestre = pares de meses naturales
         (extract(year FROM s.ts AT TIME ZONE 'America/Guayaquil')::int * 6
          + (extract(month FROM s.ts AT TIME ZONE 'America/Guayaquil')::int - 1) / 2)
UNION ALL
--     (b) POSICIONES HISTORICAS: una entrada por linea, emparejada. Se coloca
--         DESPUES del ultimo movimiento existente anterior a la venta —hasta
--         dos horas antes de ella— para que el par quede encerrado entre dos
--         eslabones consecutivos de la cadena original y su suma cero no
--         desplace a ninguno.
SELECT s.variante_id, s.bodega_id, s.cantidad,
       -- El LEAST no es adorno: si el ultimo movimiento existente cae a menos
       -- de un milisegundo de la venta, el GREATEST solo empujaria la entrada
       -- POR DETRAS de su propia salida y la cadena saldria negativa.
       LEAST(GREATEST(s.ts - interval '2 hours',
                      COALESCE(pv.ult, s.ts - interval '2 hours') + interval '1 millisecond'),
             s.ts - interval '1 microsecond') AS ts,
       s.costo, false AS es_nueva
FROM f1_sal s
LEFT JOIN LATERAL (SELECT max(p.ts) AS ult FROM f1_prev p
                   WHERE p.v = s.variante_id AND p.b = s.bodega_id AND p.ts < s.ts) pv ON true
WHERE NOT s.es_nueva;

-- 5.4 El plan completo, ordenado en el tiempo. `mov_seq` sale de un
--     `row_number()` ORDENADO POR LA FECHA, asi que el orden `(fecha, id)` con
--     el que se lee la cadena es el mismo con el que se calcula: la trampa de
--     C-2 —que el desempate acabe siendo el id y el id no siga a la fecha— no
--     puede darse.
CREATE TEMP TABLE f1_mov AS
SELECT row_number() OVER (ORDER BY ts, variante_id, bodega_id, tipo, ref_id) AS mov_seq, *
FROM (
    SELECT e.variante_id, e.bodega_id, 1 AS tipo, 1 AS factor, e.cantidad, e.ts, e.costo,
           NULL::bigint AS ref_id, e.es_nueva
    FROM f1_ent e
    UNION ALL
    SELECT s.variante_id, s.bodega_id, 5, -1, s.cantidad, s.ts, s.costo,
           s.pedido_id, s.es_nueva
    FROM f1_sal s
) z;
CREATE INDEX ON f1_mov(variante_id, bodega_id, ts, mov_seq);
CREATE INDEX ON f1_mov(mov_seq);

-- 5.5 El encadenamiento.
--     stock_anterior = (saldo de lo EXISTENTE anterior a este instante)
--                    + (suma de lo que esta fase ya movio en esa posicion)
--     El primer sumando es 0 en las posiciones nuevas —su unico movimiento
--     previo es la apertura de 2026-08-09, posterior a todo esto— y solo se
--     consulta en las historicas.
CREATE TEMP TABLE f1_kar AS
SELECT m.*,
       (m.saldo_previo + m.acum - m.factor * m.cantidad)::int AS stock_anterior,
       (m.saldo_previo + m.acum)::int                         AS stock_nuevo
FROM (
    SELECT m.*,
           COALESCE(pv.s, 0) AS saldo_previo,
           sum(m.factor * m.cantidad) OVER (PARTITION BY m.variante_id, m.bodega_id
                                            ORDER BY m.ts, m.mov_seq
                                            ROWS UNBOUNDED PRECEDING) AS acum
    FROM f1_mov m
    LEFT JOIN LATERAL (
        SELECT sum(p.q) AS s FROM f1_prev p
        WHERE p.v = m.variante_id AND p.b = m.bodega_id AND p.ts < m.ts
    ) pv ON true
) m;
CREATE INDEX ON f1_kar(mov_seq);

-- 5.6 GUARDIA DURA, ANTES DE ESCRIBIR NADA. Tres cosas que el trigger del
--     kardex no comprueba y que, si fallan, no dan error sino un dato falso.
DO $guardia_kardex$
DECLARE v_neg bigint; v_rotos bigint; v_resto bigint; v_colision bigint;
BEGIN
    -- (1) Ninguna posicion en negativo en NINGUN instante.
    SELECT count(*) INTO v_neg FROM f1_kar WHERE stock_anterior < 0 OR stock_nuevo < 0;
    IF v_neg > 0 THEN
        RAISE EXCEPTION 'ABORTA: % movimientos dejarian la posicion en negativo.', v_neg;
    END IF;

    -- (2) La cadena COMPLETA —lo que ya existe MAS lo que esta fase anade—
    --     enlaza de punta a punta y arranca en cero.
    --     Comprobar solo las filas nuevas NO vale y es un error facil de
    --     cometer: en una posicion historica, entre dos bloques de esta fase
    --     puede haber un movimiento preexistente, y entonces el saldo SALTA
    --     con toda la razon. Medido: 87 «roturas» que no lo eran. La unica
    --     pregunta con sentido se hace sobre la cadena fusionada.
    SELECT count(*) INTO v_rotos FROM (
        SELECT lag(sn) OVER w AS prev, sa, row_number() OVER w AS rn
        FROM (
            SELECT v, b, ts, id, stock_anterior AS sa, stock_nuevo AS sn FROM f1_prev
            UNION ALL
            SELECT variante_id, bodega_id, ts, 1000000000 + mov_seq, stock_anterior, stock_nuevo
            FROM f1_kar
        ) u
        WINDOW w AS (PARTITION BY v, b ORDER BY ts, id)) z
    WHERE (rn > 1 AND prev <> sa) OR (rn = 1 AND sa <> 0);
    IF v_rotos > 0 THEN
        RAISE EXCEPTION 'ABORTA: % enlaces rotos en la cadena fusionada del kardex.', v_rotos;
    END IF;

    -- (3) EL PUNTO QUE SOSTIENE TODO EL METODO: cada posicion cierra donde
    --     empezo. Si una sola no vuelve a su saldo, el movimiento de apertura
    --     de la Fase 0 —o el siguiente eslabon existente— queda mintiendo.
    SELECT count(*) INTO v_resto FROM (
        SELECT variante_id, bodega_id, sum(factor * cantidad) AS neto
        FROM f1_kar GROUP BY 1, 2) z WHERE neto <> 0;
    IF v_resto > 0 THEN
        RAISE EXCEPTION 'ABORTA: % posiciones no vuelven a su saldo de partida.', v_resto;
    END IF;

    -- (4) Ningun movimiento de esta fase comparte instante exacto con uno
    --     existente de la misma posicion: el orden seria ambiguo.
    SELECT count(*) INTO v_colision
    FROM f1_kar k JOIN f1_prev p
      ON p.v = k.variante_id AND p.b = k.bodega_id AND p.ts = k.ts;
    IF v_colision > 0 THEN
        RAISE EXCEPTION 'ABORTA: % movimientos colisionan en instante con el kardex existente.',
                        v_colision;
    END IF;

    RAISE NOTICE 'Plan de kardex verificado: 0 negativos, 0 enlaces rotos, 0 residuos, 0 colisiones.';
END
$guardia_kardex$;

\echo '--- 5. Plan de kardex construido y verificado: OK'

-- ════════════════════════════════════════════════════════════════════════════
-- 6. LOTE B — el documento de compra que respalda cada entrada
-- ════════════════════════════════════════════════════════════════════════════
-- Una entrada de kardex sin orden, recepcion y factura detras es un numero sin
-- procedencia. Se agrupa por (proveedor, bodega, dia) y se trocea a 25 lineas,
-- como hizo la Fase 0: una orden de 300 lineas seria un valor atipico de 88x la
-- media actual y distorsionaria OTD-COM-01 y COM-06.
-- Cada entrada de kardex, con su proveedor y su dia.
CREATE TEMP TABLE f1_entp AS
SELECT k.variante_id, k.bodega_id, k.cantidad, k.ts, k.costo,
       (k.ts AT TIME ZONE 'America/Guayaquil')::date AS dia,
       COALESCE(
         (SELECT pp.proveedor_id FROM producto_proveedor pp
           WHERE pp.producto_variante_id = k.variante_id AND pp.activo
           ORDER BY pp.es_preferido DESC, pp.id LIMIT 1),
         -- 170 variantes historicas no tienen proveedor asociado: se les
         -- asigna uno estable por hash, no NULL.
         (SELECT pr.id FROM proveedor pr WHERE pr.activo ORDER BY pr.id
           OFFSET (('x' || substr(md5('pv' || k.variante_id::text), 1, 7))::bit(28)::int
                   % (SELECT count(*) FROM proveedor WHERE activo)) LIMIT 1)
       ) AS proveedor_id
FROM f1_kar k WHERE k.tipo = 1;

-- `uq_orden_compra_detalle (orden_compra_id, producto_variante_id)`: una
-- variante NO se repite dentro de una orden. Y se repetia: en una posicion
-- historica que vende dos veces el mismo dia, la reposicion emparejada genera
-- DOS entradas de esa variante, y las dos caian en la misma orden. La linea de
-- la orden se AGREGA por variante y dia; las entradas de kardex siguen siendo
-- dos, y las dos apuntan a la misma recepcion. La suma cuadra.
CREATE TEMP TABLE f1_ocl AS
WITH agg AS (
    SELECT proveedor_id, bodega_id, dia, variante_id,
           sum(cantidad)::int AS cantidad, min(ts) AS ts, max(costo) AS costo
    FROM f1_entp GROUP BY 1, 2, 3, 4
),
numerado AS (
    SELECT a.*,
           (row_number() OVER (PARTITION BY a.proveedor_id, a.bodega_id, a.dia
                               ORDER BY a.variante_id) - 1) / 25 AS lote
    FROM agg a
)
SELECT n.*,
       dense_rank() OVER (ORDER BY n.proveedor_id, n.bodega_id, n.dia, n.lote) AS oc_seq,
       row_number() OVER (ORDER BY n.proveedor_id, n.bodega_id, n.dia, n.lote,
                                   n.variante_id)                              AS ocd_seq
FROM numerado n;
CREATE INDEX ON f1_ocl(oc_seq);

-- Puente entrada de kardex -> orden/recepcion, para poder escribir la
-- referencia sin volver a resolver el proveedor.
CREATE TEMP TABLE f1_entoc AS
SELECT e.variante_id, e.bodega_id, e.ts, l.oc_seq
FROM f1_entp e
JOIN f1_ocl l ON l.proveedor_id = e.proveedor_id AND l.bodega_id = e.bodega_id
             AND l.dia          = e.dia          AND l.variante_id = e.variante_id;
CREATE INDEX ON f1_entoc(variante_id, bodega_id, ts);

-- EL NUMERO DE DOCUMENTO ES UNA SEGUNDA CLAVE, Y EL TRAMO DE IDs NO LA CUBRE.
-- `orden_compra.numero`, `recepcion_mercancia.numero` y
-- `factura_compra.numero_factura` son UNIQUE, y el seed existente ya usa el
-- MISMO formato `XX-YYYYMMDD-NNNNNN` con secuencias de 6 digitos desde 100026.
-- Con `100000 + oc_seq` la recepcion 39 choco contra `RM-20250125-100039`, que
-- lleva ahi desde el seed original; las ordenes se salvaron por PURA SUERTE,
-- porque su numero lleva la fecha de emision (cinco dias antes) y esa resta
-- desplazo la colision. La secuencia pasa a SIETE digitos desde 9.100.000:
-- 91 identifica a la Fase 1 igual que 90 identifico a la Fase 0, y ningun
-- numero de seis digitos puede igualar a uno de siete.
-- La leccion, gemela de la del tramo de ids: reservar el espacio de las CLAVES
-- PRIMARIAS no reserva el de las claves UNICAS de NEGOCIO, y esas tambien hay
-- que enumerarlas antes de cargar.
BEGIN;
INSERT INTO orden_compra (id, numero, proveedor_id, bodega_id, moneda_id, usuario_id, estado,
                          fecha_emision, fecha_entrega_esperada, subtotal, monto_impuesto, total,
                          observacion, fecha_creacion)
OVERRIDING SYSTEM VALUE
SELECT 1000000000 + l.oc_seq,
       'OC-' || to_char(min(l.dia) - 5, 'YYYYMMDD') || '-' || lpad((9100000 + l.oc_seq)::text, 7, '0'),
       min(l.proveedor_id), min(l.bodega_id), 1, 11, 'recibida',
       min(l.dia) - 5, min(l.dia), 0, 0, 0,
       '[FASE1] Reposicion del piloto', min(l.ts) - interval '5 days'
FROM f1_ocl l GROUP BY l.oc_seq
ON CONFLICT (id) DO NOTHING;

INSERT INTO orden_compra_detalle (id, orden_compra_id, producto_variante_id, cantidad,
                                  precio_unitario, monto_impuesto, cantidad_recibida, fecha_creacion)
OVERRIDING SYSTEM VALUE
SELECT 1000000000 + l.ocd_seq, 1000000000 + l.oc_seq, l.variante_id, l.cantidad,
       l.costo, round(l.cantidad * l.costo * 0.15, 2), l.cantidad, l.ts - interval '5 days'
FROM f1_ocl l
ON CONFLICT (id) DO NOTHING;
COMMIT;

BEGIN;
INSERT INTO recepcion_mercancia (id, numero, orden_compra_id, bodega_id, usuario_id, estado,
                                 fecha_recepcion, observacion, fecha_creacion)
OVERRIDING SYSTEM VALUE
SELECT 1000000000 + l.oc_seq,
       'RM-' || to_char(min(l.dia), 'YYYYMMDD') || '-' || lpad((9100000 + l.oc_seq)::text, 7, '0'),
       1000000000 + l.oc_seq, min(l.bodega_id), 9, 'confirmada',
       min(l.ts), '[FASE1] Recepcion completa de reposicion', min(l.ts)
FROM f1_ocl l GROUP BY l.oc_seq
ON CONFLICT (id) DO NOTHING;

INSERT INTO recepcion_detalle (id, recepcion_mercancia_id, orden_compra_detalle_id,
                               cantidad_recibida, cantidad_rechazada, fecha_creacion)
OVERRIDING SYSTEM VALUE
SELECT 1000000000 + l.ocd_seq, 1000000000 + l.oc_seq, 1000000000 + l.ocd_seq,
       l.cantidad, 0, l.ts
FROM f1_ocl l
ON CONFLICT (id) DO NOTHING;
COMMIT;

BEGIN;
INSERT INTO factura_compra (id, proveedor_id, orden_compra_id, moneda_id, numero_factura,
                            fecha_emision, fecha_vencimiento, subtotal, monto_impuesto, total,
                            estado, registrado_por, fecha_creacion)
OVERRIDING SYSTEM VALUE
SELECT 1000000000 + l.oc_seq, min(l.proveedor_id), 1000000000 + l.oc_seq, 1,
       'FC-' || to_char(min(l.dia) + 1, 'YYYYMMDD') || '-' || lpad((9100000 + l.oc_seq)::text, 7, '0'),
       min(l.dia) + 1,
       min(l.dia) + 1 + (SELECT p.dias_credito FROM proveedor p WHERE p.id = min(l.proveedor_id)),
       0, 0, 0, 'pagada', 11, min(l.ts) + interval '1 day'
FROM f1_ocl l GROUP BY l.oc_seq
ON CONFLICT (id) DO NOTHING;

INSERT INTO factura_compra_detalle (id, factura_compra_id, producto_variante_id, cantidad,
                                    precio_unitario, monto_impuesto, fecha_creacion)
OVERRIDING SYSTEM VALUE
SELECT 1000000000 + l.ocd_seq, 1000000000 + l.oc_seq, l.variante_id, l.cantidad,
       l.costo, round(l.cantidad * l.costo * 0.15, 2), l.ts + interval '1 day'
FROM f1_ocl l
ON CONFLICT (id) DO NOTHING;
COMMIT;

-- La cuenta por pagar se salda: son facturas de 2025 y a fecha de hoy todas
-- vencieron hace mucho. Dejarlas pendientes inventaria una deuda de un ano.
BEGIN;
INSERT INTO cuenta_por_pagar (id, factura_compra_id, proveedor_id, monto_original,
                              saldo_pendiente, fecha_vencimiento, estado, fecha_creacion)
OVERRIDING SYSTEM VALUE
SELECT fc.id, fc.id, fc.proveedor_id, fc.total, 0, fc.fecha_vencimiento, 'pagada',
       fc.fecha_creacion
FROM factura_compra fc WHERE fc.id >= 1000000000
ON CONFLICT (id) DO NOTHING;

-- `pago_proveedor` cuelga de la CUENTA POR PAGAR, no de la factura.
INSERT INTO pago_proveedor (id, cuenta_por_pagar_id, metodo_pago_id, usuario_id,
                            monto, fecha_pago, referencia, observacion, fecha_creacion)
OVERRIDING SYSTEM VALUE
SELECT c.id, c.id, 2, 11, c.monto_original, c.fecha_vencimiento,
       'TR-F1-' || lpad((c.id - 1000000000)::text, 8, '0'),
       '[FASE1] Pago de la reposicion del piloto', c.fecha_vencimiento::timestamptz
FROM cuenta_por_pagar c WHERE c.id >= 1000000000 AND c.monto_original > 0
ON CONFLICT (id) DO NOTHING;
COMMIT;

\echo '--- 6. Lote B (compras de reposicion): OK'

-- ════════════════════════════════════════════════════════════════════════════
-- 7. LOTE C — el kardex
-- ════════════════════════════════════════════════════════════════════════════
BEGIN;
INSERT INTO movimiento_inventario (id, producto_variante_id, bodega_id, tipo_movimiento_id,
                                   usuario_id, cantidad, stock_anterior, stock_nuevo,
                                   costo_unitario, referencia_tipo, referencia_id,
                                   observacion, fecha_creacion)
OVERRIDING SYSTEM VALUE
SELECT 1000000000 + k.mov_seq, k.variante_id, k.bodega_id, k.tipo, 9,
       k.cantidad, k.stock_anterior, k.stock_nuevo, k.costo,
       CASE k.tipo WHEN 1 THEN 'recepcion_mercancia' ELSE 'pedido' END,
       CASE k.tipo WHEN 1 THEN (SELECT 1000000000 + o.oc_seq FROM f1_entoc o
                                 WHERE o.variante_id = k.variante_id
                                   AND o.bodega_id  = k.bodega_id
                                   AND o.ts         = k.ts LIMIT 1)
                   ELSE k.ref_id END,
       CASE k.tipo WHEN 1 THEN '[FASE1] Reposicion previa a la venta'
                          ELSE '[FASE1] Salida por venta' END,
       -- fecha EXPLICITA, nunca el DEFAULT: es la mitigacion de C-2
       k.ts
FROM f1_kar k
ON CONFLICT (id) DO NOTHING;
COMMIT;

\echo '--- 7. Lote C (kardex): OK'

-- ════════════════════════════════════════════════════════════════════════════
-- 8. LOTE D — factura de venta
-- ════════════════════════════════════════════════════════════════════════════
-- El invariante `factura_venta.total = pedido.total - pedido.costo_envio` sale
-- SOLO: `fn_recalcular_total_factura_venta` suma el detalle, y el detalle de la
-- factura es el del pedido. La factura no factura el flete. Los totales de la
-- cabecera se dejan en 0 porque los pone el trigger.
-- No hay cupon en el piloto: `pedido.monto_descuento` queda en 0 y el descuento
-- vive en la LINEA. El cupon obliga a prorratear la cabecera entre lineas con
-- ajuste de redondeo en la ultima, y eso es la capa de marketing, no el camino
-- de escritura del pedido que esta fase valida.
BEGIN;
INSERT INTO factura_venta (id, numero, pedido_id, cliente_id, moneda_id, razon_social,
                           identificacion, direccion_facturacion, subtotal, monto_descuento,
                           monto_impuesto, total, estado, fecha_emision, fecha_creacion)
OVERRIDING SYSTEM VALUE
SELECT p.id, 'FV-' || to_char(p.dia, 'YYYYMMDD') || '-' || lpad((900000 + p.i)::text, 7, '0'),
       p.id, p.cliente_id, 1, left(p.razon_social, 200),
       left(p.numero_identificacion, 20),
       (SELECT left(d.calle_principal || ', ' || ci.nombre, 300)
          FROM direccion d JOIN ciudad ci ON ci.id = d.ciudad_id WHERE d.id = p.direccion_id),
       0, 0, 0, 0, 'emitida', p.t_facturado, p.t_facturado
FROM f1_ped p WHERE p.factura
ON CONFLICT (id) DO NOTHING;

INSERT INTO factura_venta_detalle (id, factura_venta_id, pedido_detalle_id, producto_variante_id,
                                   descripcion, cantidad, precio_unitario, monto_descuento,
                                   monto_impuesto, fecha_creacion)
OVERRIDING SYSTEM VALUE
SELECT 1000000000 + l.lin_seq, l.pedido_id, 1000000000 + l.lin_seq, l.variante_id,
       left(l.nombre_producto, 255), l.cantidad, l.precio_unitario,
       l.monto_descuento, l.monto_impuesto, p.t_facturado
FROM f1_lin l JOIN f1_ped p ON p.id = l.pedido_id
WHERE p.factura
ON CONFLICT (id) DO NOTHING;
COMMIT;

\echo '--- 8. Lote D (factura de venta): OK'

-- ════════════════════════════════════════════════════════════════════════════
-- 9. LOTE E — cobro
-- ════════════════════════════════════════════════════════════════════════════
-- `pago.estado` admite 'completado'; `transaccion_pago.estado` NO —solo
-- exitosa/fallida/pendiente—, y el tipo de la transaccion es 'captura'.
BEGIN;
INSERT INTO pago (id, pedido_id, metodo_pago_id, moneda_id, monto, estado,
                  referencia_externa, fecha_pago, fecha_creacion)
OVERRIDING SYSTEM VALUE
SELECT p.id, p.id,
       CASE WHEN p.canal = 'tienda' THEN 1
            WHEN pg_temp.u('mp', p.i) < 0.62 THEN 3 ELSE 2 END,
       1, ped.total, 'completado',
       'F1-' || lpad((900000 + p.i)::text, 7, '0'), p.t_pagado, p.t_pagado
FROM f1_ped p JOIN pedido ped ON ped.id = p.id
WHERE p.cobra AND ped.total > 0
ON CONFLICT (id) DO NOTHING;

INSERT INTO transaccion_pago (id, pago_id, tipo, estado, monto, codigo_autorizacion,
                              respuesta_pasarela, fecha_creacion)
OVERRIDING SYSTEM VALUE
SELECT pg.id, pg.id, 'captura', 'exitosa', pg.monto,
       'AUT' || lpad((pg.id - 1000000000)::text, 9, '0'),
       jsonb_build_object('origen', 'FASE1', 'resultado', 'aprobado'),
       pg.fecha_pago
FROM pago pg WHERE pg.id >= 1000000000
ON CONFLICT (id) DO NOTHING;
COMMIT;

\echo '--- 9. Lote E (cobro): OK'

-- ════════════════════════════════════════════════════════════════════════════
-- 10. BITACORA
-- ════════════════════════════════════════════════════════════════════════════
BEGIN;
SELECT fn_carga_registrar('fase1','97_fase1_piloto_pedidos','pedido', 1000000001,
       (SELECT max(id) FROM pedido WHERE id>=1000000000), (SELECT count(*) FROM pedido WHERE id>=1000000000),
       '10.000 pedidos de 2025; estacionalidad, hora y canal tomados de la serie existente.');
SELECT fn_carga_registrar('fase1','97_fase1_piloto_pedidos','pedido_detalle', 1000000001,
       (SELECT max(id) FROM pedido_detalle WHERE id>=1000000000), (SELECT count(*) FROM pedido_detalle WHERE id>=1000000000),
       'Bandas de precio segun carga_fase_parametro; uq_pedido_detalle deduplicado.');
SELECT fn_carga_registrar('fase1','97_fase1_piloto_pedidos','historial_estado_pedido', 1000000001,
       (SELECT max(id) FROM historial_estado_pedido WHERE id>=1000000000), (SELECT count(*) FROM historial_estado_pedido WHERE id>=1000000000),
       'Un hito por estado recorrido; sin fila pendiente, como el sistema real.');
SELECT fn_carga_registrar('fase1','97_fase1_piloto_pedidos','factura_venta', 1000000001,
       (SELECT max(id) FROM factura_venta WHERE id>=1000000000), (SELECT count(*) FROM factura_venta WHERE id>=1000000000),
       'Solo estados >= facturado. total = pedido.total - costo_envio (invariante).');
SELECT fn_carga_registrar('fase1','97_fase1_piloto_pedidos','factura_venta_detalle', 1000000001,
       (SELECT max(id) FROM factura_venta_detalle WHERE id>=1000000000), (SELECT count(*) FROM factura_venta_detalle WHERE id>=1000000000), NULL);
SELECT fn_carga_registrar('fase1','97_fase1_piloto_pedidos','pago', 1000000001,
       (SELECT max(id) FROM pago WHERE id>=1000000000), (SELECT count(*) FROM pago WHERE id>=1000000000),
       'Estado completado; el canal tienda cobra en efectivo.');
SELECT fn_carga_registrar('fase1','97_fase1_piloto_pedidos','transaccion_pago', 1000000001,
       (SELECT max(id) FROM transaccion_pago WHERE id>=1000000000), (SELECT count(*) FROM transaccion_pago WHERE id>=1000000000),
       'tipo captura / estado exitosa: transaccion_pago NO admite completado.');
SELECT fn_carga_registrar('fase1','97_fase1_piloto_pedidos','movimiento_inventario', 1000000001,
       (SELECT max(id) FROM movimiento_inventario WHERE id>=1000000000), (SELECT count(*) FROM movimiento_inventario WHERE id>=1000000000),
       'Reposicion + salida. Neto CERO por posicion: inventario.stock_actual no se escribe.');
SELECT fn_carga_registrar('fase1','97_fase1_piloto_pedidos','orden_compra', 1000000001,
       (SELECT max(id) FROM orden_compra WHERE id>=1000000000), (SELECT count(*) FROM orden_compra WHERE id>=1000000000),
       'Reposicion del piloto, troceada a 25 lineas.');
SELECT fn_carga_registrar('fase1','97_fase1_piloto_pedidos','orden_compra_detalle', 1000000001,
       (SELECT max(id) FROM orden_compra_detalle WHERE id>=1000000000), (SELECT count(*) FROM orden_compra_detalle WHERE id>=1000000000), NULL);
SELECT fn_carga_registrar('fase1','97_fase1_piloto_pedidos','recepcion_mercancia', 1000000001,
       (SELECT max(id) FROM recepcion_mercancia WHERE id>=1000000000), (SELECT count(*) FROM recepcion_mercancia WHERE id>=1000000000), NULL);
SELECT fn_carga_registrar('fase1','97_fase1_piloto_pedidos','recepcion_detalle', 1000000001,
       (SELECT max(id) FROM recepcion_detalle WHERE id>=1000000000), (SELECT count(*) FROM recepcion_detalle WHERE id>=1000000000), NULL);
SELECT fn_carga_registrar('fase1','97_fase1_piloto_pedidos','factura_compra', 1000000001,
       (SELECT max(id) FROM factura_compra WHERE id>=1000000000), (SELECT count(*) FROM factura_compra WHERE id>=1000000000), NULL);
SELECT fn_carga_registrar('fase1','97_fase1_piloto_pedidos','factura_compra_detalle', 1000000001,
       (SELECT max(id) FROM factura_compra_detalle WHERE id>=1000000000), (SELECT count(*) FROM factura_compra_detalle WHERE id>=1000000000), NULL);
SELECT fn_carga_registrar('fase1','97_fase1_piloto_pedidos','cuenta_por_pagar', 1000000001,
       (SELECT max(id) FROM cuenta_por_pagar WHERE id>=1000000000), (SELECT count(*) FROM cuenta_por_pagar WHERE id>=1000000000),
       'Saldadas: son facturas de 2025, todas vencidas hace mas de un anio.');
SELECT fn_carga_registrar('fase1','97_fase1_piloto_pedidos','pago_proveedor', 1000000001,
       (SELECT max(id) FROM pago_proveedor WHERE id>=1000000000), (SELECT count(*) FROM pago_proveedor WHERE id>=1000000000),
       'Pago de la reposicion, a fecha de vencimiento.');

INSERT INTO carga_fase_parametro (fase, clave, valor, nota) VALUES
 ('fase1','tramo_ids','1000000000 .. 1099999999',
  'Tramo reservado de la Fase 1. El techo NO es el bigint de PostgreSQL sino el UInt32 del almacen '
  '(pedido_id, cliente_id, producto_variante_id, documento_id=pago.id, contraparte_id, '
  'orden_compra_id, envio_id): 4.294.967.295. Comprobado en el DDL de las 21 tablas antes de cargar.'),
 ('fase1','ventana_piloto','2025-01-01 .. 2025-12-31',
  '10.000 pedidos. Estacionalidad mensual, hora del dia y coste de envio DERIVADOS de los 4.083 '
  'pedidos existentes; dia de la semana plano porque la serie base lo es.'),
 ('fase1','metodo_stock','reposicion previa por posicion con cierre a cero',
  'Cada unidad vendida se compra antes en la misma posicion. Neto CERO por posicion, asi que '
  'inventario.stock_actual no se escribe y la apertura de la Fase 0 (2026-08-09, stock_anterior=0) '
  'sigue siendo valida sin tocarla. Dos ritmos: bimestral en las posiciones nuevas, emparejada '
  '(cross-docking) en las historicas, donde hay cadena viva que no debe desplazarse.'),
 ('fase1','limitaciones_declaradas','sin stock residual; variantes fase0 vendidas antes de su alta',
  '(1) El piloto compra lo que vende: las posiciones quedan a cero tras su ultima venta. '
  '(2) Las variantes de la Fase 0 se crearon el 2026-08-08 y aqui se venden en 2025; corregirlo '
  'seria reescribir un maestro de la Fase 0. Ninguna consulta lee producto_variante.fecha_creacion.')
ON CONFLICT (fase, clave) DO UPDATE SET valor = EXCLUDED.valor, nota = EXCLUDED.nota;
COMMIT;

\echo ''
\echo '=== CARGA TERMINADA ========================================================'
SELECT (SELECT count(*) FROM pedido                WHERE id>=1000000000) pedidos,
       (SELECT count(*) FROM pedido_detalle        WHERE id>=1000000000) lineas,
       (SELECT count(*) FROM factura_venta         WHERE id>=1000000000) facturas,
       (SELECT count(*) FROM pago                  WHERE id>=1000000000) pagos,
       (SELECT count(*) FROM movimiento_inventario WHERE id>=1000000000) movimientos,
       (SELECT count(*) FROM orden_compra          WHERE id>=1000000000) ordenes,
       (SELECT round(avg(total),2) FROM pedido     WHERE id>=1000000000) ticket_medio;
