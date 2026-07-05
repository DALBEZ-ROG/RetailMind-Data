-- ============================================================
-- RetailMind - Script 14: M11 - Resenas y Contenido
-- Tablas: resena, resena_util, pregunta_producto, respuesta_pregunta,
--         reporte_resena
-- Depende de: 02 (usuario), 03 (cliente), 04 (producto), 10 (pedido)
-- ============================================================

BEGIN;

-- Una resena por cliente por producto. pedido_id opcional acredita
-- compra_verificada.
CREATE TABLE resena (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    producto_id         bigint       NOT NULL REFERENCES producto (id) ON DELETE CASCADE  ON UPDATE CASCADE,
    cliente_id          bigint       NOT NULL REFERENCES cliente (id)  ON DELETE CASCADE  ON UPDATE CASCADE,
    pedido_id           bigint       REFERENCES pedido (id)            ON DELETE SET NULL ON UPDATE CASCADE,
    calificacion        smallint     NOT NULL CHECK (calificacion BETWEEN 1 AND 5),
    titulo              varchar(150),
    comentario          text,
    compra_verificada   boolean      NOT NULL DEFAULT false,
    estado              varchar(15)  NOT NULL DEFAULT 'pendiente'
                        CHECK (estado IN ('pendiente', 'aprobada', 'rechazada')),
    fecha_creacion      timestamptz  NOT NULL DEFAULT now(),
    fecha_actualizacion timestamptz,
    CONSTRAINT uq_resena_producto_cliente UNIQUE (producto_id, cliente_id)
);
CREATE INDEX idx_resena_cliente ON resena (cliente_id);
CREATE INDEX idx_resena_pedido ON resena (pedido_id);
CREATE INDEX idx_resena_estado ON resena (estado);

-- Voto util/no util: un voto por cliente por resena.
CREATE TABLE resena_util (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    resena_id       bigint      NOT NULL REFERENCES resena (id)  ON DELETE CASCADE ON UPDATE CASCADE,
    cliente_id      bigint      NOT NULL REFERENCES cliente (id) ON DELETE CASCADE ON UPDATE CASCADE,
    es_util         boolean     NOT NULL,
    fecha_creacion  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_resena_util UNIQUE (resena_id, cliente_id)
);
CREATE INDEX idx_resena_util_cliente ON resena_util (cliente_id);

CREATE TABLE pregunta_producto (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    producto_id         bigint       NOT NULL REFERENCES producto (id) ON DELETE CASCADE  ON UPDATE CASCADE,
    cliente_id          bigint       REFERENCES cliente (id)           ON DELETE SET NULL ON UPDATE CASCADE,
    pregunta            text         NOT NULL,
    estado              varchar(15)  NOT NULL DEFAULT 'pendiente'
                        CHECK (estado IN ('pendiente', 'publicada', 'rechazada')),
    fecha_creacion      timestamptz  NOT NULL DEFAULT now(),
    fecha_actualizacion timestamptz
);
CREATE INDEX idx_pregunta_producto_producto ON pregunta_producto (producto_id);
CREATE INDEX idx_pregunta_producto_cliente ON pregunta_producto (cliente_id);
CREATE INDEX idx_pregunta_producto_estado ON pregunta_producto (estado);

-- Puede responder personal interno (usuario_id) u otro cliente (cliente_id);
-- exactamente uno de los dos.
CREATE TABLE respuesta_pregunta (
    id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pregunta_producto_id bigint      NOT NULL REFERENCES pregunta_producto (id) ON DELETE CASCADE  ON UPDATE CASCADE,
    usuario_id           bigint      REFERENCES usuario (id)                    ON DELETE SET NULL ON UPDATE CASCADE,
    cliente_id           bigint      REFERENCES cliente (id)                    ON DELETE SET NULL ON UPDATE CASCADE,
    respuesta            text        NOT NULL,
    es_oficial           boolean     NOT NULL DEFAULT false,
    fecha_creacion       timestamptz NOT NULL DEFAULT now(),
    fecha_actualizacion  timestamptz
);
CREATE INDEX idx_respuesta_pregunta_pregunta ON respuesta_pregunta (pregunta_producto_id);
CREATE INDEX idx_respuesta_pregunta_usuario ON respuesta_pregunta (usuario_id);
CREATE INDEX idx_respuesta_pregunta_cliente ON respuesta_pregunta (cliente_id);

CREATE TABLE reporte_resena (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    resena_id       bigint      NOT NULL REFERENCES resena (id) ON DELETE CASCADE  ON UPDATE CASCADE,
    cliente_id      bigint      REFERENCES cliente (id)         ON DELETE SET NULL ON UPDATE CASCADE,
    motivo          varchar(15) NOT NULL
                    CHECK (motivo IN ('ofensivo', 'spam', 'falso', 'otro')),
    comentario      text,
    estado          varchar(15) NOT NULL DEFAULT 'pendiente'
                    CHECK (estado IN ('pendiente', 'atendido', 'descartado')),
    fecha_creacion  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_reporte_resena_resena ON reporte_resena (resena_id);
CREATE INDEX idx_reporte_resena_cliente ON reporte_resena (cliente_id);
CREATE INDEX idx_reporte_resena_estado ON reporte_resena (estado);

COMMIT;
