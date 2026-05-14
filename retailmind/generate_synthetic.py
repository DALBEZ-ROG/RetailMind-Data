import pandas as pd
import random
import uuid
from datetime import datetime, timedelta

CATEGORIES  = ['Beauty', 'Accessories', 'Groceries', 'Electronics', 'Sports', 'Apparel', 'Home', 'Shoes']
BRANDS      = ['FreshFarm', 'Wayfair', 'Spalding', 'Mainstays', 'Coach', 'Decathlon', 'Fitbit', 'Dove',
                'H&M', 'Neutrogena', 'NatureBest', 'Target', 'Nike', 'Apple', 'Maybelline', 'New Balance',
                'DailyFresh', 'HomeGoods', 'Fossil', 'Puma', 'Ikea', 'Olay', 'OrganicCo', 'Sony',
                'Wilson', 'Adidas', 'Samsung', 'GreatValue', 'Ray-Ban', 'Under Armour', 'Loreal', 'Asics', 'Bose']
CHANNELS        = ['mobile', 'app', 'web']
DEVICE_TYPES    = ['ios', 'desktop', 'android', 'tablet']
REGIONS         = ['BR', 'AU', 'CA', 'IN', 'UK', 'US', 'FR', 'JP', 'DE']
TRAFFIC_SOURCES = ['email', 'paid_search', 'organic', 'referral', 'affiliate', 'social', 'direct']
USER_ACTIONS    = ['click', 'drop', 'add_to_cart', 'wishlist', 'view', 'purchase']

PRICE_RANGES = {
    'Electronics': (50, 1500), 'Beauty': (5, 120),   'Accessories': (10, 300),
    'Groceries':   (1, 50),    'Sports': (15, 500),   'Apparel': (10, 200),
    'Home':        (20, 800),  'Shoes':  (20, 350),
}

NUM_RECORDS = 100000
random.seed(48)
BASE_DATE = datetime(2024, 3, 8)

print(f"Generando {NUM_RECORDS:,} registros sintéticos...")

rows = []
for i in range(NUM_RECORDS):
    category    = random.choice(CATEGORIES)
    price_min, price_max = PRICE_RANGES[category]
    is_purchase = random.random() < 0.18
    user_action = 'purchase' if is_purchase else random.choice([a for a in USER_ACTIONS if a != 'purchase'])
    is_conv  = is_purchase
    drop_off = not is_purchase and random.random() < 0.45

    session_ts = BASE_DATE + timedelta(
        days=random.randint(0, 6),
        hours=random.randint(0, 23),
        minutes=random.randint(0, 59),
        seconds=random.randint(0, 59)
    )

    rows.append({
        'session_id':        f"S2-{uuid.uuid4().hex[:12].upper()}",
        'user_id':           f"U-{random.randint(10000, 99999)}",
        'timestamp_utc':     session_ts.strftime('%Y-%m-%d %H:%M:%S'),
        'event_index':       random.randint(1, 20),
        'user_action':       user_action,
        'product_id':        f"P-{random.randint(1000, 9999)}",
        'category':          category,
        'brand':             random.choice(BRANDS),
        'price':             round(random.uniform(price_min, price_max), 2),
        'channel':           random.choice(CHANNELS),
        'device_type':       random.choice(DEVICE_TYPES),
        'region':            random.choice(REGIONS),
        'traffic_source':    random.choice(TRAFFIC_SOURCES),
        'time_spent_sec':    round(random.uniform(5, 600), 1),
        'session_length':    round(random.uniform(30, 3600), 1),
        'interaction_count': random.randint(1, 50),
        'is_conversion':     str(is_conv).lower(),
        'drop_off_flag':     str(drop_off).lower(),
    })

    if (i + 1) % 10000 == 0:
        print(f"  {i+1:,} / {NUM_RECORDS:,} registros generados...")

import pandas as pd
df = pd.DataFrame(rows)
df.to_csv('semana_08_synthetic.csv', index=False, sep=',')

print(f"\n✅ CSV generado: semana_08_synthetic.csv")
print(f"   Registros: {len(df):,}")
print(f"   Columnas:  {list(df.columns)}")