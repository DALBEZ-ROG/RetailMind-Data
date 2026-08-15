-- =====================================================================
-- 110 — Índice CUBRIENTE de factura_venta por fecha
-- =====================================================================
-- Único cambio de esquema de la sesión de rendimiento, y es un ÍNDICE: no
-- se añade, quita ni modifica ninguna columna, tabla, política RLS,
-- función de horario ni permiso.
--
-- QUÉ RESUELVE
-- ------------
-- `/api/gerencia/metas` (OTD-VEN-15) calcula la venta facturada del mes de
-- cada meta. Con 2.855.378 facturas y RLS activo la pantalla NO LLEGABA A
-- ABRIRSE: medido, más de 10 minutos. Dos causas encadenadas:
--
--   1. El predicado comparaba `fecha_emision` (timestamptz) contra
--      `make_date(...)` (date). `timestamptz_ge_date` tiene
--      proleakproof = false, y como `factura_venta` tiene RLS
--      (`pol_horario`), PostgreSQL no permite evaluar un qual no-leakproof
--      del usuario antes del qual de seguridad — que es lo que hace un
--      Index Cond. El índice quedaba inservible y cada meta recorría las
--      2,86 M facturas. Corregido EN EL CÓDIGO (casteo a timestamptz).
--
--   2. Aun con el índice utilizable, la agregación mensual tenía que ir al
--      heap a leer `estado` y `total`, y bajo RLS eso son ~8,5 µs por fila.
--      Este índice los INCLUYE, así que el plan pasa a
--      `Parallel Index Only Scan` con `Heap Fetches: 0`.
--
-- MEDIDO (grp_administrador, agregando los 19 meses que cubren las metas):
--      subconsulta correlacionada por meta, sin índice cubriente   7.761 ms
--      una sola pasada agrupada, sin índice cubriente              3.921 ms
--      una sola pasada agrupada, CON este índice                     774 ms
--
-- COSTE
-- -----
-- 110 MB en disco y ~3 min de construcción. Se crea CONCURRENTLY para no
-- bloquear escrituras. `estado` y `total` van en INCLUDE y no en la clave:
-- no se busca por ellos, solo se leen.
--
-- IDEMPOTENTE: se puede volver a ejecutar sin efecto.
--
-- Aplicar:
--   docker compose exec -T postgres psql -U postgres -d retailmind \
--     < retailmind/sql/postgres/110_indice_cubriente_factura_venta.sql
-- =====================================================================

-- CONCURRENTLY no puede ir dentro de una transacción; psql lo ejecuta
-- suelto porque este archivo no abre ninguna.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_factura_venta_fecha_cubriente
    ON factura_venta (fecha_emision) INCLUDE (estado, total);

-- El Index Only Scan necesita el mapa de visibilidad al día; sin VACUUM el
-- plan existe pero cuenta Heap Fetches y la ganancia desaparece.
VACUUM (ANALYZE) factura_venta;

-- Comprobación: debe salir Index Only Scan y Heap Fetches: 0.
--   SET ROLE grp_administrador;
--   EXPLAIN (ANALYZE) SELECT date_trunc('month', fecha_emision), sum(total)
--     FROM factura_venta
--    WHERE estado <> 'anulada'
--      AND fecha_emision >= '2025-01-01 00:00:00-05'::timestamptz
--      AND fecha_emision <  '2026-08-01 00:00:00-05'::timestamptz
--    GROUP BY 1;

-- Marcha atrás:
--   DROP INDEX CONCURRENTLY IF EXISTS idx_factura_venta_fecha_cubriente;
