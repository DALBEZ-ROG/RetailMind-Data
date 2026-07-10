-- ============================================================================
-- 32_grants_resenas.sql — RetailMind · Addendum de privilegios (módulo reseñas)
--  Activa las tablas de M11 (resena, resena_util, reporte_resena,
--  pregunta_producto, respuesta_pregunta) para el módulo de reseñas y
--  preguntas de producto:
--    - grp_cliente: crea reseñas (ya tenía SELECT/INSERT/UPDATE sobre resena
--      por el script 19), vota utilidad, reporta abusos y pregunta; lee las
--      respuestas. OJO: resena_util, reporte_resena, pregunta_producto y
--      respuesta_pregunta NO tienen política RLS; el aislamiento por cliente
--      se aplica en la capa de servicio (ResenasService) con el cliente_id
--      del JWT, como en soporte (script 28). Si se decide llevarlo al motor,
--      agregar políticas sobre cliente_id = fn_cliente_actual().
--    - grp_gerente: modera reseñas y preguntas, responde preguntas y resuelve
--      reportes. Ya tenía SELECT por el script 19.
--  AVISO — POLÍTICA RLS NUEVA: resena sí tiene RLS (pol_cliente_propio,
--  script 21) que limita a grp_cliente a SUS filas; sin más, un cliente no
--  podría leer las reseñas aprobadas de otros (listado público del producto)
--  ni votarlas. Se agrega pol_resena_publica (solo SELECT, solo aprobadas,
--  dentro de horario). Las políticas permisivas se combinan con OR, así que
--  el cliente sigue viendo las suyas en cualquier estado. Idempotente.
-- ============================================================================

-- Votos de utilidad y reportes del cliente
GRANT SELECT, INSERT ON resena_util    TO grp_cliente;
GRANT SELECT, INSERT ON reporte_resena TO grp_cliente;

-- Preguntas del cliente y lectura de respuestas
GRANT SELECT, INSERT ON pregunta_producto  TO grp_cliente;
GRANT SELECT         ON respuesta_pregunta TO grp_cliente;

-- Secuencias identity de lo que inserta el cliente (belt & braces)
GRANT USAGE ON SEQUENCE resena_id_seq, resena_util_id_seq, reporte_resena_id_seq,
                        pregunta_producto_id_seq TO grp_cliente;

-- Moderación por gerencia: estados de reseña/pregunta/reporte y respuestas
GRANT UPDATE ON resena             TO grp_gerente;
GRANT UPDATE ON pregunta_producto  TO grp_gerente;
GRANT UPDATE ON reporte_resena     TO grp_gerente;
GRANT INSERT ON respuesta_pregunta TO grp_gerente;
GRANT USAGE ON SEQUENCE respuesta_pregunta_id_seq TO grp_gerente;

-- Lectura pública de reseñas APROBADAS para el cliente (ver AVISO arriba)
DROP POLICY IF EXISTS pol_resena_publica ON resena;
CREATE POLICY pol_resena_publica ON resena
    FOR SELECT TO grp_cliente
    USING (esta_en_horario('grp_cliente') AND estado = 'aprobada');
