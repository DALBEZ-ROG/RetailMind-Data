"""
etl/10_verify_clickhouse.py
Verifica la carga en ClickHouse:
- Muestra conteo de registros de cada tabla
- Ejecuta query de prueba agrupando por canal
"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", ".."))
from config.clickhouse_connection import get_clickhouse_client


def main():
    print("=" * 60)
    print("  VERIFICACIÓN DE DATOS EN CLICKHOUSE")
    print("=" * 60)

    client = get_clickhouse_client()

    # 1. Conteo de registros por tabla
    tablas = [
        "fact_eventos",
        "dim_canal",
        "dim_dispositivo",
        "dim_region",
        "dim_categoria",
        "dim_fuente_trafico",
        "dim_producto",
        "dim_usuario",
    ]

    print(f"\n{'Tabla':<25} {'Registros':>12}")
    print("-" * 40)
    total = 0
    for tabla in tablas:
        try:
            count = client.query(f"SELECT count() FROM {tabla}").result_rows[0][0]
            print(f"  {tabla:<23} {count:>10,}")
            total += count
        except Exception as e:
            print(f"  {tabla:<23} {'ERROR':>10}  ({e})")
    print("-" * 40)
    print(f"  {'TOTAL':<23} {total:>10,}")

    # 2. Query de prueba: eventos agrupados por canal
    print(f"\n\n📊 Eventos agrupados por canal:")
    print(f"{'Canal':<20} {'Eventos':>10} {'Conversiones':>14} {'Tasa %':>8}")
    print("-" * 55)
    try:
        result = client.query("""
            SELECT
                channel,
                count() AS eventos,
                sum(is_conversion) AS conversiones,
                round(sum(is_conversion) * 100.0 / count(), 2) AS tasa
            FROM fact_eventos
            GROUP BY channel
            ORDER BY eventos DESC
        """)
        for row in result.result_rows:
            print(f"  {str(row[0]):<18} {row[1]:>10,} {row[2]:>14,} {row[3]:>7.2f}%")
    except Exception as e:
        print(f"  Error en query: {e}")

    print(f"\n{'=' * 60}")
    print("  VERIFICACIÓN COMPLETADA")
    print(f"{'=' * 60}")

    client.close()


if __name__ == "__main__":
    main()
