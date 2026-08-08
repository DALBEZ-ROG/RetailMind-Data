-- ============================================================================
-- 91_invariante_kardex.sql — RetailMind · el invariante del kardex baja al
--                            MOTOR (2026-08-07)
--
-- Lleva a PostgreSQL la ecuacion que hasta hoy solo sostenia la aplicacion:
--
--     stock_nuevo = stock_anterior + tipo_movimiento.factor * cantidad
--
-- Los CHECK de `movimiento_inventario` solo exigian `cantidad > 0`,
-- `costo_unitario >= 0`, `stock_anterior >= 0` y `stock_nuevo >= 0`: NINGUNO
-- relaciona las tres columnas entre si. Era el unico invariante del sistema
-- que vivia fuera de la base, en un proyecto cuya tesis es la seguridad de
-- motor.
--
--   docker compose exec -T postgres psql -U postgres -d retailmind \
--       -v ON_ERROR_STOP=1 < retailmind/sql/postgres/91_invariante_kardex.sql
--
-- ---------------------------------------------------------------------------
-- POR QUE VALIDA Y NO CALCULA  (la decision de diseno de este script)
-- ---------------------------------------------------------------------------
-- Habia dos formas de cerrarlo, y la diferencia NO es de estilo:
--
--   (a) CALCULAR: BEFORE INSERT que sobrescribe `NEW.stock_nuevo` con el
--       valor correcto, pase lo que pase.
--   (b) VALIDAR: BEFORE INSERT que RECHAZA la fila si los tres numeros no
--       cuadran, y no toca nada.
--
-- Se elige (b) VALIDAR, por tres razones, la primera decisiva:
--
--   1. EL KARDEX NO ES LA UNICA COPIA. Las dos rutas de escritura hacen
--      SIEMPRE tres cosas: bloquean `inventario` con FOR UPDATE, insertan el
--      movimiento, y acto seguido escriben
--      `UPDATE inventario SET stock_actual = <su propia variable>`.
--      Ese UPDATE el trigger NI LO VE NI LO PUEDE ARREGLAR. Si el trigger
--      CALCULARA, corregiria la fila del kardex y dejaria `inventario` con el
--      valor equivocado: una incoherencia RUIDOSA de una fila se convertiria
--      en una divergencia SILENCIOSA entre dos tablas — estrictamente peor, y
--      justo la que rompe la reconstruccion posicion-por-posicion del DWH
--      (`fact_stock_mensual` cuadra hoy 1.406/1.406 contra
--      `inventario.stock_actual`, y `validar_dwh.py` aborta la publicacion si
--      una sola posicion difiere). Validar mantiene las dos copias atadas:
--      o cuadran las tres cifras, o no hay fila NI UPDATE, porque la
--      transaccion entera se va abajo.
--
--   2. EL MOTOR ARBITRA, NO REDACTA. Calcular convierte al motor en autor del
--      dato y deja el bug de la aplicacion vivo, sin error y sin traza:
--      seguiria corriendo mal para siempre. Validar deja la responsabilidad
--      del calculo donde esta y se limita a no admitir lo que no cuadra —
--      que es como ya funciona el resto del sistema (guardias que rechazan,
--      42501, CHECK), y lo contrario de los triggers de TOTALES, que si son
--      autores... porque de esos totales no hay una segunda copia en otra
--      tabla.
--
--   3. EL FALLO SE VE DONDE NACE. Con (a), un `factor` mal apuntado produce
--      un kardex invertido y correcto-en-si-mismo. Con (b), revienta en el
--      INSERT exacto que lo causa, con el codigo del tipo y las tres cifras
--      en el mensaje.
--
-- QUE PASA CON LAS DOS RUTAS ACTUALES: las dos pasan sin tocar una linea de
-- Java, porque las dos calculan bien HOY.
--   · `StockService.mover()` consulta `tipo_movimiento.factor` y aplica
--     `stockAnterior + factor * cantidad`. Es la ruta canonica: correcta por
--     construccion para cualquier factor.
--   · `ComprasService` (recepcion de compra) aplica `stockAnterior +
--     cantidadRecibida` con el signo `+` FIJO, y NUNCA consulta `factor`.
--     Coincide con la ecuacion solo porque `entrada_compra` tiene
--     `factor = +1`. Si ese tipo pasara a `-1`, hoy invertiria el kardex sin
--     un solo error; a partir de este script recibe una EXCEPCION en el
--     INSERT. Ese es exactamente el punto: el motor cubre la ruta que no se
--     protege sola, sin que haya que unificar el codigo.
--
-- ---------------------------------------------------------------------------
-- ALCANCE: INSERT **Y** UPDATE
-- ---------------------------------------------------------------------------
-- El kardex es en principio append-only, pero `stock_nuevo` es una columna
-- normal y sin cubrir el UPDATE quedaba la puerta abierta a reescribirla a
-- cualquier cosa. El trigger de UPDATE lleva `WHEN` sobre las tres columnas
-- (mas el tipo), asi que un UPDATE que toque `observacion` no paga nada.
--
-- OJO PARA MANTENIMIENTO: los scripts que REENCADENAN el kardex (78, 80, 84)
-- reescriben `stock_anterior`/`stock_nuevo` de cadenas enteras. Eso sigue
-- siendo legal — el reencadenado PRESERVA la ecuacion, porque desplaza las
-- dos columnas a la vez — PERO hay que escribir ambas en UNA SOLA sentencia
-- (`SET stock_anterior = ..., stock_nuevo = ...`). Si se hicieran en dos
-- UPDATE seguidos, el estado intermedio viola el invariante y el trigger
-- aborta. Para una migracion que de verdad lo necesite, la salida declarada
-- es, como propietario y dentro de la misma transaccion:
--     ALTER TABLE movimiento_inventario DISABLE TRIGGER trg_kardex_ecuacion_upd;
--     ...
--     ALTER TABLE movimiento_inventario ENABLE  TRIGGER trg_kardex_ecuacion_upd;
-- y re-verificar el conteo de la seccion 1 antes del COMMIT.
--
-- ---------------------------------------------------------------------------
-- NOTAS
-- ---------------------------------------------------------------------------
-- · SECURITY DEFINER: la funcion lee `tipo_movimiento.factor`. Hoy los 5
--   roles con INSERT sobre el kardex tienen SELECT sobre esa tabla, pero
--   `grp_despacho` y `grp_soporte` NO la leen, y desde el script 87 la
--   pantalla de permisos puede conceder INSERT sobre `movimiento_inventario`
--   a un rol nuevo con dos clics SIN conceder `tipo_movimiento`. Sin
--   SECURITY DEFINER, ese rol recibiria un "permission denied" del TRIGGER en
--   una ruta que por privilegios deberia funcionar. Mismo motivo por el que
--   `fn_recalcular_total_orden_compra` es SECURITY DEFINER.
--   `search_path` fijado, como en el resto de funciones del proyecto.
-- · NO toca los triggers `trg_horario_*`, ni las politicas RLS, ni un GRANT.
--   El trigger nuevo se llama `trg_kardex_*` a proposito: no colisiona con el
--   prefijo que cuentan las verificaciones del mecanismo horario (34).
-- · IDEMPOTENTE (CREATE OR REPLACE + DROP TRIGGER IF EXISTS) y TRANSACCIONAL.
-- · Se AUTOVERIFICA: la seccion 1 aborta si un solo movimiento existente
--   violara la ecuacion, ANTES de crear nada. Un trigger no se instala sobre
--   datos que ya lo incumplen.
-- ============================================================================
\set ON_ERROR_STOP on

BEGIN;

-- ── 1. Guardia previa: el dato existente ya cumple, o no se instala nada ────

DO $previa$
DECLARE
    v_total  bigint;
    v_violan bigint;
    v_ej     text;
BEGIN
    SELECT count(*),
           count(*) FILTER (
               WHERE mi.stock_nuevo <> mi.stock_anterior + tm.factor * mi.cantidad)
      INTO v_total, v_violan
    FROM public.movimiento_inventario mi
    JOIN public.tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id;

    IF v_violan > 0 THEN
        SELECT string_agg(x.t, E'\n')
          INTO v_ej
        FROM (SELECT format('  id=%s tipo=%s cantidad=%s anterior=%s nuevo=%s (esperado %s)',
                            mi.id, tm.codigo, mi.cantidad, mi.stock_anterior,
                            mi.stock_nuevo, mi.stock_anterior + tm.factor * mi.cantidad) AS t
              FROM public.movimiento_inventario mi
              JOIN public.tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id
              WHERE mi.stock_nuevo <> mi.stock_anterior + tm.factor * mi.cantidad
              ORDER BY mi.id LIMIT 10) x;
        RAISE EXCEPTION
            'ABORTA: % de % movimientos ya violan la ecuacion. No se instala el trigger.%s',
            v_violan, v_total, E'\n' || v_ej;
    END IF;

    RAISE NOTICE 'Guardia previa OK: % de % movimientos cumplen la ecuacion.',
                 v_total, v_total;
END
$previa$;

-- ── 2. La funcion que valida ────────────────────────────────────────────────

CREATE OR REPLACE FUNCTION public.fn_validar_ecuacion_kardex()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $fn$
DECLARE
    v_factor   smallint;
    v_codigo   text;
    v_esperado integer;
BEGIN
    SELECT tm.factor, tm.codigo
      INTO v_factor, v_codigo
    FROM public.tipo_movimiento tm
    WHERE tm.id = NEW.tipo_movimiento_id;

    -- Sin tipo no hay ecuacion que comprobar. La FK ya lo impide; si algun dia
    -- dejara de hacerlo, no se deja pasar en silencio.
    IF v_factor IS NULL THEN
        RAISE EXCEPTION
            'Kardex: el tipo de movimiento % no existe; no se puede validar el saldo.',
            NEW.tipo_movimiento_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    v_esperado := NEW.stock_anterior + v_factor * NEW.cantidad;

    IF NEW.stock_nuevo IS DISTINCT FROM v_esperado THEN
        RAISE EXCEPTION
            'Kardex descuadrado: % de % unidades sobre un saldo de % debe dejar % y se intento escribir %.',
            v_codigo, NEW.cantidad, NEW.stock_anterior, v_esperado, NEW.stock_nuevo
            USING ERRCODE = 'check_violation',
                  HINT = 'stock_nuevo = stock_anterior + tipo_movimiento.factor * cantidad. '
                         'Si la ruta que escribe no consulta el factor, esta suponiendo el signo.';
    END IF;

    RETURN NEW;
END;
$fn$;

COMMENT ON FUNCTION public.fn_validar_ecuacion_kardex() IS
    'Invariante del kardex: stock_nuevo = stock_anterior + tipo_movimiento.factor * cantidad. '
    'VALIDA (rechaza), no calcula: la aplicacion escribe ademas inventario.stock_actual con su '
    'propia variable en otra sentencia, y corregir aqui dejaria esa segunda copia desalineada. '
    'Script 91.';

-- ── 3. Los triggers ─────────────────────────────────────────────────────────

DROP TRIGGER IF EXISTS trg_kardex_ecuacion_ins ON public.movimiento_inventario;
CREATE TRIGGER trg_kardex_ecuacion_ins
    BEFORE INSERT ON public.movimiento_inventario
    FOR EACH ROW
    EXECUTE FUNCTION public.fn_validar_ecuacion_kardex();

-- Solo cuando cambia algo que participa en la ecuacion: un UPDATE de
-- `observacion` o `referencia_id` no paga la consulta.
DROP TRIGGER IF EXISTS trg_kardex_ecuacion_upd ON public.movimiento_inventario;
CREATE TRIGGER trg_kardex_ecuacion_upd
    BEFORE UPDATE ON public.movimiento_inventario
    FOR EACH ROW
    WHEN (NEW.stock_anterior     IS DISTINCT FROM OLD.stock_anterior
       OR NEW.stock_nuevo        IS DISTINCT FROM OLD.stock_nuevo
       OR NEW.cantidad           IS DISTINCT FROM OLD.cantidad
       OR NEW.tipo_movimiento_id IS DISTINCT FROM OLD.tipo_movimiento_id)
    EXECUTE FUNCTION public.fn_validar_ecuacion_kardex();

-- ── 4. Guardia posterior: los triggers quedaron, y el dato sigue cuadrando ──

DO $posterior$
DECLARE
    v_triggers int;
    v_violan   bigint;
BEGIN
    SELECT count(*) INTO v_triggers
    FROM pg_trigger t
    JOIN pg_class c ON c.oid = t.tgrelid
    WHERE NOT t.tgisinternal
      AND c.relname = 'movimiento_inventario'
      AND t.tgname IN ('trg_kardex_ecuacion_ins', 'trg_kardex_ecuacion_upd')
      AND t.tgenabled <> 'D';

    IF v_triggers <> 2 THEN
        RAISE EXCEPTION 'ABORTA: se esperaban 2 triggers activos y hay %.', v_triggers;
    END IF;

    SELECT count(*) INTO v_violan
    FROM public.movimiento_inventario mi
    JOIN public.tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id
    WHERE mi.stock_nuevo <> mi.stock_anterior + tm.factor * mi.cantidad;

    IF v_violan > 0 THEN
        RAISE EXCEPTION 'ABORTA: % movimientos violan la ecuacion tras instalar.', v_violan;
    END IF;

    RAISE NOTICE 'Guardia posterior OK: 2 triggers activos, 0 movimientos en violacion.';
END
$posterior$;

COMMIT;

\echo ''
\echo 'INSTALADO. El invariante del kardex lo garantiza ahora el MOTOR.'
\echo '  stock_nuevo = stock_anterior + tipo_movimiento.factor * cantidad'
\echo 'Valida (rechaza), no calcula: ver la cabecera del script.'
\echo ''
