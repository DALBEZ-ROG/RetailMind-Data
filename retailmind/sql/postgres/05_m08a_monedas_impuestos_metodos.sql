-- ============================================================
-- RetailMind - Script 05: M8 (parte A) - Catalogos financieros
-- Tablas: moneda, tipo_cambio, impuesto, producto_impuesto,
--         pasarela_pago, metodo_pago
-- Se crean antes de compras/ventas porque ambos ciclos los referencian.
-- Depende de: 01 (pais), 04 (producto)
-- ============================================================

BEGIN;

CREATE TABLE moneda (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo          char(3)     NOT NULL UNIQUE,    -- ISO 4217: 'USD'
    nombre          varchar(80) NOT NULL,
    simbolo         varchar(5)  NOT NULL,
    decimales       smallint    NOT NULL DEFAULT 2 CHECK (decimales BETWEEN 0 AND 6),
    es_base         boolean     NOT NULL DEFAULT false,
    activo          boolean     NOT NULL DEFAULT true,
    fecha_creacion  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE tipo_cambio (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    moneda_origen_id  bigint         NOT NULL REFERENCES moneda (id) ON DELETE CASCADE ON UPDATE CASCADE,
    moneda_destino_id bigint         NOT NULL REFERENCES moneda (id) ON DELETE CASCADE ON UPDATE CASCADE,
    tasa              numeric(18,8)  NOT NULL CHECK (tasa > 0),
    fecha             date           NOT NULL,
    fecha_creacion    timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT uq_tipo_cambio UNIQUE (moneda_origen_id, moneda_destino_id, fecha),
    CONSTRAINT ck_tipo_cambio_distintas CHECK (moneda_origen_id <> moneda_destino_id)
);
CREATE INDEX idx_tipo_cambio_destino ON tipo_cambio (moneda_destino_id);
CREATE INDEX idx_tipo_cambio_fecha ON tipo_cambio (fecha);

-- IVA/ICE modelados como catalogo; el producto se vincula via producto_impuesto.
CREATE TABLE impuesto (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo              varchar(20)  NOT NULL UNIQUE,   -- 'IVA15', 'IVA0', 'ICE'
    nombre              varchar(100) NOT NULL,
    tipo                varchar(20)  NOT NULL DEFAULT 'iva'
                        CHECK (tipo IN ('iva', 'ice', 'otro')),
    porcentaje          numeric(5,2) NOT NULL CHECK (porcentaje >= 0),
    pais_id             bigint       REFERENCES pais (id) ON DELETE RESTRICT ON UPDATE CASCADE,
    activo              boolean      NOT NULL DEFAULT true,
    fecha_creacion      timestamptz  NOT NULL DEFAULT now(),
    fecha_actualizacion timestamptz
);
CREATE INDEX idx_impuesto_pais ON impuesto (pais_id);

CREATE TABLE producto_impuesto (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    producto_id     bigint      NOT NULL REFERENCES producto (id) ON DELETE CASCADE  ON UPDATE CASCADE,
    impuesto_id     bigint      NOT NULL REFERENCES impuesto (id) ON DELETE RESTRICT ON UPDATE CASCADE,
    fecha_creacion  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_producto_impuesto UNIQUE (producto_id, impuesto_id)
);
CREATE INDEX idx_producto_impuesto_impuesto ON producto_impuesto (impuesto_id);

CREATE TABLE pasarela_pago (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo              varchar(50)  NOT NULL UNIQUE,   -- 'payphone', 'datafast', 'paypal'
    nombre              varchar(100) NOT NULL,
    modo                varchar(15)  NOT NULL DEFAULT 'prueba'
                        CHECK (modo IN ('prueba', 'produccion')),
    configuracion       jsonb,          -- credenciales/endpoints cifrados por la app
    activo              boolean      NOT NULL DEFAULT true,
    fecha_creacion      timestamptz  NOT NULL DEFAULT now(),
    fecha_actualizacion timestamptz
);

CREATE TABLE metodo_pago (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo              varchar(50)  NOT NULL UNIQUE,   -- 'tarjeta_credito', 'transferencia'
    nombre              varchar(100) NOT NULL,
    tipo                varchar(20)  NOT NULL
                        CHECK (tipo IN ('tarjeta', 'transferencia', 'efectivo', 'billetera', 'contra_entrega')),
    pasarela_pago_id    bigint       REFERENCES pasarela_pago (id) ON DELETE SET NULL ON UPDATE CASCADE,
    requiere_pasarela   boolean      NOT NULL DEFAULT false,
    orden               int          NOT NULL DEFAULT 0,
    activo              boolean      NOT NULL DEFAULT true,
    fecha_creacion      timestamptz  NOT NULL DEFAULT now(),
    fecha_actualizacion timestamptz
);
CREATE INDEX idx_metodo_pago_pasarela ON metodo_pago (pasarela_pago_id);

COMMIT;
