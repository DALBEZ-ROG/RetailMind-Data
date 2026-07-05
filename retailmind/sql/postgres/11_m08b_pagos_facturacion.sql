-- ============================================================
-- RetailMind - Script 11: M8 (parte B) - Pagos y Facturacion de venta
-- Tablas: pago, transaccion_pago, reembolso, factura_venta,
--         factura_venta_detalle
-- Depende de: 03 (cliente), 05 (moneda, metodo_pago, pasarela_pago),
--             10 (pedido, pedido_detalle, devolucion)
-- ============================================================

BEGIN;

CREATE TABLE pago (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pedido_id           bigint        NOT NULL REFERENCES pedido (id)      ON DELETE RESTRICT ON UPDATE CASCADE,
    metodo_pago_id      bigint        NOT NULL REFERENCES metodo_pago (id) ON DELETE RESTRICT ON UPDATE CASCADE,
    pasarela_pago_id    bigint        REFERENCES pasarela_pago (id)        ON DELETE SET NULL ON UPDATE CASCADE,
    moneda_id           bigint        NOT NULL REFERENCES moneda (id)      ON DELETE RESTRICT ON UPDATE CASCADE,
    monto               numeric(12,2) NOT NULL CHECK (monto > 0),
    estado              varchar(15)   NOT NULL DEFAULT 'pendiente'
                        CHECK (estado IN ('pendiente', 'autorizado', 'completado',
                                          'fallido', 'anulado', 'reembolsado')),
    referencia_externa  varchar(100),      -- id de transaccion en la pasarela
    fecha_pago          timestamptz,
    fecha_creacion      timestamptz   NOT NULL DEFAULT now(),
    fecha_actualizacion timestamptz
);
CREATE INDEX idx_pago_pedido ON pago (pedido_id);
CREATE INDEX idx_pago_metodo ON pago (metodo_pago_id);
CREATE INDEX idx_pago_pasarela ON pago (pasarela_pago_id);
CREATE INDEX idx_pago_estado ON pago (estado);
CREATE INDEX idx_pago_fecha ON pago (fecha_pago);

-- Bitacora de interacciones con la pasarela (un pago puede tener varias:
-- autorizacion, captura, reintento fallido, anulacion...).
CREATE TABLE transaccion_pago (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pago_id             bigint        NOT NULL REFERENCES pago (id) ON DELETE CASCADE ON UPDATE CASCADE,
    tipo                varchar(20)   NOT NULL
                        CHECK (tipo IN ('autorizacion', 'captura', 'anulacion', 'reembolso', 'verificacion')),
    estado              varchar(15)   NOT NULL
                        CHECK (estado IN ('exitosa', 'fallida', 'pendiente')),
    monto               numeric(12,2) NOT NULL CHECK (monto >= 0),
    codigo_autorizacion varchar(50),
    respuesta_pasarela  jsonb,
    fecha_creacion      timestamptz   NOT NULL DEFAULT now()
);
CREATE INDEX idx_transaccion_pago_pago ON transaccion_pago (pago_id);
CREATE INDEX idx_transaccion_pago_estado ON transaccion_pago (estado);

CREATE TABLE reembolso (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pago_id             bigint        NOT NULL REFERENCES pago (id) ON DELETE RESTRICT ON UPDATE CASCADE,
    devolucion_id       bigint        REFERENCES devolucion (id)    ON DELETE SET NULL ON UPDATE CASCADE,
    monto               numeric(12,2) NOT NULL CHECK (monto > 0),
    motivo              text,
    estado              varchar(15)   NOT NULL DEFAULT 'solicitado'
                        CHECK (estado IN ('solicitado', 'procesado', 'rechazado')),
    referencia_externa  varchar(100),
    fecha_procesado     timestamptz,
    fecha_creacion      timestamptz   NOT NULL DEFAULT now(),
    fecha_actualizacion timestamptz
);
CREATE INDEX idx_reembolso_pago ON reembolso (pago_id);
CREATE INDEX idx_reembolso_devolucion ON reembolso (devolucion_id);
CREATE INDEX idx_reembolso_estado ON reembolso (estado);

-- Datos del comprador desnormalizados (snapshot fiscal: la factura no puede
-- cambiar si el cliente edita sus datos). Totales por trigger (script 16).
CREATE TABLE factura_venta (
    id                    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    numero                varchar(20)   NOT NULL UNIQUE,     -- secuencial ej. '001-001-000000123'
    pedido_id             bigint        NOT NULL REFERENCES pedido (id)  ON DELETE RESTRICT ON UPDATE CASCADE,
    cliente_id            bigint        NOT NULL REFERENCES cliente (id) ON DELETE RESTRICT ON UPDATE CASCADE,
    moneda_id             bigint        NOT NULL REFERENCES moneda (id)  ON DELETE RESTRICT ON UPDATE CASCADE,
    clave_acceso          varchar(49)   UNIQUE,              -- clave de acceso SRI (facturacion electronica)
    razon_social          varchar(200)  NOT NULL,
    identificacion        varchar(20)   NOT NULL,
    direccion_facturacion text,
    subtotal              numeric(12,2) NOT NULL DEFAULT 0 CHECK (subtotal >= 0),
    monto_descuento       numeric(12,2) NOT NULL DEFAULT 0 CHECK (monto_descuento >= 0),
    monto_impuesto        numeric(12,2) NOT NULL DEFAULT 0 CHECK (monto_impuesto >= 0),
    total                 numeric(12,2) NOT NULL DEFAULT 0 CHECK (total >= 0),
    estado                varchar(15)   NOT NULL DEFAULT 'emitida'
                          CHECK (estado IN ('emitida', 'autorizada', 'anulada')),
    fecha_emision         timestamptz   NOT NULL DEFAULT now(),
    fecha_creacion        timestamptz   NOT NULL DEFAULT now(),
    fecha_actualizacion   timestamptz
);
CREATE INDEX idx_factura_venta_pedido ON factura_venta (pedido_id);
CREATE INDEX idx_factura_venta_cliente ON factura_venta (cliente_id);
CREATE INDEX idx_factura_venta_estado ON factura_venta (estado);
CREATE INDEX idx_factura_venta_fecha ON factura_venta (fecha_emision);

CREATE TABLE factura_venta_detalle (
    id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    factura_venta_id     bigint        NOT NULL REFERENCES factura_venta (id)     ON DELETE CASCADE  ON UPDATE CASCADE,
    pedido_detalle_id    bigint        REFERENCES pedido_detalle (id)             ON DELETE SET NULL ON UPDATE CASCADE,
    producto_variante_id bigint        NOT NULL REFERENCES producto_variante (id) ON DELETE RESTRICT ON UPDATE CASCADE,
    descripcion          varchar(255)  NOT NULL,
    cantidad             int           NOT NULL CHECK (cantidad > 0),
    precio_unitario      numeric(12,2) NOT NULL CHECK (precio_unitario >= 0),
    subtotal             numeric(14,2) GENERATED ALWAYS AS (cantidad * precio_unitario) STORED,
    monto_descuento      numeric(12,2) NOT NULL DEFAULT 0 CHECK (monto_descuento >= 0),
    monto_impuesto       numeric(12,2) NOT NULL DEFAULT 0 CHECK (monto_impuesto >= 0),
    fecha_creacion       timestamptz   NOT NULL DEFAULT now()
);
CREATE INDEX idx_factura_venta_detalle_factura ON factura_venta_detalle (factura_venta_id);
CREATE INDEX idx_factura_venta_detalle_pedido_detalle ON factura_venta_detalle (pedido_detalle_id);
CREATE INDEX idx_factura_venta_detalle_variante ON factura_venta_detalle (producto_variante_id);

COMMIT;
