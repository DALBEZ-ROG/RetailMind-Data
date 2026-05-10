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
        # Intenta utf-8 primero; si falla, usa latin-1 (cubre cp1252/Windows)
        for enc in ("utf-8", "utf-8-sig", "latin-1"):
            try:
                with open(SQL_PATH, "r", encoding=enc) as f:
                    ddl = f.read()
                break
            except UnicodeDecodeError:
                continue
        else:
            print(f"[ERROR] No se pudo leer el archivo DDL con ninguna codificación conocida.")
            sys.exit(1)
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
        print("Tablas creadas (o ya existentes) correctamente OK")
    except Exception as e:
        print(f"[ERROR] Fallo al ejecutar el DDL:\n  {e}")
        sys.exit(1)


if __name__ == "__main__":
    create_tables()
