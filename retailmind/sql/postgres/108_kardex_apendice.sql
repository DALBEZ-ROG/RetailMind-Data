-- ============================================================================
-- 108_kardex_apendice.sql — RetailMind (2026-08-12)
--
-- PREVENCION: que una operacion hecha HOY desde la aplicacion no pueda volver
-- a romper el encadenamiento del kardex.
--
--   docker compose exec -T postgres psql -U postgres -d retailmind \
--       -v ON_ERROR_STOP=1 < retailmind/sql/postgres/108_kardex_apendice.sql
--
-- ---------------------------------------------------------------------------
-- EL PROBLEMA, EN UNA FRASE
-- ---------------------------------------------------------------------------
-- `inventario.stock_actual` es UN ESCALAR por posicion: el saldo al FINAL de
-- la cadena. Las dos rutas que escriben kardex —`StockService.mover` y la
-- recepcion de `ComprasService`— lo leen y lo copian a `stock_anterior`. Eso
-- solo es cierto si la fila nueva va AL FINAL de la cadena. Con el seed
-- sembrado hasta 2035-01, `now()` cae EN MITAD, y la fila nace con el saldo
-- del final de la cadena en un punto donde el saldo corrido es otro — y ademas
-- deja obsoleto todo lo posterior.
--
-- ---------------------------------------------------------------------------
-- LAS OPCIONES QUE SE EVALUARON, Y POR QUE SE DESCARTAN
-- ---------------------------------------------------------------------------
-- (a) PROHIBIR fecha anterior al ultimo movimiento de la posicion.
--     Descartada: TODAS las posiciones tienen movimientos sembrados hasta
--     2034-12. La aplicacion quedaria incapaz de mover una sola unidad. Deja
--     el kardex integro apagando el sistema.
--
-- (b) Que `StockService` calcule `stock_anterior` desde el saldo corrido EN
--     ESA FECHA. Descartada: arregla la FILA y no el ENLACE. Comprobado sobre
--     el caso real: aunque el movimiento 14440 hubiera escrito 58/57 —el saldo
--     corrido correcto—, el siguiente movimiento de esa posicion
--     (1300444789, con `stock_anterior` = 58) habria quedado igual de
--     obsoleto. La rotura se mueve una fila, no desaparece.
--
-- (c) Un trigger que valide el ENLACE ademas de la fila, y RECHACE.
--     Descartada como solucion unica: rechaza toda operacion de la aplicacion,
--     o sea (a) con otro mensaje. Se conserva, eso si, como RED (ver abajo).
--
-- (d) Reencadenar hacia adelante lo posterior tras cada insercion.
--     Descartada: reescribe historia en cada operacion y, sobre todo, PUEDE
--     FALLAR. Medido en el caso real: tras el recalculo, la cadena de la
--     bodega 4 toca un minimo de 0, asi que restarle una unidad la habria
--     dejado en -1 y el propio `movimiento_inventario_stock_nuevo_check`
--     habria abortado LA TRANSFERENCIA. Es decir: esta opcion habria hecho
--     fallar justo la operacion que se quiere permitir, y con un error opaco.
--
-- ---------------------------------------------------------------------------
-- LO QUE SE HACE  (el kardex es un APENDICE)
-- ---------------------------------------------------------------------------
-- Un BEFORE INSERT que, SOLO para lo que escribe la aplicacion, ancla
-- `fecha_creacion` al final de la cadena de esa posicion:
--
--     si el SEGUNDO de NEW.fecha_creacion <= el SEGUNDO de la ultima
--         entonces NEW.fecha_creacion := trunc_segundo(ultima) + 1 segundo
--
-- EL SALTO ES DE UN SEGUNDO, Y NO DE UN MICROSEGUNDO — esto no es un detalle
-- de estilo, es lo unico que hace que el almacen lea la cadena igual que
-- PostgreSQL. Se descubrio ejecutando el DAG: con el anclaje de 1 microsegundo
-- la reparacion quedaba perfecta en PostgreSQL y `fact_stock_mensual` ABORTABA
-- igual, con 11.404 de 11.406 posiciones cuadradas. La razon es que
-- `fact_movimiento_inventario.fecha` es un **DateTime de ClickHouse, con
-- resolucion de SEGUNDO**, y el cierre se calcula con
-- `argMax(stock_nuevo, (fecha, movimiento_id))`: al truncarse el microsegundo,
-- el movimiento de la aplicacion y el ultimo del seed caen en el MISMO
-- segundo, y el desempate pasa a ser el id — donde la aplicacion escribe
-- 14.442 y el seed 2.100.301.035. El almacen leia el movimiento nuevo ANTES
-- del viejo y publicaba el saldo anterior. Con el salto de un segundo los dos
-- ordenes coinciden por construccion, sin depender de los ids.
--
-- Con eso la fila nueva es, por construccion, la ULTIMA de su posicion: su
-- `stock_anterior` (= `inventario.stock_actual`) es exactamente el saldo que
-- le corresponde, y no queda nada posterior a lo que dejar obsoleto. El
-- invariante deja de depender de que la aplicacion acierte.
--
-- Se elige por SIMPLICIDAD y RIESGO BAJO:
--   * Cero lineas de Java. Cubre LAS DOS rutas de escritura a la vez —y
--     cualquiera futura— porque vive donde vive el dato.
--   * No puede hacer fallar una operacion legitima: no rechaza nada, y el
--     control de "stock insuficiente" de `StockService` sigue midiendo contra
--     `inventario.stock_actual`, que es el stock real de hoy.
--   * Es un NO-OP en cuanto el seed deje de ocupar el futuro: si el ultimo
--     movimiento de la posicion es anterior a `now()`, la fecha no se toca.
--     No es una distorsion permanente, es una guarda que solo actua mientras
--     la cabeza del libro este en el futuro.
--
-- Y se conserva (c) como RED, en la MISMA funcion: despues de anclar, se
-- comprueba que `stock_anterior` sea el `stock_nuevo` del ultimo movimiento
-- (o 0 si la posicion no tiene ninguno). Tras el anclaje esa comprobacion no
-- puede fallar en operacion normal; si algun dia falla, es que la posicion ya
-- venia descuadrada, y entonces se para con un mensaje claro en vez de
-- corromper la cadena en silencio — que es exactamente lo que dejo al ETL sin
-- publicar.
--
-- ---------------------------------------------------------------------------
-- ALCANCE, Y LO QUE CUESTA
-- ---------------------------------------------------------------------------
-- La guarda se aplica SOLO cuando `session_user = 'retailmind_app'`, o sea a
-- lo que escribe la aplicacion. El seed, el ETL y el mantenimiento corren como
-- `postgres` y conservan su `fecha_creacion` explicita: el metodo de
-- redensificacion de la carga masiva —insertar en el pasado con neto cero—
-- depende de poder hacerlo, y este script no se lo quita.
--
-- COSTE DECLARADO: mientras el seed llegue a 2035, el kardex de una operacion
-- real de hoy queda fechado al final de la cadena de su posicion y no a la
-- hora del reloj. No se pierde el dato: cada fila del kardex apunta a su
-- documento por (`referencia_tipo`, `referencia_id`) —la transferencia, la
-- recepcion, el pedido—, y ese documento SI conserva la hora real.
--
-- CONCURRENCIA: las dos rutas de escritura bloquean antes la fila de
-- `inventario` con SELECT ... FOR UPDATE, que serializa por posicion; la
-- lectura del ultimo movimiento que hace este trigger queda amparada por ese
-- mismo bloqueo.
-- ============================================================================

\timing on
\set ON_ERROR_STOP on

-- ---------------------------------------------------------------------------
-- 1. Indice que sostiene la lectura del trigger
-- ---------------------------------------------------------------------------
-- Sin el, "el ultimo movimiento de esta posicion" se resuelve recorriendo
-- hacia atras `idx_movimiento_inventario_fecha` y filtrando: 32 ms medidos en
-- la posicion afectada, y sin techo para una posicion cuyo ultimo movimiento
-- sea antiguo. Con el, es una sola bajada del arbol.
CREATE INDEX IF NOT EXISTS idx_movimiento_inventario_cadena
    ON public.movimiento_inventario (producto_variante_id, bodega_id, fecha_creacion, id);

-- ---------------------------------------------------------------------------
-- 2. La guarda
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.fn_kardex_apendice()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'pg_temp'
AS $function$
DECLARE
    v_ult_fecha timestamptz;
    v_ult_saldo integer;
    v_sku       text;
BEGIN
    -- Solo lo que escribe la APLICACION. El seed / ETL / mantenimiento corren
    -- como `postgres` y conservan su fecha explicita a proposito.
    IF session_user <> 'retailmind_app' THEN
        RETURN NEW;
    END IF;

    SELECT m.fecha_creacion, m.stock_nuevo
      INTO v_ult_fecha, v_ult_saldo
    FROM public.movimiento_inventario m
    WHERE m.producto_variante_id = NEW.producto_variante_id
      AND m.bodega_id            = NEW.bodega_id
    ORDER BY m.fecha_creacion DESC, m.id DESC
    LIMIT 1;

    IF v_ult_fecha IS NULL THEN
        -- Primera fila de la posicion: toda cadena arranca en 0.
        v_ult_saldo := 0;
    ELSIF date_trunc('second', NEW.fecha_creacion)
          <= date_trunc('second', v_ult_fecha) THEN
        -- ANCLAJE: la fila nueva pasa a ser la ultima de su cadena, y en un
        -- SEGUNDO estrictamente posterior — que es la resolucion con la que el
        -- almacen ordena. Con un microsegundo bastaria para PostgreSQL y NO
        -- para el DWH (ver la cabecera).
        NEW.fecha_creacion := date_trunc('second', v_ult_fecha) + interval '1 second';
    END IF;

    -- RED: tras el anclaje esto no puede fallar en operacion normal. Si falla,
    -- la posicion ya venia descuadrada y se para aqui, no mas adelante.
    IF NEW.stock_anterior IS DISTINCT FROM v_ult_saldo THEN
        SELECT sku INTO v_sku FROM public.producto_variante WHERE id = NEW.producto_variante_id;
        RAISE EXCEPTION
            'Kardex desencadenado: el SKU % en la bodega % cierra su ultimo movimiento en % y se intento escribir uno que parte de %.',
            coalesce(v_sku, NEW.producto_variante_id::text), NEW.bodega_id,
            v_ult_saldo, NEW.stock_anterior
            USING ERRCODE = 'check_violation',
                  HINT    = 'El saldo de la posicion no coincide con el cierre de su kardex. '
                            'Revise inventario.stock_actual de esa posicion antes de volver a mover stock.';
    END IF;

    RETURN NEW;
END;
$function$;

COMMENT ON FUNCTION public.fn_kardex_apendice() IS
    'Mantiene el kardex como APENDICE para lo que escribe la aplicacion: ancla '
    'fecha_creacion al final de la cadena de la posicion y verifica el ENLACE '
    'con el ultimo movimiento. No aplica a postgres (seed/ETL/mantenimiento).';

DROP TRIGGER IF EXISTS trg_kardex_apendice_ins ON public.movimiento_inventario;

-- Se llama `apendice` y no `zz_...` a proposito: los triggers de una misma
-- tabla disparan en orden ALFABETICO, y `apendice` < `ecuacion`, asi que la
-- fecha queda anclada antes de que `trg_kardex_ecuacion_ins` valide la fila.
CREATE TRIGGER trg_kardex_apendice_ins
    BEFORE INSERT ON public.movimiento_inventario
    FOR EACH ROW EXECUTE FUNCTION public.fn_kardex_apendice();

-- ---------------------------------------------------------------------------
-- 3. Normalizar lo que ya se escribio con un anclaje de microsegundo
-- ---------------------------------------------------------------------------
-- Empuja al segundo siguiente los movimientos que colisionan EN EL SEGUNDO con
-- el anterior de su posicion y ademas tienen un id MENOR — el par que el
-- almacen leeria al reves.
--
-- Solo se tocan los que son el ULTIMO movimiento de su posicion: ahi adelantar
-- la marca de tiempo no puede reordenar nada. Un par invertido EN MITAD de la
-- cadena NO se toca, porque empujarlo podria colocarlo por delante del
-- siguiente y romper el encadenamiento — y ademas es inofensivo: `argMax` toma
-- el maximo del MES, asi que solo importa si el par cierra el mes. Hoy existe
-- uno asi, sembrado, y queda declarado en el reporte sin tocarlo:
--     id 1200736356 tras 3300014980 — variante 900000222 / bodega 4,
--     2025-12-18 21:22:58, a mitad de diciembre.
--
-- Idempotente: en una segunda corrida ya no hay nada que empujar.
BEGIN;

WITH ord AS (
    SELECT id, producto_variante_id, bodega_id, fecha_creacion,
           lag(id)             OVER w AS prev_id,
           lag(fecha_creacion) OVER w AS prev_fecha,
           lead(id)            OVER w AS next_id
    FROM public.movimiento_inventario
    WINDOW w AS (PARTITION BY producto_variante_id, bodega_id
                 ORDER BY fecha_creacion, id)
),
a_empujar AS (
    SELECT id, date_trunc('second', prev_fecha) + interval '1 second' AS fecha_nueva
    FROM ord
    WHERE prev_id IS NOT NULL
      AND next_id IS NULL                                   -- ultimo de su posicion
      AND id < prev_id                                      -- el id retrocede
      AND date_trunc('second', fecha_creacion) = date_trunc('second', prev_fecha)
)
UPDATE public.movimiento_inventario m
SET fecha_creacion = a.fecha_nueva
FROM a_empujar a
WHERE m.id = a.id;

-- Verificacion: la cadena no se movio y ya no queda ningun par invertido que
-- cierre una posicion.
DO $$
DECLARE
    v_rotos bigint;
    v_inv   bigint;
BEGIN
    SELECT count(*) INTO v_rotos
    FROM (
        SELECT stock_anterior,
               lag(stock_nuevo) OVER w AS prev_nuevo,
               row_number()     OVER w AS rn
        FROM public.movimiento_inventario
        WINDOW w AS (PARTITION BY producto_variante_id, bodega_id
                     ORDER BY fecha_creacion, id)
    ) e
    WHERE (rn > 1 AND stock_anterior <> prev_nuevo) OR (rn = 1 AND stock_anterior <> 0);

    IF v_rotos > 0 THEN
        RAISE EXCEPTION 'La normalizacion rompio el encadenamiento (% enlaces rotos).', v_rotos;
    END IF;

    SELECT count(*) INTO v_inv
    FROM (
        SELECT id, lag(id) OVER w AS prev_id, fecha_creacion,
               lag(fecha_creacion) OVER w AS prev_fecha, lead(id) OVER w AS next_id
        FROM public.movimiento_inventario
        WINDOW w AS (PARTITION BY producto_variante_id, bodega_id
                     ORDER BY fecha_creacion, id)
    ) o
    WHERE prev_id IS NOT NULL AND next_id IS NULL AND id < prev_id
      AND date_trunc('second', fecha_creacion) = date_trunc('second', prev_fecha);

    IF v_inv > 0 THEN
        RAISE EXCEPTION 'Quedan % pares invertidos que cierran una posicion.', v_inv;
    END IF;

    RAISE NOTICE 'Normalizacion OK: 0 enlaces rotos y 0 pares invertidos al cierre de una posicion.';
END $$;

COMMIT;

-- ---------------------------------------------------------------------------
-- 4. Verificacion
-- ---------------------------------------------------------------------------
\echo ''
\echo '--- pares que el almacen leeria al reves (los de mitad de cadena quedan declarados) ---'
WITH ord AS (
    SELECT id, producto_variante_id, bodega_id, fecha_creacion,
           lag(id)             OVER w AS prev_id,
           lag(fecha_creacion) OVER w AS prev_fecha,
           lead(id)            OVER w AS next_id
    FROM public.movimiento_inventario
    WINDOW w AS (PARTITION BY producto_variante_id, bodega_id
                 ORDER BY fecha_creacion, id)
)
SELECT count(*) FILTER (WHERE next_id IS NULL) AS invertidos_al_cierre_de_posicion,
       count(*) FILTER (WHERE next_id IS NOT NULL) AS invertidos_en_mitad_declarados
FROM ord
WHERE prev_id IS NOT NULL AND id < prev_id
  AND date_trunc('second', fecha_creacion) = date_trunc('second', prev_fecha);

\echo '--- trigger instalado ---'
SELECT tgname, tgenabled
FROM pg_trigger
WHERE tgrelid = 'public.movimiento_inventario'::regclass AND NOT tgisinternal
ORDER BY tgname;

\echo '--- indice ---'
SELECT indexname, pg_size_pretty(pg_relation_size(indexname::regclass)) AS tamano
FROM pg_indexes
WHERE tablename = 'movimiento_inventario' AND indexname = 'idx_movimiento_inventario_cadena';
