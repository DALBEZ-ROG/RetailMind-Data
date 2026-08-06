"""
conexion_verificacion.py — Conexiones a PostgreSQL de los scripts de
VERIFICACIÓN (`matriz_tableros.py`, `matriz_prevision.py`,
`matriz_alerta_cliente.py`, `validar_tableros.py`).

MISMO MECANISMO que el pipeline (`etl/dwh/conexiones.py`): variables de
entorno cargadas con python-dotenv desde `retailmind/.env`, SIN
`override=True` —las variables del proceso ganan— y SIN ningún valor por
defecto que contenga un secreto. Si falta una clave el script FALLA diciendo
cuál: tras la contenerización (2026-08-03) el **5432 es el CONTENEDOR** y el
PostgreSQL local pasó al **5433**, así que conectarse en silencio al sitio
equivocado dejó de ser una hipótesis — es lo que hacían estos cuatro scripts.

Dos roles, y por qué tienen que ser dos:

  · **LECTURA — `retailmind_etl`** (script 85): solo lectura y BYPASSRLS. Es
    el rol con el que estos scripts toman sus cifras de control, porque
    `pol_horario` está declarada con `cmd = ALL` y **ALL incluye SELECT**:
    con cualquier rol `grp_*` la verificación compararía la respuesta del API
    contra CERO FILAS y daría «todo cuadra» sin un solo error. Se reutiliza
    tal cual `get_pg_connection()` del pipeline, que ya lo resuelve.

  · **ADMIN — superusuario**. Los `matriz_*.py` ENSANCHAN la ventana de
    `grupo_horario` antes de correr la matriz y la restauran verificándola
    después; eso es una ESCRITURA sobre la seguridad de motor, y
    `retailmind_etl` es incapaz de hacerla por construcción
    (`default_transaction_read_only = on` a nivel de ROL). La contraseña del
    superusuario NO vive en `.env` a propósito (lo dice `.env.example`):
    viaja por el secreto de Docker `deploy/secrets/pg_superuser.txt`, que es
    de donde se lee cuando `PG_SUPERUSER_PASSWORD` no está en el entorno.
"""

from __future__ import annotations

import os
from pathlib import Path

import psycopg2
from dotenv import load_dotenv

# El rol de lectura ya está resuelto por el pipeline: no se reimplementa.
from etl.dwh.conexiones import get_pg_connection as pg_lectura  # noqa: F401

RAIZ_ETL = Path(__file__).resolve().parent
RAIZ_PROYECTO = RAIZ_ETL.parent

load_dotenv(RAIZ_ETL / ".env")          # sin override: el entorno manda
load_dotenv(RAIZ_PROYECTO / ".env")

#: Secreto de Docker del superusuario del contenedor (gitignored).
ARCHIVO_SECRETO_SUPERUSUARIO = RAIZ_PROYECTO / "deploy" / "secrets" / "pg_superuser.txt"


def _clave_superusuario() -> str:
    """
    La contraseña del superusuario, del entorno o del secreto de Docker.

    Nunca hay valor por defecto: si no está en ninguno de los dos sitios el
    script muere aquí, en vez de intentar entrar al motor equivocado con una
    credencial vieja.
    """
    clave = os.getenv("PG_SUPERUSER_PASSWORD")
    if clave:
        return clave

    if ARCHIVO_SECRETO_SUPERUSUARIO.is_file():
        clave = ARCHIVO_SECRETO_SUPERUSUARIO.read_text(encoding="utf-8").strip()
        if clave:
            return clave

    raise SystemExit(
        "[ERROR] Falta la contraseña del superusuario de PostgreSQL.\n"
        "  Se busca, en este orden:\n"
        "    1. la variable de entorno PG_SUPERUSER_PASSWORD\n"
        f"    2. el secreto de Docker {ARCHIVO_SECRETO_SUPERUSUARIO}\n"
        "  Hace falta porque ensanchar la ventana de `grupo_horario` es una\n"
        "  ESCRITURA y `retailmind_etl` es de solo lectura por construcción."
    )


def pg_admin():
    """
    Conexión de SUPERUSUARIO al mismo motor que lee el ETL.

    Host, puerto y base salen de las MISMAS variables que el pipeline
    (`ETL_PG_*`), para que no puedan divergir: si el ETL valida contra el
    contenedor, la matriz no puede estar tocando los horarios del local.
    """
    host = os.getenv("ETL_PG_HOST")
    puerto = os.getenv("ETL_PG_PORT")
    base = os.getenv("ETL_PG_DATABASE")
    usuario = os.getenv("PG_SUPERUSER_USER", "postgres")   # nombre de rol, no secreto

    faltantes = [n for n, v in
                 (("ETL_PG_HOST", host), ("ETL_PG_PORT", puerto),
                  ("ETL_PG_DATABASE", base)) if not v]
    if faltantes:
        raise SystemExit(
            f"[ERROR] Faltan variables de conexión: {', '.join(faltantes)}.\n"
            f"  Defínelas en {RAIZ_ETL / '.env'} (plantilla: .env.example).\n"
            "  OJO: el 5432 es el CONTENEDOR y el PostgreSQL local está en el 5433."
        )

    try:
        return psycopg2.connect(
            host=host, port=puerto, dbname=base,
            user=usuario, password=_clave_superusuario(),
            application_name="retailmind_verificacion",
            connect_timeout=10,
        )
    except psycopg2.OperationalError as e:
        raise SystemExit(
            f"[ERROR] No se pudo conectar como '{usuario}' a "
            f"{host}:{puerto}/{base}.\n  Detalle: {e}"
        )
