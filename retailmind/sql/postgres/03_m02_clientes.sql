-- ============================================================
-- RetailMind - Script 03: M2 - Clientes
-- Tablas: grupo_cliente, cliente, segmento_cliente, cliente_segmento
-- Depende de: 02 (usuario)
-- ============================================================

BEGIN;

CREATE TABLE grupo_cliente (
    id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nombre               varchar(100)  NOT NULL UNIQUE,   -- 'Minorista', 'Mayorista', 'VIP'
    descripcion          text,
    porcentaje_descuento numeric(5,2)  NOT NULL DEFAULT 0
                         CHECK (porcentaje_descuento >= 0 AND porcentaje_descuento <= 100),
    activo               boolean       NOT NULL DEFAULT true,
    fecha_creacion       timestamptz   NOT NULL DEFAULT now(),
    fecha_actualizacion  timestamptz
);

-- usuario_id nullable: permite clientes invitados (guest checkout) sin cuenta.
CREATE TABLE cliente (
    id                    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    usuario_id            bigint       UNIQUE REFERENCES usuario (id)       ON DELETE RESTRICT ON UPDATE CASCADE,
    grupo_cliente_id      bigint       REFERENCES grupo_cliente (id)        ON DELETE SET NULL ON UPDATE CASCADE,
    tipo_identificacion   varchar(15)  CHECK (tipo_identificacion IN ('cedula', 'ruc', 'pasaporte')),
    numero_identificacion varchar(20),
    nombre                varchar(100) NOT NULL,
    apellido              varchar(100),
    email                 varchar(255) NOT NULL,
    telefono              varchar(20),
    fecha_nacimiento      date,
    genero                varchar(15)  CHECK (genero IN ('masculino', 'femenino', 'otro', 'no_indica')),
    acepta_marketing      boolean      NOT NULL DEFAULT false,
    activo                boolean      NOT NULL DEFAULT true,
    fecha_creacion        timestamptz  NOT NULL DEFAULT now(),
    fecha_actualizacion   timestamptz,
    CONSTRAINT uq_cliente_identificacion UNIQUE (tipo_identificacion, numero_identificacion)
);
CREATE INDEX idx_cliente_email ON cliente (lower(email));
CREATE INDEX idx_cliente_grupo ON cliente (grupo_cliente_id);

-- Segmentos para marketing/analitica (ej. 'compradores frecuentes').
-- criterio jsonb guarda la regla de segmentacion si es dinamica.
CREATE TABLE segmento_cliente (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nombre              varchar(100) NOT NULL UNIQUE,
    descripcion         text,
    criterio            jsonb,
    activo              boolean      NOT NULL DEFAULT true,
    fecha_creacion      timestamptz  NOT NULL DEFAULT now(),
    fecha_actualizacion timestamptz
);

CREATE TABLE cliente_segmento (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cliente_id          bigint      NOT NULL REFERENCES cliente (id)          ON DELETE CASCADE ON UPDATE CASCADE,
    segmento_cliente_id bigint      NOT NULL REFERENCES segmento_cliente (id) ON DELETE CASCADE ON UPDATE CASCADE,
    fecha_creacion      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_cliente_segmento UNIQUE (cliente_id, segmento_cliente_id)
);
CREATE INDEX idx_cliente_segmento_segmento ON cliente_segmento (segmento_cliente_id);

COMMIT;
