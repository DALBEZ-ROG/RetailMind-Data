"""
etl/12_generate_synthetic.py
Genera 108,584 registros sintéticos para una semana específica
e inserta directamente en ClickHouse retailmind.fact_eventos.
"""
import os
import sys
import time
import argparse
import numpy as np
import pandas as pd

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", ".."))
from config.clickhouse_connection import get_clickhouse_client, CH_DATABASE

TOTAL_REGISTROS = 108_584
BATCH_SIZE = 50_000
DB = CH_DATABASE

# Distribuciones
CHANNELS = ['mobile', 'web', 'app']
CHANNEL_WEIGHTS = [0.60, 0.25, 0.15]

ACTIONS = ['click', 'view', 'add_to_cart', 'wishlist', 'drop', 'purchase']
ACTION_WEIGHTS = [0.35, 0.30, 0.12, 0.07, 0.12, 0.04]

NUM_USUARIOS = np.random.randint(6000, 7001)
NUM_SESIONES = np.random.randint(15000, 20001)


def verificar_semana_existe(client, semana):
    """Verifica si la semana ya tiene datos en fact_eventos."""
    result = client.query(
        f"SELECT count() FROM {DB}.fact_eventos WHERE semana = {{s:UInt8}}",
        parameters={"s": semana}
    )
    count = result.result_rows[0][0]
    return count > 0


def generar_datos(semana, n=TOTAL_REGISTROS):
    """Genera n registros sintéticos usando numpy vectorizado."""
    rng = np.random.default_rng()

    # Generar IDs
    user_ids = [f"user_{i:05d}" for i in rng.integers(1, NUM_USUARIOS + 1, size=n)]
    session_ids = [f"sess_{semana:02d}_{i:06d}" for i in rng.integers(1, NUM_SESIONES + 1, size=n)]
    product_ids = [f"prod_{i:04d}" for i in rng.integers(1, 501, size=n)]

    # Canales
    channels = rng.choice(CHANNELS, size=n, p=CHANNEL_WEIGHTS)

    # Acciones
    user_actions = rng.choice(ACTIONS, size=n, p=ACTION_WEIGHTS)

    # is_conversion: 1 solo cuando action = purchase
    is_conversion = np.where(user_actions == 'purchase', 1, 0).astype(np.uint8)

    # drop_off_flag: 1 solo cuando action = drop
    drop_off_flag = np.where(user_actions == 'drop', 1, 0).astype(np.uint8)

    # Métricas numéricas
    prices = np.round(rng.uniform(5.99, 999.99, size=n), 2).astype(np.float32)
    time_spent = np.round(rng.uniform(10, 3600, size=n), 1).astype(np.float32)
    session_length = np.round(rng.uniform(1, 20, size=n), 1).astype(np.float32)
    interaction_count = rng.integers(1, 51, size=n).astype(np.uint32)
    event_index = rng.integers(1, 30, size=n).astype(np.uint32)

    # Timestamps
    base_ts = pd.Timestamp("2026-01-01") + pd.Timedelta(weeks=semana - 1)
    offsets = pd.to_timedelta(rng.integers(0, 7 * 24 * 3600, size=n), unit='s')
    timestamps = (base_ts + offsets).strftime("%Y-%m-%d %H:%M:%S")

    df = pd.DataFrame({
        "session_id": session_ids,
        "user_id": user_ids,
        "timestamp_utc": timestamps,
        "event_index": event_index,
        "user_action": user_actions,
        "product_id": product_ids,
        "time_spent_sec": time_spent,
        "session_length": session_length,
        "interaction_count": interaction_count,
        "is_conversion": is_conversion,
        "drop_off_flag": drop_off_flag,
        "price": prices,
        "channel": channels,
        "semana": np.full(n, semana, dtype=np.uint8),
    })

    return df


def main():
    parser = argparse.ArgumentParser(description="Genera datos sintéticos para una semana")
    parser.add_argument("--semana", type=int, required=True, help="Número de semana (2-52)")
    args = parser.parse_args()

    semana = args.semana
    if semana < 2 or semana > 52:
        print(f"❌ Error: semana debe estar entre 2 y 52 (recibido: {semana})")
        sys.exit(1)

    print("=" * 60)
    print(f"  GENERACIÓN DE DATOS SINTÉTICOS - SEMANA {semana}")
    print("=" * 60)
    inicio = time.time()

    # Conectar a ClickHouse
    print("\n🔌 Conectando a ClickHouse...")
    client = get_clickhouse_client()
    version = client.query("SELECT version()").result_rows[0][0]
    print(f"   Conectado (versión {version})")

    # Verificar que la semana no exista
    print(f"\n🔍 Verificando semana {semana}...")
    if verificar_semana_existe(client, semana):
        print(f"❌ Error: La semana {semana} ya tiene datos en fact_eventos.")
        print(f"   Ejecute primero un reset o elija otra semana.")
        client.close()
        sys.exit(1)
    print(f"   ✅ Semana {semana} disponible")

    # Generar datos
    print(f"\n🎲 Generando {TOTAL_REGISTROS:,} registros sintéticos...")
    df = generar_datos(semana)
    print(f"   ✅ Datos generados")
    print(f"   Canales: {df['channel'].value_counts().to_dict()}")
    print(f"   Conversiones: {df['is_conversion'].sum():,}")
    print(f"   Abandonos: {df['drop_off_flag'].sum():,}")
    print(f"   Usuarios únicos: {df['user_id'].nunique():,}")
    print(f"   Sesiones únicas: {df['session_id'].nunique():,}")

    # Insertar en lotes
    print(f"\n📦 Insertando en retailmind.fact_eventos (lotes de {BATCH_SIZE:,})...")
    total_insertados = 0
    columns = [
        "session_id", "user_id", "timestamp_utc", "event_index",
        "user_action", "product_id", "time_spent_sec", "session_length",
        "interaction_count", "is_conversion", "drop_off_flag", "price",
        "channel", "semana"
    ]

    for i in range(0, len(df), BATCH_SIZE):
        batch = df.iloc[i:i + BATCH_SIZE]
        rows = batch[columns].values.tolist()
        client.insert(f"{DB}.fact_eventos", rows, column_names=columns)
        total_insertados += len(rows)
        print(f"   Lote {i // BATCH_SIZE + 1}: {total_insertados:,}/{TOTAL_REGISTROS:,}")

    # Resumen
    elapsed = time.time() - inicio
    print(f"\n{'=' * 60}")
    print(f"  GENERACIÓN COMPLETADA")
    print(f"{'=' * 60}")
    print(f"  📅 Semana: {semana}")
    print(f"  📦 Registros insertados: {total_insertados:,}")
    print(f"  ⏱️  Tiempo total: {elapsed:.1f} segundos")
    print(f"{'=' * 60}")

    client.close()


if __name__ == "__main__":
    main()
