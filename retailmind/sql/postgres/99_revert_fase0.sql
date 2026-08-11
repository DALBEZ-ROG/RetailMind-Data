-- ============================================================================
-- 99_revert_fase0.sql — RetailMind · reversion COMPLETA de la Fase 0 de la
--                       carga masiva (scripts 93, 94, 95 y 96)
--
--   docker compose exec -T postgres psql -U postgres -d retailmind \
--       -v ON_ERROR_STOP=1 < retailmind/sql/postgres/99_revert_fase0.sql
--
--   Ensayo en seco (no borra nada, deja el estado intacto):
--   docker compose exec -T postgres psql -U postgres -d retailmind \
--       -v ON_ERROR_STOP=1 -v ensayo=1 < retailmind/sql/postgres/99_revert_fase0.sql
--
-- ---------------------------------------------------------------------------
-- QUE BORRA, Y POR QUE PUEDE HACERLO SIN MIEDO
-- ---------------------------------------------------------------------------
-- Todo lo que escribio la Fase 0 vive en ids >= 900.000.000 (ver el script 92),
-- asi que la reversion es una condicion sobre la clave primaria y no una
-- busqueda de marcadores en texto libre. Eso importa: el marcador `[SEED-*]`
-- de `movimiento_inventario.observacion` —el mecanismo que habia antes— deja
-- 254 filas sin marca, y un borrado guiado por el se dejaria atras justo lo
-- que no ve. Aqui no hay fila de la fase que pueda caer fuera del rango ni
-- fila ajena que pueda caer dentro: el maximo id previo del sistema es
-- `pedido.id = 4218`.
--
-- ORDEN DE BORRADO: al reves de las dependencias. Ninguna clave foranea de
-- estas tablas apunta hacia arriba en la lista, y por eso no hace falta
-- diferir restricciones ni desactivar nada.
--
-- LO QUE NO BORRA, A PROPOSITO:
--   · Las tablas `carga_fase_registro` y `carga_fase_parametro` y la funcion
--     `fn_carga_registrar` (script 92). Son la INFRAESTRUCTURA de la carga
--     entera, compartida con las fases 1-3; borrarlas aqui dejaria a las fases
--     siguientes sin bitacora ni parametros. Se vacian sus filas de `fase0` y
--     las tablas se quedan. Para eliminarlas tambien —solo si se abandona la
--     carga masiva por completo— hay dos lineas comentadas al final.
--   · Cualquier usuario, cliente, producto o proveedor previo. Los 89 usuarios,
--     72 clientes, 1.214 productos y 11 proveedores originales quedan intactos.
--
-- SE AUTOVERIFICA: al terminar comprueba que NO queda ni una fila con id
-- >= 900.000.000 en las 21 tablas, que el kardex sigue cuadrado posicion por
-- posicion y que las tablas de venta no se movieron. Si algo falla, ABORTA y
-- la transaccion entera se va abajo.
--
-- IDEMPOTENTE: ejecutarlo dos veces es inofensivo (la segunda no borra nada).
-- TRANSACCIONAL: o se deshace todo, o no se deshace nada.
-- ============================================================================
\set ON_ERROR_STOP on
\if :{?ensayo}
\else
  \set ensayo 0
\endif

BEGIN;

-- ── 0. Foto previa, para poder informar de lo que se llevo por delante ──────
CREATE TEMP TABLE rev_antes ON COMMIT DROP AS
SELECT 'pedido' t, count(*) n FROM pedido
UNION ALL SELECT 'pedido_detalle', count(*) FROM pedido_detalle
UNION ALL SELECT 'factura_venta', count(*) FROM factura_venta
UNION ALL SELECT 'pago', count(*) FROM pago
UNION ALL SELECT 'grupo_horario', count(*) FROM grupo_horario;

-- ── 1. Borrado, al reves de las dependencias ────────────────────────────────
DELETE FROM producto_proveedor       WHERE id >= 900000000;
DELETE FROM cuenta_por_pagar         WHERE id >= 900000000;
DELETE FROM factura_compra_detalle   WHERE id >= 900000000;
DELETE FROM factura_compra           WHERE id >= 900000000;
DELETE FROM movimiento_inventario    WHERE id >= 900000000;
DELETE FROM inventario               WHERE id >= 900000000;
DELETE FROM recepcion_detalle        WHERE id >= 900000000;
DELETE FROM recepcion_mercancia      WHERE id >= 900000000;
DELETE FROM orden_compra_detalle     WHERE id >= 900000000;
DELETE FROM orden_compra             WHERE id >= 900000000;
DELETE FROM producto_categoria       WHERE id >= 900000000;
DELETE FROM producto_variante        WHERE id >= 900000000;
DELETE FROM producto                 WHERE id >= 900000000;
DELETE FROM direccion                WHERE id >= 900000000;
DELETE FROM cliente                  WHERE id >= 900000000;
DELETE FROM usuario_rol              WHERE id >= 900000000;
DELETE FROM usuario                  WHERE id >= 900000000;
-- `categoria` y `proveedor` usan la base ESTRECHA (60.000) porque el almacen
-- declara sus ids como UInt16: ver el bloque «DOS BASES» del script 92.
DELETE FROM proveedor                WHERE id >= 60000;
DELETE FROM categoria                WHERE id >= 60000;
DELETE FROM marca                    WHERE id >= 900000000;

DELETE FROM carga_fase_registro  WHERE fase = 'fase0';
DELETE FROM carga_fase_parametro WHERE fase = 'fase0';

-- ── 2. Autoverificacion. Si algo no cuadra, ABORTA. ─────────────────────────
DO $verif$
DECLARE
    t text; n bigint; restos text := '';
    v_pos bigint; v_cuad bigint; v_rotos bigint;
BEGIN
    FOREACH t IN ARRAY ARRAY['marca','categoria','proveedor','usuario','cliente','direccion',
                             'producto','producto_variante','producto_categoria','inventario',
                             'movimiento_inventario','orden_compra','orden_compra_detalle',
                             'recepcion_mercancia','recepcion_detalle','factura_compra',
                             'factura_compra_detalle','cuenta_por_pagar','pago_proveedor',
                             'producto_proveedor','usuario_rol']
    LOOP
        EXECUTE format('SELECT count(*) FROM public.%I WHERE id >= %s', t,
                       CASE WHEN t IN ('categoria','proveedor') THEN 60000 ELSE 900000000 END) INTO n;
        IF n > 0 THEN restos := restos || format('  %s: %s%s', t, n, E'\n'); END IF;
    END LOOP;
    IF restos <> '' THEN
        RAISE EXCEPTION 'ABORTA: quedan filas de la Fase 0 sin borrar:%', E'\n' || restos;
    END IF;

    -- El kardex tiene que seguir cuadrando posicion por posicion.
    SELECT count(*), count(*) FILTER (WHERE i.stock_actual = COALESCE(k.saldo, 0))
      INTO v_pos, v_cuad
    FROM inventario i
    LEFT JOIN (SELECT mi.producto_variante_id v, mi.bodega_id b,
                      sum(mi.cantidad * tm.factor) saldo
               FROM movimiento_inventario mi
               JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id
               GROUP BY 1, 2) k
           ON k.v = i.producto_variante_id AND k.b = i.bodega_id;
    IF v_pos <> v_cuad THEN
        RAISE EXCEPTION 'ABORTA: el kardex quedo descuadrado tras revertir (% de % posiciones).',
                        v_cuad, v_pos;
    END IF;

    -- …y el encadenamiento tiene que seguir intacto.
    SELECT count(*) INTO v_rotos FROM (
        SELECT lag(stock_nuevo) OVER (PARTITION BY producto_variante_id, bodega_id
                                      ORDER BY fecha_creacion, id) prev,
               stock_anterior,
               row_number() OVER (PARTITION BY producto_variante_id, bodega_id
                                  ORDER BY fecha_creacion, id) rn
        FROM movimiento_inventario) z
    WHERE rn > 1 AND prev <> stock_anterior;
    IF v_rotos > 0 THEN
        RAISE EXCEPTION 'ABORTA: % enlaces rotos en el kardex tras revertir.', v_rotos;
    END IF;

    RAISE NOTICE 'Reversion verificada: 0 filas residuales, % de % posiciones cuadradas, 0 enlaces rotos.',
                 v_cuad, v_pos;
END
$verif$;

-- ── 3. Las tablas de venta no se tocaron ────────────────────────────────────
DO $venta$
DECLARE d text := '';
BEGIN
    SELECT string_agg(format('  %s: %s -> %s', a.t, a.n, b.n), E'\n')
      INTO d
    FROM rev_antes a
    JOIN (SELECT 'pedido' t, count(*) n FROM pedido
          UNION ALL SELECT 'pedido_detalle', count(*) FROM pedido_detalle
          UNION ALL SELECT 'factura_venta', count(*) FROM factura_venta
          UNION ALL SELECT 'pago', count(*) FROM pago
          UNION ALL SELECT 'grupo_horario', count(*) FROM grupo_horario) b ON b.t = a.t
    WHERE a.n <> b.n;
    IF d IS NOT NULL THEN
        RAISE EXCEPTION 'ABORTA: la reversion movio tablas que no debia:%', E'\n' || d;
    END IF;
    RAISE NOTICE 'Tablas de venta y grupo_horario intactas.';
END
$venta$;

\if :ensayo
ROLLBACK;
\echo ''
\echo 'ENSAYO EN SECO: la reversion se ejecuto y se VERIFICO, y despues se revirtio.'
\echo 'La base queda EXACTAMENTE como estaba.'
\else
COMMIT;
\echo ''
\echo 'FASE 0 REVERTIDA. La base vuelve al estado previo al script 93.'
\endif

-- ---------------------------------------------------------------------------
-- Solo si se ABANDONA la carga masiva entera (las fases 1-3 tambien): esto
-- retira la infraestructura de procedencia que instalo el script 92.
--   DROP FUNCTION IF EXISTS public.fn_carga_registrar(text,text,text,bigint,bigint,bigint,text);
--   DROP TABLE IF EXISTS public.carga_fase_registro, public.carga_fase_parametro;
-- ---------------------------------------------------------------------------

\echo ''
SELECT 'cliente' t, count(*) n FROM cliente
UNION ALL SELECT 'usuario', count(*) FROM usuario
UNION ALL SELECT 'producto_variante', count(*) FROM producto_variante
UNION ALL SELECT 'proveedor', count(*) FROM proveedor
UNION ALL SELECT 'inventario', count(*) FROM inventario
UNION ALL SELECT 'movimiento_inventario', count(*) FROM movimiento_inventario
UNION ALL SELECT 'pedido', count(*) FROM pedido ORDER BY 1;
