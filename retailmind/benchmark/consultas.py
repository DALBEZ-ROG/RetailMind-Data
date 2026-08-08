"""
Las CINCO consultas del banco de pruebas, cada una en su version PostgreSQL y
ClickHouse. Un unico sitio donde viven: el arnes que las mide y el verificador
que compara sus resultados leen de aqui, asi que no puede haber dos versiones
de la misma consulta.

Reglas que se respetan en las diez consultas:

  1. Los DOS motores calculan EXACTAMENTE lo mismo. Nada de `uniq()` en
     ClickHouse: es HyperLogLog, es aproximado y no seria la misma pregunta que
     el `COUNT(DISTINCT)` de PostgreSQL. Se usa `uniqExact()`.
  2. El `ORDER BY` sobre texto lleva `COLLATE "C"` en PostgreSQL. La base nace
     con ICU `es-EC` y ClickHouse ordena por bytes; sin el, «Electronica» cae en
     otro sitio y las filas no se pueden contrastar en paralelo.
  3. Donde ClickHouse rellena un LEFT JOIN sin pareja con el DEFECTO DEL TIPO
     (0 / cadena vacia) y PostgreSQL con NULL, se iguala en PostgreSQL con
     `coalesce`. Es la misma trampa que documenta CLAUDE.md para la Fase 3B.
  4. Ninguna consulta se retoco para invertir un resultado.
"""

# ═══════════════════════════════════════════════════════════════════════
# ESCALA PEQUENA — el almacen analitico (66.082 filas en 21 tablas)
# ═══════════════════════════════════════════════════════════════════════

# ── Q1 · OTD-VEN-06 — evolucion mensual de la venta y el margen ────────
# Es el agregado que sirve el informe compuesto de Ventas. Recorre las 10.384
# lineas de `fact_venta_linea` (28 columnas) leyendo solo 8, agrupa por
# (mes, categoria) y devuelve ~190 filas. Es el caso de libro de la ventaja
# columnar: proyeccion estrecha sobre una tabla ancha.
#
# Del informe real se mide el AGREGADO y no las dos ventanas que lleva encima
# (`participacion_pct`, `variacion_pct`): esas operan sobre las ~190 filas YA
# agregadas, no recorren el hecho, y se calculan en Float64 —lo que impediria
# contrastar el resultado con igualdad exacta—. Se quita el mismo trabajo a los
# dos motores.

Q1_CH = """
SELECT
    min(if(empty(d.mes_etiqueta), formatDateTime(v.mes, '%Y-%m'), d.mes_etiqueta))
                                    AS etiqueta_mes,
    v.mes                           AS mes_dato,
    v.categoria                     AS categoria_dato,
    uniqExact(v.pedido_id)          AS n_pedidos,
    sum(v.cantidad)                 AS n_unidades,
    sum(v.subtotal_bruto)           AS t_venta_bruta,
    sum(v.descuento_total)          AS t_descuentos,
    sum(v.venta_neta)               AS t_venta_neta,
    sum(v.costo_total)              AS t_costo,
    sum(v.margen)                   AS t_margen
FROM retailmind_dwh.fact_venta_linea v
LEFT JOIN retailmind_dwh.dim_fecha d ON d.fecha = v.mes
WHERE v.es_cancelado = 0
GROUP BY v.mes, v.categoria
ORDER BY v.mes, v.categoria
"""

Q1_PG = """
SELECT
    min(coalesce(nullif(d.mes_etiqueta, ''), to_char(v.mes, 'YYYY-MM')))
                                    AS etiqueta_mes,
    v.mes                           AS mes_dato,
    v.categoria                     AS categoria_dato,
    count(DISTINCT v.pedido_id)     AS n_pedidos,
    sum(v.cantidad)                 AS n_unidades,
    sum(v.subtotal_bruto)           AS t_venta_bruta,
    sum(v.descuento_total)          AS t_descuentos,
    sum(v.venta_neta)               AS t_venta_neta,
    sum(v.costo_total)              AS t_costo,
    sum(v.margen)                   AS t_margen
FROM dwh.fact_venta_linea v
LEFT JOIN dwh.dim_fecha d ON d.fecha = v.mes
WHERE v.es_cancelado = 0
GROUP BY v.mes, v.categoria
ORDER BY v.mes, v.categoria COLLATE "C"
"""

# ── Q2 · OTD-INV-04 — rotacion de inventario por categoria ─────────────
# Forma DISTINTA a proposito: dos niveles de agregacion anidados sobre
# `fact_stock_mensual` (22.528 filas) unidos por LEFT JOIN a un agregado de
# `fact_movimiento_inventario` (13.289). 35.817 filas entre las dos tablas y un
# JOIN entre agregados, no una tabla plana. Si la ventaja columnar dependiera
# solo del ancho de la tabla, aqui deberia notarse menos.

Q2_CH = """
SELECT
    s.categoria                                              AS categoria,
    s.meses                                                  AS meses,
    s.posiciones                                             AS posiciones,
    s.stock_promedio                                         AS stock_promedio,
    s.stock_final                                            AS stock_final,
    v.unidades_vendidas                                      AS unidades_vendidas,
    v.unidades_salida_total                                  AS unidades_salida_total,
    if(s.stock_promedio > 0,
       round(v.unidades_vendidas / s.stock_promedio, 2), 0)  AS rotacion_veces,
    if(v.unidades_vendidas > 0,
       round(s.dias * s.stock_promedio / v.unidades_vendidas, 1), NULL)
                                                             AS dias_cobertura,
    if(v.unidades_vendidas = 0, 1, 0)                        AS parada
FROM (
    SELECT categoria,
           count()                                           AS meses,
           max(posiciones_mes)                               AS posiciones,
           round(avg(unidades_mes), 2)                       AS stock_promedio,
           argMax(unidades_mes, mes)                         AS stock_final,
           greatest(dateDiff('day', min(mes), addMonths(max(mes), 1)), 1) AS dias
    FROM (
        SELECT categoria, mes,
               sum(toInt64(stock_cierre))                    AS unidades_mes,
               uniqExact(producto_variante_id)               AS posiciones_mes
        FROM retailmind_dwh.fact_stock_mensual
        GROUP BY categoria, mes
    )
    GROUP BY categoria
) s
LEFT JOIN (
    SELECT categoria,
           sumIf(cantidad, tipo_movimiento = 'salida_venta') AS unidades_vendidas,
           sumIf(cantidad, cantidad_con_signo < 0)           AS unidades_salida_total
    FROM retailmind_dwh.fact_movimiento_inventario
    GROUP BY categoria
) v ON v.categoria = s.categoria
ORDER BY s.categoria
"""

Q2_PG = """
SELECT
    s.categoria                                              AS categoria,
    s.meses                                                  AS meses,
    s.posiciones                                             AS posiciones,
    s.stock_promedio                                         AS stock_promedio,
    s.stock_final                                            AS stock_final,
    coalesce(v.unidades_vendidas, 0)                         AS unidades_vendidas,
    coalesce(v.unidades_salida_total, 0)                     AS unidades_salida_total,
    CASE WHEN s.stock_promedio > 0
         THEN round(coalesce(v.unidades_vendidas, 0)::numeric / s.stock_promedio, 2)
         ELSE 0 END                                          AS rotacion_veces,
    CASE WHEN coalesce(v.unidades_vendidas, 0) > 0
         THEN round(s.dias * s.stock_promedio / v.unidades_vendidas, 1)
         END                                                 AS dias_cobertura,
    CASE WHEN coalesce(v.unidades_vendidas, 0) = 0 THEN 1 ELSE 0 END AS parada
FROM (
    SELECT categoria,
           count(*)                                          AS meses,
           max(posiciones_mes)                               AS posiciones,
           round(avg(unidades_mes), 2)                       AS stock_promedio,
           (array_agg(unidades_mes ORDER BY mes DESC))[1]    AS stock_final,
           greatest((date_trunc('month', max(mes)) + interval '1 month')::date
                    - min(mes), 1)                           AS dias
    FROM (
        SELECT categoria, mes,
               sum(stock_cierre::bigint)                     AS unidades_mes,
               count(DISTINCT producto_variante_id)          AS posiciones_mes
        FROM dwh.fact_stock_mensual
        GROUP BY categoria, mes
    ) m
    GROUP BY categoria
) s
LEFT JOIN (
    SELECT categoria,
           sum(cantidad) FILTER (WHERE tipo_movimiento = 'salida_venta')
                                                             AS unidades_vendidas,
           sum(cantidad) FILTER (WHERE cantidad_con_signo < 0)
                                                             AS unidades_salida_total
    FROM dwh.fact_movimiento_inventario
    GROUP BY categoria
) v ON v.categoria = s.categoria
ORDER BY s.categoria COLLATE "C"
"""

# ═══════════════════════════════════════════════════════════════════════
# ESCALA GRANDE — analitica web (2.823.245 filas)
# ═══════════════════════════════════════════════════════════════════════

# ── Q3 · Trafico por canal y accion, CON distintos exactos ─────────────
# El agregado que sirve el panel de analitica web: 2.823.245 eventos, 18 grupos,
# dos `COUNT(DISTINCT)` de alta cardinalidad (455.550 sesiones, 6.810 usuarios).

Q3_CH = """
SELECT channel                     AS canal,
       user_action                 AS accion,
       count()                     AS eventos,
       uniqExact(session_id)       AS sesiones,
       uniqExact(user_id)          AS usuarios,
       sum(is_conversion)          AS conversiones,
       sum(drop_off_flag)          AS abandonos,
       sum(interaction_count)      AS interacciones
FROM retailmind.fact_eventos
GROUP BY channel, user_action
ORDER BY channel, user_action
"""

Q3_PG = """
SELECT channel                     AS canal,
       user_action                 AS accion,
       count(*)                    AS eventos,
       count(DISTINCT session_id)  AS sesiones,
       count(DISTINCT user_id)     AS usuarios,
       sum(is_conversion)          AS conversiones,
       sum(drop_off_flag)          AS abandonos,
       sum(interaction_count)      AS interacciones
FROM web.fact_eventos
GROUP BY channel, user_action
ORDER BY channel COLLATE "C", user_action COLLATE "C"
"""

# ── Q4 · La MISMA agrupacion SIN `COUNT(DISTINCT)` ─────────────────────
# Es el control del experimento. `COUNT(DISTINCT)` en PostgreSQL se resuelve
# ordenando, y en ClickHouse con una tabla hash: si toda la diferencia de Q3
# viniera de ahi, la comparacion no diria nada sobre el modelo columnar. Q4
# aisla eso — mismas filas, mismos grupos, solo sumas.

Q4_CH = """
SELECT channel                     AS canal,
       user_action                 AS accion,
       count()                     AS eventos,
       sum(is_conversion)          AS conversiones,
       sum(drop_off_flag)          AS abandonos,
       sum(interaction_count)      AS interacciones,
       max(event_index)            AS max_indice
FROM retailmind.fact_eventos
GROUP BY channel, user_action
ORDER BY channel, user_action
"""

Q4_PG = """
SELECT channel                     AS canal,
       user_action                 AS accion,
       count(*)                    AS eventos,
       sum(is_conversion)          AS conversiones,
       sum(drop_off_flag)          AS abandonos,
       sum(interaction_count)      AS interacciones,
       max(event_index)            AS max_indice
FROM web.fact_eventos
GROUP BY channel, user_action
ORDER BY channel COLLATE "C", user_action COLLATE "C"
"""

# ── Q5 · Top de productos: filtro + 1.700 grupos ───────────────────────
# Tercera forma: la agrupacion no tiene 18 grupos sino 1.700, y hay un filtro
# por `user_action` que deja 1.310.209 de 2.823.245 filas (53,6 %). Es donde un
# indice de PostgreSQL tiene mas que decir: el plan medido confirma que resuelve
# el filtro por rango del indice con un Index Only Scan.

Q5_CH = """
SELECT product_id                  AS producto,
       count()                     AS eventos,
       uniqExact(session_id)       AS sesiones,
       sum(is_conversion)          AS conversiones,
       sum(interaction_count)      AS interacciones
FROM retailmind.fact_eventos
WHERE user_action IN ('view', 'add_to_cart', 'purchase')
GROUP BY product_id
ORDER BY eventos DESC, product_id
LIMIT 25
"""

Q5_PG = """
SELECT product_id                  AS producto,
       count(*)                    AS eventos,
       count(DISTINCT session_id)  AS sesiones,
       sum(is_conversion)          AS conversiones,
       sum(interaction_count)      AS interacciones
FROM web.fact_eventos
WHERE user_action IN ('view', 'add_to_cart', 'purchase')
GROUP BY product_id
ORDER BY eventos DESC, product_id COLLATE "C"
LIMIT 25
"""


CONSULTAS = [
    dict(id="Q1", escala="pequena", filas=10_384,
         titulo="OTD-VEN-06 — venta y margen por mes y categoria",
         detalle="fact_venta_linea (10.384) LEFT JOIN dim_fecha (730); GROUP BY mes, categoria",
         pg=Q1_PG, ch=Q1_CH),
    dict(id="Q2", escala="pequena", filas=35_817,
         titulo="OTD-INV-04 — rotacion de inventario por categoria",
         detalle="fact_stock_mensual (22.528) + fact_movimiento_inventario (13.289); "
                 "dos niveles de agregacion + JOIN entre agregados",
         pg=Q2_PG, ch=Q2_CH),
    dict(id="Q3", escala="grande", filas=2_823_245,
         titulo="Trafico web por canal y accion (con distintos exactos)",
         detalle="fact_eventos (2.823.245); 18 grupos; 2 COUNT(DISTINCT) de alta cardinalidad",
         pg=Q3_PG, ch=Q3_CH),
    dict(id="Q4", escala="grande", filas=2_823_245,
         titulo="Control: la misma agrupacion SIN COUNT(DISTINCT)",
         detalle="fact_eventos (2.823.245); 18 grupos; solo sumas y maximo",
         pg=Q4_PG, ch=Q4_CH),
    dict(id="Q5", escala="grande", filas=1_310_209,
         titulo="Top de productos: filtro + 1.700 grupos",
         detalle="fact_eventos filtrado a 3 acciones (1.310.209 de 2.823.245 filas); "
                 "GROUP BY product_id (1.700 grupos)",
         pg=Q5_PG, ch=Q5_CH),
]
