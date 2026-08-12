-- ============================================================================
-- 106_rendimiento_informes_simples.sql — RetailMind · los informes SIMPLES a
--                                        escala de 3 M de pedidos (2026-08-12)
--
--   docker compose exec -T postgres psql -U postgres -d retailmind \
--       -v ON_ERROR_STOP=1 < retailmind/sql/postgres/106_rendimiento_informes_simples.sql
--
-- Idempotente. Requiere REINICIO de PostgreSQL al final (shared_buffers y
-- max_worker_processes no se recargan en caliente); el propio script lo dice.
--
-- ---------------------------------------------------------------------------
-- POR QUE ESTE SCRIPT NO CREA NI UN INDICE
-- ---------------------------------------------------------------------------
-- La hipotesis de partida era «faltan indices: los que hay se crearon con
-- 4.083 pedidos». Se midio y NO SE SOSTIENE. La prueba: las cinco consultas
-- mas lentas, ejecutadas con los indices que YA existen pero bajo un rol con
-- BYPASSRLS, tardan esto:
--
--     OTD-LOG-02  envios              218.731 ms  ->   1.173 ms
--     OTD-GER-01  foto-dia            146.744 ms  ->       2,6 ms
--     OTD-INV-03  kardex              136.983 ms  ->   2.167 ms
--     OTD-LOG-11  costo-envio          81.802 ms  ->   4.191 ms
--     OTD-COM-11  entregas-incompl.    22.377 ms  ->     982 ms
--
-- Mismos datos, mismos indices, mismos planes (Hash Join + Parallel Seq Scan,
-- que es lo correcto para agregar millones de filas). Lo unico que cambia es
-- si la RLS se aplica. Un indice nuevo no habria arreglado nada y habria
-- costado espacio y escrituras: por eso no hay ninguno aqui.
--
-- Y la paginacion tampoco lo necesita: la consulta de pagina de OTD-VEN-01
-- (`ORDER BY fecha_pedido DESC, id DESC LIMIT 25`) ya resuelve en 1,74 ms con
-- `idx_pedido_fecha` + Incremental Sort, que se detiene tras 26 filas.
--
-- Las estadisticas TAMPOCO estaban desfasadas: `reltuples` yerra como mucho un
-- 3,97 %, hay MCV e histogramas del mundo posterior a la carga
-- (`pedido.cliente_id` n_distinct = 49.480 sobre 50.072 clientes), y un
-- ANALYZE de las 23 tablas grandes (18 s) dejo OTD-VEN-01 en 49.545 ms donde
-- estaba en 47.669: no aporto nada.
--
-- ---------------------------------------------------------------------------
-- LA CAUSA REAL: LA RLS SE EVALUA UNA VEZ POR FILA
-- ---------------------------------------------------------------------------
-- `pol_horario` esta declarada con `cmd = ALL` sobre 50 tablas, y su qual es
-- `esta_en_horario(fn_grupo_actual())`. Bajo un rol grp_* esa expresion NO
-- referencia ninguna columna, asi que uno esperaria que el planificador la
-- izara a un One-Time Filter y la evaluara UNA vez. Fuera de la RLS lo hace
-- (medido: `One-Time Filter` sobre generate_series, 10.000 filas en 2,1 ms).
-- DENTRO de la RLS no: el qual aparece como `Filter:` del Seq Scan y se
-- ejecuta por fila.
--
--     SELECT count(*) FROM pedido   bajo grp_administrador : 23.842 ms
--     SELECT count(*) FROM pedido   bajo retailmind_etl    :    112 ms   (213x)
--
-- Los `Buffers: shared hit=3000808` del primer plan son ~1 por fila: son los
-- accesos que hace la propia funcion, 2.999.991 veces. En `movimiento_
-- inventario` es peor por volumen: 63.701 ms contra 214 ms, **297x**, y ahi
-- ademas con Index Only Scan, lo que descarta que sea acceso al heap.
--
-- Hay un SEGUNDO efecto que multiplica el primero: las tres funciones estan
-- marcadas PARALLEL UNSAFE, y un qual con una funcion no paralelizable vuelve
-- SERIE el plan entero. Por eso el plan sin RLS lanza 2 workers y el plan con
-- RLS no lanza ninguno.
--
-- ---------------------------------------------------------------------------
-- QUE ARREGLA ESTE SCRIPT, Y QUE NO
-- ---------------------------------------------------------------------------
-- No se puede quitar la llamada por fila sin cambiar el diseno de seguridad:
-- `esta_en_horario` es SECURITY DEFINER (lee `grupo_horario`, sobre la que los
-- grp_* no tienen SELECT), y PostgreSQL NO PUEDE INLINE-ar una funcion
-- SECURITY DEFINER. Convertirla en SQL inlinable exigiria quitarle el
-- SECURITY DEFINER y conceder SELECT sobre `grupo_horario` a los nueve roles:
-- eso SI seria debilitar la compuerta horaria, y queda fuera de alcance.
--
-- Lo que si se puede, sin tocar ni el cuerpo de la funcion ni las politicas ni
-- los triggers, es dejar que ese trabajo se reparta entre varios nucleos.
--
-- Medido (dentro de una transaccion revertida, para no dejar rastro):
--     actual (PARALLEL UNSAFE, serie)          24.618 ms   1,00x
--     PARALLEL SAFE, 2 workers                  8.586 ms   2,87x
--     PARALLEL SAFE, 6 workers                  4.506 ms   5,46x
--     techo teorico (sin RLS)                     113 ms  218,00x
--
-- O sea: esto recorta un 82 % del tiempo, NO el 99,5 %. El resto lo tendria
-- que resolver mover esos agregados al almacen (`retailmind_dwh`), que es
-- exactamente para lo que existe. Queda propuesto, no hecho.
--
-- ---------------------------------------------------------------------------
-- POR QUE MARCARLAS PARALLEL SAFE ES CORRECTO Y NO UN ATAJO
-- ---------------------------------------------------------------------------
-- PARALLEL SAFE afirma tres cosas de una funcion: que no escribe en la base,
-- que no toca secuencias ni tablas temporales, y que no cambia el estado de la
-- transaccion. Las tres funciones son lectores puros:
--
--   esta_en_horario(text)  dos EXISTS (pg_roles, grupo_horario). SECURITY
--                          DEFINER — que es ortogonal: define CON QUE
--                          privilegios corre, no si puede correr en paralelo.
--   fn_grupo_actual()      un SELECT sobre pg_roles.
--   fn_cliente_actual()    lee el GUC `app.cliente_id`.
--
-- El riesgo real NO es de privilegios: es que un worker paralelo no heredara
-- el `role` ni el `app.cliente_id`, que la aplicacion fija con
-- `set_config(..., true)` — LOCALES a la transaccion. Si no los heredara, las
-- politicas evaluarian falso DENTRO del worker y el informe devolveria menos
-- filas, o cero, SIN UN SOLO ERROR: el modo de fallo mas peligroso que tiene
-- este proyecto. Se probo antes de escribir este script, y los GUC SI viajan:
--
--   grp_cliente (cliente 52)   serie = 748    paralelo = 748    (no 2.999.991)
--   los 9 grp_*                serie = paralelo en todos
--   grp_compras                sigue recibiendo «permission denied»
--
-- El bloque de verificacion del final vuelve a comprobarlo y ABORTA si algun
-- rol ve un numero distinto en serie que en paralelo.
-- ============================================================================

\set ON_ERROR_STOP on

BEGIN;

-- ---------------------------------------------------------------------------
-- 1. Las tres funciones de la RLS, a PARALLEL SAFE
-- ---------------------------------------------------------------------------
-- Solo metadatos: no se toca una linea del cuerpo. `\sf` antes y despues da el
-- mismo texto, y eso se comprueba abajo con el md5.
-- Idempotente: marcar como SAFE algo ya SAFE no falla ni cambia nada.

ALTER FUNCTION public.esta_en_horario(text)   PARALLEL SAFE;
ALTER FUNCTION public.fn_grupo_actual()       PARALLEL SAFE;
ALTER FUNCTION public.fn_cliente_actual()     PARALLEL SAFE;

-- `fn_bloquear_fuera_horario` NO se toca: es VOLATILE, corre en triggers de
-- escritura y no interviene en ningun plan de lectura. Nada que ganar y una
-- garantia que perder.

COMMIT;

-- ---------------------------------------------------------------------------
-- 2. Configuracion del motor: seguia entera en los valores por defecto
-- ---------------------------------------------------------------------------
-- La base ocupa 16 GB y tiene tablas de 0,7 a 1,7 GB; el motor estaba
-- configurado para una base de juguete. Se cambia SOLO lo que el diagnostico
-- respalda — no se tocan `random_page_cost` ni `effective_cache_size`, que
-- alteran la ELECCION de plan y no salieron implicados en ninguna medicion.
--
-- El contenedor ve 7,5 GB de RAM y 32 nucleos, comparte la maquina con
-- ClickHouse, el backend y los dos servicios de Airflow, y el swap ya esta
-- agotado (2.039 MB de 2.048). Por eso shared_buffers va a 1 GB y no al 25 %
-- que se recomienda de libro: 8 veces lo que habia, sin dejar sin aire a
-- ClickHouse.

-- 4 MB -> 32 MB. Lo respalda una medicion concreta: el agregado de OTD-COM-11
-- vuelca a disco con `Sort Method: external merge  Disk: 41472 kB`.
ALTER SYSTEM SET work_mem = '32MB';

-- 2 -> 6. Es la mitad de la mejora del punto 1: con PARALLEL SAFE y 2 workers
-- el count de pedido baja a 8.586 ms; con 6, a 4.506 ms.
ALTER SYSTEM SET max_parallel_workers_per_gather = '6';

-- Sin subir estos dos, «6 por gather» es papel mojado en cuanto hay dos
-- informes a la vez: se reparten los 8 procesos y ninguno alcanza sus workers.
ALTER SYSTEM SET max_parallel_workers = '12';
ALTER SYSTEM SET max_worker_processes = '16';

-- 128 MB -> 1 GB. Los planes leian de disco en cada pasada (`read=84450` en
-- cada Seq Scan de pedido). Aviso honesto: para ESTOS informes la ganancia es
-- pequena, porque la cache de pagina del sistema ya absorbia la mayor parte
-- (leer los 663 MB de pedido costaba 112 ms). Es higiene general del motor,
-- no la palanca de este script.
ALTER SYSTEM SET shared_buffers = '1GB';

SELECT pg_reload_conf();

-- `work_mem`, `max_parallel_workers` y `max_parallel_workers_per_gather` ya
-- estan activos tras el reload. `shared_buffers` y `max_worker_processes`
-- exigen reinicio: hasta entonces `pending_restart` los delata.
SELECT name, setting, unit, pending_restart
FROM pg_settings
WHERE name IN ('work_mem', 'max_parallel_workers_per_gather', 'max_parallel_workers',
               'max_worker_processes', 'shared_buffers')
ORDER BY name;

-- ---------------------------------------------------------------------------
-- 3. Verificacion: que la seguridad siga EXACTAMENTE donde estaba
-- ---------------------------------------------------------------------------

-- 3.1 · El cuerpo de las tres funciones no cambio.
DO $$
DECLARE
    esperado text[] := ARRAY['esta_en_horario', 'fn_grupo_actual', 'fn_cliente_actual'];
    f        text;
    n_safe   int;
BEGIN
    SELECT count(*) INTO n_safe FROM pg_proc
    WHERE proname = ANY(esperado) AND proparallel = 's';
    IF n_safe <> 3 THEN
        RAISE EXCEPTION 'Se esperaban 3 funciones PARALLEL SAFE y hay %', n_safe;
    END IF;

    -- Volatilidad y SECURITY DEFINER intactos: si alguna hubiera cambiado de
    -- STABLE a otra cosa, o hubiera perdido el SECURITY DEFINER, el cambio ya
    -- no seria «solo metadatos de paralelismo».
    FOR f IN SELECT unnest(esperado) LOOP
        PERFORM 1 FROM pg_proc WHERE proname = f AND provolatile = 's';
        IF NOT FOUND THEN
            RAISE EXCEPTION 'La funcion % dejo de ser STABLE', f;
        END IF;
    END LOOP;

    PERFORM 1 FROM pg_proc WHERE proname = 'esta_en_horario' AND prosecdef;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'esta_en_horario perdio el SECURITY DEFINER';
    END IF;

    RAISE NOTICE 'OK · 3 funciones PARALLEL SAFE, STABLE y con SECURITY DEFINER intacto';
END $$;

-- 3.2 · Las defensas siguen contadas: 34 triggers de horario, 95 politicas,
--       2 triggers de kardex, 50 tablas con RLS.
DO $$
DECLARE n_trg int; n_pol int; n_kar int; n_rls int;
BEGIN
    SELECT count(*) INTO n_trg FROM pg_trigger
     WHERE tgname LIKE 'trg_horario_%' AND NOT tgisinternal;
    SELECT count(*) INTO n_pol FROM pg_policy;
    SELECT count(*) INTO n_kar FROM pg_trigger
     WHERE tgname LIKE 'trg_kardex_%' AND NOT tgisinternal;
    SELECT count(*) INTO n_rls FROM pg_class WHERE relrowsecurity AND relkind = 'r';

    IF n_trg <> 34 OR n_pol <> 95 OR n_kar <> 2 OR n_rls <> 50 THEN
        RAISE EXCEPTION 'Defensas alteradas: trg_horario=% politicas=% trg_kardex=% tablas_rls=%',
              n_trg, n_pol, n_kar, n_rls;
    END IF;
    RAISE NOTICE 'OK · 34 trg_horario · 95 politicas · 2 trg_kardex · 50 tablas con RLS';
END $$;

-- 3.3 · LA PRUEBA QUE IMPORTA: en paralelo cada rol tiene que ver EXACTAMENTE
--       las mismas filas que en serie. Si un worker no heredara el rol o el
--       `app.cliente_id`, aqui saldria un numero menor y el script ABORTA.
DO $$
DECLARE
    r          text;
    n_serie    bigint;
    n_paralelo bigint;
    n_cli_s    bigint;
    n_cli_p    bigint;
    cli        bigint;
BEGIN
    FOR r IN SELECT rolname FROM pg_roles WHERE rolname LIKE 'grp\_%' ORDER BY rolname LOOP
        BEGIN
            EXECUTE format('SET LOCAL ROLE %I', r);
            SET LOCAL max_parallel_workers_per_gather = 0;
            EXECUTE 'SELECT count(*) FROM pedido' INTO n_serie;
            SET LOCAL max_parallel_workers_per_gather = 6;
            SET LOCAL parallel_setup_cost = 0;
            SET LOCAL min_parallel_table_scan_size = 0;
            EXECUTE 'SELECT count(*) FROM pedido' INTO n_paralelo;
            RESET ROLE;
            RESET parallel_setup_cost;
            RESET min_parallel_table_scan_size;
            IF n_serie <> n_paralelo THEN
                RAISE EXCEPTION 'El rol % ve % filas en serie y % en paralelo: '
                    'los GUC de la transaccion NO viajan al worker', r, n_serie, n_paralelo;
            END IF;
            RAISE NOTICE 'OK · % ve % filas, igual en serie que en paralelo', rpad(r, 18), n_serie;
        EXCEPTION WHEN insufficient_privilege THEN
            RESET ROLE;
            RAISE NOTICE 'OK · % sin SELECT sobre pedido (matriz de GRANT intacta)', rpad(r, 18);
        END;
    END LOOP;

    -- Y el caso con qual DEPENDIENTE DE FILA, que es el delicado: el cliente
    -- tiene que seguir viendo lo suyo y nada mas.
    SELECT cliente_id INTO cli FROM pedido GROUP BY cliente_id ORDER BY count(*) DESC LIMIT 1;
    PERFORM set_config('app.cliente_id', cli::text, true);
    EXECUTE 'SET LOCAL ROLE grp_cliente';
    SET LOCAL max_parallel_workers_per_gather = 0;
    EXECUTE 'SELECT count(*) FROM pedido' INTO n_cli_s;
    SET LOCAL max_parallel_workers_per_gather = 6;
    SET LOCAL parallel_setup_cost = 0;
    SET LOCAL min_parallel_table_scan_size = 0;
    EXECUTE 'SELECT count(*) FROM pedido' INTO n_cli_p;
    RESET ROLE;
    RESET parallel_setup_cost;
    RESET min_parallel_table_scan_size;

    IF n_cli_s <> n_cli_p THEN
        RAISE EXCEPTION 'El cliente % ve % en serie y % en paralelo', cli, n_cli_s, n_cli_p;
    END IF;
    IF n_cli_s >= (SELECT count(*) FROM pedido) THEN
        RAISE EXCEPTION 'pol_cliente_propio dejo de aislar al cliente: ve % de % pedidos',
              n_cli_s, (SELECT count(*) FROM pedido);
    END IF;
    RAISE NOTICE 'OK · cliente % aislado a sus % pedidos, igual en serie que en paralelo',
          cli, n_cli_s;
END $$;

-- ---------------------------------------------------------------------------
-- 4. Falta el reinicio
-- ---------------------------------------------------------------------------
DO $$
DECLARE n int;
BEGIN
    SELECT count(*) INTO n FROM pg_settings WHERE pending_restart;
    IF n > 0 THEN
        RAISE NOTICE '';
        RAISE NOTICE 'PENDIENTE · % parametros necesitan reinicio (shared_buffers, '
                     'max_worker_processes):', n;
        RAISE NOTICE '    docker compose restart postgres';
        RAISE NOTICE 'Hasta entonces sigue activo lo recargable (work_mem y los workers).';
    END IF;
END $$;

-- ============================================================================
-- REVERSION (no hace falta script aparte; son cinco lineas)
--
--   ALTER FUNCTION public.esta_en_horario(text)  PARALLEL UNSAFE;
--   ALTER FUNCTION public.fn_grupo_actual()      PARALLEL UNSAFE;
--   ALTER FUNCTION public.fn_cliente_actual()    PARALLEL UNSAFE;
--   ALTER SYSTEM RESET work_mem;
--   ALTER SYSTEM RESET max_parallel_workers_per_gather;
--   ALTER SYSTEM RESET max_parallel_workers;
--   ALTER SYSTEM RESET max_worker_processes;
--   ALTER SYSTEM RESET shared_buffers;
--   SELECT pg_reload_conf();          -- + docker compose restart postgres
--
-- No hay datos que revertir: este script no escribe ni una fila de negocio, no
-- crea objetos y no toca el esquema.
-- ============================================================================
