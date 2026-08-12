"""
etl/dwh/validar_dwh.py — validación cruzada PostgreSQL ↔ ClickHouse.

    python -m etl.dwh.validar_dwh                     # todos los controles
    python -m etl.dwh.validar_dwh --fase 2            # solo los de una fase
    python -m etl.dwh.validar_dwh --control fact_pedido
    python -m etl.dwh.validar_dwh --control caja_mes --detalle
    python -m etl.dwh.validar_dwh --listar

Se ejecuta desde `retailmind/`. Devuelve 0 si TODO cuadra y 1 si algo difiere,
de modo que sirve igual a una persona, a `run_etl.py` o a la última tarea del
DAG de §7.1 («validar_cifras_control — falla ⇒ el DAG falla»).

═══════════════════════════════════════════════════════════════════════════════
POR QUÉ ESTO EXISTE APARTE DE LA VALIDACIÓN DE CADA TAREA
═══════════════════════════════════════════════════════════════════════════════

`carga_atomica` ya valida cada tabla ANTES de publicarla, y esa es la red que
impide publicar basura. Este script valida DESPUÉS y sobre lo PUBLICADO, que es
una pregunta distinta: «¿lo que los informes están leyendo ahora mismo coincide
con PostgreSQL?». Detecta lo que la otra no puede — una tabla que quedó de una
corrida vieja porque su tarea abortó, o una carga parcial de una fase anterior.

CRITERIO DE ACEPTACIÓN: igualdad EXACTA. No hay tolerancia de centavos. Una
diferencia de $0,01 es un bug de tipo (Float64 donde debía ir Decimal), una
fila de más o de menos es un JOIN mal planteado, y un mes desplazado es una
zona horaria olvidada. Las tres se detectan aquí o no se detectan nunca.

CÓMO AÑADE SUS CONTROLES CADA FASE SIGUIENTE: una entrada más en `CONTROLES`.
Un control es un par de consultas —una por motor— que devuelven la MISMA forma
tabular; el comparador es genérico y no sabe de qué tabla se trata. Los
controles escalares y los de serie (mes a mes) usan el mismo mecanismo: un
escalar es una serie de una sola fila.
"""

import argparse
import sys
from dataclasses import dataclass, field
from decimal import Decimal

from etl.dwh.conexiones import (
    CH_DATABASE,
    ZONA_HORARIA,
    get_ch_client,
    get_pg_connection,
)


@dataclass
class Control:
    """
    Un par de consultas equivalentes. `clave` nombra las columnas que
    identifican la fila (vacío = control escalar de una sola fila); el resto de
    columnas son las medidas que deben coincidir.
    """
    nombre: str
    fase: int
    tabla: str
    descripcion: str
    sql_pg: str
    sql_ch: str
    clave: tuple[str, ...] = ()
    columnas: tuple[str, ...] = field(default_factory=tuple)


# ═══════════════════════════════════════════════════════════════════════════
# FASE 1 — piloto: dim_fecha, dim_producto, fact_venta_linea (§9.2)
# ═══════════════════════════════════════════════════════════════════════════

#: La factura CANÓNICA del pedido. Se repite aquí, y a propósito: este script
#: debe poder contradecir al ETL. Si importara la consulta de la tarea, ambos
#: compartirían el mismo error y la validación sería una tautología.
#: (Ver el hallazgo (1) de `tablas/fact_venta_linea.py`: `factura_venta` NO es
#: 1:1 con el pedido — el pedido 2 tiene dos facturas 'emitida'.)
_CTE_CUPON = f"""
    factura_canonica AS (
        SELECT DISTINCT ON (fv.pedido_id) fv.id, fv.pedido_id
        FROM factura_venta fv
        WHERE fv.estado <> 'anulada'
        ORDER BY fv.pedido_id, fv.fecha_emision DESC, fv.id DESC
    ),
    linea AS (
        SELECT
            pd.id, pd.pedido_id, pd.cantidad,
            pd.cantidad * pd.precio_unitario AS bruto,
            pd.monto_descuento               AS promocion,
            CASE WHEN fvd.monto_descuento IS NULL THEN 0::numeric
                 ELSE GREATEST(fvd.monto_descuento - pd.monto_descuento, 0::numeric)
            END                              AS cupon,
            pd.cantidad * pv.costo           AS costo,
            (date_trunc('month',
                p.fecha_pedido AT TIME ZONE '{ZONA_HORARIA}'))::date AS mes
        FROM pedido_detalle pd
        JOIN pedido p             ON p.id  = pd.pedido_id
        JOIN producto_variante pv ON pv.id = pd.producto_variante_id
        LEFT JOIN factura_canonica fc ON fc.pedido_id = p.id
        LEFT JOIN factura_venta_detalle fvd
               ON fvd.factura_venta_id = fc.id AND fvd.pedido_detalle_id = pd.id
    )
"""

CONTROLES: list[Control] = [

    Control(
        nombre="dim_producto",
        fase=1,
        tabla="dim_producto",
        descripcion="Universo de variantes: conteo, SKU únicos y sumas de catálogo",
        columnas=("filas", "skus", "productos", "categorias", "marcas",
                  "suma_precio", "suma_costo", "sin_costo"),
        sql_pg="""
            SELECT count(*), count(DISTINCT pv.sku), count(DISTINCT p.id),
                   count(DISTINCT c.id), count(DISTINCT m.id),
                   SUM(pv.precio), SUM(pv.costo),
                   COUNT(*) FILTER (WHERE pv.costo IS NULL)
            FROM producto_variante pv
            JOIN producto p       ON p.id = pv.producto_id
            LEFT JOIN marca m     ON m.id = p.marca_id
            LEFT JOIN producto_categoria pc
                                  ON pc.producto_id = p.id AND pc.es_principal
            LEFT JOIN categoria c ON c.id = pc.categoria_id
        """,
        sql_ch=f"""
            SELECT count(), countDistinct(sku), countDistinct(producto_id),
                   countDistinct(categoria), countDistinct(marca),
                   sum(precio), sum(costo), countIf(costo = 0)
            FROM {CH_DATABASE}.dim_producto
        """,
    ),

    Control(
        nombre="fact_venta_linea",
        fase=1,
        tabla="fact_venta_linea",
        descripcion="Las 7 cifras de control del piloto (§9.2), al centavo",
        columnas=("filas", "unidades", "venta_neta_linea", "costo_total",
                  "lineas_sin_costo", "meses_con_venta",
                  "descuento_promocion", "descuento_cupon", "venta_neta_final"),
        sql_pg=f"""
            WITH {_CTE_CUPON}
            SELECT count(*), SUM(cantidad), SUM(bruto - promocion), SUM(costo),
                   COUNT(*) FILTER (WHERE costo IS NULL), COUNT(DISTINCT mes),
                   SUM(promocion), SUM(cupon), SUM(bruto - promocion - cupon)
            FROM linea
        """,
        sql_ch=f"""
            SELECT count(), sum(cantidad),
                   sum(subtotal_bruto - descuento_promocion), sum(costo_total),
                   countIf(costo_unitario = 0), countDistinct(mes),
                   sum(descuento_promocion), sum(descuento_cupon_prorrateado),
                   sum(venta_neta)
            FROM {CH_DATABASE}.fact_venta_linea
        """,
    ),

    Control(
        nombre="venta_linea_mes",
        fase=1,
        tabla="fact_venta_linea",
        descripcion="Serie mes a mes de la línea — el control que delata la zona horaria",
        clave=("mes",),
        columnas=("mes", "lineas", "unidades", "venta_neta_linea"),
        sql_pg=f"""
            WITH {_CTE_CUPON}
            SELECT to_char(mes, 'YYYY-MM'), count(*), SUM(cantidad),
                   SUM(bruto - promocion)
            FROM linea GROUP BY mes ORDER BY mes
        """,
        sql_ch=f"""
            SELECT formatDateTime(mes, '%Y-%m'), count(), sum(cantidad),
                   sum(subtotal_bruto - descuento_promocion)
            FROM {CH_DATABASE}.fact_venta_linea GROUP BY mes ORDER BY mes
        """,
    ),

]


# ═══════════════════════════════════════════════════════════════════════════
# FASE 2 — el núcleo de la venta y el dinero (§9.3)
#          dim_cliente · fact_pedido · fact_flujo_caja
# ═══════════════════════════════════════════════════════════════════════════

#: La factura canónica, otra vez escrita a mano. Mismo motivo que arriba: este
#: script debe poder CONTRADECIR al ETL, no compartir su código.
_CTE_FACTURA_CANONICA = """
    factura_canonica AS (
        SELECT DISTINCT ON (fv.pedido_id) fv.pedido_id, fv.total
        FROM factura_venta fv
        WHERE fv.estado <> 'anulada'
        ORDER BY fv.pedido_id, fv.fecha_emision DESC, fv.id DESC
    )
"""

#: El mapa de normalización de `motivo_fallo` (§5.3), escrito en SQL para poder
#: comparar contra lo que el ETL normalizó en Python. Si el mapa del ETL y este
#: divergieran, el control lo dice: son dos implementaciones de la misma regla.
_MOTIVO_NORMALIZADO = """
    CASE LOWER(TRIM(COALESCE(tp.respuesta_pasarela->>'motivo','')))
         WHEN 'tarjeta rechazada por el emisor' THEN 'tarjeta_rechazada'
         ELSE LOWER(TRIM(COALESCE(tp.respuesta_pasarela->>'motivo','')))
    END
"""

CONTROLES += [

    Control(
        nombre="dim_cliente",
        fase=2,
        tabla="dim_cliente",
        descripcion="72 clientes, 72 'sin_segmentar' — la ausencia de segmento, comprobable",
        columnas=("filas", "emails", "con_direccion", "activos",
                  "tipos_identificacion", "segmentos", "sin_segmentar",
                  "con_grupo_cliente"),
        sql_pg="""
            SELECT count(*), count(DISTINCT c.email),
                   count(*) FILTER (WHERE d.id IS NOT NULL),
                   count(*) FILTER (WHERE c.activo),
                   count(DISTINCT c.tipo_identificacion),
                   1,                                   -- 'segmento' es constante
                   count(*),                            -- … en las 72 filas
                   count(*) FILTER (WHERE c.grupo_cliente_id IS NOT NULL)
            FROM cliente c
            LEFT JOIN direccion d
                   ON d.usuario_id = c.usuario_id AND d.es_predeterminada AND d.activo
        """,
        sql_ch=f"""
            SELECT count(), countDistinct(email), countIf(ciudad != 'sin_direccion'),
                   countIf(activo = 1), countDistinct(tipo_identificacion),
                   countDistinct(segmento), countIf(segmento = 'sin_segmentar'),
                   0
            FROM {CH_DATABASE}.dim_cliente
        """,
    ),

    Control(
        nombre="fact_pedido",
        fase=2,
        tabla="fact_pedido",
        descripcion="Pedidos, vivos, importe, canal y cupón — contrastados contra PostgreSQL",
        columnas=("filas", "no_cancelados", "total_todos", "total_no_cancelados",
                  "web", "tienda", "telefono",
                  "web_vivos", "tienda_vivos", "telefono_vivos",
                  "lineas", "unidades", "con_cupon", "monto_cupon",
                  "con_factura", "factura_total", "meses"),
        sql_pg=f"""
            WITH {_CTE_FACTURA_CANONICA},
            base AS (
                SELECT p.id, p.canal, p.total,
                       (ep.codigo = 'cancelado')       AS cancelado,
                       (uc.pedido_id IS NOT NULL)      AS con_cupon,
                       COALESCE(uc.monto_descontado,0) AS monto_cupon,
                       (fc.pedido_id IS NOT NULL)      AS con_factura,
                       COALESCE(fc.total, 0)           AS factura_total,
                       (date_trunc('month',
                           p.fecha_pedido AT TIME ZONE '{ZONA_HORARIA}'))::date AS mes
                FROM pedido p
                JOIN estado_pedido ep ON ep.id = p.estado_pedido_id
                LEFT JOIN uso_cupon uc ON uc.pedido_id = p.id
                LEFT JOIN factura_canonica fc ON fc.pedido_id = p.id
            )
            SELECT count(*), count(*) FILTER (WHERE NOT cancelado),
                   SUM(total), SUM(total) FILTER (WHERE NOT cancelado),
                   count(*) FILTER (WHERE canal='web'),
                   count(*) FILTER (WHERE canal='tienda'),
                   count(*) FILTER (WHERE canal='telefono'),
                   count(*) FILTER (WHERE canal='web' AND NOT cancelado),
                   count(*) FILTER (WHERE canal='tienda' AND NOT cancelado),
                   count(*) FILTER (WHERE canal='telefono' AND NOT cancelado),
                   (SELECT count(*) FROM pedido_detalle),
                   (SELECT SUM(cantidad) FROM pedido_detalle),
                   count(*) FILTER (WHERE con_cupon), SUM(monto_cupon),
                   count(*) FILTER (WHERE con_factura), SUM(factura_total),
                   count(DISTINCT mes)
            FROM base
        """,
        sql_ch=f"""
            SELECT count(), countIf(es_cancelado = 0),
                   sum(total), sumIf(total, es_cancelado = 0),
                   countIf(canal='web'), countIf(canal='tienda'), countIf(canal='telefono'),
                   countIf(canal='web' AND es_cancelado=0),
                   countIf(canal='tienda' AND es_cancelado=0),
                   countIf(canal='telefono' AND es_cancelado=0),
                   sum(lineas), sum(unidades),
                   countIf(codigo_cupon != ''), sum(monto_cupon),
                   countIf(factura_numero != ''), sum(factura_total),
                   countDistinct(mes)
            FROM {CH_DATABASE}.fact_pedido
        """,
    ),

    Control(
        nombre="pedidos_mes",
        fase=2,
        tabla="fact_pedido",
        descripcion="Pedidos NO cancelados y total, mes a mes, en todo el período cargado",
        clave=("mes",),
        columnas=("mes", "pedidos", "total"),
        # Hasta la Fase 1 este control se resolvía colapsando el grano de
        # `fact_venta_linea` por `pedido_id` a través de una columna
        # `pedido_total` que existía solo para eso. Con la tabla de CABECERA
        # cargada, la medida se lee donde vive y la subconsulta desaparece —
        # que era la condición para retirar aquella columna.
        sql_pg=f"""
            SELECT to_char(date_trunc('month',
                       p.fecha_pedido AT TIME ZONE '{ZONA_HORARIA}'), 'YYYY-MM'),
                   count(*), SUM(p.total)
            FROM pedido p
            JOIN estado_pedido ep ON ep.id = p.estado_pedido_id
            WHERE ep.codigo <> 'cancelado'
            GROUP BY 1 ORDER BY 1
        """,
        sql_ch=f"""
            SELECT formatDateTime(mes, '%Y-%m'), count(), sum(total)
            FROM {CH_DATABASE}.fact_pedido
            WHERE es_cancelado = 0 GROUP BY mes ORDER BY mes
        """,
    ),

    Control(
        nombre="hitos_pedido",
        fase=2,
        tabla="fact_pedido",
        descripcion="Cobertura del pivote de hitos y de los cuatro tramos (LOG-12)",
        columnas=("pagado", "facturado", "en_preparacion", "preparado",
                  "despachado", "entregado",
                  "tramo_pago_prep", "tramo_prep_desp", "tramo_desp_entrega",
                  "ciclo_total"),
        # `min` y no `max` también aquí: hay pedidos con el mismo hito repetido
        # (19 'confirmado', 8 'pagado', 5 'despachado'). Con `max` los tramos
        # saldrían más largos y el control daría por bueno el error del ETL si
        # el ETL cometiera el mismo.
        sql_pg="""
            WITH h AS (
                SELECT hp.pedido_id,
                    min(hp.fecha_creacion) FILTER (WHERE ep.codigo='pagado')         AS f_pagado,
                    min(hp.fecha_creacion) FILTER (WHERE ep.codigo='facturado')      AS f_facturado,
                    min(hp.fecha_creacion) FILTER (WHERE ep.codigo='en_preparacion') AS f_prep_ini,
                    min(hp.fecha_creacion) FILTER (WHERE ep.codigo='preparado')      AS f_preparado,
                    min(hp.fecha_creacion) FILTER (WHERE ep.codigo='despachado')     AS f_despachado,
                    min(hp.fecha_creacion) FILTER (WHERE ep.codigo='entregado')      AS f_entregado
                FROM historial_estado_pedido hp
                JOIN estado_pedido ep ON ep.id = hp.estado_pedido_id
                GROUP BY 1
            )
            SELECT count(f_pagado), count(f_facturado), count(f_prep_ini),
                   count(f_preparado), count(f_despachado), count(f_entregado),
                   count(*) FILTER (WHERE f_pagado IS NOT NULL AND f_preparado IS NOT NULL),
                   count(*) FILTER (WHERE f_preparado IS NOT NULL AND f_despachado IS NOT NULL),
                   count(*) FILTER (WHERE f_despachado IS NOT NULL AND f_entregado IS NOT NULL),
                   count(*) FILTER (WHERE f_pagado IS NOT NULL AND f_entregado IS NOT NULL)
            FROM h
        """,
        sql_ch=f"""
            SELECT countIf(fecha_pagado IS NOT NULL),
                   countIf(fecha_facturado IS NOT NULL),
                   countIf(fecha_en_preparacion IS NOT NULL),
                   countIf(fecha_preparado IS NOT NULL),
                   countIf(fecha_despachado IS NOT NULL),
                   countIf(fecha_entregado IS NOT NULL),
                   countIf(horas_pago_a_preparacion IS NOT NULL),
                   countIf(horas_preparacion_a_despacho IS NOT NULL),
                   countIf(horas_despacho_a_entrega IS NOT NULL),
                   countIf(horas_ciclo_total IS NOT NULL)
            FROM {CH_DATABASE}.fact_pedido
        """,
    ),

    Control(
        nombre="fact_flujo_caja",
        fase=2,
        tabla="fact_flujo_caja",
        descripcion="4.079 cobros (176 fallidos) + 902 pagos por $16.084.462,74",
        columnas=("filas", "ingresos", "fallidos", "cobrado", "monto_fallido",
                  "egresos", "monto_egresos", "egresos_a_tiempo", "motivos"),
        sql_pg=f"""
            SELECT (SELECT count(*) FROM pago) + (SELECT count(*) FROM pago_proveedor),
                   (SELECT count(*) FROM pago),
                   (SELECT count(*) FROM pago WHERE estado='fallido'),
                   (SELECT SUM(monto) FROM pago WHERE estado='completado'),
                   (SELECT SUM(monto) FROM pago WHERE estado='fallido'),
                   (SELECT count(*) FROM pago_proveedor),
                   (SELECT SUM(monto) FROM pago_proveedor),
                   (SELECT count(*) FROM pago_proveedor pp
                    JOIN cuenta_por_pagar c ON c.id = pp.cuenta_por_pagar_id
                    WHERE pp.fecha_pago <= c.fecha_vencimiento),
                   (SELECT count(DISTINCT {_MOTIVO_NORMALIZADO})
                    FROM pago pg JOIN transaccion_pago tp ON tp.pago_id = pg.id
                    WHERE pg.estado = 'fallido')
        """,
        sql_ch=f"""
            SELECT count(), countIf(sentido='ingreso'),
                   countIf(sentido='ingreso' AND estado='fallido'),
                   sumIf(monto, sentido='ingreso' AND estado='completado'),
                   sumIf(monto, sentido='ingreso' AND estado='fallido'),
                   countIf(sentido='egreso'), sumIf(monto, sentido='egreso'),
                   countIf(a_tiempo = 1),
                   countDistinctIf(motivo_fallo, motivo_fallo != '')
            FROM {CH_DATABASE}.fact_flujo_caja
        """,
    ),

    Control(
        nombre="motivos_fallo",
        fase=2,
        tabla="fact_flujo_caja",
        descripcion="Los 5 motivos NORMALIZADOS, uno a uno (no 6: §5.3)",
        clave=("motivo",),
        columnas=("motivo", "intentos", "monto"),
        sql_pg=f"""
            SELECT {_MOTIVO_NORMALIZADO} AS motivo, count(*), SUM(pg.monto)
            FROM pago pg JOIN transaccion_pago tp ON tp.pago_id = pg.id
            WHERE pg.estado = 'fallido'
            GROUP BY 1 ORDER BY 1
        """,
        sql_ch=f"""
            SELECT motivo_fallo, count(), sum(monto)
            FROM {CH_DATABASE}.fact_flujo_caja
            WHERE sentido='ingreso' AND estado='fallido'
            GROUP BY motivo_fallo ORDER BY motivo_fallo
        """,
    ),

    Control(
        nombre="caja_mes",
        fase=2,
        tabla="fact_flujo_caja",
        descripcion="Serie mensual de la caja: entra por ventas vs sale a proveedores (GER-02)",
        clave=("mes", "sentido"),
        columnas=("mes", "sentido", "movimientos", "monto"),
        # El cobro FALLIDO no tiene `fecha_pago` —no hubo liquidación—, así que
        # su período es el del INTENTO (`fecha_creacion`). Sin el COALESCE, 176
        # movimientos quedarían fuera de la serie sin un solo error, y OTD-VEN-12
        # (que es un informe por período sobre esos mismos 176) saldría vacío.
        sql_pg=f"""
            SELECT to_char(date_trunc('month',
                       COALESCE(pg.fecha_pago, pg.fecha_creacion)
                           AT TIME ZONE '{ZONA_HORARIA}'), 'YYYY-MM') AS mes,
                   'ingreso' AS sentido, count(*), SUM(pg.monto)
            FROM pago pg GROUP BY 1,2
            UNION ALL
            SELECT to_char(date_trunc('month', pp.fecha_pago), 'YYYY-MM'),
                   'egreso', count(*), SUM(pp.monto)
            FROM pago_proveedor pp GROUP BY 1,2
            ORDER BY 1,2
        """,
        sql_ch=f"""
            SELECT formatDateTime(mes, '%Y-%m'), sentido, count(), sum(monto)
            FROM {CH_DATABASE}.fact_flujo_caja
            GROUP BY mes, sentido ORDER BY mes, sentido
        """,
    ),

    # ═══════════════════════════════════════════════════════════════════════
    # FASE 3A — el ciclo de compras: dim_proveedor, fact_orden_compra,
    # fact_compra_linea (§9.4). Correcciones al diseño detectadas en esta
    # fase: C3.1 a C3.7 en docs/estrategico/CORRECCIONES_DISENO_ETL.md
    # ═══════════════════════════════════════════════════════════════════════

    Control(
        nombre="dim_proveedor",
        fase=3,
        tabla="dim_proveedor",
        descripcion="11 proveedores y sus plazos de crédito (referencia de COM-03)",
        columnas=("filas", "rucs", "activos", "sin_ciudad", "ciudades",
                  "suma_dias_credito", "plazos"),
        sql_pg=f"""
            SELECT count(*), count(DISTINCT pv.ruc),
                   count(*) FILTER (WHERE pv.activo),
                   count(*) FILTER (WHERE pv.ciudad_id IS NULL),
                   count(DISTINCT ci.nombre),
                   SUM(pv.dias_credito), count(DISTINCT pv.dias_credito)
            FROM proveedor pv
            LEFT JOIN ciudad ci ON ci.id = pv.ciudad_id
        """,
        sql_ch=f"""
            SELECT count(), countDistinct(ruc), countIf(activo = 1),
                   countIf(ciudad = 'sin_ciudad'),
                   countDistinctIf(ciudad, ciudad != 'sin_ciudad'),
                   sum(dias_credito), countDistinct(dias_credito)
            FROM {CH_DATABASE}.dim_proveedor
        """,
    ),

    Control(
        nombre="fact_orden_compra",
        fase=3,
        tabla="fact_orden_compra",
        descripcion="865 órdenes · 839 recepciones · 825 pares promesa/llegada · $22.467.387,27",
        columnas=("filas", "con_recepcion", "con_factura", "con_ambos",
                  "con_esperada", "pares", "cumplieron", "total_ordenes",
                  "total_facturas", "cxp_original", "cxp_saldo",
                  "unidades_pedidas", "unidades_recibidas"),
        # `con_recepcion` y `con_factura` valen 839 LAS DOS y describen conjuntos
        # DISTINTOS (corrección C3.1): la OC 8 tiene factura sin recepción y la
        # OC 20 recepción sin factura. `con_ambos` = 838 es la única columna que
        # lo delata; sin ella, un JOIN que pierda una orden pasa en verde.
        sql_pg=f"""
            SELECT (SELECT count(*) FROM orden_compra),
                   (SELECT count(*) FROM orden_compra oc WHERE EXISTS
                     (SELECT 1 FROM recepcion_mercancia r WHERE r.orden_compra_id = oc.id)),
                   (SELECT count(*) FROM orden_compra oc WHERE EXISTS
                     (SELECT 1 FROM factura_compra f WHERE f.orden_compra_id = oc.id)),
                   (SELECT count(*) FROM orden_compra oc
                     WHERE EXISTS (SELECT 1 FROM recepcion_mercancia r
                                   WHERE r.orden_compra_id = oc.id)
                       AND EXISTS (SELECT 1 FROM factura_compra f
                                   WHERE f.orden_compra_id = oc.id)),
                   (SELECT count(*) FROM orden_compra
                     WHERE fecha_entrega_esperada IS NOT NULL),
                   (SELECT count(*) FROM orden_compra oc
                     JOIN recepcion_mercancia r ON r.orden_compra_id = oc.id
                     WHERE oc.fecha_entrega_esperada IS NOT NULL),
                   (SELECT count(*) FROM orden_compra oc
                     JOIN recepcion_mercancia r ON r.orden_compra_id = oc.id
                     WHERE oc.fecha_entrega_esperada IS NOT NULL
                       AND (r.fecha_recepcion AT TIME ZONE '{ZONA_HORARIA}')::date
                           <= oc.fecha_entrega_esperada),
                   (SELECT SUM(total) FROM orden_compra),
                   (SELECT SUM(total) FROM factura_compra),
                   (SELECT SUM(monto_original) FROM cuenta_por_pagar),
                   (SELECT SUM(saldo_pendiente) FROM cuenta_por_pagar),
                   (SELECT SUM(cantidad) FROM orden_compra_detalle),
                   (SELECT SUM(cantidad_recibida) FROM orden_compra_detalle)
        """,
        sql_ch=f"""
            SELECT count(),
                   countIf(fecha_recepcion IS NOT NULL),
                   countIf(factura_numero != ''),
                   countIf(fecha_recepcion IS NOT NULL AND factura_numero != ''),
                   countIf(fecha_entrega_esperada IS NOT NULL),
                   countIf(dias_desvio_promesa IS NOT NULL),
                   countIf(cumplio_promesa = 1),
                   sum(total), sum(factura_total),
                   sum(cxp_monto_original), sum(cxp_saldo_pendiente),
                   sum(unidades_pedidas), sum(unidades_recibidas)
            FROM {CH_DATABASE}.fact_orden_compra
        """,
    ),

    Control(
        nombre="compras_mes",
        fase=3,
        tabla="fact_orden_compra",
        descripcion="Serie mensual del abastecimiento: emitido vs facturado (COM-04, GER-02)",
        clave=("mes",),
        columnas=("mes", "ordenes", "total_emitido", "total_facturado", "recibidas"),
        # El gasto es `factura_total` y NUNCA el total de la orden: difieren en
        # 119 órdenes por $226.070,31 porque el proveedor factura lo que entregó
        # (corrección C3.6). Las dos columnas van juntas aquí a propósito, para
        # que la brecha quede a la vista en la serie y no se descubra tarde.
        sql_pg="""
            SELECT to_char(date_trunc('month', oc.fecha_emision), 'YYYY-MM') AS mes,
                   count(*),
                   SUM(oc.total),
                   COALESCE(SUM(fc.total), 0),
                   count(*) FILTER (WHERE EXISTS
                     (SELECT 1 FROM recepcion_mercancia r WHERE r.orden_compra_id = oc.id))
            FROM orden_compra oc
            LEFT JOIN factura_compra fc ON fc.orden_compra_id = oc.id
            GROUP BY 1 ORDER BY 1
        """,
        sql_ch=f"""
            SELECT formatDateTime(mes, '%Y-%m'), count(),
                   sum(total), sum(factura_total),
                   countIf(fecha_recepcion IS NOT NULL)
            FROM {CH_DATABASE}.fact_orden_compra
            GROUP BY mes ORDER BY mes
        """,
    ),

    Control(
        nombre="fact_compra_linea",
        fase=3,
        tabla="fact_compra_linea",
        descripcion="2.949 líneas (2.855 con recepción) · 122.359 pedidas · 185 rechazadas",
        columnas=("filas", "unidades_pedidas", "unidades_recibidas",
                  "unidades_rechazadas", "unidades_llegadas", "subtotal",
                  "lineas_con_rechazo", "completas", "variantes", "ordenes"),
        # `unidades_llegadas` (recibidas + rechazadas) es el denominador de
        # `pct_rechazo` (corrección C3.2) y viaja como cifra propia: si alguien
        # lo cambiara por `cantidad_pedida`, esta fila deja de cuadrar.
        sql_pg="""
            SELECT (SELECT count(*) FROM orden_compra_detalle),
                   (SELECT SUM(cantidad) FROM orden_compra_detalle),
                   (SELECT SUM(cantidad_recibida) FROM recepcion_detalle),
                   (SELECT SUM(cantidad_rechazada) FROM recepcion_detalle),
                   (SELECT SUM(cantidad_recibida + cantidad_rechazada) FROM recepcion_detalle),
                   (SELECT SUM(subtotal) FROM orden_compra_detalle),
                   (SELECT count(*) FROM recepcion_detalle WHERE cantidad_rechazada > 0),
                   (SELECT count(*) FROM orden_compra_detalle d
                     JOIN recepcion_detalle rd ON rd.orden_compra_detalle_id = d.id
                     WHERE rd.cantidad_recibida >= d.cantidad),
                   (SELECT count(DISTINCT producto_variante_id) FROM orden_compra_detalle),
                   (SELECT count(DISTINCT orden_compra_id) FROM orden_compra_detalle)
        """,
        sql_ch=f"""
            SELECT count(), sum(cantidad_pedida), sum(cantidad_recibida),
                   sum(cantidad_rechazada),
                   sum(cantidad_recibida + cantidad_rechazada),
                   sum(subtotal), countIf(cantidad_rechazada > 0),
                   countIf(completa = 1), countDistinct(producto_variante_id),
                   countDistinct(orden_compra_id)
            FROM {CH_DATABASE}.fact_compra_linea
        """,
    ),

    Control(
        nombre="motivos_rechazo",
        fase=3,
        tabla="fact_compra_linea",
        descripcion="Los 5 motivos NORMALIZADOS de COM-07, uno a uno (no 6: C3.3)",
        clave=("motivo",),
        columnas=("motivo", "lineas", "unidades"),
        # La MISMA traducción que aplica `transformar()` en Python, escrita aquí
        # de nuevo a propósito: si la validación importara el mapa de la tarea,
        # ambos compartirían el error y el control sería una tautología.
        sql_pg="""
            SELECT CASE LOWER(TRIM(rd.motivo_rechazo))
                        WHEN 'cajas mojadas en el transporte'
                             THEN 'Empaque danado en transito'
                        ELSE rd.motivo_rechazo
                   END AS motivo,
                   count(*), SUM(rd.cantidad_rechazada)
            FROM recepcion_detalle rd
            WHERE rd.cantidad_rechazada > 0
            GROUP BY 1 ORDER BY 1
        """,
        sql_ch=f"""
            SELECT motivo_rechazo, count(), sum(cantidad_rechazada)
            FROM {CH_DATABASE}.fact_compra_linea
            WHERE cantidad_rechazada > 0
            GROUP BY motivo_rechazo ORDER BY motivo_rechazo
        """,
    ),

    Control(
        nombre="cuadre_compras",
        fase=3,
        tabla="fact_orden_compra",
        descripcion="CUADRE CONTABLE cruzando el almacén: facturas − pagos = saldo CxP",
        columnas=("facturado", "pagado", "saldo_cxp", "facturado_menos_pagado"),
        # El único control del modelo que cruza DOS tablas de hechos cargadas en
        # fases distintas: el facturado y el saldo salen de `fact_orden_compra`
        # (Fase 3A) y lo pagado de `fact_flujo_caja` (Fase 2). Que las tres
        # cifras cuadren al centavo entre sí Y contra PostgreSQL es la prueba de
        # que las dos fases hablan del mismo dinero.
        #   $22.467.387,27 − $16.084.462,74 = $6.382.924,53 · descuadre $0,00
        sql_pg="""
            SELECT (SELECT SUM(total) FROM factura_compra),
                   (SELECT SUM(monto) FROM pago_proveedor),
                   (SELECT SUM(saldo_pendiente) FROM cuenta_por_pagar),
                   (SELECT SUM(total) FROM factura_compra)
                     - (SELECT SUM(monto) FROM pago_proveedor)
        """,
        sql_ch=f"""
            SELECT (SELECT sum(factura_total) FROM {CH_DATABASE}.fact_orden_compra),
                   (SELECT sumIf(monto, sentido = 'egreso')
                    FROM {CH_DATABASE}.fact_flujo_caja),
                   (SELECT sum(cxp_saldo_pendiente) FROM {CH_DATABASE}.fact_orden_compra),
                   (SELECT sum(factura_total) FROM {CH_DATABASE}.fact_orden_compra)
                     - (SELECT sumIf(monto, sentido = 'egreso')
                        FROM {CH_DATABASE}.fact_flujo_caja)
        """,
    ),

    # ═══════════════════════════════════════════════════════════════════════
    # FASE 3B — el kardex y la reconstrucción del inventario mensual (§5.6,
    # §5.7). Correcciones de esta fase: C3B.1 a C3B.4.
    #
    # El control que decide si la fase es correcta es `stock_cierre_final`:
    # la PRUEBA DEFINITIVA, posición por posición.
    # ═══════════════════════════════════════════════════════════════════════

    Control(
        nombre="fact_movimiento_inventario",
        fase=3,
        tabla="fact_movimiento_inventario",
        descripcion="Movimientos, pares (variante, bodega), tipos y la invariante del kardex",
        columnas=("filas", "pares", "unidades_entrada", "unidades_salida",
                  "suma_con_signo", "tipos", "sin_costo", "ajustes_reales",
                  "naturaleza_ajuste", "saldos_negativos"),
        # `ajustes_reales` (56) y `naturaleza_ajuste` (399) van JUNTOS y a
        # propósito: la distancia entre ambos son los 343 movimientos de apertura
        # que C3B.1 sacó de INV-10. Si algún día coinciden, el informe cambia.
        sql_pg="""
            SELECT (SELECT count(*) FROM movimiento_inventario),
                   (SELECT count(*) FROM (SELECT DISTINCT producto_variante_id, bodega_id
                                          FROM movimiento_inventario) x),
                   (SELECT SUM(mi.cantidad) FROM movimiento_inventario mi
                     JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id
                     WHERE tm.factor > 0),
                   (SELECT SUM(mi.cantidad) FROM movimiento_inventario mi
                     JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id
                     WHERE tm.factor < 0),
                   (SELECT SUM(mi.cantidad * tm.factor) FROM movimiento_inventario mi
                     JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id),
                   (SELECT count(DISTINCT tipo_movimiento_id) FROM movimiento_inventario),
                   (SELECT count(*) FROM movimiento_inventario WHERE costo_unitario IS NULL),
                   (SELECT count(*) FROM movimiento_inventario
                     WHERE referencia_tipo = 'ajuste_inventario'),
                   (SELECT count(*) FROM movimiento_inventario mi
                     JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id
                     WHERE tm.naturaleza = 'ajuste'),
                   (SELECT count(*) FROM movimiento_inventario WHERE stock_nuevo < 0)
        """,
        sql_ch=f"""
            SELECT count(), countDistinct((producto_variante_id, bodega)),
                   sumIf(cantidad, cantidad_con_signo > 0),
                   sumIf(cantidad, cantidad_con_signo < 0),
                   sum(cantidad_con_signo), countDistinct(tipo_movimiento),
                   countIf(sin_costo = 1), countIf(es_ajuste_real = 1),
                   countIf(naturaleza = 'ajuste'), countIf(stock_nuevo < 0)
            FROM {CH_DATABASE}.fact_movimiento_inventario
        """,
    ),

    Control(
        nombre="tipos_movimiento",
        fase=3,
        tabla="fact_movimiento_inventario",
        descripcion="Los 9 tipos de movimiento, uno a uno, con su signo",
        clave=("tipo",),
        columnas=("tipo", "movimientos", "unidades", "neto"),
        sql_pg="""
            SELECT tm.codigo, count(*), SUM(mi.cantidad), SUM(mi.cantidad * tm.factor)
            FROM movimiento_inventario mi
            JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id
            GROUP BY 1 ORDER BY 1
        """,
        sql_ch=f"""
            SELECT tipo_movimiento, count(), sum(cantidad), sum(cantidad_con_signo)
            FROM {CH_DATABASE}.fact_movimiento_inventario
            GROUP BY tipo_movimiento ORDER BY tipo_movimiento
        """,
    ),

    Control(
        nombre="kardex_mes",
        fase=3,
        tabla="fact_movimiento_inventario",
        descripcion="Serie mensual de entradas y salidas del almacén, en todo el período",
        clave=("mes",),
        columnas=("mes", "movimientos", "entradas", "salidas"),
        sql_pg=f"""
            SELECT to_char(date_trunc('month',
                       mi.fecha_creacion AT TIME ZONE '{ZONA_HORARIA}'), 'YYYY-MM') AS mes,
                   count(*),
                   COALESCE(SUM(mi.cantidad) FILTER (WHERE tm.factor > 0), 0),
                   COALESCE(SUM(mi.cantidad) FILTER (WHERE tm.factor < 0), 0)
            FROM movimiento_inventario mi
            JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id
            GROUP BY 1 ORDER BY 1
        """,
        sql_ch=f"""
            SELECT formatDateTime(mes, '%Y-%m'), count(),
                   sumIf(cantidad, cantidad_con_signo > 0),
                   sumIf(cantidad, cantidad_con_signo < 0)
            FROM {CH_DATABASE}.fact_movimiento_inventario
            GROUP BY mes ORDER BY mes
        """,
    ),

    Control(
        nombre="motivos_ajuste",
        fase=3,
        tabla="fact_movimiento_inventario",
        descripcion="Los 11 motivos de ajuste LIMPIOS de INV-10 (en crudo hay 53: C3B.2)",
        clave=("tipo", "motivo"),
        columnas=("tipo", "motivo", "movimientos", "neto"),
        # La MISMA limpieza que hace `transformar()` en Python, reescrita en SQL:
        # quitar el prefijo `[SKU xN]` que puso el seed y el sufijo `· ANULADO:`.
        # Se repite a propósito para que la validación pueda contradecir al ETL.
        sql_pg="""
            SELECT a.tipo,
                   regexp_replace(
                     regexp_replace(a.motivo, '^\\s*\\[[^\\]]*\\]\\s*', ''),
                     '\\s*·\\s*ANULADO:.*$', '') AS motivo,
                   count(*), SUM(mi.cantidad * tm.factor)
            FROM movimiento_inventario mi
            JOIN tipo_movimiento tm     ON tm.id = mi.tipo_movimiento_id
            JOIN ajuste_inventario a    ON a.id  = mi.referencia_id
            WHERE mi.referencia_tipo = 'ajuste_inventario'
            GROUP BY 1, 2 ORDER BY 1, 2
        """,
        sql_ch=f"""
            SELECT ajuste_tipo, ajuste_motivo, count(), sum(cantidad_con_signo)
            FROM {CH_DATABASE}.fact_movimiento_inventario
            WHERE es_ajuste_real = 1
            GROUP BY ajuste_tipo, ajuste_motivo
            ORDER BY ajuste_tipo, ajuste_motivo
        """,
    ),

    Control(
        nombre="fact_stock_mensual",
        fase=3,
        tabla="fact_stock_mensual",
        descripcion="La malla arranca en el PRIMER movimiento de cada par, no en el primer mes (C3B.4)",
        columnas=("filas", "pares", "meses", "con_movimiento", "arrastradas",
                  "entradas", "salidas", "negativos"),
        # `filas` se calcula en PostgreSQL con la definición de C3B.4 —desde el
        # PRIMER movimiento de cada par, no desde el primer mes del período—.
        # Un cartesiano completo daría 26.714 y fabricaría 5.592 ceros.
        sql_pg=f"""
            WITH mov AS (
                SELECT mi.producto_variante_id v, mi.bodega_id b,
                       (date_trunc('month',
                          mi.fecha_creacion AT TIME ZONE '{ZONA_HORARIA}'))::date AS mes,
                       mi.cantidad, tm.factor
                FROM movimiento_inventario mi
                JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id),
            meses AS (SELECT generate_series((SELECT min(mes) FROM mov),
                                             (SELECT max(mes) FROM mov),
                                             interval '1 month')::date AS mes),
            par AS (SELECT v, b, min(mes) AS mes_ini FROM mov GROUP BY v, b),
            malla AS (SELECT p.v, p.b, m.mes FROM par p JOIN meses m ON m.mes >= p.mes_ini)
            SELECT (SELECT count(*) FROM malla),
                   (SELECT count(*) FROM par),
                   (SELECT count(*) FROM meses),
                   (SELECT count(*) FROM (SELECT DISTINCT v, b, mes FROM mov) x),
                   (SELECT count(*) FROM malla)
                     - (SELECT count(*) FROM (SELECT DISTINCT v, b, mes FROM mov) x),
                   (SELECT SUM(cantidad) FROM mov WHERE factor > 0),
                   (SELECT SUM(cantidad) FROM mov WHERE factor < 0),
                   0
        """,
        sql_ch=f"""
            SELECT count(), countDistinct((producto_variante_id, bodega)),
                   countDistinct(mes), countIf(movimientos_mes > 0),
                   countIf(mes_sin_movimiento = 1),
                   sum(entradas_mes), sum(salidas_mes), countIf(stock_cierre < 0)
            FROM {CH_DATABASE}.fact_stock_mensual
        """,
    ),

    Control(
        nombre="stock_cierre_final",
        fase=3,
        tabla="fact_stock_mensual",
        descripcion="★ PRUEBA DEFINITIVA: el cierre del último mes vs inventario.stock_actual, "
                    "posición por posición (1.406)",
        clave=("variante", "bodega"),
        columnas=("variante", "bodega", "stock"),
        # El control que decide si la Fase 3B es correcta. NO es un agregado: dos
        # posiciones que se intercambian el saldo dan la misma suma, y ese es
        # exactamente el «número plausible y equivocado» del que avisa §5.7.
        # Solo 332 de los 1.406 pares tienen movimiento en el último mes: los
        # otros 1.074 llegan por ARRASTRE, así que esta prueba ejercita sobre
        # todo el camino de los meses sin movimiento.
        sql_pg="""
            SELECT i.producto_variante_id, b.nombre, i.stock_actual
            FROM inventario i
            JOIN bodega b ON b.id = i.bodega_id
            ORDER BY 1, 2
        """,
        sql_ch=f"""
            SELECT producto_variante_id, bodega, stock_cierre
            FROM {CH_DATABASE}.fact_stock_mensual
            WHERE mes = (SELECT max(mes) FROM {CH_DATABASE}.fact_stock_mensual)
            ORDER BY 1, 2
        """,
    ),

    Control(
        nombre="stock_continuidad",
        fase=3,
        tabla="fact_stock_mensual",
        descripcion="Sin huecos: cada par tiene fila en todos los meses desde su alta",
        columnas=("pares", "pares_con_hueco", "pares_sin_llegar_al_final",
                  "primer_mes_sin_movimiento"),
        # Un hueco no rompe ninguna suma —por eso hay que buscarlo a propósito—
        # pero parte la serie de INV-09 y hace que una posición desaparezca del
        # gráfico un mes y reaparezca al siguiente. `primer_mes_sin_movimiento`
        # debe ser 0: el primer mes de un par SIEMPRE es el de su alta, que por
        # definición tuvo movimiento.
        sql_pg="""
            SELECT (SELECT count(*) FROM (SELECT DISTINCT producto_variante_id, bodega_id
                                          FROM movimiento_inventario) x),
                   0, 0, 0
        """,
        sql_ch=f"""
            SELECT count(),
                   countIf(meses_reales != meses_esperados),
                   countIf(mes_max != (SELECT max(mes) FROM {CH_DATABASE}.fact_stock_mensual)),
                   countIf(primer_sin_mov = 1)
            FROM (
                SELECT producto_variante_id, bodega,
                       count() AS meses_reales,
                       max(mes) AS mes_max,
                       dateDiff('month', min(mes),
                           (SELECT max(mes) FROM {CH_DATABASE}.fact_stock_mensual)) + 1
                           AS meses_esperados,
                       argMin(mes_sin_movimiento, mes) AS primer_sin_mov
                FROM {CH_DATABASE}.fact_stock_mensual
                GROUP BY producto_variante_id, bodega)
        """,
    ),

    Control(
        nombre="stock_mes",
        fase=3,
        tabla="fact_stock_mensual",
        descripcion="Serie mensual del stock reconstruido: unidades y posiciones vivas",
        clave=("mes",),
        columnas=("mes", "posiciones", "unidades"),
        # La reconstrucción rehecha en PostgreSQL por el camino LARGO —suma
        # acumulada del kardex hasta el cierre de cada mes—, que es justo el
        # método que §5.7 descarta por O(n²). Se usa AQUÍ, y solo aquí, porque
        # dos caminos distintos que dan el mismo número es lo que convierte la
        # reconstrucción en verificada y no en creída.
        sql_pg=f"""
            WITH mov AS (
                SELECT mi.producto_variante_id v, mi.bodega_id b,
                       (date_trunc('month',
                          mi.fecha_creacion AT TIME ZONE '{ZONA_HORARIA}'))::date AS mes,
                       mi.cantidad * tm.factor AS con_signo
                FROM movimiento_inventario mi
                JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id),
            meses AS (SELECT generate_series((SELECT min(mes) FROM mov),
                                             (SELECT max(mes) FROM mov),
                                             interval '1 month')::date AS mes),
            celda AS (SELECT v, b, mes, SUM(con_signo) AS neto FROM mov GROUP BY 1,2,3),
            par AS (SELECT v, b, min(mes) AS mes_ini FROM mov GROUP BY v, b),
            malla AS (SELECT p.v, p.b, m.mes FROM par p JOIN meses m ON m.mes >= p.mes_ini),
            serie AS (
                SELECT ma.mes,
                       SUM(COALESCE(c.neto, 0)) OVER (
                           PARTITION BY ma.v, ma.b ORDER BY ma.mes
                           ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS stock
                FROM malla ma
                LEFT JOIN celda c ON c.v = ma.v AND c.b = ma.b AND c.mes = ma.mes)
            SELECT to_char(mes, 'YYYY-MM'), count(*), SUM(stock)
            FROM serie GROUP BY 1 ORDER BY 1
        """,
        sql_ch=f"""
            SELECT formatDateTime(mes, '%Y-%m'), count(), sum(toInt64(stock_cierre))
            FROM {CH_DATABASE}.fact_stock_mensual
            GROUP BY mes ORDER BY mes
        """,
    ),

    # ═══════════════════════════════════════════════════════════════════════
    # FASE 3C — la última milla: fact_envio, fact_novedad_envio (§5.8, §5.9).
    # Correcciones de esta fase: C3C.1 a C3C.3.
    #
    # El control que decide si la fase es correcta es `zonas_envio`: la
    # resolución por precedencia ciudad > provincia > país.
    # ═══════════════════════════════════════════════════════════════════════

    Control(
        nombre="fact_envio",
        fase=3,
        tabla="fact_envio",
        descripcion="Envíos, entregados, pares con promesa medible y envíos tarifados",
        columnas=("filas", "pedidos", "con_estimada", "con_real", "promesa_medible",
                  "a_tiempo", "suma_transito", "costo_total", "sin_tarifa",
                  "transportistas", "estados", "novedades"),
        # `suma_transito` y `a_tiempo` se calculan en PostgreSQL con la MISMA
        # conversión de zona explícita que usa el ETL (C3C.1). Sin ella el
        # control validaría un tránsito 5,2 % más corto — y cuadraría con un
        # ETL igual de equivocado.
        sql_pg=f"""
            SELECT (SELECT count(*) FROM envio),
                   (SELECT count(DISTINCT pedido_id) FROM envio),
                   (SELECT count(*) FROM envio WHERE fecha_entrega_estimada IS NOT NULL),
                   (SELECT count(*) FROM envio WHERE fecha_entrega_real IS NOT NULL),
                   (SELECT count(*) FROM envio WHERE fecha_entrega_real IS NOT NULL
                      AND fecha_entrega_estimada IS NOT NULL),
                   (SELECT count(*) FROM envio e WHERE e.fecha_entrega_real IS NOT NULL
                      AND (e.fecha_entrega_real AT TIME ZONE '{ZONA_HORARIA}')::date
                          <= e.fecha_entrega_estimada),
                   (SELECT SUM((e.fecha_entrega_real AT TIME ZONE '{ZONA_HORARIA}')::date
                               - (e.fecha_despacho AT TIME ZONE '{ZONA_HORARIA}')::date)
                    FROM envio e WHERE e.fecha_entrega_real IS NOT NULL),
                   (SELECT SUM(costo) FROM envio),
                   (SELECT count(*) FROM envio WHERE costo = 0),
                   (SELECT count(DISTINCT transportista_id) FROM envio),
                   (SELECT count(DISTINCT estado) FROM envio),
                   (SELECT count(*) FROM novedad_envio)
        """,
        sql_ch=f"""
            SELECT count(), countDistinct(pedido_id),
                   countIf(fecha_entrega_estimada IS NOT NULL),
                   countIf(fecha_entrega_real IS NOT NULL),
                   countIf(dias_desvio_promesa IS NOT NULL),
                   countIf(entregado_a_tiempo = 1),
                   sum(ifNull(dias_transito, 0)),
                   sum(costo), countIf(sin_tarifa = 1),
                   countDistinct(transportista), countDistinct(estado),
                   sum(novedades)
            FROM {CH_DATABASE}.fact_envio
        """,
    ),

    Control(
        nombre="zonas_envio",
        fase=3,
        tabla="fact_envio",
        descripcion="★ La resolución de zona por PRECEDENCIA: 181 ciudad / 596 provincia / "
                    "2.078 país / 17 sin dirección",
        clave=("nivel",),
        columnas=("nivel", "envios", "zonas"),
        # El control que decide si la Fase 3C es correcta. Agrupar por país —la
        # simplificación tentadora— mandaría 2.855 de 2.872 envíos a UNA fila
        # sin dar ningún error. Aquí el desglose por nivel lo hace imposible de
        # pasar por alto: si la precedencia se rompe, tres de las cuatro filas
        # cambian de golpe.
        sql_pg="""
            WITH destino AS (
                SELECT e.id AS envio_id, ci.id AS ciudad_id, pr.id AS provincia_id,
                       pr.pais_id
                FROM envio e
                JOIN pedido p          ON p.id  = e.pedido_id
                LEFT JOIN direccion d  ON d.id  = p.direccion_envio_id
                LEFT JOIN ciudad ci    ON ci.id = d.ciudad_id
                LEFT JOIN provincia pr ON pr.id = ci.provincia_id
            ),
            resuelta AS (
                SELECT DISTINCT ON (t.envio_id) t.envio_id, z.nombre AS zona,
                       CASE WHEN z.ciudad_id    IS NOT NULL THEN 'ciudad'
                            WHEN z.provincia_id IS NOT NULL THEN 'provincia'
                            ELSE 'pais' END AS nivel
                FROM destino t
                JOIN zona_envio z ON z.activo AND z.pais_id = t.pais_id
                     AND (z.provincia_id IS NULL OR z.provincia_id = t.provincia_id)
                     AND (z.ciudad_id    IS NULL OR z.ciudad_id    = t.ciudad_id)
                ORDER BY t.envio_id, (z.ciudad_id IS NOT NULL) DESC,
                         (z.provincia_id IS NOT NULL) DESC, z.id
            )
            SELECT COALESCE(r.nivel, 'sin_direccion') AS nivel,
                   count(*), count(DISTINCT COALESCE(r.zona, 'sin_zona'))
            FROM destino d LEFT JOIN resuelta r ON r.envio_id = d.envio_id
            GROUP BY 1 ORDER BY 1
        """,
        sql_ch=f"""
            SELECT zona_nivel, count(), countDistinct(zona)
            FROM {CH_DATABASE}.fact_envio
            GROUP BY zona_nivel ORDER BY zona_nivel
        """,
    ),

    Control(
        nombre="envios_transportista",
        fase=3,
        tabla="fact_envio",
        descripcion="Los 5 transportistas: envíos, entregas, puntualidad y tránsito (LOG-03/04)",
        clave=("transportista",),
        columnas=("transportista", "envios", "entregados", "a_tiempo", "suma_transito"),
        sql_pg=f"""
            SELECT t.nombre, count(*),
                   count(*) FILTER (WHERE e.fecha_entrega_real IS NOT NULL),
                   count(*) FILTER (WHERE e.fecha_entrega_real IS NOT NULL
                       AND (e.fecha_entrega_real AT TIME ZONE '{ZONA_HORARIA}')::date
                           <= e.fecha_entrega_estimada),
                   COALESCE(SUM((e.fecha_entrega_real AT TIME ZONE '{ZONA_HORARIA}')::date
                            - (e.fecha_despacho AT TIME ZONE '{ZONA_HORARIA}')::date), 0)
            FROM envio e JOIN transportista t ON t.id = e.transportista_id
            GROUP BY 1 ORDER BY 1
        """,
        sql_ch=f"""
            SELECT transportista, count(),
                   countIf(fecha_entrega_real IS NOT NULL),
                   countIf(entregado_a_tiempo = 1),
                   sum(ifNull(dias_transito, 0))
            FROM {CH_DATABASE}.fact_envio
            GROUP BY transportista ORDER BY transportista
        """,
    ),

    Control(
        nombre="envios_mes",
        fase=3,
        tabla="fact_envio",
        descripcion="Serie mensual de la última milla: despachados, entregados y costo",
        clave=("mes",),
        columnas=("mes", "despachados", "entregados", "costo"),
        # El mes es el del DESPACHO —cuándo salió— y no el de la entrega: es la
        # fecha que tienen los 2.872, mientras la entrega solo la tienen 2.727.
        sql_pg=f"""
            SELECT to_char(date_trunc('month',
                       e.fecha_despacho AT TIME ZONE '{ZONA_HORARIA}'), 'YYYY-MM') AS mes,
                   count(*),
                   count(*) FILTER (WHERE e.fecha_entrega_real IS NOT NULL),
                   SUM(e.costo)
            FROM envio e GROUP BY 1 ORDER BY 1
        """,
        sql_ch=f"""
            SELECT formatDateTime(mes, '%Y-%m'), count(),
                   countIf(fecha_entrega_real IS NOT NULL), sum(costo)
            FROM {CH_DATABASE}.fact_envio
            GROUP BY mes ORDER BY mes
        """,
    ),

    Control(
        nombre="envios_sin_pedido",
        fase=3,
        tabla="fact_envio",
        descripcion="CONTROL CRUZADO: todo envío se liga a un pedido de fact_pedido (Fase 2)",
        columnas=("envios", "pedidos_distintos", "huerfanos"),
        # Cruza DOS tablas de hechos de fases distintas, como `cuadre_compras`.
        # Es el prerrequisito de OTD-LOG-09 (Fase 4): sin este enlace no se
        # puede calcular «de cada 100 envíos, cuántos terminan en devolución».
        # El anti-join se hace en ClickHouse contra la tabla YA PUBLICADA, que
        # es donde vive el riesgo — no en PostgreSQL, donde la FK lo garantiza.
        sql_pg="""
            SELECT (SELECT count(*) FROM envio),
                   (SELECT count(DISTINCT pedido_id) FROM envio),
                   (SELECT count(*) FROM envio e
                    WHERE NOT EXISTS (SELECT 1 FROM pedido p WHERE p.id = e.pedido_id))
        """,
        sql_ch=f"""
            SELECT count(), countDistinct(pedido_id),
                   countIf(pedido_id NOT IN (
                       SELECT pedido_id FROM {CH_DATABASE}.fact_pedido))
            FROM {CH_DATABASE}.fact_envio
        """,
    ),

    Control(
        nombre="fact_novedad_envio",
        fase=3,
        tabla="fact_novedad_envio",
        descripcion="176 novedades · 169 resueltas · 7 abiertas · 5 tipos · 2 acciones reales",
        columnas=("filas", "envios", "resueltas", "abiertas", "tipos",
                  "devueltas_almacen", "reprogramadas", "accion_segun_diseno",
                  "suma_intentos", "suma_horas"),
        # `accion_segun_diseno` DEBE dar 0 en ambos motores: es la prueba viva de
        # C3C.3. §5.9 dice que los valores son 'devolver_almacen'/'reprogramar'
        # y en la base son 'devuelto_almacen'/'reprogramada' — un filtro escrito
        # desde el diseño devuelve una tabla vacía sin dar ningún error.
        sql_pg="""
            SELECT (SELECT count(*) FROM novedad_envio),
                   (SELECT count(DISTINCT envio_id) FROM novedad_envio),
                   (SELECT count(*) FROM novedad_envio WHERE fecha_resolucion IS NOT NULL),
                   (SELECT count(*) FROM novedad_envio WHERE fecha_resolucion IS NULL),
                   (SELECT count(DISTINCT tipo) FROM novedad_envio),
                   (SELECT count(*) FROM novedad_envio WHERE accion = 'devuelto_almacen'),
                   (SELECT count(*) FROM novedad_envio WHERE accion = 'reprogramada'),
                   (SELECT count(*) FROM novedad_envio
                    WHERE accion IN ('devolver_almacen', 'reprogramar')),
                   (SELECT SUM(intento_numero) FROM novedad_envio),
                   (SELECT ROUND(SUM(ROUND(
                        (EXTRACT(EPOCH FROM (fecha_resolucion - fecha_registro))
                         / 3600.0)::numeric, 2)), 2)
                    FROM novedad_envio WHERE fecha_resolucion IS NOT NULL)
        """,
        sql_ch=f"""
            SELECT count(), countDistinct(envio_id),
                   countIf(resuelta = 1), countIf(resuelta = 0),
                   countDistinct(tipo),
                   countIf(accion = 'devuelto_almacen'),
                   countIf(accion = 'reprogramada'),
                   countIf(accion IN ('devolver_almacen', 'reprogramar')),
                   sum(intento_numero), sum(ifNull(horas_hasta_resolucion, 0))
            FROM {CH_DATABASE}.fact_novedad_envio
        """,
    ),

    Control(
        nombre="novedades_tipo",
        fase=3,
        tabla="fact_novedad_envio",
        descripcion="Los 5 tipos de incidencia × desenlace, uno a uno (LOG-05)",
        clave=("tipo", "accion"),
        columnas=("tipo", "accion", "novedades", "resueltas", "intento_max"),
        # La etiqueta 'sin_resolver' la pone el ETL sobre el NULL del origen:
        # las 7 abiertas tienen que seguir siendo visibles y contables, o el
        # tiempo medio de resolución se calcularía solo sobre lo que sí se cerró.
        sql_pg="""
            SELECT n.tipo, COALESCE(n.accion, 'sin_resolver') AS accion,
                   count(*),
                   count(*) FILTER (WHERE n.fecha_resolucion IS NOT NULL),
                   max(n.intento_numero)
            FROM novedad_envio n GROUP BY 1, 2 ORDER BY 1, 2
        """,
        sql_ch=f"""
            SELECT tipo, accion, count(), countIf(resuelta = 1), max(intento_numero)
            FROM {CH_DATABASE}.fact_novedad_envio
            GROUP BY tipo, accion ORDER BY tipo, accion
        """,
    ),

    # ═══════════════════════════════════════════════════════════════════════
    # FASE 4 — posventa, soporte y marketing (§9.5)
    #
    # Cierra el modelo: con estos controles las 19 tablas están validadas
    # contra PostgreSQL. Los tres últimos son CRUZADOS DENTRO del almacén —no
    # comparan un motor contra el otro, sino una tabla del DWH contra otra— y
    # su lado PostgreSQL es la misma pregunta hecha al origen. Es la forma de
    # atrapar una tabla que quedó de una corrida vieja: sus conteos propios
    # cuadran y su enlace con las demás no.
    # ═══════════════════════════════════════════════════════════════════════

    Control(
        nombre="dim_promocion_producto",
        fase=4,
        tabla="dim_promocion_producto",
        descripcion="Puente promoción ↔ producto: 232 pares sobre 24 promociones",
        columnas=("filas", "promociones", "productos", "sin_fecha_fin",
                  "suma_valor", "tipos_descuento", "activos"),
        sql_pg="""
            SELECT count(*), count(DISTINCT pp.promocion_id),
                   count(DISTINCT pp.producto_id),
                   count(*) FILTER (WHERE pr.fecha_fin IS NULL),
                   SUM(pr.valor), count(DISTINCT pr.tipo_descuento),
                   count(*) FILTER (WHERE pr.activo)
            FROM promocion_producto pp
            JOIN promocion pr ON pr.id = pp.promocion_id
        """,
        sql_ch=f"""
            SELECT count(), countDistinct(promocion_id), countDistinct(producto_id),
                   countIf(fecha_fin IS NULL), sum(valor),
                   countDistinct(tipo_descuento), countIf(activo = 1)
            FROM {CH_DATABASE}.dim_promocion_producto FINAL
        """,
    ),

    Control(
        nombre="fact_devolucion",
        fase=4,
        tabla="fact_devolucion",
        descripcion="RMA: 196 devoluciones · $95.693,89 · 86 reembolsos vs 85 asientos",
        columnas=("filas", "pedidos", "suma_monto", "terminales", "cerradas",
                  "con_reembolso", "suma_reembolso", "asientos", "suma_asientos",
                  "unidades", "meses"),
        # Las DOS cifras del reembolso viajan juntas y sin reconciliar (C4.1):
        # 86 devoluciones con monto en la cabecera y 85 con asiento de
        # tesorería. Que el control las lleve separadas es lo que impide que
        # alguien las «arregle» igualándolas.
        sql_pg="""
            SELECT count(*), count(DISTINCT d.pedido_id), SUM(d.monto_total),
                   count(*) FILTER (WHERE d.estado IN ('cerrada','rechazada')),
                   count(*) FILTER (WHERE d.estado = 'cerrada'),
                   count(*) FILTER (WHERE d.monto_reembolsado IS NOT NULL),
                   COALESCE(SUM(d.monto_reembolsado), 0),
                   (SELECT count(*) FROM reembolso WHERE devolucion_id IS NOT NULL),
                   (SELECT COALESCE(SUM(monto), 0) FROM reembolso
                     WHERE devolucion_id IS NOT NULL),
                   (SELECT SUM(cantidad) FROM devolucion_detalle),
                   count(DISTINCT date_trunc('month',
                         d.fecha_creacion AT TIME ZONE 'America/Guayaquil'))
            FROM devolucion d
        """,
        sql_ch=f"""
            SELECT count(), countDistinct(pedido_id), sum(monto_total),
                   countIf(es_terminal = 1), countIf(estado = 'cerrada'),
                   countIf(monto_reembolsado > 0), sum(monto_reembolsado),
                   countIf(reembolso_registrado = 1), sum(monto_reembolso_asiento),
                   sum(unidades), countDistinct(mes)
            FROM {CH_DATABASE}.fact_devolucion
        """,
    ),

    Control(
        nombre="rma_hitos",
        fase=4,
        tabla="fact_devolucion",
        descripcion="Cobertura de los hitos del RMA: sobre cuántas se mide cada tramo",
        columnas=("con_aprobacion", "con_transito", "con_recepcion",
                  "con_inspeccion", "con_cierre", "con_rechazo", "con_reembolso",
                  "con_3_pasos", "ciclo_medible", "desenlace_medible"),
        # El control que existe por C4.2: `dias_ciclo_total` solo es medible en
        # 35 de 196 y el desenlace terminal en 53. Si un día el seed o el
        # sistema cierran más devoluciones, la cifra sube AQUÍ y en la pantalla
        # del informe a la vez — que es justo lo que impide que LOG-07 siga
        # declarando una base que ya no es la suya.
        sql_pg="""
            WITH h AS (
                SELECT devolucion_id,
                       min(fecha_creacion) FILTER (WHERE estado='aprobada')      AS f_ap,
                       min(fecha_creacion) FILTER (WHERE estado='en_transito')   AS f_tr,
                       min(fecha_creacion) FILTER (WHERE estado='recibida')      AS f_re,
                       min(fecha_creacion) FILTER (WHERE estado='inspeccionada') AS f_in,
                       min(fecha_creacion) FILTER (WHERE estado='cerrada')       AS f_ce,
                       min(fecha_creacion) FILTER (WHERE estado='rechazada')     AS f_rz,
                       count(DISTINCT estado) AS pasos
                FROM historial_estado_devolucion GROUP BY 1)
            SELECT count(*) FILTER (WHERE h.f_ap IS NOT NULL),
                   count(*) FILTER (WHERE h.f_tr IS NOT NULL),
                   count(*) FILTER (WHERE h.f_re IS NOT NULL),
                   count(*) FILTER (WHERE h.f_in IS NOT NULL),
                   count(*) FILTER (WHERE h.f_ce IS NOT NULL),
                   count(*) FILTER (WHERE h.f_rz IS NOT NULL),
                   count(*) FILTER (WHERE d.fecha_reembolso IS NOT NULL),
                   count(*) FILTER (WHERE h.pasos >= 3),
                   count(*) FILTER (WHERE h.f_ce IS NOT NULL),
                   count(*) FILTER (WHERE COALESCE(h.f_ce, h.f_rz) IS NOT NULL)
            FROM devolucion d LEFT JOIN h ON h.devolucion_id = d.id
        """,
        sql_ch=f"""
            SELECT countIf(fecha_aprobacion IS NOT NULL),
                   countIf(fecha_transito   IS NOT NULL),
                   countIf(fecha_recepcion  IS NOT NULL),
                   countIf(fecha_inspeccion IS NOT NULL),
                   countIf(fecha_cierre     IS NOT NULL),
                   countIf(fecha_rechazo    IS NOT NULL),
                   countIf(fecha_reembolso  IS NOT NULL),
                   countIf(pasos_registrados >= 3),
                   countIf(dias_ciclo_total     IS NOT NULL),
                   countIf(dias_hasta_desenlace IS NOT NULL)
            FROM {CH_DATABASE}.fact_devolucion
        """,
    ),

    Control(
        nombre="fact_devolucion_linea",
        fase=4,
        tabla="fact_devolucion_linea",
        descripcion="274 líneas · 424 uds · solo 119 aptas reingresan al stock",
        columnas=("filas", "devoluciones", "unidades", "suma_monto",
                  "inspeccionadas", "aptas", "uds_aptas", "defectuosas",
                  "rechazadas", "variantes"),
        # `suma_monto` cruza esta tabla con `fact_devolucion`: es el MISMO
        # dinero visto por línea y por cabecera. La fórmula se escribe aquí de
        # nuevo para poder contradecir al ETL — pero durante meses se escribió
        # MAL en los dos sitios (`cantidad × precio_unitario`, sin restar el
        # descuento que sí resta `fn_recalcular_total_devolucion`), y una
        # re-derivación que repite el error del original no contradice nada:
        # confirma. Con las 275 líneas sembradas nunca se notó, porque solo 16
        # caen sobre pedidos con descuento; con la posventa de la década la
        # brecha se abrió a $10.814,27.
        sql_pg="""
            SELECT count(*), count(DISTINCT dd.devolucion_id), SUM(dd.cantidad),
                   SUM(ROUND(dd.cantidad * (pd.precio_unitario
                        - (pd.monto_descuento / pd.cantidad)), 2)),
                   count(*) FILTER (WHERE dd.resultado_inspeccion IS NOT NULL),
                   count(*) FILTER (WHERE dd.resultado_inspeccion = 'apto_reventa'),
                   COALESCE(SUM(dd.cantidad) FILTER
                            (WHERE dd.resultado_inspeccion = 'apto_reventa'), 0),
                   count(*) FILTER (WHERE dd.resultado_inspeccion = 'defectuoso'),
                   count(*) FILTER (WHERE dd.resultado_inspeccion = 'rechazado'),
                   count(DISTINCT pd.producto_variante_id)
            FROM devolucion_detalle dd
            JOIN pedido_detalle pd ON pd.id = dd.pedido_detalle_id
        """,
        sql_ch=f"""
            SELECT count(), countDistinct(devolucion_id), sum(cantidad),
                   sum(monto_linea),
                   countIf(inspeccionada = 1), countIf(reingresa_stock = 1),
                   sum(unidades_reingresadas),
                   countIf(resultado_inspeccion = 'defectuoso'),
                   countIf(resultado_inspeccion = 'rechazado'),
                   countDistinct(producto_variante_id)
            FROM {CH_DATABASE}.fact_devolucion_linea
        """,
    ),

    Control(
        nombre="devolucion_motivo",
        fase=4,
        tabla="fact_devolucion_linea",
        descripcion="Motivo × resultado de inspección, uno a uno (LOG-08)",
        clave=("motivo", "resultado_inspeccion"),
        columnas=("motivo", "resultado_inspeccion", "lineas", "unidades",
                  "uds_reingresadas"),
        sql_pg="""
            SELECT md.nombre,
                   COALESCE(dd.resultado_inspeccion, 'sin_inspeccionar'),
                   count(*), SUM(dd.cantidad),
                   COALESCE(SUM(dd.cantidad) FILTER
                            (WHERE dd.resultado_inspeccion = 'apto_reventa'), 0)
            FROM devolucion_detalle dd
            JOIN devolucion d           ON d.id  = dd.devolucion_id
            JOIN motivo_devolucion md   ON md.id = d.motivo_devolucion_id
            GROUP BY 1, 2 ORDER BY 1, 2
        """,
        sql_ch=f"""
            SELECT motivo, resultado_inspeccion, count(), sum(cantidad),
                   sum(unidades_reingresadas)
            FROM {CH_DATABASE}.fact_devolucion_linea
            GROUP BY motivo, resultado_inspeccion
            ORDER BY motivo, resultado_inspeccion
        """,
    ),

    Control(
        nombre="fact_ticket",
        fase=4,
        tabla="fact_ticket",
        descripcion="248 tickets · 76 cerrados · 193 con primera respuesta visible",
        columnas=("filas", "cerrados", "resueltos_por_estado", "con_primera_respuesta",
                  "con_mensaje_equipo", "con_producto", "sin_agente", "sin_categoria",
                  "mensajes", "suma_horas_resolucion", "suma_horas_respuesta"),
        # La definición de «primera respuesta» se escribe aquí ENTERA otra vez
        # (usuario_id poblado + es_interno = false), y no se importa del ETL:
        # es una DECISIÓN (C4.6), y una validación que reutilizara la consulta
        # de la tarea confirmaría la decisión en vez de comprobarla.
        sql_pg="""
            WITH r AS (
                SELECT ticket_soporte_id,
                       min(fecha_creacion) FILTER
                           (WHERE usuario_id IS NOT NULL AND es_interno = false) AS visible,
                       min(fecha_creacion) FILTER
                           (WHERE usuario_id IS NOT NULL)                        AS equipo,
                       count(*) AS mensajes
                FROM mensaje_ticket GROUP BY 1)
            SELECT count(*),
                   count(*) FILTER (WHERE t.fecha_cierre IS NOT NULL),
                   count(*) FILTER (WHERE t.estado IN ('resuelto','cerrado')),
                   count(*) FILTER (WHERE r.visible IS NOT NULL),
                   count(*) FILTER (WHERE r.equipo  IS NOT NULL),
                   count(*) FILTER (WHERE t.producto_variante_id IS NOT NULL),
                   count(*) FILTER (WHERE t.asignado_usuario_id IS NULL),
                   count(*) FILTER (WHERE t.categoria_ticket_id IS NULL),
                   COALESCE(SUM(r.mensajes), 0),
                   ROUND(COALESCE(SUM(ROUND((EXTRACT(EPOCH FROM
                         (t.fecha_cierre - t.fecha_creacion)) / 3600.0)::numeric, 2)), 0), 2),
                   ROUND(COALESCE(SUM(ROUND((EXTRACT(EPOCH FROM
                         (r.visible - t.fecha_creacion)) / 3600.0)::numeric, 2)), 0), 2)
            FROM ticket_soporte t LEFT JOIN r ON r.ticket_soporte_id = t.id
        """,
        sql_ch=f"""
            SELECT count(), countIf(fecha_cierre IS NOT NULL),
                   countIf(resuelto_por_estado = 1),
                   countIf(fecha_primera_respuesta IS NOT NULL),
                   countIf(fecha_primer_mensaje_equipo IS NOT NULL),
                   countIf(tiene_producto = 1),
                   countIf(agente = '(sin asignar)'),
                   countIf(categoria = 'sin_categoria'),
                   sum(mensajes),
                   sum(ifNull(horas_resolucion, 0)),
                   sum(ifNull(horas_primera_respuesta, 0))
            FROM {CH_DATABASE}.fact_ticket
        """,
    ),

    Control(
        nombre="ticket_sla",
        fase=4,
        tabla="fact_ticket",
        descripcion="SOP-02: cerrados a tiempo / tarde, y los abiertos sin veredicto",
        columnas=("cerrados_a_tiempo", "cerrados_tarde", "sin_veredicto",
                  "sla_2h", "sla_4h", "sla_24h", "sla_72h"),
        # Solo las DOS categorías de cerrados se validan contra PostgreSQL: las
        # otras dos («abierto dentro de plazo» y «abierto y ya vencido») dependen
        # de `now()` y no se precalculan, así que aquí solo se comprueba que los
        # abiertos lleguen SIN veredicto — que es lo que permite al informe
        # partirlos en el momento de la consulta sin inventarse un cumplimiento.
        sql_pg="""
            SELECT count(*) FILTER (WHERE fecha_cierre IS NOT NULL
                                      AND fecha_cierre <= fecha_limite),
                   count(*) FILTER (WHERE fecha_cierre IS NOT NULL
                                      AND fecha_cierre >  fecha_limite),
                   count(*) FILTER (WHERE fecha_cierre IS NULL),
                   count(*) FILTER (WHERE prioridad = 'urgente'),
                   count(*) FILTER (WHERE prioridad = 'alta'),
                   count(*) FILTER (WHERE prioridad = 'media'),
                   count(*) FILTER (WHERE prioridad = 'baja')
            FROM ticket_soporte
        """,
        sql_ch=f"""
            SELECT countIf(cumplio_sla = 1), countIf(cumplio_sla = 0),
                   countIf(cumplio_sla IS NULL),
                   countIf(horas_sla_comprometidas = 2),
                   countIf(horas_sla_comprometidas = 4),
                   countIf(horas_sla_comprometidas = 24),
                   countIf(horas_sla_comprometidas = 72)
            FROM {CH_DATABASE}.fact_ticket
        """,
    ),

    Control(
        nombre="ticket_categoria",
        fase=4,
        tabla="fact_ticket",
        descripcion="Las 8 categorías + el ticket sin clasificar (SOP-04, C4.3)",
        clave=("categoria",),
        columnas=("categoria", "tickets", "cerrados", "con_respuesta"),
        sql_pg="""
            SELECT COALESCE(ct.nombre, 'sin_categoria'), count(*),
                   count(*) FILTER (WHERE t.fecha_cierre IS NOT NULL),
                   count(*) FILTER (WHERE EXISTS (
                       SELECT 1 FROM mensaje_ticket m
                        WHERE m.ticket_soporte_id = t.id
                          AND m.usuario_id IS NOT NULL AND m.es_interno = false))
            FROM ticket_soporte t
            LEFT JOIN categoria_ticket ct ON ct.id = t.categoria_ticket_id
            GROUP BY 1 ORDER BY 1
        """,
        sql_ch=f"""
            SELECT categoria, count(), countIf(fecha_cierre IS NOT NULL),
                   countIf(fecha_primera_respuesta IS NOT NULL)
            FROM {CH_DATABASE}.fact_ticket GROUP BY categoria ORDER BY categoria
        """,
    ),

    Control(
        nombre="fact_resena",
        fase=4,
        tabla="fact_resena",
        descripcion="344 reseñas sobre 268 productos — sin fan-out de variante (C4.4)",
        columnas=("filas", "productos", "clientes", "suma_calificacion",
                  "verificadas", "moderadas", "aprobadas", "pendientes",
                  "rechazadas", "meses"),
        # `filas` = 344 y no las 347 que daría unir por `producto_id` contra una
        # dimensión con grano de variante. Es el control que hace visible C4.4.
        sql_pg="""
            SELECT count(*), count(DISTINCT producto_id), count(DISTINCT cliente_id),
                   SUM(calificacion),
                   count(*) FILTER (WHERE compra_verificada),
                   count(*) FILTER (WHERE fecha_moderacion IS NOT NULL),
                   count(*) FILTER (WHERE estado = 'aprobada'),
                   count(*) FILTER (WHERE estado = 'pendiente'),
                   count(*) FILTER (WHERE estado = 'rechazada'),
                   count(DISTINCT date_trunc('month',
                         fecha_creacion AT TIME ZONE 'America/Guayaquil'))
            FROM resena
        """,
        sql_ch=f"""
            SELECT count(), countDistinct(producto_id), countDistinct(cliente_id),
                   sum(calificacion), countIf(compra_verificada = 1),
                   countIf(moderada = 1), countIf(estado = 'aprobada'),
                   countIf(estado = 'pendiente'), countIf(estado = 'rechazada'),
                   countDistinct(mes)
            FROM {CH_DATABASE}.fact_resena
        """,
    ),

    Control(
        nombre="fact_devolucion_proveedor",
        fase=4,
        tabla="fact_devolucion_proveedor",
        descripcion="38 ítems · $9.349,93 de coste · $5.220,94 recuperados en 6 resoluciones",
        columnas=("filas", "unidades", "costo_total", "de_rma", "de_recepcion",
                  "origen_segun_diseno", "sin_proveedor", "items_agrupados",
                  "items_resueltos", "valor_recuperado", "nota_credito"),
        # `origen_segun_diseno` DEBE dar 0 en los dos motores: es la prueba viva
        # de C4.7 (los valores de §5.14 no existen en la base). El día que deje
        # de ser 0, el diseño tenía razón y la corrección sobra.
        sql_pg="""
            SELECT count(*), SUM(i.cantidad),
                   ROUND(SUM(i.cantidad * COALESCE(i.costo_unitario, 0)), 2),
                   count(*) FILTER (WHERE i.origen = 'rma'),
                   count(*) FILTER (WHERE i.origen = 'recepcion'),
                   count(*) FILTER (WHERE i.origen IN ('inspeccion_rma','recepcion_compra')),
                   count(*) FILTER (WHERE i.proveedor_id IS NULL),
                   (SELECT count(DISTINCT item_defectuoso_id)
                      FROM devolucion_proveedor_detalle),
                   (SELECT count(*) FROM devolucion_proveedor_detalle dpd
                      JOIN devolucion_proveedor dp ON dp.id = dpd.devolucion_proveedor_id
                     WHERE dp.estado IN ('resuelta','cerrada')),
                   (SELECT ROUND(COALESCE(SUM(i2.cantidad
                                    * COALESCE(i2.costo_unitario, 0)), 0), 2)
                      FROM devolucion_proveedor_detalle dpd
                      JOIN devolucion_proveedor dp ON dp.id = dpd.devolucion_proveedor_id
                      JOIN item_defectuoso i2      ON i2.id = dpd.item_defectuoso_id
                     WHERE dp.estado IN ('resuelta','cerrada')),
                   (SELECT ROUND(COALESCE(SUM(monto_credito), 0), 2)
                      FROM devolucion_proveedor)
            FROM item_defectuoso i
        """,
        sql_ch=f"""
            SELECT count(), sum(cantidad), sum(costo_total),
                   countIf(origen = 'rma'), countIf(origen = 'recepcion'),
                   countIf(origen IN ('inspeccion_rma','recepcion_compra')),
                   countIf(proveedor = 'sin_asignar'),
                   countIf(devolucion_proveedor_id != 0),
                   countIf(resuelto = 1), sum(valor_recuperado),
                   sumIf(valor_recuperado, tipo_resolucion = 'nota_credito')
            FROM {CH_DATABASE}.fact_devolucion_proveedor
        """,
    ),

    # ── Los tres controles CRUZADOS dentro del almacén ─────────────────────
    # No comparan una tabla contra su origen: comparan una tabla del DWH contra
    # OTRA del DWH, y el lado PostgreSQL hace la misma pregunta al origen. Un
    # `fact_devolucion` recargado hoy contra un `fact_pedido` de la semana
    # pasada cuadra consigo mismo y falla aquí.

    Control(
        nombre="cruce_devolucion_pedido",
        fase=4,
        tabla="fact_devolucion",
        descripcion="CRUZADO: toda devolución liga a un pedido del almacén (0 huérfanas)",
        columnas=("devoluciones", "pedidos_distintos", "huerfanas", "suma_total_pedido"),
        sql_pg="""
            SELECT count(*), count(DISTINCT d.pedido_id),
                   count(*) FILTER (WHERE p.id IS NULL),
                   ROUND(COALESCE(SUM(p.total), 0), 2)
            FROM devolucion d LEFT JOIN pedido p ON p.id = d.pedido_id
        """,
        sql_ch=f"""
            SELECT count(), countDistinct(d.pedido_id),
                   countIf(p.pedido_id = 0),
                   sum(d.total_pedido)
            FROM {CH_DATABASE}.fact_devolucion d
            LEFT JOIN {CH_DATABASE}.fact_pedido p ON p.pedido_id = d.pedido_id
        """,
    ),

    Control(
        nombre="cruce_posventa_producto",
        fase=4,
        tabla="fact_devolucion_linea",
        descripcion="CRUZADO: líneas de devolución, tickets con producto y reseñas "
                    "resuelven todos contra dim_producto",
        columnas=("lineas_dev", "lineas_sin_dim", "tickets_producto",
                  "tickets_sin_dim", "resenas", "productos_resenados",
                  "resenas_sin_dim"),
        # La reseña se cruza por PRODUCTO y no por variante (C4.4), así que el
        # lado ClickHouse une contra los `producto_id` DISTINTOS de dim_producto
        # y no contra la dimensión entera: unirla en crudo devolvería 347 donde
        # hay 344 y este control estaría reproduciendo el error que vigila.
        sql_pg="""
            SELECT (SELECT count(*) FROM devolucion_detalle),
                   (SELECT count(*) FROM devolucion_detalle dd
                      JOIN pedido_detalle pd ON pd.id = dd.pedido_detalle_id
                     WHERE NOT EXISTS (SELECT 1 FROM producto_variante v
                                        WHERE v.id = pd.producto_variante_id)),
                   (SELECT count(*) FROM ticket_soporte
                     WHERE producto_variante_id IS NOT NULL),
                   (SELECT count(*) FROM ticket_soporte t
                     WHERE t.producto_variante_id IS NOT NULL
                       AND NOT EXISTS (SELECT 1 FROM producto_variante v
                                        WHERE v.id = t.producto_variante_id)),
                   (SELECT count(*) FROM resena),
                   (SELECT count(DISTINCT producto_id) FROM resena),
                   (SELECT count(*) FROM resena r
                     WHERE NOT EXISTS (SELECT 1 FROM producto_variante v
                                        WHERE v.producto_id = r.producto_id))
        """,
        sql_ch=f"""
            SELECT
                (SELECT count() FROM {CH_DATABASE}.fact_devolucion_linea),
                (SELECT count() FROM {CH_DATABASE}.fact_devolucion_linea l
                   LEFT ANTI JOIN {CH_DATABASE}.dim_producto p
                     ON l.producto_variante_id = p.producto_variante_id),
                (SELECT countIf(tiene_producto = 1) FROM {CH_DATABASE}.fact_ticket),
                (SELECT count() FROM
                    (SELECT producto_variante_id FROM {CH_DATABASE}.fact_ticket
                      WHERE tiene_producto = 1) t
                   LEFT ANTI JOIN {CH_DATABASE}.dim_producto p
                     ON t.producto_variante_id = p.producto_variante_id),
                (SELECT count() FROM {CH_DATABASE}.fact_resena),
                (SELECT countDistinct(producto_id) FROM {CH_DATABASE}.fact_resena),
                (SELECT count() FROM
                    (SELECT DISTINCT producto_id FROM {CH_DATABASE}.fact_resena) r
                   LEFT ANTI JOIN
                    (SELECT DISTINCT producto_id FROM {CH_DATABASE}.dim_producto) p
                     ON r.producto_id = p.producto_id)
        """,
    ),

    Control(
        nombre="cruce_devolucion_envio",
        fase=4,
        tabla="fact_devolucion",
        descripcion="CRUZADO: base mensual de OTD-LOG-09 — envíos despachados vs "
                    "devoluciones del mes",
        clave=("mes",),
        columnas=("mes", "envios", "devoluciones"),
        # LOG-09 divide dos poblaciones que viven en tablas de FASES distintas
        # (fact_envio es de la 3C). Se validan JUNTAS mes a mes porque el
        # cociente es el informe: un mes que exista en una tabla y no en la
        # otra daría una tasa infinita o cero sin que ninguna suma fallara.
        sql_pg="""
            WITH meses AS (
                SELECT DISTINCT date_trunc('month',
                       fecha_despacho AT TIME ZONE 'America/Guayaquil')::date AS mes
                FROM envio
                UNION
                SELECT DISTINCT date_trunc('month',
                       fecha_creacion AT TIME ZONE 'America/Guayaquil')::date
                FROM devolucion)
            SELECT to_char(m.mes, 'YYYY-MM'),
                   (SELECT count(*) FROM envio e
                     WHERE date_trunc('month',
                           e.fecha_despacho AT TIME ZONE 'America/Guayaquil')::date = m.mes),
                   (SELECT count(*) FROM devolucion d
                     WHERE date_trunc('month',
                           d.fecha_creacion AT TIME ZONE 'America/Guayaquil')::date = m.mes)
            FROM meses m ORDER BY 1
        """,
        sql_ch=f"""
            SELECT formatDateTime(mes, '%Y-%m') AS periodo,
                   sum(envios) AS envios, sum(devoluciones) AS devoluciones
            FROM (
                SELECT mes, count() AS envios, 0 AS devoluciones
                  FROM {CH_DATABASE}.fact_envio GROUP BY mes
                UNION ALL
                SELECT mes, 0, count() FROM {CH_DATABASE}.fact_devolucion GROUP BY mes
            ) GROUP BY mes ORDER BY periodo
        """,
    ),

    # ═══════════════════════════════════════════════════════════════════════
    # FASE E2 — la previsión de demanda (nivel ESTRATÉGICO, §5.1)
    # ═══════════════════════════════════════════════════════════════════════
    #
    # Aquí la validación cruzada cambia de naturaleza y conviene decirlo. Las 44
    # anteriores comparan HECHOS al centavo: PostgreSQL tiene la misma fila y el
    # criterio es la igualdad exacta. Una previsión es una fila con fecha
    # FUTURA: PostgreSQL no la tiene y no puede tenerla, así que no hay ninguna
    # cifra prevista que contrastar.
    #
    # Lo que sí se puede contrastar —y es lo único que de verdad falla aquí— es
    # el UNIVERSO y el ANCLA:
    #
    #   * el universo: se previsiona exactamente el catálogo que existe. Si el
    #     almacén perdiera una categoría o un puñado de variantes, el modelo
    #     publicaría tan tranquilo una previsión de un catálogo más pequeño, con
    #     todas sus cifras coherentes entre sí y equivocadas — el patrón
    #     dominante del sistema.
    #   * el ancla: el primer mes previsto tiene que estar donde acaba la venta
    #     REAL de PostgreSQL. Es el control que detecta una tabla RANCIA, que es
    #     el modo de fallo propio de una predicción: si entra un mes de ventas y
    #     el modelo no vuelve a correr, la pantalla sigue enseñando la previsión
    #     de un mes que YA ocurrió, sin dar ningún error y con su banda intacta.

    Control(
        nombre="fact_prevision_demanda",
        fase=5,
        tabla="fact_prevision_demanda",
        descripcion="Universo previsionado: el catálogo que PostgreSQL tiene, sin "
                    "perder ni inventar series",
        columnas=("filas", "categorias", "variantes_largas", "meses_previstos",
                  "series_sin_prevision"),
        sql_pg="""
            WITH linea AS (
                SELECT (date_trunc('month',
                            p.fecha_pedido AT TIME ZONE 'America/Guayaquil'))::date AS mes,
                       COALESCE(c.nombre, 'sin_categoria')                     AS categoria,
                       pd.producto_variante_id                                 AS variante
                FROM pedido_detalle pd
                JOIN pedido p             ON p.id  = pd.pedido_id
                JOIN estado_pedido ep     ON ep.id = p.estado_pedido_id
                JOIN producto_variante pv ON pv.id = pd.producto_variante_id
                LEFT JOIN producto_categoria pc
                                          ON pc.producto_id = pv.producto_id
                                         AND pc.es_principal
                LEFT JOIN categoria c     ON c.id  = pc.categoria_id
                WHERE ep.codigo <> 'cancelado'
            ),
            cat AS (SELECT categoria, count(DISTINCT mes) AS meses
                      FROM linea GROUP BY categoria),
            var AS (SELECT variante, count(DISTINCT mes) AS meses
                      FROM linea GROUP BY variante)
            SELECT ((SELECT count(*) FROM cat)
                    + (SELECT count(*) FROM var WHERE meses >= 12) + 1) * 3,
                   (SELECT count(*) FROM cat),
                   (SELECT count(*) FROM var WHERE meses >= 12),
                   3,
                   (SELECT count(*) FROM cat WHERE meses < 12)
        """,
        sql_ch=f"""
            SELECT count(),
                   countDistinctIf(categoria, nivel = 'categoria'),
                   countDistinctIf(producto_variante_id, nivel = 'variante'),
                   countDistinct(mes),
                   countDistinctIf(categoria, metodo = 'sin_prevision')
            FROM {CH_DATABASE}.fact_prevision_demanda
        """,
    ),

    Control(
        nombre="prevision_ancla",
        fase=5,
        tabla="fact_prevision_demanda",
        descripcion="La previsión arranca donde acaba la venta real: detecta una "
                    "tabla rancia, el modo de fallo propio de una predicción",
        columnas=("ultimo_mes_vendido", "primer_mes_previsto", "ultimo_previsto",
                  "desfase_entrenamiento"),
        # Tres meses a partir del último mes vendido, ni uno más ni uno menos.
        # Ningún desplazamiento se escribe a mano en el lado de ClickHouse:
        # PostgreSQL los deriva de su propio `max(mes)` y ClickHouse los lee de
        # la tabla publicada.
        #
        # La cuarta columna es la que vigila la decisión de §5.1.2: PostgreSQL
        # determina POR SU CUENTA si el último mes está truncado —comparando el
        # día más alto con pedidos contra la mediana de los meses anteriores— y
        # de ahí sale el desfase que el entrenamiento debería tener (2 si el mes
        # se excluyó, 1 si entró). ClickHouse lo responde con
        # `horizonte_efectivo - horizonte`. Si el ETL dejara de excluir el mes
        # incompleto, las dos cifras dejarían de coincidir aquí — y ninguna suma
        # de la tabla habría fallado.
        sql_pg="""
            WITH linea AS (
                SELECT (p.fecha_pedido AT TIME ZONE 'America/Guayaquil') AS f
                FROM pedido p
                JOIN estado_pedido ep ON ep.id = p.estado_pedido_id
                WHERE ep.codigo <> 'cancelado'
            ),
            por_mes AS (
                SELECT date_trunc('month', f)::date AS mes,
                       max(extract(day FROM f))     AS dia_max
                FROM linea GROUP BY 1
            ),
            ultimo AS (SELECT max(mes) AS mes FROM por_mes),
            corte AS (
                SELECT (SELECT dia_max FROM por_mes
                         WHERE mes = (SELECT mes FROM ultimo))       AS propio,
                       (SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY dia_max)
                          FROM por_mes
                         WHERE mes <> (SELECT mes FROM ultimo))      AS referencia
            )
            SELECT to_char(u.mes, 'YYYY-MM'),
                   to_char(u.mes + interval '1 month', 'YYYY-MM'),
                   to_char(u.mes + interval '3 month', 'YYYY-MM'),
                   CASE WHEN c.propio < c.referencia THEN 2 ELSE 1 END
            FROM ultimo u CROSS JOIN corte c
        """,
        sql_ch=f"""
            SELECT
                (SELECT formatDateTime(max(mes), '%Y-%m')
                   FROM {CH_DATABASE}.fact_venta_linea WHERE es_cancelado = 0),
                (SELECT formatDateTime(min(mes), '%Y-%m')
                   FROM {CH_DATABASE}.fact_prevision_demanda),
                (SELECT formatDateTime(max(mes), '%Y-%m')
                   FROM {CH_DATABASE}.fact_prevision_demanda),
                (SELECT max(toInt16(horizonte_efectivo) - toInt16(horizonte))
                   FROM {CH_DATABASE}.fact_prevision_demanda) + 1
        """,
    ),

    # ═══════════════════════════════════════════════════════════════════════
    # FASE E3 — la alerta de abandono de cliente (nivel ESTRATÉGICO, §5.2)
    # ═══════════════════════════════════════════════════════════════════════
    #
    # Aquí la validación cruzada vuelve a ser posible al detalle, y es una
    # suerte: la alerta es una PREDICCIÓN, pero se calcula con una fórmula
    # cerrada sobre tres cantidades que PostgreSQL sí tiene —cuántas veces
    # compró el cliente en la ventana, desde cuándo, y cuánto lleva callado—.
    # Así que PostgreSQL puede recalcular el modelo ENTERO, incluida la
    # exponencial, y contradecir al almacén cliente por cliente.
    #
    # Los tres controles atacan los tres modos de fallo de esta fase, y ninguno
    # de ellos rompe una suma:
    #
    #   * `alerta_ancla` — el ANCLA y la VENTANA. §5.2.5: la recencia se mide
    #     contra `max(fecha_pedido)` del almacén y jamás contra el reloj. Si el
    #     almacén se quedara atrás, todos los silencios saldrían cortos y la
    #     pantalla quedaría tranquilizadora. Vigila además la CONCENTRACIÓN
    #     máxima mensual, que es el guardia del artefacto de la rampa.
    #   * `alerta_lambda` — los INSUMOS de λ, cliente por cliente. Es el control
    #     fuerte: 69 filas × 3 cantidades reconstruidas desde `pedido`.
    #   * `alerta_niveles` — el VEREDICTO por cliente. PostgreSQL calcula
    #     e^(−λt) por su cuenta y cuenta cuántos caen en cada nivel. Si el
    #     almacén cambiara α o el orden de los umbrales, aquí se vería; una lista
    #     con los semáforos corridos no da ningún error por sí sola.

    Control(
        nombre="alerta_ancla",
        fase=6,
        tabla="fact_alerta_cliente",
        descripcion="Ancla, ventana estable y concentración máxima: el marco "
                    "temporal del que depende toda la alerta",
        columnas=("filas", "ancla", "ventana_inicio", "concentracion_maxima",
                  "meses_ventana"),
        # La ventana se deriva del ancla también en PostgreSQL: 7 meses contados
        # desde el mes del ancla incluido, o sea 6 meses de resta. Se escribe
        # aquí a propósito en vez de importarla del modelo — este script debe
        # poder contradecir al ETL, y compartir la constante sería una
        # tautología.
        sql_pg="""
            WITH venta AS (
                SELECT p.cliente_id AS cid,
                       (p.fecha_pedido AT TIME ZONE 'America/Guayaquil')::date AS dia
                FROM pedido p
                JOIN estado_pedido ep ON ep.id = p.estado_pedido_id
                WHERE ep.codigo <> 'cancelado' AND p.cliente_id IS NOT NULL
            ),
            ancla AS (SELECT max(dia) AS d FROM venta),
            ventana AS (
                SELECT (date_trunc('month', (SELECT d FROM ancla))
                        - interval '6 months')::date AS ini
            ),
            por_mes AS (
                SELECT date_trunc('month', dia)::date AS mes, cid, count(*) AS n
                FROM venta WHERE dia >= (SELECT ini FROM ventana)
                GROUP BY 1, 2
            ),
            conc AS (
                SELECT mes, sum(n) AS tot, max(n) AS mx FROM por_mes GROUP BY mes
            )
            SELECT (SELECT count(DISTINCT cid) FROM venta),
                   (SELECT d FROM ancla),
                   (SELECT ini FROM ventana),
                   (SELECT round(max(100.0 * mx / tot), 2) FROM conc),
                   7
        """,
        sql_ch=f"""
            SELECT count(), max(fecha_ancla), max(ventana_inicio),
                   max(concentracion_maxima), max(meses_ventana)
            FROM {CH_DATABASE}.fact_alerta_cliente
        """,
    ),

    Control(
        nombre="alerta_lambda",
        fase=6,
        tabla="fact_alerta_cliente",
        descripcion="Los insumos de λ cliente por cliente: pedidos en la ventana, "
                    "días observados y días de silencio",
        clave=("cliente_id",),
        columnas=("cliente_id", "pedidos_ventana", "dias_observados", "dias_silencio"),
        # `dias_observados` arranca en la PRIMERA compra del cliente DENTRO de la
        # ventana, no en el inicio de la ventana: contarle a un cliente de mayo
        # los cuatro meses en que todavía no era cliente le divide λ por tres.
        # Los clientes sin muestra (menos de 3 pedidos) llevan 0 días observados
        # porque no tienen λ — y aun así están en la tabla con su silencio real.
        sql_pg="""
            WITH venta AS (
                SELECT p.cliente_id AS cid,
                       (p.fecha_pedido AT TIME ZONE 'America/Guayaquil')::date AS dia
                FROM pedido p
                JOIN estado_pedido ep ON ep.id = p.estado_pedido_id
                WHERE ep.codigo <> 'cancelado' AND p.cliente_id IS NOT NULL
            ),
            ancla AS (SELECT max(dia) AS d FROM venta),
            ventana AS (
                SELECT (date_trunc('month', (SELECT d FROM ancla))
                        - interval '6 months')::date AS ini
            ),
            agg AS (
                SELECT cid,
                       count(*) FILTER (WHERE dia >= (SELECT ini FROM ventana)) AS en_ventana,
                       min(dia) FILTER (WHERE dia >= (SELECT ini FROM ventana)) AS primera,
                       max(dia) AS ultima
                FROM venta GROUP BY cid
            )
            SELECT cid,
                   en_ventana,
                   CASE WHEN en_ventana >= 3
                        THEN ((SELECT d FROM ancla) - primera) + 1 ELSE 0 END,
                   (SELECT d FROM ancla) - ultima
            FROM agg ORDER BY cid
        """,
        sql_ch=f"""
            SELECT cliente_id, pedidos_ventana, dias_observados, dias_silencio
            FROM {CH_DATABASE}.fact_alerta_cliente ORDER BY cliente_id
        """,
    ),

    Control(
        nombre="alerta_niveles",
        fase=6,
        tabla="fact_alerta_cliente",
        descripcion="El veredicto: PostgreSQL recalcula e^(−λt) y reparte los "
                    "clientes por nivel de alerta",
        columnas=("criticas", "atencion", "normal", "sin_muestra", "en_alerta"),
        # α = 0,05 y la frontera de `atencion` en 0,10 se escriben aquí, otra vez
        # a propósito. Si alguien moviera el umbral en el modelo sin decirlo, la
        # lista seguiría saliendo perfectamente formada con los semáforos
        # corridos, y este control es lo único que lo notaría.
        sql_pg="""
            WITH venta AS (
                SELECT p.cliente_id AS cid,
                       (p.fecha_pedido AT TIME ZONE 'America/Guayaquil')::date AS dia
                FROM pedido p
                JOIN estado_pedido ep ON ep.id = p.estado_pedido_id
                WHERE ep.codigo <> 'cancelado' AND p.cliente_id IS NOT NULL
            ),
            ancla AS (SELECT max(dia) AS d FROM venta),
            ventana AS (
                SELECT (date_trunc('month', (SELECT d FROM ancla))
                        - interval '6 months')::date AS ini
            ),
            agg AS (
                SELECT cid,
                       count(*) FILTER (WHERE dia >= (SELECT ini FROM ventana)) AS en_ventana,
                       min(dia) FILTER (WHERE dia >= (SELECT ini FROM ventana)) AS primera,
                       max(dia) AS ultima
                FROM venta GROUP BY cid
            ),
            prob AS (
                SELECT cid, en_ventana,
                       CASE WHEN en_ventana >= 3 THEN
                           exp(- (en_ventana::float8
                                  / (((SELECT d FROM ancla) - primera) + 1))
                               * ((SELECT d FROM ancla) - ultima))
                       END AS p
                FROM agg
            )
            SELECT count(*) FILTER (WHERE p IS NOT NULL AND p <  0.05),
                   count(*) FILTER (WHERE p IS NOT NULL AND p >= 0.05 AND p < 0.10),
                   count(*) FILTER (WHERE p IS NOT NULL AND p >= 0.10),
                   count(*) FILTER (WHERE p IS NULL),
                   count(*) FILTER (WHERE p IS NOT NULL AND p < 0.10)
            FROM prob
        """,
        sql_ch=f"""
            SELECT countIf(nivel_alerta = 'critica'),
                   countIf(nivel_alerta = 'atencion'),
                   countIf(nivel_alerta = 'normal'),
                   countIf(nivel_alerta = 'sin_muestra'),
                   countIf(nivel_alerta IN ('critica', 'atencion'))
            FROM {CH_DATABASE}.fact_alerta_cliente
        """,
    ),
]


# ═══════════════════════════════════════════════════════════════════════════
# Motor de comparación (genérico: no sabe de qué tabla se trata)
# ═══════════════════════════════════════════════════════════════════════════

def _consultar_pg(sql: str) -> list[tuple]:
    conn = get_pg_connection()
    try:
        with conn.cursor() as cur:
            cur.execute(sql)
            return cur.fetchall()
    finally:
        conn.close()


def _normalizar(valor):
    """
    Lleva ambos motores a un tipo comparable SIN perder exactitud.

    ClickHouse devuelve `Decimal` para las columnas `Decimal(14,2)` y `int`
    para los enteros; PostgreSQL devuelve `Decimal` para `numeric` e `int` para
    los conteos. El punto delicado es que un `Decimal('4991078.85')` y un
    `Decimal('4991078.850')` NO son iguales con `==` sobre la tupla si la escala
    difiere — sí lo son con `Decimal.compare`, que es lo que hace `==` entre
    Decimals. Se convierte todo a `Decimal` y JAMÁS a `float`: pasar por float
    es exactamente el error que este script existe para detectar.
    """
    if valor is None:
        return None
    if isinstance(valor, Decimal):
        return valor
    if isinstance(valor, int):
        return Decimal(valor)
    if isinstance(valor, float):
        # No debería ocurrir. Si ocurre, es un hallazgo, no un dato: se marca.
        raise TypeError(f"Un control devolvió un float ({valor}); el dinero debe "
                        f"ser Decimal en ambos motores (§4 del diseño).")
    return str(valor)


def ejecutar_control(control: Control, client) -> tuple[bool, list[str]]:
    """Corre el par de consultas y devuelve (cuadra, lista de diferencias)."""
    filas_pg = [tuple(_normalizar(v) for v in f) for f in _consultar_pg(control.sql_pg)]
    filas_ch = [tuple(_normalizar(v) for v in f)
                for f in client.query(control.sql_ch).result_rows]

    cols = control.columnas
    diferencias: list[str] = []

    if not control.clave:
        # Control escalar: una fila por motor.
        pg, ch = filas_pg[0], filas_ch[0]
        for i, nombre_col in enumerate(cols):
            if pg[i] != ch[i]:
                diferencias.append(
                    f"    {nombre_col:<24} PG={pg[i]!s:>18}  CH={ch[i]!s:>18}  "
                    f"Δ={ch[i] - pg[i] if isinstance(pg[i], Decimal) else '≠'}"
                )
        return not diferencias, diferencias

    # Control de serie: se indexa por la clave y se comparan los conjuntos.
    n_clave = len(control.clave)
    idx_pg = {f[:n_clave]: f[n_clave:] for f in filas_pg}
    idx_ch = {f[:n_clave]: f[n_clave:] for f in filas_ch}

    # La clave puede tener más de una columna (p. ej. mes + sentido en la serie
    # de caja): se muestra completa, o dos filas distintas se leerían como una.
    def _rotulo(clave: tuple) -> str:
        return " ".join(str(v) for v in clave)

    solo_pg = sorted(set(idx_pg) - set(idx_ch))
    solo_ch = sorted(set(idx_ch) - set(idx_pg))
    if solo_pg:
        diferencias.append(f"    Claves SOLO en PostgreSQL: {[_rotulo(k) for k in solo_pg]}")
    if solo_ch:
        diferencias.append(f"    Claves SOLO en ClickHouse: {[_rotulo(k) for k in solo_ch]}")

    for clave in sorted(set(idx_pg) & set(idx_ch)):
        pg, ch = idx_pg[clave], idx_ch[clave]
        if pg != ch:
            detalle = ", ".join(
                f"{cols[n_clave + i]}: PG={pg[i]} CH={ch[i]}"
                for i in range(len(pg)) if pg[i] != ch[i]
            )
            diferencias.append(f"    {_rotulo(clave)}  {detalle}")

    return not diferencias, diferencias


def _detalle_serie(control: Control, client) -> None:
    """Imprime la serie completa lado a lado (para `--detalle`)."""
    n = len(control.clave)
    filas_pg = {tuple(_normalizar(v) for v in f[:n]): f[n:]
                for f in _consultar_pg(control.sql_pg)}
    filas_ch = {tuple(_normalizar(v) for v in f[:n]): f[n:]
                for f in client.query(control.sql_ch).result_rows}
    cols = control.columnas[n:]

    encabezado = "  ".join(f"{c[:14]:>14}" for c in cols)
    rotulo = "+".join(control.clave)
    print(f"\n    {rotulo:<18} {'PostgreSQL':^48} | {'ClickHouse':^48}")
    print(f"    {'':<18} {encabezado:<48} | {encabezado:<48}")
    print("    " + "-" * 118)
    for clave in sorted(set(filas_pg) | set(filas_ch)):
        pg = filas_pg.get(clave, ("—",) * len(cols))
        ch = filas_ch.get(clave, ("—",) * len(cols))
        izq = "  ".join(f"{str(v):>14}" for v in pg)
        der = "  ".join(f"{str(v):>14}" for v in ch)
        marca = " " if tuple(map(str, pg)) == tuple(map(str, ch)) else "✗"
        print(f"  {marca} {' '.join(str(v) for v in clave):<18} {izq:<48} | {der:<48}")
    print()


# ═══════════════════════════════════════════════════════════════════════════

def validar(fase: int | None = None, solo: str | None = None,
            detalle: bool = False) -> int:
    seleccion = [c for c in CONTROLES
                 if (fase is None or c.fase == fase)
                 and (solo is None or c.nombre == solo or c.tabla == solo)]

    if not seleccion:
        print(f"[ERROR] Ningún control coincide (fase={fase}, control={solo}). "
              f"Usa --listar para ver los disponibles.")
        return 1

    print("\n" + "═" * 78)
    print(f"  VALIDACIÓN CRUZADA PostgreSQL ↔ ClickHouse ({CH_DATABASE})")
    print(f"  Criterio: igualdad EXACTA. Sin tolerancia de centavos.")
    print("═" * 78)

    client = get_ch_client()
    fallidos = 0
    try:
        for control in seleccion:
            print(f"\n[{control.fase}] {control.nombre} — {control.descripcion}")
            try:
                cuadra, diferencias = ejecutar_control(control, client)
            except Exception as e:
                print(f"  ✗ ERROR al ejecutar el control: {type(e).__name__}: {e}")
                fallidos += 1
                continue

            if cuadra:
                print(f"  ✓ CUADRA")
            else:
                print(f"  ✗ DIFIERE ({len(diferencias)} discrepancia(s)):")
                for d in diferencias:
                    print(d)
                fallidos += 1

            if detalle and control.clave:
                _detalle_serie(control, client)
    finally:
        client.close()

    print("\n" + "═" * 78)
    if fallidos:
        print(f"  RESULTADO: {fallidos} de {len(seleccion)} controles DIFIEREN.")
        print(f"  Causas probables: Float en vez de Decimal · zona horaria omitida ·")
        print(f"  fila perdida o duplicada en un JOIN. Ninguna es tolerancia aceptable.")
    else:
        print(f"  RESULTADO: los {len(seleccion)} controles cuadran EXACTAMENTE.")
    print("═" * 78 + "\n")
    return 1 if fallidos else 0


def listar() -> None:
    print(f"\nControles registrados ({len(CONTROLES)}):\n")
    for c in CONTROLES:
        tipo = "serie " if c.clave else "escalar"
        print(f"  fase {c.fase}  [{tipo}]  {c.nombre:<22} {c.descripcion}")
    print()


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        prog="python -m etl.dwh.validar_dwh",
        description="Valida el DWH publicado contra PostgreSQL. Igualdad exacta.",
    )
    parser.add_argument("--fase", type=int, help="validar solo los controles de una fase")
    parser.add_argument("--control", help="validar un control o una tabla concreta")
    parser.add_argument("--detalle", action="store_true",
                        help="imprime las series completas lado a lado")
    parser.add_argument("--listar", action="store_true", help="lista los controles")
    args = parser.parse_args(argv)

    if args.listar:
        listar()
        return 0
    return validar(fase=args.fase, solo=args.control, detalle=args.detalle)


if __name__ == "__main__":
    sys.exit(main())
