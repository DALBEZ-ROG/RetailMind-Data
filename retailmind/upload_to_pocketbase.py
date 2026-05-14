import pandas as pd
from pocketbase import PocketBase
import os
from dotenv import load_dotenv

load_dotenv()

PB_URL   = "http://127.0.0.1:8090"
PB_EMAIL = "benitesperezdariemalberto@gmail.com"
PB_PASS  = "retailmind2026@."
CSV_PATH = "dataset_temporal_export.csv"
BATCH    = 50

client = PocketBase(PB_URL)
client.collection("_superusers").auth_with_password(PB_EMAIL, PB_PASS)
print("Conectado a Pocketbase OK")

df = pd.read_csv(CSV_PATH, sep=';')
print(f"Registros a subir: {len(df):,}")

total = 0
for i in range(0, len(df), BATCH):
    batch = df.iloc[i:i+BATCH]
    for _, row in batch.iterrows():
        client.collection("dataset_retail").create({
            "session_id":        str(row["session_id"]),
            "user_id":           str(row["user_id"]),
            "timestamp_utc":     str(row["timestamp_utc"]),
            "event_index":       str(row["event_index"]),
            "user_action":       str(row["user_action"]),
            "product_id":        str(row["product_id"]),
            "category":          str(row["category"]),
            "brand":             str(row["brand"]),
            "price":             str(row["price"]),
            "channel":           str(row["channel"]),
            "device_type":       str(row["device_type"]),
            "region":            str(row["region"]),
            "traffic_source":    str(row["traffic_source"]),
            "time_spent_sec":    str(row["time_spent_sec"]),
            "session_length":    str(row["session_length"]),
            "interaction_count": str(row["interaction_count"]),
            "is_conversion":     str(row["is_conversion"]),
            "drop_off_flag":     str(row["drop_off_flag"]),
        })
    total += len(batch)
    print(f"  Subidos: {total:,} / {len(df):,}")

print(f"Carga completa: {total:,} registros en Pocketbase")