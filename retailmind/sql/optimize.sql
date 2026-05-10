-- ============================================================
-- RetailMind Analytics S.A. -- Indices y optimizacion
-- ============================================================

-- Tabla: sesiones
CREATE INDEX IF NOT EXISTS idx_sesiones_timestamp_utc
    ON sesiones(timestamp_utc);

CREATE INDEX IF NOT EXISTS idx_sesiones_user_id
    ON sesiones(user_id);

CREATE INDEX IF NOT EXISTS idx_sesiones_channel_id
    ON sesiones(channel_id);

-- Tabla: eventos
CREATE INDEX IF NOT EXISTS idx_eventos_session_id
    ON eventos(session_id);

CREATE INDEX IF NOT EXISTS idx_eventos_product_id
    ON eventos(product_id);

-- Tabla: conversiones
CREATE INDEX IF NOT EXISTS idx_conversiones_is_conversion
    ON conversiones(is_conversion);

CREATE INDEX IF NOT EXISTS idx_conversiones_session_id
    ON conversiones(session_id);

-- Indice parcial: solo registros donde hubo conversion real
CREATE INDEX IF NOT EXISTS idx_conversiones_true
    ON conversiones(session_id)
    WHERE is_conversion = true;

-- Tabla de historial de cargas (creada si no existe)
CREATE TABLE IF NOT EXISTS carga_historial (
    id               SERIAL PRIMARY KEY,
    semana           INT NOT NULL UNIQUE,
    fecha_carga      TIMESTAMP NOT NULL DEFAULT NOW(),
    registros_procesados INT NOT NULL DEFAULT 0,
    registros_nuevos     INT NOT NULL DEFAULT 0
);
