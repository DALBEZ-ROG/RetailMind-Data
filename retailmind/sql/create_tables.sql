-- ============================================================
-- RetailMind Analytics S.A. — DDL Tablas Normalizadas
-- ============================================================

-- 1. regiones
CREATE TABLE IF NOT EXISTS regiones (
    region_id   SERIAL PRIMARY KEY,
    region_name VARCHAR(100) NOT NULL UNIQUE,
    country     VARCHAR(100)
);

-- 2. dispositivos
CREATE TABLE IF NOT EXISTS dispositivos (
    device_type_id   SERIAL PRIMARY KEY,
    device_type_name VARCHAR(100) NOT NULL UNIQUE
);

-- 3. canales
CREATE TABLE IF NOT EXISTS canales (
    channel_id  SERIAL PRIMARY KEY,
    channel_name VARCHAR(100) NOT NULL UNIQUE,
    description  TEXT
);

-- 4. fuentes_trafico
CREATE TABLE IF NOT EXISTS fuentes_trafico (
    source_id   SERIAL PRIMARY KEY,
    source_name VARCHAR(100) NOT NULL UNIQUE,
    type        VARCHAR(50)
);

-- 5. categorias
CREATE TABLE IF NOT EXISTS categorias (
    category_id   SERIAL PRIMARY KEY,
    category_name VARCHAR(100) NOT NULL UNIQUE,
    description   TEXT
);

-- 6. usuarios
CREATE TABLE IF NOT EXISTS usuarios (
    user_id        VARCHAR(100) PRIMARY KEY,
    region_id      INT REFERENCES regiones(region_id),
    device_type_id INT REFERENCES dispositivos(device_type_id),
    created_at     TIMESTAMP DEFAULT NOW()
);

-- 7. productos
CREATE TABLE IF NOT EXISTS productos (
    product_id  VARCHAR(100) PRIMARY KEY,
    category_id INT REFERENCES categorias(category_id),
    brand       VARCHAR(100),
    price       DECIMAL(10, 2)
);

-- 8. sesiones
CREATE TABLE IF NOT EXISTS sesiones (
    session_id        VARCHAR(100) PRIMARY KEY,
    user_id           VARCHAR(100) REFERENCES usuarios(user_id),
    timestamp_utc     TIMESTAMP,
    session_length    FLOAT,
    interaction_count INT,
    channel_id        INT REFERENCES canales(channel_id),
    source_id         INT REFERENCES fuentes_trafico(source_id)
);

-- 9. eventos
CREATE TABLE IF NOT EXISTS eventos (
    evento_id      SERIAL PRIMARY KEY,
    session_id     VARCHAR(100) REFERENCES sesiones(session_id),
    event_index    INT,
    user_action    VARCHAR(100),
    time_spent_sec FLOAT,
    product_id     VARCHAR(100) REFERENCES productos(product_id)
);

-- 10. conversiones
CREATE TABLE IF NOT EXISTS conversiones (
    conversion_id   SERIAL PRIMARY KEY,
    session_id      VARCHAR(100) REFERENCES sesiones(session_id),
    is_conversion   BOOLEAN,
    drop_off_flag   BOOLEAN,
    conversion_time TIMESTAMP
);
