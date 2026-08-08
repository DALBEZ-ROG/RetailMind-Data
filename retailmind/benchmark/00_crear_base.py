"""
PASO 0 — Crea la base de pruebas AISLADA `retailmind_benchmark`.

Es una base NUEVA del mismo cluster PostgreSQL, con la misma configuracion
regional que la operativa (ICU `es-EC`) para que ninguna diferencia de
ordenamiento se cuele en la comparacion. La base operativa `retailmind` no se
toca: este script solo puede crear o borrar `retailmind_benchmark`, y lo
comprueba antes de ejecutar nada.

La base queda PERMANENTE. `--recrear` la borra y la vuelve a crear, y es lo
unico que borra algo en todo el experimento.

    py -3 retailmind/benchmark/00_crear_base.py
    py -3 retailmind/benchmark/00_crear_base.py --recrear
"""

from __future__ import annotations

import sys

import psycopg2
from psycopg2 import sql

from comun import BASE_BENCH, clave_superusuario, log

DDL = f"""
CREATE DATABASE {BASE_BENCH}
    ENCODING 'UTF8' TEMPLATE template0
    LOCALE_PROVIDER icu ICU_LOCALE 'es-EC' LOCALE 'C.UTF-8'
"""


def main() -> int:
    # Se conecta a `postgres`, la base de mantenimiento: CREATE/DROP DATABASE no
    # pueden ejecutarse desde dentro de la base afectada.
    conn = psycopg2.connect(host="localhost", port=5432, user="postgres",
                            password=clave_superusuario(), dbname="postgres")
    conn.autocommit = True

    with conn.cursor() as cur:
        cur.execute("SELECT 1 FROM pg_database WHERE datname = %s", (BASE_BENCH,))
        existe = cur.fetchone() is not None

        if existe and "--recrear" in sys.argv:
            log(f"Borrando `{BASE_BENCH}` (--recrear)...")
            cur.execute(sql.SQL("DROP DATABASE {} WITH (FORCE)")
                        .format(sql.Identifier(BASE_BENCH)))
            existe = False
        elif existe:
            log(f"`{BASE_BENCH}` ya existe. Usa --recrear para rehacerla.")

        if not existe:
            cur.execute(DDL)
            log(f"Creada `{BASE_BENCH}`.")

        cur.execute("SELECT datname, pg_size_pretty(pg_database_size(datname)) "
                    "FROM pg_database WHERE datistemplate = false ORDER BY 1")
        log("\nBases del cluster:")
        for n, t in cur.fetchall():
            marca = "  <- banco de pruebas" if n == BASE_BENCH else ""
            log(f"  {n:<24} {t:>10}{marca}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
