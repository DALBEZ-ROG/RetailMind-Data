"""
PASO 4 — Mide las cinco consultas en los dos motores y contrasta sus resultados.

METODOLOGIA (lo que hace defendible la comparacion)

  * ARNES SIMETRICO. Los dos motores se miden desde el MISMO proceso Python,
    con `perf_counter`, sobre una conexion TCP a `localhost`, y se materializa
    el resultado completo en las dos. No se usa el reloj interno de cada motor,
    que mide cosas distintas. Lo que se cronometra es lo que veria la
    aplicacion: enviar, ejecutar y recibir.
  * CACHE. La PRIMERA ejecucion de cada consulta se descarta (cache fria) y se
    miden REPETICIONES = 7 mas. Se reporta minimo, mediana y maximo. La cache de
    resultados de ClickHouse se APAGA explicitamente (`use_query_cache = 0`):
    con ella encendida la segunda repeticion devolveria la respuesta guardada y
    la medicion no valdria nada. PostgreSQL no tiene cache de resultados.
  * POSTGRESQL EN DOS CONFIGURACIONES. Una con los valores por DEFECTO —los que
    el sistema tiene hoy— y otra AFINADA (mas `work_mem`, hasta 8 procesos
    paralelos). Se reportan las dos: la primera es la verdad del despliegue, la
    segunda cierra la objecion de que se midio un PostgreSQL mal configurado.
  * MISMO RESULTADO. Antes de dar por buena una medicion se contrastan las dos
    respuestas celda por celda. Una consulta mas rapida que devuelve otro numero
    no mide nada.

    py -3 retailmind/benchmark/04_medir.py
    py -3 retailmind/benchmark/04_medir.py --reps 15
"""

from __future__ import annotations

import base64
import http.client
import json
import socket
import statistics
import sys
import time
from decimal import Decimal, InvalidOperation
from pathlib import Path

from comun import BASE_BENCH, RAIZ, log, pg
from consultas import CONSULTAS

REPS = 7
SALIDA = Path(__file__).parent / "resultados.json"

# Ajustes de sesion de PostgreSQL en la configuracion AFINADA. Todos son de
# SESION: no se toca `postgresql.conf` ni se ejecuta ALTER SYSTEM, porque este
# cluster tambien sirve la base operativa.
AFINADO = [
    "SET work_mem = '256MB'",
    "SET max_parallel_workers_per_gather = 8",
    "SET parallel_setup_cost = 0",
    "SET parallel_tuple_cost = 0",
    "SET min_parallel_table_scan_size = 0",
    "SET min_parallel_index_scan_size = 0",
]


# ─────────────────────────────────────────────────────────────────────
# Cliente ClickHouse por HTTP (mismo tipo de transporte que psycopg2: TCP local)
# ─────────────────────────────────────────────────────────────────────

def _clave_ch() -> str:
    for linea in (RAIZ / ".env").read_text(encoding="utf-8").splitlines():
        if linea.startswith("CH_PASSWORD="):
            return linea.split("=", 1)[1].strip()
    raise SystemExit("CH_PASSWORD no esta en .env")


_AUTH = None
_CONN = None

# `use_query_cache=0`: sin esto la 2.a repeticion seria una lectura de cache y
# la medicion no valdria nada.
_RUTA = ("/?use_query_cache=0&output_format_decimal_trailing_zeros=1"
         "&default_format=TabSeparated")


def _conexion_ch() -> http.client.HTTPConnection:
    """Conexion HTTP PERSISTENTE.

    Con `urllib` se abre un TCP nuevo por consulta mientras psycopg2 reutiliza
    uno solo. Esa asimetria cargaba a ClickHouse el coste de establecer la
    conexion en CADA repeticion —y en la escala pequena eso era la mayor parte
    del tiempo medido—, o sea favorecia a PostgreSQL. Se corrige aqui: los dos
    motores se miden sobre una conexion ya abierta.
    """
    global _CONN, _AUTH
    if _AUTH is None:
        _AUTH = base64.b64encode(f"default:{_clave_ch()}".encode()).decode()
    if _CONN is None:
        _CONN = http.client.HTTPConnection("127.0.0.1", 8123, timeout=600)
        _CONN.connect()
        # TCP_NODELAY. `http.client` no lo activa; libpq (psycopg2) SI. Sin el,
        # el algoritmo de Nagle espera el ACK diferido del otro extremo y CADA
        # ida y vuelta a ClickHouse costaba ~43 ms fijos —medido— frente a
        # 0,31 ms de PostgreSQL. Eso no es el motor: es el socket, y estaba
        # cargandoselo entero a ClickHouse.
        _CONN.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    return _CONN


ULTIMO_SERVIDOR_MS = 0.0        # elapsed que declara el propio ClickHouse


def ch_http(sql: str) -> list[list[str | None]]:
    global _CONN, ULTIMO_SERVIDOR_MS
    c = _conexion_ch()
    cuerpo = sql.encode("utf-8")
    cab = {"Authorization": f"Basic {_AUTH}", "Content-Length": str(len(cuerpo)),
           "Connection": "keep-alive"}
    try:
        c.request("POST", _RUTA, body=cuerpo, headers=cab)
        r = c.getresponse()
        crudo = r.read().decode("utf-8")
    except (http.client.HTTPException, OSError):
        _CONN = None                       # se reabre y se reintenta una vez
        c = _conexion_ch()
        c.request("POST", _RUTA, body=cuerpo, headers=cab)
        r = c.getresponse()
        crudo = r.read().decode("utf-8")
    if r.status != 200:
        raise RuntimeError(crudo[:500])
    resumen_srv = r.getheader("X-ClickHouse-Summary")
    # ClickHouse manda los numeros de la cabecera como CADENAS.
    ULTIMO_SERVIDOR_MS = (float(json.loads(resumen_srv).get("elapsed_ns", 0)) / 1e6
                          if resumen_srv else 0.0)
    filas = []
    for linea in crudo.split("\n"):
        if linea == "":
            continue
        filas.append([None if c == "\\N" else c for c in linea.split("\t")])
    return filas


# ─────────────────────────────────────────────────────────────────────
# Comparacion de resultados: valor a valor, no cadena a cadena
# ─────────────────────────────────────────────────────────────────────

def _canon(v):
    if v is None:
        return None
    if isinstance(v, bool):
        return Decimal(int(v))
    if isinstance(v, (int, Decimal)):
        return Decimal(v).normalize()
    if isinstance(v, float):
        return Decimal(repr(v)).normalize()
    s = str(v)
    try:
        return Decimal(s).normalize()
    except InvalidOperation:
        return s


def comparar(fpg, fch) -> tuple[bool, str, str]:
    """Devuelve (iguales, modo, detalle_de_la_primera_diferencia)."""
    if len(fpg) != len(fch):
        return False, "-", f"filas: PG={len(fpg)} CH={len(fch)}"
    modo = "exacto"
    for i, (a, b) in enumerate(zip(fpg, fch)):
        if len(a) != len(b):
            return False, "-", f"fila {i}: columnas PG={len(a)} CH={len(b)}"
        for j, (x, y) in enumerate(zip(a, b)):
            cx, cy = _canon(x), _canon(y)
            if cx == cy:
                continue
            # Unica tolerancia admitida y declarada: las columnas DERIVADAS que
            # ClickHouse calcula en Float64 (`round(a/b, n)`) y PostgreSQL en
            # `numeric`. Solo aplica a numeros, y a la 6.a cifra decimal.
            if isinstance(cx, Decimal) and isinstance(cy, Decimal):
                if round(float(cx), 6) == round(float(cy), 6):
                    modo = "exacto salvo redondeo a 1e-6"
                    continue
            return False, modo, f"fila {i} col {j}: PG={x!r} CH={y!r}"
    return True, modo, ""


# ─────────────────────────────────────────────────────────────────────
# Medicion
# ─────────────────────────────────────────────────────────────────────

def medir_pg(conn, sql: str, reps: int):
    with conn.cursor() as cur:
        cur.execute(sql)          # calentamiento: SE DESCARTA
        filas = cur.fetchall()
        tiempos = []
        for _ in range(reps):
            t0 = time.perf_counter()
            cur.execute(sql)
            cur.fetchall()
            tiempos.append((time.perf_counter() - t0) * 1000)
    return tiempos, [list(f) for f in filas]


def medir_ch(sql: str, reps: int):
    filas = ch_http(sql)          # calentamiento: SE DESCARTA
    tiempos, servidor = [], []
    for _ in range(reps):
        t0 = time.perf_counter()
        ch_http(sql)
        tiempos.append((time.perf_counter() - t0) * 1000)
        servidor.append(ULTIMO_SERVIDOR_MS)
    return tiempos, filas, servidor


def tiempo_servidor_pg(plan: str) -> float:
    """`Execution Time` que declara el propio PostgreSQL en EXPLAIN ANALYZE."""
    for linea in plan.split("\n"):
        if linea.startswith("Execution Time:"):
            return float(linea.split(":")[1].strip().split(" ")[0])
    return 0.0


def resumen(t: list[float]) -> dict:
    return dict(min=min(t), mediana=statistics.median(t), max=max(t),
                media=statistics.fmean(t), n=len(t))


def plan_pg(conn, sql: str) -> str:
    with conn.cursor() as cur:
        cur.execute("EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, TIMING OFF, FORMAT TEXT) " + sql)
        return "\n".join(r[0] for r in cur.fetchall())


def piso_transporte(conn, reps: int) -> dict:
    """Coste de una ida y vuelta VACIA (`SELECT 1`) en cada motor.

    No es un adorno: en la escala pequena las consultas duran pocos
    milisegundos, y sin esta referencia no se puede saber que parte del tiempo
    es trabajo del motor y que parte es el protocolo. Se reporta y NO se resta
    de las mediciones — restarlo seria fabricar un numero que nadie mide.
    """
    t_pg, t_ch = [], []
    with conn.cursor() as cur:
        cur.execute("SELECT 1")
        cur.fetchall()
        for _ in range(reps):
            t0 = time.perf_counter()
            cur.execute("SELECT 1")
            cur.fetchall()
            t_pg.append((time.perf_counter() - t0) * 1000)
    ch_http("SELECT 1")
    for _ in range(reps):
        t0 = time.perf_counter()
        ch_http("SELECT 1")
        t_ch.append((time.perf_counter() - t0) * 1000)
    return dict(postgresql=resumen(t_pg), clickhouse=resumen(t_ch))


def main() -> int:
    reps = REPS
    if "--reps" in sys.argv:
        reps = int(sys.argv[sys.argv.index("--reps") + 1])

    base = pg(BASE_BENCH)
    afin = pg(BASE_BENCH)
    with afin.cursor() as cur:
        for s in AFINADO:
            cur.execute(s)

    piso = piso_transporte(base, reps)
    log("== Piso de transporte (`SELECT 1`, ida y vuelta vacia) ==")
    log(f"   PostgreSQL  min {piso['postgresql']['min']:.2f} ms   "
        f"med {piso['postgresql']['mediana']:.2f} ms")
    log(f"   ClickHouse  min {piso['clickhouse']['min']:.2f} ms   "
        f"med {piso['clickhouse']['mediana']:.2f} ms\n")

    log(f"== Medicion: 1 calentamiento descartado + {reps} repeticiones ==\n")
    salida = []
    for c in CONSULTAS:
        log(f"-- {c['id']}  {c['titulo']}")
        log(f"   {c['detalle']}")

        t_def, filas_def = medir_pg(base, c["pg"], reps)
        t_afi, filas_afi = medir_pg(afin, c["pg"], reps)
        t_ch, filas_ch, srv_ch = medir_ch(c["ch"], reps)

        ok, modo, det = comparar(filas_def, filas_ch)
        ok2, _, det2 = comparar(filas_def, filas_afi)

        r_def, r_afi, r_ch = resumen(t_def), resumen(t_afi), resumen(t_ch)
        rel_def = r_def["mediana"] / r_ch["mediana"]
        rel_afi = r_afi["mediana"] / r_ch["mediana"]

        log(f"   filas devueltas: {len(filas_ch)}    "
            f"resultado identico: {'SI' if ok and ok2 else 'NO'}  ({modo})"
            + (f"  -> {det or det2}" if not (ok and ok2) else ""))
        log(f"   PostgreSQL defecto : min {r_def['min']:8.1f}  med {r_def['mediana']:8.1f}"
            f"  max {r_def['max']:8.1f} ms")
        log(f"   PostgreSQL afinado : min {r_afi['min']:8.1f}  med {r_afi['mediana']:8.1f}"
            f"  max {r_afi['max']:8.1f} ms")
        log(f"   ClickHouse         : min {r_ch['min']:8.1f}  med {r_ch['mediana']:8.1f}"
            f"  max {r_ch['max']:8.1f} ms")
        log(f"   relacion (mediana PG / mediana CH): defecto {rel_def:6.2f}x   "
            f"afinado {rel_afi:6.2f}x"
            f"   -> gana {'ClickHouse' if min(rel_def, rel_afi) > 1 else 'PostgreSQL'}")

        p_def, p_afi = plan_pg(base, c["pg"]), plan_pg(afin, c["pg"])
        srv = dict(clickhouse=statistics.median(srv_ch),
                   pg_defecto=tiempo_servidor_pg(p_def),
                   pg_afinado=tiempo_servidor_pg(p_afi))
        log(f"   lado servidor (mediana CH / EXPLAIN ANALYZE PG): "
            f"CH {srv['clickhouse']:.1f} ms · PG def {srv['pg_defecto']:.1f} ms · "
            f"PG afi {srv['pg_afinado']:.1f} ms\n")

        salida.append(dict(
            id=c["id"], escala=c["escala"], filas=c["filas"], titulo=c["titulo"],
            detalle=c["detalle"], filas_resultado=len(filas_ch),
            identico=bool(ok and ok2), modo_comparacion=modo, diferencia=det or det2,
            pg_defecto=r_def, pg_afinado=r_afi, clickhouse=r_ch,
            relacion_defecto=rel_def, relacion_afinado=rel_afi,
            lado_servidor=srv, plan_pg_defecto=p_def, plan_pg_afinado=p_afi,
        ))

    SALIDA.write_text(
        json.dumps(dict(reps=reps, piso_transporte=piso, consultas=salida),
                   indent=2, ensure_ascii=False), encoding="utf-8")
    log(f"Resultados crudos -> {SALIDA}")
    return 0 if all(s["identico"] for s in salida) else 1


if __name__ == "__main__":
    raise SystemExit(main())
