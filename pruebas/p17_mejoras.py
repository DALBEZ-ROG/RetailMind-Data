"""
p17_mejoras.py — Las tres mejoras con superficie de API del 2026-08-25.

Cubre lo que se puede comprobar sin navegador; la parte de interfaz —el aviso
de cuenta creada del alta y el tope de caracteres de los campos— la mide
`p17_mejoras.js`.

  · **Existencias** (`/api/inventario/existencias`): la pantalla que faltaba en
    Inventario. Se comprueban las cifras CONTRA POSTGRESQL, la matriz de roles,
    la lista blanca de filtros, que el desglose por bodega cuadre con el
    agregado y —lo que sostiene que BODEGA pueda entrar— que la respuesta no
    lleve ni un importe.
  · **Proveedor en el catálogo**: que el listado y el detalle lo traigan, y que
    lo que dicen coincida con `producto_proveedor`.
  · **PDF de la factura de venta**: que la columna «Código» venga LLENA (salía
    vacía en todas las líneas), que el cupón aparezca con su código, y que las
    otras dos plantillas que comparten el renderizador —factura de compra y
    guía de retorno del RMA— sigan generándose.
  · **El ADMIN entra al RMA** (script 113): `grp_administrador` no tenía ni un
    GRANT sobre `historial_estado_devolucion`, así que el único rol que puede
    ejecutar las seis transiciones del ciclo no podía ni abrir el detalle.

Las cifras de control salen de PostgreSQL por `comun/motor.py` y se contrastan
contra la RESPUESTA HTTP, nunca contra la misma consulta que sirve la pantalla:
comparar una consulta consigo misma da verde con las dos mitades equivocadas.
"""

from __future__ import annotations

import io
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from comun.arnes import Cliente, Registro, esperar_api, sesiones            # noqa: E402
from comun.motor import sql                                                 # noqa: E402

#: Quién debe entrar a Existencias y quién no. Espeja la línea de
#: `SecurityConfig` y la del `roleGuard`: los mismos que el kardex, porque la
#: consulta no selecciona ni un importe.
ACCESO_EXISTENCIAS = {
    "ADMIN": 200, "GERENTE": 200, "BODEGA": 200, "ANALISTA": 200,
    "VENDEDOR": 403, "COMPRAS": 403, "DESPACHO": 403, "SOPORTE": 403, "CLIENTE": 403,
}

#: Nombres con aspecto monetario. Si alguno aparece en la respuesta de
#: Existencias, la segregación financiera se rompió — igual que en
#: `validar_tableros.py`, que recorre la respuesta entera buscándolos.
PALABRAS_DINERO = ("precio", "costo", "monto", "total", "importe", "valor",
                   "subtotal", "iva", "impuesto", "margen")


def _entero(texto: str) -> int:
    return int((texto or "0").strip().splitlines()[0].strip())


def _hay_dinero(nodo, camino="") -> str | None:
    """Devuelve la primera clave con pinta de dinero, o None."""
    if isinstance(nodo, dict):
        for k, v in nodo.items():
            if any(p in str(k).lower() for p in PALABRAS_DINERO):
                return f"{camino}.{k}"
            hallado = _hay_dinero(v, f"{camino}.{k}")
            if hallado:
                return hallado
    elif isinstance(nodo, list):
        for i, v in enumerate(nodo[:5]):
            hallado = _hay_dinero(v, f"{camino}[{i}]")
            if hallado:
                return hallado
    return None


def correr(estado: str = "E3") -> Registro:
    reg = Registro(estado)
    if not esperar_api():
        reg.caso("P17-000", "La API responde", condicion=False, severidad="S1",
                 observado="/api/health no contestó", esperado="200")
        return reg

    clientes = sesiones(list(ACCESO_EXISTENCIAS))
    admin: Cliente | None = clientes.get("ADMIN")
    if admin is None:
        reg.caso("P17-000", "ADMIN entra", condicion=False, severidad="S1",
                 observado="no se pudo iniciar sesión", esperado="token de ADMIN")
        return reg

    # ════════════════════════════════════════════════════════════ EXISTENCIAS

    r = admin.get("/api/inventario/existencias?size=25")
    cuerpo = r.json() if admin.codigo(r) == 200 else {}
    reg.caso("P17-001", "Existencias responde con el sobre completo",
             condicion=admin.codigo(r) == 200
                       and all(k in cuerpo for k in ("items", "total", "page", "size", "resumen")),
             severidad="S2",
             observado=f"HTTP {admin.codigo(r)} · claves {sorted(cuerpo)}",
             esperado="200 con items, total, page, size y resumen",
             reproducir=admin.curl("GET", "/api/inventario/existencias"))

    # Las tres cifras que la pantalla enseña, contra el motor.
    variantes_pg = reg.medir("P17-002", "Variantes en la base",
                             lambda: _entero(sql("SELECT count(*) FROM producto_variante")))
    unidades_pg = reg.medir("P17-003", "Unidades en existencia según la base",
                            lambda: _entero(sql("SELECT COALESCE(sum(stock_actual),0) FROM inventario")))

    resumen = cuerpo.get("resumen") or {}
    reg.caso("P17-004", "El total de variantes coincide con PostgreSQL",
             condicion=cuerpo.get("total") == variantes_pg == resumen.get("variantes"),
             severidad="S2",
             observado=f"API total={cuerpo.get('total')} resumen={resumen.get('variantes')} · PG={variantes_pg}",
             esperado="las tres cifras iguales")

    reg.caso("P17-005", "Las unidades coinciden con inventario.stock_actual",
             condicion=resumen.get("unidades") == unidades_pg,
             severidad="S2",
             observado=f"API={resumen.get('unidades')} · PG={unidades_pg}",
             esperado="suma exacta, sin filtro puesto",
             detalle="es la cifra que responde «cuánto tengo»; si difiere, la pantalla miente")

    # Una variante SIN ninguna posición de inventario tiene que salir igual:
    # partir de `inventario` la haría desaparecer y «no aparece» se leería como
    # «no tengo», que es justo lo contrario de lo que la pantalla viene a decir.
    sin_posicion_pg = reg.medir(
        "P17-006", "Variantes sin ninguna posición de inventario",
        lambda: _entero(sql("""SELECT count(*) FROM producto_variante pv
                               WHERE NOT EXISTS (SELECT 1 FROM inventario i
                                                 WHERE i.producto_variante_id = pv.id)""")))
    r = admin.get("/api/inventario/existencias?estado=sin_stock&size=100")
    sin_stock = r.json() if admin.codigo(r) == 200 else {"items": [], "total": -1}
    sin_bodega_api = sum(1 for i in sin_stock.get("items", []) if i.get("bodegas") == 0)
    reg.caso("P17-007", "Una variante sin posición de inventario aparece con stock 0",
             condicion=sin_posicion_pg is not None and sin_bodega_api >= min(sin_posicion_pg, 1)
                       and (sin_posicion_pg == 0 or sin_bodega_api > 0),
             severidad="S2",
             observado=f"PG sin posición={sin_posicion_pg} · en la respuesta={sin_bodega_api}",
             esperado="salen listadas, con 0 bodegas y 0 unidades",
             detalle="con un FROM inventario desaparecerían del listado sin dar error")

    sin_stock_pg = reg.medir(
        "P17-008", "Variantes con existencia cero según la base",
        lambda: _entero(sql("""SELECT count(*) FROM (
                                 SELECT pv.id FROM producto_variante pv
                                 LEFT JOIN inventario i ON i.producto_variante_id = pv.id
                                 GROUP BY pv.id
                                 HAVING COALESCE(sum(i.stock_actual), 0) = 0) t""")))
    reg.caso("P17-009", "El filtro «sin existencias» devuelve exactamente esas",
             condicion=sin_stock.get("total") == sin_stock_pg,
             severidad="S2",
             observado=f"API={sin_stock.get('total')} · PG={sin_stock_pg}",
             esperado="mismo conjunto")

    # El desglose por bodega tiene que sumar lo que dice el agregado; si no, una
    # de las dos consultas está contando otra cosa.
    r = admin.get("/api/inventario/existencias?orden=stock_desc&size=3")
    filas = r.json().get("items", []) if admin.codigo(r) == 200 else []
    cuadran, detalle = True, []
    for f in filas:
        d = admin.get(f"/api/inventario/existencias/{f['variante_id']}/bodegas")
        piezas = d.json() if admin.codigo(d) == 200 else []
        suma = sum(p["stock_actual"] for p in piezas)
        if suma != f["stock_actual"] or len(piezas) != f["bodegas"]:
            cuadran = False
        detalle.append(f"{f['sku']}: agregado {f['stock_actual']} vs desglose {suma}")
    reg.caso("P17-010", "El desglose por bodega suma lo que dice el agregado",
             condicion=cuadran and bool(filas), severidad="S2",
             observado=" · ".join(detalle) or "sin filas para comprobar",
             esperado="suma y número de bodegas idénticos")

    # Lista blanca: lo que no está en la tabla del servicio da 400 y NO llega al SQL.
    for parametro, valor in (("estado", "inventado"), ("orden", "precio DESC"),
                             ("estado", "todos; DROP TABLE marca")):
        r = admin.get(f"/api/inventario/existencias?{parametro}={valor}")
        reg.caso(f"P17-011-{parametro}-{abs(hash(valor)) % 1000}",
                 f"Un {parametro} no previsto se rechaza con 400",
                 condicion=admin.codigo(r) == 400, severidad="S2",
                 observado=f"HTTP {admin.codigo(r)}",
                 esperado="400 por lista blanca, nunca 500 ni 200",
                 reproducir=admin.curl("GET", f"/api/inventario/existencias?{parametro}={valor}"))

    # Filtrar por bodega no puede esconder las variantes que NO están en ella:
    # son exactamente las que se buscan al filtrar.
    bodega = reg.medir("P17-012", "Hay al menos una bodega",
                       lambda: _entero(sql("SELECT id FROM bodega WHERE activo ORDER BY id LIMIT 1")))
    if bodega:
        r = admin.get(f"/api/inventario/existencias?bodegaId={bodega}&size=1")
        total_bodega = r.json().get("total") if admin.codigo(r) == 200 else None
        reg.caso("P17-013", "Con una bodega elegida siguen saliendo todas las variantes",
                 condicion=total_bodega == variantes_pg, severidad="S2",
                 observado=f"con bodega={bodega}: {total_bodega} · variantes en la base: {variantes_pg}",
                 esperado="el filtro de bodega va en el JOIN, no en el WHERE",
                 detalle="en el WHERE convertiría el LEFT JOIN en interno y "
                         "desaparecerían justo las que no tienen stock ahí")

    # Matriz de roles.
    for rol, esperado in ACCESO_EXISTENCIAS.items():
        c = clientes.get(rol)
        if c is None:
            reg.omitir(f"P17-014-{rol}", f"Acceso de {rol} a Existencias",
                       motivo="no se pudo iniciar sesión con ese rol")
            continue
        r = c.get("/api/inventario/existencias?size=1")
        reg.caso(f"P17-014-{rol}", f"Acceso de {rol} a Existencias",
                 condicion=c.codigo(r) == esperado, severidad="S2",
                 observado=f"HTTP {c.codigo(r)}", esperado=f"HTTP {esperado}",
                 reproducir=c.curl("GET", "/api/inventario/existencias"))

    # Lo que permite que BODEGA entre: la consulta no selecciona un importe.
    bod = clientes.get("BODEGA")
    if bod is not None:
        r = bod.get("/api/inventario/existencias?size=25")
        # Se miran SOLO `items` y `resumen`: el `total` del sobre es el número
        # de filas del conjunto, no un importe, y hacerlo saltar convertiría
        # esta comprobación en ruido que se acaba desactivando.
        cuerpo_bod = r.json() if bod.codigo(r) == 200 else None
        hallado = ("no se pudo leer" if cuerpo_bod is None
                   else _hay_dinero({"items": cuerpo_bod.get("items"),
                                     "resumen": cuerpo_bod.get("resumen")}))
        reg.caso("P17-015", "Existencias no devuelve ni un importe",
                 condicion=hallado is None, severidad="S1",
                 observado=f"campo con pinta de dinero: {hallado}",
                 esperado="ninguno — el corte financiero lo hace la CONSULTA, no la ruta",
                 detalle="BODEGA y ANALISTA abren esta pantalla; ClickHouse "
                         "no interviene y PostgreSQL no puede filtrar columnas "
                         "de un SELECT que no las pide")

    # ═══════════════════════════════════════════════ PROVEEDOR EN EL CATÁLOGO

    r = admin.get("/api/admin/catalogo/productos/buscar?size=10")
    items = r.json().get("items", []) if admin.codigo(r) == 200 else []
    reg.caso("P17-020", "El listado del catálogo trae el proveedor",
             condicion=bool(items) and all("proveedores" in i for i in items),
             severidad="S3",
             observado=f"{len(items)} filas · con campo: "
                       f"{sum(1 for i in items if 'proveedores' in i)}",
             esperado="todas las filas con el campo `proveedores`")

    con_prov = next((i for i in items if i.get("proveedores")), None)
    if con_prov is None:
        reg.omitir("P17-021", "El proveedor del listado coincide con la base",
                   motivo="ningún producto de la primera página tiene proveedor")
    else:
        esperados = reg.medir(
            "P17-021-pg", "Proveedores del producto según la base",
            lambda: sorted(x.strip() for x in sql(f"""
                SELECT DISTINCT pr.razon_social
                FROM producto_variante pv
                JOIN producto_proveedor pp ON pp.producto_variante_id = pv.id AND pp.activo
                JOIN proveedor pr ON pr.id = pp.proveedor_id
                WHERE pv.producto_id = {int(con_prov['id'])}""").splitlines() if x.strip()))
        obtenidos = sorted(x.strip() for x in con_prov["proveedores"].split(",") if x.strip())
        reg.caso("P17-021", "El proveedor del listado coincide con la base",
                 condicion=obtenidos == (esperados or []), severidad="S3",
                 observado=f"API={obtenidos} · PG={esperados}",
                 esperado="mismos nombres, sin duplicar y sin faltar")

        d = admin.get(f"/api/admin/catalogo/productos/{con_prov['id']}")
        variantes = d.json().get("variantes", []) if admin.codigo(d) == 200 else []
        coherente = bool(variantes) and all(
            (v.get("proveedor") is None) == (v.get("proveedores", 0) == 0) for v in variantes)
        dentro = all(v.get("proveedor") in (esperados or [])
                     for v in variantes if v.get("proveedor"))
        reg.caso("P17-022", "Cada variante declara su proveedor y cuántos la surten",
                 condicion=coherente and dentro, severidad="S3",
                 observado=f"{[(v['sku'], v.get('proveedor'), v.get('proveedores')) for v in variantes]}",
                 esperado="proveedor null solo cuando el recuento es 0, "
                          "y el nombre dentro de la lista del producto")

    # ══════════════════════════════════════════════════ PDF DE LA FACTURA

    factura = reg.medir(
        "P17-030", "Hay una factura de venta con cupón",
        lambda: sql("""SELECT fv.id, cu.codigo
                       FROM factura_venta fv
                       JOIN uso_cupon uc ON uc.pedido_id = fv.pedido_id
                       JOIN cupon cu ON cu.id = uc.cupon_id
                       WHERE fv.estado <> 'anulada'
                       ORDER BY fv.id DESC LIMIT 1""").split("|"))
    if not factura or len(factura) < 2:
        reg.omitir("P17-031", "El PDF de la factura trae el código de cada línea",
                   motivo="no hay ninguna factura de venta con cupón en esta base")
    else:
        factura_id, cupon = factura[0].strip(), factura[1].strip()
        r = admin.get(f"/api/ventas/facturas/{factura_id}/pdf")
        pdf_ok = admin.codigo(r) == 200 and r.content[:4] == b"%PDF"
        texto = ""
        if pdf_ok:
            try:
                from pypdf import PdfReader
                texto = "\n".join(p.extract_text() or "" for p in
                                  PdfReader(io.BytesIO(r.content)).pages)
            except ImportError:
                texto = ""

        skus = reg.medir(
            "P17-031-pg", "SKU de las líneas de esa factura",
            lambda: [x.strip() for x in sql(f"""
                SELECT pv.sku FROM factura_venta_detalle d
                JOIN producto_variante pv ON pv.id = d.producto_variante_id
                WHERE d.factura_venta_id = {int(factura_id)}""").splitlines() if x.strip()])

        if not texto:
            reg.omitir("P17-031", "El PDF de la factura trae el código de cada línea",
                       motivo="pypdf no está instalado o el PDF no se pudo leer "
                              f"(HTTP {admin.codigo(r)})")
        else:
            # Se comparan sin espacios: un SKU largo puede partirse en dos
            # líneas dentro de su columna, y eso lo IMPRIME igual — el defecto
            # que se persigue es la celda VACÍA, no el salto de línea.
            plano = "".join(texto.split())
            faltan = [s for s in (skus or []) if "".join(s.split()) not in plano]
            reg.caso("P17-031", "El PDF de la factura trae el código de cada línea",
                     condicion=bool(skus) and not faltan, severidad="S2",
                     observado=f"{len(skus or [])} líneas · sin su código en el PDF: {faltan}",
                     esperado="la columna «Código» llena en todas las líneas",
                     detalle="salía vacía porque el detalle de la factura no guarda "
                             "el SKU; ahora sale de la variante")
            reg.caso("P17-032", "El PDF nombra el cupón aplicado",
                     condicion=cupon in texto, severidad="S3",
                     observado=f"cupón {cupon} {'presente' if cupon in texto else 'AUSENTE'}",
                     esperado="el código del cupón, junto a la fila de descuento")
            reg.caso("P17-033", "El PDF va numerado y firmado en el pie",
                     condicion="Página 1 de" in texto and "RETAILMIND" in texto.upper(),
                     severidad="S4",
                     observado=("pie con numeración" if "Página 1 de" in texto
                                else "sin numeración de página"),
                     esperado="«Página X de Y» y el emisor en cada página")

    # Las otras dos plantillas comparten el renderizador: si una se rompió al
    # rediseñarlo, se rompió aquí y no en la factura de venta.
    compra = reg.medir("P17-034-id", "Hay una factura de compra",
                       lambda: _entero(sql("SELECT id FROM factura_compra ORDER BY id DESC LIMIT 1")))
    if compra:
        r = admin.get(f"/api/compras/facturas/{compra}/pdf")
        reg.caso("P17-034", "La factura de COMPRA sigue generándose",
                 condicion=admin.codigo(r) == 200 and r.content[:4] == b"%PDF",
                 severidad="S2",
                 observado=f"HTTP {admin.codigo(r)} · {len(r.content) if r is not None else 0} bytes",
                 esperado="200 con un PDF válido",
                 detalle="comparte DocumentoPdfService con la factura de venta")

    devolucion = reg.medir(
        "P17-035-id", "Hay una devolución con guía de retorno",
        lambda: _entero(sql("""SELECT id FROM devolucion
                               WHERE guia_retorno IS NOT NULL ORDER BY id DESC LIMIT 1""")))
    if devolucion:
        r = admin.get(f"/api/devoluciones/{devolucion}/guia-pdf")
        reg.caso("P17-035", "La guía de retorno del RMA sigue generándose",
                 condicion=admin.codigo(r) == 200 and r.content[:4] == b"%PDF",
                 severidad="S2",
                 observado=f"HTTP {admin.codigo(r)} · {len(r.content) if r is not None else 0} bytes",
                 esperado="200 con un PDF válido",
                 detalle="comparte DocumentoPdfService con las dos facturas")

    # ═══════════════════════════════════ EL ADMIN ENTRA AL RMA (script 113)
    #
    # `grp_administrador` no tenía NI UN GRANT sobre
    # `historial_estado_devolucion` —lo tenían los otros siete grupos del
    # pipeline— y el detalle de una devolución lo lee siempre. Resultado: el
    # ÚNICO rol que puede ejecutar las seis transiciones del RMA no podía ni
    # abrir la pantalla ni descargar la guía. El 403 era un 42501 traducido.
    #
    # Se contrasta contra el rol que ya lo tenía bien (GERENTE) y no contra un
    # número escrito aquí: lo que se afirma es que los dos ven LO MISMO, que es
    # lo que significa «el admin ya entra».
    ger = clientes.get("GERENTE")
    if devolucion and ger is not None:
        r_admin = admin.get(f"/api/devoluciones/{devolucion}")
        r_ger = ger.get(f"/api/devoluciones/{devolucion}")
        hitos_admin = (len((r_admin.json() or {}).get("historial") or [])
                       if admin.codigo(r_admin) == 200 else -1)
        hitos_ger = (len((r_ger.json() or {}).get("historial") or [])
                     if ger.codigo(r_ger) == 200 else -2)
        reg.caso("P17-036", "El ADMIN abre el detalle de una devolución",
                 condicion=admin.codigo(r_admin) == 200, severidad="S2",
                 observado=f"ADMIN {admin.codigo(r_admin)} · GERENTE {ger.codigo(r_ger)}",
                 esperado="200 en los dos",
                 reproducir=admin.curl("GET", f"/api/devoluciones/{devolucion}"),
                 detalle="daba 403 —42501, permission denied for table "
                         "historial_estado_devolucion— hasta el script 113")
        reg.caso("P17-037", "Y ve el MISMO historial que el gerente",
                 condicion=hitos_admin == hitos_ger and hitos_admin > 0,
                 severidad="S2",
                 observado=f"ADMIN {hitos_admin} hitos · GERENTE {hitos_ger}",
                 esperado="mismo número de hitos, y mayor que cero",
                 detalle="con GRANT pero sin política RLS leería CERO FILAS en "
                         "silencio; comparar el contenido distingue eso de un 200 real")

    # El privilegio de ESCRITURA es la otra mitad, y la que no se ve al abrir la
    # pantalla: el `id` de esa tabla es un `serial`, así que el INSERT exige
    # además USAGE sobre su secuencia. Sin ella el admin leería el detalle y la
    # primera transición que intentara moriría con «permission denied for
    # sequence». Se mira el privilegio EFECTIVO en el motor y no por API, porque
    # disparar una transición de verdad movería una devolución real.
    privilegios = reg.medir(
        "P17-038-pg", "Privilegios del admin sobre el historial del RMA",
        lambda: sql(
            "SELECT has_table_privilege('grp_administrador', "
            "'historial_estado_devolucion', 'INSERT')::text || ' ' || "
            "has_sequence_privilege('grp_administrador', "
            "'historial_estado_devolucion_id_seq', 'USAGE')::text").strip())
    reg.caso("P17-038", "El ADMIN puede además escribir un hito del RMA",
             condicion=privilegios == "true true", severidad="S2",
             observado=f"INSERT / USAGE = {privilegios}",
             esperado="true true — la tabla es `serial` y el INSERT necesita las DOS",
             detalle="sin USAGE sobre la secuencia el admin abriría la pantalla y "
                     "fallaría en la primera transición del ciclo")

    return reg


if __name__ == "__main__":
    estado = sys.argv[1] if len(sys.argv) > 1 else "E3"
    reg = correr(estado)
    print("\n" + "=" * 70)
    print(reg.resumen())
    print("informe:", reg.volcar("p17_mejoras"))
    sys.exit(1 if reg.fallos else 0)
