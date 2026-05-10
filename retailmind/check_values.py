import psycopg2
from dotenv import load_dotenv
import os

load_dotenv()
conn = psycopg2.connect(
    host=os.getenv('DB_HOST'),
    port=os.getenv('DB_PORT'),
    dbname=os.getenv('DB_NAME'),
    user=os.getenv('DB_USER'),
    password=os.getenv('DB_PASSWORD')
)
cur = conn.cursor()
for col in ['category','brand','channel','device_type','region','traffic_source','user_action']:
    cur.execute(f'SELECT DISTINCT {col} FROM dataset_temporal LIMIT 50')
    vals = [r[0] for r in cur.fetchall()]
    print(f'{col}: {vals}')
conn.close()