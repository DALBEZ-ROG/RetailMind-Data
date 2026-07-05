-- ============================================================
-- RetailMind - Script 02: M1 - Seguridad y Usuarios
-- Tablas: rol, permiso, rol_permiso, usuario, usuario_rol, direccion,
--         token_recuperacion, refresh_token, log_auditoria, log_acceso
-- Depende de: 01 (ciudad)
-- ============================================================

BEGIN;

CREATE TABLE rol (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo              varchar(50)  NOT NULL UNIQUE,   -- ej. 'ADMIN', 'VENDEDOR'
    nombre              varchar(100) NOT NULL UNIQUE,
    descripcion         text,
    es_sistema          boolean      NOT NULL DEFAULT false,  -- roles base no eliminables
    activo              boolean      NOT NULL DEFAULT true,
    fecha_creacion      timestamptz  NOT NULL DEFAULT now(),
    fecha_actualizacion timestamptz
);

CREATE TABLE permiso (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo          varchar(100) NOT NULL UNIQUE,   -- ej. 'producto.crear'
    nombre          varchar(150) NOT NULL,
    modulo          varchar(50)  NOT NULL,          -- agrupador: 'catalogo', 'ventas', ...
    descripcion     text,
    activo          boolean      NOT NULL DEFAULT true,
    fecha_creacion  timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_permiso_modulo ON permiso (modulo);

CREATE TABLE rol_permiso (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rol_id          bigint      NOT NULL REFERENCES rol (id)     ON DELETE CASCADE ON UPDATE CASCADE,
    permiso_id      bigint      NOT NULL REFERENCES permiso (id) ON DELETE CASCADE ON UPDATE CASCADE,
    fecha_creacion  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_rol_permiso UNIQUE (rol_id, permiso_id)
);
CREATE INDEX idx_rol_permiso_permiso ON rol_permiso (permiso_id);

CREATE TABLE usuario (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email               varchar(255) NOT NULL,
    password_hash       varchar(255) NOT NULL,
    nombre              varchar(100) NOT NULL,
    apellido            varchar(100),
    telefono            varchar(20),
    email_verificado    boolean      NOT NULL DEFAULT false,
    ultimo_acceso       timestamptz,
    intentos_fallidos   smallint     NOT NULL DEFAULT 0 CHECK (intentos_fallidos >= 0),
    bloqueado_hasta     timestamptz,
    activo              boolean      NOT NULL DEFAULT true,
    fecha_creacion      timestamptz  NOT NULL DEFAULT now(),
    fecha_actualizacion timestamptz
);
-- Unicidad de email sin sensibilidad a mayusculas
CREATE UNIQUE INDEX uq_usuario_email ON usuario (lower(email));

CREATE TABLE usuario_rol (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    usuario_id      bigint      NOT NULL REFERENCES usuario (id) ON DELETE CASCADE  ON UPDATE CASCADE,
    rol_id          bigint      NOT NULL REFERENCES rol (id)     ON DELETE RESTRICT ON UPDATE CASCADE,
    fecha_creacion  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_usuario_rol UNIQUE (usuario_id, rol_id)
);
CREATE INDEX idx_usuario_rol_rol ON usuario_rol (rol_id);

CREATE TABLE direccion (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    usuario_id          bigint       NOT NULL REFERENCES usuario (id) ON DELETE CASCADE  ON UPDATE CASCADE,
    ciudad_id           bigint       NOT NULL REFERENCES ciudad (id)  ON DELETE RESTRICT ON UPDATE CASCADE,
    tipo                varchar(15)  NOT NULL DEFAULT 'envio'
                        CHECK (tipo IN ('envio', 'facturacion', 'ambas')),
    alias               varchar(50),                 -- 'Casa', 'Oficina'
    destinatario        varchar(150) NOT NULL,
    calle_principal     varchar(150) NOT NULL,
    calle_secundaria    varchar(150),
    numero              varchar(20),
    referencia          text,
    codigo_postal       varchar(20),
    telefono            varchar(20),
    es_predeterminada   boolean      NOT NULL DEFAULT false,
    activo              boolean      NOT NULL DEFAULT true,
    fecha_creacion      timestamptz  NOT NULL DEFAULT now(),
    fecha_actualizacion timestamptz
);
CREATE INDEX idx_direccion_usuario ON direccion (usuario_id);
CREATE INDEX idx_direccion_ciudad ON direccion (ciudad_id);

CREATE TABLE token_recuperacion (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    usuario_id      bigint       NOT NULL REFERENCES usuario (id) ON DELETE CASCADE ON UPDATE CASCADE,
    token           varchar(255) NOT NULL UNIQUE,
    expira_en       timestamptz  NOT NULL,
    usado           boolean      NOT NULL DEFAULT false,
    usado_en        timestamptz,
    fecha_creacion  timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_token_recuperacion_usuario ON token_recuperacion (usuario_id);

CREATE TABLE refresh_token (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    usuario_id      bigint       NOT NULL REFERENCES usuario (id) ON DELETE CASCADE ON UPDATE CASCADE,
    token           varchar(500) NOT NULL UNIQUE,
    expira_en       timestamptz  NOT NULL,
    revocado        boolean      NOT NULL DEFAULT false,
    revocado_en     timestamptz,
    ip_origen       inet,
    user_agent      text,
    fecha_creacion  timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_refresh_token_usuario ON refresh_token (usuario_id);
CREATE INDEX idx_refresh_token_expira ON refresh_token (expira_en);

-- Auditoria generica de cambios (datos antes/despues en jsonb)
CREATE TABLE log_auditoria (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    usuario_id          bigint      REFERENCES usuario (id) ON DELETE SET NULL ON UPDATE CASCADE,
    tabla               varchar(63) NOT NULL,
    registro_id         bigint,
    accion              varchar(20) NOT NULL
                        CHECK (accion IN ('INSERT', 'UPDATE', 'DELETE', 'LOGIN', 'LOGOUT', 'OTRO')),
    datos_anteriores    jsonb,
    datos_nuevos        jsonb,
    ip_origen           inet,
    fecha_creacion      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_log_auditoria_usuario ON log_auditoria (usuario_id);
CREATE INDEX idx_log_auditoria_tabla_registro ON log_auditoria (tabla, registro_id);
CREATE INDEX idx_log_auditoria_fecha ON log_auditoria (fecha_creacion);

CREATE TABLE log_acceso (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    usuario_id      bigint       REFERENCES usuario (id) ON DELETE SET NULL ON UPDATE CASCADE,
    email_intentado varchar(255),
    exitoso         boolean      NOT NULL,
    motivo_fallo    varchar(100),          -- 'password_incorrecto', 'usuario_bloqueado', ...
    ip_origen       inet,
    user_agent      text,
    fecha_creacion  timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_log_acceso_usuario ON log_acceso (usuario_id);
CREATE INDEX idx_log_acceso_fecha ON log_acceso (fecha_creacion);

COMMIT;
