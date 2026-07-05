-- ============================================================
-- RetailMind - Script 12: M9 (parte B) - Envios y seguimiento
-- Tablas: envio, envio_detalle, seguimiento_envio
-- Depende de: 06 (bodega), 09 (transportista, metodo_envio),
--             10 (pedido, pedido_detalle)
-- ============================================================

BEGIN;

-- direccion_entrega es snapshot en texto: el envio conserva la direccion
-- aunque el cliente la modifique o elimine despues.
CREATE TABLE envio (
    id                     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    numero                 varchar(20)   NOT NULL UNIQUE,
    pedido_id              bigint        NOT NULL REFERENCES pedido (id) ON DELETE RESTRICT ON UPDATE CASCADE,
    transportista_id       bigint        REFERENCES transportista (id)   ON DELETE SET NULL ON UPDATE CASCADE,
    metodo_envio_id        bigint        REFERENCES metodo_envio (id)    ON DELETE SET NULL ON UPDATE CASCADE,
    bodega_id              bigint        REFERENCES bodega (id)          ON DELETE SET NULL ON UPDATE CASCADE,  -- bodega origen
    direccion_entrega      text          NOT NULL,
    numero_guia            varchar(60),
    estado                 varchar(15)   NOT NULL DEFAULT 'preparando'
                           CHECK (estado IN ('preparando', 'listo', 'en_transito',
                                             'entregado', 'fallido', 'devuelto')),
    costo                  numeric(12,2) NOT NULL DEFAULT 0 CHECK (costo >= 0),
    peso_total_kg          numeric(8,3)  CHECK (peso_total_kg >= 0),
    fecha_despacho         timestamptz,
    fecha_entrega_estimada date,
    fecha_entrega_real     timestamptz,
    fecha_creacion         timestamptz   NOT NULL DEFAULT now(),
    fecha_actualizacion    timestamptz
);
CREATE INDEX idx_envio_pedido ON envio (pedido_id);
CREATE INDEX idx_envio_transportista ON envio (transportista_id);
CREATE INDEX idx_envio_metodo ON envio (metodo_envio_id);
CREATE INDEX idx_envio_bodega ON envio (bodega_id);
CREATE INDEX idx_envio_estado ON envio (estado);
CREATE INDEX idx_envio_guia ON envio (numero_guia);

-- Permite envios parciales: que lineas del pedido y cuantas unidades
-- van en cada paquete.
CREATE TABLE envio_detalle (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    envio_id          bigint      NOT NULL REFERENCES envio (id)          ON DELETE CASCADE  ON UPDATE CASCADE,
    pedido_detalle_id bigint      NOT NULL REFERENCES pedido_detalle (id) ON DELETE RESTRICT ON UPDATE CASCADE,
    cantidad          int         NOT NULL CHECK (cantidad > 0),
    fecha_creacion    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_envio_detalle UNIQUE (envio_id, pedido_detalle_id)
);
CREATE INDEX idx_envio_detalle_pedido_detalle ON envio_detalle (pedido_detalle_id);

CREATE TABLE seguimiento_envio (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    envio_id        bigint       NOT NULL REFERENCES envio (id) ON DELETE CASCADE ON UPDATE CASCADE,
    estado          varchar(50)  NOT NULL,          -- estado reportado por el transportista
    descripcion     text,
    ubicacion       varchar(150),
    fecha_evento    timestamptz  NOT NULL DEFAULT now(),
    fecha_creacion  timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_seguimiento_envio_envio ON seguimiento_envio (envio_id);
CREATE INDEX idx_seguimiento_envio_fecha ON seguimiento_envio (fecha_evento);

COMMIT;
