"""Verificación de los TABLEROS DE DIRECCIÓN contra PostgreSQL (fases E1-A y E1-B).

Compara cada cifra que un tablero PUBLICA —tomada de la RESPUESTA HTTP, no de
una consulta a ClickHouse escrita a mano— contra la consulta equivalente sobre
la base transaccional. Es la misma disciplina que `validar_dwh.py` aplica a las
tablas del almacén, un escalón más arriba: allí se valida la CARGA, aquí se
valida lo que el gerente ve en pantalla.

Por qué la cifra se toma de la respuesta HTTP y no de ClickHouse: entre la tabla
del almacén y la pantalla hay un agregado, un filtro y un formateo, y es
exactamente ahí donde se pierde una fila o se cuela un JOIN que multiplica. Un
control que consultara el almacén por su cuenta daría luz verde a un tablero
roto.

Conexión a PostgreSQL: SOLO con el rol `retailmind_etl`, que es de lectura en
cuatro capas y tiene BYPASSRLS (sin él, `pol_horario` —declarada con `cmd = ALL`,
y ALL incluye SELECT— devolvería CERO FILAS en silencio y todos los controles
darían diferencia sin que nada estuviera mal).

Uso:
    py validar_tableros.py                  # los siete tableros
    py validar_tableros.py --tablero T-1
    py validar_tableros.py --detalle
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from decimal import Decimal
from typing import Any, Callable

import psycopg2

API = os.environ.get("RETAILMIND_API", "http://localhost:8080")
USUARIO = os.environ.get("RETAILMIND_USER", "admin@retailmind.com")
CLAVE = os.environ.get("RETAILMIND_PASS", "Admin2026!")

PG = dict(
    host=os.environ.get("ETL_PG_HOST", "localhost"),
    port=int(os.environ.get("ETL_PG_PORT", "5432")),
    dbname=os.environ.get("ETL_PG_DB", "retailmind"),
    user=os.environ.get("ETL_PG_USER", "retailmind_etl"),
    password=os.environ.get("ETL_PG_PASSWORD", "Etl2026!"),
)

# Un centavo de tolerancia sobre el dinero, cero sobre los conteos: el redondeo
# de un porcentaje no es una diferencia, una fila de más sí lo es.
TOL = Decimal("0.01")


# ── Acceso ───────────────────────────────────────────────────────────────────

def token() -> str:
    datos = json.dumps({"username": USUARIO, "password": CLAVE}).encode()
    pet = urllib.request.Request(
        f"{API}/api/auth/login", data=datos,
        headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(pet, timeout=30) as r:
        return json.load(r)["token"]


def tablero(clave: str, jwt: str) -> dict:
    pet = urllib.request.Request(
        f"{API}/api/tableros/{clave}", headers={"Authorization": f"Bearer {jwt}"})
    with urllib.request.urlopen(pet, timeout=180) as r:
        return json.load(r)


def bloque(sobre: dict, ident: str) -> dict:
    for b in sobre.get("bloques", []):
        if b["id"] == ident:
            return b
    raise AssertionError(f"El tablero no trae el bloque «{ident}»")


def kpi(sobre: dict, etiqueta: str) -> Any:
    for k in sobre.get("kpis", []):
        if k["etiqueta"].startswith(etiqueta):
            return k["valor"]
    raise AssertionError(f"El tablero no trae el KPI «{etiqueta}»")


def suma(b: dict, campo: str) -> Decimal:
    return sum((Decimal(str(f[campo] or 0)) for f in b["items"]), Decimal(0))


# ── Motor de control ─────────────────────────────────────────────────────────

class Resultado:
    def __init__(self) -> None:
        self.ok = 0
        self.mal = 0
        self.fallos: list[str] = []

    def comprobar(self, nombre: str, api: Any, pg: Any, detalle: bool) -> None:
        a, b = Decimal(str(api)), Decimal(str(pg))
        delta = a - b
        bien = abs(delta) <= TOL
        marca = "OK " if bien else "MAL"
        if bien:
            self.ok += 1
        else:
            self.mal += 1
            self.fallos.append(nombre)
        if not bien or detalle:
            print(f"  [{marca}] {nombre}")
            print(f"         API = {a}   PostgreSQL = {b}   Delta = {delta}")


def uno(cur, sql: str, *args) -> Any:
    cur.execute(sql, args)
    fila = cur.fetchone()
    return fila[0] if fila and fila[0] is not None else 0


# ═════════════════════════════════════════════════════════════════════════════
# T-1 · OMNICANAL
# ═════════════════════════════════════════════════════════════════════════════

def validar_t1(cur, jwt: str, r: Resultado, detalle: bool) -> None:
    print("\n=== T-1 · Tablero Omnicanal ===")
    s = tablero("omnicanal", jwt)

    # KPI: venta del período y ticket medio.
    pedidos = uno(cur, """
        SELECT count(*) FROM pedido p JOIN estado_pedido ep ON ep.id = p.estado_pedido_id
        WHERE ep.codigo <> 'cancelado'""")
    venta = uno(cur, """
        SELECT sum(p.total) FROM pedido p JOIN estado_pedido ep ON ep.id = p.estado_pedido_id
        WHERE ep.codigo <> 'cancelado'""")
    r.comprobar("T-1 KPI venta del período", kpi(s, "Venta del período"), venta, detalle)
    r.comprobar("T-1 KPI ticket medio", kpi(s, "Ticket medio"),
                round(Decimal(venta) / pedidos, 2), detalle)

    # Bloque 1: la serie por canal cuadra con el total y con cada canal.
    b1 = bloque(s, "participacion_canal")
    r.comprobar("T-1 participación · pedidos del bloque", suma(b1, "pedidos"), pedidos, detalle)
    r.comprobar("T-1 participación · venta del bloque", suma(b1, "venta"), venta, detalle)
    for canal in ("web", "tienda", "telefono"):
        n = uno(cur, """
            SELECT count(*) FROM pedido p JOIN estado_pedido ep ON ep.id = p.estado_pedido_id
            WHERE ep.codigo <> 'cancelado' AND p.canal = %s""", canal)
        v = uno(cur, """
            SELECT sum(p.total) FROM pedido p JOIN estado_pedido ep ON ep.id = p.estado_pedido_id
            WHERE ep.codigo <> 'cancelado' AND p.canal = %s""", canal)
        api_n = sum(Decimal(str(f["pedidos"])) for f in b1["items"] if f["canal"] == canal)
        api_v = sum(Decimal(str(f["venta"])) for f in b1["items"] if f["canal"] == canal)
        r.comprobar(f"T-1 participación · pedidos {canal}", api_n, n, detalle)
        r.comprobar(f"T-1 participación · venta {canal}", api_v, v, detalle)

    # Bloque 3: cliente omnicanal. Las tres cestas son excluyentes y suman.
    b3 = bloque(s, "cliente_omnicanal")
    omni = uno(cur, """
        SELECT count(*) FROM (
            SELECT p.cliente_id FROM pedido p
            JOIN estado_pedido ep ON ep.id = p.estado_pedido_id
            WHERE ep.codigo <> 'cancelado'
            GROUP BY p.cliente_id
            HAVING bool_or(p.canal = 'web') AND bool_or(p.canal <> 'web')) t""")
    activos = uno(cur, """
        SELECT count(DISTINCT p.cliente_id) FROM pedido p
        JOIN estado_pedido ep ON ep.id = p.estado_pedido_id
        WHERE ep.codigo <> 'cancelado'""")
    r.comprobar("T-1 KPI clientes omnicanales", kpi(s, "Clientes omnicanales"), omni, detalle)
    r.comprobar("T-1 semáforo · las tres cestas suman los clientes activos",
                suma(b3, "clientes"), activos, detalle)
    r.comprobar("T-1 semáforo · cesta omnicanal",
                b3["items"][0]["clientes"], omni, detalle)

    # Bloque 4: el embudo, paso a paso, con la lógica «alcanzó este hito o uno
    # posterior» — la misma que el tablero aplica, replicada aquí a mano.
    hitos = """
        WITH h AS (
            SELECT hp.pedido_id,
                   min(hp.fecha_creacion) FILTER (WHERE e.codigo = 'pagado')     AS f_pag,
                   min(hp.fecha_creacion) FILTER (WHERE e.codigo = 'facturado')  AS f_fac,
                   min(hp.fecha_creacion) FILTER (WHERE e.codigo = 'despachado') AS f_des,
                   min(hp.fecha_creacion) FILTER (WHERE e.codigo = 'entregado')  AS f_ent
            FROM historial_estado_pedido hp
            JOIN estado_pedido e ON e.id = hp.estado_pedido_id
            GROUP BY hp.pedido_id)
        SELECT %s FROM pedido p LEFT JOIN h ON h.pedido_id = p.id
    """
    esperado = {
        "Pedido creado": "count(*)",
        "Cobrado": ("count(*) FILTER (WHERE h.f_pag IS NOT NULL OR h.f_fac IS NOT NULL "
                    "OR h.f_des IS NOT NULL OR h.f_ent IS NOT NULL)"),
        "Facturado": ("count(*) FILTER (WHERE h.f_fac IS NOT NULL OR h.f_des IS NOT NULL "
                      "OR h.f_ent IS NOT NULL)"),
        "Despachado": "count(*) FILTER (WHERE h.f_des IS NOT NULL OR h.f_ent IS NOT NULL)",
        "Entregado": "count(*) FILTER (WHERE h.f_ent IS NOT NULL)",
    }
    b4 = bloque(s, "embudo")
    anterior = None
    for fila in b4["items"]:
        expr = esperado[fila["paso"]]
        cur.execute(hitos.replace("%s", expr))
        n = cur.fetchone()[0]
        r.comprobar(f"T-1 embudo · {fila['paso']}", fila["pedidos"], n, detalle)
        # El embudo tiene que ser monótono: si un paso sube respecto del
        # anterior, la serie no es un embudo y la pantalla miente por forma.
        if anterior is not None and fila["pedidos"] > anterior:
            r.mal += 1
            r.fallos.append(f"T-1 embudo NO monótono en «{fila['paso']}»")
            print(f"  [MAL] T-1 embudo NO monótono en «{fila['paso']}»: "
                  f"{fila['pedidos']} > {anterior}")
        else:
            r.ok += 1
        anterior = fila["pedidos"]

    # Bloque 5: cobros rechazados. La fecha es la del INTENTO (C2.1).
    b5 = bloque(s, "cobros_fallidos")
    fallidos = uno(cur, "SELECT count(*) FROM pago WHERE estado = 'fallido'")
    r.comprobar("T-1 cobros rechazados · total", suma(b5, "intentos"), fallidos, detalle)
    # El motivo NO es una columna de `pago`: vive en el jsonb de la transacción,
    # y en crudo hay SEIS valores donde el negocio tiene cinco («tarjeta
    # rechazada» aparece como código y como frase). El ETL normaliza en Python
    # —para poder registrar un valor no previsto en vez de silenciarlo—, así
    # que el control se hace sobre el conteo por motivo ya normalizado.
    cur.execute("""
        SELECT lower(trim(coalesce(tp.respuesta_pasarela->>'motivo', ''))) AS crudo,
               count(*)
        FROM pago pg
        JOIN transaccion_pago tp ON tp.pago_id = pg.id
        WHERE pg.estado = 'fallido'
        GROUP BY 1""")
    crudos = dict(cur.fetchall())
    esperado_motivos: dict[str, int] = {}
    for crudo, n in crudos.items():
        clave = "tarjeta_rechazada" if "rechazad" in crudo else crudo
        esperado_motivos[clave] = esperado_motivos.get(clave, 0) + n
    r.comprobar("T-1 cobros rechazados · motivos distintos tras normalizar",
                len({f["motivo"] for f in b5["items"]}), len(esperado_motivos), detalle)
    for motivo, n in sorted(esperado_motivos.items()):
        api_n = sum(Decimal(str(f["intentos"])) for f in b5["items"]
                    if f["motivo"] == motivo)
        r.comprobar(f"T-1 cobros rechazados · {motivo}", api_n, n, detalle)
    intentos = uno(cur, "SELECT count(*) FROM pago")
    r.comprobar("T-1 KPI tasa de rechazo", kpi(s, "Rechazo del cobro"),
                round(Decimal(fallidos) * 100 / intentos, 2), detalle)

    # Bloque 6: mezcla de pago sobre los cobros COMPLETADOS.
    b6 = bloque(s, "mezcla_pago")
    cobrados = uno(cur, "SELECT count(*) FROM pago WHERE estado = 'completado'")
    monto = uno(cur, "SELECT sum(monto) FROM pago WHERE estado = 'completado'")
    r.comprobar("T-1 mezcla de pago · cobros", suma(b6, "cobros"), cobrados, detalle)
    r.comprobar("T-1 mezcla de pago · monto", suma(b6, "monto"), monto, detalle)


# ═════════════════════════════════════════════════════════════════════════════
# T-2 · RENTABILIDAD Y ROTACIÓN
# ═════════════════════════════════════════════════════════════════════════════

#: La línea de venta tal como la define el modelo: bruto − promoción − cupón
#: prorrateado, y el costo contra el costo VIGENTE de la variante.
_LINEA = """
    WITH factura_canonica AS (
        SELECT DISTINCT ON (fv.pedido_id) fv.id, fv.pedido_id
        FROM factura_venta fv
        WHERE fv.estado <> 'anulada'
        ORDER BY fv.pedido_id, fv.fecha_emision DESC, fv.id DESC
    ),
    con_descuento AS (
        SELECT p.id AS pedido_id
        FROM pedido p
        LEFT JOIN (SELECT pedido_id, sum(monto_descuento) AS d
                   FROM pedido_detalle GROUP BY pedido_id) dl ON dl.pedido_id = p.id
        WHERE p.monto_descuento > 0 OR COALESCE(dl.d, 0) > 0
    ),
    linea AS (
        SELECT pd.id,
               pd.pedido_id,
               pd.producto_variante_id,
               pd.cantidad,
               pd.cantidad * pd.precio_unitario                    AS bruto,
               pd.monto_descuento                                  AS promocion,
               -- El cupón se DESPEJA: el descuento de la línea de FACTURA ya
               -- incluye la promoción, así que el cupón prorrateado es la
               -- diferencia. Tomar `fvd.monto_descuento` a secas contaría la
               -- promoción dos veces —una en su capa y otra dentro del cupón—
               -- y el descuento total saldría inflado sin que ninguna suma
               -- dejara de cuadrar consigo misma.
               CASE WHEN fvd.monto_descuento IS NULL THEN 0::numeric
                    ELSE GREATEST(fvd.monto_descuento - pd.monto_descuento, 0) END AS cupon,
               pd.cantidad * pv.costo                              AS costo,
               (fvd.monto_descuento IS NULL AND cd.pedido_id IS NOT NULL) AS excepcion
        FROM pedido_detalle pd
        JOIN pedido p             ON p.id  = pd.pedido_id
        JOIN estado_pedido ep     ON ep.id = p.estado_pedido_id
        JOIN producto_variante pv ON pv.id = pd.producto_variante_id
        LEFT JOIN factura_canonica fc  ON fc.pedido_id = p.id
        LEFT JOIN factura_venta_detalle fvd
               ON fvd.factura_venta_id = fc.id AND fvd.pedido_detalle_id = pd.id
        LEFT JOIN con_descuento cd ON cd.pedido_id = p.id
        WHERE ep.codigo <> 'cancelado'
    )
"""


def validar_t2(cur, jwt: str, r: Resultado, detalle: bool) -> None:
    print("\n=== T-2 · Tablero de Rentabilidad y Rotación ===")
    s = tablero("rentabilidad", jwt)

    cur.execute(_LINEA + """
        SELECT count(*), sum(cantidad), sum(bruto), sum(promocion), sum(cupon),
               sum(bruto - promocion - cupon), sum(costo),
               sum(bruto - promocion - cupon - costo)
        FROM linea""")
    (lineas, unidades, bruto, promo, cupon, neta, costo, margen) = cur.fetchone()

    r.comprobar("T-2 KPI venta neta", kpi(s, "Venta neta"), neta, detalle)
    r.comprobar("T-2 KPI margen", kpi(s, "Margen"), margen, detalle)
    r.comprobar("T-2 KPI margen sobre la venta", kpi(s, "Margen sobre la venta"),
                round(Decimal(margen) * 100 / Decimal(neta), 2), detalle)
    r.comprobar("T-2 KPI descuento entregado", kpi(s, "Descuento entregado"),
                Decimal(promo) + Decimal(cupon), detalle)

    b1 = bloque(s, "margen_categoria")
    r.comprobar("T-2 margen por categoría · unidades", suma(b1, "unidades"), unidades, detalle)
    r.comprobar("T-2 margen por categoría · venta neta", suma(b1, "venta_neta"), neta, detalle)
    r.comprobar("T-2 margen por categoría · costo", suma(b1, "costo"), costo, detalle)
    r.comprobar("T-2 margen por categoría · margen", suma(b1, "margen"), margen, detalle)

    b4 = bloque(s, "descuento_mes")
    r.comprobar("T-2 descuento · capa de promoción",
                suma(b4, "descuento_promocion"), promo, detalle)
    r.comprobar("T-2 descuento · capa de cupón", suma(b4, "descuento_cupon"), cupon, detalle)
    r.comprobar("T-2 descuento · venta bruta", suma(b4, "venta_bruta"), bruto, detalle)

    # C1.2 — las líneas con el cupón sin prorratear se cuentan APARTE.
    cur.execute(_LINEA + """
        SELECT count(*), count(DISTINCT pedido_id) FROM linea WHERE excepcion""")
    lin_exc, ped_exc = cur.fetchone()
    r.comprobar("T-2 excepciones de descuento · líneas", b4["excepciones"], lin_exc, detalle)
    r.comprobar("T-2 excepciones de descuento · pedidos",
                b4["pedidosExcepcion"], ped_exc, detalle)
    r.comprobar("T-2 excepciones · suma de la columna del bloque",
                suma(b4, "lineas_excepcion"), lin_exc, detalle)

    # Matriz margen × rotación: exactamente las variantes CON venta.
    b2 = bloque(s, "matriz_margen_rotacion")
    con_venta = uno(cur, _LINEA + """
        SELECT count(DISTINCT producto_variante_id) FROM linea""")
    r.comprobar("T-2 matriz · variantes con venta", b2["filas"], con_venta, detalle)
    r.comprobar("T-2 matriz · venta neta de la nube", suma(b2, "venta_neta"), neta, detalle)

    # Producto hueso: el complemento exacto del catálogo.
    b3 = bloque(s, "producto_hueso")
    catalogo = uno(cur, "SELECT count(*) FROM producto_variante")
    sin_venta = uno(cur, _LINEA + """
        SELECT count(*) FROM producto_variante v
        WHERE NOT EXISTS (SELECT 1 FROM linea l
                          WHERE l.producto_variante_id = v.id)""")
    r.comprobar("T-2 producto hueso · variantes sin venta", b3["filas"], sin_venta, detalle)
    r.comprobar("T-2 producto hueso + matriz = catálogo",
                b3["filas"] + b2["filas"], catalogo, detalle)

    # Capital inmovilizado al cierre: la existencia REAL de la bodega.
    b6 = bloque(s, "capital_mensual")
    stock_hoy = uno(cur, """
        SELECT sum(i.stock_actual * pv.costo)
        FROM inventario i JOIN producto_variante pv ON pv.id = i.producto_variante_id""")
    ultimo = b6["items"][-1]
    r.comprobar("T-2 capital al cierre = inventario vigente valorizado",
                ultimo["capital"], stock_hoy, detalle)
    r.comprobar("T-2 KPI capital al cierre", kpi(s, "Capital inmovilizado"),
                stock_hoy, detalle)
    unidades_hoy = uno(cur, "SELECT sum(stock_actual) FROM inventario")
    r.comprobar("T-2 capital · unidades del último cierre",
                ultimo["unidades"], unidades_hoy, detalle)

    # La variación del PRIMER mes va nula, nunca cero (la trampa de INV-09).
    if b6["items"][0]["variacion_pct"] is None:
        r.ok += 1
    else:
        r.mal += 1
        r.fallos.append("T-2 el primer mes de la serie de capital trae variación")
        print("  [MAL] T-2 el primer mes de capital debería ir con variación NULA, "
              f"y trae {b6['items'][0]['variacion_pct']}")


# ═════════════════════════════════════════════════════════════════════════════
# T-3 · CLIENTE Y POSVENTA
# ═════════════════════════════════════════════════════════════════════════════

def validar_t3(cur, jwt: str, r: Resultado, detalle: bool) -> None:
    print("\n=== T-3 · Tablero de Cliente y Posventa ===")
    s = tablero("cliente-posventa", jwt)

    activos = uno(cur, """
        SELECT count(DISTINCT p.cliente_id) FROM pedido p
        JOIN estado_pedido ep ON ep.id = p.estado_pedido_id
        WHERE ep.codigo <> 'cancelado'""")
    venta = uno(cur, """
        SELECT sum(p.total) FROM pedido p JOIN estado_pedido ep ON ep.id = p.estado_pedido_id
        WHERE ep.codigo <> 'cancelado'""")
    r.comprobar("T-3 KPI clientes activos", kpi(s, "Clientes activos"), activos, detalle)

    # Pareto: un cliente por fila, y el acumulado termina exactamente en 100 %.
    b1 = bloque(s, "pareto_clientes")
    r.comprobar("T-3 Pareto · clientes", b1["filas"], activos, detalle)
    r.comprobar("T-3 Pareto · venta acumulada", suma(b1, "venta"), venta, detalle)
    r.comprobar("T-3 Pareto · el acumulado cierra en 100 %",
                b1["items"][-1]["acumulado_pct"], 100, detalle)

    # El top 20 % de clientes, calculado igual que el tablero: ceil(0,2 · n).
    corte = -(-activos * 20 // 100)
    venta_top = uno(cur, """
        SELECT sum(v) FROM (
            SELECT sum(p.total) AS v FROM pedido p
            JOIN estado_pedido ep ON ep.id = p.estado_pedido_id
            WHERE ep.codigo <> 'cancelado'
            GROUP BY p.cliente_id ORDER BY v DESC LIMIT %s) t""", corte)
    r.comprobar("T-3 KPI ingreso del top 20 %", kpi(s, "Ingreso del top 20"),
                round(Decimal(venta_top) * 100 / Decimal(venta), 2), detalle)

    # Nuevo contra recurrente: la suma de las dos cestas es la venta entera, y
    # los clientes nuevos de toda la serie son TODOS los clientes con pedido.
    b2 = bloque(s, "nuevo_recurrente")
    r.comprobar("T-3 nuevo/recurrente · venta total",
                suma(b2, "venta_nuevos") + suma(b2, "venta_recurrentes"), venta, detalle)
    r.comprobar("T-3 nuevo/recurrente · cada cliente es nuevo UNA vez",
                suma(b2, "clientes_nuevos"), activos, detalle)

    # Tickets: C4.3 (sin_categoria es una fila más) y C4.5 (resuelto ≠ cerrado).
    b3 = bloque(s, "tickets_categoria")
    tickets = uno(cur, "SELECT count(*) FROM ticket_soporte")
    cerrados = uno(cur, "SELECT count(*) FROM ticket_soporte WHERE estado = 'cerrado'")
    resueltos = uno(cur, "SELECT count(*) FROM ticket_soporte WHERE estado = 'resuelto'")
    sin_cat = uno(cur, "SELECT count(*) FROM ticket_soporte WHERE categoria_ticket_id IS NULL")
    r.comprobar("T-3 tickets · total", suma(b3, "tickets"), tickets, detalle)
    r.comprobar("T-3 tickets · cerrados", suma(b3, "cerrados"), cerrados, detalle)
    r.comprobar("T-3 tickets · resueltos SIN cerrar", suma(b3, "resueltos"), resueltos, detalle)
    r.comprobar("T-3 tickets · la base de los tiempos son los cerrados",
                suma(b3, "base_tiempos"), cerrados, detalle)
    fila_sin_cat = [f for f in b3["items"] if f["categoria"] == "sin_categoria"]
    r.comprobar("T-3 tickets · el ticket sin clasificar es una fila propia (C4.3)",
                fila_sin_cat[0]["tickets"] if fila_sin_cat else 0, sin_cat, detalle)

    # Devoluciones.
    b4 = bloque(s, "devolucion_producto")
    dev_uds = uno(cur, "SELECT sum(cantidad) FROM devolucion_detalle")
    dev_lin = uno(cur, "SELECT count(*) FROM devolucion_detalle")
    # `devolucion_detalle` NO guarda la variante: se llega a ella por
    # `pedido_detalle`. Un JOIN «obvio» por producto no existe aqui.
    r.comprobar("T-3 devoluciones · unidades", suma(b4, "unidades"), dev_uds, detalle)
    r.comprobar("T-3 devoluciones · líneas", suma(b4, "lineas"), dev_lin, detalle)

    # C4.4 — la calificación NO se une a la dimensión de producto. El control es
    # exactamente ese: el número de reseñas del bloque debe ser el de la tabla,
    # y NO el que saldría del JOIN por producto padre.
    b5 = bloque(s, "calificacion_producto")
    resenas = uno(cur, "SELECT count(*) FROM resena")
    inflado = uno(cur, """
        SELECT count(*) FROM resena r
        JOIN producto_variante pv ON pv.producto_id = r.producto_id""")
    r.comprobar("T-3 reseñas · sin multiplicar por variante (C4.4)",
                suma(b5, "resenas"), resenas, detalle)
    if inflado != resenas:
        print(f"         (el JOIN a la dimensión daría {inflado} en vez de {resenas}: "
              f"{inflado - resenas} reseñas fantasma)")

    # Cruce reclamo × devolución: la intersección exacta, no la unión.
    b6 = bloque(s, "reclama_y_devuelve")
    cruce = uno(cur, """
        SELECT count(*) FROM (
            SELECT t.producto_variante_id FROM ticket_soporte t
            WHERE t.producto_variante_id IS NOT NULL
            INTERSECT
            SELECT pd.producto_variante_id
            FROM devolucion_detalle dd
            JOIN pedido_detalle pd ON pd.id = dd.pedido_detalle_id) x""")
    r.comprobar("T-3 cruce reclamo × devolución", b6["filas"], cruce, detalle)


# ═════════════════════════════════════════════════════════════════════════════
# T-4 · OPERACIÓN Y ÚLTIMA MILLA — y la prueba de que NO viaja dinero
# ═════════════════════════════════════════════════════════════════════════════

#: Nombres de columna con aspecto monetario. T-4 es el único tablero que
#: Despacho y Bodega pueden abrir, y lo único que lo garantiza —además de la
#: línea de SecurityConfig— es que su consulta no seleccione un importe.
#: ClickHouse no tiene privilegio por columna que respalde eso, así que la
#: regla se comprueba aquí: si un bloque futuro cuela una columna de dinero,
#: esta lista lo caza antes de que llegue a producción.
COLUMNAS_DE_DINERO = (
    "costo", "monto", "total", "precio", "importe", "venta", "gasto", "saldo",
    "pagado", "reembols", "margen", "subtotal", "valor", "credito", "factura_total",
    "recuperado", "cxp", "descuento", "ticket_promedio",
)

#: Excepciones EXPLÍCITAS: nombres que contienen una de las palabras de arriba
#: sin ser un importe. Se enumeran una a una y no con un patrón: una regla laxa
#: aquí es una puerta abierta.
NO_SON_DINERO = {
    "unidades_perdidas",    # merma en UNIDADES
    "unidades_sobrantes",
    "unidades_reingresadas",
    "valor",                # solo si el bloque lo declara; ver abajo
    "es_total",             # marca booleana de la fila del ciclo completo
}


def sin_dinero(sobre: dict) -> list[str]:
    """Recorre KPI y TODAS las filas de TODOS los bloques buscando importes."""
    sospechosas: set[str] = set()

    def revisar(clave: str) -> None:
        c = clave.lower()
        if c in NO_SON_DINERO:
            return
        for palabra in COLUMNAS_DE_DINERO:
            if palabra in c:
                sospechosas.add(clave)

    for b in sobre.get("bloques", []):
        for fila in b.get("items", []):
            for clave in fila:
                revisar(clave)
    return sorted(sospechosas)


def validar_t4(cur, jwt: str, r: Resultado, detalle: bool) -> None:
    print("\n=== T-4 · Tablero de Operación y Última Milla ===")
    s = tablero("operacion", jwt)

    # ── La prueba que da sentido al tablero ───────────────────────────────
    fugas = sin_dinero(s)
    if fugas:
        r.mal += 1
        r.fallos.append("T-4 · columnas de dinero en la respuesta: " + ", ".join(fugas))
        print("  [MAL] T-4 · el tablero SIN dinero está devolviendo importes:")
        for f in fugas:
            print("        -", f)
    else:
        r.ok += 1
        if detalle:
            print("  [OK ] T-4 · ninguna columna de dinero en la respuesta")

    #: El día del envío, convertido a la zona del negocio. Restado en UTC,
    #: uno de cada cinco envíos cambia de día (C3C.1).
    dia_entrega = "(e.fecha_entrega_real AT TIME ZONE 'America/Guayaquil')::date"
    dia_despacho = "(e.fecha_despacho AT TIME ZONE 'America/Guayaquil')::date"

    envios = uno(cur, "SELECT count(*) FROM envio e")
    con_promesa = uno(cur, f"""
        SELECT count(*) FROM envio e
        WHERE e.fecha_entrega_real IS NOT NULL AND e.fecha_entrega_estimada IS NOT NULL""")
    a_tiempo = uno(cur, f"""
        SELECT count(*) FROM envio e
        WHERE e.fecha_entrega_real IS NOT NULL AND e.fecha_entrega_estimada IS NOT NULL
          AND {dia_entrega} <= e.fecha_entrega_estimada""")

    b1 = bloque(s, "cumplimiento_promesa")
    r.comprobar("T-4 cumplimiento · envíos", suma(b1, "envios"), envios, detalle)
    r.comprobar("T-4 cumplimiento · con promesa medible",
                suma(b1, "con_promesa"), con_promesa, detalle)
    r.comprobar("T-4 cumplimiento · a tiempo", suma(b1, "a_tiempo"), a_tiempo, detalle)
    r.comprobar("T-4 cumplimiento · medibles + no medibles = envíos",
                suma(b1, "con_promesa") + suma(b1, "sin_promesa"), envios, detalle)
    r.comprobar("T-4 KPI entregas a tiempo", kpi(s, "Entregas dentro"),
                round(Decimal(a_tiempo) * 100 / con_promesa, 2), detalle)

    # Días de tránsito.
    medidos = uno(cur, f"""
        SELECT count(*) FROM envio e
        WHERE e.fecha_entrega_real IS NOT NULL AND e.fecha_despacho IS NOT NULL""")
    b2 = bloque(s, "dias_transito")
    r.comprobar("T-4 tránsito · envíos medidos", suma(b2, "medidos"), medidos, detalle)
    transito = uno(cur, f"""
        SELECT round(avg(({dia_entrega} - {dia_despacho}))::numeric, 2) FROM envio e
        WHERE e.fecha_entrega_real IS NOT NULL AND e.fecha_despacho IS NOT NULL""")
    r.comprobar("T-4 KPI tránsito medio", kpi(s, "Tránsito medio"), transito, detalle)

    # Tiempo por etapa: las CUATRO poblaciones, una por una (C2.7).
    hitos = """
        WITH h AS (
            SELECT hp.pedido_id,
                   min(hp.fecha_creacion) FILTER (WHERE e.codigo = 'pagado')         AS f_pag,
                   min(hp.fecha_creacion) FILTER (WHERE e.codigo = 'preparado')      AS f_pre,
                   min(hp.fecha_creacion) FILTER (WHERE e.codigo = 'despachado')     AS f_des,
                   min(hp.fecha_creacion) FILTER (WHERE e.codigo = 'entregado')      AS f_ent
            FROM historial_estado_pedido hp
            JOIN estado_pedido e ON e.id = hp.estado_pedido_id
            GROUP BY hp.pedido_id)
        SELECT %s FROM pedido p JOIN h ON h.pedido_id = p.id
    """
    # OJO: «preparación» en los tramos del almacén es el hito 'preparado'
    # (picking TERMINADO) y no 'en_preparacion'. Son dos hitos distintos —2.868
    # pedidos frente a 2.883— y se eligió el primero para que cada tramo empiece
    # donde acaba el anterior. Replicarlo con 'en_preparacion' da 15 pedidos de
    # más y acusa al tablero de una diferencia que es del control.
    poblaciones = {
        "Del cobro al picking terminado":
            "count(*) FILTER (WHERE h.f_pag IS NOT NULL AND h.f_pre IS NOT NULL)",
        "Del picking terminado al despacho":
            "count(*) FILTER (WHERE h.f_pre IS NOT NULL AND h.f_des IS NOT NULL)",
        "Del despacho a la entrega":
            "count(*) FILTER (WHERE h.f_des IS NOT NULL AND h.f_ent IS NOT NULL)",
        "Ciclo completo (cobro → entrega)":
            "count(*) FILTER (WHERE h.f_pag IS NOT NULL AND h.f_ent IS NOT NULL)",
    }
    b3 = bloque(s, "tiempo_etapa")
    for fila in b3["items"]:
        cur.execute(hitos.replace("%s", poblaciones[fila["etapa"]]))
        n = cur.fetchone()[0]
        r.comprobar(f"T-4 etapa «{fila['etapa']}» · pedidos medidos",
                    fila["pedidos_medidos"], n, detalle)

    # Incidencias: la lista blanca sale de los DATOS (C3C.3).
    b4 = bloque(s, "incidencias")
    nov = uno(cur, "SELECT count(*) FROM novedad_envio")
    resueltas = uno(cur, "SELECT count(*) FROM novedad_envio WHERE estado = 'resuelta'")
    r.comprobar("T-4 incidencias · total", suma(b4, "novedades"), nov, detalle)
    r.comprobar("T-4 incidencias · resueltas", suma(b4, "resueltas"), resueltas, detalle)
    cur.execute("SELECT DISTINCT COALESCE(accion, 'sin_resolver') FROM novedad_envio")
    reales = {x[0] for x in cur.fetchall()}
    publicados = {f["desenlace"] for f in b4["items"]}
    r.comprobar("T-4 incidencias · desenlaces distintos",
                len(publicados), len(reales), detalle)
    if publicados != reales:
        r.mal += 1
        r.fallos.append(f"T-4 desenlaces {publicados} != base {reales}")
        print(f"  [MAL] T-4 desenlaces publicados {publicados} vs base {reales}")
    else:
        r.ok += 1

    # Merma: es_ajuste_real, JAMÁS naturaleza='ajuste' (C3B.1, factor 380×).
    b5 = bloque(s, "merma_motivo")
    cur.execute("""
        SELECT sum(m.cantidad) FILTER (WHERE tm.factor = -1),
               sum(m.cantidad) FILTER (WHERE tm.factor =  1),
               count(*)
        FROM movimiento_inventario m
        JOIN tipo_movimiento tm ON tm.id = m.tipo_movimiento_id
        WHERE m.referencia_tipo = 'ajuste_inventario'""")
    perdidas, sobrantes, movs = cur.fetchone()
    r.comprobar("T-4 merma · unidades perdidas",
                suma(b5, "unidades_perdidas"), perdidas, detalle)
    r.comprobar("T-4 merma · unidades sobrantes",
                suma(b5, "unidades_sobrantes"), sobrantes, detalle)
    r.comprobar("T-4 merma · movimientos", suma(b5, "movimientos"), movs, detalle)

    # Y el contraste que prueba por qué el filtro importa: el sobrante con el
    # filtro «obvio» de naturaleza sale multiplicado.
    inflado = uno(cur, """
        SELECT sum(m.cantidad) FROM movimiento_inventario m
        JOIN tipo_movimiento tm ON tm.id = m.tipo_movimiento_id
        WHERE tm.naturaleza = 'ajuste' AND tm.factor = 1""")
    if sobrantes and inflado and inflado > sobrantes * 100:
        print(f"         (con el filtro por naturaleza el sobrante sería {inflado} "
              f"en vez de {sobrantes}: {round(inflado / sobrantes)}x)")

    # Embudo del retorno: monótono, y el último paso puede ser CERO.
    b6 = bloque(s, "retorno_almacen")
    anterior = None
    for fila in b6["items"]:
        if anterior is not None and fila["pedidos"] > anterior:
            r.mal += 1
            r.fallos.append(f"T-4 embudo de retorno NO monótono en «{fila['paso']}»")
            print(f"  [MAL] T-4 retorno NO monótono en «{fila['paso']}»: "
                  f"{fila['pedidos']} > {anterior}")
        else:
            r.ok += 1
        anterior = fila["pedidos"]
    devueltos = uno(cur, """
        SELECT count(DISTINCT envio_id) FROM novedad_envio WHERE accion = 'devuelto_almacen'""")
    r.comprobar("T-4 retorno · envíos devueltos al almacén",
                b6["items"][2]["pedidos"], devueltos, detalle)


# ═════════════════════════════════════════════════════════════════════════════
# T-5 · COSTO DE LA OPERACIÓN
# ═════════════════════════════════════════════════════════════════════════════

def validar_t5(cur, jwt: str, r: Resultado, detalle: bool) -> None:
    print("\n=== T-5 · Tablero de Costo de la Operación ===")
    s = tablero("costo-operacion", jwt)

    # C3C.2: los envíos sin tarifar quedan fuera, y se declara cuántos.
    sin_tarifa = uno(cur, """
        SELECT count(*) FROM envio WHERE costo = 0 AND peso_total_kg IS NULL""")
    tarifados = uno(cur, """
        SELECT count(*) FROM envio WHERE NOT (costo = 0 AND peso_total_kg IS NULL)""")
    costo = uno(cur, """
        SELECT sum(costo) FROM envio WHERE NOT (costo = 0 AND peso_total_kg IS NULL)""")
    r.comprobar("T-5 KPI costo de envío", kpi(s, "Costo de envío"), costo, detalle)
    r.comprobar("T-5 KPI costo medio por envío", kpi(s, "Costo medio por envío"),
                round(Decimal(costo) / tarifados, 2), detalle)

    b1 = bloque(s, "costo_zona_mes")
    r.comprobar("T-5 serie · envíos tarifados", suma(b1, "envios"), tarifados, detalle)
    r.comprobar("T-5 serie · costo", suma(b1, "costo"), costo, detalle)

    b2 = bloque(s, "costo_por_kg")
    todos = uno(cur, "SELECT count(*) FROM envio")
    r.comprobar("T-5 costo/kg · envíos (todos)", suma(b2, "envios"), todos, detalle)
    r.comprobar("T-5 costo/kg · sin tarifar declarados",
                suma(b2, "sin_tarifa"), sin_tarifa, detalle)

    # C4.1: 86 reembolsos contra 85 asientos, $169,70 de diferencia.
    b3 = bloque(s, "reembolsos")
    cur.execute("""
        SELECT count(*), sum(d.monto_reembolsado),
               count(*) FILTER (WHERE re.id IS NULL),
               sum(d.monto_reembolsado) - COALESCE(sum(re.monto), 0)
        FROM devolucion d
        LEFT JOIN reembolso re ON re.devolucion_id = d.id
        WHERE d.monto_reembolsado > 0""")
    n_dev, monto, sin_asiento, diferencia = cur.fetchone()
    r.comprobar("T-5 reembolsos · devoluciones", suma(b3, "devoluciones"), n_dev, detalle)
    r.comprobar("T-5 reembolsos · monto", suma(b3, "reembolsado"), monto, detalle)
    r.comprobar("T-5 reembolsos · sin asiento contable",
                suma(b3, "sin_asiento"), sin_asiento, detalle)
    r.comprobar("T-5 reembolsos · diferencia con el asiento",
                suma(b3, "diferencia"), diferencia, detalle)
    r.comprobar("T-5 KPI reembolsos pagados", kpi(s, "Reembolsos pagados"), monto, detalle)


# ═════════════════════════════════════════════════════════════════════════════
# T-6 · ABASTECIMIENTO
# ═════════════════════════════════════════════════════════════════════════════

def validar_t6(cur, jwt: str, r: Resultado, detalle: bool) -> None:
    print("\n=== T-6 · Tablero de Abastecimiento ===")
    s = tablero("abastecimiento", jwt)

    ordenes = uno(cur, "SELECT count(*) FROM orden_compra")
    facturas = uno(cur, "SELECT count(*) FROM factura_compra")
    gasto = uno(cur, "SELECT sum(total) FROM factura_compra")
    saldo = uno(cur, "SELECT sum(saldo_pendiente) FROM cuenta_por_pagar")

    r.comprobar("T-6 KPI gasto de compra", kpi(s, "Gasto de compra"), gasto, detalle)
    r.comprobar("T-6 KPI saldo abierto en CxP",
                kpi(s, "Saldo abierto"), saldo, detalle)

    # C5.1: el mes del gasto es el de la FACTURA. El total es el mismo en las
    # dos versiones —eso es lo peligroso— así que se comprueba el TOTAL y,
    # aparte, cuántas facturas caen en un mes distinto al de su orden.
    b1 = bloque(s, "gasto_proveedor_mes")
    r.comprobar("T-6 gasto · facturas", suma(b1, "facturas"), facturas, detalle)
    r.comprobar("T-6 gasto · total", suma(b1, "gasto"), gasto, detalle)
    desplazadas = uno(cur, """
        SELECT count(*) FROM factura_compra fc
        JOIN orden_compra oc ON oc.id = fc.orden_compra_id
        WHERE date_trunc('month', fc.fecha_emision) <> date_trunc('month', oc.fecha_emision)""")
    print(f"         (C5.1: {desplazadas} de {facturas} facturas caen en un mes "
          f"distinto al de su orden)")

    b2 = bloque(s, "ficha_proveedor")
    proveedores = uno(cur, "SELECT count(DISTINCT proveedor_id) FROM orden_compra")
    r.comprobar("T-6 ficha · proveedores", b2["filas"], proveedores, detalle)
    r.comprobar("T-6 ficha · órdenes", suma(b2, "ordenes"), ordenes, detalle)
    r.comprobar("T-6 ficha · gasto", suma(b2, "gasto"), gasto, detalle)

    # C5.2: el alcance por defecto son las ENTREGADAS.
    b3 = bloque(s, "entregas_incompletas")
    cortas_entregadas = uno(cur, """
        SELECT count(*) FROM orden_compra_detalle d
        JOIN orden_compra oc ON oc.id = d.orden_compra_id
        WHERE oc.estado IN ('recibida', 'recibida_parcial')
          AND d.cantidad_recibida < d.cantidad""")
    cortas_todas = uno(cur, """
        SELECT count(*) FROM orden_compra_detalle d
        WHERE d.cantidad_recibida < d.cantidad""")
    r.comprobar("T-6 entregas incompletas · líneas cortas (alcance entregadas)",
                suma(b3, "lineas_cortas"), cortas_entregadas, detalle)
    r.comprobar("T-6 entregas incompletas · alcance por defecto",
                1 if s.get("alcance") == "entregadas" else 0, 1, detalle)
    print(f"         (C5.2: {cortas_entregadas} líneas cortas con «entregadas» "
          f"contra {cortas_todas} con «todas»)")

    # C3.2: el rechazo se mide sobre lo que LLEGÓ.
    b4 = bloque(s, "rechazo_puerta")
    cur.execute("""
        SELECT sum(rd.cantidad_rechazada), sum(rd.cantidad_recibida)
        FROM recepcion_detalle rd WHERE rd.cantidad_rechazada > 0""")
    rechazadas, recibidas_en_rechazo = cur.fetchone()
    r.comprobar("T-6 rechazo · unidades rechazadas",
                suma(b4, "unidades_rechazadas"), rechazadas, detalle)
    total_rechazadas = uno(cur, "SELECT sum(cantidad_rechazada) FROM recepcion_detalle")
    total_recibidas = uno(cur, "SELECT sum(cantidad_recibida) FROM recepcion_detalle")
    r.comprobar("T-6 KPI % rechazo sobre lo que llegó",
                kpi(s, "Unidades rechazadas"),
                round(Decimal(total_rechazadas) * 100
                      / (Decimal(total_recibidas) + Decimal(total_rechazadas)), 2), detalle)

    # Deuda y puntualidad del pago.
    b6 = bloque(s, "cxp_vencimientos")
    r.comprobar("T-6 CxP · saldo", suma(b6, "saldo"), saldo, detalle)
    b7 = bloque(s, "puntualidad_pago")
    pagos = uno(cur, "SELECT count(*) FROM pago_proveedor")
    pagado = uno(cur, "SELECT sum(monto) FROM pago_proveedor")
    a_tiempo = uno(cur, """
        SELECT count(*) FROM pago_proveedor pp
        JOIN cuenta_por_pagar cxp ON cxp.id = pp.cuenta_por_pagar_id
        WHERE pp.fecha_pago <= cxp.fecha_vencimiento""")
    r.comprobar("T-6 puntualidad · pagos", suma(b7, "pagos"), pagos, detalle)
    r.comprobar("T-6 puntualidad · pagado", suma(b7, "pagado"), pagado, detalle)
    r.comprobar("T-6 puntualidad · dentro del vencimiento",
                suma(b7, "a_tiempo"), a_tiempo, detalle)

    # Defectuosos: muestra declarada y origen del CHECK (C4.7).
    b8 = bloque(s, "defectuosos")
    items = uno(cur, "SELECT count(*) FROM item_defectuoso")
    unidades = uno(cur, "SELECT sum(cantidad) FROM item_defectuoso")
    r.comprobar("T-6 defectuosos · ítems", suma(b8, "items"), items, detalle)
    r.comprobar("T-6 defectuosos · unidades", suma(b8, "unidades"), unidades, detalle)
    cur.execute("SELECT DISTINCT origen FROM item_defectuoso ORDER BY 1")
    origenes = {x[0] for x in cur.fetchall()}
    publicados = {f["origen"] for f in b8["items"]}
    if publicados != origenes:
        r.mal += 1
        r.fallos.append(f"T-6 orígenes {publicados} != base {origenes}")
        print(f"  [MAL] T-6 orígenes publicados {publicados} vs base {origenes}")
    else:
        r.ok += 1
        if detalle:
            print(f"  [OK ] T-6 defectuosos · origen del CHECK: {sorted(origenes)}")
    if "muestra" not in b8:
        r.mal += 1
        r.fallos.append("T-6 defectuosos no declara su muestra débil")
        print("  [MAL] T-6 defectuosos debe declarar su muestra en pantalla")
    else:
        r.ok += 1


# ═════════════════════════════════════════════════════════════════════════════
# T-7 · GOBIERNO DEL DATO
# ═════════════════════════════════════════════════════════════════════════════

def validar_t7(cur, jwt: str, r: Resultado, detalle: bool) -> None:
    print("\n=== T-7 · Tablero de Gobierno del Dato ===")
    s = tablero("gobierno-dato", jwt)

    # Este tablero NO se contrasta contra PostgreSQL: mide la bitácora del
    # pipeline, que solo existe en el almacén. Lo que sí se comprueba son sus
    # invariantes internos, que son donde estaban las trampas.
    b1 = bloque(s, "salud_corrida")
    b3 = bloque(s, "antiguedad_dato")

    # 19 tablas del modelo, ni una más ni una menos.
    r.comprobar("T-7 antigüedad · tablas del modelo", b3["filas"], 19, detalle)

    # La suma de filas NO debe incluir la pseudo-tarea «corrida», que repite el
    # total de todas las tablas y lo duplicaría.
    tablas = [f for f in b1["items"] if f["tipo"] == "tabla"]
    suma_tablas = sum(Decimal(str(f["filas"])) for f in tablas)
    r.comprobar("T-7 filas publicadas = suma de las TABLAS",
                kpi(s, "Filas publicadas"), suma_tablas, detalle)
    corrida = [f for f in b1["items"] if f["tipo"] == "corrida"]
    if corrida:
        ingenua = sum(Decimal(str(f["filas"])) for f in b1["items"])
        print(f"         (la suma ingenua de la corrida daría {ingenua} "
              f"en vez de {suma_tablas}: la fila «corrida» repite el total)")
        # Y esa fila NO puede quedarse en «en curso»: se colapsa a su estado final.
        if corrida[0]["resultado"] == "en_curso":
            r.mal += 1
            r.fallos.append("T-7 la fila «corrida» se quedó en «en_curso»")
            print("  [MAL] T-7 la fila «corrida» aparece «en_curso»: no se colapsó "
                  "al estado final y es una alarma falsa")
        else:
            r.ok += 1

    # Ninguna tarea puede aparecer dos veces en la misma corrida.
    tareas = [f["tarea"] for f in b1["items"]]
    r.comprobar("T-7 salud · una fila por tarea", len(tareas), len(set(tareas)), detalle)

    # El calendario es la única tabla sin sello de carga, y se declara.
    calendario = [f for f in b3["items"] if f["tabla"] == "dim_fecha"]
    if not calendario or calendario[0].get("generada") != 1:
        r.mal += 1
        r.fallos.append("T-7 dim_fecha debe declararse como generada, sin sello")
        print("  [MAL] T-7 dim_fecha no está marcada como generada en el almacén")
    else:
        r.ok += 1
        if detalle:
            print("  [OK ] T-7 dim_fecha declarada como generada (sin fecha_carga)")

    con_sello = [f for f in b3["items"] if f.get("generada") == 0]
    r.comprobar("T-7 antigüedad · tablas con sello de carga",
                len(con_sello), 18, detalle)


# ── Entrada ──────────────────────────────────────────────────────────────────

def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--tablero",
                    choices=["T-1", "T-2", "T-3", "T-4", "T-5", "T-6", "T-7"],
                    help="valida solo uno")
    ap.add_argument("--detalle", action="store_true",
                    help="imprime también los controles que pasan")
    args = ap.parse_args()

    print("Verificación de los tableros de dirección (fase E1-A)")
    print(f"API: {API}   ·   PostgreSQL: {PG['user']}@{PG['dbname']}")

    jwt = token()
    r = Resultado()
    controles: dict[str, Callable] = {
        "T-1": validar_t1, "T-2": validar_t2, "T-3": validar_t3,
        "T-4": validar_t4, "T-5": validar_t5, "T-6": validar_t6, "T-7": validar_t7,
    }

    with psycopg2.connect(**PG) as cx:
        with cx.cursor() as cur:
            for clave, fn in controles.items():
                if args.tablero and args.tablero != clave:
                    continue
                fn(cur, jwt, r, args.detalle)

    print(f"\n{'=' * 62}")
    print(f"Controles en verde: {r.ok}   ·   con diferencia: {r.mal}")
    if r.fallos:
        print("Fallaron:")
        for f in r.fallos:
            print(f"  - {f}")
        return 1
    print("Todas las cifras publicadas cuadran con PostgreSQL.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except urllib.error.HTTPError as e:
        print(f"ERROR HTTP {e.code}: {e.read().decode(errors='replace')[:400]}")
        sys.exit(2)
