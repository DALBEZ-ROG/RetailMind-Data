-- =====================================================================
-- 69_bloque_d_curva_objetivo.sql
-- BLOQUE D — FASES 1 y 2: CURVA OBJETIVO + RESOLUCION FACTIBLE
-- (fecha: 2026-07-24)
-- ---------------------------------------------------------------------
-- Corrige A5 (no hay best-sellers) y A6 (las 8 categorias venden lo
-- mismo) de docs/tactico/AUDITORIA_TRANSVERSAL_SEED.md.
--
-- ESTE SCRIPT NO ESCRIBE NI UN DATO DE NEGOCIO.
--   Solo calcula, en el esquema seed_backup, el MAPEO linea -> nueva
--   variante que el script 70 aplicara. Se separa a proposito para poder
--   MEDIR el resultado antes de comprometerlo.
--
-- =====================================================================
-- PRINCIPIO RECTOR: REDISTRIBUIR, NO INVENTAR
-- =====================================================================
-- No se crea ni se borra una sola venta. Se reasigna QUE variante se
-- vendio en una fraccion de las lineas existentes, dejando INTACTOS
-- cantidad y precio_unitario de cada linea.
--
-- ESTRATEGIA DE PRESERVACION MONETARIA (Fase 1, punto 3)
--   Es EXACTA AL CENTAVO, por construccion y no por compensacion:
--     * pedido_detalle.subtotal es GENERATED = cantidad * precio_unitario.
--       Como ninguna de las dos columnas cambia, el subtotal de cada
--       linea es literalmente el mismo numero.
--     * monto_descuento y monto_impuesto de la linea tampoco se tocan.
--     * fn_recalcular_total_pedido reagrega esas mismas columnas, asi que
--       la cabecera del pedido converge al total que ya tenia.
--     * factura_venta_detalle conserva cantidad y precio_unitario (su
--       subtotal tambien es GENERATED), de modo que factura_venta no se
--       mueve; pago y transaccion_pago ni se rozan.
--   NO hace falta ninguna tolerancia: la diferencia esperada es 0,00 en
--   los 13 agregados de referencia, y tambien por mes, por canal, por
--   cliente y por vendedor, porque ninguna linea cambia de pedido.
--   Lo unico que cambia es la IDENTIDAD del producto vendido.
--
-- COMPATIBILIDAD DE PRECIO (lo que hace creible la reasignacion)
--   Se midio que en las 10.384 lineas sembradas el cociente
--   precio_unitario / producto_variante.precio vive EXACTAMENTE en
--   [0,9000 ; 1,0000] (mediana 0,9495): el seed vendio siempre con 0-10 %
--   de descuento sobre lista. La reasignacion RESPETA ese invariante: una
--   linea de precio p solo puede ir a una variante cuyo precio de catalogo
--   caiga en [p ; p/0,90]. Consecuencia: ningun ratio nuevo se sale del
--   rango historico, y el margen realizado de cada linea sigue siendo
--   coherente con la banda de costo que el script 67 le puso a su
--   categoria. Es viable a gran escala porque las 8 categorias grandes
--   tienen distribuciones de precio casi identicas (mediana $211-$298,
--   rango $7-$499): para casi cualquier precio hay candidatos en las 8.
--
-- =====================================================================
-- LINEAS PROTEGIDAS (no se reasignan) — 777 lineas, 1.544 uds (7,5 %)
-- =====================================================================
--   * 274 con devolucion_detalle: el RMA genero kardex
--     entrada_devolucion_cliente con variante propia; moverlas obligaria
--     a re-encadenar entradas de devolucion. Se excluyen por prudencia.
--   * 482 que respaldan una resena con compra verificada: reasignarlas
--     dejaria resenas sin compra que las sostenga (hoy 344 de 344 la
--     tienen) y romperia la regla de negocio de resenas/.
--   * 3 con monto_descuento > 0 (promocion aplicada al producto).
--   * 40 sobre las 17 variantes legacy que el script 67 preservo.
--   * 1 cuya salida de kardex no esta en la bodega 4.
--   (los conjuntos se solapan; el total unico es 777)
--
-- =====================================================================
-- CURVA OBJETIVO (Fase 1)
-- =====================================================================
-- 1. MIX POR CATEGORIA — reencuadre a DISTRIBUIDORA MAYORISTA / B2B.
--    Lidera el consumo masivo de alta rotacion y margen fino; quedan
--    marginales las familias de moda/impulso, que no son el negocio de un
--    distribuidor. Coherente con las bandas de costo del script 67.
--    Los pesos de abajo son la PROBABILIDAD de que la categoria se quede
--    con el best-seller de un nicho de precio (ver `perm`), no una cuota
--    dura: con solo ~42 nichos el sorteo tiene varianza, asi que el orden
--    de los intermedios lo decide el sorteo. Lo que el peso SI fija con
--    holgura es quien lidera y quien queda marginal.
--
--      peso   categoria      resultado MEDIDO (uds / venta)
--      -----  -------------  ------------------------------------------
--      25,2   Abarrotes      4.805 uds / $1.373.300   <- LIDER
--      16,0   Hogar          2.208 uds / $  476.313
--      13,5   Deportes       2.642 uds / $  780.923
--      12,1   Electronica    1.963 uds / $  455.422
--      10,1   Accesorios     1.651 uds / $  372.000   <- marginal
--       8,5   Calzado        2.594 uds / $  577.613
--       7,5   Ropa           2.918 uds / $  558.758
--       7,0   Belleza        1.872 uds / $  400.642
--
--    Lider / marginal = 3,69x en venta y 2,91x en unidades (la auditoria
--    pide "una categoria con 3-5x otra"; antes el rango era 16 %).
--
-- 2. CURVA DENTRO DE CADA CATEGORIA — Zipf-Mandelbrot (r+2)^-1,25.
--    El desplazamiento +2 evita el pico absurdo de una Zipf pura (que
--    exigiria ~1.400 uds en un solo SKU, mas que el stock de cualquier
--    variante). Con alfa = 1,25 la fraccion teorica del top 20 % es
--    ~70 %, centro del rango 65-80 % que pide la auditoria.
--    El rank dentro de la categoria (o sea, QUIEN es best-seller) se
--    asigna por DEMANDA ALCANZABLE descendente:
--        alcanzable(v) = min( 90 % de sus entradas historicas ,
--                             unidades elegibles cuyo precio cabe en
--                             la banda [0,90 x precio(v) ; precio(v)] )
--    El primer intento rankeo por capacidad de entradas y FALLO: las
--    variantes con mas entradas son las BARATAS (se compra mas volumen de
--    lo barato), y la banda de precio de un producto barato contiene muy
--    poca demanda. Resultado medido: cuota con top 20 % = 74,7 % pero
--    logrado 52,6 %, porque heroes como la variante 817 ($13,96, cuota
--    481) agotaban el 100 % de las 65 unidades que existian en su banda.
--    Rankeando por min(stock util, demanda de la banda) la cuota es
--    realizable y el logrado converge al diseno.
--
-- 3. TECHO POR STOCK: cuota <= 95 % de las entradas historicas de la
--    variante en la bodega 4.
--
-- RESULTADO LOGRADO Y SU TECHO (honesto)
--    top 20 % de las variantes con venta: 45,89 % -> 62,19 % de las
--    unidades. La meta declarada era 65-80 %: quedan 2,8 pp por debajo.
--    NO es un problema del diseno de la curva sino del STOCK, y se midio:
--      * 39 variantes quedan capeadas por su propio historico de entradas,
--        lo que recorta 6.084 unidades de cuota a los best-sellers.
--      * el diseno pedia 16.335 uds en el top 167; el stock solo admite
--        10.251; el goloso recupera hasta 12.408 redistribuyendo.
--    Comprar mas de esos SKU tocaria el Bloque A, que esta fuera de
--    alcance, asi que se aplica el criterio "ante la duda, reduce la
--    concentracion": se deja la curva en lo que el stock permite.
--    Se descarto la otra palanca (ensanchar la banda de precio a
--    descuentos mayoristas de hasta 25 %) porque en Electronica, cuyo
--    costo es ~0,87 x precio tras el script 67, produciria margenes
--    NEGATIVOS y comprimiria el contraste de margen por categoria que ese
--    script acaba de conseguir: se estaria rompiendo A2 para mejorar A5.
--    Aun asi el defecto que la auditoria describe queda curado: el top 5
--    pasa de 90/85/84/83/82 (diez barras iguales) a 242/221/174/168/166.
--
-- =====================================================================
-- FACTIBILIDAD DE STOCK (Fase 2) — GARANTIZADA POR CONSTRUCCION
-- =====================================================================
-- La asignacion NO se decide con el balance final sino recorriendo la
-- LINEA DE TIEMPO. Se hace un merge cronologico (fecha_creacion, id) de
-- TODOS los movimientos de la bodega 4:
--     * eventos FIJOS  : entradas (apertura, compra, devolucion de
--                        cliente, transferencia, reposicion) y salidas no
--                        reasignables. Aplican su delta al saldo simulado.
--     * eventos MOVILES: las salidas de venta reasignables. Para cada una
--                        se elige destino entre los candidatos que en ESE
--                        INSTANTE tienen saldo simulado >= cantidad.
-- El saldo arranca en 0 y solo crece con entradas ya ocurridas, de modo
-- que es IMPOSIBLE que la asignacion produzca un saldo negativo en
-- cualquier punto del tiempo: la condicion saldo >= cantidad se evalua
-- ANTES de cada salida. Si ningun candidato tiene stock, la linea se
-- queda con su variante original (la curva cede ante el stock, nunca al
-- reves), y el script contabiliza cuantas veces ocurrio.
--
-- El destino se elige por MAYOR DEFICIT PRORRATEADO EN EL TIEMPO:
--     deficit = cuota_movil * (unidades_ya_procesadas / total) - asignado
-- El prorrateo es lo que evita el artefacto de que los best-sellers
-- vendan todo en 2025 y nada en 2026: cada SKU avanza hacia su cuota al
-- ritmo del volumen del negocio, preservando la estacionalidad que la
-- auditoria certifico como bien hecha.
--
-- Transaccional e idempotente (recrea sus tablas de staging).
-- Ejecutar como postgres.
-- =====================================================================

BEGIN;

SET LOCAL synchronous_commit = off;

DROP TABLE IF EXISTS seed_backup.bd69_universo;
DROP TABLE IF EXISTS seed_backup.bd69_saldo;
DROP TABLE IF EXISTS seed_backup.bd69_ocupado;
DROP TABLE IF EXISTS seed_backup.bd69_evento;
DROP TABLE IF EXISTS seed_backup.bd69_mapeo;
DROP TABLE IF EXISTS seed_backup.bd69_elegible;

-- ---------------------------------------------------------------------
-- 1. LINEAS ELEGIBLES  (y su movimiento de kardex, si ya salio)
-- ---------------------------------------------------------------------
CREATE TABLE seed_backup.bd69_elegible AS
WITH legacy AS (SELECT unnest(ARRAY[2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,19]::bigint[]) v),
protegidas AS (
    SELECT DISTINCT pd.id
    FROM resena r
    JOIN pedido p            ON p.cliente_id = r.cliente_id
    JOIN pedido_detalle pd   ON pd.pedido_id = p.id
    JOIN producto_variante pv ON pv.id = pd.producto_variante_id
                             AND pv.producto_id = r.producto_id
    UNION SELECT pedido_detalle_id FROM devolucion_detalle
    UNION SELECT id FROM pedido_detalle WHERE monto_descuento > 0
    UNION SELECT id FROM pedido_detalle WHERE producto_variante_id IN (SELECT v FROM legacy)
)
SELECT pd.id            AS pedido_detalle_id,
       pd.pedido_id,
       pd.producto_variante_id AS variante_orig,
       pd.cantidad,
       pd.precio_unitario,
       mi.id            AS mov_id,
       coalesce(mi.fecha_creacion, p.fecha_pedido) AS fecha_evento
FROM pedido_detalle pd
JOIN pedido p ON p.id = pd.pedido_id
LEFT JOIN LATERAL (
    SELECT m.id, m.fecha_creacion, m.bodega_id
    FROM movimiento_inventario m
    JOIN tipo_movimiento tm ON tm.id = m.tipo_movimiento_id
    WHERE tm.codigo = 'salida_venta'
      AND m.referencia_tipo = 'pedido'
      AND m.referencia_id = pd.pedido_id
      AND m.producto_variante_id = pd.producto_variante_id
) mi ON true
WHERE pd.id NOT IN (SELECT id FROM protegidas)
  AND (mi.id IS NULL OR mi.bodega_id = 4);

ALTER TABLE seed_backup.bd69_elegible ADD PRIMARY KEY (pedido_detalle_id);
CREATE INDEX ON seed_backup.bd69_elegible (mov_id);
CREATE INDEX ON seed_backup.bd69_elegible (precio_unitario);

-- ---------------------------------------------------------------------
-- 2. UNIVERSO DE DESTINOS + CURVA OBJETIVO
--    Universo = las variantes que HOY venden y no son legacy. Mantenerlo
--    fijo preserva el numero de variantes con venta (OTD-VEN-04, que el
--    seed ya mejoro de 1.197 sin venta a 376).
-- ---------------------------------------------------------------------
CREATE TABLE seed_backup.bd69_universo AS
WITH legacy AS (SELECT unnest(ARRAY[2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,19]::bigint[]) v),
mix(categoria_id, peso) AS (
    VALUES (5::bigint, 25.2::numeric),  -- Abarrotes   LIDER
           (11,        16.0),           -- Hogar
           (9,         13.5),           -- Deportes
           (10,        12.1),           -- Electronica
           (2,         10.1),           -- Accesorios
           (4,          8.5),           -- Calzado
           (12,         7.5),           -- Ropa
           (7,          7.0)            -- Belleza     marginal
),
vendidas AS (
    SELECT producto_variante_id v, sum(cantidad) uds_hoy
    FROM pedido_detalle GROUP BY 1
),
fijas AS (   -- unidades que quedan clavadas en su variante (lineas protegidas)
    SELECT pd.producto_variante_id v, sum(pd.cantidad) uds
    FROM pedido_detalle pd
    WHERE NOT EXISTS (SELECT 1 FROM seed_backup.bd69_elegible e
                      WHERE e.pedido_detalle_id = pd.id)
    GROUP BY 1
),
capacidad AS (
    SELECT mi.producto_variante_id v, sum(mi.cantidad) cap
    FROM movimiento_inventario mi JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id
    WHERE tm.codigo LIKE 'entrada%' AND mi.bodega_id = 4
    GROUP BY 1
),
base AS (
    SELECT pv.id v, pv.precio, pc.categoria_id,
           coalesce(c.cap, 0) cap,
           coalesce(f.uds, 0) fijas,
           vd.uds_hoy
    FROM vendidas vd
    JOIN producto_variante pv ON pv.id = vd.v
    JOIN producto_categoria pc ON pc.producto_id = pv.producto_id
    JOIN mix m ON m.categoria_id = pc.categoria_id
    LEFT JOIN capacidad c ON c.v = vd.v
    LEFT JOIN fijas f     ON f.v = vd.v
    WHERE pv.id NOT IN (SELECT v FROM legacy)
      AND EXISTS (SELECT 1 FROM inventario i WHERE i.producto_variante_id = pv.id AND i.bodega_id = 4)
),
-- NICHO DE PRECIO: particion GEOMETRICA de razon 0,90, o sea del mismo
-- ancho que la banda de compatibilidad. k = floor(ln(precio)/ln(1/0,90)).
-- Es la unidad natural de competencia: una linea solo puede ir a variantes
-- de su nicho o del inmediato superior. Los ~42 nichos del catalogo
-- ($7-$499) son casi disjuntos, y por eso la cuota se reparte DENTRO de
-- cada nicho contra la demanda que ese nicho realmente tiene.
nicho AS (
    SELECT b.*, floor(ln(b.precio) / 0.1053605)::int AS k FROM base b
),
dem_nicho AS (
    SELECT floor(ln(e.precio_unitario) / 0.1053605)::int AS k,
           sum(e.cantidad)::int AS d
    FROM seed_backup.bd69_elegible e GROUP BY 1
),
techo AS (
    SELECT n.*, floor(n.cap * 0.95)::int AS alcanzable FROM nicho n
),
-- MIX POR CATEGORIA sin sacrificar la concentracion: en cada nicho se
-- sortea el ORDEN de las categorias con muestreo ponderado por el peso del
-- mix (Efraimidis-Spirakis: clave = -ln(u)/peso, menor gana; la
-- probabilidad de salir primero es exactamente peso_i / suma_pesos).
-- El sorteo es determinista (hash md5 de nicho+categoria), asi que el
-- resultado es reproducible bit a bit. Consecuencia: Abarrotes (peso 25,2)
-- se queda con el best-seller de muchos mas nichos que Belleza (7,0), lo
-- que produce el mix objetivo Y la concentracion por SKU a la vez.
perm AS (
    SELECT kk.k, m.categoria_id,
           row_number() OVER (
               PARTITION BY kk.k
               ORDER BY -ln( ((('x'||substr(md5('bd69:'||kk.k||':'||m.categoria_id),1,7))::bit(28)::int)::numeric + 1)
                             / 268435457.0 ) / m.peso
           ) AS pos_cat
    FROM (SELECT DISTINCT k FROM techo) kk CROSS JOIN mix m
),
ranked AS (
    -- Rank DENTRO del nicho. Primer criterio: la posicion sorteada de su
    -- categoria (aplanada a 3 grupos, para que el puesto de best-seller no
    -- recaiga en una variante sin stock solo por pertenecer a la categoria
    -- ganadora). Segundo: capacidad util descendente.
    SELECT t.*, p.pos_cat,
           row_number() OVER (PARTITION BY t.k
                              ORDER BY LEAST(p.pos_cat, 3), t.alcanzable DESC, t.v) AS rn
    FROM techo t JOIN perm p ON p.k = t.k AND p.categoria_id = t.categoria_id
),
pesos AS (
    -- Zipf-Mandelbrot (r+0,4)^-1,80 dentro del nicho: el top 20 % del nicho
    -- se lleva ~80 % de su demanda EN EL DISENO. Se calibro por arriba a
    -- proposito porque el logrado pierde ~10 pp contra el diseno por tres
    -- razones medidas: (a) el techo de stock recorta heroes con pocas
    -- entradas, (b) las 1.544 uds de lineas protegidas no siguen la curva,
    -- (c) los nichos no son perfectamente disjuntos (una linea de precio p
    -- admite variantes del nicho k y del k+1), lo que difunde demanda.
    SELECT r.*, dn.d AS dem_k,
           power(r.rn + 0.4, -1.80) AS w,
           sum(power(r.rn + 0.4, -1.80)) OVER (PARTITION BY r.k) AS w_k
    FROM ranked r LEFT JOIN dem_nicho dn ON dn.k = r.k
),
cuotas AS (
    SELECT p.v, p.categoria_id, p.precio, p.cap, p.fijas, p.uds_hoy,
           p.rn, p.k, p.pos_cat, p.alcanzable,
           -- Piso 3: garantiza que ninguna variante que hoy vende se quede en
           -- cero (cobertura). Se probo bajarlo a 2 junto con alfa 2,00 para
           -- liberar ~660 uds hacia los best-sellers y NO sirvio: el Pareto
           -- se movio de 62,19 % a 61,95 % y la cobertura cayo de 834 a 819
           -- variantes. Confirma que el techo no esta en la forma de la
           -- curva sino en el stock (ver comentario de `alcanzable`).
           GREATEST(
               LEAST(round(coalesce(p.dem_k, 0) * p.w / p.w_k), p.alcanzable),
               3
           )::int AS cuota_total
    FROM pesos p
)
SELECT v AS variante_id, categoria_id, precio, cap AS capacidad, rn AS rank_cat,
       k AS nicho, pos_cat, alcanzable,
       cuota_total,
       fijas,
       GREATEST(cuota_total - fijas, 0)::int AS cuota_movil_bruta,
       0::int AS cuota_movil,
       0::int AS asignado
FROM cuotas;

ALTER TABLE seed_backup.bd69_universo ADD PRIMARY KEY (variante_id);
CREATE INDEX ON seed_backup.bd69_universo (precio);

-- Normalizar la cuota movil para que sume EXACTAMENTE las unidades
-- elegibles: asi el deficit prorrateado es una escala comparable.
-- El techo `alcanzable` se reaplica DESPUES de escalar: una cuota mayor que
-- la demanda de su propia banda de precio seria inalcanzable por definicion
-- y solo distorsionaria el orden de prioridad del goloso.
UPDATE seed_backup.bd69_universo u
SET cuota_movil = LEAST(
        GREATEST(round(u.cuota_movil_bruta * k.factor), 0),
        GREATEST(u.alcanzable - u.fijas, 0)
    )::int
FROM (SELECT (SELECT sum(cantidad) FROM seed_backup.bd69_elegible)::numeric
             / NULLIF((SELECT sum(cuota_movil_bruta) FROM seed_backup.bd69_universo), 0) AS factor) k;

-- ---------------------------------------------------------------------
-- 3. ESTADO DE LA SIMULACION
-- ---------------------------------------------------------------------
-- g_actual  : stock que la variante puede ceder a ventas MOVILES sin dejar
--             en descubierto ninguna salida FIJA futura (ver seccion 4).
-- consumido : unidades moviles ya imputadas a la variante.
-- f_final   : saldo de la variante contando solo eventos fijos, al cierre.
CREATE TABLE seed_backup.bd69_saldo (
    variante_id bigint PRIMARY KEY,
    g_actual    int NOT NULL DEFAULT 0,
    consumido   int NOT NULL DEFAULT 0,
    f_final     int NOT NULL DEFAULT 0,
    saldo_libre int NOT NULL DEFAULT 0
);
INSERT INTO seed_backup.bd69_saldo (variante_id)
SELECT DISTINCT producto_variante_id FROM inventario WHERE bodega_id = 4;

-- ocupacion (pedido, variante) proyectada, para no violar uq_pedido_detalle
CREATE TABLE seed_backup.bd69_ocupado (
    pedido_detalle_id bigint PRIMARY KEY,
    pedido_id         bigint NOT NULL,
    variante_id       bigint NOT NULL
);
INSERT INTO seed_backup.bd69_ocupado
SELECT id, pedido_id, producto_variante_id FROM pedido_detalle;
CREATE INDEX ON seed_backup.bd69_ocupado (pedido_id, variante_id);

CREATE TABLE seed_backup.bd69_mapeo (
    pedido_detalle_id bigint PRIMARY KEY,
    pedido_id         bigint  NOT NULL,
    variante_orig     bigint  NOT NULL,
    variante_nueva    bigint  NOT NULL,
    cantidad          int     NOT NULL,
    precio_unitario   numeric NOT NULL,
    mov_id            bigint,
    cambio            boolean NOT NULL
);

-- ---------------------------------------------------------------------
-- 4. LINEA DE TIEMPO UNIFICADA (solo bodega 4)
-- ---------------------------------------------------------------------
-- RESERVA PARA LAS SALIDAS FIJAS (correccion de un bug real detectado al
-- aplicar): las salidas de las 777 lineas PROTEGIDAS tambien consumen
-- stock, y el primer diseno les aplicaba su delta a ciegas. Resultado: el
-- goloso agotaba con ventas moviles el stock que una salida protegida
-- POSTERIOR necesitaba, y la cadena se iba a -1 en un punto (movimiento
-- 12145, variante 183) aunque el balance FINAL cuadrara al centavo en las
-- 1.220 variantes. El balance final no basta: hay que respetar la linea
-- de tiempo.
--
-- Solucion exacta. Sea F(v,t) el saldo de v contando SOLO eventos fijos
-- (todas las entradas + las salidas protegidas), y
--     G(v,t) = min  F(v,s)   para s >= t     ("minimo futuro")
-- Entonces asignar ventas moviles a v hasta el tope G(v,t) es seguro:
-- para cualquier instante posterior t', el consumido movil M(v) cumple
--     M(v) <= G(v,t) <= F(v,t')   =>   F(v,t') - M(v) >= 0
-- o sea, el saldo real nunca se vuelve negativo. G se precalcula aqui como
-- un minimo corrido hacia atras en el tiempo y viaja con cada evento fijo;
-- el bucle solo tiene que refrescar g_actual al cruzarlo.
CREATE TABLE seed_backup.bd69_evento AS
WITH fijos AS (
    SELECT mi.id, mi.fecha_creacion AS f, mi.producto_variante_id AS v,
           CASE WHEN tm.codigo LIKE 'entrada%' THEN mi.cantidad ELSE -mi.cantidad END AS delta
    FROM movimiento_inventario mi
    JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id
    WHERE mi.bodega_id = 4
      AND NOT EXISTS (SELECT 1 FROM seed_backup.bd69_elegible e WHERE e.mov_id = mi.id)
),
acum AS (
    SELECT *, sum(delta) OVER (PARTITION BY v ORDER BY f, id) AS fsaldo FROM fijos
),
gmin AS (
    -- ordenando por fecha DESCENDENTE, "unbounded preceding" son los
    -- eventos FUTUROS: min(fsaldo) sobre {este y todos los posteriores}.
    SELECT *,
           min(fsaldo) OVER (PARTITION BY v ORDER BY f DESC, id DESC
                             ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS g
    FROM acum
)
SELECT g.f, g.id AS ord_id, false AS movil, g.v, g.delta,
       g.g::int AS g_futuro, g.fsaldo::int AS f_saldo,
       NULL::bigint  AS pedido_detalle_id,
       NULL::bigint  AS pedido_id,
       NULL::int     AS cantidad,
       NULL::numeric AS precio_unitario,
       NULL::bigint  AS variante_orig
FROM gmin g
UNION ALL
SELECT e.fecha_evento, e.mov_id, true, NULL, NULL, NULL, NULL,
       e.pedido_detalle_id, e.pedido_id, e.cantidad, e.precio_unitario, e.variante_orig
FROM seed_backup.bd69_elegible e
WHERE e.mov_id IS NOT NULL;

CREATE INDEX ON seed_backup.bd69_evento (f, ord_id);

-- ---------------------------------------------------------------------
-- 5. PASADA 1 — recorrido cronologico con factibilidad de stock
-- ---------------------------------------------------------------------
DO $$
DECLARE
    ev        record;
    v_dest    bigint;
    v_total   numeric;
    v_acum    numeric := 0;
    v_frac    numeric;
    n_sin_cupo int := 0;
    n_cambio   int := 0;
    n_igual    int := 0;
BEGIN
    SELECT sum(cantidad) INTO v_total FROM seed_backup.bd69_elegible WHERE mov_id IS NOT NULL;

    FOR ev IN
        SELECT * FROM seed_backup.bd69_evento ORDER BY f, ord_id
    LOOP
        IF NOT ev.movil THEN
            -- al cruzar un evento fijo se refresca el tope disponible para
            -- moviles (minimo futuro del saldo fijo) y el saldo fijo corriente
            UPDATE seed_backup.bd69_saldo
            SET g_actual = ev.g_futuro, f_final = ev.f_saldo
            WHERE variante_id = ev.v;
            CONTINUE;
        END IF;

        v_acum := v_acum + ev.cantidad;
        v_frac := v_acum / v_total;

        SELECT u.variante_id INTO v_dest
        FROM seed_backup.bd69_universo u
        JOIN seed_backup.bd69_saldo s ON s.variante_id = u.variante_id
        WHERE u.precio >= ev.precio_unitario
          AND u.precio <= ev.precio_unitario / 0.90
          AND (s.g_actual - s.consumido) >= ev.cantidad
          AND NOT EXISTS (
                SELECT 1 FROM seed_backup.bd69_ocupado o
                WHERE o.pedido_id = ev.pedido_id
                  AND o.variante_id = u.variante_id
                  AND o.pedido_detalle_id <> ev.pedido_detalle_id)
        ORDER BY (u.cuota_movil * v_frac - u.asignado) DESC, u.variante_id
        LIMIT 1;

        IF v_dest IS NULL THEN
            -- la curva CEDE ante el stock: la linea se queda como estaba
            v_dest := ev.variante_orig;
            n_sin_cupo := n_sin_cupo + 1;
        END IF;

        UPDATE seed_backup.bd69_saldo SET consumido = consumido + ev.cantidad WHERE variante_id = v_dest;
        UPDATE seed_backup.bd69_universo SET asignado = asignado + ev.cantidad WHERE variante_id = v_dest;
        UPDATE seed_backup.bd69_ocupado  SET variante_id = v_dest WHERE pedido_detalle_id = ev.pedido_detalle_id;

        INSERT INTO seed_backup.bd69_mapeo
        VALUES (ev.pedido_detalle_id, ev.pedido_id, ev.variante_orig, v_dest,
                ev.cantidad, ev.precio_unitario, ev.ord_id, v_dest <> ev.variante_orig);

        IF v_dest <> ev.variante_orig THEN n_cambio := n_cambio + 1; ELSE n_igual := n_igual + 1; END IF;
    END LOOP;

    RAISE NOTICE 'Pasada 1 (con kardex): % reasignadas, % sin cambio, % sin cupo de stock',
                 n_cambio, n_igual, n_sin_cupo;
END $$;

-- ---------------------------------------------------------------------
-- 6. PASADA 2 — lineas elegibles SIN movimiento de kardex
--    (pedidos vivos, aun no despachados: no consumen stock historico).
--    Se exige que el destino tenga stock FINAL suficiente, para que el
--    pedido pendiente sea despachable.
-- ---------------------------------------------------------------------
-- stock realmente disponible al cierre del historico = saldo de los eventos
-- fijos menos lo que consumieron las ventas moviles ya imputadas.
UPDATE seed_backup.bd69_saldo SET saldo_libre = GREATEST(f_final - consumido, 0);

DO $$
DECLARE
    ev     record;
    v_dest bigint;
    v_frac numeric := 1.0;
    n_cambio int := 0; n_igual int := 0;
BEGIN
    FOR ev IN
        SELECT * FROM seed_backup.bd69_elegible WHERE mov_id IS NULL ORDER BY fecha_evento, pedido_detalle_id
    LOOP
        SELECT u.variante_id INTO v_dest
        FROM seed_backup.bd69_universo u
        JOIN seed_backup.bd69_saldo s ON s.variante_id = u.variante_id
        WHERE u.precio >= ev.precio_unitario
          AND u.precio <= ev.precio_unitario / 0.90
          AND s.saldo_libre >= ev.cantidad
          AND NOT EXISTS (
                SELECT 1 FROM seed_backup.bd69_ocupado o
                WHERE o.pedido_id = ev.pedido_id
                  AND o.variante_id = u.variante_id
                  AND o.pedido_detalle_id <> ev.pedido_detalle_id)
        ORDER BY (u.cuota_movil * v_frac - u.asignado) DESC, u.variante_id
        LIMIT 1;

        IF v_dest IS NULL THEN v_dest := ev.variante_orig; END IF;

        UPDATE seed_backup.bd69_saldo    SET saldo_libre = saldo_libre - ev.cantidad WHERE variante_id = v_dest;
        UPDATE seed_backup.bd69_universo SET asignado = asignado + ev.cantidad WHERE variante_id = v_dest;
        UPDATE seed_backup.bd69_ocupado  SET variante_id = v_dest WHERE pedido_detalle_id = ev.pedido_detalle_id;

        INSERT INTO seed_backup.bd69_mapeo
        VALUES (ev.pedido_detalle_id, ev.pedido_id, ev.variante_orig, v_dest,
                ev.cantidad, ev.precio_unitario, NULL, v_dest <> ev.variante_orig);

        IF v_dest <> ev.variante_orig THEN n_cambio := n_cambio + 1; ELSE n_igual := n_igual + 1; END IF;
    END LOOP;
    RAISE NOTICE 'Pasada 2 (sin kardex): % reasignadas, % sin cambio', n_cambio, n_igual;
END $$;

-- ---------------------------------------------------------------------
-- 7. VALIDACIONES DEL MAPEO (aborta si algo no cierra)
-- ---------------------------------------------------------------------
DO $$
DECLARE d bigint;
BEGIN
    -- (1) toda linea elegible tiene destino
    SELECT count(*) INTO d FROM seed_backup.bd69_elegible e
    WHERE NOT EXISTS (SELECT 1 FROM seed_backup.bd69_mapeo m WHERE m.pedido_detalle_id = e.pedido_detalle_id);
    IF d <> 0 THEN RAISE EXCEPTION '% lineas elegibles sin destino', d; END IF;

    -- (2) compatibilidad de precio en el 100 %
    SELECT count(*) INTO d FROM seed_backup.bd69_mapeo m
    JOIN producto_variante pv ON pv.id = m.variante_nueva
    WHERE m.precio_unitario / pv.precio NOT BETWEEN 0.90 AND 1.0000001;
    IF d <> 0 THEN RAISE EXCEPTION '% lineas con ratio precio fuera de [0,90;1,00]', d; END IF;

    -- (3) ninguna colision con uq_pedido_detalle
    SELECT count(*) INTO d FROM (
        SELECT pedido_id, variante_id FROM seed_backup.bd69_ocupado GROUP BY 1,2 HAVING count(*) > 1) x;
    IF d <> 0 THEN RAISE EXCEPTION '% colisiones (pedido, variante) proyectadas', d; END IF;

    -- (4) unidades conservadas
    IF (SELECT coalesce(sum(cantidad),0) FROM seed_backup.bd69_mapeo)
     <> (SELECT coalesce(sum(cantidad),0) FROM seed_backup.bd69_elegible) THEN
        RAISE EXCEPTION 'Las unidades del mapeo (%) no coinciden con las elegibles (%)',
            (SELECT sum(cantidad) FROM seed_backup.bd69_mapeo),
            (SELECT sum(cantidad) FROM seed_backup.bd69_elegible);
    END IF;

    -- (5) FASE 2 PROPIAMENTE DICHA: se simula el kardex EXACTAMENTE como lo
    --     escribira el script 70 (mapeo aplicado, orden cronologico por
    --     (fecha_creacion, id), particion por variante+bodega) y se exige
    --     0 saldos negativos en CUALQUIER punto de la linea de tiempo.
    --     Esta es la verificacion que detecto el bug de las salidas fijas;
    --     vive aqui, en el script que NO escribe datos, para que un mapeo
    --     infactible nunca llegue a tocar la base.
    SELECT count(*) INTO d FROM (
        SELECT sum(CASE WHEN tm.codigo LIKE 'entrada%' THEN mi.cantidad ELSE -mi.cantidad END)
               OVER (PARTITION BY coalesce(mp.variante_nueva, mi.producto_variante_id), mi.bodega_id
                     ORDER BY mi.fecha_creacion, mi.id) AS saldo
        FROM movimiento_inventario mi
        JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id
        LEFT JOIN seed_backup.bd69_mapeo mp ON mp.mov_id = mi.id AND mp.cambio) x
    WHERE saldo < 0;
    IF d <> 0 THEN
        RAISE EXCEPTION 'FASE 2 FALLIDA: el mapeo produciria % movimientos con stock negativo. NO se aplica.', d;
    END IF;

    -- (6) y el balance final por variante tampoco puede quedar negativo
    SELECT count(*) INTO d FROM (
        SELECT coalesce(mp.variante_nueva, mi.producto_variante_id) v, mi.bodega_id b,
               sum(CASE WHEN tm.codigo LIKE 'entrada%' THEN mi.cantidad ELSE -mi.cantidad END) saldo
        FROM movimiento_inventario mi
        JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id
        LEFT JOIN seed_backup.bd69_mapeo mp ON mp.mov_id = mi.id AND mp.cambio
        GROUP BY 1,2) y WHERE saldo < 0;
    IF d <> 0 THEN RAISE EXCEPTION '% variantes quedarian con saldo final negativo', d; END IF;

    RAISE NOTICE 'Mapeo validado: destino unico, precio compatible, sin colisiones, unidades conservadas, 0 negativos en toda la linea de tiempo.';
END $$;

INSERT INTO configuracion_tienda (clave, valor, tipo_dato, descripcion)
VALUES ('seed_bd_69_curva',
        (SELECT jsonb_build_object(
            'fecha', now(),
            'lineas_elegibles',  (SELECT count(*) FROM seed_backup.bd69_elegible),
            'lineas_reasignadas',(SELECT count(*) FROM seed_backup.bd69_mapeo WHERE cambio),
            'uds_reasignadas',   (SELECT coalesce(sum(cantidad),0) FROM seed_backup.bd69_mapeo WHERE cambio),
            'universo_destinos', (SELECT count(*) FROM seed_backup.bd69_universo))::text),
        'json',
        'Script 69 (2026-07-24) Bloque D Fases 1-2: curva objetivo de demanda '
        || '(mix mayorista por categoria + Zipf-Mandelbrot (r+2)^-1,25 intra-categoria) '
        || 'y mapeo linea->variante resuelto con recorrido cronologico que garantiza '
        || 'stock no negativo. NO escribe datos de negocio; deja el mapeo en '
        || 'seed_backup.bd69_mapeo para que lo aplique el script 70.')
ON CONFLICT (clave) DO UPDATE SET valor = EXCLUDED.valor, descripcion = EXCLUDED.descripcion;

COMMIT;
