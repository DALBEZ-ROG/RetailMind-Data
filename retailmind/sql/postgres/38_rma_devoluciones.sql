-- ============================================================================
-- 38_rma_devoluciones.sql — RetailMind · RMA / logística inversa (2026-07-16)
--
-- Rediseña la devolución simplista (un paso, stock inmediato) como proceso
-- RMA completo que NACE DEL CLIENTE y pasa por SOPORTE → DESPACHO → BODEGA →
-- GERENTE. Ciclo de vida (CHECK ampliado, compuertas en backend):
--
--   solicitada → en_revision → aprobada → en_transito → recibida
--             → inspeccionada → reembolsada → cerrada      (rechazada terminal)
--
-- El stock reingresa SOLO en la inspección de BODEGA y solo los ítems
-- APTO_REVENTA (resultado_inspeccion por ítem). El reembolso lo procesa
-- GERENTE/ADMIN sobre lo aprobado en inspección (apto + defectuoso; el
-- rechazado —daño imputable al cliente— no se reembolsa).
--
-- Cambios MÍNIMOS y ADITIVOS:
--   1) devolucion: CHECK de estado ampliado + columnas (cliente_id backfilled,
--      ticket_soporte_id, guía de retorno, transportista, bodega de reingreso,
--      motivo_rechazo, monto/método/fecha de reembolso).
--   2) devolucion_detalle: resultado_inspeccion + nota_inspeccion.
--   3) historial_estado_devolucion (patrón historial_estado_pedido; autor
--      usuario_id O cliente_id porque la solicitud la crea el cliente).
--   4) MIGRACIÓN de las devoluciones del flujo anterior: estaban en 'recibida'
--      con el stock YA reingresado al registrarlas → pasan a 'cerrada' con
--      historial explicativo y sus ítems quedan 'apto_reventa' (así sus
--      cantidades siguen contando para "no devolver más de lo comprado").
--   5) GRANTs por rol de la matriz + RLS (pol_horario staff / pol_soporte /
--      pol_cliente_propio) en las 3 tablas del módulo.
--
-- Idempotente. No toca triggers ni tablas ajenas al módulo.
-- ============================================================================

-- ------------------------------------------------ 1) devolucion: estados nuevos
ALTER TABLE devolucion DROP CONSTRAINT IF EXISTS devolucion_estado_check;
ALTER TABLE devolucion ADD CONSTRAINT devolucion_estado_check
    CHECK (estado IN ('solicitada', 'en_revision', 'aprobada', 'rechazada',
                      'en_transito', 'recibida', 'inspeccionada',
                      'reembolsada', 'cerrada'));

-- --------------------------------------------- 1b) devolucion: columnas nuevas
ALTER TABLE devolucion
    ADD COLUMN IF NOT EXISTS cliente_id        bigint REFERENCES cliente(id)
                                               ON UPDATE CASCADE ON DELETE RESTRICT,
    ADD COLUMN IF NOT EXISTS ticket_soporte_id bigint REFERENCES ticket_soporte(id)
                                               ON UPDATE CASCADE ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS transportista_id  bigint REFERENCES transportista(id)
                                               ON UPDATE CASCADE ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS bodega_id         bigint REFERENCES bodega(id)
                                               ON UPDATE CASCADE ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS guia_retorno      varchar(30),
    ADD COLUMN IF NOT EXISTS motivo_rechazo    text,
    ADD COLUMN IF NOT EXISTS monto_reembolsado numeric(12,2),
    ADD COLUMN IF NOT EXISTS metodo_reembolso  varchar(30),
    ADD COLUMN IF NOT EXISTS fecha_reembolso   timestamptz;

-- Backfill del dueño (las filas viejas se crearon sin cliente): sale del pedido
UPDATE devolucion d SET cliente_id = p.cliente_id
FROM pedido p WHERE p.id = d.pedido_id AND d.cliente_id IS NULL;

-- ------------------------- 2) devolucion_detalle: inspección de calidad por ítem
ALTER TABLE devolucion_detalle
    ADD COLUMN IF NOT EXISTS resultado_inspeccion varchar(15),
    ADD COLUMN IF NOT EXISTS nota_inspeccion      text;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'devolucion_detalle_resultado_inspeccion_check') THEN
        ALTER TABLE devolucion_detalle
            ADD CONSTRAINT devolucion_detalle_resultado_inspeccion_check
            CHECK (resultado_inspeccion IS NULL
                   OR resultado_inspeccion IN ('apto_reventa', 'defectuoso', 'rechazado'));
    END IF;
END $$;

-- --------------------------------- 3) Historial de estados de la devolución
CREATE TABLE IF NOT EXISTS historial_estado_devolucion (
    id             bigserial PRIMARY KEY,
    devolucion_id  bigint NOT NULL REFERENCES devolucion(id)
                   ON UPDATE CASCADE ON DELETE CASCADE,
    estado         varchar(20) NOT NULL
                   CHECK (estado IN ('solicitada', 'en_revision', 'aprobada',
                                     'rechazada', 'en_transito', 'recibida',
                                     'inspeccionada', 'reembolsada', 'cerrada')),
    -- Autor: usuario interno O cliente (la solicitud nace del cliente y
    -- grp_cliente no lee la tabla usuario)
    usuario_id     bigint REFERENCES usuario(id) ON UPDATE CASCADE ON DELETE SET NULL,
    cliente_id     bigint REFERENCES cliente(id) ON UPDATE CASCADE ON DELETE SET NULL,
    comentario     text,
    fecha_creacion timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_hist_devolucion ON historial_estado_devolucion (devolucion_id);

-- ------------------- 4) Migración del flujo anterior (5 devoluciones 'recibida')
-- En el flujo viejo 'recibida' era terminal y el stock reingresaba al
-- registrarla. En el flujo nuevo 'recibida' = "paquete llegó, falta inspección",
-- así que las filas legacy (sin historial: solo existen pre-RMA) se cierran.
DO $$
DECLARE
    v_dev record;
BEGIN
    FOR v_dev IN
        SELECT d.id FROM devolucion d
        WHERE d.estado = 'recibida'
          AND NOT EXISTS (SELECT 1 FROM historial_estado_devolucion h
                          WHERE h.devolucion_id = d.id)
    LOOP
        UPDATE devolucion SET estado = 'cerrada' WHERE id = v_dev.id;
        UPDATE devolucion_detalle
        SET resultado_inspeccion = 'apto_reventa',
            nota_inspeccion = 'Flujo anterior: stock reingresado al registrar la devolución'
        WHERE devolucion_id = v_dev.id AND resultado_inspeccion IS NULL;
        INSERT INTO historial_estado_devolucion (devolucion_id, estado, comentario)
        VALUES (v_dev.id, 'cerrada',
                'Migrada del flujo anterior (proceso en un paso, stock ya reingresado)');
    END LOOP;
END $$;

-- ----------------------------------------------------- 5) GRANTs de la matriz
-- CLIENTE: solicita (INSERT) y consulta las suyas (RLS); lee motivos activos,
-- el transportista de su guía y escribe/lee su historial.
GRANT SELECT, INSERT ON devolucion, devolucion_detalle TO grp_cliente;
GRANT SELECT, INSERT ON historial_estado_devolucion    TO grp_cliente;
GRANT SELECT ON motivo_devolucion, transportista       TO grp_cliente;
GRANT USAGE ON SEQUENCE devolucion_id_seq, devolucion_detalle_id_seq,
                        historial_estado_devolucion_id_seq TO grp_cliente;

-- SOPORTE: valida la solicitud (en_revision/aprobar/rechazar + guía de retorno).
-- Ya tenía SELECT (script 37); se agrega la escritura de la transición.
GRANT UPDATE ON devolucion                          TO grp_soporte;
GRANT SELECT, INSERT ON historial_estado_devolucion TO grp_soporte;
GRANT SELECT ON transportista, bodega               TO grp_soporte;
GRANT USAGE ON SEQUENCE historial_estado_devolucion_id_seq TO grp_soporte;

-- DESPACHO (logística inversa): marca en_transito / recibida.
GRANT SELECT, UPDATE ON devolucion                  TO grp_despacho;
GRANT SELECT ON devolucion_detalle, motivo_devolucion TO grp_despacho;
GRANT SELECT, INSERT ON historial_estado_devolucion TO grp_despacho;
GRANT USAGE ON SEQUENCE historial_estado_devolucion_id_seq TO grp_despacho;

-- BODEGA (control de calidad): registra la inspección por ítem y el reingreso
-- del stock apto (inventario/movimiento_inventario ya los tiene del script 19).
GRANT SELECT, UPDATE ON devolucion, devolucion_detalle TO grp_bodega;
GRANT SELECT ON motivo_devolucion, cliente, transportista TO grp_bodega;
GRANT SELECT, INSERT ON historial_estado_devolucion    TO grp_bodega;
GRANT USAGE ON SEQUENCE historial_estado_devolucion_id_seq TO grp_bodega;
-- La línea de tiempo muestra el AUTOR de cada paso (JOIN a usuario): grant de
-- COLUMNA mínimo — bodega no lee emails ni hashes
GRANT SELECT (id, nombre, apellido) ON usuario TO grp_bodega;

-- GERENTE (finanzas): procesa el reembolso. Ya leía; se agrega la escritura.
GRANT UPDATE ON devolucion                          TO grp_gerente;
GRANT SELECT, INSERT ON historial_estado_devolucion TO grp_gerente;
GRANT USAGE ON SEQUENCE historial_estado_devolucion_id_seq TO grp_gerente;

-- VENDEDOR / ANALISTA / SOPORTE: visibilidad del proceso (solo lectura)
GRANT SELECT ON devolucion, devolucion_detalle, motivo_devolucion TO grp_vendedor;
GRANT SELECT ON historial_estado_devolucion TO grp_vendedor, grp_analista;

-- Apoyos que faltaban para las pantallas/joins del módulo:
--  * despacho y vendedor leen transportista/bodega en los listados de RMA
--  * BODEGA concluye el retorno físico: al inspeccionar marca el pedido
--    'devuelto' (grant de COLUMNA, solo estado) y deja rastro en su historial
GRANT SELECT ON bodega        TO grp_despacho;
GRANT SELECT ON transportista TO grp_vendedor;
GRANT UPDATE (estado_pedido_id) ON pedido TO grp_bodega;
GRANT SELECT ON estado_pedido             TO grp_bodega;
GRANT INSERT ON historial_estado_pedido   TO grp_bodega;
GRANT USAGE ON SEQUENCE historial_estado_pedido_id_seq TO grp_bodega;

-- ---------------------------------------------- 5b) Trigger de totales del RMA
-- fn_recalcular_total_devolucion (trigger de devolucion_detalle) escribe
-- devolucion.monto_total. Sin SECURITY DEFINER corre como el rol invocador
-- (grp_cliente al solicitar, que NO tiene UPDATE sobre devolucion) y revienta
-- con 42501 — mismo caso ya resuelto en el trigger de totales de la orden de
-- compra. La app NUNCA escribe monto_total (regla de oro #1).
ALTER FUNCTION fn_recalcular_total_devolucion() SECURITY DEFINER;

-- ------------------------------------------------------------------- 6) RLS
-- Mismo patrón del resto del esquema: pol_horario enumera los grupos staff,
-- pol_soporte va aparte (24/7) y pol_cliente_propio aísla por app.cliente_id.
ALTER TABLE devolucion                  ENABLE ROW LEVEL SECURITY;
ALTER TABLE devolucion_detalle          ENABLE ROW LEVEL SECURITY;
ALTER TABLE historial_estado_devolucion ENABLE ROW LEVEL SECURITY;

DO $$
DECLARE
    v_tabla text;
BEGIN
    FOREACH v_tabla IN ARRAY ARRAY['devolucion', 'devolucion_detalle',
                                   'historial_estado_devolucion'] LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public'
                       AND tablename = v_tabla AND policyname = 'pol_horario') THEN
            EXECUTE format(
                'CREATE POLICY pol_horario ON public.%I FOR ALL
                 TO grp_administrador, grp_analista, grp_bodega, grp_compras,
                    grp_despacho, grp_gerente, grp_vendedor
                 USING (esta_en_horario(fn_grupo_actual()))
                 WITH CHECK (esta_en_horario(fn_grupo_actual()))', v_tabla);
        END IF;
        IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public'
                       AND tablename = v_tabla AND policyname = 'pol_soporte') THEN
            EXECUTE format(
                'CREATE POLICY pol_soporte ON public.%I FOR ALL TO grp_soporte
                 USING (esta_en_horario(''grp_soporte''))
                 WITH CHECK (esta_en_horario(''grp_soporte''))', v_tabla);
        END IF;
    END LOOP;
END $$;

-- bodega tiene RLS y su pol_horario no incluye a grp_soporte: sin política
-- propia el GRANT de arriba vería 0 filas (y aprobar no hallaría la bodega
-- principal). transportista y motivo_devolucion no tienen RLS: el GRANT basta.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public'
                   AND tablename = 'bodega' AND policyname = 'pol_soporte') THEN
        CREATE POLICY pol_soporte ON public.bodega FOR SELECT TO grp_soporte
            USING (esta_en_horario('grp_soporte'));
    END IF;
END $$;

-- Cliente: sus devoluciones por cliente_id directo; detalle e historial por
-- pertenencia a una devolución suya (subconsulta, patrón pol_cliente_propio
-- de envio/historial_estado_pedido).
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public'
                   AND tablename = 'devolucion' AND policyname = 'pol_cliente_propio') THEN
        CREATE POLICY pol_cliente_propio ON public.devolucion FOR ALL TO grp_cliente
            USING (esta_en_horario('grp_cliente') AND cliente_id = fn_cliente_actual())
            WITH CHECK (esta_en_horario('grp_cliente') AND cliente_id = fn_cliente_actual());
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public'
                   AND tablename = 'devolucion_detalle' AND policyname = 'pol_cliente_propio') THEN
        CREATE POLICY pol_cliente_propio ON public.devolucion_detalle FOR ALL TO grp_cliente
            USING (esta_en_horario('grp_cliente') AND devolucion_id IN
                   (SELECT id FROM devolucion WHERE cliente_id = fn_cliente_actual()))
            WITH CHECK (esta_en_horario('grp_cliente') AND devolucion_id IN
                   (SELECT id FROM devolucion WHERE cliente_id = fn_cliente_actual()));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public'
                   AND tablename = 'historial_estado_devolucion'
                   AND policyname = 'pol_cliente_propio') THEN
        CREATE POLICY pol_cliente_propio ON public.historial_estado_devolucion
            FOR ALL TO grp_cliente
            USING (esta_en_horario('grp_cliente') AND devolucion_id IN
                   (SELECT id FROM devolucion WHERE cliente_id = fn_cliente_actual()))
            WITH CHECK (esta_en_horario('grp_cliente') AND devolucion_id IN
                   (SELECT id FROM devolucion WHERE cliente_id = fn_cliente_actual()));
    END IF;
END $$;
