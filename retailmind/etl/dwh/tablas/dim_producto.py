"""
etl/dwh/tablas/dim_producto.py — D2 del modelo (§4.2 del diseño).

El producto en su grano real: la VARIANTE. 1.221 filas.

El grano es la variante y no el producto porque es la variante la que se vende,
la que se mueve en el kardex y la que se compra — y porque el id público del
catálogo del cliente ya es el de la variante.

Trae el universo COMPLETO de variantes, incluidas las que jamás se vendieron.
Eso no es exhaustividad gratuita: es el requisito de OTD-VEN-04 («productos que
NO se venden»), que se responde con un anti-join contra `fact_venta_linea`. Sin
las 1.221 filas no hay contra qué restar.

TRANSFORMACIÓN DE CATEGORÍA — verificada, no supuesta (2026-07-30):

    max(categorías por producto)           = 1
    productos con categoría principal      = 1.214 de 1.214
    filas del join completo                = 1.221 (= variantes, sin fan-out)

`producto_categoria` tiene un `es_principal` que sugiere que un producto puede
llevar varias categorías y hay que elegir una. En estos datos NO ocurre: la
denormalización es 1:1 y sin pérdida, no hay regla de desempate que inventar.
Se comprobó antes de escribir el SELECT precisamente porque el esquema invita a
suponer lo contrario, y porque un fan-out aquí multiplicaría filas en silencio.
"""

from datetime import datetime
from zoneinfo import ZoneInfo

from etl.dwh.conexiones import ZONA_HORARIA
from etl.dwh.tarea import TareaCarga


class DimProducto(TareaCarga):

    nombre = "dim_producto"

    def ddl(self, nombre_tabla: str) -> str:
        return f"""
        CREATE TABLE {nombre_tabla}
        (
            producto_variante_id UInt32,
            sku                  String,
            producto_id          UInt32,
            producto_nombre      String,
            slug                 String,
            marca                LowCardinality(String),
            categoria            LowCardinality(String),
            categoria_id         UInt16,
            precio               Decimal(14,2),
            costo                Decimal(14,2),
            margen_catalogo_pct  Decimal(6,2),
            peso_kg              Decimal(10,3),
            activo               UInt8,
            fecha_carga          DateTime('{ZONA_HORARIA}')
        )
        ENGINE = ReplacingMergeTree(fecha_carga)
        ORDER BY producto_variante_id
        """

    def columnas(self) -> list[str]:
        return [
            "producto_variante_id", "sku", "producto_id", "producto_nombre", "slug",
            "marca", "categoria", "categoria_id", "precio", "costo",
            "margen_catalogo_pct", "peso_kg", "activo", "fecha_carga",
        ]

    def sql_extraccion(self) -> str:
        """
        Nota sobre los centinelas: ninguna columna de la clave de ordenamiento
        admite NULL, y las de texto que podrían venir vacías resuelven a
        'sin_marca' / 'sin_categoria' en vez de a NULL. Hoy los 1.221 registros
        tienen marca y categoría, pero el centinela evita que un alta futura sin
        marca reviente la carga en vez de degradarse de forma legible.

        `margen_catalogo_pct` se calcula AQUÍ y no en la consulta del informe
        para que la definición del margen de catálogo viva en un solo sitio.
        La división está guardada con NULLIF: `precio` es hoy > 0 en las 1.221
        variantes (mín. $7,29), pero un precio 0 daría una división por cero que
        abortaría la corrida entera.
        """
        return """
            SELECT
                pv.id                                              AS producto_variante_id,
                pv.sku,
                p.id                                               AS producto_id,
                p.nombre                                           AS producto_nombre,
                p.slug,
                COALESCE(m.nombre, 'sin_marca')                    AS marca,
                COALESCE(c.nombre, 'sin_categoria')                AS categoria,
                COALESCE(c.id, 0)                                  AS categoria_id,
                pv.precio,
                pv.costo,
                ROUND(
                    ((pv.precio - pv.costo) / NULLIF(pv.precio, 0)) * 100, 2
                )                                                  AS margen_catalogo_pct,
                pv.peso_kg,
                CASE WHEN pv.activo THEN 1 ELSE 0 END              AS activo
            FROM producto_variante pv
            JOIN producto p            ON p.id = pv.producto_id
            LEFT JOIN marca m          ON m.id = p.marca_id
            LEFT JOIN producto_categoria pc
                                       ON pc.producto_id = p.id AND pc.es_principal
            LEFT JOIN categoria c      ON c.id = pc.categoria_id
            ORDER BY pv.id
        """

    def transformar(self, lote: list[tuple]) -> list[tuple]:
        """Añade `fecha_carga`: la única columna que no viene de PostgreSQL."""
        ahora = datetime.now(ZoneInfo(ZONA_HORARIA)).replace(microsecond=0)
        return [tuple(fila) + (ahora,) for fila in lote]

    def sql_controles(self) -> str:
        return """
            SELECT
                count(*)                                       AS filas,
                count(DISTINCT pv.sku)                         AS skus_distintos,
                count(DISTINCT p.id)                           AS productos,
                count(DISTINCT c.id)                           AS categorias,
                count(DISTINCT m.id)                           AS marcas,
                SUM(pv.precio)                                 AS suma_precio,
                SUM(pv.costo)                                  AS suma_costo,
                COUNT(*) FILTER (WHERE pv.costo IS NULL)       AS sin_costo,
                COUNT(*) FILTER (WHERE c.id IS NULL)           AS sin_categoria
            FROM producto_variante pv
            JOIN producto p            ON p.id = pv.producto_id
            LEFT JOIN marca m          ON m.id = p.marca_id
            LEFT JOIN producto_categoria pc
                                       ON pc.producto_id = p.id AND pc.es_principal
            LEFT JOIN categoria c      ON c.id = pc.categoria_id
        """

    def validar(self, client, tabla_staging: str, controles: dict) -> list[str]:
        """
        Más allá del conteo: el SKU debe seguir siendo único (si el join hubiera
        hecho fan-out, `count(*)` podría cuadrar por casualidad pero los SKU no),
        y las sumas de dinero deben ser EXACTAS al centavo.
        """
        errores = []
        fila = client.query(f"""
            SELECT count(), countDistinct(sku), countDistinct(producto_id),
                   countDistinct(categoria), countDistinct(marca),
                   sum(precio), sum(costo)
            FROM {tabla_staging}
        """).result_rows[0]
        total, skus, productos, categorias, marcas, suma_precio, suma_costo = fila

        if skus != total:
            errores.append(f"SKU duplicados: {total} filas pero {skus} SKU distintos "
                           f"(síntoma de fan-out en el join de categoría).")
        if productos != int(controles["productos"]):
            errores.append(f"Productos distintos: origen {controles['productos']} "
                           f"vs destino {productos}.")
        if categorias != int(controles["categorias"]):
            errores.append(f"Categorías distintas: origen {controles['categorias']} "
                           f"vs destino {categorias}.")
        if marcas != int(controles["marcas"]):
            errores.append(f"Marcas distintas: origen {controles['marcas']} "
                           f"vs destino {marcas}.")
        if suma_precio != controles["suma_precio"]:
            errores.append(f"Suma de precios: origen {controles['suma_precio']} "
                           f"vs destino {suma_precio}.")
        if suma_costo != controles["suma_costo"]:
            errores.append(f"Suma de costos: origen {controles['suma_costo']} "
                           f"vs destino {suma_costo}.")
        return errores
