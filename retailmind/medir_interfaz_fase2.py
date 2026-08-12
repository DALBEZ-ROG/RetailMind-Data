"""
medir_interfaz_fase2.py — V10 de la Fase 2: ¿la interfaz aguanta el volumen?

Nació en la Fase 2 para responder si la interfaz aguantaba el salto de volumen,
y sigue sirviendo en cada fase posterior: no basta con que los elementos
devuelvan 200, hay que saber CUÁNTO TARDAN. Por eso no fija el tamaño del
sistema en el texto —decía «314.083 pedidos» y hoy son 3,00 M—: el guion mide
lo que haya cuando se ejecuta.

Mide los 7 tableros de dirección y 6 informes compuestos, tres veces cada uno,
y reporta la mediana (no la media: un pico de arranque de la JVM no debe
contaminar la cifra). Un tablero es UNA petición que trae todos sus bloques,
así que el tiempo medido es el de la pantalla completa.

Las credenciales se piden POR ENTORNO y sin valor por defecto, para no engordar
la deuda C-4:

    export RETAILMIND_ADMIN_PASS='...'
    py -3 retailmind/medir_interfaz_fase2.py
"""
from __future__ import annotations

import json
import os
import statistics
import sys
import time
import urllib.error
import urllib.request

BASE = os.environ.get("RETAILMIND_API", "http://localhost:8080")
USUARIO = os.environ.get("RETAILMIND_ADMIN_USER", "admin@retailmind.com")
CLAVE = os.environ.get("RETAILMIND_ADMIN_PASS")

TABLEROS = [
    ("T-1 Omnicanal",            "/api/tableros/omnicanal"),
    ("T-2 Rentabilidad",         "/api/tableros/rentabilidad"),
    ("T-3 Cliente y posventa",   "/api/tableros/cliente-posventa"),
    ("T-4 Operacion",            "/api/tableros/operacion"),
    ("T-5 Costo operacion",      "/api/tableros/costo-operacion"),
    ("T-6 Abastecimiento",       "/api/tableros/abastecimiento"),
    ("T-7 Gobierno del dato",    "/api/tableros/gobierno-dato"),
]

INFORMES = [
    ("VEN-06 evolucion mensual", "/api/informes/ventas/evolucion-mensual"),
    ("VEN-05 clientes",          "/api/informes/ventas/clientes"),
    ("VEN-03 top productos",     "/api/informes/ventas/top-productos"),
    ("INV-04 rotacion",          "/api/informes/inventario/rotacion"),
    ("LOG-12 tiempos de ciclo",  "/api/informes/logistica/tiempos-ciclo"),
    ("GER-02 balanza",           "/api/informes/gerencia/balanza"),
]

REPETICIONES = 3


def pedir(url: str, token: str | None = None, cuerpo: dict | None = None):
    datos = json.dumps(cuerpo).encode() if cuerpo is not None else None
    req = urllib.request.Request(url, data=datos, method="POST" if datos else "GET")
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    t0 = time.perf_counter()
    with urllib.request.urlopen(req, timeout=180) as r:
        crudo = r.read()
        ms = (time.perf_counter() - t0) * 1000
        return r.status, json.loads(crudo), ms, len(crudo)


def main() -> int:
    if not CLAVE:
        print("Falta RETAILMIND_ADMIN_PASS en el entorno.", file=sys.stderr)
        return 2

    try:
        _, sesion, ms, _ = pedir(f"{BASE}/api/auth/login", cuerpo={
            "username": USUARIO, "password": CLAVE})
    except urllib.error.URLError as e:
        print(f"No se pudo entrar en {BASE}: {e}", file=sys.stderr)
        return 2
    token = sesion.get("token") or sesion.get("accessToken")
    if not token:
        print(f"El login no devolvio token: {list(sesion)}", file=sys.stderr)
        return 2
    print(f"Login OK en {ms:.0f} ms\n")

    fallos = 0
    for titulo, grupo in (("TABLEROS DE DIRECCION", TABLEROS), ("INFORMES COMPUESTOS", INFORMES)):
        print(f"{titulo}")
        print(f"  {'elemento':<28} {'mediana':>9} {'min':>8} {'max':>8} {'KB':>7}  estado")
        for nombre, ruta in grupo:
            tiempos, estado, tam, degradado = [], None, 0, None
            for _ in range(REPETICIONES):
                try:
                    estado, cuerpo, ms, tam = pedir(f"{BASE}{ruta}", token)
                    tiempos.append(ms)
                    if isinstance(cuerpo, dict):
                        degradado = cuerpo.get("analiticaDisponible")
                except urllib.error.HTTPError as e:
                    estado = e.code
                    break
                except Exception as e:                      # noqa: BLE001
                    estado = f"ERR {e}"
                    break
            if tiempos and estado == 200:
                marca = "OK" if degradado is not False else "OK (analitica caida)"
                print(f"  {nombre:<28} {statistics.median(tiempos):>8.0f}ms "
                      f"{min(tiempos):>7.0f}ms {max(tiempos):>7.0f}ms {tam/1024:>6.1f}  {marca}")
            else:
                fallos += 1
                print(f"  {nombre:<28} {'—':>9} {'—':>8} {'—':>8} {'—':>7}  FALLO {estado}")
        print()

    print(f"RESULTADO: {fallos} fallos de {len(TABLEROS) + len(INFORMES)} elementos.")
    return 1 if fallos else 0


if __name__ == "__main__":
    sys.exit(main())
