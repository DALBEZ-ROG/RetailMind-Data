-- ============================================================
-- RetailMind - Script 15: M12 - Soporte
-- Tablas: categoria_ticket, ticket_soporte, mensaje_ticket, faq
-- Depende de: 02 (usuario), 03 (cliente), 10 (pedido)
-- ============================================================

BEGIN;

CREATE TABLE categoria_ticket (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nombre          varchar(100) NOT NULL UNIQUE,
    descripcion     text,
    activo          boolean      NOT NULL DEFAULT true,
    fecha_creacion  timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE ticket_soporte (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    numero              varchar(20)  NOT NULL UNIQUE,
    cliente_id          bigint       NOT NULL REFERENCES cliente (id) ON DELETE RESTRICT ON UPDATE CASCADE,
    categoria_ticket_id bigint       REFERENCES categoria_ticket (id) ON DELETE SET NULL ON UPDATE CASCADE,
    pedido_id           bigint       REFERENCES pedido (id)           ON DELETE SET NULL ON UPDATE CASCADE,
    asignado_usuario_id bigint       REFERENCES usuario (id)          ON DELETE SET NULL ON UPDATE CASCADE,
    asunto              varchar(200) NOT NULL,
    descripcion         text,
    prioridad           varchar(10)  NOT NULL DEFAULT 'media'
                        CHECK (prioridad IN ('baja', 'media', 'alta', 'urgente')),
    estado              varchar(20)  NOT NULL DEFAULT 'abierto'
                        CHECK (estado IN ('abierto', 'en_proceso', 'esperando_cliente',
                                          'resuelto', 'cerrado')),
    fecha_cierre        timestamptz,
    fecha_creacion      timestamptz  NOT NULL DEFAULT now(),
    fecha_actualizacion timestamptz
);
CREATE INDEX idx_ticket_soporte_cliente ON ticket_soporte (cliente_id);
CREATE INDEX idx_ticket_soporte_categoria ON ticket_soporte (categoria_ticket_id);
CREATE INDEX idx_ticket_soporte_pedido ON ticket_soporte (pedido_id);
CREATE INDEX idx_ticket_soporte_asignado ON ticket_soporte (asignado_usuario_id);
CREATE INDEX idx_ticket_soporte_estado ON ticket_soporte (estado);

-- Autor: personal interno (usuario_id) o el cliente (cliente_id);
-- exactamente uno de los dos.
CREATE TABLE mensaje_ticket (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticket_soporte_id bigint      NOT NULL REFERENCES ticket_soporte (id) ON DELETE CASCADE  ON UPDATE CASCADE,
    usuario_id        bigint      REFERENCES usuario (id)                 ON DELETE SET NULL ON UPDATE CASCADE,
    cliente_id        bigint      REFERENCES cliente (id)                 ON DELETE SET NULL ON UPDATE CASCADE,
    mensaje           text        NOT NULL,
    es_interno        boolean     NOT NULL DEFAULT false,   -- nota interna no visible al cliente
    fecha_creacion    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_mensaje_ticket_ticket ON mensaje_ticket (ticket_soporte_id);
CREATE INDEX idx_mensaje_ticket_usuario ON mensaje_ticket (usuario_id);
CREATE INDEX idx_mensaje_ticket_cliente ON mensaje_ticket (cliente_id);

CREATE TABLE faq (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    categoria_ticket_id bigint       REFERENCES categoria_ticket (id) ON DELETE SET NULL ON UPDATE CASCADE,
    pregunta            varchar(300) NOT NULL,
    respuesta           text         NOT NULL,
    orden               int          NOT NULL DEFAULT 0,
    activo              boolean      NOT NULL DEFAULT true,
    fecha_creacion      timestamptz  NOT NULL DEFAULT now(),
    fecha_actualizacion timestamptz
);
CREATE INDEX idx_faq_categoria ON faq (categoria_ticket_id);

COMMIT;
