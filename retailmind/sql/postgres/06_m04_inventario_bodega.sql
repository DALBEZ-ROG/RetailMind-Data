-- ============================================================
-- RetailMind - Script 06: M4 - Inventario y Bodega
-- Tablas: bodega, ubicacion_bodega, tipo_movimiento, lote, inventario,
--         movimiento_inventario (kardex), ajuste_inventario,
--         transferencia_bodega, reserva_stock
-- Depende de: 01 (ciudad), 02 (usuario), 04 (producto_variante)
-- Nota: reserva_stock.pedido_id y carrito_id reciben su FK por ALTER
--       en los scripts 08 y 10 (las tablas destino aun no existen).
-- ============================================================

BEGIN;

CREATE TABLE bodega (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo              varchar(20)  NOT NULL UNIQUE,
    nombre              varchar(100) NOT NULL,
    ciudad_id           bigint       REFERENCES ciudad (id)  ON DELETE RESTRICT ON UPDATE CASCADE,
    responsable_usuario_id bigint    REFERENCES usuario (id) ON DELETE SET NULL ON UPDATE CASCADE,
    direccion           varchar(255),
    telefono            varchar(20),
    es_principal        boolean      NOT NULL DEFAULT false,
    activo              boolean      NOT NULL DEFAULT true,
    fecha_creacion      timestamptz  NOT NULL DEFAULT now(),
    fecha_actualizacion timestamptz
);
CREATE INDEX idx_bodega_ciudad ON bodega (ciudad_id);
CREATE INDEX idx_bodega_responsable ON bodega (responsable_usuario_id);

CREATE TABLE ubicacion_bodega (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    bodega_id       bigint      NOT NULL REFERENCES bodega (id) ON DELETE CASCADE ON UPDATE CASCADE,
    codigo          varchar(30) NOT NULL,       -- ej. 'A-01-N2'
    pasillo         varchar(10),
    estante         varchar(10),
    nivel           varchar(10),
    descripcion     varchar(200),
    activo          boolean     NOT NULL DEFAULT true,
    fecha_creacion  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_ubicacion_bodega UNIQUE (bodega_id, codigo)
);

-- Catalogo de tipos de movimiento del kardex.
-- factor: +1 suma stock, -1 resta stock.
CREATE TABLE tipo_movimiento (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo          varchar(40)  NOT NULL UNIQUE,
    nombre          varchar(100) NOT NULL,
    naturaleza      varchar(15)  NOT NULL
                    CHECK (naturaleza IN ('entrada', 'salida', 'ajuste', 'transferencia')),
    factor          smallint     NOT NULL CHECK (factor IN (-1, 1)),
    descripcion     text,
    activo          boolean      NOT NULL DEFAULT true,
    fecha_creacion  timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE lote (
    id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    producto_variante_id bigint      NOT NULL REFERENCES producto_variante (id) ON DELETE RESTRICT ON UPDATE CASCADE,
    codigo               varchar(50) NOT NULL,
    fecha_fabricacion    date,
    fecha_vencimiento    date,
    fecha_creacion       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_lote UNIQUE (producto_variante_id, codigo),
    CONSTRAINT ck_lote_fechas CHECK (fecha_vencimiento IS NULL OR fecha_fabricacion IS NULL
                                     OR fecha_vencimiento > fecha_fabricacion)
);

-- Existencias por (variante, bodega): multi-bodega real.
CREATE TABLE inventario (
    id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    producto_variante_id bigint      NOT NULL REFERENCES producto_variante (id) ON DELETE RESTRICT ON UPDATE CASCADE,
    bodega_id            bigint      NOT NULL REFERENCES bodega (id)            ON DELETE RESTRICT ON UPDATE CASCADE,
    ubicacion_bodega_id  bigint      REFERENCES ubicacion_bodega (id)           ON DELETE SET NULL ON UPDATE CASCADE,
    stock_actual         int         NOT NULL DEFAULT 0 CHECK (stock_actual >= 0),
    stock_reservado      int         NOT NULL DEFAULT 0 CHECK (stock_reservado >= 0),
    stock_minimo         int         NOT NULL DEFAULT 0 CHECK (stock_minimo >= 0),
    stock_maximo         int         CHECK (stock_maximo IS NULL OR stock_maximo >= stock_minimo),
    fecha_creacion       timestamptz NOT NULL DEFAULT now(),
    fecha_actualizacion  timestamptz,
    CONSTRAINT uq_inventario UNIQUE (producto_variante_id, bodega_id),
    CONSTRAINT ck_inventario_reserva CHECK (stock_reservado <= stock_actual)
);
CREATE INDEX idx_inventario_bodega ON inventario (bodega_id);
CREATE INDEX idx_inventario_ubicacion ON inventario (ubicacion_bodega_id);

-- Kardex: registro inmutable de cada movimiento de stock.
-- (referencia_tipo, referencia_id) apunta polimorficamente al documento origen
-- (orden_compra, pedido, ajuste_inventario, transferencia_bodega, devolucion).
CREATE TABLE movimiento_inventario (
    id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    producto_variante_id bigint        NOT NULL REFERENCES producto_variante (id) ON DELETE RESTRICT ON UPDATE CASCADE,
    bodega_id            bigint        NOT NULL REFERENCES bodega (id)            ON DELETE RESTRICT ON UPDATE CASCADE,
    tipo_movimiento_id   bigint        NOT NULL REFERENCES tipo_movimiento (id)   ON DELETE RESTRICT ON UPDATE CASCADE,
    lote_id              bigint        REFERENCES lote (id)                       ON DELETE SET NULL ON UPDATE CASCADE,
    usuario_id           bigint        REFERENCES usuario (id)                    ON DELETE SET NULL ON UPDATE CASCADE,
    cantidad             int           NOT NULL CHECK (cantidad > 0),
    stock_anterior       int           NOT NULL CHECK (stock_anterior >= 0),
    stock_nuevo          int           NOT NULL CHECK (stock_nuevo >= 0),
    costo_unitario       numeric(12,2) CHECK (costo_unitario >= 0),
    referencia_tipo      varchar(40),
    referencia_id        bigint,
    observacion          text,
    fecha_creacion       timestamptz   NOT NULL DEFAULT now()
);
CREATE INDEX idx_movimiento_inventario_variante ON movimiento_inventario (producto_variante_id);
CREATE INDEX idx_movimiento_inventario_bodega ON movimiento_inventario (bodega_id);
CREATE INDEX idx_movimiento_inventario_tipo ON movimiento_inventario (tipo_movimiento_id);
CREATE INDEX idx_movimiento_inventario_lote ON movimiento_inventario (lote_id);
CREATE INDEX idx_movimiento_inventario_fecha ON movimiento_inventario (fecha_creacion);
CREATE INDEX idx_movimiento_inventario_referencia ON movimiento_inventario (referencia_tipo, referencia_id);

-- Cabecera de ajustes (conteos fisicos, mermas). Los movimientos del kardex
-- referencian este documento via (referencia_tipo='ajuste_inventario', referencia_id).
CREATE TABLE ajuste_inventario (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    bodega_id           bigint      NOT NULL REFERENCES bodega (id)  ON DELETE RESTRICT ON UPDATE CASCADE,
    usuario_id          bigint      REFERENCES usuario (id)          ON DELETE SET NULL ON UPDATE CASCADE,
    tipo                varchar(15) NOT NULL
                        CHECK (tipo IN ('positivo', 'negativo', 'conteo')),
    estado              varchar(15) NOT NULL DEFAULT 'borrador'
                        CHECK (estado IN ('borrador', 'aplicado', 'anulado')),
    motivo              text        NOT NULL,
    fecha_aplicacion    timestamptz,
    fecha_creacion      timestamptz NOT NULL DEFAULT now(),
    fecha_actualizacion timestamptz
);
CREATE INDEX idx_ajuste_inventario_bodega ON ajuste_inventario (bodega_id);
CREATE INDEX idx_ajuste_inventario_estado ON ajuste_inventario (estado);

CREATE TABLE transferencia_bodega (
    id                    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    bodega_origen_id      bigint      NOT NULL REFERENCES bodega (id) ON DELETE RESTRICT ON UPDATE CASCADE,
    bodega_destino_id     bigint      NOT NULL REFERENCES bodega (id) ON DELETE RESTRICT ON UPDATE CASCADE,
    usuario_solicita_id   bigint      REFERENCES usuario (id)         ON DELETE SET NULL ON UPDATE CASCADE,
    estado                varchar(15) NOT NULL DEFAULT 'pendiente'
                          CHECK (estado IN ('pendiente', 'en_transito', 'recibida', 'cancelada')),
    fecha_envio           timestamptz,
    fecha_recepcion       timestamptz,
    observacion           text,
    fecha_creacion        timestamptz NOT NULL DEFAULT now(),
    fecha_actualizacion   timestamptz,
    CONSTRAINT ck_transferencia_bodegas_distintas CHECK (bodega_origen_id <> bodega_destino_id)
);
CREATE INDEX idx_transferencia_bodega_origen ON transferencia_bodega (bodega_origen_id);
CREATE INDEX idx_transferencia_bodega_destino ON transferencia_bodega (bodega_destino_id);
CREATE INDEX idx_transferencia_bodega_estado ON transferencia_bodega (estado);

-- Reservas de stock por carrito o pedido (evita sobreventa).
-- FKs de carrito_id y pedido_id se agregan por ALTER en scripts 08 y 10.
CREATE TABLE reserva_stock (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    inventario_id   bigint      NOT NULL REFERENCES inventario (id) ON DELETE CASCADE ON UPDATE CASCADE,
    carrito_id      bigint,
    pedido_id       bigint,
    cantidad        int         NOT NULL CHECK (cantidad > 0),
    origen          varchar(15) NOT NULL CHECK (origen IN ('carrito', 'pedido')),
    estado          varchar(15) NOT NULL DEFAULT 'activa'
                    CHECK (estado IN ('activa', 'liberada', 'consumida')),
    expira_en       timestamptz,
    fecha_creacion  timestamptz NOT NULL DEFAULT now(),
    fecha_actualizacion timestamptz,
    CONSTRAINT ck_reserva_stock_origen CHECK (
        (origen = 'carrito' AND carrito_id IS NOT NULL) OR
        (origen = 'pedido'  AND pedido_id  IS NOT NULL)
    )
);
CREATE INDEX idx_reserva_stock_inventario ON reserva_stock (inventario_id);
CREATE INDEX idx_reserva_stock_carrito ON reserva_stock (carrito_id);
CREATE INDEX idx_reserva_stock_pedido ON reserva_stock (pedido_id);
CREATE INDEX idx_reserva_stock_estado ON reserva_stock (estado);

COMMIT;
