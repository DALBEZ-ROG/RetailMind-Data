"""
Piezas compartidas del banco de pruebas columnar vs. relacional.

Conexiones:
  * PostgreSQL  -> contenedor `retailmind-postgres-1`, base `retailmind_benchmark`
                   (NUNCA `retailmind`: ver `guardia_base()`).
  * ClickHouse  -> contenedor `retailmind-clickhouse-1`, SOLO LECTURA.

El transporte entre motores es TabSeparated: es el formato en el que CH y PG
coinciden carácter por carácter (NULL = `\\N`, escapes `\\t` `\\n` `\\\\`), así
que la copia no pasa por ningún parser intermedio que pueda alterar un valor.
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
from pathlib import Path

import psycopg2

RAIZ = Path(__file__).resolve().parents[2]

CT_PG = "retailmind-postgres-1"
CT_CH = "retailmind-clickhouse-1"

BASE_BENCH = "retailmind_benchmark"
BASE_PROHIBIDA = {"retailmind", "airflow", "postgres", "template0", "template1"}

DWH = "retailmind_dwh"          # origen ClickHouse del almacén
LEGADA = "retailmind"           # origen ClickHouse de la analítica web

ESQ_DWH = "dwh"                 # destino PG para las 21 tablas del modelo
ESQ_WEB = "web"                 # destino PG para fact_eventos


# ─────────────────────────────────────────────────────────────────────
# Conexión
# ─────────────────────────────────────────────────────────────────────

def clave_superusuario() -> str:
    return (RAIZ / "deploy" / "secrets" / "pg_superuser.txt").read_text().strip()


def guardia_base(dsn_db: str) -> None:
    """La base operativa es INTOCABLE. Cualquier destino que no sea el banco
    de pruebas aborta el proceso antes de abrir la conexión."""
    if dsn_db != BASE_BENCH:
        raise SystemExit(
            f"ABORTADO: el destino de escritura seria `{dsn_db}`. "
            f"Solo se admite `{BASE_BENCH}`."
        )


def pg(dbname: str = BASE_BENCH, autocommit: bool = True):
    guardia_base(dbname)
    c = psycopg2.connect(
        host="localhost", port=5432, user="postgres",
        password=clave_superusuario(), dbname=dbname,
    )
    c.autocommit = autocommit
    with c.cursor() as cur:
        # Las marcas de tiempo del DWH vienen sin zona en TSV; el negocio es
        # America/Guayaquil y ahi es donde el ETL las calculo.
        cur.execute("SET TimeZone = 'America/Guayaquil'")
    return c


def pg_lectura(dbname: str):
    """Conexión de SOLO LECTURA a una base distinta del banco (p. ej. la
    operativa, para las verificaciones V1). No pasa por `guardia_base`
    porque fija `default_transaction_read_only`."""
    c = psycopg2.connect(
        host="localhost", port=5432, user="postgres",
        password=clave_superusuario(), dbname=dbname,
    )
    c.autocommit = True
    with c.cursor() as cur:
        cur.execute("SET default_transaction_read_only = on")
    return c


def ch(consulta: str, base: str = DWH) -> str:
    """Ejecuta una consulta en ClickHouse y devuelve el texto crudo."""
    r = subprocess.run(
        ["docker", "exec", CT_CH, "clickhouse-client",
         "--database", base, "--query", consulta],
        capture_output=True,
    )
    if r.returncode != 0:
        raise RuntimeError(r.stderr.decode("utf-8", "replace"))
    return r.stdout.decode("utf-8", "replace")


def ch_valor(consulta: str, base: str = DWH) -> str:
    return ch(consulta, base).strip()


# ─────────────────────────────────────────────────────────────────────
# Traducción de tipos ClickHouse -> PostgreSQL
# ─────────────────────────────────────────────────────────────────────

_SIMPLES = {
    "UInt8": "smallint", "Int8": "smallint",
    "UInt16": "integer", "Int16": "smallint",
    "UInt32": "bigint", "Int32": "integer",
    "UInt64": "bigint", "Int64": "bigint",
    "Float32": "real", "Float64": "double precision",
    "String": "text", "UUID": "uuid", "Date": "date", "Date32": "date",
}


def tipo_pg(tipo_ch: str) -> tuple[str, bool]:
    """Devuelve (tipo PostgreSQL, admite_nulo)."""
    nulo = False
    t = tipo_ch.strip()
    m = re.fullmatch(r"Nullable\((.*)\)", t)
    if m:
        nulo, t = True, m.group(1)
    m = re.fullmatch(r"LowCardinality\((.*)\)", t)
    if m:
        t = m.group(1)
        m2 = re.fullmatch(r"Nullable\((.*)\)", t)
        if m2:
            nulo, t = True, m2.group(1)

    if t.startswith("DateTime"):
        return "timestamptz", nulo
    m = re.fullmatch(r"Decimal\((\d+),\s*(\d+)\)", t)
    if m:
        return f"numeric({m.group(1)},{m.group(2)})", nulo
    m = re.fullmatch(r"Array\((.*)\)", t)
    if m:
        interno, _ = tipo_pg(m.group(1))
        return f"{interno}[]", nulo
    if t in _SIMPLES:
        return _SIMPLES[t], nulo
    raise ValueError(f"tipo ClickHouse sin traduccion: {tipo_ch}")


def expresion_lectura(col: str, tipo_ch: str) -> str:
    """Expresión con la que se LEE la columna en ClickHouse para el volcado.

    Solo los arreglos necesitan tratamiento: la representación nativa de CH
    (`['a','b']`) no es la de PostgreSQL (`{"a","b"}`). Todo lo demás viaja
    tal cual — no se transforma un valor durante la copia.
    """
    t = tipo_ch.replace("LowCardinality(", "").replace("Nullable(", "")
    if t.startswith("Array(String"):
        return (
            f"concat('{{', arrayStringConcat(arrayMap(x -> "
            f"concat('\"', replaceAll(replaceAll(x, '\\\\', '\\\\\\\\'), '\"', '\\\\\"'), '\"'), "
            f"`{col}`), ','), '}}')"
        )
    if t.startswith("Array("):
        return (
            f"concat('{{', arrayStringConcat(arrayMap(x -> toString(x), `{col}`), ','), '}}')"
        )
    return f"`{col}`"


def columnas_ch(base: str, tabla: str) -> list[tuple[str, str]]:
    txt = ch(
        "SELECT name, type FROM system.columns "
        f"WHERE database = '{base}' AND table = '{tabla}' "
        "ORDER BY position FORMAT TSV", base
    )
    return [tuple(l.split("\t")) for l in txt.strip().split("\n")]


# ─────────────────────────────────────────────────────────────────────
# Copia CH -> PG
# ─────────────────────────────────────────────────────────────────────

def crear_tabla(conn, esquema: str, tabla: str, cols: list[tuple[str, str]]) -> str:
    defs = []
    for nombre, tch in cols:
        tpg, nulo = tipo_pg(tch)
        defs.append(f'    "{nombre}" {tpg}{"" if nulo else " NOT NULL"}')
    ddl = (f'DROP TABLE IF EXISTS {esquema}."{tabla}";\n'
           f'CREATE TABLE {esquema}."{tabla}" (\n' + ",\n".join(defs) + "\n);")
    with conn.cursor() as cur:
        cur.execute(ddl)
    return ddl


def copiar(conn, base_ch: str, tabla: str, esquema: str, destino: str | None = None,
           extra_where: str = "") -> int:
    """Vuelca una tabla de ClickHouse a PostgreSQL por TabSeparated en flujo."""
    destino = destino or tabla
    cols = columnas_ch(base_ch, tabla)
    crear_tabla(conn, esquema, destino, cols)

    proy = ", ".join(expresion_lectura(n, t) for n, t in cols)
    consulta = f"SELECT {proy} FROM `{base_ch}`.`{tabla}` {extra_where} FORMAT TabSeparated"

    p = subprocess.Popen(
        ["docker", "exec", CT_CH, "clickhouse-client",
         "--database", base_ch, "--max_block_size", "65536", "--query", consulta],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    )
    lista = ", ".join(f'"{n}"' for n, _ in cols)
    with conn.cursor() as cur:
        cur.copy_expert(
            f"COPY {esquema}.\"{destino}\" ({lista}) FROM STDIN WITH (FORMAT text)",
            p.stdout,
        )
    err = p.stderr.read().decode("utf-8", "replace")
    p.wait()
    if p.returncode != 0:
        raise RuntimeError(f"clickhouse-client fallo en {tabla}: {err}")

    with conn.cursor() as cur:
        cur.execute(f'SELECT count(*) FROM {esquema}."{destino}"')
        return cur.fetchone()[0]


def log(*a):
    print(*a, flush=True)
