"""
05_load_incremental.py
Carga incremental semanal desde dataset_temporal hacia las 10 tablas normalizadas.
Solo inserta registros nuevos (session_id como control de duplicados).
Uso:
    python etl/05_load_incremental.py
    python etl/05_load_incremental.py --force
"""

import os
import sys
import argparse

sys.path.append(os.path.join(os.path.dirname(__file__), ".."))

from config.db_connection import get_connection
from utils.load_tracker import (
    get_semana_actual,
    semana_ya_cargada,
    registrar_carga,
)

BATCH_SIZE = 1000


# ── Helpers ──────────────────────────────────────────────────────────────────

def _insert_batch(cur, sql, rows):
    """Inserta en lotes de BATCH_SIZE. Retorna total de filas enviadas."""
    total = 0
    for i in range(0, len(rows), BATCH_SIZE):
        batch = rows[i: i + BATCH_SIZE]
        cur.executemany(sql, batch)
        total += len(batch)
    return total


def _count_before(cur, table: str, pk_col: str, values: list) -> int:
    """Cuenta cuantos PKs de 'values' ya existen en la tabla."""
    if not values:
        return 0
    cur.execute(
        f"SELECT COUNT(*) FROM {table} WHERE {pk_col} = ANY(%s);",
        (values,)
    )
    return cur.fetchone()[0]


def _print_progress(semana: int, tabla: str, nuevos: int, duplicados: int):
    print(f"[SEMANA {semana}] Procesando tabla {tabla}... "
          f"[{nuevos} nuevos / {duplicados} duplicados] \u2713")


def to_bool(val):
    if val is None:
        return None
    return str(val).strip().lower() in ("1", "true", "yes", "t")


# ── Carga incremental ─────────────────────────────────────────────────────────

def load_incremental(semana: int):
    print("=" * 60)
    print(f"  CARGA INCREMENTAL -- SEMANA {semana}")
    print("=" * 60)

    try:
        conn = get_connection()
        cur = conn.cursor()
    except Exception as e:
        print(f"[ERROR] Conexion fallida: {e}")
        sys.exit(1)

    total_procesados = 0
    total_nuevos = 0

    try:
        # ── Leer dataset_temporal completo ────────────────────────
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

        # ── Cargar mapas de lookup ────────────────────────────────
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

        # ── 1. Tablas de catalogo (lookup) ────────────────────────
        # regiones
        regiones = list({r[11] for r in rows if r[11]})
        _insert_batch(cur,
            "INSERT INTO regiones (region_name, country) VALUES (%s, %s) "
            "ON CONFLICT (region_name) DO NOTHING;",
            [(v, None) for v in regiones])
        # Recargar mapa tras posibles inserts nuevos
        cur.execute("SELECT region_name, region_id FROM regiones;")
        region_map = {r[0]: r[1] for r in cur.fetchall()}

        # dispositivos
        dispositivos = list({r[10] for r in rows if r[10]})
        _insert_batch(cur,
            "INSERT INTO dispositivos (device_type_name) VALUES (%s) "
            "ON CONFLICT (device_type_name) DO NOTHING;",
            [(v,) for v in dispositivos])
        cur.execute("SELECT device_type_name, device_type_id FROM dispositivos;")
        device_map = {r[0]: r[1] for r in cur.fetchall()}

        # canales
        canales = list({r[9] for r in rows if r[9]})
        _insert_batch(cur,
            "INSERT INTO canales (channel_name, description) VALUES (%s, %s) "
            "ON CONFLICT (channel_name) DO NOTHING;",
            [(v, None) for v in canales])
        cur.execute("SELECT channel_name, channel_id FROM canales;")
        channel_map = {r[0]: r[1] for r in cur.fetchall()}

        # fuentes_trafico
        fuentes = list({r[12] for r in rows if r[12]})
        _insert_batch(cur,
            "INSERT INTO fuentes_trafico (source_name, type) VALUES (%s, %s) "
            "ON CONFLICT (source_name) DO NOTHING;",
            [(v, None) for v in fuentes])
        cur.execute("SELECT source_name, source_id FROM fuentes_trafico;")
        source_map = {r[0]: r[1] for r in cur.fetchall()}

        # categorias
        categorias = list({r[6] for r in rows if r[6]})
        _insert_batch(cur,
            "INSERT INTO categorias (category_name, description) VALUES (%s, %s) "
            "ON CONFLICT (category_name) DO NOTHING;",
            [(v, None) for v in categorias])
        cur.execute("SELECT category_name, category_id FROM categorias;")
        category_map = {r[0]: r[1] for r in cur.fetchall()}

        # ── 2. usuarios ───────────────────────────────────────────
        seen_users = {}
        for r in rows:
            uid = r[1]
            if uid and uid not in seen_users:
                seen_users[uid] = (uid, region_map.get(r[11]), device_map.get(r[10]))

        user_ids = list(seen_users.keys())
        existing_users = _count_before(cur, "usuarios", "user_id", user_ids)
        _insert_batch(cur,
            "INSERT INTO usuarios (user_id, region_id, device_type_id) "
            "VALUES (%s, %s, %s) ON CONFLICT (user_id) DO NOTHING;",
            list(seen_users.values()))
        nuevos_u = len(seen_users) - existing_users
        total_nuevos += nuevos_u
        _print_progress(semana, "usuarios", nuevos_u, existing_users)

        # ── 3. productos ──────────────────────────────────────────
        seen_products = {}
        for r in rows:
            pid = r[5]
            if pid and pid not in seen_products:
                try:
                    price = float(r[8]) if r[8] else None
                except ValueError:
                    price = None
                seen_products[pid] = (pid, category_map.get(r[6]), r[7], price)

        prod_ids = list(seen_products.keys())
        existing_prods = _count_before(cur, "productos", "product_id", prod_ids)
        _insert_batch(cur,
            "INSERT INTO productos (product_id, category_id, brand, price) "
            "VALUES (%s, %s, %s, %s) ON CONFLICT (product_id) DO NOTHING;",
            list(seen_products.values()))
        nuevos_p = len(seen_products) - existing_prods
        total_nuevos += nuevos_p
        _print_progress(semana, "productos", nuevos_p, existing_prods)

        # ── 4. sesiones ───────────────────────────────────────────
        seen_sessions = {}
        for r in rows:
            sid = r[0]
            if sid and sid not in seen_sessions:
                try:
                    sl = float(r[14]) if r[14] else None
                except ValueError:
                    sl = None
                try:
                    ic = int(r[15]) if r[15] else None
                except ValueError:
                    ic = None
                seen_sessions[sid] = (
                    sid, r[1], r[2], sl, ic,
                    channel_map.get(r[9]),
                    source_map.get(r[12])
                )

        sess_ids = list(seen_sessions.keys())
        existing_sess = _count_before(cur, "sesiones", "session_id", sess_ids)
        _insert_batch(cur,
            "INSERT INTO sesiones "
            "(session_id, user_id, timestamp_utc, session_length, "
            " interaction_count, channel_id, source_id) "
            "VALUES (%s, %s, %s, %s, %s, %s, %s) "
            "ON CONFLICT (session_id) DO NOTHING;",
            list(seen_sessions.values()))
        nuevos_s = len(seen_sessions) - existing_sess
        total_nuevos += nuevos_s
        _print_progress(semana, "sesiones", nuevos_s, existing_sess)

        # ── 5. eventos ────────────────────────────────────────────
        # Para eventos usamos (session_id, event_index) como control logico.
        # Obtenemos pares ya existentes para calcular duplicados.
        cur.execute("SELECT session_id, event_index FROM eventos;")
        existing_event_keys = {(r[0], r[1]) for r in cur.fetchall()}

        eventos = []
        for r in rows:
            try:
                ei = int(r[3]) if r[3] else None
            except ValueError:
                ei = None
            try:
                ts = float(r[13]) if r[13] else None
            except ValueError:
                ts = None
            eventos.append((r[0], ei, r[4], ts, r[5]))

        nuevos_ev = sum(
            1 for e in eventos
            if (e[0], e[1]) not in existing_event_keys
        )
        duplicados_ev = len(eventos) - nuevos_ev
        _insert_batch(cur,
            "INSERT INTO eventos "
            "(session_id, event_index, user_action, time_spent_sec, product_id) "
            "VALUES (%s, %s, %s, %s, %s) "
            "ON CONFLICT DO NOTHING;",
            eventos)
        total_nuevos += nuevos_ev
        _print_progress(semana, "eventos", nuevos_ev, duplicados_ev)

        # ── 6. conversiones ───────────────────────────────────────
        # Control por session_id (una conversion por sesion)
        cur.execute("SELECT session_id FROM conversiones;")
        existing_conv_sessions = {r[0] for r in cur.fetchall()}

        conversiones = []
        for r in rows:
            conversiones.append((r[0], to_bool(r[16]), to_bool(r[17]), None))

        nuevos_c = sum(
            1 for c in conversiones
            if c[0] not in existing_conv_sessions
        )
        duplicados_c = len(conversiones) - nuevos_c
        _insert_batch(cur,
            "INSERT INTO conversiones "
            "(session_id, is_conversion, drop_off_flag, conversion_time) "
            "VALUES (%s, %s, %s, %s) "
            "ON CONFLICT DO NOTHING;",
            conversiones)
        total_nuevos += nuevos_c
        _print_progress(semana, "conversiones", nuevos_c, duplicados_c)

        conn.commit()

        # ── Registrar en historial ────────────────────────────────
        registrar_carga(semana, total_procesados, total_nuevos)

        print()
        print(f"  Registros procesados : {total_procesados:,}")
        print(f"  Registros nuevos     : {total_nuevos:,}")
        print(f"  Duplicados omitidos  : {total_procesados - total_nuevos:,}")
        print(f"\nCarga de semana {semana} completada exitosamente \u2713")

    except Exception as e:
        conn.rollback()
        print(f"[ERROR] Fallo durante la carga incremental:\n  {e}")
        sys.exit(1)
    finally:
        cur.close()
        conn.close()


# ── Entry point ───────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="Carga incremental semanal de RetailMind Analytics"
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="Fuerza la recarga aunque la semana ya haya sido procesada"
    )
    args = parser.parse_args()

    semana = get_semana_actual()

    if semana_ya_cargada(semana):
        if args.force:
            print(f"[ADVERTENCIA] Semana {semana} ya fue cargada. "
                  f"Ejecutando con --force...")
        else:
            print(f"[DETENIDO] Semana {semana} ya fue cargada. "
                  f"Use --force para recargar.")
            sys.exit(0)

    load_incremental(semana)


if __name__ == "__main__":
    main()
