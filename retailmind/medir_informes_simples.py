# -*- coding: utf-8 -*-
"""V5 — Cronómetro de los 30 informes SIMPLES (los que sirve PostgreSQL).

La referencia es `docs/RENDIMIENTO_INFORMES_SIMPLES.md`: la suma bajó de
767.443 ms a 157.716 ms en la sesión del `PARALLEL SAFE`. Este guion vuelve a
medir exactamente los mismos 30 para comprobar que la suma MEJORA o se mantiene.

Cada informe se mide DOS veces y se queda la segunda: la primera de todas paga
el arranque en frío del contenedor y falsearía la comparación.

    set RETAILMIND_ADMIN_PASS=...
    py -3 retailmind/medir_informes_simples.py
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

BASE = os.environ.get("RETAILMIND_API", "http://localhost:8080")
CLAVE = os.environ.get("RETAILMIND_ADMIN_PASS")
if not CLAVE:
    print("Falta RETAILMIND_ADMIN_PASS.")
    sys.exit(2)

# Los 30 endpoints SIMPLES, tal cual los declaran los seis controladores no
# compuestos. El orden es el del documento de referencia.
INFORMES = [
    "ventas/cartera-pedidos", "ventas/por-vendedor", "ventas/carritos-abandonados",
    "ventas/moderacion", "ventas/avance-meta", "ventas/participacion-canal",
    "compras/ordenes", "compras/cuentas-por-pagar", "compras/defectuosos",
    "compras/catalogo-proveedor", "compras/entregas-incompletas",
    "inventario/bajo-minimo", "inventario/stock-bodega", "inventario/kardex",
    "inventario/ajustes", "inventario/transferencias", "inventario/valor-inventario",
    "inventario/sobre-stock",
    "logistica/cola-despacho", "logistica/envios", "logistica/devoluciones",
    "logistica/costo-envio",
    "soporte/bandeja", "soporte/por-categoria", "soporte/por-agente",
    "gerencia/foto-dia", "gerencia/cupones", "gerencia/marketing",
    "gerencia/auditoria", "gerencia/accesos",
]

# Referencia de la sesión anterior (ms). Los 15 no listados iban todos < 1 s.
REFERENCIA = {
    "logistica/envios": 22287, "gerencia/foto-dia": 33599, "inventario/kardex": 25348,
    "logistica/costo-envio": 17001, "ventas/cartera-pedidos": 9082,
    "ventas/participacion-canal": 6803, "ventas/por-vendedor": 6764,
    "compras/entregas-incompletas": 8737, "logistica/devoluciones": 3932,
    "compras/ordenes": 1319, "logistica/cola-despacho": 13597,
    "compras/cuentas-por-pagar": 2764, "soporte/bandeja": 2000,
    "soporte/por-agente": 1114, "soporte/por-categoria": 1178,
}
SUMA_REFERENCIA = 157716


def login():
    req = urllib.request.Request(
        BASE + "/api/auth/login",
        data=json.dumps({"username": "admin@retailmind.com", "password": CLAVE}).encode(),
        headers={"Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(req, timeout=300))["token"]


def medir(tok, ruta):
    req = urllib.request.Request(BASE + "/api/informes/" + ruta,
                                 headers={"Authorization": "Bearer " + tok})
    t0 = time.time()
    try:
        urllib.request.urlopen(req, timeout=1800).read()
        return int((time.time() - t0) * 1000), None
    except urllib.error.HTTPError as e:
        return int((time.time() - t0) * 1000), e.code


def main():
    tok = login()
    print("%-36s %10s %10s %10s" % ("informe", "sesión ant.", "ahora", "cambio"))
    print("-" * 70)
    suma = 0
    errores = []
    for ruta in INFORMES:
        medir(tok, ruta)                      # calentamiento, se descarta
        ms, cod = medir(tok, ruta)
        if cod:
            errores.append("%s → HTTP %s" % (ruta, cod))
        suma += ms
        ref = REFERENCIA.get(ruta)
        cambio = "" if ref is None else ("%+.0f%%" % ((ms - ref) * 100.0 / ref))
        print("%-36s %10s %10d %10s"
              % (ruta, ref if ref is not None else "<1000", ms, cambio))
    print("-" * 70)
    print("%-36s %10d %10d %9.2fx"
          % ("SUMA DE LOS 30", SUMA_REFERENCIA, suma,
             SUMA_REFERENCIA / float(suma) if suma else 0))
    if errores:
        print("\nERRORES: " + "; ".join(errores))
    print("\nVEREDICTO V5: %s"
          % ("la suma MEJORA" if suma <= SUMA_REFERENCIA else "la suma EMPEORA"))
    sys.exit(0 if suma <= SUMA_REFERENCIA and not errores else 1)


if __name__ == "__main__":
    main()
