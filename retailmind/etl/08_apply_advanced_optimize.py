"""
08_apply_advanced_optimize.py
Ejecuta el SQL de optimizacion avanzada (vistas materializadas, indices compuestos).
Uso:
    python etl/08_apply_advanced_optimize.py
"""

import os
import sys

sys.path.append(os.path.join(os.path.dirname(__file__), ".."))

from config.db_connection import get_connection

SQL_PATH = os.path.join(os.path.dirname(__file__), "..", "sql", "advanced_optimize.sql")


def apply_advanced_optimize():
    print("=" * 60)
    print("  Aplicando optimizacion avanzada (vistas materializadas)")
    print("=" * 60)

    # Leer el SQL
    for enc in ("utf-8", "utf-8-sig", "latin-1"):
        try:
            with open(SQL_PATH, "r", encoding=enc) as f:
                sql_content = f.read()
            break
        except UnicodeDecodeError:
            continue
    else:
        print(f"[ERROR] No se pudo leer: {SQL_PATH}")
        sys.exit(1)

    try:
        conn = get_connection()
        cur = conn.cursor()
    except Exception as e:
        print(f"[ERROR] Conexion fallida: {e}")
        sys.exit(1)

    # Ejecutar sentencia por sentencia
    # Separar por punto y coma, ignorando el bloque comentado (/* ... */)
    # Primero removemos el bloque de particionamiento comentado
    clean_sql = sql_content
    start_comment = clean_sql.find("/*")
    end_comment = clean_sql.find("*/")
    if start_comment != -1 and end_comment != -1:
        clean_sql = clean_sql[:start_comment] + clean_sql[end_comment + 2:]

    statements = [s.strip() for s in clean_sql.split(";") if s.strip() and not s.strip().startswith("--")]

    success = 0
    errors = 0

    for stmt in statements:
        if not stmt:
            continue
        try:
            cur.execute(stmt + ";")
            conn.commit()
            # Identificar que se ejecuto
            upper = stmt.upper().lstrip()
            if "CREATE INDEX" in upper:
                print(f"  [OK] Indice creado/verificado")
            elif "CREATE MATERIALIZED VIEW" in upper:
                nombre = stmt.split("mv_")[1].split()[0] if "mv_" in stmt else "?"
                print(f"  [OK] Vista materializada: mv_{nombre}")
            elif "CREATE UNIQUE INDEX" in upper:
                print(f"  [OK] Indice unico creado")
            elif "CREATE OR REPLACE FUNCTION" in upper:
                print(f"  [OK] Funcion refresh_dashboard_views() creada")
            elif "DROP MATERIALIZED VIEW" in upper:
                print(f"  [OK] Vista anterior eliminada (DROP)")
            else:
                print(f"  [OK] Sentencia ejecutada")
            success += 1
        except Exception as e:
            conn.rollback()
            error_msg = str(e).strip().split("\n")[0]
            print(f"  [WARN] {error_msg}")
            errors += 1

    cur.close()
    conn.close()

    print()
    print(f"  Exitosas    : {success}")
    print(f"  Advertencias: {errors}")
    print(f"\nOptimizacion avanzada aplicada OK")


if __name__ == "__main__":
    apply_advanced_optimize()
