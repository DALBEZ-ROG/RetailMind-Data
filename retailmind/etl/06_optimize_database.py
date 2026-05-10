"""
06_optimize_database.py
Crea indices y optimizaciones en PostgreSQL para soportar
el crecimiento hasta 1,600,000 registros (16 semanas x 100k).
Puede ejecutarse de forma independiente o desde main.py.
"""

import os
import sys

sys.path.append(os.path.join(os.path.dirname(__file__), ".."))

from config.db_connection import get_connection

SQL_PATH = os.path.join(os.path.dirname(__file__), "..", "sql", "optimize.sql")


def optimize_database():
    print("=" * 60)
    print("PASO 6 -- Optimizacion de base de datos (indices)")
    print("=" * 60)

    # Leer el SQL de optimize.sql
    for enc in ("utf-8", "utf-8-sig", "latin-1"):
        try:
            with open(SQL_PATH, "r", encoding=enc) as f:
                sql_content = f.read()
            break
        except UnicodeDecodeError:
            continue
        except FileNotFoundError:
            print(f"[ERROR] No se encontro el archivo: {SQL_PATH}")
            sys.exit(1)

    # Separar en sentencias individuales para ejecutar una por una
    # y poder reportar progreso
    statements = [
        s.strip() for s in sql_content.split(";")
        if s.strip() and not s.strip().startswith("--")
    ]

    try:
        conn = get_connection()
        cur = conn.cursor()
    except Exception as e:
        print(f"[ERROR] Conexion fallida: {e}")
        sys.exit(1)

    created = 0
    skipped = 0

    try:
        for stmt in statements:
            if not stmt:
                continue
            try:
                cur.execute(stmt + ";")
                # Detectar si fue un CREATE INDEX o CREATE TABLE
                upper = stmt.upper().lstrip()
                if upper.startswith("CREATE INDEX"):
                    # Extraer nombre del indice para el log
                    parts = stmt.split()
                    idx_name = parts[4] if len(parts) > 4 else "?"
                    print(f"  Indice creado/verificado: {idx_name} OK")
                    created += 1
                elif upper.startswith("CREATE TABLE"):
                    parts = stmt.split()
                    tbl_name = parts[5] if len(parts) > 5 else "?"
                    print(f"  Tabla creada/verificada: {tbl_name} OK")
            except Exception as stmt_err:
                print(f"  [ADVERTENCIA] Sentencia omitida: {stmt_err}")
                skipped += 1

        conn.commit()
        print()
        print(f"  Operaciones exitosas : {created}")
        print(f"  Advertencias         : {skipped}")
        print("\nOptimizacion completada OK")

    except Exception as e:
        conn.rollback()
        print(f"[ERROR] Fallo durante la optimizacion:\n  {e}")
        sys.exit(1)
    finally:
        cur.close()
        conn.close()


if __name__ == "__main__":
    optimize_database()
