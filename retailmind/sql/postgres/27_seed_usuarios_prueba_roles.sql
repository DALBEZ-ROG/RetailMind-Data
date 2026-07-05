-- ============================================================================
-- 27_seed_usuarios_prueba_roles.sql — RetailMind · Usuarios de prueba por rol
--  Crea UN usuario de prueba por cada rol de negocio que aun no tenia usuario:
--        GERENTE   -> gerente@retailmind.com
--        VENDEDOR  -> vendedor@retailmind.com
--        COMPRAS   -> compras@retailmind.com
--        BODEGA    -> bodega@retailmind.com
--        DESPACHO  -> despacho@retailmind.com
--        ANALISTA  -> analista@retailmind.com
--
--  Password (todos): Retail2026!
--    Hash BCrypt $2a$10$ via pgcrypto (crypt + gen_salt('bf',10)), identico al
--    formato del admin sembrado en 23_seed_roles_admin.sql y compatible con
--    BCryptPasswordEncoder de Spring Security.
--
--  Idempotente: WHERE NOT EXISTS en usuario + ON CONFLICT en usuario_rol.
--  Las columnas id son GENERATED ALWAYS AS IDENTITY: no se insertan manualmente.
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ------------------------------------------------------------------ usuarios
INSERT INTO usuario (email, password_hash, nombre, apellido, email_verificado, activo)
SELECT v.email, crypt('Retail2026!', gen_salt('bf', 10)), v.nombre, v.apellido, true, true
FROM (VALUES
    ('gerente@retailmind.com',  'Gerente',  'Prueba'),
    ('vendedor@retailmind.com', 'Vendedor', 'Prueba'),
    ('compras@retailmind.com',  'Compras',  'Prueba'),
    ('bodega@retailmind.com',   'Bodega',   'Prueba'),
    ('despacho@retailmind.com', 'Despacho', 'Prueba'),
    ('analista@retailmind.com', 'Analista', 'Prueba')
) AS v(email, nombre, apellido)
WHERE NOT EXISTS (
    SELECT 1 FROM usuario u WHERE lower(u.email) = v.email
);

-- ------------------------------------------------------------- vinculo rol
INSERT INTO usuario_rol (usuario_id, rol_id)
SELECT u.id, r.id
FROM usuario u
JOIN (VALUES
    ('gerente@retailmind.com',  'GERENTE'),
    ('vendedor@retailmind.com', 'VENDEDOR'),
    ('compras@retailmind.com',  'COMPRAS'),
    ('bodega@retailmind.com',   'BODEGA'),
    ('despacho@retailmind.com', 'DESPACHO'),
    ('analista@retailmind.com', 'ANALISTA')
) AS m(email, codigo) ON lower(u.email) = m.email
JOIN rol r ON r.codigo = m.codigo
ON CONFLICT (usuario_id, rol_id) DO NOTHING;
