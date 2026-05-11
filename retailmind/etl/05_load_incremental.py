"""
05_load_incremental.py
Carga incremental semanal desde dataset_temporal hacia las 10 tablas normalizadas.
Solo inserta registros nuevos (session_id como control de duplicados).
Optimizado: usa execute_values de psycopg2.extras para inserciones masivas rapidas.

Uso:
    python etl/05_load_incremental.py
    python etl/05_load_incremental.py --force
"""

import os
import sys
import time
import argparse
import logging

sys.path.append(os.path.join(os.path.dirname(__file__), ".."))

from psycopg2.extras import execute_values
from config.db_connection import get_connection
from utils.load_tracker import (
    get_semana_actual,
    semana_ya_cargada,
    registrar_carga,
)
from utils.error_reporter import report_error

logger = logging.getLogger("retailmind")

BATCH_SIZE = 5000
MAX_RETRIES = 3


# ── Helpers ──────────────────────────────────────────────────────────────────

def _insert_values(cur, sql_template, rows, page_size=5000):
    """Inserta usando execute_values (mucho mas rapido que executemany).
    sql_template debe tener formato: INSERT INTO tabla (cols) VALUES %s ON CONFLICT ...
    """
    if not rows:
        return 0
    execute_values(cur, sql_template, rows, page_size=page_size)
    return len(rows)


def _insert_with_retry(cur, conn, sql_template, rows, table_name, semana):
    """Inserta con reintentos. Si falla 3 veces, reporta error."""
    for attempt in range(1, MAX_RETRIES + 1):
        try:
            total = _insert_values(cur, sql_template, rows)
            return total
        except Exception as e:
            logger.warning(f"Intento {attempt}/{MAX_RETRIES} fallo para {table_name}: {e}")
            conn.rollback()
            if attempt == MAX_RETRIES:
                report_error(table_name, e, semana)
                raise
            time.sleep(1)
    return 0


def _print_progress(semana, tabla, nuevos, duplicados):
    msg = f"[SEMANA {semana}] {tabla}: {nuevos} nuevos / {duplicados} duplicados OK"
    print(f"  {msg}")
    logger.info(msg)


def to_bool(val):
    if val is None:
        return None
    return str(val).strip().lower() in ("1", "true", "yes", "t")


# ── Carga incremental ─────────────────────────────────────────────────────────

def load_incremental(semana):
    print("=" * 60)
    print(f"  CARGA INCREMENTAL -- SEMANA {semana}")
    print("=" * 60)
    logger.info(f"=== Inicio carga incremental semana {semana} ===")

    start_time = time.time()

    try:
        conn = get_connection()
        cur = conn.cursor()
    except Exception as e:
        logger.error(f"Conexion fallida: {e}")
        print(f"[ERROR] Conexion fallida: {e}")
        sys.exit(1)

    total_procesados = 0
    total_nuevos = 0

    try:
        # Optimizacion: desactivar synchronous_commit para esta sesion
        cur.execute("SET synchronous_commit = OFF;")
        cur.execute("SET work_mem = '256MB';")

        # Leer dataset_temporal
        cur.execute("""
            SELECT session_id, user_id, timestamp_utc, event_index, user_action,
                   product_id, category, brand, price, channel, device_type,
                   region, traffic_source, time_spent_sec, session_length,
                   interaction_count, is_conversion, drop_off_flag
            FROM dataset_temporal;
        """)
        rows = cur.fetchall()
        total_procesados = len(rows)
        print(f"  {total_procesados:,} registros leidos desde dataset_temporal.")
        logger.info(f"Leidos {total_procesados} registros de dataset_temporal")

        # ── Catalogos (upsert rapido) ────────────────────────────────────────

        regiones = list({r[11] for r in rows if r[11]})
        if regiones:
            _insert_values(cur,
                "INSERT INTO regiones (region_name, country) VALUES %s ON CONFLICT (region_name) DO NOTHING",
                [(v, None) for v in regiones])
        cur.execute("SELECT region_name, region_id FROM regiones;")
        region_map = {r[0]: r[1] for r in cur.fetchall()}

        dispositivos = list({r[10] for r in rows if r[10]})
        if dispositivos:
            _insert_values(cur,
                "INSERT INTO dispositivos (device_type_name) VALUES %s ON CONFLICT (device_type_name) DO NOTHING",
                [(v,) for v in dispositivos])
        cur.execute("SELECT device_type_name, device_type_id FROM dispositivos;")
        device_map = {r[0]: r[1] for r in cur.fetchall()}

        canales = list({r[9] for r in rows if r[9]})
        if canales:
            _insert_values(cur,
                "INSERT INTO canales (channel_name, description) VALUES %s ON CONFLICT (channel_name) DO NOTHING",
                [(v, None) for v in canales])
        cur.execute("SELECT channel_name, channel_id FROM canales;")
        channel_map = {r[0]: r[1] for r in cur.fetchall()}

        fuentes = list({r[12] for r in rows if r[12]})
        if fuentes:
            _insert_values(cur,
                "INSERT INTO fuentes_trafico (source_name, type) VALUES %s ON CONFLICT (source_name) DO NOTHING",
                [(v, None) for v in fuentes])
        cur.execute("SELECT source_name, source_id FROM fuentes_trafico;")
        source_map = {r[0]: r[1] for r in cur.fetchall()}

        categorias = list({r[6] for r in rows if r[6]})
        if categorias:
            _insert_values(cur,
                "INSERT INTO categorias (category_name, description) VALUES %s ON CONFLICT (category_name) DO NOTHING",
                [(v, None) for v in categorias])
        cur.execute("SELECT category_name, category_id FROM categorias;")
        category_map = {r[0]: r[1] for r in cur.fetchall()}

        print("  Catalogos actualizados.")

        # ── Usuarios ─────────────────────────────────────────────────────────

        seen_users = {}
        for r in rows:
            uid = r[1]
            if uid and uid not in seen_users:
                seen_users[uid] = (uid, region_map.get(r[11]), device_map.get(r[10]))

        # Contar existentes antes
        user_ids = list(seen_users.keys())
        cur.execute("SELECT COUNT(*) FROM usuarios WHERE user_id = ANY(%s);", (user_ids,))
        existing_users = cur.fetchone()[0]

        _insert_with_retry(cur, conn,
            "INSERT INTO usuarios (user_id, region_id, device_type_id) VALUES %s ON CONFLICT (user_id) DO NOTHING",
            list(seen_users.values()), "usuarios", semana)
        nuevos_u = len(seen_users) - existing_users
        total_nuevos += nuevos_u
        _print_progress(semana, "usuarios", nuevos_u, existing_users)

        # ── Productos ────────────────────────────────────────────────────────

        seen_products = {}
        for r in rows:
            pid = r[5]
            if pid and pid not in seen_products:
                try:
                    price = float(r[8]) if r[8] else None
                except (ValueError, TypeError):
                    price = None
                seen_products[pid] = (pid, category_map.get(r[6]), r[7], price)

        prod_ids = list(seen_products.keys())
        cur.execute("SELECT COUNT(*) FROM productos WHERE product_id = ANY(%s);", (prod_ids,))
        existing_prods = cur.fetchone()[0]

        _insert_with_retry(cur, conn,
            "INSERT INTO productos (product_id, category_id, brand, price) VALUES %s ON CONFLICT (product_id) DO NOTHING",
            list(seen_products.values()), "productos", semana)
        nuevos_p = len(seen_products) - existing_prods
        total_nuevos += nuevos_p
        _print_progress(semana, "productos", nuevos_p, existing_prods)

        # ── Sesiones ─────────────────────────────────────────────────────────

        seen_sessions = {}
        for r in rows:
            sid = r[0]
            if sid and sid not in seen_sessions:
                try:
                    sl = float(r[14]) if r[14] else None
                except (ValueError, TypeError):
                    sl = None
                try:
                    ic = int(r[15]) if r[15] else None
                except (ValueError, TypeError):
                    ic = None
                seen_sessions[sid] = (sid, r[1], r[2], sl, ic, channel_map.get(r[9]), source_map.get(r[12]))

        sess_ids = list(seen_sessions.keys())
        cur.execute("SELECT COUNT(*) FROM sesiones WHERE session_id = ANY(%s);", (sess_ids,))
        existing_sess = cur.fetchone()[0]

        _insert_with_retry(cur, conn,
            "INSERT INTO sesiones (session_id, user_id, timestamp_utc, session_length, interaction_count, channel_id, source_id) "
            "VALUES %s ON CONFLICT (session_id) DO NOTHING",
            list(seen_sessions.values()), "sesiones", semana)
        nuevos_s = len(seen_sessions) - existing_sess
        total_nuevos += nuevos_s
        _print_progress(semana, "sesiones", nuevos_s, existing_sess)

        # ── Eventos (bulk insert) ────────────────────────────────────────────

        eventos = []
        for r in rows:
            try:
                ei = int(r[3]) if r[3] else None
            except (ValueError, TypeError):
                ei = None
            try:
                ts = float(r[13]) if r[13] else None
            except (ValueError, TypeError):
                ts = None
            eventos.append((r[0], ei, r[4], ts, r[5]))

        _insert_with_retry(cur, conn,
            "INSERT INTO eventos (session_id, event_index, user_action, time_spent_sec, product_id) "
            "VALUES %s ON CONFLICT DO NOTHING",
            eventos, "eventos", semana)
        _print_progress(semana, "eventos", len(eventos), 0)
        total_nuevos += len(eventos)

        # ── Conversiones (bulk insert) ───────────────────────────────────────

        conversiones = []
        for r in rows:
            conversiones.append((r[0], to_bool(r[16]), to_bool(r[17]), None))

        _insert_with_retry(cur, conn,
            "INSERT INTO conversiones (session_id, is_conversion, drop_off_flag, conversion_time) "
            "VALUES %s ON CONFLICT DO NOTHING",
            conversiones, "conversiones", semana)
        _print_progress(semana, "conversiones", len(conversiones), 0)
        total_nuevos += len(conversiones)

        conn.commit()

        # Registrar en historial
        registrar_carga(semana, total_procesados, total_nuevos)

        elapsed = time.time() - start_time
        print()
        print(f"  Registros procesados : {total_procesados:,}")
        print(f"  Registros nuevos     : {total_nuevos:,}")
        print(f"  Tiempo               : {elapsed:.1f}s")
        print(f"\nCarga de semana {semana} completada exitosamente OK")
        logger.info(f"=== Fin carga semana {semana}: {total_nuevos} nuevos de {total_procesados} procesados ({elapsed:.1f}s) ===")

    except Exception as e:
        conn.rollback()
        logger.error(f"Fallo carga incremental semana {semana}: {e}", exc_info=True)
        print(f"[ERROR] Fallo durante la carga incremental:\n  {e}")
        sys.exit(1)
    finally:
        cur.close()
        conn.close()


# ── Entry point ───────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="Carga incremental semanal")
    parser.add_argument("--force", action="store_true",
                        help="Fuerza la recarga aunque la semana ya haya sido procesada")
    args = parser.parse_args()

    semana = get_semana_actual()

    if semana_ya_cargada(semana):
        if args.force:
            print(f"[ADVERTENCIA] Semana {semana} ya fue cargada. Ejecutando con --force...")
            logger.warning(f"Recarga forzada de semana {semana}")
        else:
            print(f"[DETENIDO] Semana {semana} ya fue cargada. Use --force para recargar.")
            sys.exit(0)

    load_incremental(semana)


if __name__ == "__main__":
    main()
