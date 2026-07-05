-- ============================================================
-- RetailMind - Script 08: M6 - Carrito, Wishlist y Comparacion
-- Tablas: carrito, carrito_item, wishlist, wishlist_item,
--         comparacion, comparacion_item
-- Depende de: 03 (cliente), 04 (producto, producto_variante), 06 (reserva_stock)
-- ============================================================

BEGIN;

-- cliente_id nullable + sesion_token: soporta carritos de visitantes anonimos.
CREATE TABLE carrito (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cliente_id          bigint       REFERENCES cliente (id) ON DELETE SET NULL ON UPDATE CASCADE,
    sesion_token        varchar(100),
    estado              varchar(15)  NOT NULL DEFAULT 'activo'
                        CHECK (estado IN ('activo', 'convertido', 'abandonado')),
    fecha_creacion      timestamptz  NOT NULL DEFAULT now(),
    fecha_actualizacion timestamptz,
    CONSTRAINT ck_carrito_propietario CHECK (cliente_id IS NOT NULL OR sesion_token IS NOT NULL)
);
CREATE INDEX idx_carrito_cliente ON carrito (cliente_id);
CREATE INDEX idx_carrito_sesion ON carrito (sesion_token);
CREATE INDEX idx_carrito_estado ON carrito (estado);

CREATE TABLE carrito_item (
    id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    carrito_id           bigint        NOT NULL REFERENCES carrito (id)           ON DELETE CASCADE  ON UPDATE CASCADE,
    producto_variante_id bigint        NOT NULL REFERENCES producto_variante (id) ON DELETE CASCADE  ON UPDATE CASCADE,
    cantidad             int           NOT NULL CHECK (cantidad > 0),
    precio_unitario      numeric(12,2) NOT NULL CHECK (precio_unitario >= 0),  -- precio al momento de agregar
    fecha_creacion       timestamptz   NOT NULL DEFAULT now(),
    fecha_actualizacion  timestamptz,
    CONSTRAINT uq_carrito_item UNIQUE (carrito_id, producto_variante_id)
);
CREATE INDEX idx_carrito_item_variante ON carrito_item (producto_variante_id);

CREATE TABLE wishlist (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cliente_id          bigint       NOT NULL REFERENCES cliente (id) ON DELETE CASCADE ON UPDATE CASCADE,
    nombre              varchar(100) NOT NULL DEFAULT 'Mi lista',
    es_publica          boolean      NOT NULL DEFAULT false,
    fecha_creacion      timestamptz  NOT NULL DEFAULT now(),
    fecha_actualizacion timestamptz,
    CONSTRAINT uq_wishlist UNIQUE (cliente_id, nombre)
);

CREATE TABLE wishlist_item (
    id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    wishlist_id          bigint      NOT NULL REFERENCES wishlist (id)          ON DELETE CASCADE ON UPDATE CASCADE,
    producto_variante_id bigint      NOT NULL REFERENCES producto_variante (id) ON DELETE CASCADE ON UPDATE CASCADE,
    fecha_creacion       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_wishlist_item UNIQUE (wishlist_id, producto_variante_id)
);
CREATE INDEX idx_wishlist_item_variante ON wishlist_item (producto_variante_id);

CREATE TABLE comparacion (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cliente_id      bigint       REFERENCES cliente (id) ON DELETE CASCADE ON UPDATE CASCADE,
    sesion_token    varchar(100),
    fecha_creacion  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_comparacion_propietario CHECK (cliente_id IS NOT NULL OR sesion_token IS NOT NULL)
);
CREATE INDEX idx_comparacion_cliente ON comparacion (cliente_id);

CREATE TABLE comparacion_item (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    comparacion_id  bigint      NOT NULL REFERENCES comparacion (id) ON DELETE CASCADE ON UPDATE CASCADE,
    producto_id     bigint      NOT NULL REFERENCES producto (id)    ON DELETE CASCADE ON UPDATE CASCADE,
    fecha_creacion  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_comparacion_item UNIQUE (comparacion_id, producto_id)
);
CREATE INDEX idx_comparacion_item_producto ON comparacion_item (producto_id);

-- FK diferida desde inventario: ahora que carrito existe, se enlaza la reserva.
ALTER TABLE reserva_stock
    ADD CONSTRAINT fk_reserva_stock_carrito
    FOREIGN KEY (carrito_id) REFERENCES carrito (id) ON DELETE CASCADE ON UPDATE CASCADE;

COMMIT;
