"""
etl/dwh/registro.py
Catálogo de las tareas implementadas y su orden de dependencia.

Es la LISTA BLANCA del pipeline: `cargar.py` solo ejecuta lo que aparece aquí,
y `--tabla` con un nombre desconocido falla con la lista de opciones válidas en
vez de intentar construir un nombre de tabla a partir de texto libre. Es el
mismo criterio de lista blanca que rige las consultas del proyecto.

ESTADO: **MODELO COMPLETO — las 19 tablas implementadas.**

    Fase 1 (piloto)  dim_fecha · dim_producto · fact_venta_linea   ← HECHA
    Fase 2           dim_cliente · fact_pedido · fact_flujo_caja   ← HECHA
    Fase 3A          dim_proveedor · fact_orden_compra ·
                     fact_compra_linea                             ← HECHA
    Fase 3B          fact_movimiento_inventario ·
                     fact_stock_mensual                            ← HECHA
    Fase 3C          fact_envio · fact_novedad_envio               ← HECHA
    Fase 4           dim_promocion_producto · fact_devolucion ·
                     fact_devolucion_linea · fact_ticket ·
                     fact_resena · fact_devolucion_proveedor       ← HECHA

OJO con el ORDEN: `fact_stock_mensual` es la ÚNICA `TareaDerivada` del modelo y
la única con dependencias de datos reales (`fact_movimiento_inventario` +
`dim_producto`). Lanzarla antes que ellas no corrompe nada —su validación
aborta— pero no publica. `fact_novedad_envio` declara `depende_de = fact_envio`
por otra razón: comparte con él el CTE de resolución de zona, de modo que las
dos tablas no puedan discrepar sobre la zona del mismo envío.
`fact_devolucion_linea` declara `depende_de = fact_devolucion` por una tercera:
no comparte datos ni CTE, pero su Σ de líneas tiene que cuadrar con la Σ de
cabeceras, y publicar el detalle cuando el total abortó dejaría a OTD-LOG-08 y
OTD-VEN-14 contando dinero distinto del mismo hecho.

Las correcciones al diseño que cada fase ha ido encontrando —30 supuestos que no
se sostuvieron contra la base— viven en
`docs/estrategico/CORRECCIONES_DISENO_ETL.md`. Léelo ANTES de implementar una
tabla nueva: el diseño en papel se escribió sin ejecutar nada y varias de sus
afirmaciones sobre cardinalidad y cobertura son hipótesis, no hechos.

CÓMO SE AÑADE UNA TABLA (el molde completo, en tres pasos):

    1. `etl/dwh/tablas/fact_venta_linea.py` con una subclase de `TareaCarga`
       que defina: nombre, ddl(), columnas(), sql_extraccion() y sql_controles().
    2. Importarla aquí y añadirla a TAREAS.
    3. Ejecutar `python -m etl.dwh.cargar --tabla fact_venta_linea`.

No hace falta tocar `carga_atomica.py`, `bitacora.py` ni `cargar.py`: la
mecánica de lotes, validación, publicación atómica y bitácora ya está escrita
una sola vez y sirve a las 19.
"""

from etl.dwh.tablas.dim_cliente import DimCliente
from etl.dwh.tablas.dim_fecha import DimFecha
from etl.dwh.tablas.dim_producto import DimProducto
from etl.dwh.tablas.dim_promocion_producto import DimPromocionProducto
from etl.dwh.tablas.dim_proveedor import DimProveedor
from etl.dwh.tablas.fact_compra_linea import FactCompraLinea
from etl.dwh.tablas.fact_devolucion import FactDevolucion
from etl.dwh.tablas.fact_devolucion_linea import FactDevolucionLinea
from etl.dwh.tablas.fact_devolucion_proveedor import FactDevolucionProveedor
from etl.dwh.tablas.fact_envio import FactEnvio
from etl.dwh.tablas.fact_flujo_caja import FactFlujoCaja
from etl.dwh.tablas.fact_movimiento_inventario import FactMovimientoInventario
from etl.dwh.tablas.fact_novedad_envio import FactNovedadEnvio
from etl.dwh.tablas.fact_orden_compra import FactOrdenCompra
from etl.dwh.tablas.fact_pedido import FactPedido
from etl.dwh.tablas.fact_resena import FactResena
from etl.dwh.tablas.fact_stock_mensual import FactStockMensual
from etl.dwh.tablas.fact_ticket import FactTicket
from etl.dwh.tablas.fact_venta_linea import FactVentaLinea
from etl.dwh.tarea import TareaCarga

#: {nombre de la tabla → clase de la tarea}.
TAREAS: dict[str, type[TareaCarga]] = {
    DimFecha.nombre: DimFecha,
    DimProducto.nombre: DimProducto,
    DimCliente.nombre: DimCliente,
    DimProveedor.nombre: DimProveedor,
    DimPromocionProducto.nombre: DimPromocionProducto,
    FactPedido.nombre: FactPedido,
    FactVentaLinea.nombre: FactVentaLinea,
    FactFlujoCaja.nombre: FactFlujoCaja,
    FactOrdenCompra.nombre: FactOrdenCompra,
    FactCompraLinea.nombre: FactCompraLinea,
    FactMovimientoInventario.nombre: FactMovimientoInventario,
    FactStockMensual.nombre: FactStockMensual,
    FactEnvio.nombre: FactEnvio,
    FactNovedadEnvio.nombre: FactNovedadEnvio,
    FactDevolucion.nombre: FactDevolucion,
    FactDevolucionLinea.nombre: FactDevolucionLinea,
    FactTicket.nombre: FactTicket,
    FactResena.nombre: FactResena,
    FactDevolucionProveedor.nombre: FactDevolucionProveedor,
}

#: Orden en que `run_etl.py` recorrería el grafo. La ÚNICA dependencia real del
#: modelo es fact_movimiento_inventario → fact_stock_mensual (§7.1); las
#: dimensiones van antes que los hechos por claridad, no por obligación técnica.
ORDEN_SUGERIDO: tuple[str, ...] = (
    "dim_fecha",
    "dim_producto",
    "dim_cliente",
    "dim_proveedor",
    "dim_promocion_producto",
    "fact_pedido",
    "fact_venta_linea",
    "fact_flujo_caja",
    "fact_orden_compra",
    "fact_compra_linea",
    "fact_movimiento_inventario",
    "fact_envio",
    "fact_novedad_envio",
    "fact_devolucion",
    "fact_devolucion_linea",
    "fact_ticket",
    "fact_resena",
    "fact_devolucion_proveedor",
    "fact_stock_mensual",          # derivada: DESPUÉS de fact_movimiento_inventario
)


def obtener(nombre: str) -> TareaCarga:
    """Instancia la tarea registrada bajo `nombre`, o falla explicando qué hay."""
    if nombre not in TAREAS:
        disponibles = ", ".join(sorted(TAREAS)) or "(ninguna: Fase 0)"
        pendiente = " Está prevista en el diseño pero aún no implementada." \
            if nombre in ORDEN_SUGERIDO else ""
        raise KeyError(
            f"No existe la tarea '{nombre}'.{pendiente} "
            f"Tareas implementadas: {disponibles}"
        )
    return TAREAS[nombre]()


def listar() -> None:
    """Imprime el estado de las 19 tablas del modelo."""
    print(f"\nTareas del DWH ({len(TAREAS)} de {len(ORDEN_SUGERIDO)} implementadas)\n")
    for nombre in ORDEN_SUGERIDO:
        estado = "implementada" if nombre in TAREAS else "pendiente"
        marca = "*" if nombre in TAREAS else " "
        print(f"  {marca} {nombre:<28} {estado}")
    print()
