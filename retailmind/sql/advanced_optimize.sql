-- ============================================================
-- RetailMind Analytics S.A. -- Optimizacion Avanzada
-- ============================================================

-- ============================================================
-- 1. INDICES COMPUESTOS ADICIONALES
-- ============================================================

CREATE INDEX IF NOT EXISTS idx_sesiones_timestamp_channel
    ON sesiones(timestamp_utc, channel_id);

CREATE INDEX IF NOT EXISTS idx_sesiones_user_timestamp
    ON sesiones(user_id, timestamp_utc);

CREATE INDEX IF NOT EXISTS idx_conversiones_session_conversion
    ON conversiones(session_id, is_conversion);

-- ============================================================
-- 2. VISTAS MATERIALIZADAS PARA EL DASHBOARD
-- ============================================================

-- Vista: resumen general del dashboard
DROP MATERIALIZED VIEW IF EXISTS mv_resumen_dashboard;
CREATE MATERIALIZED VIEW mv_resumen_dashboard AS
SELECT
    (SELECT COUNT(*) FROM sesiones)::BIGINT                          AS total_sesiones,
    (SELECT COUNT(*) FROM usuarios)::BIGINT                          AS total_usuarios,
    (SELECT COUNT(*) FROM conversiones WHERE is_conversion = true)::BIGINT  AS total_conversiones,
    CASE
        WHEN (SELECT COUNT(*) FROM sesiones) > 0
        THEN ROUND(
            (SELECT COUNT(*) FROM conversiones WHERE is_conversion = true)::NUMERIC * 100.0
            / (SELECT COUNT(*) FROM sesiones)::NUMERIC, 2
        )
        ELSE 0
    END                                                              AS tasa_conversion,
    (SELECT COUNT(*) FROM conversiones WHERE drop_off_flag = true)::BIGINT AS total_abandonos;

CREATE UNIQUE INDEX IF NOT EXISTS idx_mv_resumen_dashboard
    ON mv_resumen_dashboard(total_sesiones);

-- Vista: sesiones agrupadas por canal
DROP MATERIALIZED VIEW IF EXISTS mv_sesiones_por_canal;
CREATE MATERIALIZED VIEW mv_sesiones_por_canal AS
SELECT
    c.channel_name AS nombre,
    COUNT(s.session_id)::BIGINT AS total
FROM sesiones s
JOIN canales c ON s.channel_id = c.channel_id
GROUP BY c.channel_name
ORDER BY total DESC;

CREATE UNIQUE INDEX IF NOT EXISTS idx_mv_sesiones_canal_nombre
    ON mv_sesiones_por_canal(nombre);

-- Vista: sesiones agrupadas por region
DROP MATERIALIZED VIEW IF EXISTS mv_sesiones_por_region;
CREATE MATERIALIZED VIEW mv_sesiones_por_region AS
SELECT
    r.region_name AS nombre,
    COUNT(s.session_id)::BIGINT AS total
FROM sesiones s
JOIN usuarios u ON s.user_id = u.user_id
JOIN regiones r ON u.region_id = r.region_id
GROUP BY r.region_name
ORDER BY total DESC;

CREATE UNIQUE INDEX IF NOT EXISTS idx_mv_sesiones_region_nombre
    ON mv_sesiones_por_region(nombre);

-- Vista: sesiones agrupadas por dispositivo
DROP MATERIALIZED VIEW IF EXISTS mv_sesiones_por_dispositivo;
CREATE MATERIALIZED VIEW mv_sesiones_por_dispositivo AS
SELECT
    d.device_type_name AS nombre,
    COUNT(s.session_id)::BIGINT AS total
FROM sesiones s
JOIN usuarios u ON s.user_id = u.user_id
JOIN dispositivos d ON u.device_type_id = d.device_type_id
GROUP BY d.device_type_name
ORDER BY total DESC;

CREATE UNIQUE INDEX IF NOT EXISTS idx_mv_sesiones_dispositivo_nombre
    ON mv_sesiones_por_dispositivo(nombre);

-- Vista: tasa de conversion por semana
DROP MATERIALIZED VIEW IF EXISTS mv_tasa_conversion_semanal;
CREATE MATERIALIZED VIEW mv_tasa_conversion_semanal AS
SELECT
    EXTRACT(WEEK FROM s.timestamp_utc)::INT AS semana,
    COUNT(c.conversion_id)::BIGINT AS total_sesiones,
    SUM(CASE WHEN c.is_conversion = true THEN 1 ELSE 0 END)::BIGINT AS total_conversiones,
    CASE
        WHEN COUNT(c.conversion_id) > 0
        THEN ROUND(
            SUM(CASE WHEN c.is_conversion = true THEN 1 ELSE 0 END)::NUMERIC * 100.0
            / COUNT(c.conversion_id)::NUMERIC, 2
        )
        ELSE 0
    END AS tasa_conversion
FROM conversiones c
JOIN sesiones s ON c.session_id = s.session_id
WHERE s.timestamp_utc IS NOT NULL
GROUP BY EXTRACT(WEEK FROM s.timestamp_utc)
ORDER BY semana;

CREATE UNIQUE INDEX IF NOT EXISTS idx_mv_tasa_semanal_semana
    ON mv_tasa_conversion_semanal(semana);

-- ============================================================
-- 3. FUNCION PARA REFRESCAR TODAS LAS VISTAS
-- ============================================================

CREATE OR REPLACE FUNCTION refresh_dashboard_views()
RETURNS TEXT
LANGUAGE plpgsql
AS $$
BEGIN
    REFRESH MATERIALIZED VIEW CONCURRENTLY mv_resumen_dashboard;
    REFRESH MATERIALIZED VIEW CONCURRENTLY mv_sesiones_por_canal;
    REFRESH MATERIALIZED VIEW CONCURRENTLY mv_sesiones_por_region;
    REFRESH MATERIALIZED VIEW CONCURRENTLY mv_sesiones_por_dispositivo;
    REFRESH MATERIALIZED VIEW CONCURRENTLY mv_tasa_conversion_semanal;
    RETURN 'Vistas materializadas refrescadas exitosamente';
END;
$$;

-- ============================================================
-- 4. PARTICIONAMIENTO DE SESIONES (PostgreSQL 10+)
-- ============================================================
-- NOTA: El particionamiento requiere recrear la tabla.
-- Este bloque es informativo y debe ejecutarse manualmente
-- si se desea migrar a tabla particionada.
-- Descomentar solo si se confirma PostgreSQL >= 10.

/*
-- Paso 1: Renombrar tabla original
ALTER TABLE sesiones RENAME TO sesiones_old;

-- Paso 2: Crear tabla particionada
CREATE TABLE sesiones (
    session_id        VARCHAR(100) NOT NULL,
    user_id           VARCHAR(100),
    timestamp_utc     TIMESTAMP,
    session_length    FLOAT,
    interaction_count INT,
    channel_id        INT,
    source_id         INT,
    PRIMARY KEY (session_id, timestamp_utc)
) PARTITION BY RANGE (timestamp_utc);

-- Paso 3: Crear particiones mensuales (ejemplo 2024)
CREATE TABLE sesiones_2024_01 PARTITION OF sesiones
    FOR VALUES FROM ('2024-01-01') TO ('2024-02-01');
CREATE TABLE sesiones_2024_02 PARTITION OF sesiones
    FOR VALUES FROM ('2024-02-01') TO ('2024-03-01');
CREATE TABLE sesiones_2024_03 PARTITION OF sesiones
    FOR VALUES FROM ('2024-03-01') TO ('2024-04-01');
CREATE TABLE sesiones_2024_04 PARTITION OF sesiones
    FOR VALUES FROM ('2024-04-01') TO ('2024-05-01');
CREATE TABLE sesiones_2024_05 PARTITION OF sesiones
    FOR VALUES FROM ('2024-05-01') TO ('2024-06-01');
CREATE TABLE sesiones_2024_06 PARTITION OF sesiones
    FOR VALUES FROM ('2024-06-01') TO ('2024-07-01');
CREATE TABLE sesiones_2024_07 PARTITION OF sesiones
    FOR VALUES FROM ('2024-07-01') TO ('2024-08-01');
CREATE TABLE sesiones_2024_08 PARTITION OF sesiones
    FOR VALUES FROM ('2024-08-01') TO ('2024-09-01');
CREATE TABLE sesiones_2024_09 PARTITION OF sesiones
    FOR VALUES FROM ('2024-09-01') TO ('2024-10-01');
CREATE TABLE sesiones_2024_10 PARTITION OF sesiones
    FOR VALUES FROM ('2024-10-01') TO ('2024-11-01');
CREATE TABLE sesiones_2024_11 PARTITION OF sesiones
    FOR VALUES FROM ('2024-11-01') TO ('2024-12-01');
CREATE TABLE sesiones_2024_12 PARTITION OF sesiones
    FOR VALUES FROM ('2024-12-01') TO ('2025-01-01');

-- Paso 4: Migrar datos
INSERT INTO sesiones SELECT * FROM sesiones_old;

-- Paso 5: Verificar y eliminar tabla vieja
-- DROP TABLE sesiones_old;
*/
