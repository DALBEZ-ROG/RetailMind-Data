-- ============================================================
-- RetailMind - Script 10: M7 - Pedidos y Ventas (Order-to-Cash)
-- Tablas: estado_pedido, pedido, pedido_detalle, historial_estado_pedido,
--         nota_pedido, motivo_devolucion, devolucion, devolucion_detalle
-- Depende de: 02 (usuario, direccion), 03 (cliente), 04 (producto_variante),
--             05 (moneda), 06 (reserva_stock), 09 (metodo_envio)
-- ============================================================

BEGIN;

-- Maquina de estados del pedido como catalogo (extensible sin ALTER).
CREATE TABLE estado_pedido (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo          varchar(30)  NOT NULL UNIQUE,
    nombre          varchar(80)  NOT NULL,
    descripcion     text,
    orden           smallint     NOT NULL DEFAULT 0,
    es_final        boolean      NOT NULL DEFAULT false,
    activo          boolean      NOT NULL DEFAULT true,
    fecha_creacion  timestamptz  NOT NULL DEFAULT now()
);

-- Totales (subtotal, monto_impuesto, total) mantenidos por trigger (script 16):
-- total = subtotal - monto_descuento + monto_impuesto + costo_envio.
CREATE TABLE pedido (
    id                       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    numero                   varchar(20)   NOT NULL UNIQUE,
    cliente_id               bigint        NOT NULL REFERENCES cliente (id)       ON DELETE RESTRICT ON UPDATE CASCADE,
    estado_pedido_id         bigint        NOT NULL REFERENCES estado_pedido (id) ON DELETE RESTRICT ON UPDATE CASCADE,
    moneda_id                bigint        NOT NULL REFERENCES moneda (id)        ON DELETE RESTRICT ON UPDATE CASCADE,
    metodo_envio_id          bigint        REFERENCES metodo_envio (id)           ON DELETE SET NULL ON UPDATE CASCADE,
    direccion_envio_id       bigint        REFERENCES direccion (id)              ON DELETE RESTRICT ON UPDATE CASCADE,
    direccion_facturacion_id bigint        REFERENCES direccion (id)              ON DELETE RESTRICT ON UPDATE CASCADE,
    canal                    varchar(15)   NOT NULL DEFAULT 'web'
                             CHECK (canal IN ('web', 'tienda', 'telefono')),
    subtotal                 numeric(12,2) NOT NULL DEFAULT 0 CHECK (subtotal >= 0),
    monto_descuento          numeric(12,2) NOT NULL DEFAULT 0 CHECK (monto_descuento >= 0),
    monto_impuesto           numeric(12,2) NOT NULL DEFAULT 0 CHECK (monto_impuesto >= 0),
    costo_envio              numeric(12,2) NOT NULL DEFAULT 0 CHECK (costo_envio >= 0),
    total                    numeric(12,2) NOT NULL DEFAULT 0 CHECK (total >= 0),
    fecha_pedido             timestamptz   NOT NULL DEFAULT now(),
    fecha_creacion           timestamptz   NOT NULL DEFAULT now(),
    fecha_actualizacion      timestamptz
);
CREATE INDEX idx_pedido_cliente ON pedido (cliente_id);
CREATE INDEX idx_pedido_estado ON pedido (estado_pedido_id);
CREATE INDEX idx_pedido_moneda ON pedido (moneda_id);
CREATE INDEX idx_pedido_metodo_envio ON pedido (metodo_envio_id);
CREATE INDEX idx_pedido_direccion_envio ON pedido (direccion_envio_id);
CREATE INDEX idx_pedido_direccion_facturacion ON pedido (direccion_facturacion_id);
CREATE INDEX idx_pedido_fecha ON pedido (fecha_pedido);

-- nombre_producto y sku son snapshot: preservan lo vendido aunque el
-- catalogo cambie despues.
CREATE TABLE pedido_detalle (
    id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pedido_id            bigint        NOT NULL REFERENCES pedido (id)            ON DELETE CASCADE  ON UPDATE CASCADE,
    producto_variante_id bigint        NOT NULL REFERENCES producto_variante (id) ON DELETE RESTRICT ON UPDATE CASCADE,
    nombre_producto      varchar(200)  NOT NULL,
    sku                  varchar(50)   NOT NULL,
    cantidad             int           NOT NULL CHECK (cantidad > 0),
    precio_unitario      numeric(12,2) NOT NULL CHECK (precio_unitario >= 0),
    subtotal             numeric(14,2) GENERATED ALWAYS AS (cantidad * precio_unitario) STORED,
    monto_descuento      numeric(12,2) NOT NULL DEFAULT 0 CHECK (monto_descuento >= 0),
    monto_impuesto       numeric(12,2) NOT NULL DEFAULT 0 CHECK (monto_impuesto >= 0),
    fecha_creacion       timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT uq_pedido_detalle UNIQUE (pedido_id, producto_variante_id)
);
CREATE INDEX idx_pedido_detalle_variante ON pedido_detalle (producto_variante_id);
CREATE INDEX idx_pedido_detalle_sku ON pedido_detalle (sku);

CREATE TABLE historial_estado_pedido (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pedido_id        bigint      NOT NULL REFERENCES pedido (id)        ON DELETE CASCADE  ON UPDATE CASCADE,
    estado_pedido_id bigint      NOT NULL REFERENCES estado_pedido (id) ON DELETE RESTRICT ON UPDATE CASCADE,
    usuario_id       bigint      REFERENCES usuario (id)                ON DELETE SET NULL ON UPDATE CASCADE,
    comentario       text,
    fecha_creacion   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_historial_estado_pedido_pedido ON historial_estado_pedido (pedido_id);
CREATE INDEX idx_historial_estado_pedido_estado ON historial_estado_pedido (estado_pedido_id);

CREATE TABLE nota_pedido (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pedido_id           bigint      NOT NULL REFERENCES pedido (id) ON DELETE CASCADE  ON UPDATE CASCADE,
    usuario_id          bigint      REFERENCES usuario (id)         ON DELETE SET NULL ON UPDATE CASCADE,
    nota                text        NOT NULL,
    es_visible_cliente  boolean     NOT NULL DEFAULT false,
    fecha_creacion      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_nota_pedido_pedido ON nota_pedido (pedido_id);

CREATE TABLE motivo_devolucion (
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo             varchar(30)  NOT NULL UNIQUE,
    nombre             varchar(100) NOT NULL,
    requiere_evidencia boolean      NOT NULL DEFAULT false,
    activo             boolean      NOT NULL DEFAULT true,
    fecha_creacion     timestamptz  NOT NULL DEFAULT now()
);

-- monto_total mantenido por trigger (script 16) a partir del detalle.
CREATE TABLE devolucion (
    id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    numero               varchar(20)   NOT NULL UNIQUE,
    pedido_id            bigint        NOT NULL REFERENCES pedido (id)            ON DELETE RESTRICT ON UPDATE CASCADE,
    motivo_devolucion_id bigint        NOT NULL REFERENCES motivo_devolucion (id) ON DELETE RESTRICT ON UPDATE CASCADE,
    usuario_gestiona_id  bigint        REFERENCES usuario (id)                    ON DELETE SET NULL ON UPDATE CASCADE,
    estado               varchar(15)   NOT NULL DEFAULT 'solicitada'
                         CHECK (estado IN ('solicitada', 'aprobada', 'rechazada', 'recibida', 'reembolsada')),
    descripcion          text,
    monto_total          numeric(12,2) NOT NULL DEFAULT 0 CHECK (monto_total >= 0),
    fecha_creacion       timestamptz   NOT NULL DEFAULT now(),
    fecha_actualizacion  timestamptz
);
CREATE INDEX idx_devolucion_pedido ON devolucion (pedido_id);
CREATE INDEX idx_devolucion_motivo ON devolucion (motivo_devolucion_id);
CREATE INDEX idx_devolucion_estado ON devolucion (estado);

CREATE TABLE devolucion_detalle (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    devolucion_id     bigint      NOT NULL REFERENCES devolucion (id)     ON DELETE CASCADE  ON UPDATE CASCADE,
    pedido_detalle_id bigint      NOT NULL REFERENCES pedido_detalle (id) ON DELETE RESTRICT ON UPDATE CASCADE,
    cantidad          int         NOT NULL CHECK (cantidad > 0),
    estado_producto   varchar(15) CHECK (estado_producto IN ('nuevo', 'abierto', 'danado')),
    accion            varchar(15) NOT NULL DEFAULT 'reembolso'
                      CHECK (accion IN ('reembolso', 'cambio', 'credito')),
    fecha_creacion    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_devolucion_detalle UNIQUE (devolucion_id, pedido_detalle_id)
);
CREATE INDEX idx_devolucion_detalle_pedido_detalle ON devolucion_detalle (pedido_detalle_id);

-- FK diferida desde inventario: ahora que pedido existe, se enlaza la reserva.
ALTER TABLE reserva_stock
    ADD CONSTRAINT fk_reserva_stock_pedido
    FOREIGN KEY (pedido_id) REFERENCES pedido (id) ON DELETE CASCADE ON UPDATE CASCADE;

COMMIT;
