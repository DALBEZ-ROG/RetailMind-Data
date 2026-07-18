-- ============================================================================
-- 40 · APLICACIÓN REAL DE CUPONES Y PROMOCIONES (fase de descuentos)
-- ----------------------------------------------------------------------------
-- El módulo de marketing (cupon / uso_cupon / promocion / promocion_producto)
-- pasa de CRUD a APLICARSE en el ciclo de venta:
--   · Promociones vigentes  -> descuento POR LÍNEA en pedido_detalle.monto_descuento
--     (el trigger fn_recalcular_total_pedido ya resta ese monto del subtotal).
--   · Cupón del checkout    -> descuento DE CABECERA en pedido.monto_descuento
--     (el trigger fn_recalcular_total_cabecera_pedido ya lo resta del total).
-- NO se crea ninguna columna ni se toca ningún trigger de totales existente:
-- el esquema ya traía la capa de descuento preparada.
--
-- Este script agrega SOLO lo que faltaba:
--   1. Grants para que grp_cliente valide cupones y registre su uso, y para
--      que grp_vendedor vea el uso del cupón en el detalle del pedido.
--   2. Trigger SECURITY DEFINER sobre uso_cupon que, con lock de fila del
--      cupón, enforza límites (vigencia, usos_maximos, usos_por_cliente) y
--      mantiene cupon.usos_actuales — la app NUNCA escribe ese contador.
--      Es el backstop de concurrencia: dos checkouts simultáneos no pueden
--      superar el límite aunque ambos hayan pasado la validación de servicio.
--
-- Idempotente: se puede re-ejecutar sin efectos secundarios.
-- ============================================================================

BEGIN;

-- ── 1 · Grants ──────────────────────────────────────────────────────────────
-- grp_cliente ya tiene USAGE sobre el schema y SELECT sobre promocion /
-- promocion_producto (script 34). Le faltaba leer cupon (validación) y
-- escribir/leer uso_cupon (registro + conteo de usos propios).
GRANT SELECT           ON cupon     TO grp_cliente;
GRANT SELECT, INSERT   ON uso_cupon TO grp_cliente;

-- Back-office: el detalle del pedido (ADMIN/GERENTE/VENDEDOR) muestra el
-- cupón aplicado; gerente y admin ya leían uso_cupon, vendedor no.
GRANT SELECT           ON uso_cupon TO grp_vendedor;

-- ── 2 · Trigger de límites y contador (SECURITY DEFINER) ────────────────────
-- Corre como el dueño del esquema: bloquea la fila del cupón (FOR UPDATE),
-- re-verifica TODAS las reglas dentro de la transacción y actualiza
-- usos_actuales. grp_cliente no necesita (ni recibe) UPDATE sobre cupon.
-- ERRCODE 23514 (check_violation) -> DataIntegrityViolationException -> 400;
-- los mensajes claros al usuario los da la pre-validación del servicio.
CREATE OR REPLACE FUNCTION fn_registrar_uso_cupon()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_cupon        cupon%ROWTYPE;
    v_usos_cliente integer;
BEGIN
    SELECT * INTO v_cupon FROM cupon WHERE id = NEW.cupon_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'El cupón % no existe', NEW.cupon_id
              USING ERRCODE = 'foreign_key_violation';
    END IF;

    IF NOT v_cupon.activo THEN
        RAISE EXCEPTION 'El cupón % está inactivo', v_cupon.codigo
              USING ERRCODE = 'check_violation';
    END IF;
    IF now() < v_cupon.fecha_inicio
       OR (v_cupon.fecha_fin IS NOT NULL AND now() > v_cupon.fecha_fin) THEN
        RAISE EXCEPTION 'El cupón % está fuera de vigencia', v_cupon.codigo
              USING ERRCODE = 'check_violation';
    END IF;

    IF v_cupon.usos_maximos IS NOT NULL
       AND v_cupon.usos_actuales >= v_cupon.usos_maximos THEN
        RAISE EXCEPTION 'El cupón % agotó sus usos disponibles (límite %)',
              v_cupon.codigo, v_cupon.usos_maximos
              USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.cliente_id IS NOT NULL THEN
        SELECT count(*) INTO v_usos_cliente
        FROM uso_cupon
        WHERE cupon_id = NEW.cupon_id AND cliente_id = NEW.cliente_id;
        IF v_usos_cliente >= v_cupon.usos_por_cliente THEN
            RAISE EXCEPTION 'El cliente ya usó el cupón % el máximo permitido (%)',
                  v_cupon.codigo, v_cupon.usos_por_cliente
                  USING ERRCODE = 'check_violation';
        END IF;
    END IF;

    UPDATE cupon SET usos_actuales = usos_actuales + 1 WHERE id = NEW.cupon_id;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_uso_cupon_registro ON uso_cupon;
CREATE TRIGGER trg_uso_cupon_registro
    BEFORE INSERT ON uso_cupon
    FOR EACH ROW EXECUTE FUNCTION fn_registrar_uso_cupon();

-- Anulación administrativa: si ADMIN borra un uso, el contador se libera.
CREATE OR REPLACE FUNCTION fn_liberar_uso_cupon()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    UPDATE cupon SET usos_actuales = GREATEST(0, usos_actuales - 1)
    WHERE id = OLD.cupon_id;
    RETURN OLD;
END;
$$;

DROP TRIGGER IF EXISTS trg_uso_cupon_liberacion ON uso_cupon;
CREATE TRIGGER trg_uso_cupon_liberacion
    AFTER DELETE ON uso_cupon
    FOR EACH ROW EXECUTE FUNCTION fn_liberar_uso_cupon();

-- Un pedido usa a lo sumo UN cupón (no acumulables por pedido).
CREATE UNIQUE INDEX IF NOT EXISTS uq_uso_cupon_pedido ON uso_cupon (pedido_id);

COMMIT;
