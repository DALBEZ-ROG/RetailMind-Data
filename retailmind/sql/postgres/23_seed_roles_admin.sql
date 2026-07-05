-- ============================================================================
-- 23_seed_roles_admin.sql — RetailMind · Roles de negocio + admin de prueba
--  - Siembra los 8 roles de aplicacion en la tabla rol. El codigo del rol es
--    la clave que el backend mapea (lista blanca en Java) a su rol de grupo
--    de PostgreSQL; la descripcion documenta ese mapeo.
--  - Crea el usuario administrador de prueba:
--        email:    admin@retailmind.com
--        password: Admin2026!   (hash BCrypt via pgcrypto, compatible Spring)
--  Idempotente: ON CONFLICT / WHERE NOT EXISTS.
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

INSERT INTO rol (codigo, nombre, descripcion, es_sistema, activo) VALUES
 ('ADMIN',    'Administrador',         'Acceso total. Rol de motor: grp_administrador (exento de horario y RLS).', true, true),
 ('GERENTE',  'Gerente',               'Lectura total y aprobaciones. Rol de motor: grp_gerente.',                 true, true),
 ('VENDEDOR', 'Vendedor',              'Ciclo de venta. Rol de motor: grp_vendedor.',                              true, true),
 ('COMPRAS',  'Encargado de Compras',  'Ciclo de abastecimiento. Rol de motor: grp_compras.',                      true, true),
 ('BODEGA',   'Encargado de Bodega',   'Inventario fisico. Rol de motor: grp_bodega.',                             true, true),
 ('DESPACHO', 'Encargado de Despacho', 'Envios y seguimiento. Rol de motor: grp_despacho.',                        true, true),
 ('CLIENTE',  'Cliente',               'Tienda en linea, solo sus filas (RLS). Rol de motor: grp_cliente.',        true, true),
 ('ANALISTA', 'Analista de Datos',     'Solo lectura de negocio. Rol de motor: grp_analista.',                     true, true)
ON CONFLICT (codigo) DO NOTHING;

-- Usuario administrador de prueba (BCrypt $2a$, lo valida BCryptPasswordEncoder)
INSERT INTO usuario (email, password_hash, nombre, apellido, email_verificado, activo)
SELECT 'admin@retailmind.com',
       crypt('Admin2026!', gen_salt('bf', 10)),
       'Administrador', 'Sistema', true, true
WHERE NOT EXISTS (SELECT 1 FROM usuario WHERE lower(email) = 'admin@retailmind.com');

INSERT INTO usuario_rol (usuario_id, rol_id)
SELECT u.id, r.id
FROM usuario u, rol r
WHERE lower(u.email) = 'admin@retailmind.com' AND r.codigo = 'ADMIN'
ON CONFLICT (usuario_id, rol_id) DO NOTHING;
