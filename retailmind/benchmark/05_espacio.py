"""
PASO 5 — Espacio en disco de los MISMOS datos en cada motor.

Es parte legitima de la comparacion: un motor columnar guarda cada columna
junta, con valores del mismo tipo y a menudo repetidos, y por eso comprime mucho
mejor que un motor por filas. La cifra se toma de los catalogos de cada motor,
no del sistema de ficheros.

Que se cuenta y por que:
  * PostgreSQL — el TAMANO TOTAL (`pg_total_relation_size`): tabla + TOAST +
    INDICES. Los indices cuentan porque sin ellos los tiempos medidos serian
    otros; es el precio de ese rendimiento.
  * ClickHouse — el tamano COMPRIMIDO, que es lo que ocupa en disco, y al lado
    el SIN COMPRIMIR, para que se vea de donde sale la diferencia.

    py -3 retailmind/benchmark/05_espacio.py
"""

from __future__ import annotations

import json
from pathlib import Path

from comun import BASE_BENCH, DWH, ESQ_DWH, ESQ_WEB, LEGADA, ch, log, pg

SALIDA = Path(__file__).parent / "espacio.json"


def mb(b) -> float:
    # `sum()` de PostgreSQL sobre bigint devuelve numeric -> Decimal en Python.
    return float(b) / 1024 / 1024


def espacio_pg(conn, esquema: str) -> dict:
    with conn.cursor() as cur:
        cur.execute("""
            SELECT coalesce(sum(pg_relation_size(c.oid)), 0)        AS heap,
                   coalesce(sum(pg_indexes_size(c.oid)), 0)         AS indices,
                   coalesce(sum(pg_total_relation_size(c.oid)), 0)  AS total,
                   count(*)                                         AS tablas
            FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = %s AND c.relkind = 'r'""", (esquema,))
        h, i, t, n = cur.fetchone()
    return dict(tablas=n, heap_mb=mb(h), indices_mb=mb(i), total_mb=mb(t))


def espacio_ch(base: str, solo: str | None = None, excluir: set | None = None) -> dict:
    filtro = f"AND table = '{solo}'" if solo else ""
    txt = ch(
        "SELECT table, sum(data_compressed_bytes), sum(data_uncompressed_bytes) "
        f"FROM system.parts WHERE database = '{base}' AND active {filtro} "
        "GROUP BY table FORMAT TSV", base)
    comp = sin = 0
    n = 0
    for linea in txt.strip().split("\n"):
        if not linea:
            continue
        t, c, u = linea.split("\t")
        if excluir and t in excluir:
            continue
        comp += int(c)
        sin += int(u)
        n += 1
    return dict(tablas=n, comprimido_mb=mb(comp), sin_comprimir_mb=mb(sin))


def main() -> int:
    conn = pg(BASE_BENCH)
    r = {}

    log("== Espacio en disco: los MISMOS datos en los dos motores ==\n")
    for etiqueta, esq, base_ch, solo, excl in [
        ("ESCALA PEQUENA — 21 tablas del DWH (66.082 filas)",
         ESQ_DWH, DWH, None, {"etl_ejecucion"}),
        ("ESCALA GRANDE — fact_eventos (2.823.245 filas)",
         ESQ_WEB, LEGADA, "fact_eventos", None),
    ]:
        p = espacio_pg(conn, esq)
        c = espacio_ch(base_ch, solo, excl)
        rel_total = p["total_mb"] / c["comprimido_mb"]
        rel_heap = p["heap_mb"] / c["comprimido_mb"]
        log(etiqueta)
        log(f"  PostgreSQL  tablas {p['heap_mb']:9.1f} MB  + indices "
            f"{p['indices_mb']:8.1f} MB  = {p['total_mb']:9.1f} MB")
        log(f"  ClickHouse  comprimido {c['comprimido_mb']:9.1f} MB   "
            f"(sin comprimir {c['sin_comprimir_mb']:.1f} MB)")
        log(f"  relacion: PostgreSQL ocupa {rel_total:.1f}x lo que ClickHouse "
            f"(sin contar indices, {rel_heap:.1f}x)\n")
        r[etiqueta] = dict(postgresql=p, clickhouse=c,
                           relacion_total=rel_total, relacion_sin_indices=rel_heap)

    with conn.cursor() as cur:
        cur.execute("SELECT pg_database_size(%s)", (BASE_BENCH,))
        tam = cur.fetchone()[0]
    log(f"Base `{BASE_BENCH}` completa: {mb(tam):.1f} MB")
    r["base_benchmark_mb"] = mb(tam)

    SALIDA.write_text(json.dumps(r, indent=2, ensure_ascii=False), encoding="utf-8")
    log(f"\nDetalle -> {SALIDA}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
