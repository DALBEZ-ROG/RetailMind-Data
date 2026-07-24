-- =====================================================================================
-- 54_datos_maestros_catalogo.sql
-- CARGA DELIBERADA DE DATOS MAESTROS DE CONFIGURACIÓN — 2026-07-23
-- -------------------------------------------------------------------------------------
-- Estos NO son datos transaccionales: son ATRIBUTOS DE CONFIGURACIÓN del catálogo que
-- ninguna operación del negocio llena por sí sola (no hay forma razonable de teclear el
-- peso de 1.221 productos desde un formulario). Se cargan a mano, una sola vez, para
-- habilitar informes tácticos que hoy salen vacíos o inertes
-- (ver docs/tactico/AUDITORIA_DENSIDAD_DATOS.md, sección 5).
--
-- Cuatro columnas, y SOLO estas cuatro, se escriben aquí:
--   1) producto_variante.peso_kg   (hoy 100% NULL en 1.221 variantes)   -> OTD-LOG-11
--   2) inventario.stock_minimo     (1.211/1.227 en el default 0)        -> OTD-INV-01
--   3) inventario.stock_maximo     (hoy 100% NULL en 1.227 filas)       -> OTD-INV-08
--   4) tarifa_envio.costo_por_kg   (0.00 en las 3 tarifas)              -> OTD-LOG-11
--
-- IDEMPOTENTE: cada UPDATE toca solo filas en su valor por defecto/nulo, así que volver a
-- ejecutarlo no reescribe ni distorsiona nada ya cargado. TRANSACCIONAL: todo en un
-- BEGIN/COMMIT. NO crea operaciones (pedidos, pagos, kardex): eso es la siembra de volumen,
-- una tarea posterior y distinta. NO ejecuta DDL: la estructura ya existe.
--
-- Estado PREVIO medido vía MCP (2026-07-23, sólo lectura):
--   peso_kg no nulo:      0 / 1221
--   stock_minimo <> 0:   16 / 1227   (valor único 2, variantes 2..17 — se PRESERVAN)
--   stock_maximo no nulo: 0 / 1227
--   costo_por_kg <> 0:    0 / 3
-- =====================================================================================

BEGIN;

-- -------------------------------------------------------------------------------------
-- 1) producto_variante.peso_kg  — peso realista por VARIANTE
-- Criterio: se deriva del NOMBRE del producto cuando es informativo (balón, mochila,
-- batería, gorra, hoodie, camiseta, zapatilla, bicicleta, mancuerna) y, en su defecto,
-- de la CATEGORÍA (11 reales inspeccionadas: Hogar y Deportes son las más pesadas,
-- Belleza y Ropa las más livianas). Un jitter determinista por id de variante introduce
-- variación dentro de cada grupo (dos tallas del mismo modelo no pesan idéntico) y
-- mantiene el resultado reproducible. Rango global resultante ~0.05 kg (cosmético) a
-- ~14 kg (bicicleta). Cobertura objetivo: 100% con peso > 0.
-- -------------------------------------------------------------------------------------
UPDATE producto_variante pv
SET peso_kg = ROUND((
      CASE
        -- Overrides por nombre (más específicos que la categoría)
        WHEN p.nombre ILIKE '%bicicleta%'                                   THEN 9.00 + (((pv.id*29 + 7) % 100) / 100.0)*5.00
        WHEN p.nombre ILIKE '%mancuerna%' OR p.nombre ILIKE '%kettlebell%'
          OR p.nombre ILIKE '% pesa%'                                        THEN 2.00 + (((pv.id*29 + 7) % 100) / 100.0)*8.00
        WHEN p.nombre ILIKE '%mochila%'                                      THEN 0.60 + (((pv.id*29 + 7) % 100) / 100.0)*0.50
        WHEN p.nombre ILIKE '%balon%' OR p.nombre ILIKE '%balón%'
          OR p.nombre ILIKE '%ball%'  OR p.nombre ILIKE '%pelota%'           THEN 0.40 + (((pv.id*29 + 7) % 100) / 100.0)*0.22
        WHEN p.nombre ILIKE '%bateria%' OR p.nombre ILIKE '%batería%'        THEN 0.25 + (((pv.id*29 + 7) % 100) / 100.0)*0.30
        WHEN p.nombre ILIKE '%gorra%'                                        THEN 0.10 + (((pv.id*29 + 7) % 100) / 100.0)*0.10
        WHEN p.nombre ILIKE '%hoodie%' OR p.nombre ILIKE '%chaqueta%'
          OR p.nombre ILIKE '%buzo%'                                         THEN 0.50 + (((pv.id*29 + 7) % 100) / 100.0)*0.35
        WHEN p.nombre ILIKE '%camiseta%' OR p.nombre ILIKE '%camisa%'        THEN 0.15 + (((pv.id*29 + 7) % 100) / 100.0)*0.15
        WHEN p.nombre ILIKE '%legging%'                                      THEN 0.20 + (((pv.id*29 + 7) % 100) / 100.0)*0.15
        WHEN p.nombre ILIKE '%zapatilla%' OR p.nombre ILIKE '%zapato%'       THEN 0.60 + (((pv.id*29 + 7) % 100) / 100.0)*0.60
        -- Fallback por categoría
        ELSE CASE pc.categoria_id
               WHEN 4  THEN 0.40 + (((pv.id*29 + 7) % 100) / 100.0)*1.00   -- Calzado
               WHEN 2  THEN 0.10 + (((pv.id*29 + 7) % 100) / 100.0)*0.90   -- Accesorios
               WHEN 12 THEN 0.15 + (((pv.id*29 + 7) % 100) / 100.0)*0.55   -- Ropa
               WHEN 1  THEN 0.15 + (((pv.id*29 + 7) % 100) / 100.0)*0.55   -- Ropa Hombre
               WHEN 3  THEN 0.15 + (((pv.id*29 + 7) % 100) / 100.0)*0.55   -- Ropa Mujer
               WHEN 11 THEN 0.80 + (((pv.id*29 + 7) % 100) / 100.0)*7.00   -- Hogar
               WHEN 5  THEN 0.30 + (((pv.id*29 + 7) % 100) / 100.0)*2.20   -- Abarrotes
               WHEN 7  THEN 0.05 + (((pv.id*29 + 7) % 100) / 100.0)*0.55   -- Belleza
               WHEN 9  THEN 0.40 + (((pv.id*29 + 7) % 100) / 100.0)*4.60   -- Deportes
               WHEN 10 THEN 0.15 + (((pv.id*29 + 7) % 100) / 100.0)*3.35   -- Electrónica
               ELSE 0.50 + (((pv.id*29 + 7) % 100) / 100.0)*1.50           -- genérico (sin categoría)
             END
      END)::numeric, 2)
FROM producto p
JOIN producto_categoria pc ON pc.producto_id = p.id
WHERE pv.producto_id = p.id
  AND pv.peso_kg IS NULL;

-- Red de seguridad: si alguna variante quedó sin categoría (0 esperadas), igual recibe peso > 0.
UPDATE producto_variante
SET peso_kg = ROUND((0.50 + (((id*29 + 7) % 100)/100.0)*1.50)::numeric, 2)
WHERE peso_kg IS NULL;

-- -------------------------------------------------------------------------------------
-- 2) inventario.stock_minimo  — punto de reorden por rotación + categoría
-- Criterio: el mínimo es el punto de reorden (demanda durante el lead time + stock de
-- seguridad). Donde HAY señal de rotación real (movimiento_inventario tipo 'salida_venta')
-- el mínimo se eleva al menos a las unidades vendidas observadas. Sin historial, se usa una
-- base por categoría diferenciada por velocidad de rotación: FMCG (Abarrotes, Belleza)
-- rotan rápido y llevan puntos de reorden altos —una parte por encima del stock plano de
-- 100 u. cargado en el ETL, lo que refleja el sub-stock clásico de los productos de alta
-- rotación—; Electrónica/Hogar rotan lento y llevan mínimos bajos. Jitter determinista por
-- id para variación intra-categoría.
-- PRESERVA las 16 filas que ya tienen mínimo <> 0 (variantes 2..17, valor 2): sólo se
-- escriben las 1.211 filas en el default 0.
-- -------------------------------------------------------------------------------------
UPDATE inventario i
SET stock_minimo = GREATEST(
      (params.minb + ((pv.id*13) % (params.mins + 1))),
      COALESCE(rot.salidas, 0)
    )
FROM producto_variante pv
JOIN producto_categoria pc ON pc.producto_id = pv.producto_id
JOIN (VALUES
        (5,40,95),(7,30,95),(2,12,28),(4,10,24),(12,10,20),
        (1,10,20),(3,10,20),(9,8,16),(11,6,12),(10,4,10)
     ) AS params(cat,minb,mins) ON params.cat = pc.categoria_id
LEFT JOIN (
        SELECT mi.producto_variante_id AS vid, SUM(mi.cantidad) AS salidas
        FROM movimiento_inventario mi
        JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id
        WHERE tm.codigo = 'salida_venta'
        GROUP BY mi.producto_variante_id
     ) rot ON rot.vid = pv.id
WHERE i.producto_variante_id = pv.id
  AND i.stock_minimo = 0;

-- -------------------------------------------------------------------------------------
-- 3) inventario.stock_maximo  — capacidad objetivo, SIEMPRE > stock_minimo
-- Criterio: el máximo es la capacidad/objetivo de reposición, proporcional a la rotación.
-- Base por categoría (fija que el máximo de las categorías de rotación LENTA quede a veces
-- por debajo del stock plano de 100 u. → sobre-stock real de artículos que rotan poco) más
-- jitter por id. Se calcula DESPUÉS del mínimo y se ancla a stock_minimo + 20 para garantizar
-- max > min en toda fila (incluidas las 16 con mínimo preservado). Escribe las 1.227 filas
-- (todas en NULL).
-- -------------------------------------------------------------------------------------
UPDATE inventario i
SET stock_maximo = GREATEST(
      i.stock_minimo + 20,
      params.maxb + ((pv.id*17) % (params.maxs + 1))
    )
FROM producto_variante pv
JOIN producto_categoria pc ON pc.producto_id = pv.producto_id
JOIN (VALUES
        (5,180,140),(7,160,140),(2,120,120),(4,100,120),(12,100,120),
        (1,100,120),(3,100,120),(9,85,100),(11,75,90),(10,70,90)
     ) AS params(cat,maxb,maxs) ON params.cat = pc.categoria_id
WHERE i.producto_variante_id = pv.id
  AND i.stock_maximo IS NULL;

-- Red de seguridad: cualquier fila aún sin máximo (0 esperadas) recibe uno coherente.
UPDATE inventario
SET stock_maximo = GREATEST(stock_minimo + 20, 120)
WHERE stock_maximo IS NULL;

-- -------------------------------------------------------------------------------------
-- 4) tarifa_envio.costo_por_kg  — costo por kilo diferenciado por zona (mercado EC)
-- Criterio: zona urbana cercana < provincial < nacional, coherente con los costo_base
-- ya existentes (2.50 / 4.50 / 6.50). Valores realistas de courier ecuatoriano por kg.
--   Zona 1 Quevedo (local, express)      base 2.50 -> 0.30 /kg
--   Zona 2 Los Ríos (provincial, std)    base 4.50 -> 0.55 /kg
--   Zona 3 Ecuador  (nacional, std)      base 6.50 -> 0.85 /kg
-- Sólo escribe tarifas en el default 0.00.
-- -------------------------------------------------------------------------------------
UPDATE tarifa_envio
SET costo_por_kg = CASE zona_envio_id
                     WHEN 1 THEN 0.30
                     WHEN 2 THEN 0.55
                     WHEN 3 THEN 0.85
                     ELSE costo_por_kg
                   END
WHERE costo_por_kg = 0;

COMMIT;
