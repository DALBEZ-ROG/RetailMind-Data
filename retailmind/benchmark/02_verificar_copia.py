"""
PASO 2 — Verifica que la copia en PostgreSQL es IDENTICA al origen ClickHouse.

No se compara un conteo: se compara la tabla ENTERA, fila por fila. Cada motor
vuelca sus filas ordenadas por la clave, con las columnas normalizadas al MISMO
texto (la unica diferencia real entre motores es como IMPRIMEN una marca de
tiempo o un arreglo), y se contrasta el MD5 del flujo completo.

Un conteo igual no prueba nada: dos tablas con las mismas filas pero un decimal
corrido dan el mismo conteo y miden cosas distintas.

    py -3 retailmind/benchmark/02_verificar_copia.py
"""

from __future__ import annotations

import hashlib
import re
import subprocess
import sys
import time

from comun import (BASE_BENCH, CT_CH, DWH, ESQ_DWH, ESQ_WEB, LEGADA, ch,
                   columnas_ch, log, pg)

EXCLUIDAS = {"etl_ejecucion"}


# ── Normalizacion: la MISMA cadena en los dos motores ────────────────

def _desnudar(t: str) -> tuple[str, bool]:
    nulo = False
    m = re.fullmatch(r"Nullable\((.*)\)", t)
    if m:
        nulo, t = True, m.group(1)
    m = re.fullmatch(r"LowCardinality\((.*)\)", t)
    if m:
        t = m.group(1)
        m2 = re.fullmatch(r"Nullable\((.*)\)", t)
        if m2:
            nulo, t = True, m2.group(1)
    return t, nulo


def sel_ch(col: str, tipo: str) -> str:
    t, _ = _desnudar(tipo)
    if t.startswith("DateTime"):
        # OJO: en ClickHouse el minuto es `%i`. `%M` es el NOMBRE DEL MES, y con
        # el patron "obvio" la comparacion falla en las 21 tablas por una
        # diferencia que no esta en los datos (`17:August:19` vs `17:17:19`).
        return f"formatDateTime(`{col}`, '%Y-%m-%d %H:%i:%S')"
    if t.startswith("Array("):
        return f"arrayStringConcat(arrayMap(x -> toString(x), `{col}`), '|')"
    return f"toString(`{col}`)" if t == "UUID" else f"`{col}`"


def sel_pg(col: str, tipo: str) -> str:
    t, _ = _desnudar(tipo)
    if t.startswith("DateTime"):
        return f"to_char(\"{col}\", 'YYYY-MM-DD HH24:MI:SS')"
    if t.startswith("Array("):
        return f"array_to_string(\"{col}\", '|')"
    return f'"{col}"'


# ── Volcados en flujo, con hash incremental ──────────────────────────

def md5_ch(consulta: str, base: str) -> tuple[str, int]:
    p = subprocess.Popen(
        ["docker", "exec", CT_CH, "clickhouse-client", "--database", base,
         "--max_block_size", "65536",
         # Por defecto ClickHouse IMPRIME los Decimal sin ceros finales
         # (`249.8` donde PostgreSQL escribe `249.80`). El valor es el mismo;
         # sin este ajuste la comparacion acusa 19 tablas distintas por como se
         # imprime un numero, no por lo que vale.
         "--output_format_decimal_trailing_zeros", "1",
         "--query", consulta],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    )
    h, n = hashlib.md5(), 0
    for chunk in iter(lambda: p.stdout.read(1 << 20), b""):
        h.update(chunk)
        n += chunk.count(b"\n")
    err = p.stderr.read().decode("utf-8", "replace")
    p.wait()
    if p.returncode != 0:
        raise RuntimeError(err)
    return h.hexdigest(), n


class _Sumidero:
    """Objeto tipo fichero para `copy_expert`: hashea sin materializar nada."""

    def __init__(self):
        self.h, self.n = hashlib.md5(), 0

    def write(self, b):
        if isinstance(b, str):
            b = b.encode("utf-8")
        self.h.update(b)
        self.n += b.count(b"\n")
        return len(b)


def md5_pg(conn, consulta: str) -> tuple[str, int]:
    s = _Sumidero()
    with conn.cursor() as cur:
        cur.copy_expert(f"COPY ({consulta}) TO STDOUT WITH (FORMAT text)", s)
    return s.h.hexdigest(), s.n


# ── Comparacion de una tabla ─────────────────────────────────────────

def comparar(conn, base_ch: str, tabla: str, esquema: str, orden: list[str]):
    cols = columnas_ch(base_ch, tabla)
    proy_ch = ", ".join(sel_ch(n, t) for n, t in cols)
    proy_pg = ", ".join(sel_pg(n, t) for n, t in cols)
    ord_ch = ", ".join(f"`{c}`" for c in orden)
    ord_pg = ", ".join(f'"{c}"' for c in orden)

    q_ch = (f"SELECT {proy_ch} FROM `{base_ch}`.`{tabla}` "
            f"ORDER BY {ord_ch} FORMAT TabSeparated")
    # `COLLATE \"C\"` no hace falta: el orden es por columnas numericas de clave.
    q_pg = f'SELECT {proy_pg} FROM {esquema}."{tabla}" ORDER BY {ord_pg}'

    t0 = time.perf_counter()
    h1, n1 = md5_ch(q_ch, base_ch)
    h2, n2 = md5_pg(conn, q_pg)
    seg = time.perf_counter() - t0
    return h1, n1, h2, n2, seg


def clave_orden(base_ch: str, tabla: str) -> list[str]:
    """Orden determinista: la clave de ordenamiento de la tabla en ClickHouse.
    Si no discrimina, se completa con TODAS las columnas."""
    txt = ch("SELECT sorting_key FROM system.tables "
             f"WHERE database='{base_ch}' AND name='{tabla}' FORMAT TSV", base_ch).strip()
    clave = [c.strip().strip("`") for c in txt.split(",") if c.strip()]
    cols = [n for n, _ in columnas_ch(base_ch, tabla)]
    for c in cols:
        if c not in clave:
            clave.append(c)
    return clave


def main() -> int:
    conn = pg(BASE_BENCH)
    fallos = 0

    log("== V2 — copia identica al origen, fila por fila ==\n")
    log(f"{'tabla':<32}{'filas CH':>10}{'filas PG':>10}  {'md5':<10} {'seg':>6}")
    log("-" * 74)

    tablas = [t for t in ch(
        f"SELECT name FROM system.tables WHERE database='{DWH}' "
        "AND engine NOT LIKE '%View%' ORDER BY name FORMAT TSV"
    ).strip().split("\n") if t not in EXCLUIDAS]

    tot_ch = tot_pg = 0
    for t in tablas:
        h1, n1, h2, n2, seg = comparar(conn, DWH, t, ESQ_DWH, clave_orden(DWH, t))
        ok = (h1 == h2 and n1 == n2)
        fallos += 0 if ok else 1
        tot_ch += n1
        tot_pg += n2
        log(f"{t:<32}{n1:>10,}{n2:>10,}  {'IGUAL' if ok else 'DISTINTO':<10} {seg:>6.2f}")

    log("-" * 74)
    log(f"{'TOTAL ' + str(len(tablas)) + ' tablas':<32}{tot_ch:>10,}{tot_pg:>10,}\n")

    # NO se ordena por `event_pk`: pese al nombre no es una clave. Tiene 50.000
    # valores distintos sobre 2.823.245 filas porque `rowNumberInAllBlocks()`
    # reinicia en cada bloque de insercion. Ordenar por el da un empate masivo,
    # cada motor lo desempata a su manera y el md5 sale distinto con los mismos
    # datos. Se ordena por TODAS las columnas, como en el resto de tablas.
    h1, n1, h2, n2, seg = comparar(conn, LEGADA, "fact_eventos", ESQ_WEB,
                                   clave_orden(LEGADA, "fact_eventos"))
    ok = (h1 == h2 and n1 == n2)
    fallos += 0 if ok else 1
    log(f"{'fact_eventos (escala grande)':<32}{n1:>10,}{n2:>10,}  "
        f"{'IGUAL' if ok else 'DISTINTO':<10} {seg:>6.2f}")
    log(f"  md5 ClickHouse = {h1}")
    log(f"  md5 PostgreSQL = {h2}\n")

    log("VEREDICTO V2: " + ("TODAS IGUALES" if fallos == 0 else f"{fallos} TABLAS DISTINTAS"))
    return 0 if fallos == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
