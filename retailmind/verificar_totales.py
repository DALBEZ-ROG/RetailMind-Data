# -*- coding: utf-8 -*-
"""V2 — Los totales que muestran las pantallas siguen siendo CORRECTOS.

Por cada listado paginado se contrasta el `total` del sobre contra un
`count(*)` en la base, ejecutado con el MISMO rol de motor. Hay dos veredictos
posibles y ninguno admite término medio:

  * `totalEsMinimo = false` → el total tiene que ser EXACTO. Cualquier
    diferencia es un fallo.
  * `totalEsMinimo = true`  → el total es un MÍNIMO declarado. Tiene que
    cumplirse `total <= real`; si el sobre dijera más de lo que hay, estaría
    mintiendo. Se informa además cuánto se queda corto.

También comprueba que poner un FILTRO devuelve el conteo al terreno exacto:
ese es el trato del tope, y si no se cumpliera el usuario no tendría forma de
obtener la cifra verdadera.

    set RETAILMIND_ADMIN_PASS=...
    py -3 retailmind/verificar_totales.py
"""
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

BASE = os.environ.get("RETAILMIND_API", "http://localhost:8080")
RAIZ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CLAVE = os.environ.get("RETAILMIND_ADMIN_PASS")
if not CLAVE:
    print("Falta RETAILMIND_ADMIN_PASS.")
    sys.exit(2)


def login():
    req = urllib.request.Request(
        BASE + "/api/auth/login",
        data=json.dumps({"username": "admin@retailmind.com", "password": CLAVE}).encode(),
        headers={"Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(req, timeout=300))["token"]


def api(tok, ruta):
    req = urllib.request.Request(BASE + ruta, headers={"Authorization": "Bearer " + tok})
    return json.load(urllib.request.urlopen(req, timeout=1800))


def sql(consulta):
    p = subprocess.run(
        ["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "postgres",
         "-d", "retailmind", "-t", "-A", "-c", consulta],
        cwd=RAIZ, capture_output=True, text=True, encoding="utf-8", timeout=3600)
    if p.returncode != 0:
        raise RuntimeError(p.stderr)
    filas = [l for l in p.stdout.strip().splitlines() if l.strip() and l.strip() != "SET"]
    return int(filas[-1])


ROL = "SET ROLE grp_administrador; "

# (rótulo, ruta, count(*) de referencia)
CASOS = [
    ("ventas/pedidos            ", "/api/ventas/pedidos",
     ROL + "SELECT count(*) FROM pedido;"),
    ("ventas/facturas           ", "/api/ventas/facturas",
     ROL + "SELECT count(*) FROM factura_venta;"),
    ("ventas/preparacion        ", "/api/ventas/preparacion",
     ROL + "SELECT count(*) FROM pedido p JOIN estado_pedido ep ON ep.id=p.estado_pedido_id"
           " WHERE ep.codigo IN ('facturado','en_preparacion');"),
    ("devoluciones (RMA)        ", "/api/devoluciones",
     ROL + "SELECT count(*) FROM devolucion;"),
    ("compras/ordenes           ", "/api/compras/ordenes",
     ROL + "SELECT count(*) FROM orden_compra;"),
    ("compras/cuentas-por-pagar ", "/api/compras/cuentas-por-pagar",
     ROL + "SELECT count(*) FROM cuenta_por_pagar;"),
    ("compras/items-defectuosos ", "/api/compras/items-defectuosos",
     ROL + "SELECT count(*) FROM item_defectuoso;"),
    ("soporte/tickets           ", "/api/soporte/tickets",
     ROL + "SELECT count(*) FROM ticket_soporte;"),
    ("resenas                   ", "/api/resenas",
     ROL + "SELECT count(*) FROM resena;"),
    ("INF VEN-01 cartera        ", "/api/informes/ventas/cartera-pedidos",
     ROL + "SELECT count(*) FROM pedido;"),
    ("INF LOG-01 cola-despacho  ", "/api/informes/logistica/cola-despacho",
     ROL + "SELECT count(*) FROM pedido p JOIN estado_pedido ep ON ep.id=p.estado_pedido_id"
           " WHERE ep.codigo IN ('facturado','en_preparacion','preparado');"),
    ("INF SOP-01 bandeja        ", "/api/informes/soporte/bandeja",
     ROL + "SELECT count(*) FROM ticket_soporte;"),
    ("INF COM-01 ordenes        ", "/api/informes/compras/ordenes",
     ROL + "SELECT count(*) FROM orden_compra;"),
]

# El tope solo se declara; con filtro el conteo tiene que volver a ser exacto.
CON_FILTRO = [
    ("pedidos estado=preparado  ", "/api/ventas/pedidos?estado=preparado",
     ROL + "SELECT count(*) FROM pedido p JOIN estado_pedido ep ON ep.id=p.estado_pedido_id"
           " WHERE ep.codigo='preparado';"),
    ("pedidos canal=telefono    ", "/api/ventas/pedidos?canal=telefono",
     ROL + "SELECT count(*) FROM pedido WHERE canal='telefono';"),
    ("facturas buscando FV-2026 ", "/api/ventas/facturas?q=FV-2026",
     ROL + "SELECT count(*) FROM factura_venta fv JOIN pedido p ON p.id=fv.pedido_id"
           " WHERE fv.numero ILIKE '%FV-2026%' OR fv.razon_social ILIKE '%FV-2026%'"
           "    OR p.numero ILIKE '%FV-2026%';"),
    ("VEN-01 estado=cancelado   ", "/api/informes/ventas/cartera-pedidos?estado=cancelado",
     ROL + "SELECT count(*) FROM pedido p JOIN estado_pedido ep ON ep.id=p.estado_pedido_id"
           " WHERE ep.codigo='cancelado';"),
]


def revisar(tok, titulo, casos):
    print("\n%s" % titulo)
    print("%-27s %12s %6s %12s   %s" % ("listado", "total API", "min?", "count(*)", "veredicto"))
    print("-" * 82)
    fallos = 0
    for etiqueta, ruta, consulta in casos:
        d = api(tok, ruta)
        total = d.get("total")
        minimo = bool(d.get("totalEsMinimo"))
        real = sql(consulta)
        if minimo:
            ok = total <= real
            det = "MÍNIMO declarado, real %s (se queda corto en %s)" % (
                "{:,}".format(real).replace(",", "."),
                "{:,}".format(real - total).replace(",", "."))
        else:
            ok = total == real
            det = "exacto" if ok else "DIFIERE"
        if not ok:
            fallos += 1
        print("%-27s %12s %6s %12s   %s   %s"
              % (etiqueta, total, "sí" if minimo else "no", real,
                 "OK" if ok else "FALLO", det))
    return fallos


def main():
    tok = login()
    fallos = revisar(tok, "SIN FILTRO — el total es exacto o un mínimo DECLARADO", CASOS)
    fallos += revisar(tok, "CON FILTRO — el conteo tiene que volver a ser EXACTO", CON_FILTRO)

    # Ningún sobre debe declarar un mínimo cuando el conjunto cabe en el tope.
    print("\nCoherencia del tope (200.000):")
    for etiqueta, ruta, _ in CON_FILTRO:
        d = api(tok, ruta)
        malo = d.get("totalEsMinimo") and d.get("total", 0) < 200000
        print("   %-27s totalEsMinimo=%-5s total=%-9s %s"
              % (etiqueta, bool(d.get("totalEsMinimo")), d.get("total"),
                 "INCOHERENTE" if malo else "OK"))
        if malo:
            fallos += 1

    print("\n" + "=" * 82)
    print("VEREDICTO V2: %s" % ("todos los totales son correctos" if fallos == 0
                                else "%d FALLO(S)" % fallos))
    sys.exit(0 if fallos == 0 else 1)


if __name__ == "__main__":
    main()
