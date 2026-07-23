-- ============================================================================
-- 52_intento_pago_fallido.sql — Registro de intentos de cobro RECHAZADOS
-- (2026-07-22, OTD-VEN-12, cierre de brecha del catálogo táctico § 11.1)
--
-- Hoy pago.estado = 'completado' en 26/26 y transaccion_pago.tipo = 'captura'
-- en 26/26: un pago rechazado en el checkout no deja rastro y no se puede
-- medir cuántas ventas se pierden en el cobro. Los CHECK ya admiten los
-- valores necesarios ('fallido' en pago, 'fallida' y 'autorizacion' en
-- transaccion_pago): NO se tocan.
--
-- El único obstáculo del motor era pago.pedido_id NOT NULL: el checkout crea
-- el pedido y cobra en UNA transacción, así que cuando la "pasarela" simulada
-- rechaza, TODO se revierte (pedido, stock, factura: comportamiento correcto
-- que se conserva) y no existe ningún pedido que el intento pueda referenciar.
-- Cambio mínimo y NO destructivo:
--   * pedido_id pasa a nullable SOLO para intentos fallidos — el CHECK
--     pago_pedido_o_fallido exige pedido en todo pago no-fallido, así que los
--     26 pagos reales y todo pago futuro completado conservan su integridad.
--   * fn_registrar_intento_pago_fallido (SECURITY DEFINER): inserta el par
--     pago('fallido') + transaccion_pago('autorizacion'/'fallida') con el
--     motivo y el contexto (cliente, carrito) en respuesta_pasarela. Corre
--     como postgres porque la política pol_cliente_pago exige pedido propio
--     (aquí no hay pedido) — mismo patrón que fn_registrar_uso_cupon.
--     El backend la llama en una transacción REQUIRES_NEW: el intento se
--     confirma aunque el checkout completo se revierta con el error.
--
-- RLS: las filas con pedido_id NULL son invisibles para grp_cliente
-- (pol_cliente_propio filtra por pedido propio) — el cliente ve el motivo en
-- la respuesta del checkout, no consultando pago. El personal (pol_horario)
-- sí las lee para el informe. Idempotente y transaccional.
-- ============================================================================

BEGIN;

-- ── 1) pedido_id nullable + coherencia (solo el intento fallido va sin pedido)
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_attribute
               WHERE attrelid = 'pago'::regclass
                 AND attname = 'pedido_id' AND attnotnull) THEN
        ALTER TABLE pago ALTER COLUMN pedido_id DROP NOT NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'pago_pedido_o_fallido') THEN
        ALTER TABLE pago ADD CONSTRAINT pago_pedido_o_fallido
            CHECK (pedido_id IS NOT NULL OR estado = 'fallido');
    END IF;
END $$;

-- ── 2) Registro del intento (SECURITY DEFINER; ver cabecera) ────────────────
CREATE OR REPLACE FUNCTION fn_registrar_intento_pago_fallido(
    p_cliente_id     bigint,
    p_metodo_pago_id bigint,
    p_monto          numeric,
    p_referencia     varchar,
    p_motivo         text,
    p_carrito_id     bigint
) RETURNS bigint
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
    v_pago_id bigint;
BEGIN
    IF p_monto IS NULL OR p_monto <= 0 THEN
        RAISE EXCEPTION 'El monto del intento de pago debe ser mayor que cero';
    END IF;
    IF p_motivo IS NULL OR btrim(p_motivo) = '' THEN
        RAISE EXCEPTION 'El intento de pago fallido requiere el motivo del rechazo';
    END IF;

    INSERT INTO pago (pedido_id, metodo_pago_id, moneda_id, monto, estado,
                      referencia_externa)
    SELECT NULL, p_metodo_pago_id, m.id, p_monto, 'fallido', NULLIF(p_referencia, '')
    FROM moneda m WHERE m.es_base AND m.activo
    ORDER BY m.id LIMIT 1
    RETURNING id INTO v_pago_id;

    IF v_pago_id IS NULL THEN
        RAISE EXCEPTION 'No hay moneda base configurada para registrar el intento';
    END IF;

    INSERT INTO transaccion_pago (pago_id, tipo, estado, monto, respuesta_pasarela)
    VALUES (v_pago_id, 'autorizacion', 'fallida', p_monto,
            jsonb_build_object('simulado', true, 'tipo', 'tarjeta',
                               'motivo', p_motivo, 'cliente_id', p_cliente_id,
                               'carrito_id', p_carrito_id));
    RETURN v_pago_id;
END $$;

-- Solo el checkout del cliente registra intentos (y admin para pruebas)
REVOKE ALL ON FUNCTION fn_registrar_intento_pago_fallido(bigint, bigint, numeric, varchar, text, bigint) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION fn_registrar_intento_pago_fallido(bigint, bigint, numeric, varchar, text, bigint)
    TO grp_cliente, grp_administrador;

COMMIT;
