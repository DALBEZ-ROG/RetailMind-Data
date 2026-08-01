"""
etl/dwh/tablas/dim_proveedor.py — D4 del modelo (§4.4 del diseño).

El proveedor como entidad. 11 filas. Se une a `fact_orden_compra` y a
`fact_compra_linea` por `proveedor_id`, y a `fact_flujo_caja` por
`contraparte_id` cuando `contraparte_tipo = 'proveedor'`.

═══════════════════════════════════════════════════════════════════════════════
`dias_credito` NO ES ADORNO
═══════════════════════════════════════════════════════════════════════════════

Es la referencia contra la cual OTD-COM-03 juzga la puntualidad de pago, y en
estos datos la relación es EXACTA — comprobado sobre las 839 cuentas por pagar:

    cuenta_por_pagar.fecha_vencimiento
        = factura_compra.fecha_emision + proveedor.dias_credito     839 de 839
    cuentas cuyo vencimiento NO se deriva del crédito pactado ......   0
    facturas cuyo vencimiento difiere del de su CxP ................   0

Es decir: el vencimiento no es un dato suelto que alguien tecleó, sino el plazo
del proveedor aplicado a la fecha de la factura. Por eso la columna vive en la
dimensión y no repetida en cada hecho: `fact_flujo_caja` ya trae el desvío
calculado (`dias_desvio_vencimiento`), y quien quiera saber **contra qué plazo**
se midió lo encuentra aquí, en un solo sitio.

Los plazos reales van de 15 a 60 días: 15 (2 proveedores), 30 (6), 45 (2) y
60 (1).

═══════════════════════════════════════════════════════════════════════════════
DOS PROVEEDORES SIN CIUDAD — corrección C3.5 (2026-07-30)
═══════════════════════════════════════════════════════════════════════════════

§4.4 lista `ciudad` sin prever su ausencia. `proveedor.ciudad_id` es nullable y
2 de los 11 lo tienen en NULL: los proveedores 1 (`GlobalSport`) y 2
(`DeportAndina`), legacy anteriores al seed, que entre ambos suman 48 órdenes de
compra — no son residuos sin uso.

Resuelven al centinela `'sin_ciudad'`, mismo criterio que `'sin_direccion'` en
`dim_cliente` y `'sin_marca'` en `dim_producto`: la columna es `LowCardinality`
y se agrupa en los informes, y un NULL dentro de un `GROUP BY` se lee como una
ciudad más. Registrado en `docs/estrategico/CORRECCIONES_DISENO_ETL.md` (C3.5).

Los 11 proveedores están ACTIVOS y los 865 pedidos de compra se reparten entre
ellos sin una sola orden huérfana (verificado: 29+19+108+78+104+88+112+64+86+
102+75 = 865). Los ids NO son contiguos (1, 2, 12…20), así que nada del modelo
puede suponer un rango.
"""

from datetime import datetime
from zoneinfo import ZoneInfo

from etl.dwh.conexiones import ZONA_HORARIA
from etl.dwh.tarea import TareaCarga

#: Centinela de ciudad ausente. Constante y no literal suelto: si mañana se
#: completan las dos fichas legacy, este es el único sitio que lo sabe.
SIN_CIUDAD = "sin_ciudad"


class DimProveedor(TareaCarga):

    nombre = "dim_proveedor"

    def ddl(self, nombre_tabla: str) -> str:
        return f"""
        CREATE TABLE {nombre_tabla}
        (
            proveedor_id     UInt16,
            razon_social     String,
            nombre_comercial String,
            ruc              String,
            ciudad           LowCardinality(String),
            dias_credito     UInt16,
            activo           UInt8,
            fecha_carga      DateTime('{ZONA_HORARIA}')
        )
        ENGINE = ReplacingMergeTree(fecha_carga)
        ORDER BY proveedor_id
        """

    def columnas(self) -> list[str]:
        return [
            "proveedor_id", "razon_social", "nombre_comercial", "ruc",
            "ciudad", "dias_credito", "activo", "fecha_carga",
        ]

    # ── Extracción ───────────────────────────────────────────────────────────

    def sql_extraccion(self) -> str:
        """
        `nombre_comercial` también es nullable en el esquema (hoy poblado en los
        11) y cae a la razón social en vez de a cadena vacía: es el rótulo corto
        que los informes muestran, y una etiqueta vacía en un eje de gráfico es
        peor que una larga.
        """
        return f"""
        SELECT
            pv.id                                      AS proveedor_id,
            pv.razon_social,
            COALESCE(NULLIF(TRIM(pv.nombre_comercial), ''),
                     pv.razon_social)                  AS nombre_comercial,
            pv.ruc,
            COALESCE(ci.nombre, '{SIN_CIUDAD}')        AS ciudad,
            pv.dias_credito,
            CASE WHEN pv.activo THEN 1 ELSE 0 END      AS activo
        FROM proveedor pv
        LEFT JOIN ciudad ci ON ci.id = pv.ciudad_id
        ORDER BY pv.id
        """

    def transformar(self, lote: list[tuple]) -> list[tuple]:
        ahora = datetime.now(ZoneInfo(ZONA_HORARIA)).replace(microsecond=0)
        return [tuple(fila) + (ahora,) for fila in lote]

    # ── Controles ────────────────────────────────────────────────────────────

    def sql_controles(self) -> str:
        """
        `rucs` vigila el fan-out igual que `emails` en `dim_cliente`: 11 filas
        con 10 RUC distintos sería una fila duplicada por el join de ciudad, y
        `count(*)` no distingue eso de un alta nueva.

        `suma_dias_credito` es la cifra que ata esta dimensión con OTD-COM-03:
        si el plazo de un proveedor cambia, la referencia contra la que se juzga
        la puntualidad cambia con él, y conviene que se note en la validación.
        """
        return """
        SELECT
            count(*)                                          AS filas,
            count(DISTINCT pv.ruc)                            AS rucs,
            count(*) FILTER (WHERE pv.activo)                 AS activos,
            count(*) FILTER (WHERE pv.ciudad_id IS NULL)      AS sin_ciudad,
            count(DISTINCT ci.nombre)                         AS ciudades,
            SUM(pv.dias_credito)                              AS suma_dias_credito,
            count(DISTINCT pv.dias_credito)                   AS plazos_distintos,
            (SELECT count(DISTINCT proveedor_id) FROM orden_compra) AS proveedores_con_oc,
            (SELECT count(*) FROM cuenta_por_pagar cpp
             JOIN factura_compra fc ON fc.id = cpp.factura_compra_id
             JOIN proveedor p       ON p.id  = cpp.proveedor_id
             WHERE cpp.fecha_vencimiento <> fc.fecha_emision + p.dias_credito)
                                                              AS venc_fuera_de_credito
        FROM proveedor pv
        LEFT JOIN ciudad ci ON ci.id = pv.ciudad_id
        """

    def validar(self, client, tabla_staging: str, controles: dict) -> list[str]:
        errores = []
        fila = client.query(f"""
            SELECT count(), countDistinct(ruc), countDistinct(proveedor_id),
                   countIf(activo = 1), countIf(ciudad = '{SIN_CIUDAD}'),
                   sum(dias_credito), countDistinct(dias_credito)
            FROM {tabla_staging}
        """).result_rows[0]
        (total, rucs, ids, activos, sin_ciudad, suma_credito, plazos) = fila

        if ids != total:
            errores.append(f"proveedor_id duplicado: {total} filas pero {ids} ids "
                           f"distintos (fan-out del join de ciudad).")
        if rucs != int(controles["rucs"]):
            errores.append(f"RUC distintos: origen {controles['rucs']} vs destino {rucs}.")
        if activos != int(controles["activos"]):
            errores.append(f"Proveedores activos: origen {controles['activos']} "
                           f"vs destino {activos}.")
        if sin_ciudad != int(controles["sin_ciudad"]):
            errores.append(f"Proveedores sin ciudad: origen {controles['sin_ciudad']} "
                           f"vs destino {sin_ciudad} en '{SIN_CIUDAD}'.")
        if int(suma_credito) != int(controles["suma_dias_credito"]):
            errores.append(f"Suma de días de crédito: origen "
                           f"{controles['suma_dias_credito']} vs destino {suma_credito}.")
        if plazos != int(controles["plazos_distintos"]):
            errores.append(f"Plazos de crédito distintos: origen "
                           f"{controles['plazos_distintos']} vs destino {plazos}.")

        # La dimensión debe cubrir a TODO proveedor que aparezca en una compra.
        # Si alguna vez hubiera un proveedor con órdenes y sin ficha, los hechos
        # se quedarían sin nombre y el informe agruparía por un id desnudo.
        if int(controles["proveedores_con_oc"]) > total:
            errores.append(
                f"{controles['proveedores_con_oc']} proveedores tienen órdenes de "
                f"compra pero la dimensión solo trae {total}: hay compras a un "
                f"proveedor sin ficha."
            )
        # Invariante declarado de §4.4: el vencimiento SE DERIVA del crédito
        # pactado. Si deja de cumplirse, `dias_credito` deja de ser la referencia
        # de OTD-COM-03 y el informe mide contra un plazo que ya no rige.
        if int(controles["venc_fuera_de_credito"]) != 0:
            errores.append(
                f"{controles['venc_fuera_de_credito']} cuentas por pagar tienen un "
                f"vencimiento que NO es `fecha_emision + dias_credito`. §4.4 declara "
                f"`dias_credito` como la referencia de la puntualidad (OTD-COM-03); "
                f"si el vencimiento se fija a mano, hay que traerlo del hecho y no "
                f"de la dimensión."
            )
        return errores
