"""
utils/error_reporter.py
Reporta errores a la tabla error_log de PostgreSQL y al archivo de log.
"""

import os
import sys
import logging
import traceback

sys.path.append(os.path.join(os.path.dirname(__file__), ".."))

from config.db_connection import get_connection

logger = logging.getLogger("retailmind")

# DDL para crear la tabla si no existe
_CREATE_ERROR_LOG = """
CREATE TABLE IF NOT EXISTS error_log (
    id          SERIAL PRIMARY KEY,
    timestamp   TIMESTAMP NOT NULL DEFAULT NOW(),
    tipo_error  VARCHAR(100),
    mensaje     TEXT,
    stack_trace TEXT,
    resuelto    BOOLEAN NOT NULL DEFAULT false
);
"""


def ensure_error_log_table():
    """Crea la tabla error_log si no existe."""
    try:
        conn = get_connection()
        cur = conn.cursor()
        cur.execute(_CREATE_ERROR_LOG)
        conn.commit()
        cur.close()
        conn.close()
    except Exception as e:
        logger.warning(f"No se pudo crear tabla error_log: {e}")


def report_error(tabla: str, error: Exception, semana: int = 0):
    """
    Reporta un error al sistema:
    1. Inserta en la tabla error_log de PostgreSQL
    2. Loguea en etl.log
    3. Imprime en consola con formato claro
    """
    ensure_error_log_table()

    tipo_error = f"ETL_TABLA_{tabla.upper()}"
    mensaje = f"Semana {semana} | Tabla: {tabla} | Error: {str(error)}"
    stack = traceback.format_exc()

    # 1. Guardar en BD
    try:
        conn = get_connection()
        cur = conn.cursor()
        cur.execute(
            "INSERT INTO error_log (tipo_error, mensaje, stack_trace) VALUES (%s, %s, %s);",
            (tipo_error, mensaje, stack[:4000])
        )
        conn.commit()
        cur.close()
        conn.close()
    except Exception as db_err:
        logger.warning(f"No se pudo guardar error en BD: {db_err}")

    # 2. Loguear en archivo
    logger.error(f"[{tipo_error}] {mensaje}")

    # 3. Imprimir en consola
    print(f"[ERROR] [{tipo_error}] {mensaje}")
