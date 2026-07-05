-- ============================================================
-- RetailMind - Script 09: M9 (parte A) - Catalogos de logistica
-- Tablas: transportista, metodo_envio, zona_envio, tarifa_envio
-- Se crean antes que pedido porque pedido referencia metodo_envio.
-- Depende de: 01 (pais, provincia, ciudad)
-- ============================================================

BEGIN;

CREATE TABLE transportista (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nombre              varchar(150) NOT NULL UNIQUE,
    ruc                 varchar(13)  UNIQUE,
    telefono            varchar(20),
    email               varchar(255),
    sitio_web           varchar(255),
    url_seguimiento     varchar(500),   -- plantilla, ej. 'https://track.x.com?g={guia}'
    activo              boolean      NOT NULL DEFAULT true,
    fecha_creacion      timestamptz  NOT NULL DEFAULT now(),
    fecha_actualizacion timestamptz
);

CREATE TABLE metodo_envio (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo              varchar(50)  NOT NULL UNIQUE,   -- 'estandar', 'express', 'retiro_tienda'
    nombre              varchar(100) NOT NULL,
    descripcion         text,
    transportista_id    bigint       REFERENCES transportista (id) ON DELETE SET NULL ON UPDATE CASCADE,
    dias_entrega_min    smallint     CHECK (dias_entrega_min >= 0),
    dias_entrega_max    smallint     CHECK (dias_entrega_max >= 0),
    orden               int          NOT NULL DEFAULT 0,
    activo              boolean      NOT NULL DEFAULT true,
    fecha_creacion      timestamptz  NOT NULL DEFAULT now(),
    fecha_actualizacion timestamptz,
    CONSTRAINT ck_metodo_envio_dias CHECK (dias_entrega_max IS NULL OR dias_entrega_min IS NULL
                                           OR dias_entrega_max >= dias_entrega_min)
);
CREATE INDEX idx_metodo_envio_transportista ON metodo_envio (transportista_id);

-- Zona geografica de cobertura. Aplica al nivel mas especifico no nulo:
-- ciudad > provincia > pais.
CREATE TABLE zona_envio (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nombre              varchar(100) NOT NULL UNIQUE,
    pais_id             bigint       NOT NULL REFERENCES pais (id) ON DELETE RESTRICT ON UPDATE CASCADE,
    provincia_id        bigint       REFERENCES provincia (id)     ON DELETE RESTRICT ON UPDATE CASCADE,
    ciudad_id           bigint       REFERENCES ciudad (id)        ON DELETE RESTRICT ON UPDATE CASCADE,
    descripcion         text,
    activo              boolean      NOT NULL DEFAULT true,
    fecha_creacion      timestamptz  NOT NULL DEFAULT now(),
    fecha_actualizacion timestamptz
);
CREATE INDEX idx_zona_envio_pais ON zona_envio (pais_id);
CREATE INDEX idx_zona_envio_provincia ON zona_envio (provincia_id);
CREATE INDEX idx_zona_envio_ciudad ON zona_envio (ciudad_id);

CREATE TABLE tarifa_envio (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    zona_envio_id       bigint        NOT NULL REFERENCES zona_envio (id)   ON DELETE CASCADE ON UPDATE CASCADE,
    metodo_envio_id     bigint        NOT NULL REFERENCES metodo_envio (id) ON DELETE CASCADE ON UPDATE CASCADE,
    costo_base          numeric(12,2) NOT NULL CHECK (costo_base >= 0),
    costo_por_kg        numeric(12,2) NOT NULL DEFAULT 0 CHECK (costo_por_kg >= 0),
    peso_min_kg         numeric(8,3)  NOT NULL DEFAULT 0 CHECK (peso_min_kg >= 0),
    peso_max_kg         numeric(8,3)  CHECK (peso_max_kg IS NULL OR peso_max_kg > peso_min_kg),
    envio_gratis_desde  numeric(12,2) CHECK (envio_gratis_desde >= 0),  -- monto de pedido
    activo              boolean       NOT NULL DEFAULT true,
    fecha_creacion      timestamptz   NOT NULL DEFAULT now(),
    fecha_actualizacion timestamptz,
    CONSTRAINT uq_tarifa_envio UNIQUE (zona_envio_id, metodo_envio_id, peso_min_kg)
);
CREATE INDEX idx_tarifa_envio_metodo ON tarifa_envio (metodo_envio_id);

COMMIT;
