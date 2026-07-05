-- ============================================================================
-- 26_seed_demo_ventas.sql — RetailMind · Seed minimo del CICLO DE VENTA
--  Geografia minima (Ecuador > Los Rios > Quevedo), 2 clientes con usuario
--  LOGIN de app (password Cliente2026!, BCrypt) y direccion, 2 transportistas,
--  2 metodos de envio y motivos de devolucion.
--  Reusa productos/variantes/stock del script 25. Idempotente.
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- 1) Geografia minima
INSERT INTO pais (codigo_iso2, codigo_iso3, nombre)
SELECT 'EC','ECU','Ecuador'
WHERE NOT EXISTS (SELECT 1 FROM pais WHERE codigo_iso2='EC');

INSERT INTO provincia (pais_id, nombre)
SELECT p.id, 'Los Rios' FROM pais p WHERE p.codigo_iso2='EC'
  AND NOT EXISTS (SELECT 1 FROM provincia WHERE nombre='Los Rios');

INSERT INTO ciudad (provincia_id, nombre)
SELECT pr.id, v.nombre
FROM provincia pr, (VALUES ('Quevedo'), ('Babahoyo')) AS v(nombre)
WHERE pr.nombre='Los Rios'
  AND NOT EXISTS (SELECT 1 FROM ciudad c WHERE c.nombre=v.nombre);

-- 2) Usuarios de los clientes (rol CLIENTE) + ficha cliente + direccion
INSERT INTO usuario (email, password_hash, nombre, apellido, email_verificado, activo)
SELECT v.email, crypt('Cliente2026!', gen_salt('bf', 10)), v.nombre, v.apellido, true, true
FROM (VALUES
    ('maria.lopez@demo.com',  'Maria',  'Lopez'),
    ('carlos.vera@demo.com',  'Carlos', 'Vera')
) AS v(email, nombre, apellido)
WHERE NOT EXISTS (SELECT 1 FROM usuario u WHERE lower(u.email)=v.email);

INSERT INTO usuario_rol (usuario_id, rol_id)
SELECT u.id, r.id FROM usuario u, rol r
WHERE lower(u.email) IN ('maria.lopez@demo.com','carlos.vera@demo.com')
  AND r.codigo='CLIENTE'
ON CONFLICT (usuario_id, rol_id) DO NOTHING;

INSERT INTO cliente (usuario_id, tipo_identificacion, numero_identificacion,
                     nombre, apellido, email, telefono)
SELECT u.id, 'cedula', v.ident, v.nombre, v.apellido, v.email, v.telefono
FROM (VALUES
    ('maria.lopez@demo.com','1205551234','Maria','Lopez','0991112233'),
    ('carlos.vera@demo.com','0923456789','Carlos','Vera','0994445566')
) AS v(email, ident, nombre, apellido, telefono)
JOIN usuario u ON lower(u.email)=v.email
WHERE NOT EXISTS (SELECT 1 FROM cliente c WHERE lower(c.email)=v.email);

INSERT INTO direccion (usuario_id, ciudad_id, tipo, alias, destinatario,
                       calle_principal, referencia, es_predeterminada)
SELECT u.id, (SELECT id FROM ciudad WHERE nombre='Quevedo'), 'ambas', 'Casa',
       v.destinatario, v.calle, v.referencia, true
FROM (VALUES
    ('maria.lopez@demo.com','Maria Lopez','Av. 7 de Octubre 405','Frente al parque central'),
    ('carlos.vera@demo.com','Carlos Vera','Calle Bolivar 210','Junto a la ferreteria')
) AS v(email, destinatario, calle, referencia)
JOIN usuario u ON lower(u.email)=v.email
WHERE NOT EXISTS (SELECT 1 FROM direccion d WHERE d.usuario_id=u.id);

-- 3) Transportistas y metodos de envio
INSERT INTO transportista (nombre, ruc, telefono, sitio_web, url_seguimiento)
SELECT v.nombre, v.ruc, v.telefono, v.web, v.track
FROM (VALUES
    ('Servientrega','1790123456001','1700-737843','https://servientrega.com.ec','https://servientrega.com.ec/rastreo/{guia}'),
    ('Tramaco Express','0991234567001','04-3731100','https://tramaco.com.ec','https://tramaco.com.ec/tracking/{guia}')
) AS v(nombre, ruc, telefono, web, track)
WHERE NOT EXISTS (SELECT 1 FROM transportista t WHERE t.nombre=v.nombre);

INSERT INTO metodo_envio (codigo, nombre, descripcion, transportista_id,
                          dias_entrega_min, dias_entrega_max, orden)
SELECT v.codigo, v.nombre, v.descripcion,
       (SELECT id FROM transportista WHERE nombre=v.transportista),
       v.dmin, v.dmax, v.orden
FROM (VALUES
    ('EST','Envio estandar','Entrega 2 a 5 dias laborables','Servientrega',2,5,1),
    ('EXP','Envio express', 'Entrega 24-48 horas','Tramaco Express',1,2,2)
) AS v(codigo, nombre, descripcion, transportista, dmin, dmax, orden)
WHERE NOT EXISTS (SELECT 1 FROM metodo_envio me WHERE me.codigo=v.codigo);

-- 4) Motivos de devolucion
INSERT INTO motivo_devolucion (codigo, nombre, requiere_evidencia)
SELECT v.codigo, v.nombre, v.evidencia
FROM (VALUES
    ('talla_incorrecta','Talla o ajuste incorrecto', false),
    ('producto_danado', 'Producto danado o defectuoso', true),
    ('no_corresponde',  'No corresponde a lo pedido', false),
    ('arrepentimiento', 'Cambio de opinion del cliente', false)
) AS v(codigo, nombre, evidencia)
WHERE NOT EXISTS (SELECT 1 FROM motivo_devolucion m WHERE m.codigo=v.codigo);
