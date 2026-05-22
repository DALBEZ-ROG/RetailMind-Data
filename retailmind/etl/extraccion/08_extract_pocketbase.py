"""
etl/08_extract_pocketbase.py
Extrae TODOS los registros de la colección "dataset_retail" de Pocketbase
y los guarda como data/stage/datos.parquet.
"""
import os
import sys
import math
import pandas as pd
from pocketbase import PocketBase
from dotenv import load_dotenv

load_dotenv()

PB_URL      = os.getenv("PB_URL", "http://host.docker.internal:8090")
PB_EMAIL    = os.getenv("PB_EMAIL", "benitesperezdariemalberto@gmail.com")
PB_PASSWORD = os.getenv("PB_PASSWORD", "retailmind2026@.")

PAGE_SIZE   = 500
OUTPUT_PATH = os.path.join(os.path.dirname(__file__), "..", "..", "data", "stage", "datos.parquet")


def main():
    print("=" * 60)
    print("  EXTRACCIÓN DE DATOS DESDE POCKETBASE")
    print("=" * 60)

    # 1. Autenticar con Pocketbase
    print(f"\n🔐 Conectando a Pocketbase ({PB_URL})...")
    client = PocketBase(PB_URL)
    try:
        client.collection("_superusers").auth_with_password(PB_EMAIL, PB_PASSWORD)
        print("✅ Autenticación exitosa")
    except Exception as e:
        print(f"❌ Error de autenticación: {e}")
        sys.exit(1)

    # 2. Obtener total de registros
    first_page = client.collection("dataset_retail").get_list(1, 1)
    total_records = first_page.total_items
    total_pages = math.ceil(total_records / PAGE_SIZE)
    print(f"\n📊 Total de registros: {total_records:,}")
    print(f"📄 Páginas a extraer: {total_pages} (de {PAGE_SIZE} registros c/u)")

    # 3. Extraer todos los registros con paginación
    all_records = []
    for page in range(1, total_pages + 1):
        print(f"   Extrayendo página {page}/{total_pages}...")
        result = client.collection("dataset_retail").get_list(page, PAGE_SIZE)
        for item in result.items:
            record = {}
            for key, value in item.__dict__.items():
                if key not in ("id", "collection_id", "collection_name", "created", "updated"):
                    record[key] = value
                    continue
            # Usar acceso directo a los campos del registro
            all_records.append(item.__dict__)

    # 4. Convertir a DataFrame
    df = pd.DataFrame(all_records)
    df = df.drop(columns=[col for col in ['expand', 'collectionId', 'collectionName'] if col in df.columns])
    # Eliminar columnas internas de Pocketbase
    cols_to_drop = [c for c in ["id", "collection_id", "collection_name", "created", "updated"] if c in df.columns]
    df.drop(columns=cols_to_drop, inplace=True)

    # 5. Guardar como parquet
    output_abs = os.path.abspath(OUTPUT_PATH)
    os.makedirs(os.path.dirname(output_abs), exist_ok=True)
    df.to_parquet(output_abs, engine="pyarrow", index=False)

    # 6. Resumen
    file_size = os.path.getsize(output_abs)
    print(f"\n{'=' * 60}")
    print(f"  EXTRACCIÓN COMPLETADA")
    print(f"{'=' * 60}")
    print(f"  📦 Total registros: {len(df):,}")
    print(f"  📋 Columnas: {list(df.columns)}")
    print(f"  💾 Archivo: {output_abs}")
    print(f"  📏 Tamaño: {file_size / (1024*1024):.2f} MB")
    print(f"{'=' * 60}")


if __name__ == "__main__":
    main()
