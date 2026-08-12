-- ============================================================================
-- 104_fase6_correccion_hitos.sql — RetailMind · corrección de la Fase 6
--
--   docker compose exec -T postgres psql -U postgres -d retailmind \
--       -v ON_ERROR_STOP=1 -f - < retailmind/sql/postgres/104_fase6_correccion_hitos.sql
--
-- ---------------------------------------------------------------------------
-- QUÉ ARREGLA
-- ---------------------------------------------------------------------------
-- La primera versión de `fase6_stg.fecha_peldano` daba a CADA peldaño de la
-- escalera su propio multiplicador aleatorio en vez de acumular incrementos:
--
--     peldaño i  →  (i - 1) días × (1 + 3·u(pedido, 70 + i))
--
-- Eso no es monótono. El peldaño 3 podía sortear el factor más bajo de su
-- rango (2 días × 1,0 = 2 días) y caer ANTES que el peldaño 2, que había
-- sorteado el más alto (1 día × 4,0 = 4 días). El error aparece exactamente
-- donde los rangos de dos peldaños consecutivos se solapan.
--
-- Medido tras la carga: **18.210 hitos fuera de orden sobre 770.741** (2,4 %),
-- concentrados en los peldaños 3, 4, 5, 6 y 8. Ningún otro dato quedó
-- afectado: el peldaño 6 de un pedido 'devuelto' —que es la fecha del
-- reingreso al kardex— se interpola contra el hito real y siempre fue
-- correcto, y por eso V4 dio 0 movimientos de kardex anteriores a su pedido.
--
-- La corrección es la SUMA ACUMULADA de incrementos estrictamente positivos,
-- que hace el desorden imposible por construcción. Se aplica a las mismas
-- semillas, así que el resultado es reproducible y no un reparcheo ciego.
--
-- Alcance: hitos de la Fase 6 (`devolucion_id >= 2.600.000.000`) y las fechas
-- derivadas del peldaño 7 (reembolso) y del 6 (item defectuoso). Los 1.020
-- hitos originales NO se tocan.
--
-- SE AUTOVERIFICA Y ABORTA. IDEMPOTENTE.
-- ============================================================================
\set ON_ERROR_STOP on
\timing on

BEGIN;

-- El andamio mínimo: la misma función generadora, con las mismas sales.
DROP SCHEMA IF EXISTS fase6_fix CASCADE;
CREATE SCHEMA fase6_fix;

CREATE FUNCTION fase6_fix.u(semilla bigint, sal int) RETURNS double precision
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT (abs(hashtextextended(semilla::text || ':' || sal::text, 0)) % 1000000)::double precision
           / 1000000.0
$$;

CREATE FUNCTION fase6_fix.fecha_peldano(
    p_pedido bigint, p_ent timestamptz, p_dev timestamptz, p_i int)
RETURNS timestamptz LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT CASE
      WHEN p_dev IS NOT NULL AND p_i <= 6 THEN
          p_ent + (p_dev - p_ent) * p_i / 6.0 - ((6 - p_i) * interval '1 microsecond')
      WHEN p_dev IS NOT NULL THEN
          p_dev + (SELECT sum(1 + 4 * fase6_fix.u(p_pedido, 60 + k))
                   FROM generate_series(7, p_i) k) * interval '1 day'
      ELSE
          p_ent + (1 + floor(29 * fase6_fix.u(p_pedido, 31))) * interval '1 day'
                + COALESCE((SELECT sum(1 + 3 * fase6_fix.u(p_pedido, 70 + k))
                            FROM generate_series(2, p_i) k), 0) * interval '1 day'
    END
$$;

-- Las anclas temporales, releídas del origen: la entrega y —si lo hay— el
-- hito 'devuelto' del pedido. Son las mismas que usó la carga.
CREATE UNLOGGED TABLE fase6_fix.ancla AS
SELECT d.id dev_id, d.pedido_id,
       (SELECT min(h.fecha_creacion) FROM historial_estado_pedido h
         WHERE h.pedido_id = d.pedido_id AND h.estado_pedido_id = 6) f_ent,
       (SELECT min(h.fecha_creacion) FROM historial_estado_pedido h
         WHERE h.pedido_id = d.pedido_id AND h.estado_pedido_id = 8) f_dev
FROM devolucion d WHERE d.id >= 2600000000;
CREATE UNIQUE INDEX ON fase6_fix.ancla (dev_id);
ANALYZE fase6_fix.ancla;

COMMIT;

-- ── El peldaño de cada hito sale de su ORDEN por id dentro de la devolución.
--    Es fiable porque la carga los insertó en orden de escalera, y el hito
--    terminal de las rechazadas vive en un tramo superior (2.85e9) que ordena
--    detrás de los suyos por construcción.
BEGIN;

CREATE UNLOGGED TABLE fase6_fix.nueva AS
WITH x AS (
    SELECT h.id, h.devolucion_id,
           row_number() OVER (PARTITION BY h.devolucion_id ORDER BY h.id) i
    FROM historial_estado_devolucion h WHERE h.devolucion_id >= 2600000000)
SELECT x.id, fase6_fix.fecha_peldano(a.pedido_id, a.f_ent, a.f_dev, x.i::int) f
FROM x JOIN fase6_fix.ancla a ON a.dev_id = x.devolucion_id;
CREATE UNIQUE INDEX ON fase6_fix.nueva (id);
ANALYZE fase6_fix.nueva;

UPDATE historial_estado_devolucion h SET fecha_creacion = n.f
FROM fase6_fix.nueva n WHERE h.id = n.id AND h.fecha_creacion IS DISTINCT FROM n.f;

COMMIT;

-- ── Las fechas derivadas: reembolso (peldaño 7) e ítem defectuoso (6) ───────
BEGIN;

UPDATE reembolso r SET fecha_creacion = f.nueva, fecha_procesado = f.nueva
FROM (SELECT r2.id, fase6_fix.fecha_peldano(a.pedido_id, a.f_ent, a.f_dev, 7) nueva
      FROM reembolso r2 JOIN fase6_fix.ancla a ON a.dev_id = r2.devolucion_id
      WHERE r2.id >= 3100000000) f
WHERE r.id = f.id AND r.fecha_creacion IS DISTINCT FROM f.nueva;

UPDATE devolucion d SET fecha_reembolso = f.nueva
FROM (SELECT a.dev_id, fase6_fix.fecha_peldano(a.pedido_id, a.f_ent, a.f_dev, 7) nueva
      FROM fase6_fix.ancla a) f
WHERE d.id = f.dev_id AND d.fecha_reembolso IS NOT NULL
  AND d.fecha_reembolso IS DISTINCT FROM f.nueva;

UPDATE item_defectuoso it SET fecha_creacion = f.nueva
FROM (SELECT it2.id, fase6_fix.fecha_peldano(a.pedido_id, a.f_ent, a.f_dev, 6) nueva
      FROM item_defectuoso it2
      JOIN devolucion_detalle dd ON dd.id = it2.devolucion_detalle_id
      JOIN fase6_fix.ancla a ON a.dev_id = dd.devolucion_id
      WHERE it2.devolucion_detalle_id >= 2700000000) f
WHERE it.id = f.id AND it.fecha_creacion IS DISTINCT FROM f.nueva;

COMMIT;

-- ── Autoverificación: si queda un solo hito fuera de orden, aborta ──────────
DO $v$
DECLARE n bigint; m bigint;
BEGIN
    SELECT count(*) INTO n FROM (
        SELECT fecha_creacion,
               lag(fecha_creacion) OVER (PARTITION BY devolucion_id ORDER BY id) prev
        FROM historial_estado_devolucion) x
    WHERE prev IS NOT NULL AND fecha_creacion < prev;
    IF n > 0 THEN
        RAISE EXCEPTION 'ABORTA: quedan % hitos fuera de orden.', n;
    END IF;

    -- Y que el peldaño 6 de los 'devuelto' siga clavado en el hito del pedido,
    -- porque es la fecha con la que se escribió el reingreso al kardex.
    SELECT count(*) INTO m
    FROM movimiento_inventario mi
    JOIN devolucion d ON d.id = mi.referencia_id AND d.id >= 2600000000
    JOIN historial_estado_pedido h ON h.pedido_id = d.pedido_id AND h.estado_pedido_id = 8
    WHERE mi.id >= 3300000000 AND mi.fecha_creacion <> h.fecha_creacion;
    IF m > 0 THEN
        RAISE EXCEPTION 'ABORTA: % reingresos dejaron de coincidir con el hito devuelto.', m;
    END IF;

    RAISE NOTICE 'Corrección verificada: 0 hitos fuera de orden, reingresos intactos.';
END
$v$;

DROP SCHEMA IF EXISTS fase6_fix CASCADE;
\echo ''
\echo 'HITOS CORREGIDOS.'
