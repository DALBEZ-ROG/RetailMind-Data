-- ============================================================
-- RetailMind - Script 13: M10 - Marketing
-- Tablas: cupon, uso_cupon, promocion, promocion_producto,
--         campana, banner, newsletter_suscriptor
-- Depende de: 03 (cliente), 04 (producto), 10 (pedido)
-- ============================================================

BEGIN;

CREATE TABLE cupon (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo              varchar(50)   NOT NULL UNIQUE,
    descripcion         text,
    tipo_descuento      varchar(15)   NOT NULL
                        CHECK (tipo_descuento IN ('porcentaje', 'monto_fijo', 'envio_gratis')),
    valor               numeric(12,2) NOT NULL DEFAULT 0 CHECK (valor >= 0),
    monto_minimo_pedido numeric(12,2) NOT NULL DEFAULT 0 CHECK (monto_minimo_pedido >= 0),
    usos_maximos        int           CHECK (usos_maximos > 0),         -- NULL = ilimitado
    usos_por_cliente    int           NOT NULL DEFAULT 1 CHECK (usos_por_cliente > 0),
    usos_actuales       int           NOT NULL DEFAULT 0 CHECK (usos_actuales >= 0),
    fecha_inicio        timestamptz   NOT NULL,
    fecha_fin           timestamptz,
    activo              boolean       NOT NULL DEFAULT true,
    fecha_creacion      timestamptz   NOT NULL DEFAULT now(),
    fecha_actualizacion timestamptz,
    CONSTRAINT ck_cupon_vigencia CHECK (fecha_fin IS NULL OR fecha_fin > fecha_inicio),
    CONSTRAINT ck_cupon_porcentaje CHECK (tipo_descuento <> 'porcentaje' OR valor <= 100)
);
CREATE INDEX idx_cupon_vigencia ON cupon (fecha_inicio, fecha_fin);

-- El pedido no lleva cupon_id: la relacion cupon-pedido vive aqui
-- (evita FK circular y soporta auditoria de canje).
CREATE TABLE uso_cupon (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cupon_id         bigint        NOT NULL REFERENCES cupon (id)  ON DELETE RESTRICT ON UPDATE CASCADE,
    pedido_id        bigint        NOT NULL REFERENCES pedido (id) ON DELETE CASCADE  ON UPDATE CASCADE,
    cliente_id       bigint        REFERENCES cliente (id)         ON DELETE SET NULL ON UPDATE CASCADE,
    monto_descontado numeric(12,2) NOT NULL CHECK (monto_descontado >= 0),
    fecha_creacion   timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT uq_uso_cupon UNIQUE (cupon_id, pedido_id)
);
CREATE INDEX idx_uso_cupon_pedido ON uso_cupon (pedido_id);
CREATE INDEX idx_uso_cupon_cliente ON uso_cupon (cliente_id);

CREATE TABLE promocion (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nombre              varchar(150)  NOT NULL,
    descripcion         text,
    tipo_descuento      varchar(15)   NOT NULL
                        CHECK (tipo_descuento IN ('porcentaje', 'monto_fijo')),
    valor               numeric(12,2) NOT NULL CHECK (valor >= 0),
    fecha_inicio        timestamptz   NOT NULL,
    fecha_fin           timestamptz,
    prioridad           smallint      NOT NULL DEFAULT 0,
    acumulable          boolean       NOT NULL DEFAULT false,
    activo              boolean       NOT NULL DEFAULT true,
    fecha_creacion      timestamptz   NOT NULL DEFAULT now(),
    fecha_actualizacion timestamptz,
    CONSTRAINT ck_promocion_vigencia CHECK (fecha_fin IS NULL OR fecha_fin > fecha_inicio),
    CONSTRAINT ck_promocion_porcentaje CHECK (tipo_descuento <> 'porcentaje' OR valor <= 100)
);
CREATE INDEX idx_promocion_vigencia ON promocion (fecha_inicio, fecha_fin);

CREATE TABLE promocion_producto (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    promocion_id   bigint      NOT NULL REFERENCES promocion (id) ON DELETE CASCADE ON UPDATE CASCADE,
    producto_id    bigint      NOT NULL REFERENCES producto (id)  ON DELETE CASCADE ON UPDATE CASCADE,
    fecha_creacion timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_promocion_producto UNIQUE (promocion_id, producto_id)
);
CREATE INDEX idx_promocion_producto_producto ON promocion_producto (producto_id);

CREATE TABLE campana (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nombre              varchar(150)  NOT NULL,
    descripcion         text,
    canal               varchar(20)   NOT NULL DEFAULT 'web'
                        CHECK (canal IN ('email', 'redes', 'web', 'sms', 'mixto')),
    presupuesto         numeric(12,2) CHECK (presupuesto >= 0),
    estado              varchar(15)   NOT NULL DEFAULT 'borrador'
                        CHECK (estado IN ('borrador', 'activa', 'pausada', 'finalizada')),
    fecha_inicio        date,
    fecha_fin           date,
    fecha_creacion      timestamptz   NOT NULL DEFAULT now(),
    fecha_actualizacion timestamptz,
    CONSTRAINT ck_campana_vigencia CHECK (fecha_fin IS NULL OR fecha_inicio IS NULL
                                          OR fecha_fin >= fecha_inicio)
);
CREATE INDEX idx_campana_estado ON campana (estado);

CREATE TABLE banner (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    campana_id          bigint       REFERENCES campana (id) ON DELETE SET NULL ON UPDATE CASCADE,
    titulo              varchar(150) NOT NULL,
    imagen_url          varchar(500) NOT NULL,
    url_destino         varchar(500),
    posicion            varchar(50)  NOT NULL DEFAULT 'home_principal',
    orden               int          NOT NULL DEFAULT 0,
    fecha_inicio        timestamptz,
    fecha_fin           timestamptz,
    activo              boolean      NOT NULL DEFAULT true,
    fecha_creacion      timestamptz  NOT NULL DEFAULT now(),
    fecha_actualizacion timestamptz,
    CONSTRAINT ck_banner_vigencia CHECK (fecha_fin IS NULL OR fecha_inicio IS NULL
                                         OR fecha_fin > fecha_inicio)
);
CREATE INDEX idx_banner_campana ON banner (campana_id);
CREATE INDEX idx_banner_posicion ON banner (posicion);

CREATE TABLE newsletter_suscriptor (
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email              varchar(255) NOT NULL,
    cliente_id         bigint       REFERENCES cliente (id) ON DELETE SET NULL ON UPDATE CASCADE,
    confirmado         boolean      NOT NULL DEFAULT false,
    token_confirmacion varchar(255),
    fecha_suscripcion  timestamptz  NOT NULL DEFAULT now(),
    fecha_baja         timestamptz,
    activo             boolean      NOT NULL DEFAULT true,
    fecha_creacion     timestamptz  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_newsletter_suscriptor_email ON newsletter_suscriptor (lower(email));
CREATE INDEX idx_newsletter_suscriptor_cliente ON newsletter_suscriptor (cliente_id);

COMMIT;
