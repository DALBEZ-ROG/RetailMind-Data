-- ============================================================
-- RetailMind - Script 07: M5 - Proveedores y Compras (Procure-to-Pay)
-- Tablas: proveedor, contacto_proveedor, producto_proveedor, orden_compra,
--         orden_compra_detalle, recepcion_mercancia, recepcion_detalle,
--         factura_compra, factura_compra_detalle, cuenta_por_pagar,
--         pago_proveedor
-- Depende de: 01 (ciudad), 02 (usuario), 04 (producto_variante),
--             05 (moneda, metodo_pago), 06 (bodega, lote)
-- ============================================================

BEGIN;

CREATE TABLE proveedor (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ruc                 varchar(13)  NOT NULL UNIQUE,
    razon_social        varchar(200) NOT NULL,
    nombre_comercial    varchar(200),
    email               varchar(255),
    telefono            varchar(20),
    ciudad_id           bigint       REFERENCES ciudad (id) ON DELETE SET NULL ON UPDATE CASCADE,
    direccion           varchar(255),
    sitio_web           varchar(255),
    dias_credito        smallint     NOT NULL DEFAULT 0 CHECK (dias_credito >= 0),
    activo              boolean      NOT NULL DEFAULT true,
    fecha_creacion      timestamptz  NOT NULL DEFAULT now(),
    fecha_actualizacion timestamptz
);
CREATE INDEX idx_proveedor_ciudad ON proveedor (ciudad_id);
CREATE INDEX idx_proveedor_razon_social ON proveedor (razon_social);

CREATE TABLE contacto_proveedor (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    proveedor_id    bigint       NOT NULL REFERENCES proveedor (id) ON DELETE CASCADE ON UPDATE CASCADE,
    nombre          varchar(150) NOT NULL,
    cargo           varchar(100),
    email           varchar(255),
    telefono        varchar(20),
    es_principal    boolean      NOT NULL DEFAULT false,
    activo          boolean      NOT NULL DEFAULT true,
    fecha_creacion  timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_contacto_proveedor_proveedor ON contacto_proveedor (proveedor_id);

-- Catalogo de que variantes surte cada proveedor y a que costo.
CREATE TABLE producto_proveedor (
    id                    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    proveedor_id          bigint        NOT NULL REFERENCES proveedor (id)         ON DELETE CASCADE  ON UPDATE CASCADE,
    producto_variante_id  bigint        NOT NULL REFERENCES producto_variante (id) ON DELETE CASCADE  ON UPDATE CASCADE,
    codigo_proveedor      varchar(50),
    costo                 numeric(12,2) NOT NULL CHECK (costo >= 0),
    tiempo_entrega_dias   smallint      CHECK (tiempo_entrega_dias >= 0),
    cantidad_minima       int           NOT NULL DEFAULT 1 CHECK (cantidad_minima > 0),
    es_preferido          boolean       NOT NULL DEFAULT false,
    activo                boolean       NOT NULL DEFAULT true,
    fecha_creacion        timestamptz   NOT NULL DEFAULT now(),
    fecha_actualizacion   timestamptz,
    CONSTRAINT uq_producto_proveedor UNIQUE (proveedor_id, producto_variante_id)
);
CREATE INDEX idx_producto_proveedor_variante ON producto_proveedor (producto_variante_id);

-- Totales (subtotal, monto_impuesto, total) mantenidos por trigger (script 16).
CREATE TABLE orden_compra (
    id                      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    numero                  varchar(20)   NOT NULL UNIQUE,
    proveedor_id            bigint        NOT NULL REFERENCES proveedor (id) ON DELETE RESTRICT ON UPDATE CASCADE,
    bodega_id               bigint        NOT NULL REFERENCES bodega (id)    ON DELETE RESTRICT ON UPDATE CASCADE,
    moneda_id               bigint        NOT NULL REFERENCES moneda (id)    ON DELETE RESTRICT ON UPDATE CASCADE,
    usuario_id              bigint        REFERENCES usuario (id)            ON DELETE SET NULL ON UPDATE CASCADE,
    estado                  varchar(20)   NOT NULL DEFAULT 'borrador'
                            CHECK (estado IN ('borrador', 'enviada', 'confirmada',
                                              'recibida_parcial', 'recibida', 'cancelada')),
    fecha_emision           date          NOT NULL DEFAULT CURRENT_DATE,
    fecha_entrega_esperada  date,
    subtotal                numeric(12,2) NOT NULL DEFAULT 0 CHECK (subtotal >= 0),
    monto_impuesto          numeric(12,2) NOT NULL DEFAULT 0 CHECK (monto_impuesto >= 0),
    total                   numeric(12,2) NOT NULL DEFAULT 0 CHECK (total >= 0),
    observacion             text,
    fecha_creacion          timestamptz   NOT NULL DEFAULT now(),
    fecha_actualizacion     timestamptz
);
CREATE INDEX idx_orden_compra_proveedor ON orden_compra (proveedor_id);
CREATE INDEX idx_orden_compra_bodega ON orden_compra (bodega_id);
CREATE INDEX idx_orden_compra_estado ON orden_compra (estado);
CREATE INDEX idx_orden_compra_fecha ON orden_compra (fecha_emision);

CREATE TABLE orden_compra_detalle (
    id                    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    orden_compra_id       bigint        NOT NULL REFERENCES orden_compra (id)      ON DELETE CASCADE  ON UPDATE CASCADE,
    producto_variante_id  bigint        NOT NULL REFERENCES producto_variante (id) ON DELETE RESTRICT ON UPDATE CASCADE,
    cantidad              int           NOT NULL CHECK (cantidad > 0),
    precio_unitario       numeric(12,2) NOT NULL CHECK (precio_unitario >= 0),
    subtotal              numeric(14,2) GENERATED ALWAYS AS (cantidad * precio_unitario) STORED,
    monto_impuesto        numeric(12,2) NOT NULL DEFAULT 0 CHECK (monto_impuesto >= 0),
    cantidad_recibida     int           NOT NULL DEFAULT 0 CHECK (cantidad_recibida >= 0),
    fecha_creacion        timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT uq_orden_compra_detalle UNIQUE (orden_compra_id, producto_variante_id)
);
CREATE INDEX idx_orden_compra_detalle_variante ON orden_compra_detalle (producto_variante_id);

CREATE TABLE recepcion_mercancia (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    numero              varchar(20) NOT NULL UNIQUE,
    orden_compra_id     bigint      NOT NULL REFERENCES orden_compra (id) ON DELETE RESTRICT ON UPDATE CASCADE,
    bodega_id           bigint      NOT NULL REFERENCES bodega (id)       ON DELETE RESTRICT ON UPDATE CASCADE,
    usuario_id          bigint      REFERENCES usuario (id)               ON DELETE SET NULL ON UPDATE CASCADE,
    estado              varchar(15) NOT NULL DEFAULT 'registrada'
                        CHECK (estado IN ('registrada', 'confirmada', 'anulada')),
    fecha_recepcion     timestamptz NOT NULL DEFAULT now(),
    observacion         text,
    fecha_creacion      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_recepcion_mercancia_orden ON recepcion_mercancia (orden_compra_id);
CREATE INDEX idx_recepcion_mercancia_bodega ON recepcion_mercancia (bodega_id);
CREATE INDEX idx_recepcion_mercancia_estado ON recepcion_mercancia (estado);

CREATE TABLE recepcion_detalle (
    id                      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    recepcion_mercancia_id  bigint      NOT NULL REFERENCES recepcion_mercancia (id)  ON DELETE CASCADE  ON UPDATE CASCADE,
    orden_compra_detalle_id bigint      NOT NULL REFERENCES orden_compra_detalle (id) ON DELETE RESTRICT ON UPDATE CASCADE,
    lote_id                 bigint      REFERENCES lote (id)                          ON DELETE SET NULL ON UPDATE CASCADE,
    cantidad_recibida       int         NOT NULL CHECK (cantidad_recibida > 0),
    cantidad_rechazada      int         NOT NULL DEFAULT 0 CHECK (cantidad_rechazada >= 0),
    motivo_rechazo          text,
    fecha_creacion          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_recepcion_detalle_recepcion ON recepcion_detalle (recepcion_mercancia_id);
CREATE INDEX idx_recepcion_detalle_oc_detalle ON recepcion_detalle (orden_compra_detalle_id);
CREATE INDEX idx_recepcion_detalle_lote ON recepcion_detalle (lote_id);

-- Totales mantenidos por trigger (script 16).
CREATE TABLE factura_compra (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    proveedor_id        bigint        NOT NULL REFERENCES proveedor (id) ON DELETE RESTRICT ON UPDATE CASCADE,
    orden_compra_id     bigint        REFERENCES orden_compra (id)       ON DELETE SET NULL ON UPDATE CASCADE,
    moneda_id           bigint        NOT NULL REFERENCES moneda (id)    ON DELETE RESTRICT ON UPDATE CASCADE,
    numero_factura      varchar(50)   NOT NULL,
    fecha_emision       date          NOT NULL,
    fecha_vencimiento   date,
    subtotal            numeric(12,2) NOT NULL DEFAULT 0 CHECK (subtotal >= 0),
    monto_impuesto      numeric(12,2) NOT NULL DEFAULT 0 CHECK (monto_impuesto >= 0),
    total               numeric(12,2) NOT NULL DEFAULT 0 CHECK (total >= 0),
    estado              varchar(20)   NOT NULL DEFAULT 'registrada'
                        CHECK (estado IN ('registrada', 'pagada_parcial', 'pagada', 'anulada')),
    fecha_creacion      timestamptz   NOT NULL DEFAULT now(),
    fecha_actualizacion timestamptz,
    CONSTRAINT uq_factura_compra UNIQUE (proveedor_id, numero_factura),
    CONSTRAINT ck_factura_compra_vencimiento CHECK (fecha_vencimiento IS NULL OR fecha_vencimiento >= fecha_emision)
);
CREATE INDEX idx_factura_compra_orden ON factura_compra (orden_compra_id);
CREATE INDEX idx_factura_compra_moneda ON factura_compra (moneda_id);
CREATE INDEX idx_factura_compra_estado ON factura_compra (estado);
CREATE INDEX idx_factura_compra_fecha ON factura_compra (fecha_emision);

CREATE TABLE factura_compra_detalle (
    id                    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    factura_compra_id     bigint        NOT NULL REFERENCES factura_compra (id)    ON DELETE CASCADE  ON UPDATE CASCADE,
    producto_variante_id  bigint        NOT NULL REFERENCES producto_variante (id) ON DELETE RESTRICT ON UPDATE CASCADE,
    cantidad              int           NOT NULL CHECK (cantidad > 0),
    precio_unitario       numeric(12,2) NOT NULL CHECK (precio_unitario >= 0),
    subtotal              numeric(14,2) GENERATED ALWAYS AS (cantidad * precio_unitario) STORED,
    monto_impuesto        numeric(12,2) NOT NULL DEFAULT 0 CHECK (monto_impuesto >= 0),
    fecha_creacion        timestamptz   NOT NULL DEFAULT now()
);
CREATE INDEX idx_factura_compra_detalle_factura ON factura_compra_detalle (factura_compra_id);
CREATE INDEX idx_factura_compra_detalle_variante ON factura_compra_detalle (producto_variante_id);

CREATE TABLE cuenta_por_pagar (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    factura_compra_id   bigint        NOT NULL UNIQUE REFERENCES factura_compra (id) ON DELETE RESTRICT ON UPDATE CASCADE,
    proveedor_id        bigint        NOT NULL REFERENCES proveedor (id)             ON DELETE RESTRICT ON UPDATE CASCADE,
    monto_original      numeric(12,2) NOT NULL CHECK (monto_original >= 0),
    saldo_pendiente     numeric(12,2) NOT NULL CHECK (saldo_pendiente >= 0),
    fecha_vencimiento   date          NOT NULL,
    estado              varchar(15)   NOT NULL DEFAULT 'pendiente'
                        CHECK (estado IN ('pendiente', 'parcial', 'pagada', 'vencida')),
    fecha_creacion      timestamptz   NOT NULL DEFAULT now(),
    fecha_actualizacion timestamptz,
    CONSTRAINT ck_cuenta_por_pagar_saldo CHECK (saldo_pendiente <= monto_original)
);
CREATE INDEX idx_cuenta_por_pagar_proveedor ON cuenta_por_pagar (proveedor_id);
CREATE INDEX idx_cuenta_por_pagar_estado ON cuenta_por_pagar (estado);
CREATE INDEX idx_cuenta_por_pagar_vencimiento ON cuenta_por_pagar (fecha_vencimiento);

CREATE TABLE pago_proveedor (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cuenta_por_pagar_id bigint        NOT NULL REFERENCES cuenta_por_pagar (id) ON DELETE RESTRICT ON UPDATE CASCADE,
    metodo_pago_id      bigint        NOT NULL REFERENCES metodo_pago (id)      ON DELETE RESTRICT ON UPDATE CASCADE,
    usuario_id          bigint        REFERENCES usuario (id)                   ON DELETE SET NULL ON UPDATE CASCADE,
    monto               numeric(12,2) NOT NULL CHECK (monto > 0),
    fecha_pago          date          NOT NULL DEFAULT CURRENT_DATE,
    referencia          varchar(100),        -- nro. transferencia / cheque
    observacion         text,
    fecha_creacion      timestamptz   NOT NULL DEFAULT now()
);
CREATE INDEX idx_pago_proveedor_cuenta ON pago_proveedor (cuenta_por_pagar_id);
CREATE INDEX idx_pago_proveedor_metodo ON pago_proveedor (metodo_pago_id);
CREATE INDEX idx_pago_proveedor_fecha ON pago_proveedor (fecha_pago);

COMMIT;
