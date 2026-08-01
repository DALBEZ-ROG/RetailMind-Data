"""
etl/dwh/conexiones.py
Conexiones del pipeline DWH: PostgreSQL (origen) y ClickHouse (destino).

Reutiliza el PATRÓN de `config/db_connection.py` y `config/clickhouse_connection.py`
(variables de entorno vía python-dotenv + logging rotatorio), con dos diferencias
deliberadas:

  1. PostgreSQL se abre con el rol `retailmind_etl` (script 85), NO con `postgres`
     ni con `retailmind_app`. Es el único rol con BYPASSRLS y solo-lectura: sin él
     `pol_horario` devolvería CERO FILAS en silencio (§8.1 del diseño).
  2. ClickHouse apunta a la base NUEVA `retailmind_dwh`. La base `retailmind`
     legada queda congelada como archivo y este paquete JAMÁS la nombra.
"""

import logging
import os
from logging.handlers import RotatingFileHandler

import clickhouse_connect
import psycopg2
from dotenv import load_dotenv

# ── Rutas del proyecto ───────────────────────────────────────────────────────

RAIZ_ETL = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
load_dotenv(os.path.join(RAIZ_ETL, ".env"))

# ── Logging (mismo patrón que config/db_connection.py, archivo propio) ───────

LOG_DIR = os.path.join(RAIZ_ETL, "logs")
os.makedirs(LOG_DIR, exist_ok=True)
LOG_FILE = os.path.join(LOG_DIR, "etl_dwh.log")

logger = logging.getLogger("retailmind.dwh")
logger.setLevel(logging.INFO)

if not logger.handlers:
    _archivo = RotatingFileHandler(
        LOG_FILE, maxBytes=5 * 1024 * 1024, backupCount=5, encoding="utf-8"
    )
    _archivo.setFormatter(logging.Formatter(
        "[%(asctime)s] [%(levelname)s] %(message)s", datefmt="%Y-%m-%d %H:%M:%S"
    ))
    logger.addHandler(_archivo)

    _consola = logging.StreamHandler()
    _consola.setFormatter(logging.Formatter("[%(levelname)s] %(message)s"))
    logger.addHandler(_consola)

# ── Configuración: PostgreSQL (origen, SOLO LECTURA) ─────────────────────────

PG_HOST     = os.getenv("ETL_PG_HOST", "localhost")
PG_PORT     = os.getenv("ETL_PG_PORT", "5432")
PG_DATABASE = os.getenv("ETL_PG_DATABASE", "retailmind")
PG_USER     = os.getenv("ETL_PG_USER", "retailmind_etl")
PG_PASSWORD = os.getenv("ETL_PG_PASSWORD")

# ── Configuración: ClickHouse (destino) ──────────────────────────────────────

CH_HOST     = os.getenv("ETL_CH_HOST", "localhost")
CH_PORT     = int(os.getenv("ETL_CH_PORT", "8123"))
CH_DATABASE = os.getenv("ETL_CH_DATABASE", "retailmind_dwh")
CH_USER     = os.getenv("ETL_CH_USER", "default")
CH_PASSWORD = os.getenv("ETL_CH_PASSWORD", "")

# La base legada. Se nombra aquí SOLO para poder prohibirla explícitamente.
CH_BASE_LEGADA = "retailmind"

ZONA_HORARIA = "America/Guayaquil"


class BaseProhibida(RuntimeError):
    """Se intentó apuntar el pipeline a la base legada de ClickHouse."""


def _validar_destino(database: str) -> None:
    """
    Guarda de seguridad: el DWH nuevo NUNCA escribe en la base `retailmind`
    legada (§6.3 del diagnóstico). Es un error de configuración, no un aviso.
    """
    if database.strip().lower() == CH_BASE_LEGADA:
        raise BaseProhibida(
            f"ETL_CH_DATABASE apunta a '{database}', que es la base LEGADA y está "
            f"congelada como archivo. El pipeline DWH escribe en 'retailmind_dwh'."
        )


# ── Conexiones ───────────────────────────────────────────────────────────────

def get_pg_connection():
    """
    Conexión a PostgreSQL con el rol de lectura del ETL.

    El rol trae `default_transaction_read_only = on` desde el motor (script 85),
    así que esta conexión es incapaz de escribir aunque alguien se equivoque.
    """
    if not PG_PASSWORD:
        raise ConnectionError(
            "Falta ETL_PG_PASSWORD en retailmind/.env. El pipeline debe conectar "
            "como 'retailmind_etl' (script 85), no como postgres ni retailmind_app."
        )
    try:
        conn = psycopg2.connect(
            host=PG_HOST,
            port=PG_PORT,
            dbname=PG_DATABASE,
            user=PG_USER,
            password=PG_PASSWORD,
            application_name="retailmind_etl_dwh",
            connect_timeout=10,
        )
        conn.set_session(readonly=True)
        return conn
    except psycopg2.OperationalError as e:
        logger.error(f"No se pudo conectar a PostgreSQL: {e}")
        raise ConnectionError(
            f"[ERROR] No se pudo conectar a PostgreSQL.\n"
            f"  Host: {PG_HOST}:{PG_PORT} | BD: {PG_DATABASE} | Rol: {PG_USER}\n"
            f"  Detalle: {e}"
        )


def get_ch_client(database: str | None = None):
    """Cliente de ClickHouse apuntando a la base del DWH."""
    destino = database or CH_DATABASE
    _validar_destino(destino)
    return clickhouse_connect.get_client(
        host=CH_HOST,
        port=CH_PORT,
        database=destino,
        username=CH_USER,
        password=CH_PASSWORD,
        connect_timeout=10,
        send_receive_timeout=300,
    )


def get_ch_client_sin_base():
    """
    Cliente sin base fijada. Necesario para `CREATE DATABASE` la primera vez,
    cuando `retailmind_dwh` todavía no existe.
    """
    return clickhouse_connect.get_client(
        host=CH_HOST,
        port=CH_PORT,
        username=CH_USER,
        password=CH_PASSWORD,
        connect_timeout=10,
    )


# ── Verificación de orígenes (equivale al sensor `verificar_origenes` del DAG) ──

def verificar_postgres() -> bool:
    """
    Comprueba que PostgreSQL responde Y —lo importante— que el rol NO está
    siendo filtrado por RLS. Un rol sin BYPASSRLS devolvería 0 sin error.
    """
    try:
        conn = get_pg_connection()
        with conn.cursor() as cur:
            cur.execute("SELECT current_user, rolbypassrls FROM pg_roles "
                        "WHERE rolname = current_user;")
            usuario, bypassrls = cur.fetchone()
            cur.execute("SELECT count(*) FROM pedido;")
            pedidos = cur.fetchone()[0]
        conn.close()

        print(f"[OK] PostgreSQL {PG_HOST}:{PG_PORT}/{PG_DATABASE} como '{usuario}'")
        print(f"     BYPASSRLS: {bypassrls} | pedidos visibles: {pedidos:,}")

        if not bypassrls:
            print("[ERROR] El rol NO tiene BYPASSRLS: pol_horario lo dejará en cero "
                  "filas de madrugada, sin error. Ver script 85.")
            return False
        if pedidos == 0:
            print("[ERROR] Cero pedidos visibles. RLS está filtrando en silencio.")
            return False
        return True
    except Exception as e:
        print(f"[ERROR] PostgreSQL no responde: {e}")
        return False


def verificar_clickhouse() -> bool:
    """Comprueba que ClickHouse responde y que la base del DWH existe."""
    try:
        cliente = get_ch_client_sin_base()
        version = cliente.query("SELECT version()").result_rows[0][0]
        bases = [f[0] for f in cliente.query("SELECT name FROM system.databases").result_rows]
        cliente.close()

        print(f"[OK] ClickHouse {CH_HOST}:{CH_PORT} — versión {version}")
        if CH_DATABASE in bases:
            print(f"     Base '{CH_DATABASE}' presente.")
        else:
            print(f"[AVISO] La base '{CH_DATABASE}' aún no existe. "
                  f"Créala con: python -m etl.dwh.cargar --init")
        return True
    except Exception as e:
        print(f"[ERROR] ClickHouse no responde: {e}")
        return False


if __name__ == "__main__":
    ok_pg = verificar_postgres()
    ok_ch = verificar_clickhouse()
    raise SystemExit(0 if (ok_pg and ok_ch) else 1)
