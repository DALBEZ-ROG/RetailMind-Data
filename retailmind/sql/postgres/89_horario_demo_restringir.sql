-- ============================================================================
-- 89_horario_demo_restringir.sql — RetailMind · DEMOSTRACION EN VIVO de que la
--                                   restriccion horaria sigue armada (2026-08-06)
--
-- ---------------------------------------------------------------------------
-- GUION DE LA DEMOSTRACION
-- ---------------------------------------------------------------------------
-- El script 88 dejo todas las ventanas en 24/7 para que la feria no dependa del
-- dia y la hora. Este script sirve para ENSENAR, en treinta segundos, que la
-- compuerta no se elimino: se estrecha la ventana de UN rol a un rango que
-- EXCLUYE el momento actual, y la pantalla de ese rol se queda vacia.
--
--   PASO 0 - Antes de tocar nada, entra como bodega@retailmind.com
--            (`Retail2026!`) y abre /operativo/ventas/preparacion. Se ven los
--            pedidos en cola. Deja la pestana abierta.
--
--            ESTE PASO NO ES OPCIONAL, y conviene saber por que (verificado
--            2026-08-06): el script 53 hizo que `fuera_horario` BLOQUEE EL
--            LOGIN. Con el rol ya restringido, un intento de entrar responde
--            **HTTP 401 «Credenciales incorrectas»** — mensaje generico, que
--            en vivo se lee como una contrasena mal tecleada y descarrila la
--            demostracion. El motivo real SI queda registrado, pero en
--            `log_acceso.motivo_fallo = 'fuera_horario'`, que nadie mira
--            durante una sustentacion. La sesion se abre ANTES; el JWT ya
--            emitido sigue siendo valido y por eso la RECARGA funciona.
--
--   PASO 1 - Ejecuta este script (por defecto estrecha `grp_bodega`):
--              docker exec -i retailmind-postgres-1 psql -U postgres \
--                  -d retailmind -v ON_ERROR_STOP=1 \
--                  < retailmind/sql/postgres/89_horario_demo_restringir.sql
--
--            Para otro rol, pasa la variable `rol`:
--              docker exec -i retailmind-postgres-1 psql -U postgres \
--                  -d retailmind -v ON_ERROR_STOP=1 -v rol=grp_despacho \
--                  < retailmind/sql/postgres/89_horario_demo_restringir.sql
--
--            El script imprime la hora de Ecuador, la ventana que deja puesta y
--            el veredicto de `esta_en_horario()`, que debe salir **false**.
--
--   PASO 2 - RECARGA la pantalla de bodega (F5). La cola aparece VACIA.
--            Lo que hay que contar mientras se mira:
--              * No hay ningun mensaje de error, y eso es lo importante. La
--                politica `pol_horario` esta declarada `cmd = ALL`, y ALL
--                incluye SELECT: RLS no rechaza la consulta, la FILTRA. El
--                backend recibe cero filas y las pinta tal cual.
--              * Si en vez de leer se intenta ESCRIBIR, el motor responde con
--                el trigger `trg_horario_*` y SQLSTATE 42501, que
--                `GlobalExceptionHandler` traduce a 403. En el MOTOR esto se
--                comprueba en un renglon (verificado 2026-08-06):
--                    BEGIN; SET LOCAL ROLE grp_bodega;
--                    UPDATE inventario SET stock_actual = stock_actual WHERE id = 1;
--                    ROLLBACK;
--                -> ERROR 42501: «fuera del horario permitido para el rol
--                   grp_bodega (operacion UPDATE sobre la tabla inventario)».
--
--                OJO, y esto NO es lo que dice la intuicion: pulsar «preparar»
--                EN LA PANTALLA no da 403, da **400 «No existe el pedido N»**.
--                No es un fallo. `VentasService.estadoPedido()` LEE el pedido
--                antes de escribirlo, y esa lectura ya viene filtrada a cero
--                filas por `pol_horario`; el servicio concluye que el pedido no
--                existe y aborta ANTES de que el trigger llegue a dispararse.
--                O sea: por la via de la aplicacion, RLS muerde primero y el
--                42501 no se alcanza. Si en la sustentacion se quiere ensenar
--                el 403, hay que hacerlo contra el motor con el psql de arriba,
--                no con el boton.
--              * Con el usuario admin (`admin@retailmind.com`) todo se sigue
--                viendo: `esta_en_horario()` exime a `grp_administrador` en su
--                primer IF. Comparar las dos sesiones lado a lado es la mejor
--                forma de ensenar que el filtro es POR ROL.
--
--   PASO 3 - Vuelve atras con el script hermano:
--              docker exec -i retailmind-postgres-1 psql -U postgres \
--                  -d retailmind -v ON_ERROR_STOP=1 \
--                  < retailmind/sql/postgres/90_horario_demo_restaurar.sql
--            Recarga otra vez: la cola vuelve. El sistema queda EXACTAMENTE
--            como lo dejo el script 88.
--
-- ---------------------------------------------------------------------------
-- COMO SE ELIGE EL RANGO  (calculado, nunca con horas escritas a mano)
-- ---------------------------------------------------------------------------
-- Sea `t` la hora actual en America/Guayaquil, la MISMA expresion que evalua
-- `esta_en_horario()`: `(now() AT TIME ZONE 'America/Guayaquil')::time`.
-- Hay que dejar un intervalo `[a, b)` dentro de `[00:00:00, 24:00:00)` con
-- `a < b` (lo exige el CHECK `grupo_horario_check`) y con `t` FUERA.
--
--     si t <  22:00:00  ->  [t + 1h, t + 2h)   ... `t` queda por debajo de `a`,
--                            y `t + 2h` no puede pasar de 24:00 porque t < 22.
--     si t >= 22:00:00  ->  [00:00:00, 01:00:00)  ... `t` queda por encima de
--                            `b`, ya que t >= 22:00 > 01:00.
--
-- Las dos ramas producen siempre un rango valido y siempre excluyen el momento
-- actual, a cualquier hora del dia. Nada esta escrito a mano.
--
-- Se estrechan los SIETE dias del rol, no solo el de hoy: asi el efecto no
-- depende de que la demo cruce la medianoche mientras esta puesta. Salvedad
-- honesta: si el rango calculado fue la rama `[00:00, 01:00)` (demo lanzada
-- despues de las 22:00) y la pantalla se recarga pasada la medianoche, el rol
-- volveria a estar dentro de ventana. Para una demostracion de minutos es
-- irrelevante; se deja dicho para que nadie lo descubra en el peor momento.
--
-- IDEMPOTENTE: correrlo dos veces seguidas deja el mismo estado (el rango se
--   recalcula desde `now()`, asi que la segunda ejecucion escribe el rango de
--   SU instante; en ambos casos el resultado es el mismo: el rol excluido).
-- TRANSACCIONAL con guardia: si tras el UPDATE `esta_en_horario(rol)` NO
--   devolviera false, el bloque final lanza EXCEPTION y todo hace ROLLBACK -
--   una demo que no demuestra nada no se confirma.
--
-- NO TOCA: ni funciones, ni triggers, ni politicas RLS, ni GRANTs, ni ningun
--   otro rol. Solo las 7 filas de `grupo_horario` del rol elegido.
--
-- OJO: `grp_administrador` esta EXENTO por el primer IF de `esta_en_horario()`.
--   Pasarlo como `-v rol=grp_administrador` no bloquea nada, y el guardia lo
--   detecta y aborta con un mensaje que lo explica.
-- ============================================================================
\set ON_ERROR_STOP on

-- Rol por defecto si no se paso `-v rol=...`. `:'rol'` lo cita como literal.
\if :{?rol}
\else
  \set rol grp_bodega
\endif

BEGIN;

-- ── 1. El rango, derivado de now() en America/Guayaquil ─────────────────────

CREATE TEMP TABLE tmp_demo_rango ON COMMIT DROP AS
SELECT
    :'rol'::text                                              AS rol,
    (now() AT TIME ZONE 'America/Guayaquil')                  AS ahora_ec,
    (now() AT TIME ZONE 'America/Guayaquil')::time            AS t,
    CASE WHEN (now() AT TIME ZONE 'America/Guayaquil')::time < time '22:00:00'
         THEN ((now() AT TIME ZONE 'America/Guayaquil')::time + interval '1 hour')::time
         ELSE time '00:00:00'
    END                                                       AS a,
    CASE WHEN (now() AT TIME ZONE 'America/Guayaquil')::time < time '22:00:00'
         THEN ((now() AT TIME ZONE 'America/Guayaquil')::time + interval '2 hours')::time
         ELSE time '01:00:00'
    END                                                       AS b;

\echo ''
\echo '--- 89_horario_demo_restringir: rango calculado ---'
SELECT rol,
       to_char(ahora_ec, 'YYYY-MM-DD HH24:MI:SS')  AS ahora_ecuador,
       t                                            AS hora_actual,
       a                                            AS ventana_desde,
       b                                            AS ventana_hasta
FROM tmp_demo_rango;

-- ── 2. Estrechar las 7 ventanas del rol elegido ─────────────────────────────

UPDATE public.grupo_horario gh
SET    hora_inicio = r.a,
       hora_fin    = r.b,
       activo      = true
FROM   tmp_demo_rango r
WHERE  gh.rol_grupo = r.rol;

-- Si el rol no tuviera ninguna fila, el UPDATE no hace nada y la demo no
-- demostraria nada: se avisa aqui y no en la pantalla.
DO $existe$
DECLARE v_filas int;
BEGIN
    SELECT count(*) INTO v_filas FROM public.grupo_horario
    WHERE rol_grupo = (SELECT rol FROM tmp_demo_rango);
    IF v_filas = 0 THEN
        RAISE EXCEPTION
            'ABORTA: el rol % no tiene ninguna fila en grupo_horario; no hay ventana que estrechar.',
            (SELECT rol FROM tmp_demo_rango);
    END IF;
END
$existe$;

-- ── 3. Guardia: el rol DEBE quedar fuera de horario ─────────────────────────

DO $guardia$
DECLARE
    v_rol text;
    v_dentro boolean;
BEGIN
    SELECT rol INTO v_rol FROM tmp_demo_rango;

    IF v_rol = 'grp_administrador' THEN
        RAISE EXCEPTION
            'ABORTA: grp_administrador esta EXENTO por el primer IF de esta_en_horario(); '
            'nunca quedara fuera de horario. Elige otro rol (p. ej. -v rol=grp_bodega).';
    END IF;

    SELECT esta_en_horario(v_rol) INTO v_dentro;
    IF v_dentro THEN
        RAISE EXCEPTION
            'ABORTA: tras estrechar la ventana, esta_en_horario(%) sigue devolviendo true. '
            'La demo no demostraria nada, asi que no se confirma.', v_rol;
    END IF;

    RAISE NOTICE 'Guardia OK: esta_en_horario(%) = false. Recarga la pantalla de ese rol.', v_rol;
END
$guardia$;

COMMIT;

\echo ''
\echo 'LISTO. Recarga la pantalla del rol restringido: debe quedar VACIA, sin error.'
\echo 'Para volver atras: psql ... < retailmind/sql/postgres/90_horario_demo_restaurar.sql'
\echo ''
