-- =============================================================================
-- 33_fix_inventario_unique.sql
-- Garantiza que la unicidad de `inventario` sea la COMPUESTA
-- (producto_variante_id, bodega_id) y NO constraints UNIQUE de una sola
-- columna (un UNIQUE suelto sobre bodega_id limitaría el inventario a una
-- sola fila por bodega en toda la BD).
--
-- Idempotente: puede ejecutarse las veces que haga falta.
--   * Si existen UNIQUE de una sola columna sobre bodega_id o
--     producto_variante_id, los elimina.
--   * Si falta la compuesta uq_inventario, la crea (validando antes que no
--     haya duplicados que la violen).
--   * Si el esquema ya está correcto (caso del DDL vigente
--     06_m04_inventario_bodega.sql), no hace nada y lo reporta por NOTICE.
-- =============================================================================

DO $$
DECLARE
    c   record;
    dup bigint;
BEGIN
    -- 1) Eliminar UNIQUE de una sola columna (el bug reportado)
    FOR c IN
        SELECT conname
        FROM   pg_constraint
        WHERE  conrelid = 'public.inventario'::regclass
          AND  contype  = 'u'
          AND  pg_get_constraintdef(oid) IN ('UNIQUE (bodega_id)',
                                             'UNIQUE (producto_variante_id)')
    LOOP
        EXECUTE format('ALTER TABLE public.inventario DROP CONSTRAINT %I', c.conname);
        RAISE NOTICE 'Eliminada constraint UNIQUE de una sola columna: %', c.conname;
    END LOOP;

    -- 2) Crear la UNIQUE compuesta si no existe
    IF NOT EXISTS (
        SELECT 1
        FROM   pg_constraint
        WHERE  conrelid = 'public.inventario'::regclass
          AND  contype  = 'u'
          AND  pg_get_constraintdef(oid) = 'UNIQUE (producto_variante_id, bodega_id)'
    ) THEN
        SELECT count(*) INTO dup
        FROM (
            SELECT producto_variante_id, bodega_id
            FROM   public.inventario
            GROUP  BY producto_variante_id, bodega_id
            HAVING count(*) > 1
        ) d;

        IF dup > 0 THEN
            RAISE EXCEPTION
                'Hay % pares (producto_variante_id, bodega_id) duplicados en inventario; '
                'resolverlos antes de crear uq_inventario', dup;
        END IF;

        ALTER TABLE public.inventario
            ADD CONSTRAINT uq_inventario UNIQUE (producto_variante_id, bodega_id);
        RAISE NOTICE 'Creada constraint compuesta uq_inventario (producto_variante_id, bodega_id)';
    ELSE
        RAISE NOTICE 'uq_inventario compuesta ya existe: esquema correcto, sin cambios';
    END IF;
END $$;
