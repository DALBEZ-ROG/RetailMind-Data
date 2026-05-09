"""
04_verify_load.py
Verifica el conteo de registros en cada una de las 10 tablas normalizadas
y muestra un resumen en consola.
"""

import os
import sys

sys.path.append(os.path.join(os.path.dirname(__file__), ".."))

from config.db_connection import get_connection

TABLES = [
    "regiones",
    "dispositivos",
    "canales",
    "fuentes_trafico",
    "categorias",
    "usuarios",
    "productos",
    "sesiones",
    "eventos",
    "conversiones",
]


def verify_load():
    print("=" * 55)
    print("PASO 4 — Verificación de carga")
    print("=" * 55)

    try:
        conn = get_connection()
        cur = conn.cursor()
    except Exception as e:
        print(f"[ERROR] Conexión fallida: {e}")
        sys.exit(1)

    results = []
    try:
        for table in TABLES:
            cur.execute(f"SELECT COUNT(*) FROM {table};")
            count = cur.fetchone()[0]
            results.append((table, count))
    except Exception as e:
        print(f"[ERROR] Fallo al consultar conteos:\n  {e}")
        sys.exit(1)
    finally:
        cur.close()
        conn.close()

    # ── Tabla resumen ────────────────────────────────────────────
    col_w = max(len(t) for t, _ in results) + 2
    print(f"\n{'Tabla':<{col_w}} {'Registros':>12}")
    print("-" * (col_w + 14))
    total = 0
    for table, count in results:
        print(f"{table:<{col_w}} {count:>12,}")
        total += count
    print("-" * (col_w + 14))
    print(f"{'TOTAL':<{col_w}} {total:>12,}")
    print()

    # Alerta si alguna tabla está vacía
    empty = [t for t, c in results if c == 0]
    if empty:
        print(f"[ADVERTENCIA] Las siguientes tablas están vacías: {', '.join(empty)}")
    else:
        print("Todas las tablas contienen datos ✓")


if __name__ == "__main__":
    verify_load()
