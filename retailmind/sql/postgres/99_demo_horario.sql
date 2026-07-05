-- ============================================================================
-- 99_demo_horario.sql — RetailMind · GUION DE DEMO para video (no es schema)
-- Ejecutar conectado como postgres:
--   psql -h localhost -U postgres -d retailmind -f 99_demo_horario.sql
-- O pegar bloque por bloque en una sesion psql para narrarlo.
-- No persiste nada: todo va dentro de BEGIN ... ROLLBACK.
-- ============================================================================

-- ESCENA 0: mostrar la configuracion de horarios vigente
SELECT rol_grupo, dia_semana, hora_inicio, hora_fin, activo
FROM grupo_horario ORDER BY rol_grupo, dia_semana;

SELECT now() AS ahora,
       esta_en_horario('grp_bodega')        AS bodega_puede,
       esta_en_horario('grp_cliente')       AS cliente_puede,
       esta_en_horario('grp_administrador') AS admin_puede;

BEGIN;
-- datos de utileria para la demo
INSERT INTO bodega (codigo, nombre) VALUES ('DEMO01','Bodega Demo');

-- ============================================================
-- ESCENA 1: DENTRO de horario — grp_bodega trabaja normal
-- ============================================================
SET ROLE grp_bodega;
SELECT current_user, count(*) AS bodegas_visibles FROM bodega;   -- 1
RESET ROLE;

-- ============================================================
-- ESCENA 2: el ADMIN saca a bodega de horario (sin ALTER, solo UPDATE)
-- ============================================================
UPDATE grupo_horario SET activo = false WHERE rol_grupo = 'grp_bodega';
SELECT esta_en_horario('grp_bodega');                            -- false

-- ============================================================
-- ESCENA 3: FUERA de horario — lectura filtrada, escritura bloqueada
-- ============================================================
SET ROLE grp_bodega;
SELECT current_user, count(*) AS bodegas_visibles FROM bodega;   -- 0 (RLS)
SAVEPOINT antes_del_error;
INSERT INTO bodega (codigo, nombre) VALUES ('X','No debe entrar');
-- ERROR: Acceso denegado: fuera del horario permitido para el rol grp_bodega
ROLLBACK TO antes_del_error;
RESET ROLE;

-- ============================================================
-- ESCENA 4: el ADMIN sigue operando (exento)
-- ============================================================
SET ROLE grp_administrador;
SELECT current_user, count(*) AS bodegas_visibles FROM bodega;   -- 1
RESET ROLE;

-- ============================================================
-- ESCENA 5: grp_cliente solo ve SUS filas (RLS de propiedad)
-- ============================================================
INSERT INTO cliente (nombre, email) VALUES ('Ana','ana@demo.com'), ('Beto','beto@demo.com');
INSERT INTO carrito (cliente_id) SELECT id FROM cliente;

SELECT set_config('app.cliente_id',
       (SELECT id::text FROM cliente WHERE email='ana@demo.com'), true);
SET ROLE grp_cliente;
SELECT nombre, email FROM cliente;                    -- solo Ana
SELECT count(*) AS carritos_visibles FROM carrito;    -- 1 (el suyo)
UPDATE cliente SET nombre='Hackeado' WHERE email='beto@demo.com';  -- UPDATE 0
RESET ROLE;

-- ============================================================
-- ESCENA 6: grp_analista jamas escribe (privilegios, no horario)
-- ============================================================
SET ROLE grp_analista;
SAVEPOINT antes_analista;
INSERT INTO bodega (codigo, nombre) VALUES ('Y','Tampoco');
-- ERROR: permiso denegado a la tabla bodega
ROLLBACK TO antes_analista;
RESET ROLE;

-- limpiar todo (nada de la demo persiste, incluido el activo=false)
ROLLBACK;

SELECT count(*) AS bodegas, (SELECT bool_and(activo) FROM grupo_horario) AS horarios_activos
FROM bodega;
