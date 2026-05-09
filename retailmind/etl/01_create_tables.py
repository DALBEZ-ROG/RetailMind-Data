"""
01_create_tables.py
Ejecuta el DDL en PostgreSQL para crear las 10 tablas normalizadas.
Puede ejecutarse de forma independiente o desde main.py.
"""

import os
import sys

# Permite importar config/ tanto en ejecución directa como desde main.py
sys.path.append(os.path.join(os.path.dirname(__file__), ".."))

from config.db_connection import get_connection

SQL_PATH = os.path.join(os.path.dirname(__file__), "..", "sql", "create_tables.sql")


def create_tables():
    print("=" * 55)
    print("PASO 1 — Creando tablas normalizadas en PostgreSQL")
    print("=" * 55)

    try:
        with open(SQL_PATH, "r", encoding="utf-8") as f:
            ddl = f.read()
    except FileNotFoundError:
        print(f"[ERROR] No se encontró el archivo DDL en: {SQL_PATH}")
        sys.exit(1)

    try:
        conn = get_connection()
        cur = conn.cursor()
        cur.execute(ddl)
        conn.commit()
        cur.close()
        conn.close()
        print("Tablas creadas (o ya existentes) correctamente ✓")
    except Exception as e:
        print(f"[ERROR] Fallo al ejecutar el DDL:\n  {e}")
        sys.exit(1)


if __name__ == "__main__":
    create_tables()
