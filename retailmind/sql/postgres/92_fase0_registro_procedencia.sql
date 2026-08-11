-- ============================================================================
-- 92_fase0_registro_procedencia.sql — RetailMind · el mecanismo de PROCEDENCIA
--                                     de la carga masiva (Fase 0, 2026-08-10)
--
--   docker compose exec -T postgres psql -U postgres -d retailmind \
--       -v ON_ERROR_STOP=1 < retailmind/sql/postgres/92_fase0_registro_procedencia.sql
--
-- ---------------------------------------------------------------------------
-- EL PROBLEMA
-- ---------------------------------------------------------------------------
-- La carga a 3.000.000 de pedidos entra POR FASES. Cada fase tiene que poder
-- deshacerse ENTERA y SIN ARRASTRAR NADA AJENO. Hoy el sistema no tiene con
-- que hacerlo:
--
--   · No existe ninguna columna de procedencia en ninguna tabla.
--   · El unico marcador que hay es de TEXTO y esta INCOMPLETO: el `[SEED-*]`
--     de `movimiento_inventario.observacion` deja 254 filas sin marca. Un
--     borrado guiado por ese marcador se deja atras justo lo que no ve.
--
-- ---------------------------------------------------------------------------
-- LA DECISION: RANGO DE IDs RESERVADO, no columna nueva ni marcador de texto
-- ---------------------------------------------------------------------------
-- Todo lo que escriba la carga masiva vive en ids >= 900.000.000, en TODAS las
-- tablas, escrito con `OVERRIDING SYSTEM VALUE` (los `id` de este esquema son
-- `GENERATED ALWAYS AS IDENTITY`: sin esa clausula el motor los rechaza).
--
-- Se eligio frente a las otras dos opciones por cuatro razones:
--
--   1. NO MODIFICA NINGUNA TABLA EXISTENTE. Una columna `origen_carga` en
--      `cliente`, `producto_variante`, `pedido`… obligaria a tocar 15 tablas,
--      y con ellas sus GRANT por columna (109 ACL vivos), sus politicas RLS
--      (95) y las consultas del ETL. La regla 7 del proyecto —«no modificar
--      tablas existentes»— existe precisamente por eso. Un rango de ids no
--      toca ni un byte del esquema de negocio.
--
--   2. ES EXACTO EN LAS DOS DIRECCIONES. `DELETE … WHERE id >= 900000000` no
--      puede dejar una fila de la fase (no hay forma de que una fila cargada
--      caiga fuera del rango) y no puede llevarse una fila ajena (ninguna fila
--      previa llega a 900 millones: el maximo hoy es `pedido.id = 4218`). Un
--      marcador de texto falla en las dos: se olvida de escribir, se edita
--      desde la aplicacion, y ya demostro que se olvida.
--
--   3. ES AUTOEVIDENTE Y SOBREVIVE A LA PERDIDA DE ESTE REGISTRO. Se ve en
--      cualquier `SELECT` sin consultar ninguna tabla auxiliar. Si esta tabla
--      desapareciera, la procedencia seguiria siendo legible.
--
--   4. NO COLISIONA CON NADA PREVISTO. Las fases 1-3 insertaran ~7,6 millones
--      de movimientos de kardex por `nextval`; para alcanzar 900.000.000 harian
--      falta dos ordenes de magnitud mas. Y como se escribe con OVERRIDING, las
--      SECUENCIAS IDENTITY NO SE MUEVEN: la aplicacion sigue asignando ids
--      desde donde iba (4219, 4220…) y no queda un hueco de 900 millones.
--
-- CONTRAPARTIDA DECLARADA: el rango reservado es una convencion, no una
-- restriccion del motor. Nada impide que alguien escriba a mano un id de 900
-- millones fuera de la carga. Se acepta: la alternativa (un CHECK que prohiba
-- ese rango a la aplicacion) seria modificar tablas existentes, que es
-- exactamente lo que este diseno evita.
--
--
-- ---------------------------------------------------------------------------
-- DOS BASES, NO UNA: EL RANGO SE ADAPTA AL TIPO MAS ESTRECHO QUE LO CONSUME
-- ---------------------------------------------------------------------------
-- La base ancha es 900.000.000, pero DOS tablas usan 60.000, y el motivo no
-- esta en PostgreSQL sino AGUAS ABAJO, en ClickHouse:
--
--   · `categoria`  -> `dim_producto.categoria_id` esta declarado **UInt16**
--   · `proveedor`  -> `proveedor_id` es **UInt16** en CUATRO tablas del
--                     almacen (dim_proveedor, fact_orden_compra,
--                     fact_compra_linea, fact_devolucion_proveedor)
--
-- UInt16 tope en 65.535. Con la base ancha, la carga del DWH falla con un
-- «DataError: Unable to create Python array», que es lo que dice el driver
-- cuando un valor no cabe en el tipo — un mensaje que habla de None y no de
-- desbordamiento, asi que cuesta reconocerlo. Se descubrio ejecutando el DAG,
-- no leyendo el DDL.
--
-- Se adapta el RANGO y no el tipo porque el ETL esta fuera del alcance de esta
-- fase. 60.000 sigue siendo inconfundible: las categorias organicas llegan a
-- 21 y los proveedores a 13, y quedan 5.535 huecos para las fases siguientes.
--
-- LA LECCION, que vale para cualquier fase futura: antes de reservar un rango
-- hay que mirar el tipo MAS ESTRECHO por el que ese id va a pasar, y ese tipo
-- puede estar en otro motor.
--
-- ---------------------------------------------------------------------------
-- QUE HAY AQUI
-- ---------------------------------------------------------------------------
-- Dos tablas NUEVAS —no se toca ninguna existente—:
--
--   · `carga_fase_registro`   qué escribió cada script, en qué rango y cuántas
--                             filas. Es la bitácora que audita la reversión.
--   · `carga_fase_parametro`  los PARAMETROS DE DISENO de la carga (bandas de
--                             precio, pesos de demanda, ventana temporal). Sin
--                             esto, las fases siguientes tendrian que redescubrir
--                             de que catalogo salio el ticket medio proyectado.
--
-- Ninguna de las dos lleva RLS ni GRANT a los roles `grp_*`: son metadatos de
-- carga, no dato de negocio. Solo el superusuario las ve, que es quien carga.
-- Asi el conteo de politicas (95) y el de triggers `trg_horario_*` (34) quedan
-- EXACTAMENTE como estaban.
--
-- IDEMPOTENTE y TRANSACCIONAL.
-- ============================================================================
\set ON_ERROR_STOP on

BEGIN;

CREATE TABLE IF NOT EXISTS public.carga_fase_registro (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    fase        varchar(20)  NOT NULL,
    script      varchar(80)  NOT NULL,
    tabla       varchar(63)  NOT NULL,
    id_desde    bigint       NOT NULL,
    id_hasta    bigint       NOT NULL,
    filas       bigint       NOT NULL,
    nota        text,
    fecha_carga timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_carga_fase_registro UNIQUE (fase, script, tabla),
    -- 60.000 y no 900.000.000: ver el bloque «DOS BASES» de la cabecera.
    CONSTRAINT ck_carga_fase_rango CHECK (id_desde >= 60000 AND id_hasta >= id_desde)
);

-- `CREATE TABLE IF NOT EXISTS` no toca una tabla que ya existe, asi que la
-- restriccion se rehace aparte: sin esto, una base creada con la version
-- anterior del script se queda con el CHECK viejo (>= 900.000.000) y rechaza
-- el registro de `categoria` y `proveedor`, que van en la base estrecha.
ALTER TABLE public.carga_fase_registro DROP CONSTRAINT IF EXISTS ck_carga_fase_rango;
ALTER TABLE public.carga_fase_registro ADD  CONSTRAINT ck_carga_fase_rango
      CHECK (id_desde >= 60000 AND id_hasta >= id_desde);

COMMENT ON TABLE public.carga_fase_registro IS
    'Bitacora de la carga masiva por fases. Un renglon por (fase, script, tabla) '
    'con el rango de ids escrito. La procedencia REAL es el rango reservado '
    '(>= 900.000.000, o >= 60.000 en categoria y proveedor por el UInt16 del '
    'almacen); esta tabla lo audita, no lo define. Script 92.';

CREATE TABLE IF NOT EXISTS public.carga_fase_parametro (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    fase        varchar(20)  NOT NULL,
    clave       varchar(60)  NOT NULL,
    valor       text         NOT NULL,
    nota        text,
    fecha_carga timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_carga_fase_parametro UNIQUE (fase, clave)
);

COMMENT ON TABLE public.carga_fase_parametro IS
    'Parametros de diseno de la carga masiva: bandas de precio, pesos de demanda '
    'y ventana temporal. Las fases 1-3 los LEEN de aqui; si se pierden, el ticket '
    'medio proyectado deja de ser reproducible. Script 92.';

-- ── La funcion que registra. Idempotente por (fase, script, tabla). ─────────
CREATE OR REPLACE FUNCTION public.fn_carga_registrar(
    p_fase text, p_script text, p_tabla text,
    p_desde bigint, p_hasta bigint, p_filas bigint, p_nota text DEFAULT NULL)
RETURNS void
LANGUAGE sql
SET search_path = public, pg_temp
AS $fn$
    INSERT INTO public.carga_fase_registro (fase, script, tabla, id_desde, id_hasta, filas, nota)
    VALUES (p_fase, p_script, p_tabla, p_desde, p_hasta, p_filas, p_nota)
    ON CONFLICT (fase, script, tabla) DO UPDATE
       SET id_desde = EXCLUDED.id_desde, id_hasta = EXCLUDED.id_hasta,
           filas    = EXCLUDED.filas,    nota     = EXCLUDED.nota,
           fecha_carga = now();
$fn$;

-- ── Guardia: el rango reservado tiene que estar VACIO en las tablas que la
--    Fase 0 va a escribir, o este script no es el primero y hay que revertir. ─
DO $guardia$
DECLARE
    t text; n bigint; base bigint; sucias text := '';
BEGIN
    FOREACH t IN ARRAY ARRAY['marca','categoria','proveedor','usuario','cliente','direccion',
                             'producto','producto_variante','producto_categoria','inventario',
                             'movimiento_inventario','orden_compra','orden_compra_detalle',
                             'recepcion_mercancia','recepcion_detalle','factura_compra',
                             'factura_compra_detalle','cuenta_por_pagar','pago_proveedor',
                             'producto_proveedor','usuario_rol']
    LOOP
        base := CASE WHEN t IN ('categoria','proveedor') THEN 60000 ELSE 900000000 END;
        EXECUTE format('SELECT count(*) FROM public.%I WHERE id >= %s', t, base) INTO n;
        IF n > 0 THEN sucias := sucias || format('  %s: %s filas%s', t, n, E'\n'); END IF;
    END LOOP;
    IF sucias <> '' THEN
        RAISE NOTICE 'AVISO: el rango reservado YA tiene filas (carga previa de la Fase 0):%',
                     E'\n' || sucias;
    ELSE
        RAISE NOTICE 'Guardia OK: los rangos reservados estan vacios en las 21 tablas de la Fase 0.';
    END IF;
END
$guardia$;

COMMIT;

\echo ''
\echo 'INSTALADO. Procedencia = rango de ids reservado desde 900.000.000.'
\echo 'Reversion: 99_revert_fase0.sql'
\echo ''
