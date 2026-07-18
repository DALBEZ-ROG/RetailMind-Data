# -*- coding: utf-8 -*-
"""
Generador del documento RetailMind_T11_Analisis_Tactico.docx (version Word EDITABLE).
Contenido tomado del PDF original de la Tarea 11, con la correccion de trazabilidad
aplicada (2026-07-17, script 42): pedido.vendedor_id es ahora columna directa (FK a
usuario, poblada desde el JWT en pedidos internos; NULL en pedidos online canal 'web'),
por lo que "Controlar ventas por vendedor" es un informe SIMPLE limpio sin deduccion
indirecta via historial_estado_pedido.
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

CENTER = WD_ALIGN_PARAGRAPH.CENTER

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

h(2, "1.1. Informes simples y compuestos")
p("Cada informe táctico se clasifica según su costo de obtención sobre los datos:")
par = doc.add_paragraph(style="List Bullet")
r = par.add_run("Informe SIMPLE: ")
r.bold = True
par.add_run("responde «¿cómo está esto ahora?». Se obtiene directamente de la base de datos "
            "relacional (BDR) con una consulta SELECT sencilla, sin agregaciones pesadas ni "
            "transformación temporal. Ejemplo: pedidos pendientes de despacho.")
par = doc.add_paragraph(style="List Bullet")
r = par.add_run("Informe COMPUESTO: ")
r.bold = True
par.add_run("responde «¿cómo evoluciona o se agrega esto en el tiempo?». Requiere agregaciones "
            "sobre volúmenes históricos, comparaciones entre períodos o cruces de varias fuentes; "
            "ejecutarlo sobre la BDR transaccional la penalizaría, por lo que se procesa en la "
            "base de datos columnar, alimentada mediante un proceso ETL orquestado con Apache "
            "Airflow. Ejemplo: tendencia de ventas por mes y categoría.")

h(2, "1.2. Arquitectura híbrida del sistema real")
p("RetailMind ya materializa esta separación con una arquitectura híbrida de dos motores: "
  "PostgreSQL como única base transaccional (BDR, base retailmind con aproximadamente 103 tablas "
  "operativas, seguridad por roles, RLS y triggers de negocio) y ClickHouse como base columnar "
  "dedicada exclusivamente a analítica. Los informes simples de este análisis se resuelven con "
  "consultas directas sobre PostgreSQL; los compuestos justifican el pipeline ETL (extracción "
  "desde la BDR, transformación y carga en ClickHouse) cuya orquestación con Airflow constituye "
  "la fase pendiente del proyecto.")
p("Los objetivos tácticos se levantaron para los siete departamentos del negocio — Ventas, "
  "Compras, Inventario/Bodega, Logística/Despacho, Soporte y Gerencia/Dirección (que gestiona "
  "también Marketing) — y cada uno fue verificado contra el esquema real de la base de datos "
  "para garantizar que es alcanzable con los datos existentes.")

# ═══════════ 2. TABLA PRINCIPAL DE OBJETIVOS TÁCTICOS ═════════════════════
h(1, "2. Objetivos tácticos por departamento y clasificación de informes")
p("La siguiente tabla consolida los objetivos tácticos provistos por cada jefatura "
  "departamental, la clasificación de su informe y la base de datos de la que se obtiene.")

BDR = "BDR (PostgreSQL)"
BDC = "BDColumnar (ClickHouse)"
objetivos = [
    ("VENTAS", "Controlar ventas por vendedor (cumplimiento individual)", "X", "—", BDR),
    ("VENTAS", "Monitorear pedidos por estado (pendientes, despachados, entregados)", "X", "—", BDR),
    ("VENTAS", "Analizar tendencia de ventas por mes y categoría", "—", "X", BDC),
    ("VENTAS", "Medir ticket promedio de compra por período", "—", "X", BDC),
    ("COMPRAS", "Ver órdenes de compra pendientes de aprobación", "X", "—", BDR),
    ("COMPRAS", "Controlar cuentas por pagar vigentes y vencidas", "X", "—", BDR),
    ("COMPRAS", "Analizar gasto de compra por proveedor por trimestre", "—", "X", BDC),
    ("COMPRAS", "Evaluar cumplimiento de tiempos de entrega por proveedor", "—", "X", BDC),
    ("INVENTARIO / BODEGA", "Identificar productos bajo stock mínimo", "X", "—", BDR),
    ("INVENTARIO / BODEGA", "Consultar stock actual por bodega", "X", "—", BDR),
    ("INVENTARIO / BODEGA", "Analizar rotación de inventario por categoría", "—", "X", BDC),
    ("INVENTARIO / BODEGA", "Medir productos de baja/nula rotación por período", "—", "X", BDC),
    ("LOGÍSTICA / DESPACHO", "Ver pedidos preparados pendientes de despacho", "X", "—", BDR),
    ("LOGÍSTICA / DESPACHO", "Consultar envíos por transportista", "X", "—", BDR),
    ("LOGÍSTICA / DESPACHO", "Analizar tiempo promedio de entrega por transportista/zona", "—", "X", BDC),
    ("LOGÍSTICA / DESPACHO", "Medir tasa de devoluciones sobre despachos por período", "—", "X", BDC),
    ("SOPORTE", "Ver tickets abiertos por prioridad y estado", "X", "—", BDR),
    ("SOPORTE", "Consultar tickets asignados por agente", "X", "—", BDR),
    ("SOPORTE", "Analizar tiempo promedio de resolución por categoría por mes", "—", "X", BDC),
    ("SOPORTE", "Medir cumplimiento de SLA por período", "—", "X", BDC),
    ("GERENCIA / DIRECCIÓN (incluye Marketing)", "Consultar estado consolidado de pedidos del día", "X", "—", BDR),
    ("GERENCIA / DIRECCIÓN (incluye Marketing)", "Listar cupones activos y promociones vigentes (marketing)", "X", "—", BDR),
    ("GERENCIA / DIRECCIÓN (incluye Marketing)", "Analizar tasa de redención de cupones e impacto de promociones por período (marketing)", "—", "X", BDC),
    ("GERENCIA / DIRECCIÓN (incluye Marketing)", "Analizar rentabilidad por categoría por período", "—", "X", BDC),
    ("GERENCIA / DIRECCIÓN (incluye Marketing)", "Medir evolución de ingresos vs. costos mensual", "—", "X", BDC),
]
t = table(
    ["DEPARTAMENTO", "OBJETIVO TÁCTICO", "¿SIMPLE?", "¿COMPUESTO?", "FUENTE"],
    objetivos,
    widths=[1.3, 2.9, 0.8, 1.0, 1.3],
)
# Fusionar verticalmente las celdas de departamento (mismo texto consecutivo)
data_rows = t.rows[1:]
start = 0
for i in range(1, len(objetivos) + 1):
    if i == len(objetivos) or objetivos[i][0] != objetivos[start][0]:
        if i - start > 1:
            merged = data_rows[start].cells[0].merge(data_rows[i - 1].cells[0])
            # dejar un solo texto en la celda fusionada
            merged.text = ""
            r = merged.paragraphs[0].add_run(objetivos[start][0])
            r.bold = True
            r.font.size = Pt(9)
        start = i

# ═══════════ 3. VERIFICACIÓN DE FACTIBILIDAD ══════════════════════════════
doc.add_page_break()
h(1, "3. Verificación de factibilidad sobre el esquema real")
p("Cada objetivo fue contrastado contra el esquema vigente de PostgreSQL para confirmar que los "
  "datos que lo sustentan existen. La siguiente tabla resume el soporte de datos identificado "
  "para cada informe:")

sustento = [
    ("Controlar ventas por vendedor (cumplimiento individual)",
     "pedido.vendedor_id (FK directa a usuario, poblada desde el JWT en pedidos internos) + usuario"),
    ("Monitorear pedidos por estado (pendientes, despachados, entregados)", "pedido + estado_pedido"),
    ("Analizar tendencia de ventas por mes y categoría", "hechos de venta agregados por mes/categoría"),
    ("Medir ticket promedio de compra por período", "agregación temporal de pedidos pagados"),
    ("Ver órdenes de compra pendientes de aprobación", "orden_compra (estado)"),
    ("Controlar cuentas por pagar vigentes y vencidas",
     "cuenta_por_pagar (estado, fecha_vencimiento, saldo_pendiente)"),
    ("Analizar gasto de compra por proveedor por trimestre",
     "facturas de compra agregadas por proveedor/trimestre"),
    ("Evaluar cumplimiento de tiempos de entrega por proveedor",
     "orden_compra vs. recepcion_mercancia (fechas)"),
    ("Identificar productos bajo stock mínimo", "inventario (stock_actual vs. stock_minimo)"),
    ("Consultar stock actual por bodega", "inventario + bodega"),
    ("Analizar rotación de inventario por categoría",
     "kardex (movimiento_inventario) agregado por período"),
    ("Medir productos de baja/nula rotación por período", "kardex vs. catálogo, ventanas temporales"),
    ("Ver pedidos preparados pendientes de despacho", "pedido (estado 'preparado')"),
    ("Consultar envíos por transportista", "envio + transportista"),
    ("Analizar tiempo promedio de entrega por transportista/zona",
     "envio (fecha_despacho vs. fecha_entrega_real) por zona"),
    ("Medir tasa de devoluciones sobre despachos por período",
     "devolucion vs. envio, series por período"),
    ("Ver tickets abiertos por prioridad y estado", "ticket_soporte (prioridad, estado)"),
    ("Consultar tickets asignados por agente", "ticket_soporte (asignado_usuario_id) + usuario"),
    ("Analizar tiempo promedio de resolución por categoría por mes",
     "tickets cerrados agregados por categoría/mes"),
    ("Medir cumplimiento de SLA por período", "SLA derivado de prioridad vs. fecha de cierre"),
    ("Consultar estado consolidado de pedidos del día", "pedido + estado_pedido (fecha actual)"),
    ("Listar cupones activos y promociones vigentes (marketing)", "cupon, promocion (activo, vigencia)"),
    ("Analizar tasa de redención de cupones e impacto de promociones por período (marketing)",
     "uso_cupon y descuentos agregados por período"),
    ("Analizar rentabilidad por categoría por período",
     "ventas vs. costo (producto_variante.costo) por categoría"),
    ("Medir evolución de ingresos vs. costos mensual",
     "factura_venta vs. factura_compra, serie mensual"),
]
table(["INFORME", "SOPORTE DE DATOS VERIFICADO"], sustento, widths=[3.5, 3.8])

p()
par = doc.add_paragraph()
r = par.add_run("Nota de verificación: ")
r.bold = True
r.italic = True
r2 = par.add_run(
    "la atribución de ventas por vendedor es directa: la tabla pedido incluye la columna "
    "vendedor_id (FK a usuario), poblada automáticamente con el usuario autenticado (JWT) al "
    "registrar un pedido interno; en los pedidos online (canal 'web') la columna queda en NULL "
    "porque son autogestión del cliente, trazada por cliente_id y el historial de estados. Esta "
    "columna forma parte de la trazabilidad de autor implementada en el sistema, que centraliza "
    "en un servicio de auditoría (AuditoriaService, tabla log_auditoria) el registro del autor "
    "de las acciones críticas. Por su parte, el SLA de soporte no se guarda como columna: se "
    "deriva de la prioridad del ticket y su fecha de creación, lo que refuerza su clasificación "
    "como informe compuesto.")
r2.italic = True

# ═══════════ 4. ANÁLISIS DE RESULTADOS ════════════════════════════════════
h(1, "4. Análisis de resultados")
table(
    ["CLASIFICACIÓN", "FUENTE", "CANTIDAD", "PORCENTAJE"],
    [
        ("Informes simples", "BDR (PostgreSQL)", "12", "48%"),
        ("Informes compuestos", "BDColumnar (ClickHouse)", "13", "52%"),
        ("Total", "", "25", "100%"),
    ],
    widths=[1.9, 2.3, 1.2, 1.4],
    font_size=10,
)
p()
p("De los 25 informes tácticos identificados, 12 son simples y se resuelven directamente sobre "
  "la BDR PostgreSQL, mientras que 13 son compuestos y se procesan en la base columnar "
  "ClickHouse. La proporción es casi paritaria y no es casual: cada departamento necesita, por "
  "un lado, controlar el presente (colas de trabajo, pendientes, estados actuales — consultas "
  "puntuales que la BDR responde en milisegundos gracias a sus índices y a su modelo "
  "normalizado) y, por otro, evaluar la evolución (tendencias, promedios por período, tasas y "
  "comparaciones entre meses o trimestres).")
p("Los informes compuestos comparten tres rasgos que desaconsejan ejecutarlos sobre la base "
  "transaccional: (a) barren volúmenes históricos completos en lugar de filas puntuales; "
  "(b) exigen agregaciones (SUM, AVG, tasas) y agrupaciones por dimensiones de tiempo, "
  "categoría, proveedor o zona; y (c) su patrón de lectura masiva por columnas es exactamente "
  "el que un motor columnar optimiza. Ejecutarlos sobre PostgreSQL competiría por recursos con "
  "las transacciones operativas (ventas, checkout, kardex) y degradaría el sistema en "
  "producción.")
p("Por ello, los 13 informes compuestos justifican el pipeline ETL hacia ClickHouse: un proceso "
  "periódico extrae los datos de PostgreSQL, los transforma (desnormalización, hechos y "
  "dimensiones, precálculo de métricas) y los carga en la base columnar, donde los análisis se "
  "ejecutan sin afectar la operación. La orquestación con Apache Airflow — programación, "
  "dependencias entre tareas, reintentos y monitoreo del pipeline — es precisamente la fase "
  "pendiente identificada en el proyecto.")

# ═══════════ 5. CONCLUSIÓN ════════════════════════════════════════════════
h(1, "5. Conclusión")
p("El análisis del nivel táctico de RetailMind demuestra que las necesidades de dirección y "
  "control de los siete departamentos se cubren con 25 informes verificados contra el esquema "
  "real del sistema: 12 simples, atendidos por la base relacional PostgreSQL que ya soporta la "
  "operación, y 13 compuestos, que por su naturaleza agregada y temporal se destinan a la base "
  "columnar ClickHouse. La arquitectura híbrida existente resulta, por tanto, adecuada para el "
  "nivel táctico: la BDR responde el «ahora» de cada área y la BD columnar el «cómo evoluciona», "
  "quedando como siguiente paso natural la orquestación del pipeline ETL con Apache Airflow "
  "para automatizar la alimentación de los informes compuestos.")

import os
out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "RetailMind_T11_Analisis_Tactico.docx")
doc.save(out)
print("Guardado:", out)
