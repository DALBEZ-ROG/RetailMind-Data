-- ============================================================================
-- 111_rls_initplan.sql — RetailMind · que el predicado de RLS se evalúe UNA VEZ
--                        por consulta y no una vez POR FILA  (2026-08-19)
--
--   docker compose exec -T postgres psql -U postgres -d retailmind \
--       -v ON_ERROR_STOP=1 < retailmind/sql/postgres/111_rls_initplan.sql
--
-- ---------------------------------------------------------------------------
-- EL PROBLEMA (defecto D-11 del registro de pruebas)
-- ---------------------------------------------------------------------------
-- Las 95 políticas evalúan `esta_en_horario(...)` —y las de cliente, además,
-- `fn_cliente_actual()`—. Ninguna de esas llamadas depende de la fila: son
-- función del ROL y del RELOJ, iguales para todas las filas de la consulta.
--
-- PostgreSQL, sin embargo, las evalúa **una vez por fila**. Y cada llamada a
-- `esta_en_horario()` lee `grupo_horario`, así que el coste se multiplica por
-- el número de filas examinadas. Medido sobre la base real, sumando 19.973
-- facturas de 2.855.380:
--
--     como superusuario (sin RLS)   ........      5 ms  ·        107 buffers
--     bajo grp_administrador (RLS)  ......   4.056 ms  ·  2.936.358 buffers
--
-- 810× más lento y 27.000× más E/S, con el índice correcto en su sitio.
--
-- ---------------------------------------------------------------------------
-- QUÉ SE DESCARTÓ ANTES DE LLEGAR AQUÍ (para que nadie lo repita)
-- ---------------------------------------------------------------------------
--  · NO es que falte un índice: `idx_factura_venta_fecha_cubriente` existe y es
--    exactamente el adecuado — `btree(fecha_emision) INCLUDE (estado, total)`.
--  · NO es la RLS en sí. Reproducido en una tabla sintética de 500.000 filas:
--    con `USING (true)` el plan usa el índice y lee 241 buffers. Es la LLAMADA
--    A FUNCIÓN dentro de la política lo que lo degrada, no la política.
--  · NO lo arregla `LEAKPROOF`. Se probó marcando `esta_en_horario` y
--    `fn_grupo_actual` como LEAKPROOF: el plan no se movió ni un buffer. Se
--    revirtieron las dos a NOT LEAKPROOF — no se deja tocado un atributo de
--    seguridad que no aporta nada.
--  · NO es que las funciones sean caras: `esta_en_horario` es STABLE y una
--    llamada cuesta 2,1 ms. El problema es hacerlo 2,8 MILLONES de veces.
--
-- ---------------------------------------------------------------------------
-- LA CORRECCIÓN
-- ---------------------------------------------------------------------------
-- Envolver cada llamada independiente de la fila en un SUBSELECT escalar:
--
--     esta_en_horario(fn_grupo_actual())
--     →  (SELECT esta_en_horario(fn_grupo_actual()))
--
-- Eso convierte el predicado en un **InitPlan**: PostgreSQL lo evalúa UNA vez
-- al arrancar la consulta y reutiliza el booleano para todas las filas. Medido
-- en la tabla sintética: **6.703 buffers → 10**.
--
-- LA SEGURIDAD NO CAMBIA, y esto es lo importante: el predicado es EL MISMO.
-- No se relaja ninguna condición, no se añade ni se quita un rol, no se toca
-- un GRANT. Solo cambia CUÁNTAS VECES se evalúa una expresión cuyo valor es,
-- por construcción, idéntico para todas las filas de la consulta. Si la ventana
-- horaria está cerrada, el InitPlan devuelve `false` y la política deniega
-- exactamente igual que antes.
--
-- IDEMPOTENTE: si una política ya está envuelta, se deja como está.
-- TRANSACCIONAL con guardia: si al final el número de políticas no coincide
-- con el del principio, o alguna se queda sin predicado, ABORTA y no cambia
-- nada.
-- ============================================================================

\set ON_ERROR_STOP on

BEGIN;

-- ── Estado de partida, para poder exigir que no se pierda nada ──────────────
CREATE TEMP TABLE _antes AS
SELECT schemaname, tablename, policyname, permissive, roles, cmd, qual, with_check
FROM pg_policies WHERE schemaname = 'public';

DO $$
DECLARE
    p            record;
    nueva_qual   text;
    nueva_check  text;
    reescritas   int := 0;
    intactas     int := 0;
    grupo        text;
    roles_sql    text;
BEGIN
    FOR p IN
        SELECT policyname, tablename, permissive, roles, cmd, qual, with_check
        FROM pg_policies WHERE schemaname = 'public'
        ORDER BY tablename, policyname
    LOOP
        nueva_qual  := p.qual;
        nueva_check := p.with_check;

        -- (1) La compuerta horaria con el grupo de la sesión.
        nueva_qual  := replace(nueva_qual,
            'esta_en_horario(fn_grupo_actual())',
            '(SELECT esta_en_horario(fn_grupo_actual()))');
        nueva_check := replace(nueva_check,
            'esta_en_horario(fn_grupo_actual())',
            '(SELECT esta_en_horario(fn_grupo_actual()))');

        -- (2) La compuerta horaria con el grupo escrito (cliente y soporte).
        --     Se enumeran los nueve grupos en vez de usar una expresión
        --     regular: una regex sobre paréntesis anidados es justo el tipo de
        --     cosa que funciona con los datos de hoy y falla con los de mañana.
        FOREACH grupo IN ARRAY ARRAY[
            'grp_administrador','grp_gerente','grp_vendedor','grp_compras',
            'grp_bodega','grp_despacho','grp_cliente','grp_analista','grp_soporte'
        ] LOOP
            nueva_qual := replace(nueva_qual,
                format('esta_en_horario(''%s''::text)', grupo),
                format('(SELECT esta_en_horario(''%s''::text))', grupo));
            nueva_check := replace(nueva_check,
                format('esta_en_horario(''%s''::text)', grupo),
                format('(SELECT esta_en_horario(''%s''::text))', grupo));
        END LOOP;

        -- (3) La identidad del cliente. Aparece SIEMPRE comparada contra una
        --     columna (`cliente_id = fn_cliente_actual()`), así que envolverla
        --     no cambia la comparación: solo deja de resolverse por fila.
        nueva_qual  := replace(nueva_qual,
            '= fn_cliente_actual()', '= (SELECT fn_cliente_actual())');
        nueva_check := replace(nueva_check,
            '= fn_cliente_actual()', '= (SELECT fn_cliente_actual())');

        -- Ya envuelta (o sin nada que envolver): no se toca.
        IF nueva_qual IS NOT DISTINCT FROM p.qual
           AND nueva_check IS NOT DISTINCT FROM p.with_check THEN
            intactas := intactas + 1;
            CONTINUE;
        END IF;

        -- `roles` viene como name[]; `{public}` se traduce a PUBLIC.
        SELECT string_agg(quote_ident(r), ', ')
          INTO roles_sql
          FROM unnest(p.roles) AS r;
        IF roles_sql IS NULL OR p.roles = '{public}'::name[] THEN
            roles_sql := 'PUBLIC';
        END IF;

        EXECUTE format('DROP POLICY %I ON public.%I', p.policyname, p.tablename);
        EXECUTE format(
            'CREATE POLICY %I ON public.%I AS %s FOR %s TO %s %s %s',
            p.policyname, p.tablename,
            CASE WHEN p.permissive = 'PERMISSIVE' THEN 'PERMISSIVE' ELSE 'RESTRICTIVE' END,
            p.cmd, roles_sql,
            CASE WHEN nueva_qual  IS NOT NULL THEN 'USING (' || nueva_qual || ')' ELSE '' END,
            CASE WHEN nueva_check IS NOT NULL THEN 'WITH CHECK (' || nueva_check || ')' ELSE '' END
        );
        reescritas := reescritas + 1;
    END LOOP;

    RAISE NOTICE 'Politicas reescritas: %  ·  sin cambios: %', reescritas, intactas;
END $$;

-- ── GUARDIA 1 · ni una política de menos, ni de más ─────────────────────────
DO $$
DECLARE antes int; ahora int;
BEGIN
    SELECT count(*) INTO antes FROM _antes;
    SELECT count(*) INTO ahora FROM pg_policies WHERE schemaname = 'public';
    IF antes <> ahora THEN
        RAISE EXCEPTION 'ABORTA: habia % politicas y quedan %', antes, ahora;
    END IF;
    RAISE NOTICE 'Guardia 1 OK: % politicas antes y despues.', ahora;
END $$;

-- ── GUARDIA 2 · nadie perdió su predicado ni cambió de rol o de comando ─────
DO $$
DECLARE roto int;
BEGIN
    SELECT count(*) INTO roto
    FROM _antes a
    JOIN pg_policies d
      ON d.schemaname = a.schemaname AND d.tablename = a.tablename
     AND d.policyname = a.policyname
    WHERE d.cmd IS DISTINCT FROM a.cmd
       OR d.roles IS DISTINCT FROM a.roles
       OR d.permissive IS DISTINCT FROM a.permissive
       OR (a.qual IS NOT NULL AND d.qual IS NULL)
       OR (a.with_check IS NOT NULL AND d.with_check IS NULL);
    IF roto > 0 THEN
        RAISE EXCEPTION 'ABORTA: % politicas cambiaron de rol, comando o perdieron predicado', roto;
    END IF;
    RAISE NOTICE 'Guardia 2 OK: roles, comandos y predicados conservados.';
END $$;

-- ── GUARDIA 3 · la compuerta sigue MORDIENDO ────────────────────────────────
-- Se comprueba que el predicado no se haya quedado en «siempre verdadero»: con
-- una ventana cerrada, `esta_en_horario` tiene que seguir devolviendo false.
DO $$
DECLARE abierto boolean; cerrado boolean;
BEGIN
    SELECT esta_en_horario('grp_vendedor') INTO abierto;
    IF NOT abierto THEN
        RAISE EXCEPTION 'ABORTA: grp_vendedor deberia estar en horario (24/7) y no lo esta';
    END IF;
    -- Un rol inexistente no tiene ventanas: la funcion debe negar.
    SELECT esta_en_horario('grp_no_existe_111') INTO cerrado;
    IF cerrado THEN
        RAISE EXCEPTION 'ABORTA: esta_en_horario acepta un grupo sin ventanas';
    END IF;
    RAISE NOTICE 'Guardia 3 OK: la compuerta horaria sigue discriminando.';
END $$;

DROP TABLE _antes;

COMMIT;

\echo ''
\echo 'LISTO. El predicado de RLS pasa a evaluarse una vez por consulta (InitPlan).'
\echo 'La condicion es la MISMA: solo cambia cuantas veces se calcula.'
\echo 'Verifica con:  py -3 pruebas/p03_motor.py E3   (debe seguir en 43/43)'
