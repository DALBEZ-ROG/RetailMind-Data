-- ============================================================================
-- 20_horario.sql — RetailMind · Restriccion HORARIA configurable
--  - Tabla grupo_horario: el administrador la edita con INSERT/UPDATE
--    (sin ALTER, sin redeploy) => caso de uso "Configurar horarios de acceso".
--  - esta_en_horario(rol): true si el rol puede operar AHORA.
--    grp_administrador (y superusuarios) SIEMPRE exentos.
--  - fn_grupo_actual(): resuelve a que grupo pertenece la sesion actual
--    (funciona con SET ROLE grp_x y con membresia de un usuario LOGIN).
--  - Triggers BEFORE INSERT/UPDATE/DELETE (statement-level) sobre las tablas
--    operativas: fuera de horario => RAISE EXCEPTION.
--  Regla: si un grupo NO tiene fila vigente en grupo_horario => DENEGADO
--  (default seguro). Por eso grp_cliente se siembra 24/7.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1) Tabla de configuracion de horarios
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS grupo_horario (
    id             int GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rol_grupo      text        NOT NULL,
    dia_semana     smallint    NOT NULL CHECK (dia_semana BETWEEN 0 AND 6), -- 0=domingo..6=sabado
    hora_inicio    time        NOT NULL,
    hora_fin       time        NOT NULL,
    activo         boolean     NOT NULL DEFAULT true,
    fecha_creacion timestamptz NOT NULL DEFAULT now(),
    CHECK (hora_inicio < hora_fin)
);

COMMENT ON TABLE grupo_horario IS
  'Ventanas horarias por rol de grupo. Editable solo por grp_administrador con INSERT/UPDATE (sin ALTER).';

-- Todos pueden CONSULTAR su horario; solo el administrador lo configura
GRANT SELECT ON grupo_horario TO grp_gerente, grp_vendedor, grp_compras,
                                 grp_bodega, grp_despacho, grp_cliente, grp_analista;
GRANT ALL PRIVILEGES ON grupo_horario TO grp_administrador;
GRANT USAGE, SELECT ON SEQUENCE grupo_horario_id_seq TO grp_administrador;

-- ----------------------------------------------------------------------------
-- 2) fn_grupo_actual(): grupo efectivo de la sesion
--    - Si la sesion hizo SET ROLE grp_x        => devuelve grp_x
--    - Si el usuario LOGIN es miembro de grp_x => devuelve grp_x
--    - Superusuario => grp_administrador (pg_has_role da true a todo)
--    - Prioridad: grp_administrador primero (exencion gana)
-- ----------------------------------------------------------------------------
-- OJO: debe ser SECURITY INVOKER; con DEFINER, current_user dentro de la
-- funcion seria el owner (postgres) y todos quedarian exentos.
CREATE OR REPLACE FUNCTION fn_grupo_actual()
RETURNS text
LANGUAGE sql STABLE
SET search_path = public, pg_temp
AS $$
    SELECT COALESCE(
        (SELECT r.rolname
           FROM pg_roles r
          WHERE r.rolname LIKE 'grp\_%'
            AND pg_has_role(current_user, r.oid, 'MEMBER')
          ORDER BY (r.rolname = 'grp_administrador') DESC, r.rolname
          LIMIT 1),
        current_user::text
    );
$$;

-- ----------------------------------------------------------------------------
-- 3) esta_en_horario(rol): compara dia/hora actuales contra grupo_horario
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION esta_en_horario(p_rol text)
RETURNS boolean
LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    -- El administrador (y cualquier superusuario) queda EXENTO del horario
    IF p_rol = 'grp_administrador'
       OR EXISTS (SELECT 1 FROM pg_roles WHERE rolname = p_rol AND rolsuper) THEN
        RETURN true;
    END IF;

    RETURN EXISTS (
        SELECT 1
        FROM grupo_horario gh
        WHERE gh.rol_grupo  = p_rol
          AND gh.activo
          AND gh.dia_semana = EXTRACT(DOW FROM now())::smallint
          AND localtime    >= gh.hora_inicio
          AND localtime    <  gh.hora_fin
    );
END;
$$;

GRANT EXECUTE ON FUNCTION fn_grupo_actual()       TO PUBLIC;
GRANT EXECUTE ON FUNCTION esta_en_horario(text)   TO PUBLIC;

-- ----------------------------------------------------------------------------
-- 4) Trigger de bloqueo de ESCRITURA fuera de horario
--    Statement-level: se dispara ANTES de tocar cualquier fila.
-- ----------------------------------------------------------------------------
-- SECURITY INVOKER por la misma razon: necesita ver el current_user real.
CREATE OR REPLACE FUNCTION fn_bloquear_fuera_horario()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = public, pg_temp
AS $$
DECLARE
    v_grupo text := fn_grupo_actual();
BEGIN
    IF NOT esta_en_horario(v_grupo) THEN
        RAISE EXCEPTION 'Acceso denegado: fuera del horario permitido para el rol % (operacion % sobre la tabla %)',
              v_grupo, TG_OP, TG_TABLE_NAME
              USING ERRCODE = 'insufficient_privilege',
                    HINT    = 'Consulte la tabla grupo_horario o contacte al administrador.';
    END IF;
    RETURN NULL;  -- en triggers statement-level el retorno se ignora
END;
$$;

DO $$
DECLARE
    v_tabla text;
    v_tablas text[] := ARRAY[
        -- ciclo de venta / cliente
        'cliente','carrito','carrito_item','wishlist','wishlist_item',
        'pedido','pedido_detalle','factura_venta','factura_venta_detalle',
        'resena','direccion','historial_estado_pedido',
        -- abastecimiento
        'proveedor','contacto_proveedor','producto_proveedor',
        'orden_compra','orden_compra_detalle','recepcion_mercancia',
        'recepcion_detalle','factura_compra','factura_compra_detalle',
        'cuenta_por_pagar','pago_proveedor',
        -- inventario / bodega
        'bodega','ubicacion_bodega','inventario','movimiento_inventario',
        'transferencia_bodega','ajuste_inventario','reserva_stock','lote',
        -- logistica
        'envio','envio_detalle','seguimiento_envio'
    ];
BEGIN
    FOREACH v_tabla IN ARRAY v_tablas LOOP
        EXECUTE format('DROP TRIGGER IF EXISTS trg_horario_%s ON public.%I', v_tabla, v_tabla);
        EXECUTE format(
            'CREATE TRIGGER trg_horario_%s
             BEFORE INSERT OR UPDATE OR DELETE ON public.%I
             FOR EACH STATEMENT
             EXECUTE FUNCTION fn_bloquear_fuera_horario()',
            v_tabla, v_tabla);
    END LOOP;
    RAISE NOTICE 'Triggers de horario creados sobre % tablas operativas', array_length(v_tablas, 1);
END $$;

-- ----------------------------------------------------------------------------
-- 5) SEED de horarios por defecto (solo si la tabla esta vacia)
--    Operativos: lunes-viernes 08:00-18:00, sabado 08:00-13:00
--    grp_cliente: 24/7 (tienda en linea)
--    grp_administrador: NO necesita filas (exento por funcion)
-- ----------------------------------------------------------------------------
DO $$
DECLARE
    v_grupo text;
    v_dia   int;
BEGIN
    IF EXISTS (SELECT 1 FROM grupo_horario) THEN
        RAISE NOTICE 'grupo_horario ya tiene datos; seed omitido';
        RETURN;
    END IF;

    FOREACH v_grupo IN ARRAY ARRAY['grp_gerente','grp_vendedor','grp_compras',
                                   'grp_bodega','grp_despacho','grp_analista'] LOOP
        FOR v_dia IN 1..5 LOOP  -- lunes a viernes
            INSERT INTO grupo_horario (rol_grupo, dia_semana, hora_inicio, hora_fin)
            VALUES (v_grupo, v_dia, '08:00', '18:00');
        END LOOP;
        INSERT INTO grupo_horario (rol_grupo, dia_semana, hora_inicio, hora_fin)
        VALUES (v_grupo, 6, '08:00', '13:00');  -- sabado
    END LOOP;

    FOR v_dia IN 0..6 LOOP  -- cliente: todos los dias, todo el dia
        INSERT INTO grupo_horario (rol_grupo, dia_semana, hora_inicio, hora_fin)
        VALUES ('grp_cliente', v_dia, '00:00', '23:59:59');
    END LOOP;

    RAISE NOTICE 'Seed de horarios por defecto insertado';
END $$;
