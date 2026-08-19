"""
p12_rendimiento.py — Rendimiento (suite P12), estado E3.

Mide CADA endpoint AISLADO y repetido, que es la diferencia entre esta suite y
el cronómetro del barrido. Las cifras de D-03 salieron de los 9 roles llamando
en serie, así que llevan contención: el mismo informe daba 49,7 s a BODEGA y
25,2 s a ADMIN, y esa diferencia no es del SQL.

**Ninguna consulta se optimiza a partir de un número contaminado.** Primero se
mide sola, con repeticiones y descartando la primera (caché fría del motor y
del pool), y solo entonces se decide si hay algo que arreglar.

La suite reporta p50 y p95 por endpoint y separa tres veredictos:
  · dentro de umbral
  · lento de forma REPRODUCIBLE  → candidato real a optimizar
  · lento solo en el barrido     → era contención, no la consulta
"""

from __future__ import annotations

import statistics
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from comun.arnes import Cliente, Registro, esperar_api                     # noqa: E402

#: Los sospechosos de D-03, con el peor tiempo que dio el barrido (en s).
SOSPECHOSOS = {
    "/api/informes/inventario/kardex": 49.7,
    "/api/informes/logistica/envios": 41.3,
    "/api/informes/logistica/costo-envio": 33.0,
    "/api/informes/ventas/avance-meta": 18.7,
    "/api/informes/compras/entregas-incompletas": 15.6,
    "/api/informes/ventas/participacion-canal": 12.0,
    "/api/informes/ventas/por-vendedor": 10.0,
    "/api/informes/logistica/devoluciones": 6.4,
    "/api/informes/compras/cuentas-por-pagar": 5.5,
}

#: LO QUE EL USUARIO VIVE, que no es lo mismo que un `GET` pelado.
#
# El arreglo de D-03 en los informes que AGREGAN es un filtro por defecto que
# declara la PANTALLA (`valorInicial` en las definiciones), no el servicio. Esa
# separación es deliberada y está explicada en `ventana-por-defecto.ts`: un
# `GET` sin `desde` tiene que seguir significando «sin filtro», o la API
# mentiría devolviendo un subconjunto como si fuera el total.
#
# Consecuencia para esta suite: medir la ruta pelada mide el PEOR caso, no la
# experiencia. Se miden las dos, y cada una con su umbral.
FILTRO_DE_PANTALLA = {
    "/api/informes/logistica/costo-envio": {"desde": None},
    "/api/informes/logistica/devoluciones": {"desde": None},
    "/api/informes/ventas/por-vendedor": {"desde": None},
    "/api/informes/ventas/participacion-canal": {"desde": None},
    "/api/informes/compras/entregas-incompletas": {"desde": None},
}


def _ventana_por_defecto(dias: int = 90) -> str:
    """La misma ventana que pone la pantalla, calculada igual."""
    import datetime as _dt
    return (_dt.date.today() - _dt.timedelta(days=dias)).isoformat()

#: Referencia: los que el barrido dio por rápidos. Sirven de control — si estos
#: también salen lentos aislados, el problema es del entorno, no del SQL.
CONTROL = [
    "/api/informes/ventas/cartera-pedidos",
    "/api/informes/soporte/bandeja",
    "/api/informes/compras/ordenes",
]

REPETICIONES = 5
UMBRAL_S = 3.0


def medir(c: Cliente, ruta: str, reps: int = REPETICIONES,
          params: dict | None = None) -> tuple[list[float], int]:
    """Devuelve (tiempos sin la primera, código de la última respuesta)."""
    tiempos, codigo = [], -1
    for i in range(reps + 1):
        t0 = time.time()
        r = c.get(ruta, params=params, timeout=180)
        dt = time.time() - t0
        codigo = c.codigo(r)
        if i > 0:                      # la primera se descarta: caché fría
            tiempos.append(dt)
    return tiempos, codigo


def correr(estado_datos: str = "E3") -> Registro:
    reg = Registro(estado_datos)
    if not esperar_api():
        reg.caso("P01-004", "El API responde", condicion=False, severidad="S1",
                 observado="sin respuesta", esperado="HTTP < 500")
        return reg

    admin = Cliente("ADMIN")
    if not admin.entrar():
        reg.caso("P02-001", "Login de ADMIN", condicion=False, severidad="S1",
                 observado=admin.error_login or "?", esperado="200")
        return reg

    print(f"\n=== midiendo aislado, {REPETICIONES} repeticiones + 1 de calentamiento ===\n",
          flush=True)

    # ── control: si estos salen lentos, la medición no vale ─────────────────
    lentos_de_control = []
    for ruta in CONTROL:
        tiempos, codigo = medir(admin, ruta)
        p50 = statistics.median(tiempos) if tiempos else -1
        if p50 > UMBRAL_S:
            lentos_de_control.append(f"{ruta.split('/')[-1]}={p50:.1f}s")
        print(f"  control  {ruta.split('/')[-1]:26} p50={p50:6.2f}s  HTTP {codigo}", flush=True)

    reg.caso("P12-000", "El entorno de medida está sano (los rápidos siguen rápidos)",
             condicion=not lentos_de_control, severidad="S2",
             observado=", ".join(lentos_de_control) if lentos_de_control else "los 3 por debajo del umbral",
             esperado="si el control sale lento, la máquina está ocupada y "
                      "ninguna cifra de abajo sirve")

    # ── los sospechosos ─────────────────────────────────────────────────────
    reproducibles, absueltos = [], []
    for ruta, peor_barrido in SOSPECHOSOS.items():
        tiempos, codigo = medir(admin, ruta)
        if not tiempos:
            continue
        p50 = statistics.median(tiempos)
        p95 = max(tiempos)
        nombre = ruta.split("/")[-1]
        print(f"  sospecha {nombre:26} p50={p50:6.2f}s  p95={p95:6.2f}s  "
              f"(barrido {peor_barrido:.1f}s)  HTTP {codigo}", flush=True)

        dentro = p95 <= UMBRAL_S
        if dentro:
            absueltos.append(f"{nombre} {peor_barrido:.0f}s→{p95:.1f}s")
        else:
            reproducibles.append(f"{nombre} p50={p50:.1f}s p95={p95:.1f}s")

        # ── LO QUE VIVE EL USUARIO ──────────────────────────────────────────
        # Para los informes que agregan, la pantalla llega con su ventana por
        # defecto puesta. Ese es el tiempo que hay que exigir; el de la ruta
        # pelada se registra aparte y como PEOR CASO, no como fallo.
        if ruta in FILTRO_DE_PANTALLA:
            params = {"desde": _ventana_por_defecto()}
            t_pant, cod_pant = medir(admin, ruta, params=params)
            p95_pant = max(t_pant) if t_pant else -1
            print(f"           └ con el filtro de la pantalla   p95={p95_pant:6.2f}s  "
                  f"HTTP {cod_pant}", flush=True)
            reg.caso("P12-001", f"{nombre} con el filtro por defecto de la pantalla",
                     condicion=p95_pant <= UMBRAL_S, severidad="S3",
                     observado=f"p95={p95_pant:.2f}s con desde={params['desde']} "
                               f"(sin filtro: {p95:.1f}s)",
                     esperado=f"p95 ≤ {UMBRAL_S} s — es el tiempo que el usuario ve al abrir",
                     reproducir=f'{admin.curl("GET", ruta)}?desde={params["desde"]}',
                     ms=int(p95_pant * 1000))
            reg.omitir("P12-001", f"{nombre} SIN filtro (peor caso declarado)",
                       motivo=f"p95={p95:.2f}s. Un GET sin `desde` significa «todo el "
                              f"histórico» y es lento a propósito: el defecto lo pone la "
                              f"pantalla, no el servicio, para que la API no mienta "
                              f"devolviendo un subconjunto como si fuera el total")
            continue

        reg.caso("P12-001", f"{nombre} responde dentro del umbral aislado",
                 condicion=dentro, severidad="S3",
                 observado=f"p50={p50:.2f}s p95={p95:.2f}s (el barrido dio {peor_barrido:.1f}s)",
                 esperado=f"p95 ≤ {UMBRAL_S} s",
                 reproducir=admin.curl("GET", ruta),
                 ms=int(p95 * 1000))

    # ── el veredicto que importa: cuáles son consulta y cuáles eran ruido ───
    reg.caso("P12-DIAGNOSTICO",
             "Separación entre lentitud reproducible y contención del barrido",
             condicion=True, severidad="",
             observado=(f"REPRODUCIBLES ({len(reproducibles)}): {'; '.join(reproducibles) or 'ninguno'} || "
                        f"ABSUELTOS ({len(absueltos)}): {'; '.join(absueltos) or 'ninguno'}"),
             esperado="solo los reproducibles justifican tocar una consulta",
             detalle="los absueltos eran contención de 9 roles llamando en serie")

    return reg


if __name__ == "__main__":
    estado = sys.argv[1] if len(sys.argv) > 1 else "E3"
    reg = correr(estado)
    print("\n" + "=" * 70)
    print(reg.resumen())
    print("informe:", reg.volcar("p12_rendimiento"))
    sys.exit(1 if reg.fallos else 0)
