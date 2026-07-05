-- ============================================================
-- RetailMind - Script 04: M3 - Catalogo de productos
-- Tablas: marca, categoria, producto, producto_variante, atributo,
--         valor_atributo, variante_valor_atributo, producto_imagen,
--         producto_categoria, producto_relacionado, etiqueta,
--         producto_etiqueta, producto_especificacion
-- Depende de: nada externo (autocontenido)
-- ============================================================

BEGIN;

CREATE TABLE marca (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nombre              varchar(100) NOT NULL UNIQUE,
    slug                varchar(120) NOT NULL UNIQUE,
    logo_url            varchar(500),
    descripcion         text,
    activo              boolean      NOT NULL DEFAULT true,
    fecha_creacion      timestamptz  NOT NULL DEFAULT now(),
    fecha_actualizacion timestamptz
);

-- Jerarquia por lista de adyacencia: categoria_padre_id auto-referenciada.
-- RESTRICT impide borrar una categoria con hijas (deben reasignarse antes).
CREATE TABLE categoria (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    categoria_padre_id  bigint       REFERENCES categoria (id) ON DELETE RESTRICT ON UPDATE CASCADE,
    nombre              varchar(100) NOT NULL,
    slug                varchar(120) NOT NULL UNIQUE,
    descripcion         text,
    imagen_url          varchar(500),
    orden               int          NOT NULL DEFAULT 0,
    activo              boolean      NOT NULL DEFAULT true,
    fecha_creacion      timestamptz  NOT NULL DEFAULT now(),
    fecha_actualizacion timestamptz,
    CONSTRAINT ck_categoria_no_ciclo_directo CHECK (categoria_padre_id IS DISTINCT FROM id)
);
CREATE INDEX idx_categoria_padre ON categoria (categoria_padre_id);

-- Producto = entidad comercial; el stock/precio vive en producto_variante (SKU).
CREATE TABLE producto (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    marca_id            bigint       REFERENCES marca (id) ON DELETE SET NULL ON UPDATE CASCADE,
    nombre              varchar(200) NOT NULL,
    slug                varchar(220) NOT NULL UNIQUE,
    descripcion_corta   varchar(500),
    descripcion         text,
    publicado           boolean      NOT NULL DEFAULT false,
    destacado           boolean      NOT NULL DEFAULT false,
    activo              boolean      NOT NULL DEFAULT true,
    fecha_creacion      timestamptz  NOT NULL DEFAULT now(),
    fecha_actualizacion timestamptz
);
CREATE INDEX idx_producto_marca ON producto (marca_id);
CREATE INDEX idx_producto_nombre ON producto (nombre);
CREATE INDEX idx_producto_publicado ON producto (publicado) WHERE publicado;

CREATE TABLE producto_variante (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    producto_id         bigint        NOT NULL REFERENCES producto (id) ON DELETE CASCADE ON UPDATE CASCADE,
    sku                 varchar(50)   NOT NULL UNIQUE,
    codigo_barras       varchar(50)   UNIQUE,
    precio              numeric(12,2) NOT NULL CHECK (precio >= 0),
    precio_comparacion  numeric(12,2) CHECK (precio_comparacion >= 0),   -- precio "antes" tachado
    costo               numeric(12,2) NOT NULL DEFAULT 0 CHECK (costo >= 0),
    peso_kg             numeric(8,3)  CHECK (peso_kg >= 0),
    alto_cm             numeric(8,2)  CHECK (alto_cm >= 0),
    ancho_cm            numeric(8,2)  CHECK (ancho_cm >= 0),
    largo_cm            numeric(8,2)  CHECK (largo_cm >= 0),
    es_predeterminada   boolean       NOT NULL DEFAULT false,
    activo              boolean       NOT NULL DEFAULT true,
    fecha_creacion      timestamptz   NOT NULL DEFAULT now(),
    fecha_actualizacion timestamptz
);
CREATE INDEX idx_producto_variante_producto ON producto_variante (producto_id);

CREATE TABLE atributo (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo          varchar(50)  NOT NULL UNIQUE,   -- 'color', 'talla'
    nombre          varchar(100) NOT NULL UNIQUE,
    tipo            varchar(15)  NOT NULL DEFAULT 'texto'
                    CHECK (tipo IN ('texto', 'color', 'numero')),
    activo          boolean      NOT NULL DEFAULT true,
    fecha_creacion  timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE valor_atributo (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    atributo_id     bigint       NOT NULL REFERENCES atributo (id) ON DELETE CASCADE ON UPDATE CASCADE,
    valor           varchar(100) NOT NULL,          -- 'Rojo', 'XL'
    valor_extra     varchar(50),                    -- ej. codigo hex '#FF0000'
    orden           int          NOT NULL DEFAULT 0,
    fecha_creacion  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_valor_atributo UNIQUE (atributo_id, valor)
);
CREATE INDEX idx_valor_atributo_atributo ON valor_atributo (atributo_id);

CREATE TABLE variante_valor_atributo (
    id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    producto_variante_id bigint      NOT NULL REFERENCES producto_variante (id) ON DELETE CASCADE  ON UPDATE CASCADE,
    valor_atributo_id    bigint      NOT NULL REFERENCES valor_atributo (id)    ON DELETE RESTRICT ON UPDATE CASCADE,
    fecha_creacion       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_variante_valor_atributo UNIQUE (producto_variante_id, valor_atributo_id)
);
CREATE INDEX idx_variante_valor_atributo_valor ON variante_valor_atributo (valor_atributo_id);

CREATE TABLE producto_imagen (
    id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    producto_id          bigint       NOT NULL REFERENCES producto (id)      ON DELETE CASCADE ON UPDATE CASCADE,
    producto_variante_id bigint       REFERENCES producto_variante (id)      ON DELETE CASCADE ON UPDATE CASCADE,
    url                  varchar(500) NOT NULL,
    texto_alternativo    varchar(200),
    orden                int          NOT NULL DEFAULT 0,
    es_principal         boolean      NOT NULL DEFAULT false,
    fecha_creacion       timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_producto_imagen_producto ON producto_imagen (producto_id);
CREATE INDEX idx_producto_imagen_variante ON producto_imagen (producto_variante_id);

CREATE TABLE producto_categoria (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    producto_id     bigint      NOT NULL REFERENCES producto (id)  ON DELETE CASCADE ON UPDATE CASCADE,
    categoria_id    bigint      NOT NULL REFERENCES categoria (id) ON DELETE CASCADE ON UPDATE CASCADE,
    es_principal    boolean     NOT NULL DEFAULT false,
    fecha_creacion  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_producto_categoria UNIQUE (producto_id, categoria_id)
);
CREATE INDEX idx_producto_categoria_categoria ON producto_categoria (categoria_id);

CREATE TABLE producto_relacionado (
    id                      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    producto_id             bigint      NOT NULL REFERENCES producto (id) ON DELETE CASCADE ON UPDATE CASCADE,
    producto_relacionado_id bigint      NOT NULL REFERENCES producto (id) ON DELETE CASCADE ON UPDATE CASCADE,
    tipo                    varchar(20) NOT NULL DEFAULT 'relacionado'
                            CHECK (tipo IN ('relacionado', 'venta_cruzada', 'venta_superior')),
    fecha_creacion          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_producto_relacionado UNIQUE (producto_id, producto_relacionado_id, tipo),
    CONSTRAINT ck_producto_relacionado_distinto CHECK (producto_id <> producto_relacionado_id)
);
CREATE INDEX idx_producto_relacionado_destino ON producto_relacionado (producto_relacionado_id);

CREATE TABLE etiqueta (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nombre          varchar(80)  NOT NULL UNIQUE,
    slug            varchar(100) NOT NULL UNIQUE,
    fecha_creacion  timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE producto_etiqueta (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    producto_id     bigint      NOT NULL REFERENCES producto (id) ON DELETE CASCADE ON UPDATE CASCADE,
    etiqueta_id     bigint      NOT NULL REFERENCES etiqueta (id) ON DELETE CASCADE ON UPDATE CASCADE,
    fecha_creacion  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_producto_etiqueta UNIQUE (producto_id, etiqueta_id)
);
CREATE INDEX idx_producto_etiqueta_etiqueta ON producto_etiqueta (etiqueta_id);

-- Ficha tecnica: pares nombre/valor agrupables ('Pantalla', 'Bateria', ...)
CREATE TABLE producto_especificacion (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    producto_id     bigint       NOT NULL REFERENCES producto (id) ON DELETE CASCADE ON UPDATE CASCADE,
    grupo           varchar(100),
    nombre          varchar(100) NOT NULL,
    valor           varchar(500) NOT NULL,
    orden           int          NOT NULL DEFAULT 0,
    fecha_creacion  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_producto_especificacion UNIQUE (producto_id, nombre)
);

COMMIT;
