"""
p04_validacion.py — Validación de entrada (suite P04).

Prueba de caja negra: mete basura por los parámetros y exige que el sistema la
rechace con el código correcto. Lo que se persigue NO es que rechace —eso
suele hacerlo—, sino que rechace **con el código que corresponde**:

    400  entrada mal formada / valor fuera de la lista blanca
    404  ruta o recurso inexistente
    405  método equivocado
    409  guardia de estado
    500  NUNCA, y menos por un parámetro que escribió el usuario

Un 500 por una comilla o por `?page=-1` es S2: manda a buscar el fallo dentro
del servidor cuando el problema está en la petición, que es justo lo que el
`GlobalExceptionHandler` documenta querer evitar.
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from comun.arnes import Cliente, Registro, esperar_api                     # noqa: E402

#: Informes SIMPLES con un filtro de lista blanca conocido.
INFORMES_CON_LISTA_BLANCA = [
    ("/api/informes/ventas/cartera-pedidos",      "estado"),
    ("/api/informes/ventas/carritos-abandonados", "estado"),
    ("/api/informes/soporte/bandeja",             "estado"),
    ("/api/informes/compras/ordenes",             "estado"),
    ("/api/informes/compras/cuentas-por-pagar",   "situacion"),
    ("/api/informes/gerencia/cupones",            "situacion"),
    ("/api/informes/gerencia/marketing",          "vigencia"),
    ("/api/informes/compras/entregas-incompletas", "alcance"),
    ("/api/informes/logistica/envios",            "estado"),
]

#: Rutas de listado donde probar la paginación.
#
# El catálogo entra por `/productos/buscar`, que es el que PAGINA. El
# `/productos` a secas se retiró el 2026-08-19 (defecto D-04): devolvía los
# 6.217 productos sin tope y no lo usaba nadie. Apuntar aquí al retirado hacía
# que los cuatro casos suspendieran con un 405 —correcto, porque el POST sigue
# existiendo— acusando al sistema de un fallo de paginación inexistente.
PAGINADAS = [
    "/api/informes/ventas/cartera-pedidos",
    "/api/informes/soporte/bandeja",
    "/api/informes/compras/ordenes",
    "/api/admin/catalogo/productos/buscar",
]

#: Cadenas hostiles. Ninguna debe producir un 500 ni ejecutarse.
HOSTILES = [
    ("comilla",        "'"),
    ("inyeccion_or",   "' OR '1'='1"),
    ("inyeccion_drop", "x'; DROP TABLE marca; --"),
    ("comentario",     "-- "),
    ("nulo_c",         "a\x00b"),
    ("muy_larga",      "A" * 5000),
    ("unicode",        "ñÁé—😀"),
    ("json_llaves",    '{"a":1}'),
    ("porcentaje",     "%s %d %%"),
    ("negativo",       "-1"),
]


def correr(estado_datos: str = "E3") -> Registro:
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

    # ── P04-001 · lista blanca de filtros ────────────────────────────────────
    for ruta, filtro in INFORMES_CON_LISTA_BLANCA:
        base = admin.get(ruta)
        if base is None or base.status_code in (404, 401):
            reg.caso("P04-001", f"{ruta} existe", condicion=False, severidad="S3",
                     observado=f"HTTP {base.status_code if base is not None else 'sin respuesta'}",
                     esperado="la ruta debería existir",
                     reproducir=admin.curl("GET", ruta))
            continue
        for etiqueta, valor in HOSTILES:
            r = admin.get(ruta, params={filtro: valor})
            codigo = r.status_code if r is not None else -1
            reg.caso("P04-001",
                     f"lista blanca {filtro}={etiqueta} en {ruta.split('/')[-1]}",
                     condicion=codigo == 400,
                     severidad="S2" if codigo in (500, -1) else "S3",
                     observado=(f"HTTP {codigo}" + (f" · {(r.text or '')[:120]}" if r is not None and codigo >= 500 else "")) if r is not None else f"sin respuesta · {admin.ultimo_error}",
                     esperado="HTTP 400 (valor fuera de la lista blanca)",
                     reproducir=f'{admin.curl("GET", ruta)}?{filtro}={valor[:40]}')

    # ── P04-003 · tipo de parámetro cambiado ─────────────────────────────────
    # Solo sobre endpoints que DECLARAN el parámetro. `/admin/catalogo/productos`
    # no declara ninguno, así que ignorar `page=abc` es lo correcto y probarlo
    # ahí mediría la nada (ver D-04: ese endpoint no pagina, que es otro asunto).
    for ruta, params in (
        ("/api/informes/ventas/cartera-pedidos",  {"page": "abc"}),
        ("/api/admin/catalogo/productos/buscar",  {"marcaId": "abc"}),
        ("/api/informes/ventas/cartera-pedidos",  {"size": "1,5"}),
    ):
        r = admin.get(ruta, params=params)
        codigo = admin.codigo(r)
        reg.caso("P04-003", f"{params} en {ruta.split('/')[-1]}",
                 condicion=codigo == 400, severidad="S2" if codigo >= 500 else "S3",
                 observado=f"HTTP {codigo}", esperado="HTTP 400 nombrando el parámetro",
                 reproducir=f'{admin.curl("GET", ruta)}?{params}')

    # ── P04-004/005 · ruta y método ──────────────────────────────────────────
    r = admin.get("/api/no-existe-esta-ruta")
    reg.caso("P04-004", "Ruta inexistente bajo /api",
             condicion=(r is not None and r.status_code == 404), severidad="S3",
             observado=f"HTTP {r.status_code if r is not None else -1}", esperado="HTTP 404")

    # POST sobre una ruta de solo lectura. Bajo `/api/informes/**` la respuesta
    # es **403 y no 405**, y es CORRECTO: esa rama termina en `denyAll()` y
    # Spring Security se ejecuta ANTES del enrutado, así que la petición nunca
    # llega a descubrir que el método no existe. Denegar antes de enrutar es más
    # estricto, no menos: no filtra qué métodos hay detrás.
    r = admin.pedir("POST", "/api/informes/ventas/cartera-pedidos", json={})
    codigo = admin.codigo(r)
    reg.caso("P04-005", "POST sobre una ruta GET protegida por denyAll",
             condicion=codigo in (403, 405), severidad="S3",
             observado=f"HTTP {codigo}",
             esperado="403 (denyAll antes del enrutado) o 405")

    # Y sobre una ruta que NO está bajo denyAll, donde sí debe verse el 405.
    r = admin.pedir("DELETE", "/api/auth/roles")
    codigo = admin.codigo(r)
    reg.caso("P04-005", "DELETE sobre /api/auth/roles (solo GET)",
             condicion=codigo in (403, 405), severidad="S3",
             observado=f"HTTP {codigo}", esperado="405, o 403 si la ruta lo deniega antes")

    # ── P04-012 · paginación ─────────────────────────────────────────────────
    for ruta in PAGINADAS:
        for etiqueta, params in (
            ("page negativa", {"page": -1}),
            ("size cero",     {"size": 0}),
            ("size enorme",   {"size": 100000}),
            ("page enorme",   {"page": 999999999}),
        ):
            r = admin.get(ruta, params=params)
            codigo = r.status_code if r is not None else -1
            # 400 (rechaza) o 200 (tope aplicado) son ambos aceptables; 500 no.
            ok = codigo in (200, 400)
            detalle = ""
            if codigo == 200 and etiqueta == "size enorme":
                try:
                    cuerpo = r.json()
                    n = len(cuerpo.get("items", []))
                    ok = n <= 1000
                    detalle = f"devolvió {n} filas"
                except Exception:
                    pass
            reg.caso("P04-012", f"{etiqueta} en {ruta.split('/')[-1]}",
                     condicion=ok, severidad="S2" if codigo >= 500 else "S3",
                     observado=f"HTTP {codigo} {detalle}",
                     esperado="400, o 200 con tope declarado (≤1000 filas)",
                     reproducir=f'{admin.curl("GET", ruta)}?{params}')

    # ── P04-011 · fechas invertidas ──────────────────────────────────────────
    r = admin.get("/api/informes/ventas/cartera-pedidos",
                  params={"desde": "2030-01-01", "hasta": "2020-01-01"})
    codigo = r.status_code if r is not None else -1
    reg.caso("P04-011", "Rango de fechas invertido",
             condicion=codigo in (200, 400), severidad="S2" if codigo >= 500 else "S3",
             observado=f"HTTP {codigo}", esperado="400, o 200 con conjunto vacío")

    r = admin.get("/api/informes/ventas/cartera-pedidos", params={"desde": "no-es-fecha"})
    codigo = r.status_code if r is not None else -1
    reg.caso("P04-011", "Fecha mal formada",
             condicion=codigo == 400, severidad="S2" if codigo >= 500 else "S3",
             observado=f"HTTP {codigo}", esperado="HTTP 400")

    # ── P04-007/009 · alta de variante: peso y precio ────────────────────────
    # Solo se PRUEBA EL RECHAZO: ningún caso de esta suite escribe en la base.
    for etiqueta, cuerpo in (
        ("sin peso",       {"sku": "PRUEBA-P04", "precio": 10, "stockMinimo": 1}),
        ("peso cero",      {"sku": "PRUEBA-P04", "precio": 10, "pesoKg": 0}),
        ("peso negativo",  {"sku": "PRUEBA-P04", "precio": 10, "pesoKg": -1.5}),
        ("precio cero",    {"sku": "PRUEBA-P04", "precio": 0,  "pesoKg": 1.2}),
        ("precio negativo",{"sku": "PRUEBA-P04", "precio": -5, "pesoKg": 1.2}),
        ("precio nulo",    {"sku": "PRUEBA-P04", "precio": None, "pesoKg": 1.2}),
    ):
        r = admin.pedir("POST", "/api/admin/catalogo/productos/1/variantes", json=cuerpo)
        codigo = r.status_code if r is not None else -1
        reg.caso("P04-007", f"alta de variante · {etiqueta}",
                 condicion=codigo == 400, severidad="S1" if codigo in (200, 201) else "S3",
                 observado=f"HTTP {codigo} · {(r.text or '')[:140] if r is not None else ''}",
                 esperado="HTTP 400 (peso y precio estrictamente positivos)",
                 reproducir=f'POST /api/admin/catalogo/productos/1/variantes {cuerpo}')

    # ── P04-006 · cuerpo malformado ──────────────────────────────────────────
    r = admin.pedir("POST", "/api/auth/login",
                    data="{esto no es json", headers={"Content-Type": "application/json"})
    codigo = r.status_code if r is not None else -1
    reg.caso("P04-006", "JSON malformado en login",
             condicion=codigo in (400, 401), severidad="S2" if codigo >= 500 else "S3",
             observado=f"HTTP {codigo}", esperado="HTTP 400")

    # ── P02-005/006 · token ──────────────────────────────────────────────────
    anon = Cliente("ADMIN")
    r = anon.pedir("GET", "/api/informes/ventas/cartera-pedidos")
    codigo = r.status_code if r is not None else -1
    reg.caso("P02-005", "GET protegido sin token",
             condicion=codigo in (401, 403), severidad="S1" if codigo == 200 else "S3",
             observado=f"HTTP {codigo}", esperado="401 o 403")

    falso = Cliente("ADMIN")
    falso.sesion.headers["Authorization"] = "Bearer " + (admin.token or "")[:-6] + "AAAAAA"
    r = falso.pedir("GET", "/api/informes/ventas/cartera-pedidos")
    codigo = r.status_code if r is not None else -1
    reg.caso("P02-006", "Token con la firma manipulada",
             condicion=codigo in (401, 403), severidad="S1" if codigo == 200 else "S3",
             observado=f"HTTP {codigo}", esperado="401 — la firma no valida")

    # ── P02-009 · los 3 endpoints de fact_eventos retirados (A-3) ────────────
    for metodo in ("GET", "PUT", "DELETE"):
        r = admin.pedir(metodo, "/api/gestion/fact-eventos/1",
                        json={} if metodo == "PUT" else None)
        codigo = r.status_code if r is not None else -1
        reg.caso("P02-009", f"{metodo} /api/gestion/fact-eventos/1 sigue retirado",
                 condicion=codigo in (404, 405), severidad="S1",
                 observado=f"HTTP {codigo}",
                 esperado="404/405 — se retiraron el 2026-08-07 por A-3")

    return reg


if __name__ == "__main__":
    estado = sys.argv[1] if len(sys.argv) > 1 else "E3"
    reg = correr(estado)
    print("\n" + "=" * 70)
    print(reg.resumen())
    print("informe:", reg.volcar("p04_validacion"))
    sys.exit(1 if reg.fallos else 0)
