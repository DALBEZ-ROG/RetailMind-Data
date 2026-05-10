-- Se ejecuta ANTES de que Hibernate valide las entidades
CREATE TABLE IF NOT EXISTS usuarios_sistema (
    id         SERIAL PRIMARY KEY,
    username   VARCHAR(50) UNIQUE NOT NULL,
    password   VARCHAR(255) NOT NULL,
    nombre     VARCHAR(100),
    rol        VARCHAR(20) NOT NULL DEFAULT 'VIEWER',
    activo     BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS carga_historial (
    id                   SERIAL PRIMARY KEY,
    semana               INT NOT NULL UNIQUE,
    fecha_carga          TIMESTAMP NOT NULL DEFAULT NOW(),
    registros_procesados INT NOT NULL DEFAULT 0,
    registros_nuevos     INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS error_log (
    id          SERIAL PRIMARY KEY,
    timestamp   TIMESTAMP NOT NULL DEFAULT NOW(),
    tipo_error  VARCHAR(100),
    mensaje     TEXT,
    stack_trace TEXT,
    resuelto    BOOLEAN NOT NULL DEFAULT false
);
