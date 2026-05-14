"""
etl/09_load_clickhouse.py
Lee data/stage/datos.parquet y carga los datos en ClickHouse:
- Crea tablas de dimensiones y hechos
- Carga dimensiones primero (valores únicos)
- Carga fact_eventos en lotes de 50,000 registros
"""
import os
import sys
import time
import pandas as pd
import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from config.clickhouse_connection import get_clickhouse_client

PARQUET_PATH = os.path.join(os.path.dirname(__file__), "..", "data", "stage", "datos.parquet")
BATCH_SIZE = 50_000


# ── DDL: Definición de tablas ─────────────────────────────────────────────────

DDL_STATEMENTS = [
    """
    CREATE TABLE IF NOT EXISTS dim_canal (
        canal_id UInt32,
        canal_nombre String
    ) ENGINE = MergeTree() ORDER BY canal_id
    """,
    """
    CREATE TABLE IF NOT EXISTS dim_dispositivo (
        dispositivo_id UInt32,
        dispositivo_nombre String
    ) ENGINE = MergeTree() ORDER BY dispositivo_id
    """,
    """
    CREATE TABLE IF NOT EXISTS dim_region (
        region_id UInt32,
        region_nombre String
    ) ENGINE = MergeTree() ORDER BY region_id
    """,
    """
    CREATE TABLE IF NOT EXISTS dim_categoria (
        categoria_id UInt32,
        categoria_nombre String
    ) ENGINE = MergeTree() ORDER BY categoria_id
    """,
    """
    CREATE TABLE IF NOT EXISTS dim_fuente_trafico (
        fuente_id UInt32,
        fuente_nombre String
    ) ENGINE = MergeTree() ORDER BY fuente_id
    """,
    """
    CREATE TABLE IF NOT EXISTS dim_producto (
        producto_id String,
        categoria_id UInt32,
        brand String,
        price Float32
    ) ENGINE = MergeTree() ORDER BY producto_id
    """,
    """
    CREATE TABLE IF NOT EXISTS dim_usuario (
        user_id String,
        region_id UInt32,
        dispositivo_id UInt32
    ) ENGINE = MergeTree() ORDER BY user_id
    """,
    """
    CREATE TABLE IF NOT EXISTS fact_eventos (
        event_pk UInt64 DEFAULT rowNumberInAllBlocks(),
        session_id String,
        user_id String,
        timestamp_utc String,
        event_index UInt32,
        user_action String,
        product_id String,
        time_spent_sec Float32,
        session_length Float32,
        interaction_count UInt32,
        is_conversion UInt8,
        drop_off_flag UInt8,
        price Float32,
        channel String,
        semana UInt8 DEFAULT 1
    ) ENGINE = MergeTree() ORDER BY (session_id, event_index)
    """,
]


def main():
    print("=" * 60)
    print("  CARGA DE DATOS A CLICKHOUSE")
    print("=" * 60)
    inicio = time.time()

    # 1. Leer parquet
    parquet_abs = os.path.abspath(PARQUET_PATH)
    if not os.path.exists(parquet_abs):
        print(f"❌ No se encontró el archivo: {parquet_abs}")
        print("   Ejecuta primero: python etl/08_extract_pocketbase.py")
        sys.exit(1)

    print(f"\n📂 Leyendo {parquet_abs}...")
    df = pd.read_parquet(parquet_abs)
    print(f"   {len(df):,} registros, {len(df.columns)} columnas")

    # 2. Conectar a ClickHouse
    print("\n🔌 Conectando a ClickHouse...")
    client = get_clickhouse_client()
    version = client.query("SELECT version()").result_rows[0][0]
    print(f"   Conectado (versión {version})")

    # 3. Crear tablas
    print("\n📐 Creando tablas...")
    for ddl in DDL_STATEMENTS:
        table_name = ddl.strip().split("IF NOT EXISTS")[1].strip().split("(")[0].strip()
        client.command(ddl)
        print(f"   ✅ {table_name}")

    # 4. Cargar dimensiones
    print("\n📊 Cargando dimensiones...")

    # dim_canal
    canales = df["channel"].dropna().unique()
    data_canal = [[i + 1, str(c)] for i, c in enumerate(canales)]
    client.command("TRUNCATE TABLE IF EXISTS dim_canal")
    client.insert("dim_canal", data_canal, column_names=["canal_id", "canal_nombre"])
    print(f"   ✅ dim_canal: {len(data_canal)} registros")
    canal_map = {c: i + 1 for i, c in enumerate(canales)}

    # dim_dispositivo
    dispositivos = df["device_type"].dropna().unique()
    data_disp = [[i + 1, str(d)] for i, d in enumerate(dispositivos)]
    client.command("TRUNCATE TABLE IF EXISTS dim_dispositivo")
    client.insert("dim_dispositivo", data_disp, column_names=["dispositivo_id", "dispositivo_nombre"])
    print(f"   ✅ dim_dispositivo: {len(data_disp)} registros")
    disp_map = {d: i + 1 for i, d in enumerate(dispositivos)}

    # dim_region
    regiones = df["region"].dropna().unique()
    data_reg = [[i + 1, str(r)] for i, r in enumerate(regiones)]
    client.command("TRUNCATE TABLE IF EXISTS dim_region")
    client.insert("dim_region", data_reg, column_names=["region_id", "region_nombre"])
    print(f"   ✅ dim_region: {len(data_reg)} registros")
    region_map = {r: i + 1 for i, r in enumerate(regiones)}

    # dim_categoria
    categorias = df["category"].dropna().unique()
    data_cat = [[i + 1, str(c)] for i, c in enumerate(categorias)]
    client.command("TRUNCATE TABLE IF EXISTS dim_categoria")
    client.insert("dim_categoria", data_cat, column_names=["categoria_id", "categoria_nombre"])
    print(f"   ✅ dim_categoria: {len(data_cat)} registros")
    cat_map = {c: i + 1 for i, c in enumerate(categorias)}

    # dim_fuente_trafico
    fuentes = df["traffic_source"].dropna().unique()
    data_fuente = [[i + 1, str(f)] for i, f in enumerate(fuentes)]
    client.command("TRUNCATE TABLE IF EXISTS dim_fuente_trafico")
    client.insert("dim_fuente_trafico", data_fuente, column_names=["fuente_id", "fuente_nombre"])
    print(f"   ✅ dim_fuente_trafico: {len(data_fuente)} registros")

    # dim_producto
    productos_df = df[["product_id", "category", "brand", "price"]].drop_duplicates(subset=["product_id"])
    data_prod = []
    for _, row in productos_df.iterrows():
        cat_id = cat_map.get(row["category"], 0)
        price_val = float(row["price"]) if pd.notna(row["price"]) else 0.0
        data_prod.append([str(row["product_id"]), int(cat_id), str(row["brand"]), float(price_val)])
    client.command("TRUNCATE TABLE IF EXISTS dim_producto")
    client.insert("dim_producto", data_prod, column_names=["producto_id", "categoria_id", "brand", "price"])
    print(f"   ✅ dim_producto: {len(data_prod)} registros")

    # dim_usuario
    usuarios_df = df[["user_id", "region", "device_type"]].drop_duplicates(subset=["user_id"])
    data_user = []
    for _, row in usuarios_df.iterrows():
        reg_id = region_map.get(row["region"], 0)
        disp_id = disp_map.get(row["device_type"], 0)
        data_user.append([str(row["user_id"]), int(reg_id), int(disp_id)])
    client.command("TRUNCATE TABLE IF EXISTS dim_usuario")
    client.insert("dim_usuario", data_user, column_names=["user_id", "region_id", "dispositivo_id"])
    print(f"   ✅ dim_usuario: {len(data_user)} registros")

    # 5. Cargar fact_eventos en lotes
    print(f"\n📦 Cargando fact_eventos ({len(df):,} registros en lotes de {BATCH_SIZE:,})...")
    client.command("TRUNCATE TABLE IF EXISTS fact_eventos")

    total_cargados = 0
    for i in range(0, len(df), BATCH_SIZE):
        batch = df.iloc[i:i + BATCH_SIZE]
        rows = []
        for _, row in batch.iterrows():
            rows.append([
                str(row.get("session_id", "")),
                str(row.get("user_id", "")),
                str(row.get("timestamp_utc", "")),
                int(row.get("event_index", 0)) if pd.notna(row.get("event_index")) else 0,
                str(row.get("user_action", "")),
                str(row.get("product_id", "")),
                float(row.get("time_spent_sec", 0)) if pd.notna(row.get("time_spent_sec")) else 0.0,
                float(row.get("session_length", 0)) if pd.notna(row.get("session_length")) else 0.0,
                int(row.get("interaction_count", 0)) if pd.notna(row.get("interaction_count")) else 0,
                int(row.get("is_conversion", 0)) if pd.notna(row.get("is_conversion")) else 0,
                int(row.get("drop_off_flag", 0)) if pd.notna(row.get("drop_off_flag")) else 0,
                float(row.get("price", 0)) if pd.notna(row.get("price")) else 0.0,
                str(row.get("channel", "")),
            ])

        client.insert("fact_eventos", rows, column_names=[
            "session_id", "user_id", "timestamp_utc", "event_index",
            "user_action", "product_id", "time_spent_sec", "session_length",
            "interaction_count", "is_conversion", "drop_off_flag", "price", "channel"
        ])
        total_cargados += len(rows)
        print(f"   Lote {i // BATCH_SIZE + 1}: {total_cargados:,}/{len(df):,} registros cargados")

    # 6. Resumen
    elapsed = time.time() - inicio
    print(f"\n{'=' * 60}")
    print(f"  CARGA COMPLETADA")
    print(f"{'=' * 60}")
    print(f"  ⏱️  Tiempo total: {elapsed:.1f} segundos")
    print(f"  📦 Registros en fact_eventos: {total_cargados:,}")
    print(f"  📊 Dimensiones cargadas: 7 tablas")
    print(f"{'=' * 60}")

    client.close()


if __name__ == "__main__":
    main()
