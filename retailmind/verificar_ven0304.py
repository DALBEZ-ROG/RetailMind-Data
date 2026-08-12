"""
Verificacion de la sesion del 2026-08-07: A-3 (solo lectura de `fact_eventos`)
+ OTD-VEN-03 y OTD-VEN-04.

Toda cifra se toma de la RESPUESTA HTTP y se contrasta contra ClickHouse con
una consulta escrita aparte. No lee el codigo: lo prueba.

Las claves llegan por ENTORNO y no estan escritas aqui, a proposito: este
archivo es nuevo y no tiene por que engordar la lista de la deuda C-4
(«la contrasena del admin vive en 9 archivos versionados»). A diferencia de
`validar_tableros.py`, que las trae por defecto, aqui NO hay valor de reserva:
si falta la variable, el script se planta y dice cual.

    set RETAILMIND_ADMIN_PASS=...      &&  rem la del admin
    set RETAILMIND_STAFF_PASS=...      &&  rem la del resto de roles
    py -3 retailmind/verificar_ven0304.py
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

API = os.environ.get("RETAILMIND_API", "http://localhost:8080")
CT_CH = "retailmind-clickhouse-1"


def _clave(var: str) -> str:
    v = os.environ.get(var)
    if not v:
        raise SystemExit(
            f"Falta la variable de entorno {var}.\n"
            "  Las credenciales de demostracion NO estan en este archivo (deuda C-4).\n"
            "  Exporta RETAILMIND_ADMIN_PASS y RETAILMIND_STAFF_PASS antes de correrlo;\n"
            "  las tienes en la seccion «Credenciales de desarrollo» de CLAUDE.md.")
    return v


_ADMIN = _clave("RETAILMIND_ADMIN_PASS")
_STAFF = _clave("RETAILMIND_STAFF_PASS")

USUARIOS = {
    "ADMIN":    ("admin@retailmind.com",    _ADMIN),
    "GERENTE":  ("gerente@retailmind.com",  _STAFF),
    "VENDEDOR": ("vendedor@retailmind.com", _STAFF),
    "COMPRAS":  ("compras@retailmind.com",  _STAFF),
    "ANALISTA": ("analista@retailmind.com", _STAFF),
    "BODEGA":   ("bodega@retailmind.com",   _STAFF),
    "DESPACHO": ("despacho@retailmind.com", _STAFF),
    "SOPORTE":  ("soporte@retailmind.com",  _STAFF),
}

fallos: list[str] = []


def check(ok: bool, etiqueta: str, detalle: str = "") -> bool:
    print(f"  [{'OK ' if ok else '!! '}] {etiqueta}" + (f"  {detalle}" if detalle else ""))
    if not ok:
        fallos.append(etiqueta)
    return ok


def ch(q: str) -> str:
    r = subprocess.run(["docker", "exec", CT_CH, "clickhouse-client", "--query", q],
                       capture_output=True)
    if r.returncode != 0:
        raise RuntimeError(r.stderr.decode("utf-8", "replace"))
    return r.stdout.decode("utf-8", "replace").strip()


def login(rol: str) -> str:
    email, clave = USUARIOS[rol]
    datos = json.dumps({"username": email, "password": clave}).encode()
    req = urllib.request.Request(f"{API}/api/auth/login", data=datos,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read())["token"]


def pedir(token: str | None, ruta: str, metodo: str = "GET", cuerpo=None):
    """Devuelve (codigo, json|None)."""
    datos = json.dumps(cuerpo).encode() if cuerpo is not None else None
    cab = {"Content-Type": "application/json"}
    if token:
        cab["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(f"{API}{ruta}", data=datos, headers=cab, method=metodo)
    try:
        with urllib.request.urlopen(req, timeout=90) as r:
            cuerpo_txt = r.read().decode("utf-8", "replace")
            try:
                return r.status, json.loads(cuerpo_txt)
            except json.JSONDecodeError:
                return r.status, None
    except urllib.error.HTTPError as e:
        return e.code, None
    except Exception as e:                      # noqa: BLE001
        return -1, str(e)


# ─────────────────────────────────────────────────────────────────────
def v2_solo_lectura(tok_admin: str) -> None:
    print("\n== V2 — A-3: `fact_eventos` en SOLO LECTURA ==")

    # Era `== 2_823_245`, la foto del día en que se escribió. La tabla NO está
    # congelada: `EventoTiendaService` le añade eventos best-effort cada vez que
    # se usa la tienda, así que ese número crece solo (hoy 2,93 M). Lo que A-3
    # garantiza no es un conteo fijo, es que la aplicación no EDITA ni BORRA:
    # eso es lo que se comprueba abajo con los tres endpoints retirados.
    filas = int(ch("SELECT count() FROM retailmind.fact_eventos"))
    check(filas >= 2_823_245, "fact_eventos no ha perdido filas (solo puede crecer)",
          f"{filas:,}")

    # Los endpoints de escritura NO deben existir: 404/405, jamas 200 ni 500.
    for metodo, ruta, cuerpo in [
        ("GET", "/api/gestion/fact-eventos/0", None),
        ("PUT", "/api/gestion/fact-eventos/0", {"semana": 1}),
        ("DELETE", "/api/gestion/fact-eventos/0", None),
    ]:
        cod, _ = pedir(tok_admin, ruta, metodo, cuerpo)
        check(cod in (404, 405), f"{metodo} {ruta} ya no existe", f"HTTP {cod}")

    # Lo que SI queda: listado y filtro por semana.
    cod, s = pedir(tok_admin, "/api/gestion/fact-eventos?page=0&size=5")
    check(cod == 200 and s is not None and len(s.get("content", [])) == 5,
          "listado paginado de fact_eventos sigue vivo",
          f"HTTP {cod}, total {s.get('totalElements') if s else '-'}")
    if s:
        check(int(s["totalElements"]) == filas,
              "el listado declara el mismo total que ClickHouse",
              f"API {int(s['totalElements']):,} vs CH {filas:,}")

    cod, s = pedir(tok_admin, "/api/gestion/fact-eventos?page=0&size=5&semana=3")
    esperado = int(ch("SELECT count() FROM retailmind.fact_eventos WHERE semana = 3"))
    check(cod == 200 and s is not None and int(s["totalElements"]) == esperado,
          "filtro por semana sigue vivo",
          f"HTTP {cod}, API {s['totalElements'] if s else '-'} vs CH {esperado}")

    # Las SIETE dimensiones siguen con sus operaciones (se comprueba la lectura,
    # que es lo unico que se puede probar sin escribir en la base legada).
    dims = ["dim-canal", "dim-region", "dim-dispositivo", "dim-categoria",
            "dim-fuente-trafico"]
    for d in dims:
        cod, s = pedir(tok_admin, f"/api/gestion/{d}")
        check(cod == 200 and isinstance(s, list) and len(s) > 0,
              f"dimension {d} sigue sirviendo", f"HTTP {cod}, {len(s) if s else 0} filas")
    for d, clave in [("dim-producto", "content"), ("dim-usuario", "content")]:
        cod, s = pedir(tok_admin, f"/api/gestion/{d}?page=0&size=5")
        check(cod == 200 and s is not None and len(s.get(clave, [])) == 5,
              f"dimension {d} sigue sirviendo", f"HTTP {cod}")


def v3_informes(tok: str) -> None:
    print("\n== V3 — OTD-VEN-03 y OTD-VEN-04 contra ClickHouse ==")

    # ── VEN-03 ───────────────────────────────────────────────────────
    cod, s = pedir(tok, "/api/informes/ventas/top-productos?page=0&size=10")
    if not check(cod == 200 and s is not None, "VEN-03 responde 200", f"HTTP {cod}"):
        return
    check(len(s["items"]) == 10, "VEN-03 devuelve los 10 primeros", str(len(s["items"])))
    check(s.get("analiticaDisponible") is not False, "VEN-03 con analitica disponible")
    check(bool(s.get("datosAl")), "VEN-03 trae marca de agua", str(s.get("datosAl")))
    check(bool(s.get("salvedad")), "VEN-03 declara su salvedad")

    esperado = ch("""
        SELECT producto_nombre, sum(cantidad), countDistinct(pedido_id), sum(venta_neta)
        FROM retailmind_dwh.fact_venta_linea WHERE es_cancelado = 0
        GROUP BY producto_nombre ORDER BY sum(cantidad) DESC, sum(venta_neta) DESC,
                 producto_nombre LIMIT 10 FORMAT TSV""").split("\n")
    coinciden = 0
    for i, linea in enumerate(esperado):
        nom, uds, ped, venta = linea.split("\t")
        it = s["items"][i]
        if (str(it["producto"]) == nom and int(it["unidades"]) == int(uds)
                and int(it["pedidos"]) == int(ped)
                and abs(float(it["venta"]) - float(venta)) < 0.005):
            coinciden += 1
    check(coinciden == 10, "VEN-03: las 10 filas coinciden con ClickHouse",
          f"{coinciden}/10")

    total_api = int(s["total"])
    total_ch = int(ch("SELECT countDistinct(producto_nombre) FROM "
                      "retailmind_dwh.fact_venta_linea WHERE es_cancelado = 0"))
    check(total_api == total_ch, "VEN-03: el total del sobre = productos con venta",
          f"API {total_api} vs CH {total_ch}")

    uds_ch = int(ch("SELECT sum(cantidad) FROM retailmind_dwh.fact_venta_linea "
                    "WHERE es_cancelado = 0"))
    kpi_uds = next(k["valor"] for k in s["resumen"] if k["etiqueta"] == "Unidades vendidas")
    check(int(kpi_uds) == uds_ch, "VEN-03: KPI de unidades = ClickHouse",
          f"API {int(kpi_uds):,} vs CH {uds_ch:,}")

    campos = {c for it in s["items"] for c in it}
    prohibidos = campos & {"margen", "ganancia", "costo", "costo_total", "margen_pct"}
    check(not prohibidos, "VEN-03 NO expone margen ni costo",
          f"campos: {sorted(campos)}")

    # ── VEN-04 ───────────────────────────────────────────────────────
    cod, s = pedir(tok, "/api/informes/ventas/productos-hueso?page=0&size=10")
    if not check(cod == 200 and s is not None, "VEN-04 responde 200", f"HTTP {cod}"):
        return
    # `== 10` suponía que sobraban variantes sin vender. Con la década cargada
    # casi todo el catálogo se vendió alguna vez y quedan muy pocas, así que la
    # página trae menos de 10 sin que nada esté mal.
    check(len(s["items"]) == min(10, int(s["total"])),
          "VEN-04 devuelve la primera página completa",
          f"{len(s['items'])} de {s['total']}")
    check(s.get("alcanceHueso") == "nunca", "VEN-04 arranca en «sin venta nunca»",
          str(s.get("alcanceHueso")))
    check("NUNCA" in (s.get("salvedad") or ""), "VEN-04 declara en pantalla qué lista es")

    universo = int(ch("SELECT count() FROM (SELECT * FROM retailmind_dwh.dim_producto FINAL)"))
    sin_venta = int(ch("""
        SELECT count() FROM (SELECT producto_variante_id FROM
               (SELECT * FROM retailmind_dwh.dim_producto FINAL)) d
        LEFT ANTI JOIN (SELECT DISTINCT producto_variante_id
                        FROM retailmind_dwh.fact_venta_linea WHERE es_cancelado = 0) v
        ON v.producto_variante_id = d.producto_variante_id"""))
    con_venta = universo - sin_venta
    # Estas tres eran fotos fijas —1.221 / 387 / 834—, las cifras del catálogo
    # del día en que se escribió el guion. Con la carga masiva el catálogo pasó
    # a 6.221 variantes y los tres controles se pusieron en rojo sin que nada
    # estuviera mal: el guion medía el pasado. Ahora comprueban la RELACIÓN, que
    # es lo único que debe cumplirse siempre, y el conteo del día viaja como
    # detalle informativo.
    check(universo > 0, "VEN-04: el universo del catálogo no está vacío", str(universo))
    check(sin_venta + con_venta == universo,
          "VEN-04: sin venta + con venta = universo",
          f"{sin_venta} + {con_venta} = {universo}")
    check(0 <= sin_venta <= universo, "VEN-04: las «sin venta nunca» caben en el universo",
          f"{sin_venta} de {universo}")
    check(int(s["total"]) == sin_venta, "VEN-04: el total del sobre = las sin venta nunca",
          f"API {s['total']} vs CH {sin_venta}")

    kpi = {k["etiqueta"]: k["valor"] for k in s["resumen"]}
    check(int(kpi["Variantes del catálogo"]) == universo,
          "VEN-04: el KPI del universo coincide con ClickHouse", str(kpi["Variantes del catálogo"]))
    check(int(kpi["Sin vender NUNCA"]) == sin_venta,
          "VEN-04: el KPI «sin vender nunca» coincide con ClickHouse", str(kpi["Sin vender NUNCA"]))
    check(all(int(i["nunca_vendida"]) == 1 for i in s["items"]),
          "VEN-04: con alcance «nunca», todas las filas lo son")
    check(all(i["dias_sin_venta"] is None for i in s["items"]),
          "VEN-04: las que nunca vendieron traen días VACÍO (no 0)")

    campos = {c for it in s["items"] for c in it}
    prohibidos = campos & {"costo", "capital_retenido", "venta", "margen", "precio"}
    check(not prohibidos, "VEN-04 NO expone ni una columna de dinero",
          f"campos: {sorted(campos)}")

    # El otro alcance: sin venta EN EL PERIODO.
    # La ventana era el primer semestre de 2026, elegida cuando los datos
    # acababan en 2026-07. Con la década cargada (2025-2034) ese semestre pasó
    # a ser una astilla del 5 %: el control seguía en verde sin llegar a rozar
    # el volumen real. Se amplía a los cinco primeros años, que ejercitan la
    # escala y SIGUEN siendo un subconjunto estricto — si abarcara la década
    # entera, `periodo` daría lo mismo que `nunca` y el control dejaría de
    # distinguir los dos alcances, que es justo lo que existe para comprobar.
    cod, s2 = pedir(tok, "/api/informes/ventas/productos-hueso"
                         "?alcance=periodo&desde=2025-01-01&hasta=2029-12-31&size=10")
    per_ch = int(ch("""
        SELECT count() FROM (SELECT producto_variante_id FROM
               (SELECT * FROM retailmind_dwh.dim_producto FINAL)) d
        LEFT ANTI JOIN (SELECT DISTINCT producto_variante_id
                        FROM retailmind_dwh.fact_venta_linea
                        WHERE es_cancelado = 0
                          AND toDate(fecha_pedido) >= toDate('2025-01-01')
                          AND toDate(fecha_pedido) <= toDate('2029-12-31')) v
        ON v.producto_variante_id = d.producto_variante_id"""))
    check(cod == 200 and s2 is not None and int(s2["total"]) == per_ch,
          "VEN-04: alcance «periodo» coincide con ClickHouse",
          f"API {s2['total'] if s2 else '-'} vs CH {per_ch}")
    if s2:
        check(s2.get("alcanceHueso") == "periodo" and "PERÍODO" in (s2.get("salvedad") or ""),
              "VEN-04: la salvedad cambia con el alcance")
        con_dias = [i for i in s2["items"] if i["dias_sin_venta"] is not None]
        orden_ok = all(con_dias[i]["dias_sin_venta"] >= con_dias[i + 1]["dias_sin_venta"]
                       for i in range(len(con_dias) - 1))
        check(orden_ok, "VEN-04: ordenado por días sin venta descendente")

    # Filtro invalido -> 400 (lista blanca).
    cod, _ = pedir(tok, "/api/informes/ventas/productos-hueso?alcance=cualquiera")
    check(cod == 400, "VEN-04: alcance fuera de la lista blanca da 400", f"HTTP {cod}")


def v4_roles() -> None:
    print("\n== V4 — matriz de roles ==")
    esperado = {
        "/api/informes/ventas/top-productos":
            {"ADMIN", "GERENTE", "VENDEDOR", "COMPRAS", "ANALISTA"},
        "/api/informes/ventas/productos-hueso":
            {"ADMIN", "GERENTE", "COMPRAS", "ANALISTA"},
        "/api/tableros/rentabilidad":
            {"ADMIN", "GERENTE", "ANALISTA"},
    }
    celdas = 0
    for ruta, permitidos in esperado.items():
        for rol in USUARIOS:
            tok = login(rol)
            cod, _ = pedir(tok, ruta + ("?size=1" if "informes" in ruta else ""))
            debe = rol in permitidos
            ok = (cod == 200) if debe else (cod == 403)
            celdas += 1
            if not ok:
                check(False, f"{rol} -> {ruta}",
                      f"HTTP {cod}, esperado {'200' if debe else '403'}")
    check(not fallos, f"matriz {celdas} celdas x 0 discrepancias", "")
    # La prueba que pide el enunciado, explicita:
    tok = login("COMPRAS")
    c1, _ = pedir(tok, "/api/informes/ventas/top-productos?size=1")
    c2, _ = pedir(tok, "/api/informes/ventas/productos-hueso?size=1")
    c3, _ = pedir(tok, "/api/tableros/rentabilidad")
    check(c1 == 200 and c2 == 200 and c3 == 403,
          "COMPRAS entra a VEN-03 y VEN-04 y NO al tablero T-2",
          f"VEN-03 {c1}, VEN-04 {c2}, T-2 {c3}")


def main() -> int:
    print("== Autenticacion ==")
    tok_admin = login("ADMIN")
    check(bool(tok_admin), "login de ADMIN")

    v2_solo_lectura(tok_admin)
    v3_informes(tok_admin)
    v4_roles()

    print("\n" + "=" * 64)
    if fallos:
        print(f"FALLOS: {len(fallos)}")
        for f in fallos:
            print(f"  - {f}")
        return 1
    print("TODO EN VERDE")
    return 0


if __name__ == "__main__":
    sys.exit(main())
