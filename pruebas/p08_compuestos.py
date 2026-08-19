"""
p08_compuestos.py — Informes compuestos y tableros (suite P08).

Las 43 rutas compuestas y los 7 tableros van contra ClickHouse, y su riesgo no
es caerse: es **publicar una cifra creíble y falsa**. Por eso esta suite no
comprueba solo que respondan, sino el CONTRATO que hace auditable cada número:

  · **`fuente`** — de qué motor salió. Sin ella, un informe de ClickHouse y uno
    de PostgreSQL son indistinguibles en pantalla.
  · **`datosAl`** — a qué momento corresponde el dato. Es lo único que separa
    «la venta de ayer» de «la venta de hace tres semanas porque el ETL se paró».
  · **`analiticaDisponible`** — si el almacén respondió. Un `false` con cifras
    en blanco es honesto; unas cifras sin esta bandera, no.
  · **denominador y salvedades** en los tableros — en el nivel estratégico una
    cifra sin su base no produce una pantalla rara, produce una DECISIÓN.

Y comprueba la segregación financiera **por la CONSULTA y no solo por la ruta**,
que es donde este sistema la juega: ClickHouse no tiene GRANT por columna, así
que lo único que mantiene a BODEGA y DESPACHO lejos del dinero es que el SQL no
lo seleccione. Se recorre la respuesta ENTERA buscando nombres con aspecto
monetario.
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from comun.arnes import Cliente, Registro, esperar_api, sesiones           # noqa: E402
from comun.catalogo import extraer                                          # noqa: E402

#: Los 7 tableros del nivel estratégico.
TABLEROS = ["omnicanal", "rentabilidad", "cliente-posventa", "operacion",
            "costo-operacion", "abastecimiento", "gobierno-dato"]

#: T-4 es el ÚNICO tablero sin dinero, y el único que DESPACHO y BODEGA abren.
TABLERO_SIN_DINERO = "operacion"

#: Roles que pueden abrir T-4 (los cinco declarados en el diseño).
ROLES_T4 = ["ADMIN", "GERENTE", "ANALISTA", "BODEGA", "DESPACHO"]

#: Nombres de campo que delatan un importe. La lista es deliberadamente amplia:
#: un falso positivo se revisa en un minuto, un importe que se escapa a BODEGA
#: no lo detecta nadie.
OLOR_A_DINERO = ("monto", "total", "precio", "costo", "importe", "margen",
                 "saldo", "ingreso", "gasto", "capital", "facturado",
                 "cobrado", "pagado", "ticket_promedio", "valor_")

#: Rutas compuestas que NO deben verse desde BODEGA ni DESPACHO (llevan dinero).
CON_DINERO = [
    "/api/informes/inventario/capital-inmovilizado",
    "/api/informes/logistica/costo-envio-mensual",
    "/api/informes/gerencia/balanza",
    "/api/informes/gerencia/descuento-cupones",
    "/api/informes/ventas/ticket-promedio",
]


#: Prefijos que convierten un nombre sospechoso en una BANDERA, no en un
#: importe: `es_total` marca la fila de totales, `tiene_costo` dice si lo hay.
PREFIJOS_DE_BANDERA = ("es_", "tiene_", "hay_", "incluye_", "con_", "sin_")


def _es_importe(clave: str, valor) -> bool:
    """
    ¿Este campo es un IMPORTE de verdad?

    Dos trampas, las dos pagadas en la primera corrida de esta suite:

    1. **En Python `bool` es subclase de `int`**, así que
       `isinstance(True, (int, float))` es CIERTO. Sin excluirlo antes, toda
       bandera booleana se contaba como dinero.
    2. **La subcadena no basta**: «total» está dentro de `es_total`, que es la
       marca de la fila de totales. Un nombre con prefijo de bandera no es un
       importe por mucho que contenga la palabra.

    Las dos juntas hacían que T-4 —el único tablero SIN dinero— saliera con
    cuatro «importes» en los cinco roles. No había ni uno.
    """
    if isinstance(valor, bool) or not isinstance(valor, (int, float)):
        return False
    k = str(clave).lower()
    if k.startswith(PREFIJOS_DE_BANDERA):
        return False
    return any(p in k for p in OLOR_A_DINERO)


def _campos_monetarios(nodo, camino="") -> list[str]:
    """Recorre la respuesta entera y devuelve los campos con aspecto de dinero."""
    hallados = []
    if isinstance(nodo, dict):
        for clave, valor in nodo.items():
            if _es_importe(clave, valor):
                hallados.append(f"{camino}.{clave}" if camino else str(clave))
            hallados += _campos_monetarios(valor, f"{camino}.{clave}" if camino else str(clave))
    elif isinstance(nodo, list):
        for i, v in enumerate(nodo[:20]):          # basta con una muestra
            hallados += _campos_monetarios(v, f"{camino}[{i}]")
    return hallados


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

    # ── El catálogo se cuenta DESDE EL CÓDIGO, no de memoria ────────────────
    compuestas = sorted({e.ruta for e in extraer()
                         if "Compuestos" in e.controlador and e.metodo == "GET"})
    reg.caso("P08-001", "El catálogo compuesto sigue teniendo 43 rutas",
             condicion=len(compuestas) == 43, severidad="S3",
             observado=f"{len(compuestas)} rutas",
             esperado="43 — si cambia, hay que reconciliar rutas contra OBJETIVOS "
                      "(no son la misma cuenta: ficha C-14)")

    # ── P08-001 · las 43 responden, y su sobre es auditable ────────────────
    sin_fuente, sin_fecha, sin_bandera = [], [], []
    for ruta in compuestas:
        nombre = ruta.split("/")[-1]
        r = admin.get(ruta, timeout=180)
        codigo = admin.codigo(r)
        if codigo != 200:
            reg.caso("P08-001", f"Compuesto responde: {nombre}",
                     condicion=codigo in (403,), severidad="S2",
                     observado=f"HTTP {codigo} · {(r.text or '')[:140] if r is not None else 'sin respuesta'}",
                     esperado="200 (o 403 si ADMIN no fuera su destinatario)",
                     reproducir=admin.curl("GET", ruta))
            continue
        try:
            cuerpo = r.json()
        except Exception:
            reg.caso("P08-001", f"{nombre} devuelve JSON", condicion=False,
                     severidad="S2", observado=(r.text or "")[:100], esperado="JSON")
            continue

        if "fuente" not in cuerpo:
            sin_fuente.append(nombre)
        if "datosAl" not in cuerpo:
            sin_fecha.append(nombre)
        if "analiticaDisponible" not in cuerpo:
            sin_bandera.append(nombre)

    reg.caso("P08-021", "Todo compuesto declara su FUENTE",
             condicion=not sin_fuente, severidad="S2",
             observado=f"{len(sin_fuente)} sin `fuente`: {', '.join(sin_fuente[:6])}"
                       if sin_fuente else "las 43 la declaran",
             esperado="sin `fuente`, un informe de ClickHouse y uno de PostgreSQL "
                      "son indistinguibles en pantalla")

    reg.caso("P08-021", "Todo compuesto declara a QUÉ MOMENTO corresponde el dato",
             condicion=not sin_fecha, severidad="S2",
             observado=f"{len(sin_fecha)} sin `datosAl`: {', '.join(sin_fecha[:6])}"
                       if sin_fecha else "las 43 lo declaran",
             esperado="`datosAl` es lo único que separa «la venta de ayer» de "
                      "«la de hace tres semanas porque el ETL se paró»")

    reg.caso("P08-001", "Todo compuesto declara si la analítica respondió",
             condicion=not sin_bandera, severidad="S2",
             observado=f"{len(sin_bandera)} sin `analiticaDisponible`: {', '.join(sin_bandera[:6])}"
                       if sin_bandera else "las 43 la declaran",
             esperado="sin la bandera, unas cifras vacías no se distinguen de un "
                      "almacén caído")

    # ── P08-003 · los tableros y su contrato ───────────────────────────────
    for tablero in TABLEROS:
        ruta = f"/api/tableros/{tablero}"
        r = admin.get(ruta, timeout=180)
        codigo = admin.codigo(r)
        if codigo != 200:
            reg.caso("P08-001", f"Tablero responde: {tablero}", condicion=False,
                     severidad="S2", observado=f"HTTP {codigo}", esperado="200",
                     reproducir=admin.curl("GET", ruta))
            continue
        try:
            cuerpo = r.json()
        except Exception:
            continue

        bloques = cuerpo.get("bloques") or []
        reg.caso("P08-003", f"{tablero}: cada bloque declara su DENOMINADOR",
                 condicion=all("denominador" in b for b in bloques) and len(bloques) > 0,
                 severidad="S1",
                 observado=f"{sum(1 for b in bloques if 'denominador' not in b)} de "
                           f"{len(bloques)} bloques sin denominador",
                 esperado="todos — en este nivel una cifra sin su base no produce "
                          "una pantalla rara, produce una DECISIÓN")

        reg.caso("P08-007", f"{tablero}: trae `salvedades` y `datosAl`",
                 condicion=("salvedades" in cuerpo and "datosAl" in cuerpo),
                 severidad="S2",
                 observado=f"salvedades={'salvedades' in cuerpo} datosAl={cuerpo.get('datosAl')!r}",
                 esperado="las salvedades (costo vigente, moneda constante, muestra "
                          "débil) se pintan ENCIMA de la cifra")

    # ── P08-008 · T-4 no lleva dinero para NINGUNO de sus cinco roles ──────
    # Es la comprobación que el motor no puede hacer: ClickHouse no tiene GRANT
    # por columna, así que lo único que separa a BODEGA del dinero es que la
    # consulta no lo seleccione. Se verifica automáticamente, en los 5 roles.
    clientes = sesiones(ROLES_T4)
    for rol, c in clientes.items():
        r = c.get(f"/api/tableros/{TABLERO_SIN_DINERO}", timeout=180)
        codigo = c.codigo(r)
        if codigo != 200:
            reg.caso("P08-008", f"{rol} puede abrir T-4 (el único tablero sin dinero)",
                     condicion=False, severidad="S2",
                     observado=f"HTTP {codigo}", esperado="200 — T-4 es de operación pura",
                     reproducir=c.curl("GET", f"/api/tableros/{TABLERO_SIN_DINERO}"))
            continue
        try:
            hallados = _campos_monetarios(r.json())
        except Exception:
            hallados = []
        reg.caso("P08-008", f"T-4 no devuelve ni un importe a {rol}",
                 condicion=not hallados, severidad="S1",
                 observado=f"campos monetarios: {hallados[:6]}" if hallados else "ninguno",
                 esperado="ninguno — ClickHouse no tiene GRANT por columna, así que "
                          "lo que no debe salir NO SE SELECCIONA")

    # ── P08-020 · las rutas con dinero dejan fuera a BODEGA y DESPACHO ─────
    sin_dinero = sesiones(["BODEGA", "DESPACHO"])
    for rol, c in sin_dinero.items():
        for ruta in CON_DINERO:
            r = c.get(ruta, timeout=180)
            codigo = c.codigo(r)
            if codigo == 404:
                continue                       # ruta que no existe en este catálogo
            if codigo == 200:
                try:
                    hallados = _campos_monetarios(r.json())
                except Exception:
                    hallados = []
                reg.caso("P08-020", f"{rol} entra a {ruta.split('/')[-1]} sin recibir importes",
                         condicion=not hallados, severidad="S1",
                         observado=f"HTTP 200 con {hallados[:4]}" if hallados else "200 sin importes",
                         esperado="403, o 200 sin un solo campo monetario",
                         reproducir=c.curl("GET", ruta))
            else:
                reg.caso("P08-020", f"{rol} queda fuera de {ruta.split('/')[-1]}",
                         condicion=codigo == 403, severidad="S2",
                         observado=f"HTTP {codigo}", esperado="403",
                         reproducir=c.curl("GET", ruta))

    return reg


if __name__ == "__main__":
    estado = sys.argv[1] if len(sys.argv) > 1 else "E3"
    reg = correr(estado)
    print("\n" + "=" * 70)
    print(reg.resumen())
    print("informe:", reg.volcar("p08_compuestos"))
    sys.exit(1 if reg.fallos else 0)
