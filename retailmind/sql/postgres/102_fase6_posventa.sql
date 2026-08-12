-- ============================================================================
-- 102_fase6_posventa.sql — RetailMind · Fase 6: la POSVENTA de la década
--
--   docker compose exec -T postgres psql -U postgres -d retailmind \
--       -v ON_ERROR_STOP=1 -f - < retailmind/sql/postgres/102_fase6_posventa.sql
--
--   Reanudar tras una interrupción:  añadir  -v reanudar=1
--
-- ---------------------------------------------------------------------------
-- QUÉ CARGA Y POR QUÉ
-- ---------------------------------------------------------------------------
-- La carga masiva escribió el ciclo del pedido (2.999.991) y los envíos
-- (2.110.095), pero dejó la posventa congelada en el período original: 197
-- devoluciones, 249 tickets y 344 reseñas, todos hasta 2026-08. Consecuencia
-- medida: OTD-SOP-02 devolvía CUATRO filas para una década entera.
--
-- ---------------------------------------------------------------------------
-- LAS REGLAS DE ELEGIBILIDAD SALEN DE LOS DATOS, NO DE UNA SUPOSICIÓN
-- ---------------------------------------------------------------------------
-- Medido sobre los 4.083 pedidos originales, cruzando estado × canal:
--
--   DEVOLUCIÓN
--     pedido 'devuelto'  → 100,00 % en los TRES canales (108 de 108, cero
--                          excepciones). Es un invariante, no una tasa.
--     pedido 'entregado' → 2,90 % tienda · 2,43 % web · 2,27 % teléfono.
--                          Plano entre canales, al revés que los envíos.
--     pedido 'despachado'→ 0 %. La app admite el rechazo en puerta; el dato
--                          no lo tiene, así que no se inventa.
--
--   Y la regla que ORDENA todo lo demás, también medida:
--     pedido 'devuelto'  → su devolución está SIEMPRE post-inspección
--                          (reembolsada 56 · cerrada 35 · inspeccionada 17).
--     pedido 'entregado' → 95,5 % pre-inspección; las 4 inspeccionadas NO
--                          tienen ni una línea 'apto_reventa'.
--
--   O sea: **'apto_reventa' es lo que convierte el pedido en 'devuelto'**.
--   El volumen de reingreso al almacén NO se elige: lo fijan los 79.054
--   pedidos 'devuelto' que ya existen.
--
-- ---------------------------------------------------------------------------
-- EL KARDEX: ESTA FASE SÍ LO TOCA, Y ES DELIBERADO
-- ---------------------------------------------------------------------------
-- Ningún trigger reingresa mercancía —`fn_recalcular_total_devolucion` solo
-- recalcula `monto_total`—, pero el dato existente cumple la regla de la
-- aplicación SIN UNA SOLA EXCEPCIÓN:
--
--     119 líneas 'apto_reventa' / 188 uds  ==  119 movimientos
--     'entrada_devolucion_cliente' / 188 uds        (correspondencia 1:1)
--
-- Cargar devoluciones inspeccionadas sin reingresar sería publicar una
-- posventa que se contradice con su propio almacén. Se decidió replicar la
-- mezcla real, con las consecuencias asumidas:
--
--   · se INSERTAN ~87.000 movimientos 'entrada_devolucion_cliente';
--   · las cadenas de las posiciones afectadas se RECALCULAN enteras, porque
--     el kardex se encadena por (fecha_creacion, id) y una entrada en el
--     pasado deja obsoleto el saldo corrido de todo lo que venga después;
--   · `inventario.stock_actual` SUBE ~137.000 unidades.
--
-- Tres cosas hacen que esto sea seguro y no una ruleta:
--   (1) un reingreso es SIEMPRE +N, así que ningún saldo puede volverse
--       negativo por recalcular: solo pueden subir;
--   (2) el recálculo no confía en el saldo guardado — reconstruye la suma
--       corrida desde el origen de cada cadena, así que si algo estaba mal
--       antes, sale a la luz aquí;
--   (3) la fecha del reingreso ES el hito 'devuelto' del pedido, leído de
--       `historial_estado_pedido`. La coherencia temporal no se verifica
--       después: sale por construcción.
--
-- ---------------------------------------------------------------------------
-- PROCEDENCIA POR TRAMO DE IDS RESERVADO
-- ---------------------------------------------------------------------------
-- Ni columna ni marcador de texto: un rango de ids que no comparte con nadie.
-- El techo real NO es el `bigint` de PostgreSQL sino el tipo más estrecho por
-- el que cada id pasa, y el DDL de ClickHouse declara UInt32 (4.294.967.295)
-- en `devolucion_id`, `ticket_id` y `resena_id`. Comprobado uno a uno:
-- `devolucion_detalle_id` y `movimiento_id` son UInt64, así que sus tramos
-- altos no corren riesgo.
--
--     devolucion                   2.600.000.000
--     devolucion_detalle           2.700.000.000
--     historial_estado_devolucion  2.800.000.000
--     ticket_soporte               2.900.000.000
--     resena                       3.000.000.000
--     reembolso                    3.100.000.000
--     item_defectuoso              3.200.000.000
--     movimiento_inventario        3.300.000.000   (reingreso RMA)
--
-- El id más alto que se escribirá ronda 3,30e9 = 77 % del techo UInt32.
--
-- Y el tramo de claves primarias NO cubre las claves ÚNICAS DE NEGOCIO:
-- `devolucion.numero` y `ticket_soporte.numero` llevan banda propia de SIETE
-- dígitos desde 9.100.000, donde el seed usa seis. Se comprueba contra lo
-- existente ANTES de escribir, no después.
--
-- SE AUTOVERIFICA Y ABORTA. Reversión: `99_revert_fase6.sql`.
-- ============================================================================
\set ON_ERROR_STOP on
\timing on
\if :{?reanudar}
\else
  \set reanudar 0
\endif

-- ── Tramos, como constantes de una sola definición ──────────────────────────
\set B_DEV 2600000000
\set B_DET 2700000000
\set B_HIS 2800000000
\set B_TKT 2900000000
\set B_RES 3000000000
\set B_REE 3100000000
\set B_ITD 3200000000
\set B_MOV 3300000000

-- ════════════════════════════════════════════════════════════════════════════
-- BLOQUE 0 · GUARDIAS. Nada se escribe hasta que todo esto pasa.
-- ════════════════════════════════════════════════════════════════════════════
BEGIN;

-- psql NO interpola :vars dentro de un bloque con comillas de dólar, así que
-- la válvula viaja por GUC. Es la misma trampa que costó tiempo en la Fase 2.
SELECT set_config('fase6.reanudar', :'reanudar', false);

DO $g$
DECLARE
    n bigint;
    v_techo    bigint := 4294967295;
    v_reanudar boolean := current_setting('fase6.reanudar', true) = '1';
BEGIN
    -- (a) Los tramos tienen que estar VACÍOS, o la reversión por rango se
    --     llevaría por delante filas que no son suyas. Al REANUDAR se omite,
    --     que es justo el caso en que están ocupados a propósito.
    IF NOT v_reanudar THEN
      SELECT count(*) INTO n FROM devolucion WHERE id >= 2600000000;
      IF n > 0 THEN RAISE EXCEPTION 'ABORTA: el tramo de devolucion ya tiene % filas.', n; END IF;
      SELECT count(*) INTO n FROM ticket_soporte WHERE id >= 2900000000;
      IF n > 0 THEN RAISE EXCEPTION 'ABORTA: el tramo de ticket ya tiene % filas.', n; END IF;
      SELECT count(*) INTO n FROM resena WHERE id >= 3000000000;
      IF n > 0 THEN RAISE EXCEPTION 'ABORTA: el tramo de resena ya tiene % filas.', n; END IF;
      SELECT count(*) INTO n FROM movimiento_inventario WHERE id >= 3300000000;
      IF n > 0 THEN RAISE EXCEPTION 'ABORTA: el tramo del kardex ya tiene % filas.', n; END IF;
    ELSE
      RAISE NOTICE 'REANUDANDO: los bloques ya completados se saltan solos.';
    END IF;

    -- (b) El techo UInt32 del almacén, comprobado con el volumen REAL que se
    --     va a escribir y no con una estimación optimista.
    SELECT 3200000000 + count(*) INTO n FROM pedido p JOIN estado_pedido ep ON ep.id = p.estado_pedido_id
     WHERE ep.codigo IN ('entregado','devuelto');
    IF n > v_techo THEN
        RAISE EXCEPTION 'ABORTA: el id más alto (%) supera el UInt32 del DWH (%).', n, v_techo;
    END IF;

    -- (c) La banda de numeración de SIETE dígitos tiene que estar libre. Un
    --     choque aquí no se ve hasta que la UNIQUE revienta a mitad de carga.
    --     Va DENTRO de la condición de reanudación por el mismo motivo que
    --     (a): al reanudar, la banda está ocupada por la propia fase.
    IF NOT v_reanudar THEN
      SELECT count(*) INTO n FROM devolucion WHERE numero ~ '^DV-[0-9]{8}-9[0-9]{6}$';
      IF n > 0 THEN RAISE EXCEPTION 'ABORTA: la banda DV-…-9xxxxxx ya tiene % números.', n; END IF;
      SELECT count(*) INTO n FROM ticket_soporte WHERE numero ~ '^TK-[0-9]{8}-9[0-9]{6}$';
      IF n > 0 THEN RAISE EXCEPTION 'ABORTA: la banda TK-…-9xxxxxx ya tiene % números.', n; END IF;
    END IF;

    RAISE NOTICE 'Guardias OK: 8 tramos libres, banda de numeración libre, techo UInt32 con holgura.';
END
$g$;

-- ── Andamio. Vive en su propio esquema y se derriba al final. ───────────────
DROP SCHEMA IF EXISTS fase6_stg CASCADE;
CREATE SCHEMA fase6_stg;

-- Generador determinista: misma semilla, mismo resultado, siempre. La SAL
-- (segundo argumento) es obligatoria porque el mismo pedido alimenta muchos
-- sorteos independientes y sin ella todos saldrían correlacionados.
CREATE FUNCTION fase6_stg.u(semilla bigint, sal int) RETURNS double precision
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT (abs(hashtextextended(semilla::text || ':' || sal::text, 0)) % 1000000)::double precision
           / 1000000.0
$$;

COMMIT;

-- ════════════════════════════════════════════════════════════════════════════
-- BLOQUE 1 · EL PLAN DE DEVOLUCIONES
--   Una fila por devolución, con su estado, sus fechas y su escalera ya
--   resueltos. Se materializa para que los bloques siguientes lean de aquí en
--   vez de repetir el sorteo — un sorteo repetido es un sorteo que puede
--   discrepar consigo mismo.
-- ════════════════════════════════════════════════════════════════════════════
BEGIN;

CREATE UNLOGGED TABLE fase6_stg.plan_dev AS
WITH ent AS (
    SELECT h.pedido_id, min(h.fecha_creacion) f_ent
    FROM historial_estado_pedido h WHERE h.estado_pedido_id = 6 GROUP BY 1),
dev AS (
    SELECT h.pedido_id, min(h.fecha_creacion) f_dev
    FROM historial_estado_pedido h WHERE h.estado_pedido_id = 8 GROUP BY 1),
base AS (
    SELECT p.id pedido_id, p.cliente_id, p.canal, ep.codigo est_ped,
           e.f_ent, d.f_dev
    FROM pedido p
    JOIN estado_pedido ep ON ep.id = p.estado_pedido_id
    JOIN ent e            ON e.pedido_id = p.id
    LEFT JOIN dev d       ON d.pedido_id = p.id
    WHERE ep.codigo IN ('entregado','devuelto')
      -- Un 'devuelto' sin su hito no se puede fechar por construcción: son 4
      -- de 79.054 y se dejan fuera antes que inventarles una fecha.
      AND (ep.codigo = 'entregado' OR d.f_dev IS NOT NULL)
      -- Y NUNCA un pedido que ya tiene devolución de la siembra original.
      -- La regla «todo pedido devuelto lleva devolución» es cierta, pero
      -- aplicarla a los 79.054 sin mirar quién ya estaba servido dejó 105
      -- pedidos con DOS devoluciones, y como cada una escoge líneas del mismo
      -- pedido, 88 líneas devolvieron más unidades de las que se vendieron.
      -- Lo cazó el control del ETL, no una lectura del código: el invariante
      -- «no se devuelve más de lo comprado» parece imposible de violar, y por
      -- eso nadie lo mira. Corregido en el 105.
      AND NOT EXISTS (SELECT 1 FROM devolucion x WHERE x.pedido_id = p.id)
),
elegidas AS (
    SELECT b.*,
           CASE WHEN b.est_ped = 'devuelto' THEN true
                ELSE fase6_stg.u(b.pedido_id, 11) <
                     CASE b.canal WHEN 'tienda' THEN 0.0290
                                  WHEN 'web'    THEN 0.0243
                                  ELSE               0.0227 END
           END AS entra
    FROM base b
)
SELECT
    (2600000000 + row_number() OVER (ORDER BY pedido_id))::bigint AS id,
    pedido_id, cliente_id, canal, est_ped, f_ent, f_dev,
    -- ESTADO. Dos distribuciones distintas, porque la regla medida dice que
    -- son dos poblaciones distintas y no una con ruido.
    CASE WHEN est_ped = 'devuelto' THEN
        CASE WHEN fase6_stg.u(pedido_id, 21) < 0.5185 THEN 'reembolsada'
             WHEN fase6_stg.u(pedido_id, 21) < 0.8426 THEN 'cerrada'
             ELSE 'inspeccionada' END
    ELSE
        CASE WHEN fase6_stg.u(pedido_id, 22) < 0.2135 THEN 'rechazada'
             WHEN fase6_stg.u(pedido_id, 22) < 0.3933 THEN 'en_revision'
             WHEN fase6_stg.u(pedido_id, 22) < 0.5506 THEN 'recibida'
             WHEN fase6_stg.u(pedido_id, 22) < 0.7079 THEN 'solicitada'
             WHEN fase6_stg.u(pedido_id, 22) < 0.8427 THEN 'aprobada'
             WHEN fase6_stg.u(pedido_id, 22) < 0.9551 THEN 'en_transito'
             ELSE 'inspeccionada' END
    END AS estado,
    -- MOTIVO, a las proporciones medidas (59/55/42/41 sobre 197).
    CASE WHEN fase6_stg.u(pedido_id, 23) < 0.2995 THEN 2
         WHEN fase6_stg.u(pedido_id, 23) < 0.5787 THEN 4
         WHEN fase6_stg.u(pedido_id, 23) < 0.7919 THEN 3
         ELSE 1 END AS motivo_id
FROM elegidas WHERE entra;

CREATE UNIQUE INDEX ON fase6_stg.plan_dev (id);
CREATE UNIQUE INDEX ON fase6_stg.plan_dev (pedido_id);
ANALYZE fase6_stg.plan_dev;

-- ── La escalera: cuántos peldaños tiene cada estado final ───────────────────
-- Medido: el historial existente va de 1 a 8 hitos con media 5,13, que es
-- exactamente lo que produce esta escalera con la mezcla de estados medida.
CREATE UNLOGGED TABLE fase6_stg.escalera (estado text PRIMARY KEY, peldanos int);
INSERT INTO fase6_stg.escalera VALUES
    ('solicitada',1), ('en_revision',2), ('aprobada',3), ('rechazada',3),
    ('en_transito',4), ('recibida',5), ('inspeccionada',6),
    ('reembolsada',7), ('cerrada',8);

-- ── Fecha de cada peldaño ───────────────────────────────────────────────────
-- Para un pedido 'devuelto' los seis primeros peldaños se INTERPOLAN entre la
-- entrega y el hito 'devuelto', de modo que el peldaño 6 (la inspección) cae
-- EXACTAMENTE sobre ese hito: la coherencia temporal no se comprueba luego,
-- se construye. Los peldaños 7 y 8 van después, que es cuando ocurren.
-- EL DESPLAZAMIENTO SE ACUMULA. NO SE SORTEA POR PELDAÑO.
-- La primera versión daba a cada peldaño su propio multiplicador aleatorio
-- —`(p_i - 1) * 1 día * (1 + 3·u(70 + p_i))`— y eso NO es monótono: el
-- peldaño 3 podía sortear un factor bajo (2 días × 1,0) y quedar ANTES que el
-- peldaño 2, que había sorteado uno alto (1 día × 4,0). Resultado medido:
-- 18.210 hitos fuera de orden sobre 770.741, concentrados justo donde el
-- rango de dos peldaños consecutivos se solapa (peldaños 3, 4, 5 y 8).
-- La suma acumulada de incrementos ESTRICTAMENTE POSITIVOS lo hace imposible
-- por construcción, que es como debía haber estado desde el principio.
CREATE FUNCTION fase6_stg.fecha_peldano(
    p_pedido bigint, p_ent timestamptz, p_dev timestamptz, p_i int)
RETURNS timestamptz LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT CASE
      WHEN p_dev IS NOT NULL AND p_i <= 6 THEN
          -- El microsegundo decreciente garantiza orden estricto incluso
          -- cuando entrega y devolución caen en el mismo instante, y deja el
          -- peldaño 6 EXACTAMENTE sobre el hito 'devuelto'.
          p_ent + (p_dev - p_ent) * p_i / 6.0 - ((6 - p_i) * interval '1 microsecond')
      WHEN p_dev IS NOT NULL THEN
          p_dev + (SELECT sum(1 + 4 * fase6_stg.u(p_pedido, 60 + k))
                   FROM generate_series(7, p_i) k) * interval '1 day'
      ELSE
          -- Sin hito 'devuelto': se cuenta desde la entrega, dentro de la
          -- ventana de 30 días que declara PLAZO_DIAS_DEVOLUCION.
          p_ent + (1 + floor(29 * fase6_stg.u(p_pedido, 31))) * interval '1 day'
                + COALESCE((SELECT sum(1 + 3 * fase6_stg.u(p_pedido, 70 + k))
                            FROM generate_series(2, p_i) k), 0) * interval '1 day'
    END
$$;

COMMIT;

\echo ''
\echo '── plan de devoluciones ──'
SELECT est_ped, count(*) FROM fase6_stg.plan_dev GROUP BY 1 ORDER BY 2 DESC;
SELECT estado, count(*) FROM fase6_stg.plan_dev GROUP BY 1 ORDER BY 2 DESC;

-- ════════════════════════════════════════════════════════════════════════════
-- BLOQUE 2 · CABECERAS DE DEVOLUCIÓN
--   `monto_total` NO se escribe: lo pone el trigger fn_recalcular_total_devolucion
--   desde el detalle. Escribirlo aquí sería pisar al motor.
-- ════════════════════════════════════════════════════════════════════════════
BEGIN;

INSERT INTO devolucion (id, numero, pedido_id, motivo_devolucion_id, usuario_gestiona_id,
                        estado, descripcion, fecha_creacion, cliente_id,
                        transportista_id, bodega_id, guia_retorno, motivo_rechazo,
                        monto_reembolsado, metodo_reembolso, fecha_reembolso)
OVERRIDING SYSTEM VALUE
SELECT
    d.id,
    -- Banda de SIETE dígitos (el seed usa seis) con secuencia POR DÍA.
    'DV-' || to_char(f1.f, 'YYYYMMDD') || '-9' ||
        lpad((100000 + row_number() OVER (PARTITION BY f1.f::date ORDER BY d.id))::text, 6, '0'),
    d.pedido_id, d.motivo_id,
    CASE WHEN d.estado <> 'solicitada' THEN 12 END,          -- soporte@ gestiona
    d.estado,
    'Devolución registrada por el cliente desde Mis Pedidos.',
    f1.f,
    d.cliente_id,
    CASE WHEN e.peldanos >= 4 THEN (ARRAY[1,2,6,7,8])[1 + floor(5 * fase6_stg.u(d.pedido_id, 41))::int] END,
    -- Bodega de retorno: SIEMPRE la 4. No es una simplificación, es el dato:
    -- las 143 devoluciones existentes con bodega asignada tienen las 143 la
    -- Central de Quevedo. Además evita crear posiciones de inventario nuevas.
    CASE WHEN e.peldanos >= 4 THEN 4 END,
    CASE WHEN e.peldanos >= 4 THEN 'RET-' || to_char(f1.f, 'YYYYMMDD') || '-' ||
        lpad((abs(hashtextextended(d.id::text, 7)) % 100000)::text, 5, '0') END,
    CASE WHEN d.estado = 'rechazada' THEN 'Fuera del plazo de 30 días o producto sin embalaje original.' END,
    NULL, NULL, NULL                                          -- se rellenan en el bloque 5
FROM fase6_stg.plan_dev d
JOIN fase6_stg.escalera e ON e.estado = d.estado
CROSS JOIN LATERAL (SELECT fase6_stg.fecha_peldano(d.pedido_id, d.f_ent, d.f_dev, 1) AS f) f1
WHERE NOT EXISTS (SELECT 1 FROM devolucion x WHERE x.id >= 2600000000);

COMMIT;

\echo ''
\echo '── cabeceras insertadas ──'
SELECT count(*) FROM devolucion WHERE id >= 2600000000;

-- ════════════════════════════════════════════════════════════════════════════
-- BLOQUE 3 · LÍNEAS DE DEVOLUCIÓN
--   1 o 2 líneas (media medida 1,40) tomadas de las líneas reales del pedido.
--   `resultado_inspeccion` solo existe a partir del peldaño 6, y su mezcla
--   depende del estado del PEDIDO, que es la regla medida: 'apto_reventa'
--   únicamente donde el pedido acabó 'devuelto'.
-- ════════════════════════════════════════════════════════════════════════════
BEGIN;

CREATE UNLOGGED TABLE fase6_stg.plan_det AS
WITH lineas AS (
    SELECT d.id dev_id, d.pedido_id, d.estado, d.est_ped, d.f_ent, d.f_dev,
           pd.id pd_id, pd.cantidad cant_pedida, pd.producto_variante_id,
           row_number() OVER (PARTITION BY d.id
                              ORDER BY fase6_stg.u(pd.id, 51)) rn
    FROM fase6_stg.plan_dev d
    JOIN pedido_detalle pd ON pd.pedido_id = d.pedido_id
)
SELECT
    (2700000000 + row_number() OVER (ORDER BY dev_id, pd_id))::bigint AS id,
    dev_id, pd_id, producto_variante_id, estado, est_ped, pedido_id, f_ent, f_dev,
    -- 69,5 % devuelve la línea entera; el resto una parte (fracción media 0,845).
    GREATEST(1, CASE WHEN fase6_stg.u(pd_id, 52) < 0.695 THEN cant_pedida
                     ELSE ceil(cant_pedida * 0.5)::int END) AS cantidad,
    CASE WHEN fase6_stg.u(pd_id, 53) < 0.45 THEN 'nuevo'
         WHEN fase6_stg.u(pd_id, 53) < 0.80 THEN 'abierto'
         ELSE 'danado' END AS estado_producto
FROM lineas
WHERE rn = 1 OR (rn = 2 AND fase6_stg.u(dev_id, 54) < 0.40);

ALTER TABLE fase6_stg.plan_det ADD COLUMN resultado text;

-- La mezcla de inspección, separada para que la regla se lea de un vistazo.
UPDATE fase6_stg.plan_det SET resultado =
    CASE
      WHEN estado NOT IN ('inspeccionada','reembolsada','cerrada') THEN NULL
      -- Pedido 'devuelto' ⇒ hubo mercancía apta: es lo que lo dejó devuelto.
      WHEN est_ped = 'devuelto' THEN
          CASE WHEN fase6_stg.u(pd_id, 55) < 0.755 THEN 'apto_reventa'
               ELSE 'defectuoso' END
      -- Pedido 'entregado' e inspeccionada ⇒ NO hubo nada apto, o el pedido
      -- habría pasado a 'devuelto'. Las 4 filas medidas lo confirman.
      ELSE CASE WHEN fase6_stg.u(pd_id, 55) < 0.625 THEN 'defectuoso'
                ELSE 'rechazado' END
    END;

CREATE UNIQUE INDEX ON fase6_stg.plan_det (id);
CREATE INDEX ON fase6_stg.plan_det (dev_id);
CREATE INDEX ON fase6_stg.plan_det (resultado) WHERE resultado = 'apto_reventa';
ANALYZE fase6_stg.plan_det;

COMMIT;

-- ── 3b · LA INSERCIÓN, y la lección más cara de esta fase ────────────────────
-- `devolucion_detalle` tiene un trigger AFTER INSERT **FOR EACH ROW**
-- (`fn_recalcular_total_devolucion`) que recalcula el total de la cabecera
-- leyendo todas las líneas de esa devolución. Con la tabla en 277 filas, el
-- planificador elige razonablemente un recorrido SECUENCIAL... y plpgsql
-- CACHEA ese plan. A partir de ahí cada una de las 204.000 inserciones vuelve
-- a recorrer una tabla que ya no es pequeña: coste CUADRÁTICO.
--
-- Medido en el primer intento, que hubo que abortar a los 24 minutos:
--     devolucion_detalle → 2.707.318 recorridos secuenciales
--                          744.512.450 tuplas leídas
--                                  972 accesos por índice
-- No iba lento: no iba a terminar.
--
-- El arreglo NO es desactivar el trigger —está prohibido y además es el que
-- mantiene `monto_total`, que jamás se escribe a mano—, sino quitarle al
-- planificador las dos opciones malas mientras dura la carga. `SET LOCAL` se
-- deshace solo al COMMIT.
--
-- Y hubo una SEGUNDA causa, más sutil, que el ANALYZE no arregló y que solo
-- apareció al leer el plan de verdad: el trigger une `devolucion_detalle` con
-- `pedido_detalle` (7,55 M filas) y el planificador elegía un MERGE JOIN.
-- Para casar UNA fila, un merge join recorre el índice del lado interno desde
-- el principio: coste estimado 8,99 «porque terminará pronto», coste real un
-- paseo por millones de entradas, 190.141 veces. Con nested loop el mismo
-- acceso es un Index Cond de coste 8,45:
--
--     merge join   ->  Index Scan pedido_detalle_pkey  (cost=0.43..389182.10)
--     nested loop  ->  Index Scan pedido_detalle_pkey  (cost=0.43..8.45)
--                          Index Cond: (id = dd.pedido_detalle_id)
--
-- Un plan que el optimizador considera BARATO puede ser el desastre, y la
-- única forma de verlo fue pedirle el plan en vez de mirar el reloj.
BEGIN;
SET LOCAL enable_seqscan  = off;
SET LOCAL enable_mergejoin = off;

INSERT INTO devolucion_detalle (id, devolucion_id, pedido_detalle_id, cantidad,
                                estado_producto, accion, fecha_creacion,
                                resultado_inspeccion, nota_inspeccion)
OVERRIDING SYSTEM VALUE
SELECT t.id, t.dev_id, t.pd_id, t.cantidad, t.estado_producto,
       CASE WHEN fase6_stg.u(t.pd_id, 56) < 0.88 THEN 'reembolso'
            WHEN fase6_stg.u(t.pd_id, 56) < 0.96 THEN 'cambio' ELSE 'credito' END,
       fase6_stg.fecha_peldano(t.pedido_id, t.f_ent, t.f_dev, 1),
       t.resultado,
       CASE t.resultado
         WHEN 'apto_reventa' THEN 'Producto en estado vendible; reingresa a stock.'
         WHEN 'defectuoso'   THEN 'Producto con daño; pasa al pool de devolución a proveedor.'
         WHEN 'rechazado'    THEN 'No cumple las condiciones de devolución; sin reembolso.' END
FROM fase6_stg.plan_det t
WHERE NOT EXISTS (SELECT 1 FROM devolucion_detalle x WHERE x.id >= 2700000000);

COMMIT;

\echo ''
\echo '── lineas y mezcla de inspeccion ──'
SELECT resultado_inspeccion, count(*), sum(cantidad) uds
FROM devolucion_detalle WHERE id >= 2700000000 GROUP BY 1 ORDER BY 2 DESC;

-- ════════════════════════════════════════════════════════════════════════════
-- BLOQUE 4 · HISTORIAL DE LA DEVOLUCIÓN
--   Un peldaño por hito, con su fecha propia. El autor alterna cliente y
--   usuario según el hito, como en el flujo real: el cliente solicita, el
--   soporte valida, despacho mueve, bodega inspecciona, gerencia reembolsa.
-- ════════════════════════════════════════════════════════════════════════════
BEGIN;

INSERT INTO historial_estado_devolucion (id, devolucion_id, estado, usuario_id,
                                         cliente_id, comentario, fecha_creacion)
SELECT
    2800000000 + row_number() OVER (ORDER BY d.id, s.i),
    d.id,
    (ARRAY['solicitada','en_revision','aprobada','en_transito','recibida',
           'inspeccionada','reembolsada','cerrada'])[s.i],
    -- El peldaño 1 lo firma el cliente; los demás, el rol que corresponde.
    CASE s.i WHEN 1 THEN NULL WHEN 2 THEN 12 WHEN 3 THEN 12
             WHEN 4 THEN 10 WHEN 5 THEN 10 WHEN 6 THEN 9
             WHEN 7 THEN 6  ELSE 12 END,
    CASE WHEN s.i = 1 THEN d.cliente_id END,
    NULL,
    fase6_stg.fecha_peldano(d.pedido_id, d.f_ent, d.f_dev, s.i)
FROM fase6_stg.plan_dev d
JOIN fase6_stg.escalera e ON e.estado = d.estado
CROSS JOIN LATERAL generate_series(1, e.peldanos) s(i)
-- 'rechazada' es terminal en el peldaño 3 y no pasa por 'aprobada'.
WHERE NOT (d.estado = 'rechazada' AND s.i = 3)
  AND NOT EXISTS (SELECT 1 FROM historial_estado_devolucion x WHERE x.id >= 2800000000);

-- El hito terminal de las rechazadas, que la escalera genérica no cubre.
INSERT INTO historial_estado_devolucion (id, devolucion_id, estado, usuario_id,
                                         cliente_id, comentario, fecha_creacion)
SELECT 2850000000 + row_number() OVER (ORDER BY d.id), d.id, 'rechazada', 12, NULL,
       'Solicitud rechazada tras la revisión.',
       fase6_stg.fecha_peldano(d.pedido_id, d.f_ent, d.f_dev, 3)
FROM fase6_stg.plan_dev d WHERE d.estado = 'rechazada'
  AND NOT EXISTS (SELECT 1 FROM historial_estado_devolucion x WHERE x.id >= 2850000000);

COMMIT;

\echo ''
\echo '── historial ──'
SELECT count(*) hitos, round(count(*)::numeric / (SELECT count(*) FROM fase6_stg.plan_dev), 2) por_devolucion
FROM historial_estado_devolucion WHERE id >= 2800000000;

-- ════════════════════════════════════════════════════════════════════════════
-- BLOQUE 5 · REEMBOLSOS
--   Solo para las devoluciones que llegaron a 'reembolsada' o 'cerrada'.
--   El monto sale del pedido_detalle real (precio × cantidad devuelta), no de
--   un porcentaje inventado. Se reembolsa lo apto y lo defectuoso; lo
--   'rechazado' no, que es la regla del sistema.
-- ════════════════════════════════════════════════════════════════════════════
BEGIN;

DROP TABLE IF EXISTS fase6_stg.plan_ree;
CREATE UNLOGGED TABLE fase6_stg.plan_ree AS
SELECT d.id dev_id, d.pedido_id,
       round(sum(t.cantidad * pd.precio_unitario), 2) AS monto,
       fase6_stg.fecha_peldano(d.pedido_id, d.f_ent, d.f_dev, 7) AS fecha
FROM fase6_stg.plan_dev d
JOIN fase6_stg.plan_det t ON t.dev_id = d.id
JOIN pedido_detalle pd    ON pd.id = t.pd_id
WHERE d.estado IN ('reembolsada','cerrada')
  AND t.resultado IN ('apto_reventa','defectuoso')
GROUP BY 1, 2, 4
HAVING sum(t.cantidad * pd.precio_unitario) > 0;

-- La cabecera guarda la VÍA del reembolso, que es media pregunta de OTD-LOG-10.
UPDATE devolucion d
   SET monto_reembolsado = r.monto,
       metodo_reembolso  = CASE WHEN fase6_stg.u(d.id, 81) < 0.62 THEN 'tarjeta_original'
                                WHEN fase6_stg.u(d.id, 81) < 0.88 THEN 'transferencia'
                                ELSE 'nota_credito' END,
       fecha_reembolso   = r.fecha
FROM fase6_stg.plan_ree r WHERE d.id = r.dev_id;

-- El asiento. Cuelga del pago del pedido; sin pago no hay reembolso que
-- registrar (y así se replica el hueco medido: 86 cabeceras, 85 asientos).
INSERT INTO reembolso (id, pago_id, devolucion_id, monto, motivo, estado,
                       referencia_externa, fecha_procesado, fecha_creacion)
OVERRIDING SYSTEM VALUE
SELECT 3100000000 + row_number() OVER (ORDER BY r.dev_id),
       pg.id, r.dev_id, r.monto, 'Reembolso por devolución aprobada.', 'procesado',
       'REF-' || lpad((abs(hashtextextended(r.dev_id::text, 9)) % 100000000)::text, 8, '0'),
       r.fecha, r.fecha
FROM fase6_stg.plan_ree r
JOIN LATERAL (SELECT p.id FROM pago p
              WHERE p.pedido_id = r.pedido_id AND p.estado = 'completado'
              ORDER BY p.id LIMIT 1) pg ON true
WHERE NOT EXISTS (SELECT 1 FROM reembolso x WHERE x.id >= 3100000000);

COMMIT;

\echo ''
\echo '── reembolsos ──'
SELECT (SELECT count(*) FROM devolucion WHERE id>=2600000000 AND monto_reembolsado IS NOT NULL) cabeceras,
       (SELECT count(*) FROM reembolso WHERE id>=3100000000) asientos,
       (SELECT round(sum(monto),2) FROM reembolso WHERE id>=3100000000) importe;

-- ════════════════════════════════════════════════════════════════════════════
-- BLOQUE 6 · ITEMS DEFECTUOSOS
--   Cada línea 'defectuoso' cae al pool que gestiona Compras. NO mueve stock:
--   esa mercancía nunca reingresó. El proveedor va NULL a propósito — es lo
--   que hace la aplicación cuando no puede rastrearlo, y Compras lo asigna.
-- ════════════════════════════════════════════════════════════════════════════
BEGIN;

INSERT INTO item_defectuoso (producto_variante_id, bodega_id, cantidad, origen,
                             devolucion_detalle_id, proveedor_id, costo_unitario,
                             estado, nota, registrado_por, fecha_creacion)
SELECT t.producto_variante_id,
       COALESCE(d.bodega_id, 4),
       t.cantidad, 'rma', t.id, NULL, pv.costo, 'pendiente',
       'Detectado en la inspección de la devolución ' || d.numero || '.',
       9,
       fase6_stg.fecha_peldano(t.pedido_id, t.f_ent, t.f_dev, 6)
FROM fase6_stg.plan_det t
JOIN devolucion d        ON d.id = t.dev_id
JOIN producto_variante pv ON pv.id = t.producto_variante_id
WHERE t.resultado = 'defectuoso'
  AND NOT EXISTS (SELECT 1 FROM item_defectuoso x
                  WHERE x.origen = 'rma' AND x.devolucion_detalle_id >= 2700000000);

COMMIT;

\echo ''
\echo '── items defectuosos ──'
SELECT count(*), sum(cantidad) uds FROM item_defectuoso WHERE origen='rma' AND devolucion_detalle_id >= 2700000000;
