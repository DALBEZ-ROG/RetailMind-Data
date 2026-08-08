"""
PASO 6 — V1 y V4: la prueba de que el sistema operativo no se toco.

V1 — cifras de control de la base `retailmind` (pedidos, kardex, posiciones de
     inventario, numero de tablas y stock total). Se toman ANTES y DESPUES del
     experimento y tienen que ser identicas.
V4 — los contenedores siguen `healthy` y el backend responde.

La conexion a `retailmind` es de SOLO LECTURA (`default_transaction_read_only`),
que no es una promesa: si algo intentara escribir, el motor lo rechaza.

    py -3 retailmind/benchmark/06_verificar_sistema.py --guardar antes
    py -3 retailmind/benchmark/06_verificar_sistema.py --comparar antes
"""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

from comun import log, pg_lectura

DIR = Path(__file__).parent

CIFRAS = {
    "pedidos": "SELECT count(*) FROM pedido",
    "movimientos_kardex": "SELECT count(*) FROM movimiento_inventario",
    "posiciones_inventario": "SELECT count(*) FROM inventario",
    "stock_total": "SELECT coalesce(sum(stock_actual), 0) FROM inventario",
    "tablas": ("SELECT count(*) FROM information_schema.tables "
               "WHERE table_schema = 'public' AND table_type = 'BASE TABLE'"),
    "politicas_rls": "SELECT count(*) FROM pg_policies WHERE schemaname = 'public'",
    "roles_grupo": "SELECT count(*) FROM pg_roles WHERE rolname LIKE 'grp\\_%'",
    "usuarios": "SELECT count(*) FROM usuario",
    "facturas_venta": "SELECT count(*) FROM factura_venta",
    # `pedido` no tiene columna `estado`: el estado vive en `estado_pedido`.
    "total_vendido": ("SELECT coalesce(sum(p.total), 0) FROM pedido p "
                      "JOIN estado_pedido e ON e.id = p.estado_pedido_id "
                      "WHERE e.codigo <> 'cancelado'"),
}


def tomar() -> dict:
    conn = pg_lectura("retailmind")
    r = {}
    with conn.cursor() as cur:
        for k, sql in CIFRAS.items():
            cur.execute(sql)
            r[k] = str(cur.fetchone()[0])
    conn.close()
    return r


def contenedores() -> list[tuple[str, str]]:
    out = subprocess.run(
        ["docker", "ps", "--format", "{{.Names}}\t{{.Status}}"],
        capture_output=True, text=True).stdout
    return [tuple(l.split("\t")) for l in out.strip().split("\n") if l.startswith("retailmind-")]


def salud_backend() -> str:
    r = subprocess.run(
        ["curl", "-s", "-m", "20", "http://localhost:8080/api/health"],
        capture_output=True, text=True)
    return r.stdout.strip()[:200] or "(sin respuesta)"


def main() -> int:
    if "--guardar" in sys.argv:
        etiqueta = sys.argv[sys.argv.index("--guardar") + 1]
        d = tomar()
        (DIR / f"v1_{etiqueta}.json").write_text(json.dumps(d, indent=2), encoding="utf-8")
        log(f"== V1 ({etiqueta}) — base operativa `retailmind`, solo lectura ==")
        for k, v in d.items():
            log(f"  {k:<24} {v}")
        return 0

    if "--comparar" in sys.argv:
        etiqueta = sys.argv[sys.argv.index("--comparar") + 1]
        antes = json.loads((DIR / f"v1_{etiqueta}.json").read_text(encoding="utf-8"))
        ahora = tomar()
        (DIR / "v1_despues.json").write_text(json.dumps(ahora, indent=2), encoding="utf-8")
        log("== V1 — base operativa `retailmind`: antes vs. despues ==")
        malas = 0
        for k in CIFRAS:
            ok = antes.get(k) == ahora.get(k)
            malas += 0 if ok else 1
            log(f"  {k:<24} antes={antes.get(k):<16} despues={ahora.get(k):<16} "
                f"{'IGUAL' if ok else '*** CAMBIO ***'}")
        log(f"\n  VEREDICTO V1: {'INTACTA' if malas == 0 else f'{malas} CIFRAS CAMBIARON'}")

        log("\n== V4 — contenedores y sistema ==")
        for n, s in contenedores():
            log(f"  {n:<34} {s}")
        log(f"  /api/health -> {salud_backend()}")
        return 0 if malas == 0 else 1

    raise SystemExit("usa --guardar <etiqueta> o --comparar <etiqueta>")


if __name__ == "__main__":
    raise SystemExit(main())
