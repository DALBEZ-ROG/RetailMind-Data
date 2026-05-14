"""
etl/11_reset_clickhouse.py
Borra TODAS las tablas de ClickHouse (DROP TABLE IF EXISTS).
Permite empezar desde cero.
"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from config.clickhouse_connection import get_clickhouse_client


TABLAS = [
    "fact_eventos",
    "dim_canal",
    "dim_dispositivo",
    "dim_region",
    "dim_categoria",
    "dim_fuente_trafico",
    "dim_producto",
    "dim_usuario",
]


def main():
    print("=" * 60)
    print("  ⚠️  RESET DE CLICKHOUSE")
    print("=" * 60)

    client = get_clickhouse_client()

    print("\n🗑️  Eliminando tablas...")
    for tabla in TABLAS:
        try:
            client.command(f"DROP TABLE IF EXISTS {tabla}")
            print(f"   ✅ {tabla} eliminada")
        except Exception as e:
            print(f"   ❌ Error al eliminar {tabla}: {e}")

    print(f"\n{'=' * 60}")
    print("  Base de datos reseteada correctamente")
    print(f"{'=' * 60}")

    client.close()


if __name__ == "__main__":
    main()
