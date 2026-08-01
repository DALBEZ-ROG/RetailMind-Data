"""
etl/dwh/tablas/dim_promocion_producto.py — D5 del modelo (§4.5 del diseño).

El puente promoción ↔ producto CON VENTANA TEMPORAL. Grano: una pareja
(promoción, producto). **232 filas** sobre 24 promociones y 182 productos.

Su única razón de ser es OTD-GER-07: sin esta tabla, la pregunta «¿esta línea de
venta cayó dentro de la ventana de una promoción que cubría este producto?» no
tiene respuesta en el almacén. `fact_venta_linea` sabe si una línea llevó
descuento de promoción (`tuvo_promocion`), pero no CUÁL ni CUÁNDO empezaba y
acababa, que es justo lo que hace falta para comparar el «antes» con el
«durante».

═══════════════════════════════════════════════════════════════════════════════
1. EL GRANO ES EL PRODUCTO PADRE, Y `dim_producto` ESTÁ POR VARIANTE
═══════════════════════════════════════════════════════════════════════════════

`promocion_producto.producto_id` apunta al PRODUCTO, mientras que
`dim_producto` —y con ella `fact_venta_linea`— tienen grano de VARIANTE:

    productos ..................... 1.214
    variantes ..................... 1.221
    productos con >1 variante .....     7   (máx. 3)

Es decir: una promoción sobre un producto cubre TODAS sus variantes. Esa es la
dirección correcta del cruce y no un problema — el problema aparece en el
sentido contrario, y está documentado en `fact_resena` (C4.4).

Consecuencia práctica para GER-07: el enlace es
`fact_venta_linea ⋈ dim_producto` (por variante, 1:1) `→ producto_id ⋈` esta
tabla. Para que ese camino no obligue a un tercer JOIN solo para mostrar un
rótulo, la tabla trae `producto_nombre` y `categoria` denormalizados.

═══════════════════════════════════════════════════════════════════════════════
2. `fecha_fin` VIAJA NULLABLE AUNQUE HOY NO HAYA NINGUNA ABIERTA
═══════════════════════════════════════════════════════════════════════════════

§4.5 declara `fecha_inicio / fecha_fin` como `DateTime`, sin admitir ausencia.
El esquema SÍ la admite (`promocion.fecha_fin` es nullable) y hoy están las 24
pobladas. Se carga `Nullable` de todos modos: una promoción sin fecha de fin es
una promoción ABIERTA, y rellenarla con un centinela —el fin de los tiempos, o
peor, la fecha de carga— convertiría «sigue vigente» en «terminó», que es
exactamente el tipo de dato falso que esta bitácora persigue. El control aborta
si aparece una, para que la decisión se tome mirándola y no por defecto.

Distribución real: 22 promociones por `porcentaje` y 2 por `monto_fijo`; las 24
están activas.

Bitácora completa: `docs/estrategico/CORRECCIONES_DISENO_ETL.md`.
"""

from datetime import datetime
from zoneinfo import ZoneInfo

from etl.dwh.conexiones import ZONA_HORARIA
from etl.dwh.tarea import TareaCarga

#: Centinela de categoría ausente, mismo criterio que `sin_marca` en
#: `dim_producto` y `sin_ciudad` en `dim_proveedor`.
SIN_CATEGORIA = "sin_categoria"


class DimPromocionProducto(TareaCarga):

    nombre = "dim_promocion_producto"

    def ddl(self, nombre_tabla: str) -> str:
        return f"""
        CREATE TABLE {nombre_tabla}
        (
            promocion_id     UInt32,
            promocion_nombre String,
            producto_id      UInt32,
            producto_nombre  String,
            categoria        LowCardinality(String),
            fecha_inicio     DateTime('{ZONA_HORARIA}'),
            fecha_fin        Nullable(DateTime('{ZONA_HORARIA}')),
            tipo_descuento   LowCardinality(String),
            valor            Decimal(14,2),
            prioridad        UInt8,
            acumulable       UInt8,
            activo           UInt8,
            fecha_carga      DateTime('{ZONA_HORARIA}')
        )
        ENGINE = ReplacingMergeTree(fecha_carga)
        ORDER BY (producto_id, fecha_inicio)
        """

    def columnas(self) -> list[str]:
        return [
            "promocion_id", "promocion_nombre", "producto_id", "producto_nombre",
            "categoria", "fecha_inicio", "fecha_fin", "tipo_descuento", "valor",
            "prioridad", "acumulable", "activo", "fecha_carga",
        ]

    # ── Extracción ───────────────────────────────────────────────────────────

    def sql_extraccion(self) -> str:
        """
        La categoría se resuelve con EXACTAMENTE el mismo JOIN que
        `dim_producto` —`producto_categoria` filtrada por `es_principal`— y no
        con una variante «equivalente». Si las dos tablas resolvieran la
        categoría de formas distintas, GER-07 podría agrupar un producto bajo
        una categoría en el puente y bajo otra en la línea de venta, y la
        comparación antes/durante se partiría en dos filas sin que nada fallara.
        """
        return f"""
        SELECT
            pp.promocion_id,
            pr.nombre                                     AS promocion_nombre,
            pp.producto_id,
            p.nombre                                      AS producto_nombre,
            COALESCE(c.nombre, '{SIN_CATEGORIA}')         AS categoria,
            pr.fecha_inicio,
            pr.fecha_fin,
            pr.tipo_descuento,
            pr.valor,
            pr.prioridad,
            CASE WHEN pr.acumulable THEN 1 ELSE 0 END     AS acumulable,
            CASE WHEN pr.activo     THEN 1 ELSE 0 END     AS activo
        FROM promocion_producto pp
        JOIN promocion pr ON pr.id = pp.promocion_id
        JOIN producto  p  ON p.id  = pp.producto_id
        LEFT JOIN producto_categoria pc
                          ON pc.producto_id = p.id AND pc.es_principal
        LEFT JOIN categoria c ON c.id = pc.categoria_id
        ORDER BY pp.producto_id, pr.fecha_inicio, pp.promocion_id
        """

    def transformar(self, lote: list[tuple]) -> list[tuple]:
        ahora = datetime.now(ZoneInfo(ZONA_HORARIA)).replace(microsecond=0)
        return [tuple(fila) + (ahora,) for fila in lote]

    # ── Controles ────────────────────────────────────────────────────────────

    def sql_controles(self) -> str:
        return """
        SELECT
            (SELECT count(*) FROM promocion_producto)                  AS filas,
            (SELECT count(DISTINCT promocion_id) FROM promocion_producto) AS promociones,
            (SELECT count(DISTINCT producto_id)  FROM promocion_producto) AS productos,
            (SELECT count(*) FROM promocion)                           AS promociones_catalogo,
            (SELECT count(*) FROM promocion WHERE activo)              AS promociones_activas,
            (SELECT count(*) FROM promocion WHERE fecha_fin IS NULL)   AS sin_fecha_fin,
            (SELECT count(*) FROM promocion WHERE fecha_fin < fecha_inicio) AS ventana_invertida,
            (SELECT ROUND(SUM(pr.valor), 2)
               FROM promocion_producto pp JOIN promocion pr ON pr.id = pp.promocion_id)
                                                                       AS suma_valor,
            (SELECT count(DISTINCT tipo_descuento) FROM promocion)     AS tipos_descuento,
            -- Huérfanos en las dos direcciones: un par que apunte a una
            -- promoción o a un producto inexistente desaparecería en el JOIN
            -- sin dar error y GER-07 mediría sobre menos productos.
            (SELECT count(*) FROM promocion_producto pp
              WHERE NOT EXISTS (SELECT 1 FROM promocion pr WHERE pr.id = pp.promocion_id))
                                                                       AS pares_sin_promocion,
            (SELECT count(*) FROM promocion_producto pp
              WHERE NOT EXISTS (SELECT 1 FROM producto p WHERE p.id = pp.producto_id))
                                                                       AS pares_sin_producto,
            -- El puente es INÚTIL si sus productos no llegan al almacén: se
            -- cruzan por VARIANTE (dim_producto), así que un producto sin
            -- ninguna variante dejaría una promoción sin ventas que medir.
            (SELECT count(*) FROM promocion_producto pp
              WHERE NOT EXISTS (SELECT 1 FROM producto_variante v
                                 WHERE v.producto_id = pp.producto_id))
                                                                       AS pares_sin_variante,
            -- Un par duplicado multiplicaría las ventas del producto en GER-07.
            (SELECT count(*) FROM (SELECT promocion_id, producto_id
                                     FROM promocion_producto
                                    GROUP BY 1, 2 HAVING count(*) > 1) d)
                                                                       AS pares_duplicados
        """

    def validar(self, client, tabla_staging: str, controles: dict) -> list[str]:
        errores = []

        for clave, explicacion in (
            ("pares_sin_promocion", "apuntan a una promoción inexistente"),
            ("pares_sin_producto",  "apuntan a un producto inexistente"),
            ("pares_duplicados",    "están repetidos (misma promoción y producto)"),
            ("ventana_invertida",   "tienen la fecha de fin ANTES de la de inicio"),
        ):
            if int(controles[clave]) != 0:
                errores.append(f"{controles[clave]} pares {explicacion}.")

        if int(controles["pares_sin_variante"]) != 0:
            errores.append(
                f"{controles['pares_sin_variante']} pares cubren un producto SIN "
                f"variantes. El cruce con la venta va por variante "
                f"(fact_venta_linea → dim_producto → producto_id), así que esas "
                f"promociones no tendrían ventas que comparar y GER-07 las contaría "
                f"como «sin efecto» en vez de «sin dato»."
            )

        fila = client.query(f"""
            SELECT count(),
                   countDistinct(promocion_id),
                   countDistinct(producto_id),
                   countDistinct((promocion_id, producto_id)),
                   countIf(fecha_fin IS NULL),
                   sum(valor),
                   countDistinct(tipo_descuento),
                   countIf(activo = 1),
                   countIf(fecha_fin IS NOT NULL AND fecha_fin < fecha_inicio)
            FROM {tabla_staging}
        """).result_rows[0]
        (total, promos, productos, pares, sin_fin, suma_valor, tipos,
         activos, invertidas) = fila

        if pares != total:
            errores.append(f"Par (promoción, producto) duplicado: {total} filas pero "
                           f"{pares} pares distintos.")
        if promos != int(controles["promociones"]):
            errores.append(f"Promociones: origen {controles['promociones']} vs "
                           f"destino {promos}.")
        if productos != int(controles["productos"]):
            errores.append(f"Productos: origen {controles['productos']} vs "
                           f"destino {productos}.")
        if int(suma_valor) != int(controles["suma_valor"]) or \
                round(float(suma_valor), 2) != round(float(controles["suma_valor"]), 2):
            errores.append(f"Suma del valor del descuento: origen "
                           f"{controles['suma_valor']} vs destino {suma_valor}.")
        if tipos != int(controles["tipos_descuento"]):
            errores.append(f"Tipos de descuento: origen {controles['tipos_descuento']} "
                           f"vs destino {tipos}.")
        if invertidas:
            errores.append(f"{invertidas} pares con la ventana invertida en el destino.")

        # La ventana ABIERTA no es un error, pero sí una decisión que hay que
        # tomar mirándola: hoy son 0 y GER-07 acota siempre por [inicio, fin].
        if sin_fin != int(controles["sin_fecha_fin"]):
            errores.append(f"Promociones sin fecha de fin: origen "
                           f"{controles['sin_fecha_fin']} vs destino {sin_fin}.")
        if sin_fin:
            errores.append(
                f"{sin_fin} pares pertenecen a una promoción SIN fecha de fin. "
                f"§4.5 no la contempla y GER-07 acota la ventana por los dos "
                f"extremos: una promoción abierta quedaría fuera de la comparación "
                f"«durante» sin que el informe lo dijera. Decide qué significa "
                f"antes de publicar."
            )
        # Hoy las 24 promociones del catálogo están activas: si TODAS lo están,
        # TODOS los pares deben salir con activo = 1. Es un control barato que
        # atrapa un CASE mal escrito sin depender de una constante.
        if int(controles["promociones_activas"]) == int(controles["promociones_catalogo"]) \
                and activos != total:
            errores.append(f"Las {controles['promociones_activas']} promociones del "
                           f"catálogo están activas, pero solo {activos} de {total} "
                           f"pares llegaron con activo = 1.")
        return errores
