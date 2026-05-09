"""
02_load_lookup_tables.py
Carga las tablas de catálogo (lookup) desde dataset_temporal:
  regiones, dispositivos, canales, fuentes_trafico, categorias
Usa INSERT ... ON CONFLICT DO NOTHING para ser idempotente.
"""

import os
import sys

sys.path.append(os.path.join(os.path.dirname(__file__), ".."))

from config.db_connection import get_connection

BATCH_SIZE = 1000


def _insert_batch(cur, sql, rows, table_name):
    """Inserta en lotes de BATCH_SIZE e imprime progreso."""
    total = 0
    for i in range(0, len(rows), BATCH_SIZE):
        batch = rows[i : i + BATCH_SIZE]
        cur.executemany(sql, batch)
        total += len(batch)
    print(f"Cargando tabla {table_name}... [{total} registros insertados] ✓")


def load_lookup_tables():
    print("=" * 55)
    print("PASO 2 — Cargando tablas de catálogo (lookup)")
    print("=" * 55)

    try:
        conn = get_connection()
        cur = conn.cursor()
    except Exception as e:
        print(f"[ERROR] Conexión fallida: {e}")
        sys.exit(1)

    try:
        # ── 1. regiones ──────────────────────────────────────────
        cur.execute("SELECT DISTINCT region FROM dataset_temporal WHERE region IS NOT NULL;")
        regiones = [(row[0], None) for row in cur.fetchall()]
        _insert_batch(
            cur,
            "INSERT INTO regiones (region_name, country) VALUES (%s, %s) ON CONFLICT (region_name) DO NOTHING;",
            regiones,
            "regiones",
        )

        # ── 2. dispositivos ──────────────────────────────────────
        cur.execute("SELECT DISTINCT device_type FROM dataset_temporal WHERE device_type IS NOT NULL;")
        dispositivos = [(row[0],) for row in cur.fetchall()]
        _insert_batch(
            cur,
            "INSERT INTO dispositivos (device_type_name) VALUES (%s) ON CONFLICT (device_type_name) DO NOTHING;",
            dispositivos,
            "dispositivos",
        )

        # ── 3. canales ───────────────────────────────────────────
        cur.execute("SELECT DISTINCT channel FROM dataset_temporal WHERE channel IS NOT NULL;")
        canales = [(row[0], None) for row in cur.fetchall()]
        _insert_batch(
            cur,
            "INSERT INTO canales (channel_name, description) VALUES (%s, %s) ON CONFLICT (channel_name) DO NOTHING;",
            canales,
            "canales",
        )

        # ── 4. fuentes_trafico ───────────────────────────────────
        cur.execute("SELECT DISTINCT traffic_source FROM dataset_temporal WHERE traffic_source IS NOT NULL;")
        fuentes = [(row[0], None) for row in cur.fetchall()]
        _insert_batch(
            cur,
            "INSERT INTO fuentes_trafico (source_name, type) VALUES (%s, %s) ON CONFLICT (source_name) DO NOTHING;",
            fuentes,
            "fuentes_trafico",
        )

        # ── 5. categorias ────────────────────────────────────────
        cur.execute("SELECT DISTINCT category FROM dataset_temporal WHERE category IS NOT NULL;")
        categorias = [(row[0], None) for row in cur.fetchall()]
        _insert_batch(
            cur,
            "INSERT INTO categorias (category_name, description) VALUES (%s, %s) ON CONFLICT (category_name) DO NOTHING;",
            categorias,
            "categorias",
        )

        conn.commit()
        print("\nTablas de catálogo cargadas exitosamente ✓")

    except Exception as e:
        conn.rollback()
        print(f"[ERROR] Fallo al cargar tablas de catálogo:\n  {e}")
        sys.exit(1)
    finally:
        cur.close()
        conn.close()


if __name__ == "__main__":
    load_lookup_tables()
