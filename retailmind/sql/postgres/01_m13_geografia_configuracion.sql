-- ============================================================
-- RetailMind - Esquema transaccional PostgreSQL
-- Script 01: M13 - Geografia y Configuracion
-- Tablas: pais, provincia, ciudad, idioma, configuracion_tienda, traduccion
-- Sin dependencias externas: se ejecuta primero.
-- ============================================================

BEGIN;

CREATE TABLE pais (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo_iso2         char(2)      NOT NULL UNIQUE,
    codigo_iso3         char(3)      NOT NULL UNIQUE,
    nombre              varchar(100) NOT NULL UNIQUE,
    prefijo_telefonico  varchar(8),
    activo              boolean      NOT NULL DEFAULT true,
    fecha_creacion      timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE provincia (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pais_id         bigint       NOT NULL REFERENCES pais (id) ON DELETE RESTRICT ON UPDATE CASCADE,
    codigo          varchar(10),
    nombre          varchar(100) NOT NULL,
    activo          boolean      NOT NULL DEFAULT true,
    fecha_creacion  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_provincia_pais_nombre UNIQUE (pais_id, nombre)
);
CREATE INDEX idx_provincia_pais ON provincia (pais_id);

CREATE TABLE ciudad (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    provincia_id    bigint       NOT NULL REFERENCES provincia (id) ON DELETE RESTRICT ON UPDATE CASCADE,
    nombre          varchar(100) NOT NULL,
    codigo_postal   varchar(20),
    activo          boolean      NOT NULL DEFAULT true,
    fecha_creacion  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_ciudad_provincia_nombre UNIQUE (provincia_id, nombre)
);
CREATE INDEX idx_ciudad_provincia ON ciudad (provincia_id);

CREATE TABLE idioma (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo          varchar(10)  NOT NULL UNIQUE,   -- ej. 'es', 'en', 'es-EC'
    nombre          varchar(80)  NOT NULL,
    es_predeterminado boolean    NOT NULL DEFAULT false,
    activo          boolean      NOT NULL DEFAULT true,
    fecha_creacion  timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE configuracion_tienda (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    clave               varchar(100) NOT NULL UNIQUE,
    valor               text,
    tipo_dato           varchar(20)  NOT NULL DEFAULT 'texto'
                        CHECK (tipo_dato IN ('texto', 'numero', 'booleano', 'json')),
    descripcion         text,
    fecha_creacion      timestamptz  NOT NULL DEFAULT now(),
    fecha_actualizacion timestamptz
);

-- Traducciones polimorficas: (entidad, entidad_id, campo) apunta a cualquier
-- tabla traducible (producto, categoria, faq, ...). Sin FK fisica por diseno.
CREATE TABLE traduccion (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    idioma_id           bigint       NOT NULL REFERENCES idioma (id) ON DELETE CASCADE ON UPDATE CASCADE,
    entidad             varchar(60)  NOT NULL,   -- nombre de tabla origen
    entidad_id          bigint       NOT NULL,   -- id del registro origen
    campo               varchar(60)  NOT NULL,   -- columna traducida
    valor               text         NOT NULL,
    fecha_creacion      timestamptz  NOT NULL DEFAULT now(),
    fecha_actualizacion timestamptz,
    CONSTRAINT uq_traduccion UNIQUE (idioma_id, entidad, entidad_id, campo)
);
CREATE INDEX idx_traduccion_entidad ON traduccion (entidad, entidad_id);
CREATE INDEX idx_traduccion_idioma ON traduccion (idioma_id);

COMMIT;
