-- ============================================================================
-- 49_ticket_fecha_limite.sql — Fecha límite de atención del ticket
-- (2026-07-22, OTD-SOP-02, cierre de brecha del catálogo táctico § 11.1)
--
-- El plazo prometido al cliente (urgente 2h / alta 4h / media 24h / baja 72h)
-- vivía solo en código (SoporteService.SLA_SQL) y se calculaba al vuelo en
-- cada consulta: no era una columna consultable para medir cumplimiento.
-- Ahora ticket_soporte.fecha_limite se PERSISTE al crear el ticket y se
-- RECALCULA cuando Soporte/Admin cambia la prioridad (fecha_creacion + horas
-- de la nueva prioridad). La escribe el backend; queda nullable por si algún
-- flujo legacy no la trae (las consultas la tratan como opcional).
--
-- GRANTs: NINGUNO nuevo. Los privilegios de ticket_soporte del script 37 son
-- de TABLA COMPLETA (SELECT/INSERT/UPDATE para grp_soporte, grp_gerente y
-- grp_administrador; SELECT+INSERT para grp_cliente; SELECT para grp_analista),
-- así que la columna nueva queda cubierta automáticamente. La única excepción
-- por columna (UPDATE(estado) de grp_cliente para reabrir) no necesita tocar
-- fecha_limite: la reapertura no cambia la prioridad ni el plazo.
--
-- RELLENO DERIVADO (documentado): los 12 tickets existentes nacieron sin la
-- columna. Se deriva fecha_limite = fecha_creacion + horas(prioridad ACTUAL)
-- — NO es un dato original capturado en su momento, es una reconstrucción con
-- la misma regla del servicio. Idempotente: solo toca filas con la columna
-- vacía, re-ejecutar no re-escribe nada.
-- ============================================================================

BEGIN;

ALTER TABLE ticket_soporte ADD COLUMN IF NOT EXISTS fecha_limite timestamptz;

COMMENT ON COLUMN ticket_soporte.fecha_limite IS
    'Límite de atención prometido (SLA): fecha_creacion + horas según prioridad '
    '(urgente 2h, alta 4h, media 24h, baja 72h). La persiste el backend al crear '
    'y la recalcula al cambiar la prioridad. Los tickets anteriores al script 49 '
    'llevan un valor DERIVADO retroactivamente con esa misma regla.';

-- Relleno derivado de los tickets preexistentes (solo filas vacías)
UPDATE ticket_soporte
SET fecha_limite = fecha_creacion + CASE prioridad
        WHEN 'urgente' THEN interval '2 hours'
        WHEN 'alta'    THEN interval '4 hours'
        WHEN 'media'   THEN interval '24 hours'
        ELSE                interval '72 hours' END
WHERE fecha_limite IS NULL;

COMMIT;
