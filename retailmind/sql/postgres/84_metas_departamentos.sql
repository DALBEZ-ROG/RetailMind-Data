-- ============================================================================
-- 84_metas_departamentos.sql
-- OBJETIVO TACTICO OTD-VEN-15 (cumplimiento contra meta), refuerzo por
-- DEPARTAMENTO. Tambien ataca la mitad "solo 2 de 7 departamentos" del
-- hallazgo M12 y el B8. Seccion 8 de
-- docs/tactico/AUDITORIA_TRANSVERSAL_SEED.md.
--
-- ANTES: 38 metas, solo 'general' y 'ventas' (script 66), de los 7
-- departamentos que admite el CHECK de meta_venta / la lista blanca de
-- MetasVentaService.
--
-- Que hace: agrega los 5 departamentos faltantes (compras, inventario,
-- logistica, soporte, marketing) para los 19 meses ene-2025 .. jul-2026= 95 metas.
-- No toca ni una de las 38 metas existentes.
--
-- DERIVACION DEL MONTO (cada meta sale de una metrica REAL del area, para que
-- el monto sea coherente con su actividad y varie mes a mes como varia el
-- negocio; el detalle queda escrito en meta_venta.notas):
--   compras     = facturado de COMPRA del mes (factura_compra.total por fecha_emision)
--   inventario  = valor de la mercaderia INGRESADA al almacen en el mes
--                 (cantidad x costo de las entradas de kardex), EXCLUYENDO el
--                 asiento de apertura ('inventario_inicial'), que no es gestion
--   logistica   = valor de los pedidos ENTREGADOS en el mes (envio.fecha_entrega_real)
--   soporte     = 8 % del facturado de venta del mes (venta asociada a clientes
--                 atendidos: proxy declarado, no una metrica propia del area)
--   marketing   = 15 % del facturado de venta del mes (venta atribuible a
--                 campana: proxy declarado)
-- Sobre esa base se aplica un FACTOR DE AMBICION por mes en [0,78 ; 1,22],
-- deliberadamente MAS ANCHO que el +/-10 % del script 66: asi el informe por
-- departamento tiene meses claramente fallados y meses claramente superados,
-- que es justo lo que M12 senalaba que faltaba.
--
-- LIMITACION CONOCIDA (del backend, no del dato): MetasVentaService calcula el
-- avance (venta_real) SOLO para 'general' y 'ventas' — el CASE de
-- VENTA_REAL_SQL deja NULL en los demas departamentos. Estas 95 metas pueblan
-- el catalogo de metas por departamento; medir su avance exigiria ampliar ese
-- CASE, que es cambio de codigo y queda fuera de este alcance.
--
-- Autor (fijada_por): admin (id 2) para compras/inventario/logistica y gerente
-- (id 6) para soporte/marketing — ambos de Administracion/Gerencia.
--
-- Marca 'seed_op_84_metas_departamentos'. Idempotente y transaccional.
-- Ejecutar como postgres sobre la BD retailmind.
-- ============================================================================
\set ON_ERROR_STOP on

BEGIN;

DO $$
DECLARE
    -- factor meta/real por mes (idx 1 = ene-2025 .. idx 19 = jul-2026)
    f_com numeric[] := ARRAY[0.88,1.14,0.95,1.21,0.82,1.06,0.91,1.18,0.79,1.09,0.97,1.15,0.85,1.02,1.22,0.90,1.11,0.83,1.04];
    f_inv numeric[] := ARRAY[1.12,0.86,1.19,0.93,1.07,0.80,1.16,0.98,1.03,0.87,1.20,0.94,1.10,0.81,0.99,1.17,0.89,1.05,0.92];
    f_log numeric[] := ARRAY[0.94,1.08,0.84,1.13,1.00,0.90,1.22,0.87,1.10,0.96,0.79,1.19,0.93,1.16,0.86,1.01,0.95,1.20,0.88];
    f_sop numeric[] := ARRAY[1.05,0.91,1.16,0.85,1.20,0.98,0.83,1.12,0.94,1.21,0.88,1.02,0.80,1.09,1.14,0.92,1.18,0.96,1.07];
    f_mkt numeric[] := ARRAY[0.81,1.17,1.01,0.89,1.13,1.22,0.96,0.84,1.19,0.92,1.06,0.86,1.15,0.97,0.82,1.10,0.99,1.21,0.90];
    v_idx  int := 0;
    y int; mo int;
    v_ini date; v_fin date;
    v_compra numeric; v_entradas numeric; v_entregado numeric; v_facturado numeric;
    v_n int := 0;
BEGIN
    IF EXISTS (SELECT 1 FROM configuracion_tienda WHERE clave = 'seed_op_84_metas_departamentos') THEN
        RAISE NOTICE 'Objetivo 6 (metas por departamento) ya sembrado; se omite.';
        RETURN;
    END IF;

    FOR y IN 2025..2026 LOOP
      FOR mo IN 1..12 LOOP
        EXIT WHEN (y = 2026 AND mo > 7);
        v_idx := v_idx + 1;
        v_ini := make_date(y, mo, 1);
        v_fin := v_ini + interval '1 month';

        SELECT COALESCE(sum(total), 0) INTO v_compra
        FROM factura_compra WHERE fecha_emision >= v_ini AND fecha_emision < v_fin;

        SELECT COALESCE(sum(m.cantidad * COALESCE(m.costo_unitario, pv.costo)), 0)
          INTO v_entradas
        FROM movimiento_inventario m
        JOIN tipo_movimiento tm ON tm.id = m.tipo_movimiento_id
        JOIN producto_variante pv ON pv.id = m.producto_variante_id
        WHERE tm.factor = 1
          AND COALESCE(m.referencia_tipo, '') <> 'inventario_inicial'
          AND m.fecha_creacion >= v_ini AND m.fecha_creacion < v_fin;

        SELECT COALESCE(sum(p.total), 0) INTO v_entregado
        FROM envio e JOIN pedido p ON p.id = e.pedido_id
        WHERE e.fecha_entrega_real >= v_ini AND e.fecha_entrega_real < v_fin;

        SELECT COALESCE(sum(fv.total), 0) INTO v_facturado
        FROM factura_venta fv
        WHERE fv.estado <> 'anulada'
          AND fv.fecha_emision >= v_ini AND fv.fecha_emision < v_fin;

        INSERT INTO meta_venta (anio, mes, departamento, monto_meta, notas, fijada_por,
                                activo, fecha_creacion)
        VALUES
          (y, mo, 'compras',
           GREATEST(round(v_compra * f_com[v_idx], -2), 1000),
           '[SEED-OP] Meta de compra del mes. Base: facturado a proveedores ('
             || to_char(v_compra, 'FM999G999G999D00') || '), factor ' || f_com[v_idx] || '.',
           2, true, (v_ini - 3)::timestamptz + interval '9 hour'),
          (y, mo, 'inventario',
           GREATEST(round(v_entradas * f_inv[v_idx], -2), 1000),
           '[SEED-OP] Meta de ingreso a almacen del mes. Base: valor de las entradas de kardex sin apertura ('
             || to_char(v_entradas, 'FM999G999G999D00') || '), factor ' || f_inv[v_idx] || '.',
           2, true, (v_ini - 3)::timestamptz + interval '9 hour'),
          (y, mo, 'logistica',
           GREATEST(round(v_entregado * f_log[v_idx], -2), 1000),
           '[SEED-OP] Meta de entrega del mes. Base: valor de los pedidos entregados ('
             || to_char(v_entregado, 'FM999G999G999D00') || '), factor ' || f_log[v_idx] || '.',
           2, true, (v_ini - 3)::timestamptz + interval '9 hour'),
          (y, mo, 'soporte',
           GREATEST(round(v_facturado * 0.08 * f_sop[v_idx], -2), 1000),
           '[SEED-OP] Meta de venta atendida por soporte. Base: 8% del facturado del mes ('
             || to_char(v_facturado, 'FM999G999G999D00') || '), factor ' || f_sop[v_idx] || '.',
           6, true, (v_ini - 3)::timestamptz + interval '9 hour'),
          (y, mo, 'marketing',
           GREATEST(round(v_facturado * 0.15 * f_mkt[v_idx], -2), 1000),
           '[SEED-OP] Meta de venta atribuida a campana. Base: 15% del facturado del mes ('
             || to_char(v_facturado, 'FM999G999G999D00') || '), factor ' || f_mkt[v_idx] || '.',
           6, true, (v_ini - 3)::timestamptz + interval '9 hour');
        v_n := v_n + 5;
      END LOOP;
    END LOOP;

    INSERT INTO configuracion_tienda (clave, valor, tipo_dato, descripcion)
    VALUES ('seed_op_84_metas_departamentos',
            jsonb_build_object('fecha', now(), 'meta_venta', v_n, 'meses', v_idx,
                               'departamentos_nuevos', 5)::text,
            'json', 'OTD-VEN-15 / M12 (metas por departamento) — script 84')
    ON CONFLICT (clave) DO UPDATE SET valor = EXCLUDED.valor;

    RAISE NOTICE 'meta_venta: % filas nuevas en % meses', v_n, v_idx;
END $$;

COMMIT;

\echo '--- OTD-VEN-15: metas por departamento ---'
SELECT departamento, count(*) metas, min(anio*100+mes) desde, max(anio*100+mes) hasta,
       round(min(monto_meta),2) meta_min, round(max(monto_meta),2) meta_max
FROM meta_venta GROUP BY 1 ORDER BY 1;

\echo '--- Ancho del cumplimiento en general/ventas vs. el factor de los nuevos ---'
SELECT departamento, round(min(monto_meta)/NULLIF(max(monto_meta),0), 3) razon_min_max
FROM meta_venta GROUP BY 1 ORDER BY 1;
