"""
p07_vacio.py — Estado vacío (suite P07-002 / P08-002 del plan).

Es la suite que responde a la pregunta central del plan: **qué hace el sistema
el primer día, cuando no hay ni un dato de negocio.**

No mira códigos HTTP —eso ya lo hace el barrido— sino el CONTENIDO:

  · el sobre existe y es coherente: `items` es una lista VACÍA y `total` es 0
  · ningún KPI sale como `NaN`, `Infinity` ni cadena vacía
  · un KPI que no se puede calcular lo DICE, en vez de mostrar un cero que se
    lee como «vendimos $0» cuando lo cierto es «todavía no hay con qué medir»
  · un listado sin filas devuelve **200 con lista vacía**, jamás 404

DOS CLASES DE INFORME, y la distinción es obligatoria:

  · **SIMPLES** — van contra PostgreSQL. En E0 su origen ESTÁ vacío, así que
    aquí sí se está midiendo el estado vacío de verdad.
  · **COMPUESTOS** — van contra ClickHouse (`retailmind_dwh`). El montaje de E0
    vacía PostgreSQL pero **comparte el almacén**, así que estos siguen
    respondiendo con los datos de la carga masiva. NO se juzgan como estado
    vacío: se comprueba solo que no revienten, y la suite lo DECLARA.

    Esa asimetría no es solo del arnés — es un hallazgo: nada en el sistema
    comprueba que el almacén corresponda a la base operativa que tiene delante.
    Ver el caso `P08-COHERENCIA` al final, que la mide en vez de suponerla.
"""

from __future__ import annotations

import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from comun.arnes import Cliente, Registro, esperar_api                     # noqa: E402
from comun.catalogo import concretar, extraer                               # noqa: E402

#: Informes SIMPLES (PostgreSQL). En E0 su origen está realmente vacío.
SIMPLES = [
    "/api/informes/ventas/cartera-pedidos",
    "/api/informes/ventas/por-vendedor",
    "/api/informes/ventas/carritos-abandonados",
    "/api/informes/ventas/moderacion",
    "/api/informes/ventas/avance-meta",
    "/api/informes/compras/ordenes",
    "/api/informes/compras/cuentas-por-pagar",
    "/api/informes/compras/defectuosos",
    "/api/informes/compras/catalogo-proveedor",
    "/api/informes/compras/entregas-incompletas",
    "/api/informes/logistica/cola-despacho",
    "/api/informes/logistica/envios",
    "/api/informes/logistica/devoluciones",
    "/api/informes/logistica/costo-envio",
    "/api/informes/soporte/bandeja",
    "/api/informes/soporte/por-categoria",
    "/api/informes/soporte/por-agente",
    "/api/informes/gerencia/foto-dia",
    "/api/informes/gerencia/cupones",
    "/api/informes/gerencia/marketing",
    "/api/informes/gerencia/auditoria",
    "/api/informes/gerencia/accesos",
]

#: Informes cuyas filas en E0 NO son dato de negocio y por tanto son legítimas:
#: agregan sobre tablas de REFERENCIA, o emiten una fila de «sin movimiento»
#: por diseño, o registran la propia actividad de las pruebas.
CON_FILAS_DE_REFERENCIA = {
    "por-categoria",   # las 8 categorías de ticket, con 0 tickets cada una
    "foto-dia",        # emite «Día sin movimiento» a propósito
    "accesos",         # log_acceso recoge los logins de esta misma suite
}

#: Valores que NO puede tomar un KPI publicado.
def _valor_enfermo(v) -> str | None:
    if isinstance(v, float):
        if math.isnan(v):
            return "NaN"
        if math.isinf(v):
            return "Infinity"
    if isinstance(v, str):
        bajo = v.strip().lower()
        if bajo in ("nan", "infinity", "-infinity", "undefined", "null", "none"):
            return f"cadena «{v}»"
        if bajo == "":
            return "cadena vacía"
    return None


def correr(estado_datos: str = "E0") -> Registro:
    reg = Registro(estado_datos)
    if not esperar_api():
        reg.caso("P01-004", "El API responde", condicion=False,
                 observado="sin respuesta", esperado="HTTP < 500", severidad="S1")
        return reg

    admin = Cliente("ADMIN")
    if not admin.entrar():
        reg.caso("P02-001", "Login de ADMIN", condicion=False,
                 observado=admin.error_login or "?", esperado="200", severidad="S1")
        return reg

    # ── P07-002 · los informes simples con la base vacía ─────────────────────
    for ruta in SIMPLES:
        nombre = ruta.split("/")[-1]
        r = admin.get(ruta)
        codigo = admin.codigo(r)

        if codigo == 404:
            reg.caso("P07-002", f"{nombre}: 404 sobre conjunto vacío",
                     condicion=False, severidad="S2",
                     observado="HTTP 404",
                     esperado="200 con lista vacía — un listado sin filas no es un recurso inexistente",
                     reproducir=admin.curl("GET", ruta))
            continue
        if codigo >= 500:
            reg.caso("P07-002", f"{nombre}: error interno con base vacía",
                     condicion=False, severidad="S2",
                     observado=f"HTTP {codigo} · {(r.text or '')[:150]}",
                     esperado="200 con estado vacío",
                     reproducir=admin.curl("GET", ruta))
            continue
        if codigo != 200:
            # 409 y 403 se juzgan aparte: pueden ser diseño.
            reg.caso("P07-002", f"{nombre}: responde {codigo} con base vacía",
                     condicion=codigo == 403, severidad="S3",
                     observado=f"HTTP {codigo} · {(r.text or '')[:180]}",
                     esperado="200 con estado vacío (un 409 obliga al usuario "
                              "a resolver algo antes de poder VER la pantalla)",
                     reproducir=admin.curl("GET", ruta))
            continue

        try:
            cuerpo = r.json()
        except Exception:
            reg.caso("P07-002", f"{nombre}: respuesta no es JSON", condicion=False,
                     severidad="S2", observado=(r.text or "")[:120], esperado="JSON")
            continue

        # El sobre
        items = cuerpo.get("items")
        total = cuerpo.get("total")
        reg.caso("P07-003", f"{nombre}: sobre completo",
                 condicion=isinstance(items, list) and total is not None,
                 severidad="S3",
                 observado=f"items={type(items).__name__} total={total}",
                 esperado="{items:[], total:0, page, size, resumen[]}")

        # «0 filas» NO es exigible a todos: hay informes que agregan sobre una
        # tabla de REFERENCIA (`por-categoria` lista las 8 categorías de ticket
        # con cero tickets cada una), otros que emiten una fila explícita de
        # «sin movimiento» por diseño (`foto-dia`), y `accesos` registra los
        # propios logins de esta suite. En los tres casos las filas son
        # correctas; exigir cero mediría la referencia, no el estado vacío.
        if nombre not in CON_FILAS_DE_REFERENCIA:
            reg.caso("P07-002", f"{nombre}: lista vacía con base vacía",
                     condicion=isinstance(items, list) and len(items) == 0,
                     severidad="S3",
                     observado=f"{len(items) if isinstance(items, list) else '?'} filas",
                     esperado="0 filas (no hay dato de negocio)",
                     detalle="si trae filas, salen de referencia o de otro origen")

        # Los KPI
        for kpi in cuerpo.get("resumen") or []:
            enfermo = _valor_enfermo(kpi.get("valor"))
            reg.caso("P07-002", f"{nombre}: KPI «{kpi.get('etiqueta')}» publicable",
                     condicion=enfermo is None, severidad="S2",
                     observado=f"valor={kpi.get('valor')!r} ({enfermo})" if enfermo else "ok",
                     esperado="un número, un texto legible, o un «sin datos» explícito",
                     reproducir=admin.curl("GET", ruta))

    # ── P08-COHERENCIA · ¿el almacén habla de esta misma base? ───────────────
    #
    # Un informe COMPUESTO lee ClickHouse. Si el almacén quedó de otra base —o
    # el ETL lleva días parado— el informe sigue respondiendo 200 con cifras
    # perfectamente formadas y de otro conjunto de datos. Aquí se MIDE, en vez
    # de suponerlo: se compara una magnitud que ambos lados conocen.
    r_pg = admin.get("/api/informes/ventas/cartera-pedidos")
    r_ch = admin.get("/api/informes/inventario/rotacion")
    pedidos_pg = (r_pg.json().get("total") if admin.codigo(r_pg) == 200 else None)
    uds_ch = None
    if admin.codigo(r_ch) == 200:
        for k in r_ch.json().get("resumen") or []:
            if "vendidas" in str(k.get("etiqueta", "")).lower():
                uds_ch = k.get("valor")

    coherente = not (pedidos_pg == 0 and isinstance(uds_ch, (int, float)) and uds_ch > 0)
    reg.caso("P08-COHERENCIA",
             "El almacén corresponde a la base operativa",
             condicion=coherente, severidad="S2",
             observado=f"PostgreSQL: {pedidos_pg} pedidos · ClickHouse: {uds_ch} uds vendidas",
             esperado="si el operativo está vacío, la analítica no puede publicar volumen",
             detalle="nada en el sistema comprueba esta correspondencia; "
                     "el único indicio en pantalla es la etiqueta «fuente»")

    return reg


if __name__ == "__main__":
    estado = sys.argv[1] if len(sys.argv) > 1 else "E0"
    reg = correr(estado)
    print("\n" + "=" * 70)
    print(reg.resumen())
    print("informe:", reg.volcar("p07_vacio"))
    sys.exit(1 if reg.fallos else 0)
