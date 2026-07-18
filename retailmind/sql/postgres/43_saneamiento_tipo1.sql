-- ============================================================================
-- 43_saneamiento_tipo1.sql — Saneamiento de bugs Tipo 1 (2026-07-18)
-- Cierra los ítems de INVENTARIO_DEUDA_CONSOLIDADO.md clasificados como
-- BUG/INCONSISTENCIA REAL que requieren cambios de motor:
--   a) Numeración de documentos por SECUENCIA (antes: random 5 dígitos con
--      colisión posible → 400 genérico por el UNIQUE).
--   b) Correlativo TICK-AAAA-NNNN atómico (antes: count(*)+1 con carrera).
--   c) RLS en pago / transaccion_pago (antes: cero políticas; el aislamiento
--      dependía solo de la capa de servicio).
--   d) RLS en cupon / uso_cupon (antes: grp_cliente leía todos los códigos y
--      los usos de otros clientes a nivel de motor).
--   e) resena: grants POR COLUMNA para grp_cliente (antes podía escribir
--      moderado_por / fecha_moderacion / estado vía SQL directo).
--   f) categoria_ticket: se retira la escritura latente de grp_soporte
--      (gestión de categorías = solo ADMIN, como en SecurityConfig).
--   g) Datos legacy: factura FV-20260711-55374 (pedido interno 'confirmado'
--      emitida sin pago, previa a la compuerta pago→factura) queda ANULADA.
-- Idempotente: se puede re-ejecutar sin efectos dobles.
-- ============================================================================

-- ── a) Secuencia global de numeración de documentos ─────────────────────────
-- Formato PREFIJO-YYYYMMDD-NNNNNN. Arranca en 100000 (6 dígitos) para que
-- nunca colisione con los números legacy aleatorios de 5 dígitos.
CREATE SEQUENCE IF NOT EXISTS seq_numero_documento START WITH 100000;

GRANT USAGE ON SEQUENCE seq_numero_documento TO
    grp_administrador, grp_gerente, grp_vendedor, grp_compras,
    grp_bodega, grp_despacho, grp_soporte, grp_cliente;

-- ── b) Correlativo de tickets por año (atómico bajo lock de fila) ───────────
CREATE TABLE IF NOT EXISTS correlativo_ticket (
    anio   integer PRIMARY KEY,
    ultimo integer NOT NULL DEFAULT 0
);

-- Siembra idempotente desde los tickets ya emitidos (nunca retrocede)
INSERT INTO correlativo_ticket (anio, ultimo)
SELECT split_part(numero, '-', 2)::int, max(split_part(numero, '-', 3)::int)
FROM ticket_soporte
WHERE numero ~ '^TICK-\d{4}-\d+$'
GROUP BY 1
ON CONFLICT (anio) DO UPDATE
    SET ultimo = GREATEST(correlativo_ticket.ultimo, EXCLUDED.ultimo);

-- SECURITY DEFINER: los roles no tocan la tabla; solo ejecutan la función.
-- El UPSERT bloquea la fila del año → dos creaciones simultáneas se
-- serializan y cada una recibe su número (sin 400 por el UNIQUE).
CREATE OR REPLACE FUNCTION fn_siguiente_numero_ticket() RETURNS text
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_anio integer := extract(year from (now() AT TIME ZONE 'America/Guayaquil'))::int;
    v_n    integer;
BEGIN
    INSERT INTO correlativo_ticket (anio, ultimo) VALUES (v_anio, 1)
    ON CONFLICT (anio) DO UPDATE SET ultimo = correlativo_ticket.ultimo + 1
    RETURNING ultimo INTO v_n;
    RETURN 'TICK-' || v_anio || '-' || lpad(v_n::text, 4, '0');
END $$;

REVOKE ALL ON FUNCTION fn_siguiente_numero_ticket() FROM PUBLIC;
-- Quienes pueden crear tickets (SecurityConfig): ADMIN/GERENTE/CLIENTE/SOPORTE
GRANT EXECUTE ON FUNCTION fn_siguiente_numero_ticket() TO
    grp_administrador, grp_gerente, grp_soporte, grp_cliente;

-- ── c) RLS en pago / transaccion_pago ───────────────────────────────────────
-- Privilegios vigentes: admin ALL; gerente/analista SELECT; vendedor
-- INSERT+SELECT; cliente INSERT (+SELECT(id) en pago). Las políticas espejan
-- esa matriz con el patrón del proyecto (pol_horario + política de cliente).

-- Helper SECURITY DEFINER: ¿el pago pertenece a un pedido del cliente actual?
-- (la política de transaccion_pago no puede subconsultar pago directamente:
-- grp_cliente solo tiene SELECT de la columna id)
CREATE OR REPLACE FUNCTION fn_pago_del_cliente(p_pago_id bigint) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
    SELECT EXISTS (
        SELECT 1 FROM pago pa JOIN pedido p ON p.id = pa.pedido_id
        WHERE pa.id = p_pago_id AND p.cliente_id = fn_cliente_actual());
$$;
REVOKE ALL ON FUNCTION fn_pago_del_cliente(bigint) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION fn_pago_del_cliente(bigint) TO grp_cliente;

ALTER TABLE pago ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS pol_horario ON pago;
CREATE POLICY pol_horario ON pago
    FOR ALL TO grp_administrador, grp_analista, grp_gerente, grp_vendedor
    USING (esta_en_horario(fn_grupo_actual()))
    WITH CHECK (esta_en_horario(fn_grupo_actual()));

-- Cliente: registra el pago de SU pedido (checkout online) y solo ve los suyos
DROP POLICY IF EXISTS pol_cliente_pago ON pago;
CREATE POLICY pol_cliente_pago ON pago
    FOR INSERT TO grp_cliente
    WITH CHECK (esta_en_horario('grp_cliente')
                AND pedido_id IN (SELECT id FROM pedido
                                  WHERE cliente_id = fn_cliente_actual()));

DROP POLICY IF EXISTS pol_cliente_propio ON pago;
CREATE POLICY pol_cliente_propio ON pago
    FOR SELECT TO grp_cliente
    USING (esta_en_horario('grp_cliente')
           AND pedido_id IN (SELECT id FROM pedido
                             WHERE cliente_id = fn_cliente_actual()));

ALTER TABLE transaccion_pago ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS pol_horario ON transaccion_pago;
CREATE POLICY pol_horario ON transaccion_pago
    FOR ALL TO grp_administrador, grp_analista, grp_gerente, grp_vendedor
    USING (esta_en_horario(fn_grupo_actual()))
    WITH CHECK (esta_en_horario(fn_grupo_actual()));

DROP POLICY IF EXISTS pol_cliente_pago ON transaccion_pago;
CREATE POLICY pol_cliente_pago ON transaccion_pago
    FOR INSERT TO grp_cliente
    WITH CHECK (esta_en_horario('grp_cliente') AND fn_pago_del_cliente(pago_id));

-- ── d) RLS en cupon / uso_cupon ─────────────────────────────────────────────
-- Privilegios vigentes: admin ALL; gerente/analista/vendedor SELECT;
-- cliente SELECT en cupon e INSERT+SELECT en uso_cupon.

ALTER TABLE cupon ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS pol_horario ON cupon;
CREATE POLICY pol_horario ON cupon
    FOR ALL TO grp_administrador, grp_analista, grp_gerente, grp_vendedor
    USING (esta_en_horario(fn_grupo_actual()))
    WITH CHECK (esta_en_horario(fn_grupo_actual()));

-- Cliente: solo cupones ACTIVOS (un código desactivado deja de ser legible;
-- la validación responde "no existe", que es lo correcto de cara al cliente)
-- o cupones que ÉL ya usó (para que Mis Pedidos siga mostrando el código).
DROP POLICY IF EXISTS pol_cliente_activo ON cupon;
CREATE POLICY pol_cliente_activo ON cupon
    FOR SELECT TO grp_cliente
    USING (esta_en_horario('grp_cliente')
           AND (activo OR EXISTS (SELECT 1 FROM uso_cupon uc
                                  WHERE uc.cupon_id = cupon.id
                                    AND uc.cliente_id = fn_cliente_actual())));

ALTER TABLE uso_cupon ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS pol_horario ON uso_cupon;
CREATE POLICY pol_horario ON uso_cupon
    FOR ALL TO grp_administrador, grp_analista, grp_gerente, grp_vendedor
    USING (esta_en_horario(fn_grupo_actual()))
    WITH CHECK (esta_en_horario(fn_grupo_actual()));

-- Cliente: registra y ve SOLO sus propios usos (el conteo global de límites
-- lo hace el trigger SECURITY DEFINER fn_registrar_uso_cupon, que no depende
-- de esta política)
DROP POLICY IF EXISTS pol_cliente_propio ON uso_cupon;
CREATE POLICY pol_cliente_propio ON uso_cupon
    FOR SELECT TO grp_cliente
    USING (esta_en_horario('grp_cliente') AND cliente_id = fn_cliente_actual());

DROP POLICY IF EXISTS pol_cliente_uso ON uso_cupon;
CREATE POLICY pol_cliente_uso ON uso_cupon
    FOR INSERT TO grp_cliente
    WITH CHECK (esta_en_horario('grp_cliente') AND cliente_id = fn_cliente_actual());

-- ── e) resena: mínimo privilegio por columna para grp_cliente ───────────────
-- El cliente CREA reseñas (crearResena) pero nunca las modera: fuera
-- moderado_por / fecha_moderacion / estado de su alcance de escritura.
-- (No hay edición de reseñas del cliente: el UPDATE se revoca completo.)
REVOKE INSERT, UPDATE ON resena FROM grp_cliente;
GRANT INSERT (producto_id, cliente_id, pedido_id, calificacion,
              titulo, comentario, compra_verificada)
    ON resena TO grp_cliente;

-- ── f) categoria_ticket: higiene de mínimo privilegio ───────────────────────
-- La gestión de categorías es solo ADMIN (SecurityConfig); soporte solo lee.
REVOKE INSERT, UPDATE, DELETE ON categoria_ticket FROM grp_soporte;

-- ── g) Saneamiento de datos legacy (una sola vez, guardado por estado) ──────
-- Pedido interno PED-20260711-24662: quedó 'confirmado' con una factura
-- emitida SIN pago (anterior a la compuerta pago→factura del script 36/39).
-- Se anula la factura legacy; el pedido sigue su flujo normal (pago →
-- factura → preparación). El backend ya ignora facturas 'anulada' en la
-- guardia de idempotencia de emitirFactura.
UPDATE factura_venta
SET estado = 'anulada'
WHERE numero = 'FV-20260711-55374'
  AND estado = 'emitida'
  AND pedido_id = (SELECT id FROM pedido WHERE numero = 'PED-20260711-24662');

INSERT INTO historial_estado_pedido (pedido_id, estado_pedido_id, comentario)
SELECT p.id, (SELECT id FROM estado_pedido WHERE codigo = 'confirmado'),
       'Saneamiento 2026-07-18: factura legacy FV-20260711-55374 ANULADA '
       || '(emitida sin pago, previa a la compuerta pago→factura); '
       || 'el pedido continúa su flujo normal desde ''confirmado'''
FROM pedido p
WHERE p.numero = 'PED-20260711-24662'
  AND NOT EXISTS (SELECT 1 FROM historial_estado_pedido h
                  WHERE h.pedido_id = p.id
                    AND h.comentario LIKE 'Saneamiento 2026-07-18: factura legacy%');
