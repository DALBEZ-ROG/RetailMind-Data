import os
import logging
from logging.handlers import RotatingFileHandler
import psycopg2
from dotenv import load_dotenv

# Carga las variables del archivo .env
load_dotenv()

# ── Configuracion de logging ─────────────────────────────────────────────────

LOG_DIR = os.path.join(os.path.dirname(__file__), "..", "logs")
os.makedirs(LOG_DIR, exist_ok=True)
LOG_FILE = os.path.join(LOG_DIR, "etl.log")

# Crear logger raiz para el proyecto
logger = logging.getLogger("retailmind")
logger.setLevel(logging.INFO)

# Handler de archivo con rotacion (5MB max, 5 backups)
if not logger.handlers:
    file_handler = RotatingFileHandler(
        LOG_FILE,
        maxBytes=5 * 1024 * 1024,
        backupCount=5,
        encoding="utf-8"
    )
    file_handler.setLevel(logging.INFO)
    file_handler.setFormatter(logging.Formatter(
        "[%(asctime)s] [%(levelname)s] %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S"
    ))
    logger.addHandler(file_handler)

    # Handler de consola
    console_handler = logging.StreamHandler()
    console_handler.setLevel(logging.INFO)
    console_handler.setFormatter(logging.Formatter(
        "[%(levelname)s] %(message)s"
    ))
    logger.addHandler(console_handler)


def get_connection():
    """
    Retorna una conexion activa a PostgreSQL usando las variables de entorno.
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
        logger.error(f"No se pudo conectar a PostgreSQL: {e}")
        raise ConnectionError(
            f"[ERROR] No se pudo conectar a PostgreSQL.\n"
            f"  Host: {os.getenv('DB_HOST')} | Puerto: {os.getenv('DB_PORT')} | BD: {os.getenv('DB_NAME')}\n"
            f"  Detalle: {e}"
        )
