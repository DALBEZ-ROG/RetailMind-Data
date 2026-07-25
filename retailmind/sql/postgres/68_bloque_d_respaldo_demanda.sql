-- =====================================================================
-- 68_bloque_d_respaldo_demanda.sql
-- BLOQUE D — FASE 0: RESPALDO RESTAURABLE  (fecha: 2026-07-24)
-- ---------------------------------------------------------------------
-- Respaldo COMPLETO y verificable del estado previo a la redistribucion
-- de demanda (hallazgos A5 / A6 de
-- docs/tactico/AUDITORIA_TRANSVERSAL_SEED.md).
--
-- QUE SE RESPALDA
--   Las 4 tablas que el Bloque D va a escribir, con TODAS las columnas
--   necesarias para restaurar bit a bit:
--     * pedido_detalle        -> id, producto_variante_id, nombre_producto,
--                                sku, cantidad, precio_unitario,
--                                monto_descuento, monto_impuesto
--     * factura_venta_detalle -> id, pedido_detalle_id, producto_variante_id,
--                                descripcion, cantidad, precio_unitario
--     * movimiento_inventario -> id, producto_variante_id, bodega_id,
--                                tipo_movimiento_id, cantidad,
--                                stock_anterior, stock_nuevo, fecha_creacion
--     * inventario            -> id, producto_variante_id, bodega_id,
--                                stock_actual
--   Se incluyen tambien las columnas que el Bloque D NO modifica
--   (cantidad, precio_unitario, montos, tipo, fecha): sirven de testigo
--   para PROBAR despues que no cambiaron.
--
--   Ademas se congelan los AGREGADOS MONETARIOS de referencia
--   (seed_backup.bd68_agregados, momento='antes') para el contraste
--   antes/despues exigido por la verificacion (d).
--
-- DONDE
--   Esquema dedicado `seed_backup`. NO se crea ninguna tabla nueva en
--   `public`: el conteo de tablas de negocio queda intacto.
--
-- COMO RESTAURA  (ver 99_revert_bloque_d_demanda.sql)
--   La reversion es un UPDATE ... FROM contra cada snapshot, emparejado
--   por clave primaria (id). Como el Bloque D solo hace UPDATE (jamas
--   INSERT ni DELETE en tablas de negocio), el conjunto de ids es
--   identico antes y despues, de modo que el UPDATE por id devuelve
--   TODAS las filas a su valor original. Orden de restauracion:
--     1) inventario.stock_actual            (sin dependencias)
--     2) movimiento_inventario              (variante + cadena de stock)
--     3) factura_venta_detalle              (variante + descripcion)
--     4) pedido_detalle                     (variante + nombre + sku)
--   pedido_detalle va al final porque su trigger AFTER recalcula la
--   cabecera del pedido; al restaurarse cantidad/precio identicos, el
--   recalculo converge al mismo total. El script de reversion valida al
--   cierre que las 4 tablas quedaron identicas al snapshot y aborta
--   (RAISE EXCEPTION dentro de la transaccion) si alguna difiere.
--
-- IDEMPOTENCIA
--   CREATE ... IF NOT EXISTS y carga condicionada a tabla vacia: correr
--   este script dos veces NO pisa el snapshot original. Es deliberado:
--   el respaldo debe seguir apuntando al estado PRE-bloque D aunque se
--   reejecute despues de aplicar el bloque.
--
-- Transaccional. Ejecutar como postgres. NO modifica ni un dato de negocio.
-- =====================================================================

BEGIN;

CREATE SCHEMA IF NOT EXISTS seed_backup;

COMMENT ON SCHEMA seed_backup IS
  'Snapshots de reversion de los bloques de siembra. No contiene datos de '
  'negocio vivos: solo copias para restaurar. Ninguna aplicacion lo lee.';

-- ---------------------------------------------------------------------
-- 1. SNAPSHOTS
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS seed_backup.bd68_pedido_detalle (
    id                   bigint PRIMARY KEY,
    producto_variante_id bigint  NOT NULL,
    nombre_producto      varchar NOT NULL,
    sku                  varchar NOT NULL,
    cantidad             integer NOT NULL,
    precio_unitario      numeric NOT NULL,
    monto_descuento      numeric NOT NULL,
    monto_impuesto       numeric NOT NULL
);

CREATE TABLE IF NOT EXISTS seed_backup.bd68_factura_venta_detalle (
    id                   bigint PRIMARY KEY,
    pedido_detalle_id    bigint,
    producto_variante_id bigint,
    descripcion          varchar,
    cantidad             integer NOT NULL,
    precio_unitario      numeric NOT NULL
);

CREATE TABLE IF NOT EXISTS seed_backup.bd68_movimiento_inventario (
    id                   bigint PRIMARY KEY,
    producto_variante_id bigint  NOT NULL,
    bodega_id            bigint  NOT NULL,
    tipo_movimiento_id   bigint  NOT NULL,
    cantidad             integer NOT NULL,
    stock_anterior       integer NOT NULL,
    stock_nuevo          integer NOT NULL,
    fecha_creacion       timestamptz NOT NULL
);

CREATE TABLE IF NOT EXISTS seed_backup.bd68_inventario (
    id                   bigint PRIMARY KEY,
    producto_variante_id bigint  NOT NULL,
    bodega_id            bigint  NOT NULL,
    stock_actual         integer NOT NULL
);

CREATE TABLE IF NOT EXISTS seed_backup.bd68_agregados (
    momento  text NOT NULL,
    clave    text NOT NULL,
    valor    numeric NOT NULL,
    fecha    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (momento, clave)
);

-- ---------------------------------------------------------------------
-- 2. CARGA (solo la PRIMERA vez: si ya hay filas, se respeta el original)
-- ---------------------------------------------------------------------
INSERT INTO seed_backup.bd68_pedido_detalle
SELECT id, producto_variante_id, nombre_producto, sku,
       cantidad, precio_unitario, monto_descuento, monto_impuesto
FROM pedido_detalle
WHERE NOT EXISTS (SELECT 1 FROM seed_backup.bd68_pedido_detalle);

INSERT INTO seed_backup.bd68_factura_venta_detalle
SELECT id, pedido_detalle_id, producto_variante_id, descripcion,
       cantidad, precio_unitario
FROM factura_venta_detalle
WHERE NOT EXISTS (SELECT 1 FROM seed_backup.bd68_factura_venta_detalle);

INSERT INTO seed_backup.bd68_movimiento_inventario
SELECT id, producto_variante_id, bodega_id, tipo_movimiento_id,
       cantidad, stock_anterior, stock_nuevo, fecha_creacion
FROM movimiento_inventario
WHERE NOT EXISTS (SELECT 1 FROM seed_backup.bd68_movimiento_inventario);

INSERT INTO seed_backup.bd68_inventario
SELECT id, producto_variante_id, bodega_id, stock_actual
FROM inventario
WHERE NOT EXISTS (SELECT 1 FROM seed_backup.bd68_inventario);

-- ---------------------------------------------------------------------
-- 3. AGREGADOS MONETARIOS Y FISICOS DE REFERENCIA  (momento='antes')
--    Son los 13 que la auditoria transversal certifico intactos.
-- ---------------------------------------------------------------------
INSERT INTO seed_backup.bd68_agregados (momento, clave, valor)
SELECT 'antes', k, v FROM (
    SELECT 'pedido_total'              k, coalesce(sum(total),0)              v FROM pedido
    UNION ALL SELECT 'pedido_subtotal',      coalesce(sum(subtotal),0)             FROM pedido
    UNION ALL SELECT 'pedido_impuesto',      coalesce(sum(monto_impuesto),0)       FROM pedido
    UNION ALL SELECT 'pedido_descuento',     coalesce(sum(monto_descuento),0)      FROM pedido
    UNION ALL SELECT 'pedido_envio',         coalesce(sum(costo_envio),0)          FROM pedido
    UNION ALL SELECT 'linea_subtotal',       coalesce(sum(subtotal),0)             FROM pedido_detalle
    UNION ALL SELECT 'linea_descuento',      coalesce(sum(monto_descuento),0)      FROM pedido_detalle
    UNION ALL SELECT 'linea_impuesto',       coalesce(sum(monto_impuesto),0)       FROM pedido_detalle
    UNION ALL SELECT 'linea_unidades',       coalesce(sum(cantidad),0)             FROM pedido_detalle
    UNION ALL SELECT 'linea_filas',          count(*)                              FROM pedido_detalle
    UNION ALL SELECT 'factura_venta_total',  coalesce(sum(total),0)                FROM factura_venta
    UNION ALL SELECT 'factura_linea_subtotal', coalesce(sum(subtotal),0)           FROM factura_venta_detalle
    UNION ALL SELECT 'pago_total',           coalesce(sum(monto),0)                FROM pago
    UNION ALL SELECT 'kardex_movs',          count(*)                              FROM movimiento_inventario
    UNION ALL SELECT 'kardex_unidades',      coalesce(sum(cantidad),0)             FROM movimiento_inventario
    UNION ALL SELECT 'inventario_stock',     coalesce(sum(stock_actual),0)         FROM inventario
    UNION ALL SELECT 'variantes_con_venta',  count(DISTINCT producto_variante_id)  FROM pedido_detalle
) t
WHERE NOT EXISTS (SELECT 1 FROM seed_backup.bd68_agregados WHERE momento='antes');

-- ---------------------------------------------------------------------
-- 4. VERIFICACION DEL RESPALDO — aborta si el snapshot no es fiel
-- ---------------------------------------------------------------------
DO $$
DECLARE d bigint;
BEGIN
    SELECT count(*) INTO d FROM (
        SELECT id, producto_variante_id, nombre_producto, sku, cantidad,
               precio_unitario, monto_descuento, monto_impuesto FROM pedido_detalle
        EXCEPT ALL
        SELECT id, producto_variante_id, nombre_producto, sku, cantidad,
               precio_unitario, monto_descuento, monto_impuesto
        FROM seed_backup.bd68_pedido_detalle) x;
    IF d <> 0 THEN RAISE EXCEPTION 'Respaldo INFIEL en pedido_detalle: % filas difieren', d; END IF;

    SELECT count(*) INTO d FROM (
        SELECT id, pedido_detalle_id, producto_variante_id, descripcion, cantidad, precio_unitario
        FROM factura_venta_detalle
        EXCEPT ALL
        SELECT id, pedido_detalle_id, producto_variante_id, descripcion, cantidad, precio_unitario
        FROM seed_backup.bd68_factura_venta_detalle) x;
    IF d <> 0 THEN RAISE EXCEPTION 'Respaldo INFIEL en factura_venta_detalle: % filas difieren', d; END IF;

    SELECT count(*) INTO d FROM (
        SELECT id, producto_variante_id, bodega_id, tipo_movimiento_id, cantidad,
               stock_anterior, stock_nuevo, fecha_creacion FROM movimiento_inventario
        EXCEPT ALL
        SELECT id, producto_variante_id, bodega_id, tipo_movimiento_id, cantidad,
               stock_anterior, stock_nuevo, fecha_creacion
        FROM seed_backup.bd68_movimiento_inventario) x;
    IF d <> 0 THEN RAISE EXCEPTION 'Respaldo INFIEL en movimiento_inventario: % filas difieren', d; END IF;

    SELECT count(*) INTO d FROM (
        SELECT id, producto_variante_id, bodega_id, stock_actual FROM inventario
        EXCEPT ALL
        SELECT id, producto_variante_id, bodega_id, stock_actual FROM seed_backup.bd68_inventario) x;
    IF d <> 0 THEN RAISE EXCEPTION 'Respaldo INFIEL en inventario: % filas difieren', d; END IF;

    RAISE NOTICE 'Respaldo Bloque D verificado: las 4 tablas coinciden fila a fila con el snapshot.';
END $$;

-- ---------------------------------------------------------------------
-- 5. MARCA
-- ---------------------------------------------------------------------
INSERT INTO configuracion_tienda (clave, valor, tipo_dato, descripcion)
VALUES ('seed_bd_68_respaldo',
        (SELECT jsonb_build_object(
                    'fecha', now(),
                    'pedido_detalle',        (SELECT count(*) FROM seed_backup.bd68_pedido_detalle),
                    'factura_venta_detalle', (SELECT count(*) FROM seed_backup.bd68_factura_venta_detalle),
                    'movimiento_inventario', (SELECT count(*) FROM seed_backup.bd68_movimiento_inventario),
                    'inventario',            (SELECT count(*) FROM seed_backup.bd68_inventario))::text),
        'json',
        'Script 68 (2026-07-24) Bloque D Fase 0: respaldo restaurable previo a la '
        || 'redistribucion de demanda (A5/A6). Snapshots en el esquema seed_backup. '
        || 'Reversion completa: 99_revert_bloque_d_demanda.sql.')
ON CONFLICT (clave) DO UPDATE
   SET valor = EXCLUDED.valor, descripcion = EXCLUDED.descripcion;

COMMIT;
