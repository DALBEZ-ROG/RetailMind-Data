import os
import psycopg2
from dotenv import load_dotenv

# Carga las variables del archivo .env
load_dotenv()


def get_connection():
    """
    Retorna una conexión activa a PostgreSQL usando las variables de entorno.
    Lanza una excepción descriptiva si la conexión falla.
    """
    try:
        conn = psycopg2.connect(
            host=os.getenv("DB_HOST", "localhost"),
            port=os.getenv("DB_PORT", "5432"),
            dbname=os.getenv("DB_NAME"),
            user=os.getenv("DB_USER"),
            password=os.getenv("DB_PASSWORD"),
        )
        return conn
    except psycopg2.OperationalError as e:
        raise ConnectionError(
            f"[ERROR] No se pudo conectar a PostgreSQL.\n"
            f"  Host: {os.getenv('DB_HOST')} | Puerto: {os.getenv('DB_PORT')} | BD: {os.getenv('DB_NAME')}\n"
            f"  Detalle: {e}"
        )
