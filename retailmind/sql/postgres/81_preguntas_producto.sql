-- ============================================================================
-- 81_preguntas_producto.sql
-- OBJETIVO TACTICO OTD-VEN-10 (cola de moderacion), mitad de PREGUNTAS.
-- Seccion 8 de docs/tactico/AUDITORIA_TRANSVERSAL_SEED.md.
--
-- ANTES: pregunta_producto con 1 fila y respuesta_pregunta con 1. Las resenas
-- si se sembraron (Bloque B, 53 pendientes); las preguntas de producto, no.
--
-- Que hace (replicando ResenasService, que es quien opera estas dos tablas):
--   * El CLIENTE pregunta -> la fila nace 'pendiente', moderado_por NULL.
--   * Responder una pendiente IMPLICA aprobar: se inserta respuesta_pregunta
--     (usuario_id del personal, es_oficial = true) y la pregunta pasa a
--     'publicada' con moderado_por + fecha_moderacion del que respondio.
--   * Moderar sin responder (publicar o rechazar) tambien fija moderado_por
--     + fecha_moderacion.
--   * Cada transicion de estado deja su fila en log_auditoria con el MISMO
--     formato que AuditoriaService.registrar: accion 'UPDATE',
--     tabla 'pregunta_producto', datos_anteriores {"estado": "..."} y
--     datos_nuevos {"estado": "..."}. Es lo unico que el sistema real audita
--     de este flujo (transferencias, ajustes, marketing y metas NO se auditan,
--     por eso ningun otro script de este bloque escribe log_auditoria).
--
-- Reparto: preguntas sobre productos CON VENTA, de clientes reales, entre
-- junio-2025 y julio-2026. Una parte queda SIN responder a proposito para que
-- la cola de moderacion muestre trabajo pendiente.
--
-- Marca 'seed_op_81_preguntas'. Idempotente y transaccional.
-- Ejecutar como postgres sobre la BD retailmind.
-- ============================================================================
\set ON_ERROR_STOP on

BEGIN;

DO $$
DECLARE
    v_preg text[] := ARRAY[
        'Manejan descuento por volumen si llevo mas de 20 unidades?',
        'Cual es el tiempo de entrega a Quevedo si compro hoy?',
        'Tienen stock disponible para entrega inmediata?',
        'El producto viene con factura a nombre de empresa?',
        'Que garantia tiene y como se hace efectiva?',
        'Puedo pedir una muestra antes de la compra grande?',
        'Manejan mas colores o presentaciones de este mismo producto?',
        'El precio publicado ya incluye IVA?',
        'Hacen envios a otras provincias? Cual seria el costo?',
        'Si compro por mayor, el precio unitario baja?',
        'De que material esta hecho? Necesito la ficha tecnica.',
        'Puedo pagar con transferencia bancaria en lugar de tarjeta?',
        'Cuanto pesa la caja? Lo necesito para calcular el flete.',
        'Aceptan devolucion si el producto no rota en mi local?',
        'Tienen presentacion en caja sellada para reventa?',
        'Cada cuanto reponen stock de este articulo?',
        'Puedo retirar en bodega en vez de pagar envio?',
        'El empaque trae codigo de barras para punto de venta?',
        'Cual es la cantidad minima de compra al por mayor?',
        'Este modelo reemplaza al anterior o son distintos?'
    ];
    v_resp text[] := ARRAY[
        'Si, desde 20 unidades aplicamos precio de mayorista. Escribanos y le enviamos la cotizacion.',
        'Para Quevedo la entrega es de 1 a 2 dias habiles una vez confirmado el pago.',
        'Si, hay stock disponible en Bodega Central. La reserva se confirma al pagar el pedido.',
        'Claro, la factura se emite con los datos que registre en su perfil de facturacion.',
        'Tiene garantia del fabricante; se gestiona con su numero de factura desde Soporte.',
        'Podemos coordinar una muestra para pedidos mayoristas. Abranos un ticket de soporte.',
        'Si, revise las variantes del producto en la ficha: ahi aparecen los colores disponibles.',
        'El precio mostrado es sin IVA; el impuesto se calcula en el detalle del pedido.',
        'Si enviamos a todo el pais. El costo se calcula por zona y peso al finalizar la compra.',
        'Correcto, el precio unitario baja por escalas de cantidad. Le pasamos la tabla por correo.',
        'Le compartimos la ficha tecnica por correo; indiquenos su direccion en un ticket.',
        'Si, aceptamos transferencia bancaria. La opcion aparece en el checkout.',
        'El peso figura en la ficha del producto y se usa para calcular el flete automaticamente.',
        'Aceptamos devolucion dentro de los 30 dias posteriores a la entrega, en su empaque original.',
        'Si, para reventa despachamos en caja sellada sin abrir.',
        'Reponemos este articulo mensualmente; si esta agotado le avisamos apenas ingrese.',
        'Si, puede retirar en Bodega Central Quevedo en horario de oficina, sin costo de envio.',
        'Si, el empaque trae codigo de barras compatible con lectores de punto de venta.',
        'El minimo mayorista es de 12 unidades por referencia.',
        'Son modelos distintos; este es el vigente y el anterior sale de catalogo al agotarse.'
    ];
    v_staff bigint[] := ARRAY[12, 18, 19, 20, 7, 13, 2];  -- soporte + vendedores + admin
    v_seq   int := 0;
    v_i     int;
    v_r     bigint;
    v_fecha timestamptz;
    v_fmod  timestamptz;
    v_prod  bigint;
    v_cli   bigint;
    v_user  bigint;
    v_estado text;
    v_id    bigint;
    v_idx   int;
    v_n_pub int := 0;
    v_n_pen int := 0;
    v_n_rec int := 0;
    v_n_res int := 0;
    v_n_log int := 0;
BEGIN
    IF EXISTS (SELECT 1 FROM configuracion_tienda WHERE clave = 'seed_op_81_preguntas') THEN
        RAISE NOTICE 'Objetivo 2 (preguntas de producto) ya sembrado; se omite.';
        RETURN;
    END IF;

    FOR v_i IN 1..48 LOOP
        v_seq := v_seq + 1;
        v_r := ('x' || substr(md5('q' || v_seq::text), 1, 8))::bit(32)::bigint & 2147483647;

        -- reparto entre junio-2025 y julio-2026 (14 meses), creciendo con el trafico
        v_fecha := timestamptz '2025-06-01 00:00:00-05'
                 + (((v_seq - 1) * 415 / 48) || ' day')::interval
                 + ((v_r % 24) || ' hour')::interval
                 + ((v_r / 7 % 60) || ' minute')::interval
                 + ((v_r / 11 % 60) || ' second')::interval;

        -- producto CON VENTA y cliente reales (deterministico por seq)
        SELECT pv.producto_id INTO v_prod
        FROM (SELECT DISTINCT pv2.producto_id FROM pedido_detalle pd
              JOIN producto_variante pv2 ON pv2.id = pd.producto_variante_id) pv
        JOIN producto p ON p.id = pv.producto_id AND p.activo
        ORDER BY md5(pv.producto_id::text || '#' || v_seq::text) LIMIT 1;

        SELECT c.id INTO v_cli FROM cliente c WHERE c.activo
        ORDER BY md5(c.id::text || '@' || v_seq::text) LIMIT 1;

        -- 28 publicadas con respuesta | 13 pendientes | 3 publicadas sin respuesta | 4 rechazadas
        v_estado := CASE
            WHEN v_i <= 28 THEN 'respondida'
            WHEN v_i <= 41 THEN 'pendiente'
            WHEN v_i <= 44 THEN 'publicada'
            ELSE 'rechazada' END;

        -- las pendientes son las MAS RECIENTES (cola viva de moderacion)
        IF v_estado = 'pendiente' THEN
            v_fecha := timestamptz '2026-06-05 00:00:00-05'
                     + (((v_i - 29) * 3) || ' day')::interval
                     + ((v_r % 24) || ' hour')::interval
                     + ((v_r / 7 % 60) || ' minute')::interval;
        END IF;

        v_user := v_staff[1 + (v_r % array_length(v_staff, 1))];
        v_idx  := 1 + (v_r % array_length(v_preg, 1));
        v_fmod := v_fecha + ((2 + (v_r % 46)) || ' hour')::interval;

        INSERT INTO pregunta_producto
            (producto_id, cliente_id, pregunta, estado, fecha_creacion,
             fecha_actualizacion, moderado_por, fecha_moderacion)
        VALUES (v_prod, v_cli, v_preg[v_idx],
                CASE v_estado WHEN 'respondida' THEN 'publicada' ELSE v_estado END,
                v_fecha,
                CASE WHEN v_estado <> 'pendiente' THEN v_fmod END,
                CASE WHEN v_estado <> 'pendiente' THEN v_user END,
                CASE WHEN v_estado <> 'pendiente' THEN v_fmod END)
        RETURNING id INTO v_id;

        IF v_estado = 'respondida' THEN
            INSERT INTO respuesta_pregunta
                (pregunta_producto_id, usuario_id, cliente_id, respuesta, es_oficial,
                 fecha_creacion)
            VALUES (v_id, v_user, NULL, v_resp[v_idx], true, v_fmod);
            v_n_res := v_n_res + 1;
            v_n_pub := v_n_pub + 1;
        ELSIF v_estado = 'publicada' THEN
            v_n_pub := v_n_pub + 1;
        ELSIF v_estado = 'rechazada' THEN
            v_n_rec := v_n_rec + 1;
        ELSE
            v_n_pen := v_n_pen + 1;
        END IF;

        -- rastro de auditoria de la transicion (formato AuditoriaService)
        IF v_estado <> 'pendiente' THEN
            INSERT INTO log_auditoria
                (usuario_id, tabla, registro_id, accion, datos_anteriores, datos_nuevos,
                 fecha_creacion)
            VALUES (v_user, 'pregunta_producto', v_id, 'UPDATE',
                    jsonb_build_object('estado', 'pendiente'),
                    jsonb_build_object('estado',
                        CASE v_estado WHEN 'respondida' THEN 'publicada' ELSE v_estado END),
                    v_fmod);
            v_n_log := v_n_log + 1;
        END IF;
    END LOOP;

    INSERT INTO configuracion_tienda (clave, valor, tipo_dato, descripcion)
    VALUES ('seed_op_81_preguntas',
            jsonb_build_object('fecha', now(), 'pregunta_producto', v_seq,
                               'publicadas', v_n_pub, 'pendientes', v_n_pen,
                               'rechazadas', v_n_rec, 'respuesta_pregunta', v_n_res,
                               'log_auditoria', v_n_log)::text,
            'json', 'OTD-VEN-10 (preguntas de producto) — script 81')
    ON CONFLICT (clave) DO UPDATE SET valor = EXCLUDED.valor;

    RAISE NOTICE 'Preguntas: % (publicadas %, pendientes %, rechazadas %), respuestas: %, log_auditoria: %',
                 v_seq, v_n_pub, v_n_pen, v_n_rec, v_n_res, v_n_log;
END $$;

COMMIT;

\echo '--- OTD-VEN-10: preguntas por estado ---'
SELECT q.estado, count(*) preguntas,
       count(*) FILTER (WHERE r.id IS NULL) sin_responder,
       min(q.fecha_creacion)::date desde, max(q.fecha_creacion)::date hasta
FROM pregunta_producto q
LEFT JOIN respuesta_pregunta r ON r.pregunta_producto_id = q.id
GROUP BY 1 ORDER BY 1;

\echo '--- Cola viva (pendientes de moderar/responder) ---'
SELECT count(*) AS pendientes_de_responder
FROM pregunta_producto q
WHERE NOT EXISTS (SELECT 1 FROM respuesta_pregunta r WHERE r.pregunta_producto_id = q.id)
  AND q.estado <> 'rechazada';
