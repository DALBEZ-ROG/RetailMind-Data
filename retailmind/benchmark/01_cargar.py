"""
PASO 1 — Construye la base `retailmind_benchmark` en PostgreSQL con los MISMOS
datos que tiene ClickHouse, en dos escalas.

  * esquema `dwh` -> las 21 tablas del modelo de `retailmind_dwh` (~66.000 filas)
  * esquema `web` -> `fact_eventos` de la base legada (2.823.245 filas)

No escribe una sola fila en la base operativa `retailmind`: la unica conexion
que la toca es de solo lectura y esta en `04_verificar.py`.

    py -3 retailmind/benchmark/01_cargar.py            # todo
    py -3 retailmind/benchmark/01_cargar.py --solo dwh
    py -3 retailmind/benchmark/01_cargar.py --solo eventos
"""

from __future__ import annotations

import sys
import time

from comun import (BASE_BENCH, DWH, ESQ_DWH, ESQ_WEB, LEGADA, ch, copiar, log, pg)

# Las 21 tablas del MODELO. `etl_ejecucion` es la bitacora del ETL y no forma
# parte del modelo dimensional: se excluye a proposito (CLAUDE.md lo declara).
EXCLUIDAS = {"etl_ejecucion"}


def tablas_dwh() -> list[str]:
    txt = ch(
        "SELECT name FROM system.tables "
        f"WHERE database = '{DWH}' AND engine NOT LIKE '%View%' ORDER BY name FORMAT TSV"
    )
    return [t for t in txt.strip().split("\n") if t not in EXCLUIDAS]


def cargar_dwh(conn) -> list[tuple[str, int, int]]:
    with conn.cursor() as cur:
        cur.execute(f"CREATE SCHEMA IF NOT EXISTS {ESQ_DWH}")

    filas = []
    for t in tablas_dwh():
        origen = int(ch(f"SELECT count() FROM `{DWH}`.`{t}`").strip())
        t0 = time.perf_counter()
        destino = copiar(conn, DWH, t, ESQ_DWH)
        seg = time.perf_counter() - t0
        marca = "OK " if origen == destino else "!! "
        log(f"  {marca}{t:<32} origen={origen:>9,}  destino={destino:>9,}  {seg:6.2f}s")
        filas.append((t, origen, destino))
    return filas


def cargar_eventos(conn) -> tuple[int, int]:
    with conn.cursor() as cur:
        cur.execute(f"CREATE SCHEMA IF NOT EXISTS {ESQ_WEB}")

    origen = int(ch("SELECT count() FROM `retailmind`.`fact_eventos`", LEGADA).strip())
    log(f"  fact_eventos: {origen:,} filas en ClickHouse. Copiando...")
    t0 = time.perf_counter()
    destino = copiar(conn, LEGADA, "fact_eventos", ESQ_WEB)
    seg = time.perf_counter() - t0
    marca = "OK " if origen == destino else "!! "
    log(f"  {marca}fact_eventos  origen={origen:,}  destino={destino:,}  {seg:.1f}s")
    return origen, destino


def main() -> int:
    solo = None
    if "--solo" in sys.argv:
        solo = sys.argv[sys.argv.index("--solo") + 1]

    conn = pg(BASE_BENCH)
    log(f"Destino: {BASE_BENCH} (contenedor retailmind-postgres-1)\n")

    if solo in (None, "dwh"):
        log("== ESCALA PEQUENA: modelo del DWH ==")
        filas = cargar_dwh(conn)
        tot_o = sum(o for _, o, _ in filas)
        tot_d = sum(d for _, _, d in filas)
        log(f"  TOTAL {len(filas)} tablas: origen={tot_o:,}  destino={tot_d:,}\n")

    if solo in (None, "eventos"):
        log("== ESCALA GRANDE: fact_eventos (base legada) ==")
        cargar_eventos(conn)
        log("")

    with conn.cursor() as cur:
        cur.execute("SELECT pg_size_pretty(pg_database_size(%s))", (BASE_BENCH,))
        log(f"Tamano de {BASE_BENCH} (sin indices aun): {cur.fetchone()[0]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
