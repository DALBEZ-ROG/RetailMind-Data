"""
p02_barrido.py — Barrido de TODOS los endpoints de lectura × TODOS los roles.

Cubre P02-004 (matriz de roles), P07-001 y P08-001 (los informes responden) y
sirve de red de arrastre para cualquier 500.

CRITERIO DE FALLO, y por qué es este:

  · **500 = defecto, siempre.** No hay petición de un rol autenticado a un GET
    de su propia área que justifique un error interno. Es el único veredicto
    que no admite discusión, así que es el que se persigue primero.
  · **401 con token válido = defecto.** Significa que el filtro JWT rechaza un
    token que acaba de emitir el propio sistema.
  · 403 NO es fallo por sí solo: la mitad de la matriz debe dar 403. Se
    registra para contrastarlo aparte contra `SecurityConfig`.
  · 404 sobre una ruta con `{id}` NO es fallo: el id 1 puede no existir. Sí lo
    es sobre una ruta SIN parámetros, porque significa que la ruta no está
    mapeada.

Solo se barren métodos de LECTURA (GET). Un barrido de POST/PUT/DELETE contra
la base viva escribiría datos de negocio reales, y este arnés no toca `retailmind`
en modo escritura: las pruebas de escritura viven en las suites de ciclo (P05),
que montan y desmontan su propio caso.
"""

from __future__ import annotations

import sys
import time
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from comun.arnes import Cliente, Registro, esperar_api, sesiones          # noqa: E402
from comun.catalogo import Endpoint, concretar, extraer                    # noqa: E402

#: Valores concretos para los `{parametros}` que un `1` no satisface.
MUESTRAS = {
    "departamento": "ventas",
    "informe": "cartera-pedidos",
    "tablero": "omnicanal",
    "regionNombre": "Guayas",
    "eventPk": "1",
    "tabla": "dim_producto",
    "rol": "grp_vendedor",
    "codigo": "confirmado",
    "slug": "abarrotes",
    "area": "ventas",
}

#: Rutas que se EXCLUYEN del barrido con su motivo. Cada exclusión es una
#: decisión, no un descuido, y por eso se imprime en el informe.
EXCLUIDAS = {
    "/api/etl/cargar": "dispara una carga real del ETL legado",
    "/api/dwh/actualizar": "dispara el pipeline completo (12 min)",
}


def barrer(estado_datos: str = "E3") -> Registro:
    reg = Registro(estado_datos)

    if not esperar_api():
        reg.caso("P01-004", "El API responde", condicion=False,
                 observado="/api/health no respondió tras 3 min",
                 esperado="HTTP < 500", severidad="S1")
        return reg

    clientes = sesiones()
    reg.caso("P02-001", "Los 9 roles de demo entran",
             condicion=len(clientes) == 9,
             observado=f"{len(clientes)}/9 entraron: {sorted(clientes)}",
             esperado="9/9", severidad="S1")

    for rol, c in clientes.items():
        reg.caso(f"P02-001/{rol}", f"El JWT de {rol} declara su rol",
                 condicion=(c.authority or "").upper() == rol,
                 observado=f"authority={c.authority!r}",
                 esperado=f"authority={rol!r}", severidad="S2")

    endpoints = [e for e in extraer()
                 if e.metodo == "GET" and e.ruta not in EXCLUIDAS]
    print(f"\n=== barrido: {len(endpoints)} GET × {len(clientes)} roles = "
          f"{len(endpoints)*len(clientes)} llamadas ===\n", flush=True)

    matriz: dict[str, dict[str, int]] = defaultdict(dict)
    lentas: list[tuple[str, str, int]] = []

    for e in sorted(endpoints, key=lambda x: x.ruta):
        ruta = concretar(e.ruta, MUESTRAS)
        for rol, c in clientes.items():
            t0 = time.time()
            r = c.get(ruta)
            ms = int((time.time() - t0) * 1000)
            codigo = r.status_code if r is not None else -1
            matriz[e.clave][rol] = codigo
            if ms > 3000:
                lentas.append((f"{rol} GET {ruta}", e.controlador, ms))

            if codigo == 500:
                reg.caso(f"P02-004:{e.controlador}", f"500 en GET {ruta} como {rol}",
                         condicion=False, severidad="S2",
                         observado=f"HTTP 500 · {(r.text or '')[:180]}",
                         esperado="200, 403 o 404 — nunca 500",
                         reproducir=c.curl("GET", ruta), ms=ms)
            elif codigo == 401:
                reg.caso(f"P02-006:{e.controlador}", f"401 con token válido en GET {ruta} como {rol}",
                         condicion=False, severidad="S2",
                         observado="HTTP 401 con un token recién emitido",
                         esperado="200 o 403",
                         reproducir=c.curl("GET", ruta), ms=ms)
            elif codigo == 404 and "{" not in e.ruta:
                reg.caso(f"P02-004:{e.controlador}", f"404 en ruta sin parámetros GET {ruta} ({rol})",
                         condicion=False, severidad="S3",
                         observado="HTTP 404 sobre una ruta fija",
                         esperado="la ruta debería estar mapeada",
                         reproducir=c.curl("GET", ruta), ms=ms)
            elif codigo == -1:
                reg.caso(f"P02-004:{e.controlador}", f"sin respuesta en GET {ruta} ({rol})",
                         condicion=False, severidad="S1",
                         observado="timeout o conexión caída",
                         esperado="alguna respuesta HTTP",
                         reproducir=c.curl("GET", ruta), ms=ms)

    # Nadie accesible: una ruta que da 403 a los 9 roles está muerta.
    for clave, por_rol in matriz.items():
        if all(c in (403, 401) for c in por_rol.values()):
            reg.caso("P02-008", f"Ruta inalcanzable para todo rol: {clave}",
                     condicion=False, severidad="S3",
                     observado=f"403/401 en los {len(por_rol)} roles",
                     esperado="al menos un rol debería poder usarla")

    for etiqueta, ctrl, ms in sorted(lentas, key=lambda x: -x[2])[:25]:
        reg.caso("P12-001", f"Lenta: {etiqueta}", condicion=False, severidad="S3",
                 observado=f"{ms} ms", esperado="≤ 3.000 ms", detalle=ctrl, ms=ms)

    reg.matriz = matriz          # type: ignore[attr-defined]
    return reg


if __name__ == "__main__":
    estado = sys.argv[1] if len(sys.argv) > 1 else "E3"
    reg = barrer(estado)
    print("\n" + "=" * 70)
    print(reg.resumen())
    print("informe:", reg.volcar("p02_barrido"))
    sys.exit(1 if reg.fallos else 0)
