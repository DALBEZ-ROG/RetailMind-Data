-- ============================================================================
-- 94_fase0_personas.sql — RetailMind · Fase 0 (2/4): vendedores, clientes y
--                         direcciones (2026-08-10)
--
--   docker compose exec -T postgres psql -U postgres -d retailmind \
--       -v ON_ERROR_STOP=1 < retailmind/sql/postgres/94_fase0_personas.sql
--
-- Crea 93 vendedores (hoy hay 7 con rol VENDEDOR -> quedan 100) y 50.000
-- clientes con su `usuario`, su `usuario_rol` y una `direccion`.
--
-- Procedencia: ids >= 900.000.000 en las cinco tablas (script 92).
-- POR LOTES con COMMIT: el DO block commitea cada 10.000 clientes.
-- IDEMPOTENTE: ON CONFLICT (id) DO NOTHING, y el lote se salta si ya esta.
--
-- ---------------------------------------------------------------------------
-- TRES DECISIONES QUE CONVIENE LEER ANTES DE TOCAR ESTO
-- ---------------------------------------------------------------------------
-- 1. CONTRASENA QUE NO ABRE NADA. `usuario.password_hash` es NOT NULL. Se
--    escribe una cadena con FORMA de bcrypt (`$2a$10$` + 53 caracteres del
--    alfabeto bcrypt) que NO es el hash de ninguna contrasena: estas 50.093
--    cuentas son sinteticas y no deben poder autenticarse. Reutilizar el hash
--    de un usuario real habria dado a 50.000 cuentas una contrasena conocida,
--    y escribir una nueva habria metido un secreto en un archivo versionado.
--
-- 2. CEDULAS CON DIGITO VERIFICADOR REAL Y SIN PATRON VISIBLE. El algoritmo
--    ecuatoriano (modulo 10 con coeficientes 2,1,2,1,2,1,2,1,2) se aplica de
--    verdad, asi que las cedulas pasan cualquier validador. Los seis ultimos
--    digitos son `reverse(lpad(k * 486187 mod 10^6))`: COMPOSICION DE DOS
--    BIYECCIONES —la multiplicativa (gcd(486187, 10^6) = 1) y la inversion de
--    la cadena—, luego sigue siendo INYECTIVA y la unicidad de
--    `uq_cliente_identificacion` esta garantizada por construccion, no por
--    suerte. La inversion no es cosmetica: con el multiplicador pequeno que
--    tenia la primera version, las cedulas de dos clientes consecutivos
--    diferian en una CONSTANTE y el patron se leia a simple vista
--    (…007919 → …015838 → …023757). Lo mismo vale para el telefono, la ciudad,
--    la fecha de nacimiento y el numero de casa: todos usan multiplicadores
--    grandes por la misma razon.
--
-- 3. EL 8 % SON EMPRESAS (tipo `ruc`). Hoy los 72 clientes son `cedula` y
--    `grupo_cliente` esta VACIA, y por eso el diagnostico de 2026-07-30
--    concluyo que no habia segmento B2B medible. Este 8 % es lo que hara que
--    el ticket alto de OE-01 siga teniendo a quien atribuirse cuando la
--    mediana del catalogo baje.
--
-- ARTEFACTO DECLARADO: estos 50.000 clientes se dan de alta entre 2025-01 y
-- 2026-08 pero no compran hasta la Fase 2 (2026-09). Es inevitable al cargar
-- maestros antes que transacciones. No hay informe que lo delate —OTD-VEN-05
-- lista clientes POR VENTA, y un cliente sin ventas no aparece— pero queda
-- dicho aqui para que nadie lo descubra como si fuera un fallo.
-- ============================================================================
\set ON_ERROR_STOP on

-- ── Constantes de nombres, apellidos, dominios y ciudades ───────────────────
-- Van en una tabla TEMPORAL: no dejan residuo y no exigen reversion.
CREATE TEMP TABLE IF NOT EXISTS f0_nom_h(i int, v text);
CREATE TEMP TABLE IF NOT EXISTS f0_nom_m(i int, v text);
CREATE TEMP TABLE IF NOT EXISTS f0_ape(i int, v text);
CREATE TEMP TABLE IF NOT EXISTS f0_dom(i int, v text);
CREATE TEMP TABLE IF NOT EXISTS f0_ciu(i int, ciudad_id bigint, prov text, peso int, acum int);
CREATE TEMP TABLE IF NOT EXISTS f0_emp(i int, v text);

TRUNCATE f0_nom_h; TRUNCATE f0_nom_m; TRUNCATE f0_ape; TRUNCATE f0_dom; TRUNCATE f0_ciu; TRUNCATE f0_emp;

INSERT INTO f0_nom_h(i,v) SELECT row_number() OVER (), v FROM unnest(ARRAY[
 'Luis','Jorge','Andres','Carlos','Juan','Marco','Diego','Pedro','Miguel','Fernando',
 'Ricardo','Javier','Cristian','Wilson','Byron','Freddy','Galo','Hernan','Ivan','Klever',
 'Milton','Nelson','Oswaldo','Patricio','Rodrigo','Segundo','Vicente','Washington','Xavier','Angel',
 'Alberto','Bolivar','Cesar','Danilo','Edison','Fabian','Gustavo','Holger','Israel','Jefferson',
 'Kevin','Lenin','Manuel','Norberto','Omar','Pablo','Ramiro','Santiago','Tito','Ulises']) v;

INSERT INTO f0_nom_m(i,v) SELECT row_number() OVER (), v FROM unnest(ARRAY[
 'Maria','Ana','Rosa','Carmen','Gloria','Martha','Patricia','Silvia','Veronica','Jessica',
 'Mercedes','Narcisa','Blanca','Digna','Elena','Fanny','Gladys','Ingrid','Johanna','Karina',
 'Lourdes','Magaly','Nancy','Olga','Paola','Rocio','Sandra','Tania','Yolanda','Zoila',
 'Alexandra','Beatriz','Cecilia','Dolores','Erika','Flor','Genoveva','Hilda','Irene','Jacqueline',
 'Katherine','Lorena','Monica','Nube','Piedad','Rosario','Sonia','Teresa','Victoria','Wendy']) v;

INSERT INTO f0_ape(i,v) SELECT row_number() OVER (), v FROM unnest(ARRAY[
 'Cedeno','Zambrano','Vera','Loor','Mendoza','Intriago','Alvarado','Moreira','Bravo','Chavez',
 'Delgado','Espinoza','Farias','Garcia','Herrera','Jaramillo','Lopez','Macias','Naranjo','Ortiz',
 'Palacios','Quinonez','Rodriguez','Sanchez','Torres','Ubilla','Valencia','Yepez','Zurita','Aguirre',
 'Benitez','Castro','Duenas','Erazo','Flores','Gomez','Hidalgo','Iglesias','Jimenez','Leon',
 'Molina','Nunez','Oviedo','Paredes','Quintero','Ramos','Solorzano','Tapia','Villacis','Andrade',
 'Barzola','Coello','Diaz','Estrella','Franco','Guerrero','Holguin','Icaza','Jurado','Lucero',
 'Maldonado','Navarrete','Onate','Peralta','Reyes','Salazar','Toala','Vasconez','Yanez','Zapata',
 'Anchundia','Baque','Cagua','Demera','Figueroa','Ganchozo','Hurtado','Jama','Litardo','Muentes']) v;

INSERT INTO f0_dom(i,v) SELECT row_number() OVER (), v FROM unnest(ARRAY[
 'gmail.com','hotmail.com','outlook.com','yahoo.es','gmail.com','hotmail.com','gmail.com','live.com']) v;

INSERT INTO f0_emp(i,v) SELECT row_number() OVER (), v FROM unnest(ARRAY[
 'Comercial','Distribuidora','Importadora','Almacenes','Corporacion','Negocios','Inversiones',
 'Servicios','Grupo','Representaciones','Suministros','Abastos']) v;

-- Reparto geografico: los 13 municipios que ya usan los 75 clientes actuales,
-- con SUS proporciones medidas (Quevedo 22,7 %, Guayaquil 12 %, …), mas un 8 %
-- repartido en las 8 ciudades restantes del catalogo — la expansion natural.
INSERT INTO f0_ciu(i, ciudad_id, prov, peso)
SELECT row_number() OVER (), c, p, w FROM (VALUES
 ( 1::bigint,'12',209),( 22::bigint,'09',110),( 2::bigint,'12', 74),( 39::bigint,'12', 61),
 (31::bigint,'18', 61),( 40::bigint,'12', 61),( 23::bigint,'09', 49),( 29::bigint,'13', 49),
 (35::bigint,'23', 49),( 25::bigint,'17', 49),( 30::bigint,'07', 49),( 24::bigint,'09', 49),
 (28::bigint,'13', 49),
 (27::bigint,'01', 10),( 32::bigint,'06', 10),( 33::bigint,'11', 10),( 34::bigint,'08', 10),
 (36::bigint,'10', 10),( 37::bigint,'05', 10),( 38::bigint,'24', 10),( 26::bigint,'17', 11)
) AS t(c,p,w);
UPDATE f0_ciu SET acum = s FROM (SELECT i, sum(peso) OVER (ORDER BY i) s FROM f0_ciu) x WHERE f0_ciu.i = x.i;

-- ── 1. VENDEDORES (93 nuevos; con los 7 actuales quedan 100) ────────────────
BEGIN;

INSERT INTO usuario (id, email, password_hash, nombre, apellido, telefono,
                     email_verificado, activo, fecha_creacion)
OVERRIDING SYSTEM VALUE
SELECT 900000000 + g,
       lower(nh.v) || '.' || lower(ap.v) || (100 + g)::text || '@retailmind.com',
       '$2a$10$' || left(md5('f0v' || g) || md5('f0w' || g), 53),
       nh.v, ap.v,
       '09' || lpad(((g::bigint * 48627119) % 100000000)::text, 8, '0'),
       true, true,
       timestamptz '2025-01-15 08:00:00-05' + ((g % 560) * interval '1 day')
FROM generate_series(1, 93) g
JOIN f0_nom_h nh ON nh.i = ((g * 13) % 50) + 1
JOIN f0_ape   ap ON ap.i = ((g * 29) % 80) + 1
ON CONFLICT (id) DO NOTHING;

INSERT INTO usuario_rol (id, usuario_id, rol_id, fecha_creacion)
OVERRIDING SYSTEM VALUE
SELECT 900000000 + g, 900000000 + g, 3, timestamptz '2025-01-15 08:00:00-05'
FROM generate_series(1, 93) g
ON CONFLICT (id) DO NOTHING;

SELECT fn_carga_registrar('fase0','94_fase0_personas','usuario_vendedor',
       900000001, 900000093, 93, 'Vendedores; con los 7 previos quedan 100.');
COMMIT;

-- ── 2. CLIENTES: usuario + usuario_rol + cliente + direccion, por lotes ─────
--    Los ids de cliente van desplazados 1.000.000 sobre los de vendedor para
--    que las dos poblaciones no compartan tramo dentro del rango reservado.
DO $carga$
DECLARE
    total     int := 50000;
    lote      int := 10000;
    base_u    bigint := 901000000;   -- usuario / usuario_rol / direccion de cliente
    base_c    bigint := 900000000;   -- cliente
    desde     int; hasta int; ya bigint;
BEGIN
    FOR desde IN 1 .. total BY lote LOOP
        hasta := LEAST(desde + lote - 1, total);

        SELECT count(*) INTO ya FROM cliente
         WHERE id BETWEEN base_c + desde AND base_c + hasta;
        IF ya = (hasta - desde + 1) THEN
            RAISE NOTICE 'Lote %-% ya cargado, se salta.', desde, hasta;
            CONTINUE;
        END IF;

        -- 2.1 usuario
        INSERT INTO usuario (id, email, password_hash, nombre, apellido, telefono,
                             email_verificado, activo, fecha_creacion)
        OVERRIDING SYSTEM VALUE
        SELECT base_u + g,
               -- El sufijo arranca en 1001 a proposito: los clientes ya sembrados
               -- usan el mismo patron `nombre.apellidoN@` con N de 1 a 70, y un
               -- solapamiento daria una violacion de `uq_usuario_email` a media carga.
               lower(x.nombre_pila) || '.' || lower(x.apellido) || (g + 1000)::text || '@' || d.v,
               '$2a$10$' || left(md5('f0c' || g) || md5('f0d' || g), 53),
               x.nombre_pila, x.apellido,
               '09' || lpad(((g::bigint * 48627119 + 31) % 100000000)::text, 8, '0'),
               (g % 5) <> 0, true,
               timestamptz '2025-01-01 07:00:00-05'
                 + ((g * 586.0 / 50000.0)::int * interval '1 day')
                 + ((g % 1440) * interval '1 minute')
        FROM generate_series(desde, hasta) g
        JOIN LATERAL (
            SELECT CASE WHEN g % 2 = 0 THEN (SELECT v FROM f0_nom_m WHERE i = ((g * 17) % 50) + 1)
                        ELSE                (SELECT v FROM f0_nom_h WHERE i = ((g * 23) % 50) + 1) END AS nombre_pila,
                   (SELECT v FROM f0_ape WHERE i = ((g * 31) % 80) + 1) AS apellido
        ) x ON true
        JOIN f0_dom d ON d.i = (g % 8) + 1
        ON CONFLICT (id) DO NOTHING;

        -- 2.2 usuario_rol (CLIENTE = 7)
        INSERT INTO usuario_rol (id, usuario_id, rol_id, fecha_creacion)
        OVERRIDING SYSTEM VALUE
        SELECT base_u + g, base_u + g, 7, u.fecha_creacion
        FROM generate_series(desde, hasta) g JOIN usuario u ON u.id = base_u + g
        ON CONFLICT (id) DO NOTHING;

        -- 2.3 cliente. 8 % empresas (`ruc`), 92 % personas (`cedula`).
        --     El digito verificador se calcula de verdad sobre los 9 primeros.
        INSERT INTO cliente (id, usuario_id, tipo_identificacion, numero_identificacion,
                             nombre, apellido, email, telefono, fecha_nacimiento, genero,
                             acepta_marketing, activo, fecha_creacion)
        OVERRIDING SYSTEM VALUE
        SELECT base_c + g,
               base_u + g,
               CASE WHEN g % 12 = 0 THEN 'ruc' ELSE 'cedula' END,
               CASE WHEN g % 12 = 0 THEN c.cedula || '001' ELSE c.cedula END,
               CASE WHEN g % 12 = 0
                    THEN e.v || ' ' || u.apellido || CASE WHEN g % 3 = 0 THEN ' Cia. Ltda.' ELSE ' S.A.' END
                    ELSE u.nombre END,
               CASE WHEN g % 12 = 0 THEN NULL ELSE u.apellido END,
               u.email, u.telefono,
               (date '1960-01-01' + ((g * 4813) % 16000) * interval '1 day')::date,
               CASE WHEN g % 12 = 0 THEN 'no_indica'
                    WHEN g % 2 = 0 THEN 'femenino' ELSE 'masculino' END,
               (g % 3) = 0, true, u.fecha_creacion
        FROM generate_series(desde, hasta) g
        JOIN usuario u ON u.id = base_u + g
        JOIN f0_emp e ON e.i = (g % 12) + 1
        JOIN LATERAL (
            SELECT b.b9 || ((10 - (
                     (CASE WHEN substr(b.b9,1,1)::int*2 > 9 THEN substr(b.b9,1,1)::int*2 - 9 ELSE substr(b.b9,1,1)::int*2 END)
                   +  substr(b.b9,2,1)::int
                   + (CASE WHEN substr(b.b9,3,1)::int*2 > 9 THEN substr(b.b9,3,1)::int*2 - 9 ELSE substr(b.b9,3,1)::int*2 END)
                   +  substr(b.b9,4,1)::int
                   + (CASE WHEN substr(b.b9,5,1)::int*2 > 9 THEN substr(b.b9,5,1)::int*2 - 9 ELSE substr(b.b9,5,1)::int*2 END)
                   +  substr(b.b9,6,1)::int
                   + (CASE WHEN substr(b.b9,7,1)::int*2 > 9 THEN substr(b.b9,7,1)::int*2 - 9 ELSE substr(b.b9,7,1)::int*2 END)
                   +  substr(b.b9,8,1)::int
                   + (CASE WHEN substr(b.b9,9,1)::int*2 > 9 THEN substr(b.b9,9,1)::int*2 - 9 ELSE substr(b.b9,9,1)::int*2 END)
                 ) % 10) % 10)::text AS cedula
            FROM (SELECT ci.prov || ((g * 7) % 6)::text
                         || reverse(lpad(((g::bigint * 486187) % 1000000)::text, 6, '0')) AS b9
                  FROM f0_ciu ci
                  WHERE ci.acum >= ((g * 613) % 1000) + 1 ORDER BY ci.acum LIMIT 1) b
        ) c ON true
        ON CONFLICT (id) DO NOTHING;

        -- 2.4 direccion (una por cliente, predeterminada)
        INSERT INTO direccion (id, usuario_id, ciudad_id, tipo, alias, destinatario,
                               calle_principal, calle_secundaria, numero, referencia,
                               telefono, es_predeterminada, activo, fecha_creacion)
        OVERRIDING SYSTEM VALUE
        SELECT base_u + g, base_u + g, ci.ciudad_id, 'envio', 'Casa',
               u.nombre || ' ' || COALESCE(u.apellido,''),
               v.calle, w.calle2,
               (100 + ((g * 617) % 900))::text,
               'A ' || (1 + (g % 5))::text || ' cuadras de ' || v.ref,
               u.telefono, true, true, u.fecha_creacion
        FROM generate_series(desde, hasta) g
        JOIN usuario u ON u.id = base_u + g
        JOIN LATERAL (SELECT c2.ciudad_id FROM f0_ciu c2
                      WHERE c2.acum >= ((g * 613) % 1000) + 1 ORDER BY c2.acum LIMIT 1) ci ON true
        JOIN LATERAL (SELECT (ARRAY['Av. Quito','Av. Bolivar','Calle Sucre','Av. 7 de Octubre',
                                    'Calle Malecon','Av. Guayaquil','Calle Rocafuerte','Av. Amazonas',
                                    'Calle Olmedo','Av. Los Ceibos'])[(g % 10) + 1] AS calle,
                             (ARRAY['el parque central','el mercado municipal','la iglesia matriz',
                                    'el terminal terrestre','el estadio'])[(g % 5) + 1] AS ref) v ON true
        JOIN LATERAL (SELECT (ARRAY['Calle 10 de Agosto','Calle Garcia Moreno','Av. Universitaria',
                                    'Calle Junin','Av. Circunvalacion'])[(g % 5) + 1] AS calle2) w ON true
        ON CONFLICT (id) DO NOTHING;

        COMMIT;
        RAISE NOTICE 'Lote %-% commiteado.', desde, hasta;
    END LOOP;
END
$carga$;

BEGIN;
SELECT fn_carga_registrar('fase0','94_fase0_personas','usuario_cliente',
       901000001, 901050000, (SELECT count(*) FROM usuario WHERE id >= 901000000), 'Usuario de cada cliente.');
SELECT fn_carga_registrar('fase0','94_fase0_personas','cliente',
       900000001, 900050000, (SELECT count(*) FROM cliente WHERE id >= 900000000), '92% cedula, 8% ruc (segmento empresa).');
SELECT fn_carga_registrar('fase0','94_fase0_personas','direccion',
       901000001, 901050000, (SELECT count(*) FROM direccion WHERE id >= 900000000), 'Una direccion predeterminada por cliente.');
SELECT fn_carga_registrar('fase0','94_fase0_personas','usuario_rol',
       900000001, 901050000, (SELECT count(*) FROM usuario_rol WHERE id >= 900000000), 'Rol VENDEDOR (93) y CLIENTE (50.000).');
COMMIT;

\echo ''
SELECT 'usuario' t, count(*) n FROM usuario UNION ALL
SELECT 'cliente', count(*) FROM cliente UNION ALL
SELECT 'direccion', count(*) FROM direccion UNION ALL
SELECT 'usuario_rol', count(*) FROM usuario_rol;
