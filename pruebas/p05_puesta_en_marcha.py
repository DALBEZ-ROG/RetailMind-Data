"""
p05_puesta_en_marcha.py — ¿Se puede poner el sistema en marcha SIN tocar SQL?

Es la prueba que cierra el defecto **D-09**, y la que ninguna otra suite hace:
partiendo de una base VACÍA —sin una bodega, sin un transportista, sin una zona
ni una tarifa— recorre el camino completo de una instalación nueva **usando
solo la aplicación**, y termina creando un pedido de verdad.

Antes de la corrección esto era IMPOSIBLE. Las cinco tablas de la red logística
no tenían ni un endpoint de escritura en todo el backend (verificado: cero
`INSERT INTO` sobre ellas en `src/main`), así que la única forma de dejar el
sistema utilizable era ejecutar scripts de siembra a mano. Un cliente que
instalara RetailMind se quedaba en la puerta.

QUÉ SE EXIGE, en orden, porque el orden ES la prueba:

    1. bodega          →  sin ella no se puede crear ningún pedido
    2. transportista   →  sin él no hay método de envío
    3. método de envío →  cuelga del transportista
    4. zona de envío   →  resuelve la dirección del cliente
    5. tarifa          →  sin ella el checkout no calcula el flete
    6. marca · categoría · producto · variante  →  qué vender
    7. stock           →  por ajuste de inventario, con su kardex
    8. UN PEDIDO       →  la prueba de que todo lo anterior sirvió

Corre contra **E0** (`retailmind_pruebas`, puerto 8082) y ESCRIBE, así que
lleva el mismo guardia que P05: se planta si la apuntan a otro sitio.
"""

from __future__ import annotations

import os
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from comun.arnes import Cliente, Registro, esperar_api                     # noqa: E402
from comun.motor import entero                                             # noqa: E402

API_E0 = "http://localhost:8082"


def correr(estado_datos: str = "E0") -> Registro:
    reg = Registro(estado_datos)

    api = os.environ.get("RETAILMIND_API", "")
    if api.rstrip("/") != API_E0:
        reg.caso("P05-GUARDIA", "El API apuntado es el de pruebas", condicion=False,
                 severidad="S1", observado=f"RETAILMIND_API={api!r}",
                 esperado=f"{API_E0} — esta suite ESCRIBE y no toca la base viva")
        return reg
    if entero("SELECT count(*) FROM pedido", base="retailmind_pruebas") > 1000:
        reg.caso("P05-GUARDIA", "La base de pruebas no tiene volumen de producción",
                 condicion=False, severidad="S1",
                 observado="parece la base equivocada", esperado="< 1000 pedidos")
        return reg

    if not esperar_api():
        reg.caso("P01-004", "El API de pruebas responde", condicion=False,
                 severidad="S1", observado="sin respuesta", esperado="HTTP < 500")
        return reg

    admin = Cliente("ADMIN")
    if not admin.entrar():
        reg.caso("P02-001", "Login de ADMIN", condicion=False, severidad="S1",
                 observado=admin.error_login or "?", esperado="200")
        return reg

    # ── El punto de partida: la instalación está VIRGEN ──────────────────
    vacio = {t: entero(f"SELECT count(*) FROM {t}", base="retailmind_pruebas")
             for t in ("bodega", "transportista", "metodo_envio", "zona_envio", "tarifa_envio")}
    reg.caso("P05-D09", "Se parte de una instalación sin red logística",
             condicion=all(n == 0 for n in vacio.values()), severidad="S2",
             observado=", ".join(f"{k}={v}" for k, v in vacio.items()),
             esperado="todo en 0 — si no, la prueba no demuestra nada")

    sello = str(int(time.time()))[-6:]
    creado: dict[str, int] = {}

    def paso(caso: str, titulo: str, metodo: str, ruta: str, cuerpo: dict,
             clave: str | None = None) -> bool:
        r = admin.pedir(metodo, ruta, json=cuerpo)
        codigo = admin.codigo(r)
        ok = codigo in (200, 201)
        cuerpo_resp = {}
        if ok:
            try:
                cuerpo_resp = r.json()
            except Exception:
                pass
            if clave and cuerpo_resp.get("id"):
                creado[clave] = cuerpo_resp["id"]
        reg.caso(caso, titulo, condicion=ok, severidad="S2",
                 observado=f"HTTP {codigo} · {(r.text or '')[:150] if r is not None else 'sin respuesta'}",
                 esperado="200/201 desde la aplicación, sin tocar SQL",
                 reproducir=f"{metodo} {ruta}")
        return ok

    # ── 1-5 · la red logística, íntegra, por el API ──────────────────────
    paso("P05-D09", "1· Crear la primera BODEGA desde la aplicación",
         "POST", "/api/admin/red/bodegas",
         {"codigo": f"BOD-{sello}", "nombre": "Bodega Central",
          "direccion": "Av. Principal 100", "telefono": "052760000",
          "esPrincipal": True}, "bodega")

    paso("P05-D09", "2· Contratar un TRANSPORTISTA",
         "POST", "/api/admin/red/transportistas",
         {"nombre": f"Transportes Demo {sello}", "ruc": "0999999999001",
          "telefono": "0999999999", "email": "envios@demo.test"}, "transportista")

    paso("P05-D09", "3· Definir un MÉTODO DE ENVÍO del transportista",
         "POST", "/api/admin/red/metodos",
         {"codigo": f"STD-{sello}", "nombre": "Estándar",
          "transportistaId": creado.get("transportista"),
          "diasEntregaMin": 2, "diasEntregaMax": 5, "orden": 1}, "metodo")

    pais_id = entero("SELECT min(id) FROM pais", base="retailmind_pruebas")
    paso("P05-D09", "4· Declarar una ZONA DE ENVÍO",
         "POST", "/api/admin/red/zonas",
         {"nombre": "Nacional", "paisId": pais_id,
          "descripcion": "Cobertura de todo el país"}, "zona")

    paso("P05-D09", "5· Fijar la TARIFA de esa zona",
         "POST", "/api/admin/red/tarifas",
         {"zonaEnvioId": creado.get("zona"), "metodoEnvioId": creado.get("metodo"),
          "costoBase": 4.50, "costoPorKg": 0.55, "pesoMinKg": 0}, "tarifa")

    # ── 6-7 · qué vender y cuánto hay ───────────────────────────────────
    paso("P05-D09", "6a· Crear una marca", "POST", "/api/admin/catalogo/marcas",
         {"nombre": f"Marca {sello}", "slug": f"marca-{sello}"}, "marca")
    paso("P05-D09", "6b· Crear una categoría", "POST", "/api/admin/catalogo/categorias",
         {"nombre": f"Categoria {sello}", "slug": f"cat-{sello}"}, "categoria")
    paso("P05-D09", "6c· Crear un producto", "POST", "/api/admin/catalogo/productos",
         {"nombre": f"Producto {sello}", "slug": f"prod-{sello}",
          "marcaId": creado.get("marca"), "publicado": True,
          "categoriaIds": [creado["categoria"]] if "categoria" in creado else []}, "producto")

    if "producto" in creado:
        paso("P05-D09", "6d· Crear una variante con peso y precio",
             "POST", f"/api/admin/catalogo/productos/{creado['producto']}/variantes",
             {"sku": f"SKU-{sello}", "precio": 19.90, "costo": 8.00,
              "pesoKg": 0.850, "esPredeterminada": True}, "variante")

    if "variante" in creado and "bodega" in creado:
        paso("P05-D09", "7· Cargar stock con un ajuste de inventario",
             "POST", "/api/inventario/ajustes",
             {"varianteId": creado["variante"], "bodegaId": creado["bodega"],
              "tipo": "entrada", "cantidad": 25,
              "motivo": "Carga inicial de la puesta en marcha"})

    # ── 8 · LA PRUEBA: un pedido real ───────────────────────────────────
    cliente_id = entero("SELECT c.id FROM cliente c JOIN usuario u ON u.id=c.usuario_id "
                        "WHERE u.email='maria.lopez@demo.com'", base="retailmind_pruebas")
    pedido_ok = False
    if "variante" in creado and "bodega" in creado and cliente_id > 0:
        r = admin.pedir("POST", "/api/ventas/pedidos", json={
            "clienteId": cliente_id, "bodegaId": creado["bodega"], "canal": "tienda",
            "items": [{"varianteId": creado["variante"], "cantidad": 2}]})
        codigo = admin.codigo(r)
        pedido_ok = codigo in (200, 201)
        reg.caso("P05-D09", "8· CREAR UN PEDIDO — el sistema quedó operativo",
                 condicion=pedido_ok, severidad="S1",
                 observado=f"HTTP {codigo} · {(r.text or '')[:180] if r is not None else ''}",
                 esperado="201 — es lo que era IMPOSIBLE antes de cerrar D-09",
                 detalle="sin bodega el pedido se rechazaba, y no había forma de "
                         "crear una bodega desde la aplicación")

    # ── El veredicto ────────────────────────────────────────────────────
    red = {t: entero(f"SELECT count(*) FROM {t}", base="retailmind_pruebas")
           for t in ("bodega", "transportista", "metodo_envio", "zona_envio",
                     "tarifa_envio", "pedido")}
    reg.caso("P05-D09", "D-09 CERRADO: instalación operativa sin ejecutar una línea de SQL",
             condicion=pedido_ok and all(red[t] > 0 for t in red), severidad="S1",
             observado=", ".join(f"{k}={v}" for k, v in red.items()),
             esperado="las cinco tablas pobladas y al menos un pedido, todo por la aplicación")

    return reg


if __name__ == "__main__":
    estado = sys.argv[1] if len(sys.argv) > 1 else "E0"
    reg = correr(estado)
    print("\n" + "=" * 70)
    print(reg.resumen())
    print("informe:", reg.volcar("p05_puesta_en_marcha"))
    sys.exit(1 if reg.fallos else 0)
