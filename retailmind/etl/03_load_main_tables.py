"""
03_load_main_tables.py
Carga las tablas principales desde dataset_temporal:
  usuarios, productos, sesiones, eventos, conversiones
Estrategia: TRUNCATE + INSERT (proceso completo desde cero).
"""

import os
import sys

sys.path.append(os.path.join(os.path.dirname(__file__), ".."))

from config.db_connection import get_connection

BATCH_SIZE = 1000


def _insert_batch(cur, sql, rows, table_name):
    total = 0
    for i in range(0, len(rows), BATCH_SIZE):
        batch = rows[i : i + BATCH_SIZE]
        cur.executemany(sql, batch)
        total += len(batch)
    print(f"Cargando tabla {table_name}... [{total} registros insertados] ✓")


def load_main_tables():
    print("=" * 55)
    print("PASO 3 — Cargando tablas principales")
    print("=" * 55)

    try:
        conn = get_connection()
        cur = conn.cursor()
    except Exception as e:
        print(f"[ERROR] Conexión fallida: {e}")
        sys.exit(1)

    try:
        # TRUNCATE en orden inverso de dependencias para respetar FK
        print("Truncando tablas principales...")
        cur.execute("""
            TRUNCATE TABLE conversiones, eventos, sesiones, productos, usuarios
            RESTART IDENTITY CASCADE;
        """)

        # ── Cargar lookup maps en memoria ────────────────────────
        cur.execute("SELECT region_name, region_id FROM regiones;")
        region_map = {r[0]: r[1] for r in cur.fetchall()}

        cur.execute("SELECT device_type_name, device_type_id FROM dispositivos;")
        device_map = {r[0]: r[1] for r in cur.fetchall()}

        cur.execute("SELECT channel_name, channel_id FROM canales;")
        channel_map = {r[0]: r[1] for r in cur.fetchall()}

        cur.execute("SELECT source_name, source_id FROM fuentes_trafico;")
        source_map = {r[0]: r[1] for r in cur.fetchall()}

        cur.execute("SELECT category_name, category_id FROM categorias;")
        category_map = {r[0]: r[1] for r in cur.fetchall()}

        # ── Leer dataset_temporal completo ───────────────────────
        cur.execute("""
            SELECT session_id, user_id, timestamp_utc, event_index, user_action,
                   product_id, category, brand, price, channel, device_type,
                   region, traffic_source, time_spent_sec, session_length,
                   interaction_count, is_conversion, drop_off_flag
            FROM dataset_temporal;
        """)
        rows = cur.fetchall()
        print(f"  {len(rows)} registros leídos desde dataset_temporal.")

        # ── 6. usuarios ──────────────────────────────────────────
        seen_users = {}
        for r in rows:
            uid = r[1]
            if uid and uid not in seen_users:
                seen_users[uid] = (
                    uid,
                    region_map.get(r[11]),
                    device_map.get(r[10]),
                )
        _insert_batch(
            cur,
            "INSERT INTO usuarios (user_id, region_id, device_type_id) VALUES (%s, %s, %s);",
            list(seen_users.values()),
            "usuarios",
        )

        # ── 7. productos ─────────────────────────────────────────
        seen_products = {}
        for r in rows:
            pid = r[5]
            if pid and pid not in seen_products:
                try:
                    price = float(r[8]) if r[8] else None
                except ValueError:
                    price = None
                seen_products[pid] = (
                    pid,
                    category_map.get(r[6]),
                    r[7],   # brand
                    price,
                )
        _insert_batch(
            cur,
            "INSERT INTO productos (product_id, category_id, brand, price) VALUES (%s, %s, %s, %s);",
            list(seen_products.values()),
            "productos",
        )

        # ── 8. sesiones ──────────────────────────────────────────
        seen_sessions = {}
        for r in rows:
            sid = r[0]
            if sid and sid not in seen_sessions:
                try:
                    ts = r[2] if r[2] else None
                except Exception:
                    ts = None
                try:
                    session_length = float(r[14]) if r[14] else None
                except ValueError:
                    session_length = None
                try:
                    interaction_count = int(r[15]) if r[15] else None
                except ValueError:
                    interaction_count = None
                seen_sessions[sid] = (
                    sid,
                    r[1],                    # user_id
                    ts,
                    session_length,
                    interaction_count,
                    channel_map.get(r[9]),   # channel_id
                    source_map.get(r[12]),   # source_id
                )
        _insert_batch(
            cur,
            """INSERT INTO sesiones
               (session_id, user_id, timestamp_utc, session_length, interaction_count, channel_id, source_id)
               VALUES (%s, %s, %s, %s, %s, %s, %s);""",
            list(seen_sessions.values()),
            "sesiones",
        )

        # ── 9. eventos ───────────────────────────────────────────
        eventos = []
        for r in rows:
            try:
                event_index = int(r[3]) if r[3] else None
            except ValueError:
                event_index = None
            try:
                time_spent = float(r[13]) if r[13] else None
            except ValueError:
                time_spent = None
            eventos.append((
                r[0],         # session_id
                event_index,
                r[4],         # user_action
                time_spent,
                r[5],         # product_id
            ))
        _insert_batch(
            cur,
            """INSERT INTO eventos (session_id, event_index, user_action, time_spent_sec, product_id)
               VALUES (%s, %s, %s, %s, %s);""",
            eventos,
            "eventos",
        )

        # ── 10. conversiones ─────────────────────────────────────
        def to_bool(val):
            if val is None:
                return None
            return str(val).strip().lower() in ("1", "true", "yes", "t")

        conversiones = []
        for r in rows:
            conversiones.append((
                r[0],              # session_id
                to_bool(r[16]),    # is_conversion
                to_bool(r[17]),    # drop_off_flag
                None,              # conversion_time (no disponible en staging)
            ))
        _insert_batch(
            cur,
            """INSERT INTO conversiones (session_id, is_conversion, drop_off_flag, conversion_time)
               VALUES (%s, %s, %s, %s);""",
            conversiones,
            "conversiones",
        )

        conn.commit()
        print("\nTablas principales cargadas exitosamente ✓")

    except Exception as e:
        conn.rollback()
        print(f"[ERROR] Fallo al cargar tablas principales:\n  {e}")
        sys.exit(1)
    finally:
        cur.close()
        conn.close()


if __name__ == "__main__":
    load_main_tables()
