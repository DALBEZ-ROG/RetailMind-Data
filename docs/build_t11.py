# -*- coding: utf-8 -*-
"""
Generador del documento RetailMind_T11_Analisis_Tactico.docx (version Word EDITABLE).
Version 2 (2026-07-21): contenido regenerado desde el catalogo ampliado
docs/tactico/CATALOGO_OBJETIVOS_TACTICOS.md — 68 objetivos tacticos en SEIS
departamentos, cada uno verificado contra la base real retailmind (109 tablas)
via MCP. Incorpora los 11 puntos ciegos aprobados de la auditoria del
cuestionario de negocio (78 preguntas) y corrige los defectos de la version
anterior: decia "siete" departamentos nombrando seis, citaba "~103" tablas y
usaba soportes de datos genericos.
Salida de esta ejecucion: SOLO el archivo .docx (el PDF lo produce el usuario
tras la revision final).
"""
from docx import Document
from docx.shared import Pt, RGBColor, Inches
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.enum.table import WD_TABLE_ALIGNMENT

doc = Document()

# ── Estilos base ──────────────────────────────────────────────────────────
normal = doc.styles["Normal"]
normal.font.name = "Calibri"
normal.font.size = Pt(11)

def _set_heading_color(style_name, size, rgb):
    st = doc.styles[style_name]
    st.font.name = "Calibri"
    st.font.size = Pt(size)
    st.font.color.rgb = RGBColor(*rgb)
    st.font.bold = True

_set_heading_color("Heading 1", 18, (0x1F, 0x3B, 0x5B))
_set_heading_color("Heading 2", 14, (0x1F, 0x3B, 0x5B))
_set_heading_color("Heading 3", 12, (0x2E, 0x5A, 0x88))

# ── Helpers ────────────────────────────────────────────────────────────────
def h(level, text):
    return doc.add_heading(text, level=level)

def p(text="", bold=False, italic=False, size=None, align=None):
    par = doc.add_paragraph()
    run = par.add_run(text)
    run.bold = bold
    run.italic = italic
    if size:
        run.font.size = Pt(size)
    if align:
        par.alignment = align
    return par

def table(headers, rows, widths=None, font_size=9):
    t = doc.add_table(rows=1, cols=len(headers))
    t.style = "Light Grid Accent 1"
    t.alignment = WD_TABLE_ALIGNMENT.CENTER
    hdr = t.rows[0].cells
    for i, htext in enumerate(headers):
        hdr[i].text = ""
        run = hdr[i].paragraphs[0].add_run(htext)
        run.bold = True
        run.font.size = Pt(10)
    for row in rows:
        cells = t.add_row().cells
        for i, val in enumerate(row):
            cells[i].text = ""
            r = cells[i].paragraphs[0].add_run("" if val is None else str(val))
            r.font.size = Pt(font_size)
    if widths:
        for row in t.rows:
            for i, w in enumerate(widths):
                row.cells[i].width = Inches(w)
    return t

def merge_first_col(t, rows_data, key_index=0, font_size=9):
    """Fusiona verticalmente las celdas de la primera columna cuando el valor
    (rows_data[i][key_index]) se repite en filas consecutivas."""
    data_rows = t.rows[1:]
    start = 0
    for i in range(1, len(rows_data) + 1):
        if i == len(rows_data) or rows_data[i][key_index] != rows_data[start][key_index]:
            if i - start > 1:
                merged = data_rows[start].cells[0].merge(data_rows[i - 1].cells[0])
                merged.text = ""
                r = merged.paragraphs[0].add_run(rows_data[start][key_index])
                r.bold = True
                r.font.size = Pt(font_size)
            start = i

CENTER = WD_ALIGN_PARAGRAPH.CENTER

# ══════════════ CATÁLOGO DE OBJETIVOS (fuente única del documento) ════════
# Cada tupla: (departamento, id, objetivo, es_simple, soporte_verificado,
#              estado, visualizacion, roles)
BDR = "BDR (PostgreSQL)"
BDC = "BDColumnar (ClickHouse)"
FH = "FACTIBLE HOY"
RC = "REQUIERE CAMBIO EN EL SISTEMA"
RV = "REQUIERE VOLUMEN DE DATOS"

VEN = "VENTAS"
COM = "COMPRAS"
INV = "INVENTARIO / BODEGA"
LOG = "LOGÍSTICA / DESPACHO"
SOP = "SOPORTE"
GER = "GERENCIA / DIRECCIÓN (incluye Marketing)"

OBJ = [
    # ── VENTAS ────────────────────────────────────────────────────────────
    (VEN, "OTD-VEN-01",
     "Ver toda la cartera de pedidos y en qué paso del proceso está cada uno hoy "
     "(confirmado, pagado, facturado, en preparación, despachado, entregado).",
     True,
     "pedido.estado_pedido_id → estado_pedido.codigo/nombre (11 estados) + "
     "pedido.numero/fecha_pedido/canal/total (34 pedidos reales).",
     FH, "Tabla con filtros por estado y canal", "Vendedor, Gerente, Administrador"),
    (VEN, "OTD-VEN-02",
     "Controlar cuántos pedidos registra cada vendedor y por cuánto dinero, para "
     "evaluar el cumplimiento individual.",
     True,
     "pedido.vendedor_id (FK a usuario.nombre/apellido; poblada en 17/34 — NULL solo "
     "en pedidos en línea, por diseño) + pedido.total.",
     FH, "Tabla de vendedores ordenada por monto", "Gerente, Administrador (el Vendedor ve solo lo propio)"),
    (VEN, "OTD-VEN-03",
     "Conocer los 10 productos que más se venden — el «producto estrella» — en el "
     "período elegido.",
     False,
     "pedido_detalle.producto_variante_id/cantidad/precio_unitario (43 líneas) + "
     "producto_variante.producto_id → producto.nombre.",
     FH, "Barras de los 10 primeros, con filtro de período", "Gerente, Vendedor, Compras, Analista, Administrador"),
    (VEN, "OTD-VEN-04",
     "Conocer los 10 productos que no se venden o llevan más tiempo sin venderse — el "
     "«producto hueso» — para liquidarlos o dejar de comprarlos.",
     False,
     "producto_variante (1221 variantes) cruzada contra pedido_detalle."
     "producto_variante_id; última salida por venta en movimiento_inventario."
     "fecha_creacion + tipo_movimiento.codigo='salida_venta' (43 salidas).",
     FH, "Tabla de rezagados con días sin venta", "Gerente, Compras, Analista, Administrador"),
    (VEN, "OTD-VEN-05",
     "Saber cuánto compra cada cliente: total gastado, número de pedidos y fecha de la "
     "última compra — mirar el negocio desde el cliente, no solo desde la venta.",
     False,
     "pedido.cliente_id/total/fecha_pedido (cliente_id poblado en 34/34) + "
     "cliente.nombre/apellido/email — hoy solo 2 clientes reales (cuentas demo).",
     RV, "Tabla de clientes ordenada por monto acumulado", "Gerente, Vendedor, Analista, Administrador"),
    (VEN, "OTD-VEN-06",
     "Ver cómo evolucionan las ventas mes a mes y por categoría de producto.",
     False,
     "pedido.fecha_pedido + pedido_detalle.cantidad/precio_unitario/monto_descuento + "
     "producto_categoria.categoria_id → categoria.nombre (11 categorías).",
     FH, "Líneas por mes con desglose por categoría", "Gerente, Analista, Administrador"),
    (VEN, "OTD-VEN-07",
     "Conocer el valor promedio de cada pedido por período y por canal de venta "
     "(mostrador, teléfono, tienda en línea).",
     False,
     "pedido.total/fecha_pedido/canal (34 pedidos: 17 web, 9 teléfono, 8 tienda).",
     FH, "Tarjetas y línea temporal por canal", "Gerente, Analista, Administrador"),
    (VEN, "OTD-VEN-08",
     "Detectar los carritos de compra que los clientes dejaron a medias sin llegar a "
     "pagar.",
     True,
     "carrito.estado/fecha_actualizacion + carrito_item.producto_variante_id/cantidad "
     "(19 carritos) — hoy los 19 están en estado 'convertido': ni un abandono que mostrar.",
     RV, "Tabla de carritos inactivos con antigüedad", "Gerente, Vendedor, Administrador"),
    (VEN, "OTD-VEN-09",
     "Saber con qué formas de pago cobran las ventas (efectivo, tarjeta, transferencia) "
     "y cómo cambia esa mezcla en el tiempo.",
     False,
     "pago.metodo_pago_id → metodo_pago.nombre/tipo + pago.monto/fecha_pago (26 pagos).",
     FH, "Participación por forma de pago, por mes", "Gerente, Analista, Administrador"),
    (VEN, "OTD-VEN-10",
     "Atender a tiempo la voz del cliente: reseñas en espera de aprobación y preguntas "
     "sobre productos sin responder.",
     True,
     "resena.estado (4 reseñas: 2 'pendiente', 2 'aprobada') + pregunta_producto.estado "
     "(1) y existencia de respuesta_pregunta (1).",
     FH, "Cola de moderación con antigüedad", "Administrador, Gerente (moderadores del sistema)"),
    (VEN, "OTD-VEN-11",
     "Conocer la calificación que los clientes dan a cada producto y cómo evoluciona.",
     False,
     "resena.calificacion/producto_id/fecha_creacion/compra_verificada — solo 4 reseñas "
     "sobre 1214 productos.",
     RV, "Ranking de productos por calificación", "Gerente, Vendedor, Analista"),
    (VEN, "OTD-VEN-12",
     "Saber cuántos cobros en línea fallan y por qué motivo, para no perder ventas en "
     "el paso del pago.",
     False,
     "Las columnas existen (pago.estado, transaccion_pago.tipo/respuesta_pasarela) pero "
     "registran un único valor: 'completado' en 26/26 y 'captura' en 26/26 — los "
     "intentos fallidos nunca se guardan.",
     RC, "Tabla de intentos fallidos por motivo y período", "Gerente, Administrador"),
    (VEN, "OTD-VEN-13",
     "Saber cuánto vende cada canal — mostrador, teléfono y tienda en línea — y qué "
     "parte de la venta total pone cada uno, por período.",
     False,
     "pedido.canal/total/fecha_pedido — poblados en los 34 pedidos: 17 web ($12 865,11), "
     "9 teléfono ($6 130,19), 8 tienda ($4 817,40); verificado 2026-07-21.",
     FH, "Participación de cada canal en la venta, por mes", "Gerente, Vendedor, Analista, Administrador"),
    (VEN, "OTD-VEN-14",
     "Saber cuánto dinero devuelven los clientes al mes y qué porcentaje de la venta "
     "representa, para frenar a tiempo si se dispara.",
     False,
     "devolucion.monto_total/fecha_creacion (7/7 pobladas por el trigger de totales, "
     "todas > 0) contra pedido.total/fecha_pedido (34 pedidos).",
     FH, "Valor devuelto y porcentaje sobre la venta, mensual",
     "Gerente, Administrador, Analista (Bodega y Despacho NO: es dinero)"),
    (VEN, "OTD-VEN-15",
     "Seguir la venta acumulada del período contra la meta que se fijó, para reaccionar "
     "a media quincena y no enterarse al cierre del mes.",
     True,
     "La venta real ya existe (pedido.total/fecha_pedido), pero NO existe ninguna tabla "
     "de metas o presupuesto en el esquema (verificado contra el catálogo del esquema "
     "2026-07-21).",
     RC, "Avance de venta contra la meta del período", "Gerente, Vendedor, Administrador"),

    # ── COMPRAS ───────────────────────────────────────────────────────────
    (COM, "OTD-COM-01",
     "Ver las órdenes de compra que esperan aprobación y el estado de cada orden en "
     "curso.",
     True,
     "orden_compra.estado/numero/fecha_emision/total + proveedor.razon_social "
     "(16 órdenes: confirmada 1, enviada 1, recibida 12, recibida_parcial 2).",
     FH, "Tabla con filtros por estado y proveedor", "Compras, Gerente, Administrador"),
    (COM, "OTD-COM-02",
     "Controlar cuánto le debemos a cada proveedor y qué cuotas están por vencer o ya "
     "vencieron.",
     True,
     "cuenta_por_pagar.saldo_pendiente/fecha_vencimiento/estado/proveedor_id "
     "(14 cuentas) + proveedor.razon_social.",
     FH, "Tabla de vencimientos con semáforo de fechas", "Compras, Gerente, Administrador"),
    (COM, "OTD-COM-03",
     "Saber si pagamos a los proveedores a tiempo: pagos hechos antes o después de la "
     "fecha de vencimiento, por proveedor y por mes.",
     False,
     "pago_proveedor.fecha_pago/monto/cuenta_por_pagar_id (10 pagos) vs "
     "cuenta_por_pagar.fecha_vencimiento — ambas 100 % pobladas.",
     FH, "Puntualidad de pago por proveedor y mes", "Compras, Gerente, Administrador, Analista"),
    (COM, "OTD-COM-04",
     "Conocer cuánto gastamos en compras por proveedor y por mes.",
     False,
     "factura_compra.total/fecha_emision/proveedor_id (14 facturas; universo actual de "
     "2 proveedores).",
     FH, "Barras por proveedor con evolución mensual", "Compras, Gerente, Administrador, Analista"),
    (COM, "OTD-COM-05",
     "Saber si cada proveedor cumple el compromiso que pactó: comparar la fecha de "
     "entrega que prometió al confirmar la orden contra el día en que la mercancía "
     "llegó de verdad.",
     False,
     "orden_compra.fecha_entrega_esperada existe pero está vacía en las 16 órdenes "
     "(100 % NULL): no hay plazo prometido que comparar contra "
     "recepcion_mercancia.fecha_recepcion.",
     RC, "Cumplimiento de plazo por proveedor", "Compras, Gerente"),
    (COM, "OTD-COM-06",
     "Medir el tiempo real observado del ciclo de compra: cuántos días tarda en la "
     "práctica la mercancía en llegar desde que emitimos la orden.",
     False,
     "orden_compra.fecha_emision + recepcion_mercancia.fecha_recepcion/orden_compra_id "
     "(14 recepciones reales).",
     FH, "Días de ciclo de compra por proveedor y período", "Compras, Gerente, Analista"),
    (COM, "OTD-COM-07",
     "Conocer cuánta mercancía llega en mal estado y se rechaza en la puerta al "
     "recibirla, por proveedor y por motivo.",
     False,
     "recepcion_detalle.cantidad_rechazada/motivo_rechazo/cantidad_recibida — solo 1 de "
     "22 líneas de recepción tiene rechazo registrado.",
     RV, "Porcentaje rechazado por proveedor", "Compras, Gerente; Bodega en cantidades, sin montos"),
    (COM, "OTD-COM-08",
     "Ver los artículos defectuosos pendientes de devolver al proveedor y en qué paso "
     "va cada devolución.",
     True,
     "item_defectuoso.estado/origen/cantidad/proveedor_id (3 ítems) + "
     "devolucion_proveedor.numero/estado (2 devoluciones).",
     FH, "Tablero del pool de defectuosos y devoluciones en curso",
     "Compras, Gerente; Bodega en cantidades, sin montos"),
    (COM, "OTD-COM-09",
     "Saber cuánto recuperamos de los proveedores por mercancía defectuosa: crédito a "
     "favor o reposición de producto.",
     False,
     "devolucion_proveedor.tipo_resolucion/monto_credito/fecha_resolucion + "
     "item_defectuoso.costo_unitario — solo 2 devoluciones registradas (módulo del "
     "2026-07-18).",
     RV, "Monto recuperado por proveedor y período", "Compras, Gerente, Administrador"),
    (COM, "OTD-COM-10",
     "Comparar a qué proveedor conviene comprarle cada producto: costo, plazo de "
     "entrega y proveedor preferido.",
     True,
     "La tabla producto_proveedor (con costo, tiempo_entrega_dias, cantidad_minima, "
     "es_preferido) existe pero tiene 0 filas, pese a 22 líneas de compra reales.",
     RC, "Ficha comparativa de proveedores por producto", "Compras, Gerente"),
    (COM, "OTD-COM-11",
     "Detectar qué proveedores entregan incompleto: comparar lo que se pidió contra lo "
     "que de verdad llegó, línea por línea y por proveedor.",
     True,
     "orden_compra_detalle.cantidad/cantidad_recibida (6 de 24 líneas reales con "
     "recepción menor a la pedida, verificado 2026-07-21) + "
     "orden_compra.proveedor_id/fecha_emision → proveedor.razon_social.",
     FH, "Líneas incompletas y porcentaje de cumplimiento por proveedor",
     "Compras, Gerente; Bodega en cantidades, sin montos"),
    (COM, "OTD-COM-12",
     "Saber si está subiendo el costo de lo que compramos: cómo cambia el precio que "
     "cobra el proveedor por cada producto entre una compra y la siguiente.",
     False,
     "orden_compra_detalle.precio_unitario/producto_variante_id (11 precios distintos "
     "en las 24 líneas, verificado 2026-07-21) + orden_compra.fecha_emision/"
     "proveedor_id — cada línea de compra conserva su precio a esa fecha.",
     FH, "Evolución del costo de compra por producto y proveedor", "Compras, Gerente, Analista"),

    # ── INVENTARIO / BODEGA ───────────────────────────────────────────────
    (INV, "OTD-INV-01",
     "Detectar los productos que están por debajo de su tope mínimo y hay que reponer.",
     True,
     "inventario.stock_actual vs inventario.stock_minimo + "
     "producto_variante_id/bodega_id (1227 filas pobladas).",
     FH, "Lista de reposición con faltante por bodega", "Bodega, Compras, Gerente, Administrador"),
    (INV, "OTD-INV-02",
     "Consultar las existencias actuales de cada producto en cada bodega, incluyendo lo "
     "apartado para pedidos.",
     True,
     "inventario.stock_actual/stock_reservado/bodega_id + bodega.nombre (2 bodegas).",
     FH, "Tabla de existencias con buscador", "Bodega, Compras, Vendedor, Gerente, Administrador"),
    (INV, "OTD-INV-03",
     "Revisar la historia completa de entradas y salidas de cualquier producto: qué se "
     "movió, cuándo, cuánto y por qué razón.",
     True,
     "movimiento_inventario.cantidad/stock_anterior/stock_nuevo/fecha_creacion/"
     "referencia_tipo + tipo_movimiento.codigo/nombre (98 movimientos, 9 tipos en uso).",
     FH, "Kardex por producto con filtro de tipo y fecha", "Bodega, Gerente, Administrador"),
    (INV, "OTD-INV-04",
     "Saber qué categorías de producto rotan más y cuáles se quedan paradas en bodega, "
     "por período.",
     False,
     "movimiento_inventario.cantidad/fecha_creacion/tipo_movimiento_id + "
     "producto_categoria.categoria_id → categoria.nombre (1214 asignaciones, 11 "
     "categorías).",
     FH, "Rotación por categoría y período", "Gerente, Analista, Administrador; Bodega en cantidades"),
    (INV, "OTD-INV-05",
     "Controlar la mercancía perdida o sobrante detectada en los ajustes de inventario "
     "y sus motivos.",
     True,
     "ajuste_inventario.tipo/motivo/estado/fecha_aplicacion (3 ajustes) + movimientos "
     "entrada_ajuste/salida_ajuste del kardex.",
     FH, "Lista de ajustes con motivo y cantidades", "Bodega, Gerente, Administrador"),
    (INV, "OTD-INV-06",
     "Seguir las transferencias de mercancía entre bodegas: cuáles van en camino y "
     "cuáles ya se recibieron.",
     True,
     "transferencia_bodega.estado/fecha_envio/fecha_recepcion/bodega_origen_id/"
     "bodega_destino_id (10 transferencias, todas 'recibida').",
     FH, "Tabla de transferencias por estado", "Bodega, Gerente, Administrador"),
    (INV, "OTD-INV-07",
     "Saber cuánto dinero hay parado en mercancía almacenada, por categoría y por "
     "bodega.",
     True,
     "inventario.stock_actual × producto_variante.costo (costo poblado en las 1221 "
     "variantes) + producto_categoria/categoria.nombre.",
     FH, "Valor del inventario por categoría/bodega",
     "Gerente, Administrador, Analista (Bodega NO: es dinero)"),
    (INV, "OTD-INV-08",
     "Detectar productos con demasiada existencia — por encima del tope máximo deseado "
     "— para no enterrar dinero en mercancía de más.",
     True,
     "inventario.stock_maximo existe pero está vacía en las 1227 filas (100 % NULL): "
     "no hay tope contra el cual comparar.",
     RC, "Lista de sobre-stock por bodega", "Bodega, Compras, Gerente"),
    (INV, "OTD-INV-09",
     "Ver cómo evoluciona mes a mes el dinero inmovilizado en la mercancía almacenada, "
     "para saber si la bodega se está llenando o vaciando de capital.",
     False,
     "Reconstrucción del stock al cierre de cada mes: inventario.stock_actual (1227 "
     "filas) menos los movimientos posteriores del kardex — movimiento_inventario."
     "cantidad/fecha_creacion (98, con stock_anterior/stock_nuevo 98/98 como respaldo) "
     "y el signo de tipo_movimiento.factor (±1 en los 9 tipos) — valorizado con "
     "producto_variante.costo (1221 variantes). Usa el costo vigente: no existe "
     "histórico de costos.",
     FH, "Línea mensual del valor almacenado por bodega y categoría",
     "Gerente, Administrador, Analista (Bodega NO: es dinero)"),
    (INV, "OTD-INV-10",
     "Conocer las mermas (mercancía perdida) y los sobrantes acumulados por período y "
     "por motivo, para atacar las causas de la pérdida.",
     False,
     "ajuste_inventario.tipo/motivo/fecha_aplicacion (3 ajustes: 2 negativos, 1 "
     "positivo) + kardex movimiento_inventario.cantidad con tipo_movimiento.codigo "
     "'salida_ajuste' (2) / 'entrada_ajuste' (1), enlazados por referencia_tipo="
     "'ajuste_inventario' — solo 3 ajustes registrados.",
     RV, "Acumulado por motivo y mes",
     "Bodega en cantidades; valorizado solo Gerente, Administrador"),

    # ── LOGÍSTICA / DESPACHO ──────────────────────────────────────────────
    (LOG, "OTD-LOG-01",
     "Ver la cola de pedidos listos y en espera de ser despachados.",
     True,
     "pedido.estado_pedido_id → estado_pedido.codigo='preparado' + "
     "pedido.numero/fecha_pedido.",
     FH, "Cola de trabajo de despacho", "Despacho, Gerente, Administrador"),
    (LOG, "OTD-LOG-02",
     "Seguir los envíos: cuáles van en camino, cuáles ya se entregaron, cuáles "
     "volvieron, y con qué transportista y número de guía viaja cada uno.",
     True,
     "envio.estado/numero_guia/fecha_despacho (24 envíos: 11 en tránsito, 11 "
     "entregados, 2 devueltos) + transportista.nombre (2 transportistas).",
     FH, "Tablero de envíos por estado y transportista", "Despacho, Gerente, Administrador"),
    (LOG, "OTD-LOG-03",
     "Saber si cumplimos la fecha de entrega prometida al cliente: de los envíos ya "
     "entregados, cuántos llegaron a más tardar el día prometido, por transportista.",
     False,
     "envio.fecha_entrega_estimada vs envio.fecha_entrega_real (11 entregas reales) + "
     "envio.transportista_id.",
     FH, "Cumplimiento de fecha prometida por transportista", "Despacho, Gerente, Analista"),
    (LOG, "OTD-LOG-04",
     "Medir la duración real del tránsito — los días desde que el paquete sale de "
     "bodega hasta la puerta del cliente — para comparar transportistas entre sí.",
     False,
     "envio.fecha_despacho vs envio.fecha_entrega_real (ambas pobladas en los 11 "
     "envíos entregados).",
     FH, "Días de tránsito promedio por transportista y período", "Despacho, Gerente, Analista"),
    (LOG, "OTD-LOG-05",
     "Controlar los problemas de entrega — cliente ausente, dirección equivocada, "
     "rechazo en la puerta, daño en el camino — cuántos ocurren, cuántos intentos "
     "toman y cómo terminan.",
     False,
     "novedad_envio.tipo/estado/accion/intento_numero/fecha_registro/fecha_resolucion "
     "— solo 6 novedades registradas (módulo del 2026-07-18).",
     RV, "Problemas de entrega por tipo y desenlace", "Despacho, Gerente, Soporte"),
    (LOG, "OTD-LOG-06",
     "Ver las devoluciones de clientes en curso y en qué paso va cada una (solicitada, "
     "en revisión, aprobada, en camino de vuelta, recibida, inspeccionada, "
     "reembolsada, cerrada).",
     True,
     "devolucion.numero/estado/guia_retorno/fecha_creacion (7 devoluciones: 6 "
     "cerradas, 1 inspeccionada).",
     FH, "Tablero del ciclo de devolución por estado",
     "Despacho, Soporte, Gerente; Bodega ve su tramo de inspección, sin montos"),
    (LOG, "OTD-LOG-07",
     "Medir cuántos días tarda una devolución de cliente desde que se solicita hasta "
     "que se cierra.",
     False,
     "devolucion.fecha_creacion + historial_estado_devolucion.estado/fecha_creacion "
     "(18 registros) — la mayoría de devoluciones son casos antiguos migrados directo "
     "a 'cerrada', sin tiempos intermedios reales.",
     RV, "Días de ciclo de devolución por período", "Gerente, Soporte, Analista"),
    (LOG, "OTD-LOG-08",
     "Saber por qué devuelven los clientes y qué pasa con esa mercancía: vuelve a "
     "venderse, resulta defectuosa o se rechaza sin reembolso.",
     False,
     "devolucion.motivo_devolucion_id → motivo_devolucion.nombre (4 motivos) + "
     "devolucion_detalle.resultado_inspeccion/cantidad — solo 9 líneas de devolución "
     "inspeccionables.",
     RV, "Motivos de devolución y destino de la mercancía",
     "Gerente, Soporte, Analista; Bodega en cantidades"),
    (LOG, "OTD-LOG-09",
     "Saber, de cada 100 envíos, cuántos terminan en devolución, mes a mes.",
     False,
     "envio.fecha_despacho (24 envíos) vs devolucion.pedido_id/fecha_creacion "
     "(7 devoluciones).",
     FH, "Proporción de devoluciones sobre envíos, mensual",
     "Gerente, Analista; Despacho en conteos"),
    (LOG, "OTD-LOG-10",
     "Controlar el dinero devuelto a los clientes: cuánto se reembolsó, por qué vía y "
     "por qué motivo.",
     False,
     "La tabla reembolso (monto/estado/fecha_procesado/pago_id/devolucion_id) existe "
     "pero tiene 0 filas; el único reembolso real vive disperso en devolucion."
     "monto_reembolsado/metodo_reembolso/fecha_reembolso (1 de 7).",
     RC, "Reembolsos pagados por período y vía",
     "Gerente, Administrador, Soporte (Despacho NO: es dinero)"),
    (LOG, "OTD-LOG-11",
     "Saber cuánto nos cuesta llevar cada envío, por zona y transportista, para "
     "revisar las tarifas que cobramos al cliente.",
     False,
     "envio.costo existe (NOT NULL) pero vale 0.00 en los 24 envíos: nunca se calcula "
     "desde tarifa_envio.",
     RC, "Costo de envío real vs cobrado, por zona/transportista",
     "Gerente, Administrador (Despacho NO: es dinero)"),
    (LOG, "OTD-LOG-12",
     "Medir cuánto tarda un pedido en cada etapa del camino — del pago a la "
     "preparación, de la preparación al despacho y del despacho a la entrega — para "
     "encontrar el cuello de botella real y no suponerlo.",
     False,
     "historial_estado_pedido.pedido_id/estado_pedido_id/fecha_creacion (177 registros "
     "que cubren los 34 pedidos, verificado 2026-07-21) + estado_pedido.codigo — cada "
     "transición quedó fechada, lo que permite medir cada tramo.",
     FH, "Días u horas promedio por etapa del ciclo, por período",
     "Despacho (tiempos y estados, sin dinero), Gerente, Analista"),

    # ── SOPORTE ───────────────────────────────────────────────────────────
    (SOP, "OTD-SOP-01",
     "Ver los reclamos y consultas abiertos: cuántos hay, de qué tipo, qué tan "
     "urgentes son y quién atiende cada uno.",
     True,
     "ticket_soporte.numero/estado/prioridad/categoria_ticket_id/asignado_usuario_id "
     "(12 tickets: 4 abiertos, 3 en proceso, 2 esperando cliente, 1 resuelto, 2 "
     "cerrados).",
     FH, "Bandeja con filtros por estado, urgencia y categoría", "Soporte, Gerente, Administrador"),
    (SOP, "OTD-SOP-02",
     "Saber cuántos reclamos atendemos dentro del tiempo prometido al cliente según su "
     "urgencia.",
     False,
     "No existe columna de fecha límite en ticket_soporte: el plazo prometido (2 h/4 h/"
     "24 h/72 h según urgencia) vive solo en código del backend, y fecha_cierre está "
     "poblada en apenas 2 de 12 tickets.",
     RC, "Cumplimiento del tiempo prometido por período", "Soporte, Gerente"),
    (SOP, "OTD-SOP-03",
     "Medir cuántas horas o días tardamos en resolver cada tipo de problema.",
     False,
     "ticket_soporte.fecha_creacion vs ticket_soporte.fecha_cierre + "
     "categoria_ticket.nombre — solo 2 de 12 tickets tienen fecha de cierre.",
     RV, "Tiempo de resolución por categoría", "Soporte, Gerente, Analista"),
    (SOP, "OTD-SOP-04",
     "Saber qué tipos de problema generan más reclamos, para atacar la causa de fondo.",
     True,
     "ticket_soporte.categoria_ticket_id → categoria_ticket.nombre (Devolución 5, "
     "Envíos 3, Facturación 2, Reclamo 1; 4 de 8 categorías sin tickets).",
     FH, "Distribución de tickets por categoría", "Soporte, Gerente"),
    (SOP, "OTD-SOP-05",
     "Repartir bien el trabajo del equipo: reclamos asignados y resueltos por cada "
     "persona de soporte.",
     True,
     "ticket_soporte.asignado_usuario_id → usuario.nombre/apellido + "
     "ticket_soporte.estado.",
     FH, "Carga y cierre por agente", "Soporte, Gerente, Administrador"),
    (SOP, "OTD-SOP-06",
     "Medir cuánto tardamos en dar la primera respuesta al cliente que reclama: desde "
     "que abre su caso hasta que alguien del equipo le contesta por primera vez.",
     False,
     "ticket_soporte.fecha_creacion vs el primer mensaje_ticket.fecha_creacion cuyo "
     "autor es del equipo (mensaje_ticket.usuario_id poblado) y visible al cliente "
     "(es_interno = false) — 8 de 12 tickets ya tienen respuesta visible del equipo "
     "(verificado 2026-07-21).",
     FH, "Horas hasta la primera respuesta, por período y urgencia", "Soporte, Gerente"),
    (SOP, "OTD-SOP-07",
     "Medir cuánto tarda cada persona del equipo de soporte en resolver los casos que "
     "se le asignan.",
     False,
     "ticket_soporte.asignado_usuario_id → usuario.nombre/apellido + ticket_soporte."
     "fecha_creacion/fecha_cierre — solo 2 de 12 tickets con fecha de cierre (la misma "
     "limitante de OTD-SOP-03, ahora abierta por agente).",
     RV, "Tiempo de resolución por agente y período", "Soporte, Gerente"),
    (SOP, "OTD-SOP-08",
     "Saber qué productos generan más reclamos y más devoluciones, para pedir a "
     "Compras que revise el producto o a Ventas que corrija su descripción.",
     False,
     "Mitad ya posible: devoluciones por producto vía devolucion_detalle."
     "pedido_detalle_id → pedido_detalle.producto_variante_id → producto.nombre (las 9 "
     "líneas de devolución enlazan a su producto, verificado 2026-07-21). Mitad que "
     "exige cambio: los reclamos NO se pueden ligar a un producto — ticket_soporte "
     "solo referencia cliente_id y pedido_id, sin columna de producto.",
     RC, "Ranking de productos por reclamos y devoluciones", "Soporte, Gerente, Compras"),

    # ── GERENCIA / DIRECCIÓN ──────────────────────────────────────────────
    (GER, "OTD-GER-01",
     "Tener la foto del día del negocio: pedidos de hoy, dinero cobrado hoy y "
     "pendientes que necesitan decisión.",
     True,
     "pedido.fecha_pedido/total/estado_pedido_id + pago.fecha_pago/monto (26 pagos) + "
     "estado_pedido.nombre.",
     FH, "Tarjetas de resumen del día", "Gerente, Administrador"),
    (GER, "OTD-GER-02",
     "Comparar mes a mes el dinero que entra por ventas contra el dinero que sale "
     "hacia proveedores — la balanza comercial interna del negocio.",
     False,
     "Facturado: factura_venta.total/fecha_emision (30) vs factura_compra.total/"
     "fecha_emision (14). En caja: pago.monto/fecha_pago (26) vs pago_proveedor.monto/"
     "fecha_pago (10).",
     FH, "Barras enfrentadas entradas vs salidas, por mes", "Gerente, Administrador, Analista"),
    (GER, "OTD-GER-03",
     "Saber qué categorías de producto dejan más ganancia (venta menos costo), por "
     "período.",
     False,
     "pedido_detalle.precio_unitario/cantidad/monto_descuento + producto_variante."
     "costo (poblado en 1221 variantes) + producto_categoria/categoria.nombre.",
     FH, "Ganancia por categoría y período", "Gerente, Analista, Administrador"),
    (GER, "OTD-GER-04",
     "Ver los cupones de descuento activos, cuántos usos les quedan y cuándo vencen.",
     True,
     "cupon.codigo/activo/usos_actuales/usos_maximos/fecha_fin (6 cupones).",
     FH, "Tabla de cupones con vigencia y usos restantes", "Gerente, Administrador"),
    (GER, "OTD-GER-05",
     "Saber qué cupones usaron efectivamente los clientes y cuánto descuento le "
     "costaron al negocio, por período.",
     False,
     "uso_cupon.cupon_id/monto_descontado/fecha_creacion — solo 3 usos de cupón "
     "registrados.",
     RV, "Descuento otorgado por cupón y período", "Gerente, Analista, Administrador"),
    (GER, "OTD-GER-06",
     "Ver las acciones de marketing vigentes: promociones (y los productos que "
     "abarcan), campañas y anuncios de la tienda.",
     True,
     "promocion.fecha_inicio/fecha_fin/activo (1) + promocion_producto.producto_id (2) "
     "+ campana.estado/fecha_inicio/fecha_fin (1) + banner.activo/fecha_inicio (1).",
     FH, "Panel de vigencias de marketing", "Gerente, Administrador"),
    (GER, "OTD-GER-07",
     "Saber si las promociones hacen vender más, comparando las ventas del producto "
     "antes y durante la promoción.",
     False,
     "pedido_detalle.monto_descuento (>0 = línea con promoción aplicada) + promocion."
     "fecha_inicio/fecha_fin + promocion_producto — solo 1 promoción con 2 productos.",
     RV, "Ventas antes/durante promoción", "Gerente, Analista"),
    (GER, "OTD-GER-08",
     "Revisar quién hizo qué en el sistema: aprobaciones, despachos, registros y "
     "moderaciones, con autor, fecha y detalle del cambio.",
     True,
     "log_auditoria.usuario_id/tabla/accion/registro_id/datos_anteriores/datos_nuevos/"
     "fecha_creacion (39 registros reales).",
     FH, "Registro de acciones filtrable por usuario, tabla y fecha", "Administrador, Gerente"),
    (GER, "OTD-GER-09",
     "Saber quién intentó entrar al sistema y falló, desde dónde y por qué.",
     True,
     "La tabla log_acceso (exitoso/motivo_fallo/ip_origen/email_intentado) existe "
     "completa pero tiene 0 filas: el inicio de sesión nunca la escribe.",
     RC, "Intentos de acceso fallidos recientes", "Administrador, Gerente"),
    (GER, "OTD-GER-10",
     "Conocer la ganancia real de cada producto — la diferencia entre lo que costó y "
     "lo que se cobró — además de la vista por categoría, para corregir precios "
     "producto por producto.",
     False,
     "producto_variante.precio/costo (ambos poblados en las 1221 variantes, verificado "
     "2026-07-21) + pedido_detalle.producto_variante_id/cantidad/precio_unitario/"
     "monto_descuento (43 líneas). Usa el costo vigente: no existe histórico de "
     "costos.",
     FH, "Margen por producto con buscador y filtro de período",
     "Gerente, Analista, Administrador"),
    (GER, "OTD-GER-11",
     "Controlar cuánto descuento se entrega en total cada mes — sumando promociones y "
     "cupones — y sobre qué productos recae, para que el descuento no se coma el "
     "margen sin que nadie lo mida.",
     False,
     "pedido_detalle.monto_descuento (promociones por línea; hoy solo 2 líneas > 0) + "
     "pedido.monto_descuento (cupones; 3 pedidos > 0) + uso_cupon.monto_descontado "
     "(3 usos, $52,91 en total), con el prorrateo por producto ya resuelto en "
     "factura_venta_detalle.monto_descuento (verificado 2026-07-21).",
     RV, "Descuento total entregado por mes y por producto",
     "Gerente, Analista, Administrador"),
]

assert len(OBJ) == 68, "El catálogo debe tener exactamente 68 objetivos"
N_SIMPLES = sum(1 for o in OBJ if o[3])
N_COMPUESTOS = sum(1 for o in OBJ if not o[3])
assert (N_SIMPLES, N_COMPUESTOS) == (28, 40), "Cuadre simples/compuestos roto"
assert sum(1 for o in OBJ if o[5] == FH) == 44
assert sum(1 for o in OBJ if o[5] == RC) == 10
assert sum(1 for o in OBJ if o[5] == RV) == 14

# ══════════════════════════════ PORTADA ═══════════════════════════════════
p()
p("UNIVERSIDAD TÉCNICA ESTATAL DE QUEVEDO", bold=True, size=18, align=CENTER)
p("_______________________________________", align=CENTER)
p()
p("Facultad: ______________________________", size=12, align=CENTER)
p("Carrera: _______________________________", size=12, align=CENTER)
p()
p("Asignatura", bold=True, size=12, align=CENTER)
p("Construcción de Software", size=12, align=CENTER)
p()
p("TAREA 11", bold=True, size=16, align=CENTER)
p("Análisis del Nivel Táctico del Sistema RetailMind:", bold=True, size=14, align=CENTER)
p("Objetivos Tácticos por Departamento y Clasificación de Informes", bold=True, size=14, align=CENTER)
p("(Simples — BDR / Compuestos — BD Columnar)", size=12, align=CENTER)
p("Versión 2 — catálogo ampliado y verificado contra la base de datos real", size=11, align=CENTER)
p()
p("Estudiante", bold=True, size=12, align=CENTER)
p("Benites Pérez Dariem Alberto", size=12, align=CENTER)
p()
p("Docente: ______________________________", size=12, align=CENTER)
p("Semestre: Sexto — Fecha: ______________", size=12, align=CENTER)
doc.add_page_break()

# ══════════════════════════════ 1. INTRODUCCIÓN ═══════════════════════════
h(1, "1. Introducción")
p("El sistema RetailMind concluyó la construcción de su nivel operativo: registro de las "
  "transacciones diarias de la PyME (ciclo completo de venta con pagos y facturación, ciclo de "
  "compra con aprobaciones, inventario con kardex, logística de despacho y devoluciones, soporte "
  "al cliente y marketing). El presente documento da el siguiente paso en la pirámide "
  "informacional de la organización: el análisis del nivel táctico.")
p("Mientras el nivel operativo responde a la pregunta «¿qué transacción ocurre ahora?», el nivel "
  "táctico responde a «¿cómo se dirige y controla cada área?». Un objetivo táctico es una "
  "necesidad de información planteada por el jefe de cada departamento, orientada a la dirección, "
  "el seguimiento y el control de su área en el mediano plazo. A partir de esos objetivos se "
  "derivan los informes tácticos que el sistema debe producir.")
p("Los objetivos tácticos se levantaron para los seis departamentos del negocio: Ventas, "
  "Compras, Inventario/Bodega, Logística/Despacho, Soporte y Gerencia/Dirección (que dirige "
  "también Marketing). El número de objetivos por departamento no es una cuota: sale de lo que "
  "cada área realmente necesita dirigir, y por eso es asimétrico.")

h(2, "1.1. Informes simples y compuestos: regla de clasificación")
p("Cada informe táctico se clasifica con una regla explícita, que resuelve los casos ambiguos "
  "(por ejemplo, «ventas por vendedor» y «ventas por cajero», consultas casi idénticas que en el "
  "enunciado aparecían en lados distintos):")
par = doc.add_paragraph(style="List Bullet")
r = par.add_run("Informe SIMPLE: ")
r.bold = True
par.add_run("responde sobre el ESTADO ACTUAL del área. Puede contar o sumar sobre la foto de "
            "hoy, pero NO recorre histórico ni compara períodos. Se resuelve con una consulta "
            "directa a la base de datos relacional (BDR PostgreSQL). Ejemplo: pedidos "
            "preparados en espera de despacho.")
par = doc.add_paragraph(style="List Bullet")
r = par.add_run("Informe COMPUESTO: ")
r.bold = True
par.add_run("requiere recorrer datos históricos, comparar entre períodos o cruzar varias "
            "fuentes con agregación. Se procesa en la base de datos columnar (BDColumnar "
            "ClickHouse), alimentada mediante un proceso ETL orquestado con Apache Airflow. "
            "Ejemplo: tendencia de ventas por mes y categoría.")
p("Dos precisiones para los casos limítrofes: (1) sumar o contar sobre el presente no convierte "
  "un informe en compuesto — «ventas por vendedor» es simple mientras totalice la cartera "
  "actual, y solo se vuelve compuesto cuando compara mes contra mes; (2) una consulta puntual de "
  "detalle que lista filas antiguas sin agregarlas (el historial de movimientos de UN producto, "
  "el registro de auditoría filtrado) sigue siendo simple: es una consulta directa filtrada, no "
  "un barrido agregado del histórico.")

h(2, "1.2. Arquitectura híbrida del sistema real")
p("RetailMind ya materializa esta separación con una arquitectura híbrida de dos motores: "
  "PostgreSQL como única base transaccional (BDR, base retailmind con 109 tablas operativas en "
  "el esquema public — conteo verificado contra el catálogo del motor —, seguridad por roles, "
  "RLS y triggers de negocio) y ClickHouse como base columnar dedicada exclusivamente a "
  "analítica. Los informes simples de este análisis se resuelven con consultas directas sobre "
  "PostgreSQL; los compuestos justifican el pipeline ETL (extracción desde la BDR, "
  "transformación y carga en ClickHouse) cuya orquestación con Airflow constituye la fase "
  "pendiente del proyecto.")

# ══════════════════════════════ 2. METODOLOGÍA ════════════════════════════
h(1, "2. Metodología")
p("El análisis siguió el proceso enseñado en clase, en este orden estricto:")
par = doc.add_paragraph(style="List Number")
par.add_run("Primero se levantaron las preguntas de negocio de cada jefatura SIN mirar el "
            "esquema de la base de datos: cada jefe departamental, como proveedor de objetivos "
            "tácticos, dijo qué necesita para dirigir y controlar su área, en su propio "
            "lenguaje. En esta fase se levantaron 78 preguntas de negocio, cada una acompañada "
            "de la decisión concreta que su respuesta permite tomar.")
par = doc.add_paragraph(style="List Number")
par.add_run("Después se verificó contra la base de datos real cuáles de esas preguntas puede "
            "responder: tabla por tabla y columna por columna se confirmó que el dato exista Y "
            "esté poblado — no basta que la columna esté declarada en el esquema si ningún "
            "flujo la escribe. Toda cita de tablas y columnas de este documento proviene de "
            "consultas reales de solo lectura ejecutadas contra la base retailmind.")
par = doc.add_paragraph(style="List Number")
par.add_run("Donde la base dijo que NO, se identificó exactamente qué falta en TRES capas antes "
            "de construir el ETL: la base de datos (columna o tabla), el backend (el flujo que "
            "debe escribir el dato) y el formulario de la aplicación (donde el usuario lo "
            "captura). No basta con agregar la columna: si nadie la llena desde la pantalla, el "
            "informe seguirá vacío.")
p("El resultado son 68 objetivos tácticos verificados: 44 respondibles hoy, 10 que exigen un "
  "cambio puntual en el sistema (sección 5, con las tres capas de cada cambio) y 14 cuyo esquema "
  "y flujo están completos pero necesitan volumen de operación real para que el informe agregado "
  "sea representativo (el entorno actual es de desarrollo/demostración).")

# ═══════════ 3. TABLA PRINCIPAL DE OBJETIVOS TÁCTICOS ═════════════════════
doc.add_page_break()
h(1, "3. Objetivos tácticos por departamento y clasificación de informes")
p("La siguiente tabla consolida los 68 objetivos tácticos provistos por las seis jefaturas "
  "departamentales, la clasificación de su informe (simple o compuesto) y la base de datos de "
  "la que se obtiene cada uno.")

filas_principal = [
    (o[0], o[2], "X" if o[3] else "—", "—" if o[3] else "X", BDR if o[3] else BDC)
    for o in OBJ
]
t = table(
    ["DEPARTAMENTO", "OBJETIVO TÁCTICO", "¿ES UN INFORME SIMPLE?",
     "¿ES UN INFORME COMPUESTO?", "FUENTE (BDR PostgreSQL / BDColumnar ClickHouse)"],
    filas_principal,
    widths=[1.0, 2.6, 0.7, 0.8, 1.4],
    font_size=8,
)
merge_first_col(t, filas_principal)

# ═══════════ 4. VERIFICACIÓN DE FACTIBILIDAD ══════════════════════════════
doc.add_page_break()
h(1, "4. Verificación de factibilidad sobre la base de datos real")
p("Cada objetivo fue contrastado contra la base retailmind con consultas de solo lectura: se "
  "citan las tablas y columnas concretas que lo sustentan y el conteo real observado, y se "
  "declara su estado. Los estados posibles son: FACTIBLE HOY (el dato existe y está poblado), "
  "REQUIERE CAMBIO EN EL SISTEMA (falta que alguna capa escriba el dato; ver sección 5) y "
  "REQUIERE VOLUMEN DE DATOS (esquema y flujo completos, pero la operación registrada aún es de "
  "entorno de demostración).")

filas_verif = [(o[0], o[1], o[4], o[5]) for o in OBJ]
t = table(
    ["DEPTO.", "ID", "SOPORTE DE DATOS VERIFICADO (tablas.columnas y conteos reales)", "ESTADO"],
    [(f[0], f[1], f[2], f[3]) for f in filas_verif],
    widths=[0.8, 1.0, 3.5, 1.2],
    font_size=8,
)
merge_first_col(t, filas_verif, font_size=8)

p()
par = doc.add_paragraph()
r = par.add_run("Nota de verificación: ")
r.bold = True
r.italic = True
r2 = par.add_run(
    "ninguna cita de esta tabla es genérica ni inferida: cada tabla.columna y cada conteo (34 "
    "pedidos, 177 transiciones de estado, 1221 variantes con precio y costo, 6 líneas de compra "
    "con recepción incompleta, 8 de 12 tickets con primera respuesta del equipo, etc.) proviene "
    "de una consulta real ejecutada contra la base el 2026-07-21. Cuando una columna existe pero "
    "está vacía (por ejemplo la fecha de entrega prometida de la orden de compra, 100 % NULL en "
    "16 órdenes), el objetivo se declara honestamente como REQUIERE CAMBIO EN EL SISTEMA en vez "
    "de darlo por cubierto.")
r2.italic = True

# ═══════════ 5. CIERRE DE BRECHAS ═════════════════════════════════════════
doc.add_page_break()
h(1, "5. Cierre de brechas: qué falta y en qué capa")
p("Los 10 objetivos que la base no puede responder hoy exigen cada uno un cambio identificado "
  "en las tres capas del sistema — base de datos, backend y formulario — con su esfuerzo "
  "estimado. Esta sección aplica el método completo: donde la base dijo que no, se dice "
  "exactamente qué falta y dónde.")

brechas = [
    ("OTD-VEN-12",
     "Los intentos de cobro fallidos nunca se registran (pago.estado = 'completado' en 26/26).",
     "Ninguno — pago.estado y transaccion_pago.tipo/respuesta_pasarela ya existen.",
     "Simulación de pasarela del checkout: al rechazar un pago (tarjeta inválida, fondos), "
     "registrar la transacción con tipo de rechazo y su motivo antes de devolver el error.",
     "El formulario de pago ya captura todo; solo mostrar el motivo del rechazo al cliente.",
     "MEDIO"),
    ("OTD-VEN-15",
     "No existe ninguna tabla de metas de venta: la venta real está, pero no hay meta contra la "
     "cual compararla.",
     "Tabla NUEVA de metas por período y departamento (la única tabla nueva que pide el "
     "catálogo), con sus permisos y política de horario según el patrón vigente.",
     "CRUD de metas: crear, editar y consultar el avance contra la venta real.",
     "Formulario de captura de metas para Gerencia (período, departamento, monto).",
     "MEDIO"),
    ("OTD-COM-05",
     "Plazo de entrega prometido: orden_compra.fecha_entrega_esperada 100 % NULL (0/16).",
     "Ninguno — la columna existe.",
     "Creación/edición de la orden de compra: aceptar y escribir la fecha prometida.",
     "Campo «Fecha de entrega prometida» en el formulario de orden de compra.",
     "BAJO"),
    ("OTD-COM-10",
     "Catálogo proveedor–producto: producto_proveedor con 0 filas.",
     "Ninguno — la tabla existe con costo, tiempo_entrega_dias y es_preferido.",
     "CRUD de producto-proveedor; opcional: alimentarla automáticamente con el costo de cada "
     "recepción.",
     "Sección «Productos que ofrece» en la ficha del proveedor, con costo, plazo y marcador de "
     "preferido.",
     "MEDIO"),
    ("OTD-INV-08",
     "Tope máximo de stock: inventario.stock_maximo 100 % NULL (0/1227).",
     "Ninguno — la columna existe.",
     "El endpoint de inventario que hoy edita stock_minimo debe aceptar también stock_maximo.",
     "Campo «Stock máximo» junto al mínimo en el formulario de inventario/producto.",
     "BAJO"),
    ("OTD-LOG-10",
     "Reembolsos sin registro transaccional: tabla reembolso con 0 filas.",
     "Ninguno — la tabla existe.",
     "En la transición a 'reembolsada' de la devolución: insertar además la fila en reembolso "
     "con monto, vía y fecha.",
     "Ninguno — la pantalla de reembolso del tablero de devoluciones ya captura monto y vía.",
     "BAJO"),
    ("OTD-LOG-11",
     "Costo real del envío: envio.costo = 0.00 en 24/24.",
     "Ninguno — la columna existe (NOT NULL) y tarifa_envio tiene las tarifas.",
     "Al crear el envío, calcular el costo desde la tarifa activa de la zona del pedido (la "
     "cadena dirección→zona→tarifa ya existe para asignar transportista).",
     "Ninguno de captura (cálculo automático); mostrar el costo del envío solo a roles con "
     "acceso a montos.",
     "BAJO"),
    ("OTD-SOP-02",
     "El tiempo prometido del ticket no es consultable: sin columna de fecha límite; el plazo "
     "por urgencia vive en código.",
     "Agregar columna de fecha límite a ticket_soporte (+ permisos y RLS según el patrón del "
     "rol de soporte).",
     "Persistir la fecha límite al crear el ticket (hoy se calcula al vuelo) y recalcularla si "
     "Soporte/Administración cambia la urgencia.",
     "Ninguno de captura — la bandeja ya muestra «vence en/VENCIDO»; pasaría a leer la columna.",
     "MEDIO"),
    ("OTD-SOP-08",
     "El reclamo no se puede ligar a un producto: ticket_soporte solo referencia cliente y "
     "pedido. La mitad de devoluciones por producto YA es factible hoy y no espera al cambio.",
     "Columna opcional de producto en ticket_soporte (+ permisos y RLS según el patrón del rol "
     "de soporte).",
     "Escribir el producto al crear el ticket cuando el cliente lo indique.",
     "Selector opcional de producto en el formulario de creación del ticket.",
     "BAJO"),
    ("OTD-GER-09",
     "Intentos de entrada: log_acceso con 0 filas pese a columnas completas (exitoso, "
     "motivo_fallo, ip_origen, email_intentado).",
     "Ninguno — la tabla existe.",
     "Flujo de inicio de sesión: insertar cada intento, exitoso o fallido, con correo "
     "intentado, IP y motivo.",
     "Ninguno — el formulario de inicio de sesión ya existe.",
     "BAJO"),
]
table(
    ["ID", "QUÉ FALTA", "CAMBIO EN BASE DE DATOS", "CAMBIO EN BACKEND",
     "CAMBIO EN FORMULARIO / UI", "ESFUERZO"],
    brechas,
    widths=[0.7, 1.3, 1.3, 1.5, 1.3, 0.6],
    font_size=8,
)

p()
h(2, "5.1. Objetivos que solo esperan volumen de datos")
p("En estos 14 casos el esquema está completo y el flujo ya escribe el dato: no hay cambio de "
  "base de datos, backend ni formulario. Lo que falta es operación real — el entorno actual es "
  "de desarrollo/demostración (2 clientes, 2 proveedores, 2 bodegas, unas 2 semanas de "
  "operación) — antes de que el informe agregado sea representativo.")
volumen = [
    ("OTD-VEN-05", "Solo 2 clientes (cuentas de demostración)."),
    ("OTD-VEN-08", "19 carritos, todos convertidos en pedido; ni un abandono registrado aún."),
    ("OTD-VEN-11", "4 reseñas sobre 1214 productos."),
    ("OTD-COM-07", "1 sola línea de recepción con rechazo (de 22)."),
    ("OTD-COM-09", "2 devoluciones a proveedor (módulo estrenado el 2026-07-18)."),
    ("OTD-INV-10", "Solo 3 ajustes de inventario y 3 movimientos de ajuste en el kardex."),
    ("OTD-LOG-05", "6 novedades de envío (módulo estrenado el 2026-07-18)."),
    ("OTD-LOG-07", "La mayoría de devoluciones son casos antiguos migrados directo a 'cerrada', "
                   "sin tiempos intermedios."),
    ("OTD-LOG-08", "9 líneas de devolución con inspección."),
    ("OTD-SOP-03", "Fecha de cierre poblada en solo 2 de 12 tickets."),
    ("OTD-SOP-07", "La misma limitante anterior, ahora abierta por agente."),
    ("OTD-GER-05", "3 usos de cupón."),
    ("OTD-GER-07", "1 promoción con 2 productos asociados."),
    ("OTD-GER-11", "Solo 2 líneas con descuento de promoción y 3 usos de cupón ($52,91)."),
]
table(["ID", "CONTEO REAL QUE LO LIMITA HOY"], volumen, widths=[1.1, 5.4], font_size=9)

# ═══════════ 6. DASHBOARDS ════════════════════════════════════════════════
doc.add_page_break()
h(1, "6. Dashboards: visualización y destinatarios")
p("Los informes tácticos se consultan POR PANTALLA, con filtros y registros visibles — no se "
  "entregan como PDF descargable; los PDF quedan reservados para los documentos operativos "
  "(facturas, guías, comprobantes). Cada objetivo tiene definida su visualización y los roles "
  "que la ven. La segregación financiera del sistema se respeta en los destinatarios: Bodega y "
  "Despacho nunca acceden a montos de dinero — sus tableros trabajan con cantidades, estados y "
  "fechas, y las vistas valorizadas de esos mismos fenómenos se muestran a Gerencia, "
  "Administración y Analista.")

filas_dash = [(o[0], o[1], o[6], o[7]) for o in OBJ]
t = table(
    ["DEPTO.", "ID", "VISUALIZACIÓN (pantalla con filtros)", "ROLES QUE LO VEN"],
    filas_dash,
    widths=[0.8, 1.0, 2.6, 2.1],
    font_size=8,
)
merge_first_col(t, filas_dash, font_size=8)

# ═══════════ 7. ANÁLISIS DE RESULTADOS ════════════════════════════════════
doc.add_page_break()
h(1, "7. Análisis de resultados")
table(
    ["CLASIFICACIÓN", "FUENTE", "CANTIDAD", "PORCENTAJE"],
    [
        ("Informes simples", BDR, "28", "41 %"),
        ("Informes compuestos", BDC, "40", "59 %"),
        ("Total", "", "68", "100 %"),
    ],
    widths=[1.9, 2.3, 1.2, 1.4],
    font_size=10,
)
p()
table(
    ["DEPARTAMENTO", "OBJETIVOS", "SIMPLES", "COMPUESTOS"],
    [
        ("Ventas", "15", "5", "10"),
        ("Compras", "12", "5", "7"),
        ("Inventario / Bodega", "10", "7", "3"),
        ("Logística / Despacho", "12", "3", "9"),
        ("Soporte", "8", "3", "5"),
        ("Gerencia / Dirección (incl. Marketing)", "11", "5", "6"),
        ("Total", "68", "28", "40"),
    ],
    widths=[2.6, 1.2, 1.2, 1.4],
    font_size=10,
)
p()
p("Verificación aritmética: 15+12+10+12+8+11 = 68 objetivos; 5+5+7+3+3+5 = 28 simples; "
  "10+7+3+9+5+6 = 40 compuestos; 28+40 = 68. Por estado de factibilidad: 44 factibles hoy + 10 "
  "que requieren cambio en el sistema + 14 que requieren volumen de datos = 68.")
p("De los 68 informes tácticos, 28 son simples y se resuelven directamente sobre la BDR "
  "PostgreSQL, mientras que 40 son compuestos y se procesan en la base columnar ClickHouse. Los "
  "compuestos comparten tres rasgos que desaconsejan ejecutarlos sobre la base transaccional: "
  "(a) barren volúmenes históricos completos en lugar de filas puntuales; (b) exigen "
  "agregaciones (sumas, promedios, tasas) y agrupaciones por dimensiones de tiempo, categoría, "
  "proveedor o zona; y (c) su patrón de lectura masiva por columnas es exactamente el que un "
  "motor columnar optimiza. Ejecutarlos sobre PostgreSQL competiría por recursos con las "
  "transacciones operativas (ventas, checkout, kardex) y degradaría el sistema en producción; "
  "por eso los 40 compuestos justifican el pipeline ETL hacia ClickHouse y su orquestación con "
  "Apache Airflow.")
p("La asimetría entre departamentos es deliberada y responde a la necesidad real de cada área, "
  "no a una cuota. Ventas concentra el mayor número de objetivos porque concentra el ingreso "
  "del negocio; Logística carga además todo el ciclo de devoluciones de clientes; Inventario "
  "combina el control del presente (existencias, kardex, ajustes — de ahí su mayoría de "
  "simples) con la evolución del capital almacenado; y Soporte, un área más pequeña, tiene un "
  "catálogo proporcional a lo que dirige. También es revelador que 9 de los 11 objetivos "
  "incorporados en la ampliación del catálogo resultaran compuestos: lo que faltaba no era "
  "tanto ver el presente — eso el catálogo ya lo cubría casi por completo — sino medir tiempos "
  "de ciclo, comparar períodos y consolidar dinero, exactamente el tipo de pregunta que "
  "justifica la base columnar. Los otros dos (el avance de venta contra la meta del período en "
  "curso y las entregas incompletas por proveedor) son simples porque agregan sobre la foto "
  "presente sin recorrer histórico ni comparar períodos.")

# ═══════════ 8. CONCLUSIÓN ════════════════════════════════════════════════
h(1, "8. Conclusión")
p("El análisis del nivel táctico de RetailMind demuestra que las necesidades de dirección y "
  "control de los seis departamentos se cubren con 68 objetivos tácticos verificados uno a uno "
  "contra la base de datos real del sistema (109 tablas): 28 informes simples, atendidos por la "
  "base relacional PostgreSQL que ya soporta la operación, y 40 compuestos, que por su "
  "naturaleza histórica y agregada se destinan a la base columnar ClickHouse. La verificación "
  "honesta contra los datos reales — no contra el esquema en papel — permitió además "
  "clasificar cada objetivo por factibilidad: 44 son respondibles hoy, 10 exigen un cambio "
  "puntual identificado en sus tres capas (base de datos, backend y formulario) y 14 solo "
  "esperan volumen de operación real. La arquitectura híbrida existente resulta, por tanto, "
  "adecuada para el nivel táctico: la BDR responde el «ahora» de cada área y la BD columnar el "
  "«cómo evoluciona», quedando como siguiente paso natural la construcción del pipeline ETL "
  "hacia ClickHouse orquestado con Apache Airflow — programación, dependencias entre tareas, "
  "reintentos y monitoreo — para automatizar la alimentación de los 40 informes compuestos.")

import os
out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "RetailMind_T11_Analisis_Tactico.docx")
doc.save(out)
print("Guardado:", out)
print("Objetivos:", len(OBJ), "| Simples:", N_SIMPLES, "| Compuestos:", N_COMPUESTOS)
