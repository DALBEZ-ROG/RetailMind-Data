"""
07_monitor_performance.py
Monitoreo de rendimiento de la base de datos RetailMind Analytics.
Ejecuta EXPLAIN ANALYZE en las consultas principales del dashboard,
muestra tiempos de ejecucion, tamano de tablas y conteos.

Uso:
    python etl/07_monitor_performance.py
"""

import os
import sys
import time

sys.path.append(os.path.join(os.path.dirname(__file__), ".."))

from config.db_connection import get_connection


# ── Consultas principales del dashboard ──────────────────────────────────────

QUERIES = [
    {
        "nombre": "Total sesiones (COUNT)",
        "sql": "SELECT COUNT(*) FROM sesiones;"
    },
    {
        "nombre": "Sesiones por canal (GROUP BY)",
        "sql": """
            SELECT c.channel_name, COUNT(s.session_id)
            FROM sesiones s
            JOIN canales c ON s.channel_id = c.channel_id
            GROUP BY c.channel_name
            ORDER BY COUNT(s.session_id) DESC;
        """
    },
    {
        "nombre": "Sesiones por region (GROUP BY + 2 JOINs)",
        "sql": """
            SELECT r.region_name, COUNT(s.session_id)
            FROM sesiones s
            JOIN usuarios u ON s.user_id = u.user_id
            JOIN regiones r ON u.region_id = r.region_id
            GROUP BY r.region_name
            ORDER BY COUNT(s.session_id) DESC;
        """
    },
    {
        "nombre": "Tasa conversion por semana",
        "sql": """
            SELECT EXTRACT(WEEK FROM s.timestamp_utc) AS semana,
                   COUNT(c.conversion_id),
                   SUM(CASE WHEN c.is_conversion = true THEN 1 ELSE 0 END)
            FROM conversiones c
            JOIN sesiones s ON c.session_id = s.session_id
            WHERE s.timestamp_utc IS NOT NULL
            GROUP BY EXTRACT(WEEK FROM s.timestamp_utc)
            ORDER BY semana;
        """
    },
    {
        "nombre": "Sesiones paginadas (LIMIT 20)",
        "sql": "SELECT * FROM sesiones ORDER BY session_id LIMIT 20 OFFSET 0;"
    },
]

TABLES = [
    "regiones", "dispositivos", "canales", "fuentes_trafico",
    "categorias", "usuarios", "productos", "sesiones", "eventos", "conversiones"
]


def run_explain_analyze(cur, sql):
    """Ejecuta EXPLAIN ANALYZE y retorna el tiempo de planificacion + ejecucion."""
    cur.execute(f"EXPLAIN ANALYZE {sql}")
    lines = [row[0] for row in cur.fetchall()]
    # Buscar la linea con "Execution Time"
    exec_time = None
    for line in lines:
        if "Execution Time" in line:
            # Formato: "Execution Time: 1.234 ms"
            parts = line.split(":")
            if len(parts) >= 2:
                exec_time = parts[1].strip()
            break
    return exec_time, lines


def get_table_size(cur, table):
    """Retorna el tamano de la tabla en MB."""
    cur.execute(f"SELECT pg_total_relation_size('{table}');")
    size_bytes = cur.fetchone()[0]
    return size_bytes / (1024 * 1024)


def get_table_count(cur, table):
    """Retorna el conteo de registros."""
    cur.execute(f"SELECT COUNT(*) FROM {table};")
    return cur.fetchone()[0]


def monitor_performance():
    print()
    print("=" * 70)
    print("  RetailMind Analytics -- Monitor de Rendimiento")
    print("=" * 70)

    try:
        conn = get_connection()
        cur = conn.cursor()
    except Exception as e:
        print(f"[ERROR] Conexion fallida: {e}")
        sys.exit(1)

    # ── 1. EXPLAIN ANALYZE de consultas principales ───────────────────────────
    print()
    print("-" * 70)
    print("  RENDIMIENTO DE CONSULTAS (EXPLAIN ANALYZE)")
    print("-" * 70)
    print(f"\n{'#':<4} {'Consulta':<45} {'Tiempo':>15}")
    print("-" * 70)

    for i, q in enumerate(QUERIES, 1):
        try:
            exec_time, _ = run_explain_analyze(cur, q["sql"])
            print(f"{i:<4} {q['nombre']:<45} {exec_time or 'N/A':>15}")
        except Exception as e:
            print(f"{i:<4} {q['nombre']:<45} {'ERROR':>15}")
            print(f"     -> {e}")

    # ── 2. Tamano y conteo de tablas ──────────────────────────────────────────
    print()
    print("-" * 70)
    print("  ESTADO DE TABLAS")
    print("-" * 70)
    print(f"\n{'Tabla':<20} {'Registros':>12} {'Tamano (MB)':>14}")
    print("-" * 50)

    total_registros = 0
    total_size = 0.0

    for table in TABLES:
        try:
            count = get_table_count(cur, table)
            size  = get_table_size(cur, table)
            total_registros += count
            total_size += size
            print(f"{table:<20} {count:>12,} {size:>14.2f}")
        except Exception as e:
            print(f"{table:<20} {'ERROR':>12} {'N/A':>14}")

    print("-" * 50)
    print(f"{'TOTAL':<20} {total_registros:>12,} {total_size:>14.2f}")

    # ── 3. Verificar vistas materializadas ────────────────────────────────────
    print()
    print("-" * 70)
    print("  VISTAS MATERIALIZADAS")
    print("-" * 70)

    views = [
        "mv_resumen_dashboard",
        "mv_sesiones_por_canal",
        "mv_sesiones_por_region",
        "mv_sesiones_por_dispositivo",
        "mv_tasa_conversion_semanal"
    ]

    print(f"\n{'Vista':<35} {'Estado':>12} {'Registros':>12}")
    print("-" * 62)

    for view in views:
        try:
            cur.execute(f"SELECT COUNT(*) FROM {view};")
            count = cur.fetchone()[0]
            print(f"{view:<35} {'ACTIVA':>12} {count:>12,}")
        except Exception:
            print(f"{view:<35} {'NO EXISTE':>12} {'-':>12}")
            conn.rollback()  # Limpiar el error de la transaccion

    # ── 4. Verificar indices ──────────────────────────────────────────────────
    print()
    print("-" * 70)
    print("  INDICES PERSONALIZADOS")
    print("-" * 70)

    cur.execute("""
        SELECT indexname, tablename, pg_size_pretty(pg_relation_size(indexname::regclass))
        FROM pg_indexes
        WHERE schemaname = 'public'
          AND indexname LIKE 'idx_%'
        ORDER BY tablename, indexname;
    """)
    indices = cur.fetchall()

    print(f"\n{'Indice':<45} {'Tabla':<18} {'Tamano':>10}")
    print("-" * 75)
    for idx_name, tbl_name, size in indices:
        print(f"{idx_name:<45} {tbl_name:<18} {size:>10}")

    if not indices:
        print("  (sin indices personalizados encontrados)")

    print()
    print("=" * 70)
    print("  Monitoreo completado")
    print("=" * 70)
    print()

    cur.close()
    conn.close()


if __name__ == "__main__":
    monitor_performance()
