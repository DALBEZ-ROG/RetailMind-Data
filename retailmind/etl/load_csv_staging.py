"""
load_csv_staging.py
Carga el archivo dataset_upload.csv a la tabla dataset_temporal en PostgreSQL.
Reemplaza todos los registros existentes en staging (TRUNCATE + COPY).
Optimizado: usa COPY FROM con StringIO para maxima velocidad.
"""

import os
import sys
import io
import csv
import time

sys.path.append(os.path.join(os.path.dirname(__file__), ".."))

from config.db_connection import get_connection

CSV_PATH = os.path.join(os.path.dirname(__file__), "..", "dataset_upload.csv")

EXPECTED_COLUMNS = [
    "session_id", "user_id", "timestamp_utc", "event_index", "user_action",
    "product_id", "category", "brand", "price", "channel", "device_type",
    "region", "traffic_source", "time_spent_sec", "session_length",
    "interaction_count", "is_conversion", "drop_off_flag"
]


def load_csv_to_staging():
    print("=" * 55)
    print("Cargando CSV a dataset_temporal (staging)")
    print("=" * 55)

    start = time.time()

    # Verificar que el CSV existe
    if not os.path.exists(CSV_PATH):
        print(f"[ERROR] No se encontro el archivo CSV en: {CSV_PATH}")
        sys.exit(1)

    # Leer CSV y preparar buffer para COPY
    try:
        buffer = io.StringIO()
        row_count = 0
        with open(CSV_PATH, "r", encoding="utf-8") as f:
            reader = csv.DictReader(f)
            # Validar columnas
            missing = [c for c in EXPECTED_COLUMNS if c not in reader.fieldnames]
            if missing:
                print(f"[ERROR] Columnas faltantes en el CSV: {missing}")
                sys.exit(1)

            writer = csv.writer(buffer, delimiter="\t", lineterminator="\n")
            for row in reader:
                values = []
                for col in EXPECTED_COLUMNS:
                    val = row[col].strip() if row[col] and row[col].strip() else "\\N"
                    values.append(val)
                writer.writerow(values)
                row_count += 1

        print(f"  {row_count:,} filas leidas desde el CSV.")
    except Exception as e:
        print(f"[ERROR] No se pudo leer el CSV: {e}")
        sys.exit(1)

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

        # Usar COPY FROM para carga masiva (mucho mas rapido que INSERT)
        buffer.seek(0)
        cols = ", ".join(EXPECTED_COLUMNS)
        cur.copy_expert(
            f"COPY dataset_temporal ({cols}) FROM STDIN WITH (FORMAT csv, DELIMITER E'\\t', NULL '\\N')",
            buffer
        )

        conn.commit()
        elapsed = time.time() - start
        print(f"  {row_count:,} registros insertados en dataset_temporal. OK ({elapsed:.1f}s)")

    except Exception as e:
        conn.rollback()
        print(f"[ERROR] Fallo al cargar staging: {e}")
        sys.exit(1)
    finally:
        cur.close()
        conn.close()
        buffer.close()


if __name__ == "__main__":
    load_csv_to_staging()
