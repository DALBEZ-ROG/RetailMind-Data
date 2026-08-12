-- ============================================================================
-- 103_fase6_kardex_soporte.sql — RetailMind · Fase 6, segunda mitad
--
--   docker compose exec -T postgres psql -U postgres -d retailmind \
--       -v ON_ERROR_STOP=1 -f - < retailmind/sql/postgres/103_fase6_kardex_soporte.sql
--
--   Reanudar tras una interrupción:  añadir  -v reanudar=1
--
-- Va SEPARADO del 102 a propósito. El 102 escribe hojas del modelo —nada
-- cuelga de una devolución— y se revierte con un DELETE. Este toca el KARDEX,
-- que es la única estructura del sistema con estado acumulado: cada fila
-- guarda el saldo que dejó, así que insertar en el pasado obliga a reescribir
-- todo lo que venga después en esa posición. Mezclar ambas cosas en un solo
-- archivo habría hecho que una interrupción a mitad dejara devoluciones
-- inspeccionadas sin su reingreso, que es justo la incoherencia que esta fase
-- existe para no crear.
--
-- Prerrequisito: el 102 ejecutado (deja `fase6_stg` en pie con el plan).
-- ============================================================================
\set ON_ERROR_STOP on
\timing on
\if :{?reanudar}
\else
  \set reanudar 0
\endif

-- ── Guardias ────────────────────────────────────────────────────────────────
DO $g$
DECLARE n bigint;
BEGIN
    IF to_regclass('fase6_stg.plan_det') IS NULL THEN
        RAISE EXCEPTION 'ABORTA: falta fase6_stg.plan_det. Ejecuta antes el 102.';
    END IF;
    SELECT count(*) INTO n FROM movimiento_inventario WHERE id >= 3300000000;
    IF n > 0 AND current_setting('fase6.reanudar', true) IS DISTINCT FROM '1' THEN
        RAISE WARNING 'El tramo del kardex ya tiene % filas: se saltará el paso 7a.', n;
    END IF;
END
$g$;

-- ════════════════════════════════════════════════════════════════════════════
-- BLOQUE 7 · EL KARDEX. La parte delicada.
--
--   Tres pasos que NO se pueden reordenar:
--     7a  insertar los reingresos con la ECUACIÓN satisfecha pero el ENLACE
--         todavía falso (stock_anterior = 0). El trigger valida la FILA y no
--         el enlace — es la trampa C-2 documentada, y aquí se usa a favor.
--     7b  RECALCULAR la suma corrida de cada posición tocada desde el origen
--         de su cadena. No se parchea el saldo guardado: se reconstruye, así
--         que si algo estuviera mal de antes, sale a la luz aquí.
--     7c  llevar `inventario.stock_actual` al saldo nuevo.
--
--   Un reingreso es siempre +N, luego ningún saldo puede volverse NEGATIVO al
--   recalcular: solo pueden subir. Ésa es la razón por la que este cambio es
--   seguro y el simétrico —una salida insertada en el pasado— no lo sería.
-- ════════════════════════════════════════════════════════════════════════════
BEGIN;

-- 7a ── los movimientos ──────────────────────────────────────────────────────
-- La bodega se elige entre las que YA tienen posición para esa variante: las
-- 11.406 posiciones tienen que seguir siendo 11.406.
INSERT INTO movimiento_inventario (id, producto_variante_id, bodega_id, tipo_movimiento_id,
                                   usuario_id, cantidad, stock_anterior, stock_nuevo,
                                   costo_unitario, referencia_tipo, referencia_id,
                                   observacion, fecha_creacion)
OVERRIDING SYSTEM VALUE
SELECT 3300000000 + row_number() OVER (ORDER BY t.id),
       t.producto_variante_id, bod.bodega_id, 2, 9, t.cantidad,
       0, t.cantidad,                    -- enlace provisional; lo fija el paso 7b
       pv.costo, 'devolucion', t.dev_id,
       'Reingreso por devolución de cliente aprobada en inspección.',
       -- LA FECHA ES EL HITO 'devuelto' DEL PEDIDO, leído de
       -- historial_estado_pedido. La coherencia temporal sale por
       -- construcción, no de una comprobación posterior.
       fase6_stg.fecha_peldano(t.pedido_id, t.f_ent, t.f_dev, 6)
FROM fase6_stg.plan_det t
JOIN producto_variante pv ON pv.id = t.producto_variante_id
JOIN LATERAL (SELECT i.bodega_id FROM inventario i
              WHERE i.producto_variante_id = t.producto_variante_id
              ORDER BY (i.bodega_id = 4) DESC, i.bodega_id LIMIT 1) bod ON true
WHERE t.resultado = 'apto_reventa'
  AND NOT EXISTS (SELECT 1 FROM movimiento_inventario m WHERE m.id >= 3300000000);

DROP TABLE IF EXISTS fase6_stg.pos_afectada;
CREATE UNLOGGED TABLE fase6_stg.pos_afectada AS
SELECT DISTINCT producto_variante_id v, bodega_id b
FROM movimiento_inventario WHERE id >= 3300000000;
CREATE UNIQUE INDEX ON fase6_stg.pos_afectada (v, b);
ANALYZE fase6_stg.pos_afectada;

COMMIT;

\echo ''
\echo '-- 7a reingresos insertados --'
SELECT count(*) movimientos, sum(cantidad) unidades,
       (SELECT count(*) FROM fase6_stg.pos_afectada) posiciones_a_recalcular
FROM movimiento_inventario WHERE id >= 3300000000;

-- 7b ── reencadenado, por lotes de variantes con COMMIT ──────────────────────
-- OJO CON EL LOTE: se itera sobre los VALORES que existen, jamás sobre el
-- rango de ids. La primera versión avanzaba de 200 en 200 entre el mínimo y el
-- máximo, y aquí eso es una trampa mortal: los ids de variante van del 2 al
-- 900.004.999 —la Fase 0 reservó el tramo 900.000.000— para solo 5.911 valores
-- distintos, así que el bucle recorría 4,5 MILLONES de rangos vacíos. No daba
-- error ni se colgaba: cometía un lote por milisegundo actualizando nada, y el
-- único síntoma era que `n_tup_upd` se quedaba clavado en 2.
CREATE PROCEDURE pg_temp.reencadenar() LANGUAGE plpgsql AS $rc$
DECLARE
    v_lote  int := 200;            -- variantes por lote
    v_off   bigint := 0;
    v_ids   bigint[];
    v_tot   bigint := 0;
    v_n     bigint;
BEGIN
    LOOP
        SELECT array_agg(v) INTO v_ids
        FROM (SELECT DISTINCT v FROM fase6_stg.pos_afectada
              ORDER BY v OFFSET v_off LIMIT v_lote) s;
        EXIT WHEN v_ids IS NULL;
        WITH calc AS (
            SELECT mi.id,
                   mi.cantidad * tm.factor AS delta,
                   sum(mi.cantidad * tm.factor) OVER (
                       PARTITION BY mi.producto_variante_id, mi.bodega_id
                       ORDER BY mi.fecha_creacion, mi.id
                       ROWS UNBOUNDED PRECEDING) AS nuevo
            FROM movimiento_inventario mi
            JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id
            JOIN fase6_stg.pos_afectada p
              ON p.v = mi.producto_variante_id AND p.b = mi.bodega_id
            WHERE mi.producto_variante_id = ANY(v_ids)
        )
        UPDATE movimiento_inventario m
           SET stock_anterior = c.nuevo - c.delta,
               stock_nuevo    = c.nuevo
        FROM calc c
        WHERE m.id = c.id
          AND (m.stock_nuevo    IS DISTINCT FROM c.nuevo
            OR m.stock_anterior IS DISTINCT FROM c.nuevo - c.delta);
        GET DIAGNOSTICS v_n = ROW_COUNT;
        v_tot := v_tot + v_n;
        COMMIT;
        RAISE NOTICE 'Reencadenado: % variantes, % movimientos acumulados.',
                     v_off + array_length(v_ids, 1), v_tot;
        v_off := v_off + v_lote;
    END LOOP;
    RAISE NOTICE 'Reencadenado TERMINADO: % movimientos reescritos.', v_tot;
END
$rc$;
CALL pg_temp.reencadenar();

-- 7c ── el saldo de las posiciones tocadas ───────────────────────────────────
BEGIN;
UPDATE inventario i SET stock_actual = k.saldo
FROM (SELECT mi.producto_variante_id v, mi.bodega_id b,
             sum(mi.cantidad * tm.factor) saldo
      FROM movimiento_inventario mi
      JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id
      JOIN fase6_stg.pos_afectada p ON p.v = mi.producto_variante_id AND p.b = mi.bodega_id
      GROUP BY 1, 2) k
WHERE i.producto_variante_id = k.v AND i.bodega_id = k.b
  AND i.stock_actual IS DISTINCT FROM k.saldo;
COMMIT;

\echo ''
\echo '-- 7c inventario --'
SELECT count(*) posiciones, sum(stock_actual) unidades FROM inventario;

-- ════════════════════════════════════════════════════════════════════════════
-- BLOQUE 8 · TICKETS DE SOPORTE
--   Dos poblaciones, como en el dato: los ligados a un pedido (a la tasa
--   medida por estado × canal) y los que NO tienen pedido — 59 de 249, el
--   23,7 %, que son consultas generales y no incidencias de un pedido.
--   La PRIORIDAD no se sortea: la pone la CATEGORÍA, que es exactamente lo
--   que hace el sistema con `categoria_ticket.prioridad_defecto`.
-- ════════════════════════════════════════════════════════════════════════════
BEGIN;

DROP TABLE IF EXISTS fase6_stg.plan_tkt;
CREATE UNLOGGED TABLE fase6_stg.plan_tkt AS
WITH ent AS (
    SELECT h.pedido_id, min(h.fecha_creacion) f_ent
    FROM historial_estado_pedido h WHERE h.estado_pedido_id IN (6, 8) GROUP BY 1),
liga AS (
    SELECT p.id pedido_id, p.cliente_id, e.f_ent
    FROM pedido p
    JOIN estado_pedido ep ON ep.id = p.estado_pedido_id
    JOIN ent e ON e.pedido_id = p.id
    WHERE ep.codigo IN ('entregado','devuelto')
      AND fase6_stg.u(p.id, 91) <
          CASE ep.codigo
            WHEN 'devuelto' THEN CASE p.canal WHEN 'web' THEN 0.0862 ELSE 0.0370 END
            ELSE CASE p.canal WHEN 'web'    THEN 0.0542
                              WHEN 'tienda' THEN 0.0462 ELSE 0.0439 END END
),
-- Los sueltos: un 31,1 % adicional (59 de 190), repartidos por la década.
suelto AS (
    SELECT NULL::bigint pedido_id, c.id cliente_id,
           timestamptz '2025-01-01' + (fase6_stg.u(c.id, 92) * 3600) * interval '1 day' AS f_ent
    FROM cliente c
    WHERE fase6_stg.u(c.id, 93) < 0.85
)
SELECT row_number() OVER (ORDER BY pedido_id NULLS LAST, cliente_id) AS n, *
FROM (SELECT * FROM liga
      UNION ALL
      SELECT * FROM suelto
      WHERE (SELECT count(*) FROM liga) > 0) x;

ALTER TABLE fase6_stg.plan_tkt ADD COLUMN cat int, ADD COLUMN estado text, ADD COLUMN f_tk timestamptz;

UPDATE fase6_stg.plan_tkt SET
    -- Categorías a la proporción medida (37/36/35/34/28/27/26/25 sobre 248).
    cat = CASE WHEN fase6_stg.u(n, 94) < 0.1492 THEN 2
               WHEN fase6_stg.u(n, 94) < 0.2944 THEN 4
               WHEN fase6_stg.u(n, 94) < 0.4355 THEN 6
               WHEN fase6_stg.u(n, 94) < 0.5726 THEN 3
               WHEN fase6_stg.u(n, 94) < 0.6855 THEN 7
               WHEN fase6_stg.u(n, 94) < 0.7944 THEN 5
               WHEN fase6_stg.u(n, 94) < 0.8992 THEN 1 ELSE 8 END,
    estado = CASE WHEN fase6_stg.u(n, 95) < 0.3052 THEN 'cerrado'
                  WHEN fase6_stg.u(n, 95) < 0.5301 THEN 'en_proceso'
                  WHEN fase6_stg.u(n, 95) < 0.7510 THEN 'abierto'
                  WHEN fase6_stg.u(n, 95) < 0.9317 THEN 'resuelto'
                  ELSE 'esperando_cliente' END,
    -- NUNCA antes de su pedido. El seed original tiene 79 tickets anteriores
    -- a su propio pedido (hasta 426 días antes); esa incoherencia no se
    -- replica, y por eso el desfase arranca en +1 día y no en un rango
    -- centrado en cero.
    f_tk = f_ent + (1 + floor(44 * fase6_stg.u(n, 96))) * interval '1 day';

CREATE UNIQUE INDEX ON fase6_stg.plan_tkt (n);
ANALYZE fase6_stg.plan_tkt;

INSERT INTO ticket_soporte (id, numero, cliente_id, categoria_ticket_id, pedido_id,
                            asignado_usuario_id, asunto, descripcion, prioridad, estado,
                            fecha_cierre, fecha_creacion, fecha_limite, producto_variante_id)
OVERRIDING SYSTEM VALUE
SELECT 2900000000 + t.n,
       -- Banda de SIETE dígitos (el seed usa cuatro o cinco) con secuencia
       -- POR DÍA, comprobada libre en el 102.
       'TK-' || to_char(t.f_tk, 'YYYYMMDD') || '-9' ||
           lpad((100000 + row_number() OVER (PARTITION BY t.f_tk::date ORDER BY t.n))::text, 6, '0'),
       t.cliente_id, t.cat, t.pedido_id,
       CASE WHEN t.estado <> 'abierto'
            THEN (ARRAY[12,18,19,20])[1 + floor(4 * fase6_stg.u(t.n, 97))::int] END,
       ct.nombre || ' - solicitud del cliente',
       'Solicitud registrada por el cliente desde el portal de soporte.',
       ct.prioridad_defecto,                         -- la pone la CATEGORÍA
       t.estado,
       CASE WHEN t.estado = 'cerrado'
            THEN t.f_tk + (1 + floor(20 * fase6_stg.u(t.n, 98))) * interval '1 day' END,
       t.f_tk,
       -- SLA por prioridad: urgente 2 h · alta 4 h · media 24 h · baja 72 h.
       t.f_tk + CASE ct.prioridad_defecto WHEN 'urgente' THEN interval '2 hours'
                                          WHEN 'alta'    THEN interval '4 hours'
                                          WHEN 'media'   THEN interval '24 hours'
                                          ELSE                interval '72 hours' END,
       NULL
FROM fase6_stg.plan_tkt t
JOIN categoria_ticket ct ON ct.id = t.cat
WHERE NOT EXISTS (SELECT 1 FROM ticket_soporte s WHERE s.id >= 2900000000);

COMMIT;

\echo ''
\echo '-- tickets --'
SELECT count(*) total, count(pedido_id) con_pedido, count(*) - count(pedido_id) sueltos
FROM ticket_soporte WHERE id >= 2900000000;

-- ════════════════════════════════════════════════════════════════════════════
-- BLOQUE 9 · RESEÑAS
--   `uq_resena_producto_cliente` UNIQUE (producto_id, cliente_id): un cliente
--   reseña un producto UNA vez en toda la década. Hay 311 M de pares posibles
--   para ~250.000 reseñas, así que espacio sobra — pero las colisiones existen
--   y se resuelven con DISTINCT ON, incluidas las que chocarían con las 344
--   reseñas ORIGINALES, que mandan y no se tocan.
-- ════════════════════════════════════════════════════════════════════════════
BEGIN;

DROP TABLE IF EXISTS fase6_stg.plan_res;
CREATE UNLOGGED TABLE fase6_stg.plan_res AS
WITH ent AS (
    SELECT h.pedido_id, min(h.fecha_creacion) f_ent
    FROM historial_estado_pedido h WHERE h.estado_pedido_id IN (6, 8) GROUP BY 1),
cand AS (
    SELECT p.id pedido_id, p.cliente_id, pd.id pd_id, pv.producto_id, e.f_ent,
           row_number() OVER (PARTITION BY p.id
                              ORDER BY fase6_stg.u(pd.id, 101)) rn
    FROM pedido p
    JOIN estado_pedido ep     ON ep.id = p.estado_pedido_id
    JOIN ent e                ON e.pedido_id = p.id
    JOIN pedido_detalle pd    ON pd.pedido_id = p.id
    JOIN producto_variante pv ON pv.id = pd.producto_variante_id
    WHERE ep.codigo IN ('entregado','devuelto')
      AND fase6_stg.u(p.id, 102) < 0.094
)
SELECT DISTINCT ON (producto_id, cliente_id)
       producto_id, cliente_id, pedido_id, pd_id, f_ent,
       f_ent + (1 + floor(74 * fase6_stg.u(pd_id, 103))) * interval '1 day' AS fecha
FROM cand
WHERE rn = 1 OR (rn = 2 AND fase6_stg.u(pedido_id, 104) < 0.04)
ORDER BY producto_id, cliente_id, pedido_id;

-- Las 344 originales mandan: si el par ya existe, el candidato se descarta.
DELETE FROM fase6_stg.plan_res p
WHERE EXISTS (SELECT 1 FROM resena r
              WHERE r.producto_id = p.producto_id AND r.cliente_id = p.cliente_id);

ANALYZE fase6_stg.plan_res;

INSERT INTO resena (id, producto_id, cliente_id, pedido_id, calificacion, titulo,
                    comentario, compra_verificada, estado, fecha_creacion,
                    moderado_por, fecha_moderacion)
OVERRIDING SYSTEM VALUE
SELECT 3000000000 + row_number() OVER (ORDER BY r.producto_id, r.cliente_id),
       r.producto_id, r.cliente_id, r.pedido_id,
       -- Distribución medida: 148 cincos, 97 cuatros, 52 tres, 25 unos, 22 dos.
       CASE WHEN fase6_stg.u(r.pd_id, 105) < 0.0727 THEN 1
            WHEN fase6_stg.u(r.pd_id, 105) < 0.1367 THEN 2
            WHEN fase6_stg.u(r.pd_id, 105) < 0.2878 THEN 3
            WHEN fase6_stg.u(r.pd_id, 105) < 0.5698 THEN 4
            ELSE 5 END,
       NULL, NULL,
       true,                                  -- las 344 originales lo son al 100 %
       CASE WHEN fase6_stg.u(r.pd_id, 106) < 0.7849 THEN 'aprobada'
            WHEN fase6_stg.u(r.pd_id, 106) < 0.9390 THEN 'pendiente'
            ELSE 'rechazada' END,
       r.fecha,
       -- Solo lo moderado lleva moderador: 'pendiente' es, por definición, lo
       -- que nadie ha mirado todavía.
       CASE WHEN fase6_stg.u(r.pd_id, 106) < 0.7849
              OR fase6_stg.u(r.pd_id, 106) >= 0.9390 THEN 6 END,
       CASE WHEN fase6_stg.u(r.pd_id, 106) < 0.7849
              OR fase6_stg.u(r.pd_id, 106) >= 0.9390
            THEN r.fecha + interval '1 day' END
FROM fase6_stg.plan_res r
WHERE NOT EXISTS (SELECT 1 FROM resena s WHERE s.id >= 3000000000);

COMMIT;

\echo ''
\echo '-- resenas --'
SELECT count(*) FROM resena WHERE id >= 3000000000;

-- El andamio se derriba. El plan ya está materializado en las tablas reales.
DROP SCHEMA IF EXISTS fase6_stg CASCADE;

\echo ''
\echo 'FASE 6 CARGADA (kardex, tickets y resenas).'
