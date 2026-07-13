-- ============================================================
-- RetailMind - Script 16: Funciones y Triggers
-- 1) fecha_actualizacion automatica en todas las tablas que la tienen.
-- 2) Totales de cabecera calculados por trigger (la app NUNCA los escribe):
--    pedido, factura_venta, orden_compra, factura_compra, devolucion.
-- Depende de: 01..15
-- ============================================================

BEGIN;

-- ------------------------------------------------------------
-- 1) Trigger generico de fecha_actualizacion
-- ------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_touch_fecha_actualizacion()
RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    NEW.fecha_actualizacion := now();
    RETURN NEW;
END;
$$;

-- Se aplica dinamicamente a toda tabla de public con columna fecha_actualizacion.
DO $$
DECLARE
    t record;
BEGIN
    FOR t IN
        SELECT c.table_name
        FROM information_schema.columns c
        JOIN information_schema.tables tb
          ON tb.table_schema = c.table_schema AND tb.table_name = c.table_name
        WHERE c.table_schema = 'public'
          AND c.column_name = 'fecha_actualizacion'
          AND tb.table_type = 'BASE TABLE'
    LOOP
        EXECUTE format(
            'CREATE TRIGGER trg_%s_touch
             BEFORE UPDATE ON %I
             FOR EACH ROW EXECUTE FUNCTION fn_touch_fecha_actualizacion()',
            t.table_name, t.table_name
        );
    END LOOP;
END;
$$;

-- ------------------------------------------------------------
-- 2a) Totales de pedido
--     total = subtotal - monto_descuento + monto_impuesto + costo_envio
-- ------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_recalcular_total_pedido()
RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_pedido_id bigint := COALESCE(NEW.pedido_id, OLD.pedido_id);
BEGIN
    UPDATE pedido p
    SET subtotal       = COALESCE(d.suma_subtotal, 0),
        monto_impuesto = COALESCE(d.suma_impuesto, 0),
        total          = GREATEST(0, COALESCE(d.suma_subtotal, 0) - p.monto_descuento
                                     + COALESCE(d.suma_impuesto, 0) + p.costo_envio)
    FROM (
        SELECT SUM(subtotal - monto_descuento) AS suma_subtotal,
               SUM(monto_impuesto)             AS suma_impuesto
        FROM pedido_detalle
        WHERE pedido_id = v_pedido_id
    ) d
    WHERE p.id = v_pedido_id;
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_pedido_detalle_total
AFTER INSERT OR UPDATE OR DELETE ON pedido_detalle
FOR EACH ROW EXECUTE FUNCTION fn_recalcular_total_pedido();

-- Si cambian descuento o costo de envio en la cabecera, recalcular total.
CREATE OR REPLACE FUNCTION fn_recalcular_total_cabecera_pedido()
RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    NEW.total := GREATEST(0, NEW.subtotal - NEW.monto_descuento
                             + NEW.monto_impuesto + NEW.costo_envio);
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_pedido_total
BEFORE INSERT OR UPDATE OF subtotal, monto_descuento, monto_impuesto, costo_envio ON pedido
FOR EACH ROW EXECUTE FUNCTION fn_recalcular_total_cabecera_pedido();

-- ------------------------------------------------------------
-- 2b) Totales de factura_venta
-- ------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_recalcular_total_factura_venta()
RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_factura_id bigint := COALESCE(NEW.factura_venta_id, OLD.factura_venta_id);
BEGIN
    UPDATE factura_venta f
    SET subtotal        = COALESCE(d.suma_subtotal, 0),
        monto_descuento = COALESCE(d.suma_descuento, 0),
        monto_impuesto  = COALESCE(d.suma_impuesto, 0),
        total           = GREATEST(0, COALESCE(d.suma_subtotal, 0) - COALESCE(d.suma_descuento, 0)
                                      + COALESCE(d.suma_impuesto, 0))
    FROM (
        SELECT SUM(subtotal)        AS suma_subtotal,
               SUM(monto_descuento) AS suma_descuento,
               SUM(monto_impuesto)  AS suma_impuesto
        FROM factura_venta_detalle
        WHERE factura_venta_id = v_factura_id
    ) d
    WHERE f.id = v_factura_id;
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_factura_venta_detalle_total
AFTER INSERT OR UPDATE OR DELETE ON factura_venta_detalle
FOR EACH ROW EXECUTE FUNCTION fn_recalcular_total_factura_venta();

-- ------------------------------------------------------------
-- 2c) Totales de orden_compra
-- ------------------------------------------------------------
-- SECURITY DEFINER: recalcular los totales de cabecera es un invariante del
-- sistema. Corre con los privilegios del dueño para que CUALQUIER rol con
-- permiso de modificar orden_compra_detalle (compras al crear la orden, bodega
-- al actualizar cantidad_recibida en la recepcion) dispare el recalculo sin
-- necesitar UPDATE sobre las columnas de montos de orden_compra (que la regla 1
-- reserva a los triggers). search_path fijo por seguridad.
CREATE OR REPLACE FUNCTION fn_recalcular_total_orden_compra()
RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_orden_id bigint := COALESCE(NEW.orden_compra_id, OLD.orden_compra_id);
BEGIN
    UPDATE orden_compra o
    SET subtotal       = COALESCE(d.suma_subtotal, 0),
        monto_impuesto = COALESCE(d.suma_impuesto, 0),
        total          = COALESCE(d.suma_subtotal, 0) + COALESCE(d.suma_impuesto, 0)
    FROM (
        SELECT SUM(subtotal)       AS suma_subtotal,
               SUM(monto_impuesto) AS suma_impuesto
        FROM orden_compra_detalle
        WHERE orden_compra_id = v_orden_id
    ) d
    WHERE o.id = v_orden_id;
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_orden_compra_detalle_total
AFTER INSERT OR UPDATE OR DELETE ON orden_compra_detalle
FOR EACH ROW EXECUTE FUNCTION fn_recalcular_total_orden_compra();

-- ------------------------------------------------------------
-- 2d) Totales de factura_compra
-- ------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_recalcular_total_factura_compra()
RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_factura_id bigint := COALESCE(NEW.factura_compra_id, OLD.factura_compra_id);
BEGIN
    UPDATE factura_compra f
    SET subtotal       = COALESCE(d.suma_subtotal, 0),
        monto_impuesto = COALESCE(d.suma_impuesto, 0),
        total          = COALESCE(d.suma_subtotal, 0) + COALESCE(d.suma_impuesto, 0)
    FROM (
        SELECT SUM(subtotal)       AS suma_subtotal,
               SUM(monto_impuesto) AS suma_impuesto
        FROM factura_compra_detalle
        WHERE factura_compra_id = v_factura_id
    ) d
    WHERE f.id = v_factura_id;
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_factura_compra_detalle_total
AFTER INSERT OR UPDATE OR DELETE ON factura_compra_detalle
FOR EACH ROW EXECUTE FUNCTION fn_recalcular_total_factura_compra();

-- ------------------------------------------------------------
-- 2e) Total de devolucion: cantidad devuelta * precio del detalle original
--     (proporcional al descuento de la linea).
-- ------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_recalcular_total_devolucion()
RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_devolucion_id bigint := COALESCE(NEW.devolucion_id, OLD.devolucion_id);
BEGIN
    UPDATE devolucion dv
    SET monto_total = COALESCE(d.suma, 0)
    FROM (
        SELECT SUM(dd.cantidad * (pd.precio_unitario
                                  - (pd.monto_descuento / pd.cantidad))) AS suma
        FROM devolucion_detalle dd
        JOIN pedido_detalle pd ON pd.id = dd.pedido_detalle_id
        WHERE dd.devolucion_id = v_devolucion_id
    ) d
    WHERE dv.id = v_devolucion_id;
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_devolucion_detalle_total
AFTER INSERT OR UPDATE OR DELETE ON devolucion_detalle
FOR EACH ROW EXECUTE FUNCTION fn_recalcular_total_devolucion();

COMMIT;
