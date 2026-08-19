"""
p05_compuertas.py — Reglas de negocio y compuertas de estado (suite P05).

El ciclo de venta tiene siete saltos y cada uno está enforzado en el backend:
confirmado → pagado → facturado → en_preparacion → preparado → despachado →
entregado. Esta suite intenta **saltárselos** y exige un 409 con mensaje claro.

═══ POR QUÉ ESTA SUITE NO CORRE CONTRA LA BASE VIVA ═══

Las demás suites solo leen. Esta ESCRIBE: crea productos y pedidos, y los
empuja por el ciclo. Y aquí está la trampa que obliga a aislarla — **la prueba
de una compuerta solo es inocua si la compuerta funciona**. Si un guardia
faltara, la transición ilegal se ejecutaría de verdad, y sobre `retailmind`
eso sería un pedido corrupto entre 2.999.995 reales, imposible de distinguir.

Por eso corre contra **E0** (`retailmind_pruebas`, puerto 8082) y se planta si
detecta que la apuntan a otro sitio. El banco de datos se construye POR EL API,
no con INSERTs: así el camino de alta queda probado de paso, y si la creación
falla eso ya es un hallazgo.
"""

from __future__ import annotations

import os
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from comun.arnes import Cliente, Registro, esperar_api, sesiones           # noqa: E402
from comun.motor import entero                                             # noqa: E402

#: Puerto del backend de E0. La suite se NIEGA a correr contra otro.
API_E0 = "http://localhost:8082"


def _guardia_de_base(reg: Registro) -> bool:
    """
    Se niega a escribir si el API no es el de E0 o si la base tiene volumen de
    producción. Dos comprobaciones y no una: la variable de entorno dice a qué
    puerto apunta el arnés, pero solo el conteo dice qué hay detrás.
    """
    api = os.environ.get("RETAILMIND_API", "")
    if api.rstrip("/") != API_E0:
        reg.caso("P05-GUARDIA", "El API apuntado es el de pruebas", condicion=False,
                 severidad="S1", observado=f"RETAILMIND_API={api!r}",
                 esperado=f"{API_E0} — esta suite ESCRIBE y no toca la base viva")
        return False

    pedidos = entero("SELECT count(*) FROM pedido", base="retailmind_pruebas")
    if pedidos > 1000:
        reg.caso("P05-GUARDIA", "La base de pruebas no tiene volumen de producción",
                 condicion=False, severidad="S1",
                 observado=f"{pedidos} pedidos en retailmind_pruebas",
                 esperado="< 1000 — parece la base equivocada")
        return False
    return True


class Banco:
    """Datos mínimos para poder recorrer el ciclo, creados por el API."""

    def __init__(self, admin: Cliente, reg: Registro) -> None:
        self.admin = admin
        self.reg = reg
        self.marca_id: int | None = None
        self.categoria_id: int | None = None
        self.producto_id: int | None = None
        self.variante_id: int | None = None
        self.cliente_id: int | None = None
        self.bodega_id: int | None = None

    def _crear(self, caso: str, titulo: str, ruta: str, cuerpo: dict) -> dict | None:
        r = self.admin.pedir("POST", ruta, json=cuerpo)
        codigo = self.admin.codigo(r)
        ok = codigo in (200, 201)
        self.reg.caso(caso, titulo, condicion=ok, severidad="S2",
                      observado=f"HTTP {codigo} · {(r.text or '')[:160] if r is not None else 'sin respuesta'}",
                      esperado="200/201", reproducir=f"POST {ruta} {cuerpo}")
        if not ok:
            return None
        try:
            return r.json()
        except Exception:
            return {}

    def montar(self) -> bool:
        sello = str(int(time.time()))[-6:]

        d = self._crear("P05-BANCO", "Crear marca",
                        "/api/admin/catalogo/marcas",
                        {"nombre": f"Marca P05 {sello}", "slug": f"marca-p05-{sello}"})
        self.marca_id = (d or {}).get("id")

        d = self._crear("P05-BANCO", "Crear categoría",
                        "/api/admin/catalogo/categorias",
                        {"nombre": f"Cat P05 {sello}", "slug": f"cat-p05-{sello}"})
        self.categoria_id = (d or {}).get("id")

        d = self._crear("P05-BANCO", "Crear producto",
                        "/api/admin/catalogo/productos",
                        {"nombre": f"Producto P05 {sello}", "slug": f"prod-p05-{sello}",
                         "marcaId": self.marca_id, "publicado": True,
                         "categoriaIds": [self.categoria_id] if self.categoria_id else []})
        self.producto_id = (d or {}).get("id")
        if not self.producto_id:
            return False

        d = self._crear("P05-BANCO", "Crear variante con peso y precio válidos",
                        f"/api/admin/catalogo/productos/{self.producto_id}/variantes",
                        {"sku": f"SKU-P05-{sello}", "precio": 25.50, "costo": 10.00,
                         "pesoKg": 1.250, "esPredeterminada": True})
        self.variante_id = (d or {}).get("id")

        self.cliente_id = int(entero(
            "SELECT c.id FROM cliente c JOIN usuario u ON u.id=c.usuario_id "
            "WHERE u.email='maria.lopez@demo.com'", base="retailmind_pruebas"))
        self.bodega_id = int(entero("SELECT COALESCE(min(id), 0) FROM bodega",
                                    base="retailmind_pruebas"))

        self.reg.caso("P05-BANCO", "Hay cliente y bodega para el pedido",
                      condicion=bool(self.cliente_id) and self.bodega_id > 0,
                      severidad="S2",
                      observado=f"cliente={self.cliente_id} bodega={self.bodega_id}",
                      esperado="ambos > 0",
                      detalle="si no hay bodega, E0 no puede sostener un pedido: "
                              "es la familia V-g del plan (cadena sin cimientos)")
        if not self.variante_id or self.bodega_id <= 0:
            return False

        # Stock por la VÍA REAL: un ajuste de inventario, que escribe su
        # movimiento de kardex. Meterlo con un INSERT directo en `inventario`
        # dejaría el stock sin cadena y P06 lo denunciaría con razón — el banco
        # de pruebas no puede fabricar el estado que otra suite vigila.
        d = self._crear("P05-BANCO", "Dar stock inicial con un ajuste de inventario",
                        "/api/inventario/ajustes",
                        {"varianteId": self.variante_id, "bodegaId": self.bodega_id,
                         "tipo": "entrada", "cantidad": 50,
                         "motivo": "Carga inicial del banco de pruebas P05"})
        return d is not None


def correr(estado_datos: str = "E0") -> Registro:
    reg = Registro(estado_datos)
    if not esperar_api():
        reg.caso("P01-004", "El API de pruebas responde", condicion=False,
                 severidad="S1", observado="sin respuesta", esperado="HTTP < 500")
        return reg
    if not _guardia_de_base(reg):
        return reg

    admin = Cliente("ADMIN")
    if not admin.entrar():
        reg.caso("P02-001", "Login de ADMIN", condicion=False, severidad="S1",
                 observado=admin.error_login or "?", esperado="200")
        return reg

    banco = Banco(admin, reg)
    montado = banco.montar()

    # ─────────────────────────────────────────────────────────────────────────
    # P05-012 · el canal 'web' está RESERVADO al checkout del cliente
    # ─────────────────────────────────────────────────────────────────────────
    if montado:
        r = admin.pedir("POST", "/api/ventas/pedidos", json={
            "clienteId": banco.cliente_id, "bodegaId": banco.bodega_id,
            "canal": "web",
            "items": [{"varianteId": banco.variante_id, "cantidad": 1}]})
        codigo = admin.codigo(r)
        reg.caso("P05-012", "POST /pedidos con canal 'web' se rechaza",
                 condicion=codigo in (400, 409), severidad="S1",
                 observado=f"HTTP {codigo} · {(r.text or '')[:140] if r is not None else ''}",
                 esperado="400/409 — el pedido 'web' nace del checkout y ya PAGADO; "
                          "aceptarlo aquí crearía un pedido web sin pago")

    # ─────────────────────────────────────────────────────────────────────────
    # Un pedido interno, y los siete saltos intentados al revés
    # ─────────────────────────────────────────────────────────────────────────
    pedido_id = None
    if montado:
        r = admin.pedir("POST", "/api/ventas/pedidos", json={
            "clienteId": banco.cliente_id, "bodegaId": banco.bodega_id,
            "canal": "tienda",
            "items": [{"varianteId": banco.variante_id, "cantidad": 2}]})
        codigo = admin.codigo(r)
        cuerpo = {}
        try:
            cuerpo = r.json() if r is not None else {}
        except Exception:
            pass
        pedido_id = cuerpo.get("id") or cuerpo.get("pedidoId")
        reg.caso("P05-BANCO", "Crear pedido interno (canal 'tienda')",
                 condicion=codigo in (200, 201) and pedido_id is not None,
                 severidad="S2",
                 observado=f"HTTP {codigo} · {(r.text or '')[:200] if r is not None else ''}",
                 esperado="200/201 con id de pedido",
                 detalle="si falla por stock, es la familia V-g: E0 no tiene inventario")

    if pedido_id:
        # El pedido nace 'confirmado'. Todo salto adelantado debe dar 409.
        saltos = [
            ("P05-013", "Facturar sin haber pagado",
             "POST", f"/api/ventas/pedidos/{pedido_id}/factura", None),
            ("P05-010", "Preparar sin factura",
             "POST", f"/api/ventas/pedidos/{pedido_id}/preparacion", None),
            ("P05-010", "Marcar preparado sin haber empezado la preparación",
             "POST", f"/api/ventas/pedidos/{pedido_id}/preparado", None),
            ("P05-014", "Despachar sin estar preparado",
             "POST", f"/api/ventas/pedidos/{pedido_id}/despacho", {}),
            ("P05-010", "Entregar sin haber despachado",
             "POST", f"/api/ventas/pedidos/{pedido_id}/entrega", {}),
        ]
        for caso, titulo, metodo, ruta, cuerpo in saltos:
            r = admin.pedir(metodo, ruta, json=cuerpo)
            codigo = admin.codigo(r)
            reg.caso(caso, titulo, condicion=codigo == 409, severidad="S1",
                     observado=f"HTTP {codigo} · {(r.text or '')[:160] if r is not None else ''}",
                     esperado="409 con mensaje claro — el guardia de estado debe morder",
                     reproducir=f"{metodo} {ruta}")

        # P05-018 · el estado del pedido NO cambió con ninguno de los intentos.
        # Es la comprobación que convierte «devolvió 409» en «además no hizo
        # nada»: un guardia que rechaza DESPUÉS de escribir devuelve 409 igual.
        estado = entero(f"""SELECT count(*) FROM pedido p JOIN estado_pedido ep
                            ON ep.id = p.estado_pedido_id
                            WHERE p.id = {int(pedido_id)} AND ep.codigo = 'confirmado'""",
                        base="retailmind_pruebas")
        reg.caso("P05-010", "Tras los 5 saltos rechazados el pedido sigue en 'confirmado'",
                 condicion=estado == 1, severidad="S1",
                 observado=f"{'sigue confirmado' if estado == 1 else 'CAMBIÓ de estado'}",
                 esperado="sigue confirmado — rechazar tras escribir también devuelve 409")

        # P05-011 · cobro sobre un pedido de canal 'web' (no aplica aquí, es
        # 'tienda'); en su lugar se prueba la idempotencia del cobro doble.
        metodo_pago = entero("SELECT COALESCE(min(id),0) FROM metodo_pago",
                             base="retailmind_pruebas")
        if metodo_pago > 0:
            # El importe EXACTO, no redondeado: el guardia compara contra el
            # saldo pendiente al centavo y un `round()` lo convierte en un
            # sobrepago. (Lo detectó esta misma suite: 59 contra 58,65.)
            from comun.motor import escalar as _escalar
            total = _escalar(
                f"SELECT COALESCE(total,0)::numeric(12,2) FROM pedido WHERE id={int(pedido_id)}",
                base="retailmind_pruebas")
            r1 = admin.pedir("POST", f"/api/ventas/pedidos/{pedido_id}/pagos",
                             json={"metodoPagoId": metodo_pago, "monto": float(total),
                                   "referencia": "P05"})
            c1 = admin.codigo(r1)
            reg.caso("P05-018", "El cobro del pedido interno se acepta",
                     condicion=c1 in (200, 201), severidad="S2",
                     observado=f"HTTP {c1} · {(r1.text or '')[:140] if r1 is not None else ''}",
                     esperado="200/201")
            if c1 in (200, 201):
                r2 = admin.pedir("POST", f"/api/ventas/pedidos/{pedido_id}/pagos",
                                 json={"metodoPagoId": metodo_pago, "monto": float(total),
                                       "referencia": "P05-bis"})
                c2 = admin.codigo(r2)
                reg.caso("P05-018", "Cobrar dos veces el total se rechaza",
                         condicion=c2 in (400, 409), severidad="S1",
                         observado=f"HTTP {c2} · {(r2.text or '')[:140] if r2 is not None else ''}",
                         esperado="400/409 — cobrar de más no puede ser idempotente en silencio")

    # ─────────────────────────────────────────────────────────────────────────
    # P05-004 · aprobar una orden de compra es de GERENCIA
    # ─────────────────────────────────────────────────────────────────────────
    clientes = sesiones(["COMPRAS", "BODEGA", "VENDEDOR"])
    for rol, c in clientes.items():
        r = c.pedir("POST", "/api/compras/ordenes/1/aprobar", json={})
        codigo = c.codigo(r)
        reg.caso("P05-004", f"{rol} no puede aprobar una orden de compra",
                 condicion=codigo == 403, severidad="S1",
                 observado=f"HTTP {codigo}",
                 esperado="403 — aprobar es de GERENTE/ADMIN",
                 reproducir=c.curl("POST", "/api/compras/ordenes/1/aprobar"))

    # ─────────────────────────────────────────────────────────────────────────
    # P05-040 · reseñar exige compra verificada
    # ─────────────────────────────────────────────────────────────────────────
    cli = Cliente("CLIENTE")
    if cli.entrar():
        # OJO: tiene que ser un producto que el cliente NO haya comprado. El
        # de `banco` no sirve — es justo el del pedido que esta suite acaba de
        # crear Y PAGAR para el mismo cliente, así que la compra ESTÁ
        # verificada y el 201 sería correcto. (Primera versión de esta prueba:
        # acusó al sistema de no exigir compra, usando un producto comprado.)
        sello = str(int(time.time()))[-6:]
        otro = admin.pedir("POST", "/api/admin/catalogo/productos",
                           json={"nombre": f"No comprado {sello}",
                                 "slug": f"no-comprado-{sello}",
                                 "marcaId": banco.marca_id, "publicado": True})
        producto_ajeno = None
        try:
            producto_ajeno = otro.json().get("id") if admin.codigo(otro) in (200, 201) else None
        except Exception:
            pass

        if producto_ajeno:
            r = cli.pedir("POST", "/api/resenas",
                          json={"productoId": producto_ajeno, "calificacion": 5,
                                "titulo": "P05", "comentario": "sin haber comprado"})
            codigo = cli.codigo(r)
            reg.caso("P05-040", "Reseñar un producto NO comprado se rechaza",
                     condicion=codigo in (400, 409), severidad="S1",
                     observado=f"HTTP {codigo} · {(r.text or '')[:140] if r is not None else ''}",
                     esperado="409 «Solo puedes reseñar productos que has comprado»")

        # Y la otra dirección, que es la que demuestra que la regla no es un
        # «no» indiscriminado: el producto que SÍ compró debe poder reseñarse.
        if banco.producto_id:
            r = cli.pedir("POST", "/api/resenas",
                          json={"productoId": banco.producto_id, "calificacion": 4,
                                "titulo": "P05 comprado", "comentario": "compra verificada"})
            codigo = cli.codigo(r)
            reg.caso("P05-041", "Reseñar un producto SÍ comprado se acepta",
                     condicion=codigo in (200, 201, 409), severidad="S2",
                     observado=f"HTTP {codigo} · {(r.text or '')[:140] if r is not None else ''}",
                     esperado="201 (o 409 si ya la reseñó en esta misma corrida)")

    return reg


if __name__ == "__main__":
    estado = sys.argv[1] if len(sys.argv) > 1 else "E0"
    reg = correr(estado)
    print("\n" + "=" * 70)
    print(reg.resumen())
    print("informe:", reg.volcar("p05_compuertas"))
    sys.exit(1 if reg.fallos else 0)
