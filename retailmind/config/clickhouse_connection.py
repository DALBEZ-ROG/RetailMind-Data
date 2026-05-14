"""
config/clickhouse_connection.py
Módulo de conexión a ClickHouse usando clickhouse_connect.
"""
import os
from dotenv import load_dotenv
import clickhouse_connect

load_dotenv()

CH_HOST     = os.getenv("CH_HOST", "172.29.94.38")
CH_PORT     = int(os.getenv("CH_PORT", "8123"))
CH_DATABASE = os.getenv("CH_DATABASE", "retailmind")
CH_USER     = os.getenv("CH_USER", "default")
CH_PASSWORD = os.getenv("CH_PASSWORD", "retailmind2026")


def get_clickhouse_client():
    """Retorna un cliente clickhouse_connect conectado a la base de datos."""
    client = clickhouse_connect.get_client(
        host=CH_HOST,
        port=CH_PORT,
        database=CH_DATABASE,
        username=CH_USER,
        password=CH_PASSWORD
    )
    return client


def test_connection():
    """Verifica la conexión a ClickHouse e imprime la versión del servidor."""
    try:
        client = get_clickhouse_client()
        version = client.query("SELECT version()").result_rows[0][0]
        print(f"✅ Conexión exitosa a ClickHouse {CH_HOST}:{CH_PORT}/{CH_DATABASE}")
        print(f"   Versión del servidor: {version}")
        client.close()
        return True
    except Exception as e:
        print(f"❌ Error de conexión a ClickHouse: {e}")
        return False


if __name__ == "__main__":
    test_connection()
