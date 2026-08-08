"""
PASO 3 — Indices de PostgreSQL y estadisticas.

Medir PostgreSQL sin indices seria inclinar el resultado, asi que se le da la
MEJOR configuracion que admite sin reiniciar el cluster:

  * Un indice CUBRIENTE (`INCLUDE`) por consulta, con las columnas de filtrado
    y agrupacion en la clave y las de agregacion en la carga. Asi el planificador
    puede resolver el agregado con un *index only scan* y no visitar la tabla:
    es lo mas parecido que PostgreSQL tiene a leer solo las columnas que
    necesita, que es justo lo que hace un motor columnar por construccion.
  * `VACUUM ANALYZE` despues de crearlos. Sin el, el mapa de visibilidad esta
    vacio y PostgreSQL NO usa el *index only scan* aunque el indice exista: el
    plan cae a un recorrido secuencial y el indice no sirve para nada.

Los indices cuentan en el espacio de PostgreSQL que se reporta. Es correcto que
cuenten: son parte de lo que cuesta tener ese rendimiento.

    py -3 retailmind/benchmark/03_indexar.py
"""

from __future__ import annotations

import time

from comun import BASE_BENCH, log, pg

INDICES = [
    # ── Q1 ───────────────────────────────────────────────────────────
    ("ix_venta_q1", "dwh.fact_venta_linea",
     """CREATE INDEX ix_venta_q1 ON dwh.fact_venta_linea (es_cancelado, mes, categoria)
        INCLUDE (pedido_id, cantidad, subtotal_bruto, descuento_total,
                 venta_neta, costo_total, margen)""",
     "clave = filtro (es_cancelado) + las dos columnas del GROUP BY; carga = las "
     "7 columnas agregadas. Cubre Q1 entera."),
    ("ix_dimfecha_q1", "dwh.dim_fecha",
     "CREATE INDEX ix_dimfecha_q1 ON dwh.dim_fecha (fecha) INCLUDE (mes_etiqueta)",
     "clave del LEFT JOIN de Q1, con la etiqueta en la carga."),

    # ── Q2 ───────────────────────────────────────────────────────────
    ("ix_stock_q2", "dwh.fact_stock_mensual",
     """CREATE INDEX ix_stock_q2 ON dwh.fact_stock_mensual (categoria, mes)
        INCLUDE (producto_variante_id, stock_cierre)""",
     "clave = el GROUP BY del nivel interno de Q2; carga = lo que se suma y "
     "cuenta distinto."),
    ("ix_kardex_q2", "dwh.fact_movimiento_inventario",
     """CREATE INDEX ix_kardex_q2 ON dwh.fact_movimiento_inventario (categoria)
        INCLUDE (tipo_movimiento, cantidad, cantidad_con_signo)""",
     "clave = el GROUP BY del agregado de kardex; carga = el filtro condicional "
     "y la medida."),

    # ── Q3 y Q4 ──────────────────────────────────────────────────────
    ("ix_eventos_q34", "web.fact_eventos",
     """CREATE INDEX ix_eventos_q34 ON web.fact_eventos (channel, user_action)
        INCLUDE (session_id, user_id, is_conversion, drop_off_flag,
                 interaction_count, event_index)""",
     "clave = las dos columnas del GROUP BY de Q3/Q4; carga = las seis columnas "
     "agregadas, incluidas las dos de COUNT(DISTINCT)."),

    # ── Q5 ───────────────────────────────────────────────────────────
    ("ix_eventos_q5", "web.fact_eventos",
     """CREATE INDEX ix_eventos_q5 ON web.fact_eventos (user_action, product_id)
        INCLUDE (session_id, is_conversion, interaction_count)""",
     "clave = filtro (user_action) + GROUP BY (product_id); carga = lo agregado. "
     "Deja el filtro de Q5 resuelto por rango del indice."),
]


def main() -> int:
    conn = pg(BASE_BENCH)
    log("== Indices de PostgreSQL ==\n")
    for nombre, tabla, ddl, porque in INDICES:
        t0 = time.perf_counter()
        with conn.cursor() as cur:
            cur.execute(f"DROP INDEX IF EXISTS {nombre}")
            cur.execute(" ".join(ddl.split()))
        log(f"  {nombre:<18} {tabla:<32} {time.perf_counter() - t0:6.1f}s")
        log(f"       {porque}")

    log("\n== VACUUM ANALYZE (imprescindible para el index only scan) ==")
    for tabla in ["dwh.fact_venta_linea", "dwh.dim_fecha", "dwh.fact_stock_mensual",
                  "dwh.fact_movimiento_inventario", "web.fact_eventos"]:
        t0 = time.perf_counter()
        with conn.cursor() as cur:
            cur.execute(f"VACUUM (ANALYZE) {tabla}")
        log(f"  {tabla:<34} {time.perf_counter() - t0:6.1f}s")

    log("\n== Tamano de indices ==")
    with conn.cursor() as cur:
        cur.execute("""
            SELECT indexrelname,
                   pg_size_pretty(pg_relation_size(indexrelid)),
                   pg_relation_size(indexrelid)
            FROM pg_stat_user_indexes
            WHERE schemaname IN ('dwh', 'web')
            ORDER BY pg_relation_size(indexrelid) DESC""")
        total = 0
        for n, bonito, bytes_ in cur.fetchall():
            total += bytes_
            log(f"  {n:<20} {bonito:>10}")
        log(f"  {'TOTAL':<20} {total / 1024 / 1024:>7.1f} MB")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
