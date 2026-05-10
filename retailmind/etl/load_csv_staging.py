"""
load_csv_staging.py
Carga el archivo dataset_upload.csv a la tabla dataset_temporal en PostgreSQL.
Reemplaza todos los registros existentes en staging (TRUNCATE + COPY).
"""

import os
import sys
import pandas as pd

sys.path.append(os.path.join(os.path.dirname(__file__), ".."))

from config.db_connection import get_connection

CSV_PATH = os.path.join(os.path.dirname(__file__), "..", "dataset_upload.csv")

EXPECTED_COLUMNS = [
    "session_id", "user_id", "timestamp_utc", "event_index", "user_action",
    "product_id", "category", "brand", "price", "channel", "device_type",
    "region", "traffic_source", "time_spent_sec", "session_length",
    "interaction_count", "is_conversion", "drop_off_flag"
]

BATCH_SIZE = 1000


def load_csv_to_staging():
    print("=" * 55)
    print("Cargando CSV a dataset_temporal (staging)")
    print("=" * 55)

    # Verificar que el CSV existe
    if not os.path.exists(CSV_PATH):
        print(f"[ERROR] No se encontro el archivo CSV en: {CSV_PATH}")
        sys.exit(1)

    # Leer CSV
    try:
        df = pd.read_csv(CSV_PATH, dtype=str, keep_default_na=False)
        print(f"  {len(df):,} filas leidas desde el CSV.")
    except Exception as e:
        print(f"[ERROR] No se pudo leer el CSV: {e}")
        sys.exit(1)

    # Validar columnas
    missing = [c for c in EXPECTED_COLUMNS if c not in df.columns]
    if missing:
        print(f"[ERROR] Columnas faltantes en el CSV: {missing}")
        sys.exit(1)

    # Normalizar: solo las columnas esperadas, en orden
    df = df[EXPECTED_COLUMNS]
    # Reemplazar cadenas vacias por None
    df = df.where(df != "", other=None)

    try:
        conn = get_connection()
        cur = conn.cursor()
    except Exception as e:
        print(f"[ERROR] Conexion fallida: {e}")
        sys.exit(1)

    try:
        # Limpiar staging antes de cargar
        print("  Limpiando dataset_temporal...")
        cur.execute("TRUNCATE TABLE dataset_temporal;")

        # Insertar en lotes
        rows = [tuple(row) for row in df.itertuples(index=False, name=None)]
        placeholders = ", ".join(["%s"] * len(EXPECTED_COLUMNS))
        cols = ", ".join(EXPECTED_COLUMNS)
        sql = f"INSERT INTO dataset_temporal ({cols}) VALUES ({placeholders});"

        total = 0
        for i in range(0, len(rows), BATCH_SIZE):
            batch = rows[i: i + BATCH_SIZE]
            cur.executemany(sql, batch)
            total += len(batch)

        conn.commit()
        print(f"  {total:,} registros insertados en dataset_temporal. OK")

    except Exception as e:
        conn.rollback()
        print(f"[ERROR] Fallo al cargar staging: {e}")
        sys.exit(1)
    finally:
        cur.close()
        conn.close()


if __name__ == "__main__":
    load_csv_to_staging()
