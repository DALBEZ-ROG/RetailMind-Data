"""
utils/load_tracker.py
Funciones de control de cargas semanales usando la tabla carga_historial.
"""

import os
import sys

sys.path.append(os.path.join(os.path.dirname(__file__), ".."))

from config.db_connection import get_connection

# DDL de la tabla de historial (idempotente)
_CREATE_HISTORIAL = """
CREATE TABLE IF NOT EXISTS carga_historial (
    id                   SERIAL PRIMARY KEY,
    semana               INT NOT NULL UNIQUE,
    fecha_carga          TIMESTAMP NOT NULL DEFAULT NOW(),
    registros_procesados INT NOT NULL DEFAULT 0,
    registros_nuevos     INT NOT NULL DEFAULT 0
);
"""


def ensure_historial_table():
    """Crea la tabla carga_historial si no existe."""
    try:
        conn = get_connection()
        cur = conn.cursor()
        cur.execute(_CREATE_HISTORIAL)
        conn.commit()
        cur.close()
        conn.close()
    except Exception as e:
        raise RuntimeError(f"[ERROR] No se pudo crear carga_historial: {e}")


def get_semana_actual() -> int:
    """
    Retorna el numero de la proxima semana a cargar.
    Se basa en cuantas entradas existen en carga_historial.
    Semana 1 = primera carga, semana 2 = segunda, etc.
    """
    ensure_historial_table()
    try:
        conn = get_connection()
        cur = conn.cursor()
        cur.execute("SELECT COALESCE(MAX(semana), 0) FROM carga_historial;")
        ultima = cur.fetchone()[0]
        cur.close()
        conn.close()
        return ultima + 1
    except Exception as e:
        raise RuntimeError(f"[ERROR] No se pudo obtener la semana actual: {e}")


def semana_ya_cargada(semana: int) -> bool:
    """
    Verifica si la semana indicada ya fue procesada.
    Retorna True si existe un registro en carga_historial para esa semana.
    """
    ensure_historial_table()
    try:
        conn = get_connection()
        cur = conn.cursor()
        cur.execute(
            "SELECT COUNT(*) FROM carga_historial WHERE semana = %s;",
            (semana,)
        )
        count = cur.fetchone()[0]
        cur.close()
        conn.close()
        return count > 0
    except Exception as e:
        raise RuntimeError(f"[ERROR] No se pudo verificar semana {semana}: {e}")


def registrar_carga(semana: int, procesados: int, nuevos: int):
    """
    Inserta o actualiza el registro de la semana en carga_historial.
    Usa ON CONFLICT para soportar el modo --force.
    """
    ensure_historial_table()
    try:
        conn = get_connection()
        cur = conn.cursor()
        cur.execute(
            """
            INSERT INTO carga_historial (semana, fecha_carga, registros_procesados, registros_nuevos)
            VALUES (%s, NOW(), %s, %s)
            ON CONFLICT (semana) DO UPDATE
                SET fecha_carga          = NOW(),
                    registros_procesados = EXCLUDED.registros_procesados,
                    registros_nuevos     = EXCLUDED.registros_nuevos;
            """,
            (semana, procesados, nuevos)
        )
        conn.commit()
        cur.close()
        conn.close()
    except Exception as e:
        raise RuntimeError(f"[ERROR] No se pudo registrar la carga de semana {semana}: {e}")


def listar_cargas():
    """Imprime el historial completo de cargas."""
    ensure_historial_table()
    try:
        conn = get_connection()
        cur = conn.cursor()
        cur.execute(
            "SELECT semana, fecha_carga, registros_procesados, registros_nuevos "
            "FROM carga_historial ORDER BY semana;"
        )
        rows = cur.fetchall()
        cur.close()
        conn.close()

        if not rows:
            print("  (sin cargas registradas)")
            return

        print(f"\n{'Semana':>7}  {'Fecha':>20}  {'Procesados':>12}  {'Nuevos':>8}")
        print("-" * 56)
        for semana, fecha, procesados, nuevos in rows:
            print(f"{semana:>7}  {str(fecha)[:19]:>20}  {procesados:>12,}  {nuevos:>8,}")
        print()
    except Exception as e:
        raise RuntimeError(f"[ERROR] No se pudo listar el historial: {e}")
