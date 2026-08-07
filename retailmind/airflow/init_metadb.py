"""
retailmind/airflow/init_metadb.py
Crea la base de metadatos de Airflow DENTRO del contenedor PostgreSQL que ya
existe, SIN tocar la base `retailmind`.

Lo ejecuta el servicio `airflow-init` del compose antes de `airflow db migrate`.

===========================================================================
POR QUE UN SCRIPT Y NO `docker-entrypoint-initdb.d`
===========================================================================
El directorio `deploy/postgres/initdb/` **solo corre con el volumen vacio**, y
el volumen de esta maquina lleva la base `retailmind` restaurada desde hace
dias. Anadir ahi un script para Airflow no se ejecutaria nunca, y la unica
forma de forzarlo seria borrar el volumen — es decir, borrar la base. Por eso
la creacion va aqui: idempotente y en cada arranque del perfil `airflow`.

===========================================================================
QUE CREA, Y QUE NO TOCA
===========================================================================
Crea, si no existen:
  * el rol de login `airflow` (NOSUPERUSER, NOCREATEDB, NOCREATEROLE);
  * la base de datos `airflow`, con ese rol como PROPIETARIO.

**No toca la base `retailmind`**: ni su esquema, ni sus 111 tablas, ni sus
datos, ni sus roles `grp_*`, ni `retailmind_app`, ni `retailmind_etl`. La
unica conexion que abre contra `retailmind` es la que PostgreSQL exige para
poder emitir `CREATE DATABASE` (hay que estar conectado a ALGUNA base para
hablar con el clusterr), y en esa conexion solo lee `pg_roles` y
`pg_database`. Se conecta a la base `postgres` de mantenimiento, no a
`retailmind`, precisamente para que eso quede fuera de discusion.

Un rol propio en vez del superusuario: la cadena de conexion de Airflow viaja
en una variable de entorno visible con `docker inspect`, y meter ahi la
contrasena del superusuario del cluster seria regalar el motor entero. El rol
`airflow` no puede crear bases, no puede crear roles y no tiene ni un
privilegio sobre `retailmind`.

`CREATE DATABASE` no admite `IF NOT EXISTS` ni corre dentro de una
transaccion, de ahi el autocommit y la comprobacion previa contra
`pg_database`.
"""

from __future__ import annotations

import os
import sys

import psycopg2
from psycopg2 import sql
from psycopg2.extensions import ISOLATION_LEVEL_AUTOCOMMIT

# Se conecta a la base de MANTENIMIENTO `postgres`, nunca a `retailmind`.
HOST = os.getenv("AIRFLOW_METADB_HOST", "postgres")
PORT = os.getenv("AIRFLOW_METADB_PORT", "5432")
SUPERUSER = os.getenv("AIRFLOW_METADB_SUPERUSER", "postgres")
BASE_MANTENIMIENTO = "postgres"

DB_AIRFLOW = os.getenv("AIRFLOW_METADB_NAME", "airflow")
ROL_AIRFLOW = os.getenv("AIRFLOW_METADB_USER", "airflow")
PASS_AIRFLOW = os.getenv("AIRFLOW_METADB_PASSWORD")

#: La contrasena del superusuario llega por el MISMO secreto de Docker que usa
#: el servicio `postgres` (`POSTGRES_PASSWORD_FILE`), no por una variable.
ARCHIVO_SECRETO = os.getenv("AIRFLOW_METADB_SUPERUSER_PASSWORD_FILE",
                            "/run/secrets/pg_superuser")


def _password_superusuario() -> str:
    if os.path.exists(ARCHIVO_SECRETO):
        with open(ARCHIVO_SECRETO, encoding="utf-8") as fh:
            return fh.read().strip()
    entorno = os.getenv("AIRFLOW_METADB_SUPERUSER_PASSWORD")
    if entorno:
        return entorno
    raise SystemExit(
        f"[ERROR] No hay contrasena de superusuario: no existe {ARCHIVO_SECRETO} "
        f"ni la variable AIRFLOW_METADB_SUPERUSER_PASSWORD."
    )


def main() -> int:
    if not PASS_AIRFLOW:
        raise SystemExit(
            "[ERROR] Falta AIRFLOW_METADB_PASSWORD. Definela en `.env` "
            "(clave AIRFLOW_PG_PASSWORD)."
        )

    conn = psycopg2.connect(
        host=HOST, port=PORT, dbname=BASE_MANTENIMIENTO,
        user=SUPERUSER, password=_password_superusuario(),
        connect_timeout=10, application_name="airflow_init_metadb",
    )
    conn.set_isolation_level(ISOLATION_LEVEL_AUTOCOMMIT)
    try:
        with conn.cursor() as cur:
            # ── 1. El rol ────────────────────────────────────────────────
            cur.execute("SELECT 1 FROM pg_roles WHERE rolname = %s", (ROL_AIRFLOW,))
            if cur.fetchone():
                print(f"[OK] El rol '{ROL_AIRFLOW}' ya existe.")
            else:
                # `sql.Identifier` compone el identificador de forma segura:
                # no se concatena un nombre en el texto de la sentencia, que es
                # la misma disciplina que sigue el resto del proyecto.
                cur.execute(
                    sql.SQL("CREATE ROLE {} WITH LOGIN NOSUPERUSER NOCREATEDB "
                            "NOCREATEROLE NOINHERIT PASSWORD %s")
                    .format(sql.Identifier(ROL_AIRFLOW)),
                    (PASS_AIRFLOW,),
                )
                print(f"[NUEVO] Rol '{ROL_AIRFLOW}' creado (sin privilegios sobre retailmind).")

            # La contrasena se re-aplica siempre: asi rotarla en `.env` basta
            # para que el siguiente arranque funcione, sin pasos manuales.
            cur.execute(
                sql.SQL("ALTER ROLE {} WITH PASSWORD %s")
                .format(sql.Identifier(ROL_AIRFLOW)),
                (PASS_AIRFLOW,),
            )

            # ── 2. La base ───────────────────────────────────────────────
            cur.execute("SELECT 1 FROM pg_database WHERE datname = %s", (DB_AIRFLOW,))
            if cur.fetchone():
                print(f"[OK] La base '{DB_AIRFLOW}' ya existe.")
            else:
                cur.execute(
                    sql.SQL("CREATE DATABASE {} OWNER {} ENCODING 'UTF8'")
                    .format(sql.Identifier(DB_AIRFLOW), sql.Identifier(ROL_AIRFLOW))
                )
                print(f"[NUEVO] Base '{DB_AIRFLOW}' creada, propietario '{ROL_AIRFLOW}'.")

            # ── 3. Testigo: `retailmind` sigue intacta y ajena ────────────
            cur.execute("SELECT count(*) FROM pg_database WHERE datname = 'retailmind'")
            if cur.fetchone()[0] != 1:
                raise SystemExit("[ERROR] La base 'retailmind' no aparece en el cluster.")
            cur.execute(
                "SELECT has_database_privilege(%s, 'retailmind', 'CONNECT')",
                (ROL_AIRFLOW,),
            )
            print(f"[INFO] 'retailmind' intacta. ¿'{ROL_AIRFLOW}' puede conectarse a ella? "
                  f"{cur.fetchone()[0]} (lo concede el PUBLIC por defecto de "
                  f"PostgreSQL; el rol no tiene NI UN privilegio sobre sus objetos).")
    finally:
        conn.close()

    print(f"[LISTO] Metadatos de Airflow en {HOST}:{PORT}/{DB_AIRFLOW}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
