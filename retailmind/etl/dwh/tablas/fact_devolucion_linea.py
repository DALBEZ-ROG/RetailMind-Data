"""
etl/dwh/tablas/fact_devolucion_linea.py — F11 del modelo (§5.11 del diseño).

Qué producto volvió y en qué estado. Grano: **una línea de devolución**.
274 filas, 162 con inspección registrada. Alimenta OTD-LOG-08 y el lado
«devoluciones» de OTD-SOP-08.

═══════════════════════════════════════════════════════════════════════════════
1. `reingresa_stock` SE PRECALCULA PORQUE ES LA REGLA, NO UN FILTRO
═══════════════════════════════════════════════════════════════════════════════

La regla del RMA: **solo lo `apto_reventa` vuelve al stock vendible**, con kardex
`entrada_devolucion_cliente`. Lo `defectuoso` va al pool de devolución al
proveedor (sin reingreso: nunca vuelve a estar disponible) y lo `rechazado` no
genera ni reembolso ni stock.

    apto_reventa ......  119 líneas / 188 uds   ← las únicas que reingresan
    defectuoso .......    37 líneas /  58 uds
    rechazado ........     6 líneas /   7 uds
    (sin inspeccionar)   112 líneas / 171 uds
                        ─────────────────────
                         274 líneas / 424 uds

Se calcula en el ETL y no en la consulta por el mismo motivo que `es_ajuste_real`
en la Fase 3B (C3B.1): la pregunta de LOG-08 es literalmente «¿qué pasa con esa
mercancía?», y dejar la regla al alcance de quien escriba el `WHERE` es dejarla
al alcance de un descuido. Aquí el descuido sería contar como reingresado todo lo
que no fue rechazado — 156 líneas donde son 119.

═══════════════════════════════════════════════════════════════════════════════
2. «SIN INSPECCIONAR» NO ES UN HUECO: SON 112 DE 274
═══════════════════════════════════════════════════════════════════════════════

`resultado_inspeccion` es nullable por diseño del ciclo —solo BODEGA lo escribe,
y solo cuando la mercancía llega— así que un NULL significa «esta devolución
todavía no ha llegado a inspección», no «se perdió el dato». Es el 41 % de las
líneas y corresponde a las devoluciones que aún no pasaron de `recibida`.

Resuelven al centinela `'sin_inspeccionar'`, que el diseño ya preveía. Sin él,
un `GROUP BY resultado_inspeccion` en ClickHouse las agruparía bajo una cadena
vacía indistinguible de un fallo del JOIN.

═══════════════════════════════════════════════════════════════════════════════
3. EL MONTO DE LA LÍNEA CUADRA CON EL TOTAL DE LA CABECERA — AL CENTAVO
═══════════════════════════════════════════════════════════════════════════════

`devolucion_detalle` no guarda importe: lo que volvió se valora al precio al que
se vendió, `cantidad × pedido_detalle.precio_unitario`. Verificado contra el
total que mantiene el trigger `fn_recalcular_total_devolucion`:

    Σ (cantidad × precio_unitario) de las 274 líneas ..... $95.693,89
    Σ devolucion.monto_total de las 196 cabeceras ........ $95.693,89
                                            diferencia     $     0,00

Es decir: la fórmula del ETL es la MISMA que la del trigger. El control lo
comprueba en cada carga, porque si un día dejan de coincidir, VEN-14 (que suma
cabeceras) y LOG-08 (que suma líneas) darían cifras distintas del mismo dinero.

**El descuento NO se prorratea aquí**, a diferencia de `fact_venta_linea`: el
sistema devuelve al cliente lo que pagó por la unidad, y `monto_total` —que es
la referencia del reembolso— se calcula sobre el precio unitario sin tocar. Si
algún día el reembolso pasara a descontar la promoción, esta fórmula y la del
trigger tendrían que cambiar a la vez.

Se verificó además que las 274 líneas pertenecen al MISMO pedido que su
devolución (0 líneas cruzadas) y que ninguna apunta a un `pedido_detalle`
inexistente.

Bitácora completa: `docs/estrategico/CORRECCIONES_DISENO_ETL.md`.
"""

from datetime import datetime
from zoneinfo import ZoneInfo

from etl.dwh.conexiones import ZONA_HORARIA, logger
from etl.dwh.tarea import TareaCarga

#: Los 3 resultados del CHECK de `devolucion_detalle`, todos en uso.
RESULTADOS_CONOCIDOS = frozenset({"apto_reventa", "defectuoso", "rechazado"})

#: El ÚNICO resultado que devuelve la mercancía al stock vendible.
RESULTADO_REINGRESA = "apto_reventa"

#: La línea todavía no pasó por inspección. No es un dato perdido.
SIN_INSPECCION = "sin_inspeccionar"

#: Estado en que el cliente declara haber devuelto el producto (CHECK: nuevo /
#: abierto / danado). Es lo que DICE el cliente; `resultado_inspeccion` es lo
#: que encuentra bodega, y no tienen por qué coincidir.
ESTADOS_PRODUCTO = frozenset({"nuevo", "abierto", "danado"})
SIN_ESTADO_PRODUCTO = "sin_declarar"


class FactDevolucionLinea(TareaCarga):

    nombre = "fact_devolucion_linea"

    #: Comparte con la cabecera la definición de qué es una devolución. No hay
    #: dependencia de DATOS —esta tabla se extrae sola— pero sí de orden: si la
    #: cabecera aborta, cargar sus líneas publica un detalle sin su total.
    depende_de = ("fact_devolucion",)

    def __init__(self):
        super().__init__()
        self.valores_nuevos: dict[str, int] = {}

    # ── Definición ───────────────────────────────────────────────────────────

    def ddl(self, nombre_tabla: str) -> str:
        return f"""
        CREATE TABLE {nombre_tabla}
        (
            devolucion_detalle_id UInt64,
            devolucion_id         UInt32,
            numero_devolucion     String,
            pedido_id             UInt32,
            cliente_id            UInt32,
            fecha_solicitud       DateTime('{ZONA_HORARIA}'),
            mes                   Date,
            estado_devolucion     LowCardinality(String),
            producto_variante_id  UInt32,
            sku                   String,
            producto_id           UInt32,
            producto_nombre       String,
            categoria             LowCardinality(String),
            marca                 LowCardinality(String),
            cantidad              UInt32,
            precio_unitario       Decimal(14,2),
            monto_linea           Decimal(14,2),
            motivo                LowCardinality(String),
            estado_producto       LowCardinality(String),
            accion                LowCardinality(String),
            resultado_inspeccion  LowCardinality(String),
            inspeccionada         UInt8,
            reingresa_stock       UInt8,
            unidades_reingresadas UInt32,
            fecha_carga           DateTime('{ZONA_HORARIA}')
        )
        ENGINE = MergeTree
        PARTITION BY toYYYYMM(fecha_solicitud)
        ORDER BY (fecha_solicitud, categoria, producto_variante_id)
        """

    def columnas(self) -> list[str]:
        return [
            "devolucion_detalle_id", "devolucion_id", "numero_devolucion",
            "pedido_id", "cliente_id", "fecha_solicitud", "mes", "estado_devolucion",
            "producto_variante_id", "sku", "producto_id", "producto_nombre",
            "categoria", "marca", "cantidad", "precio_unitario", "monto_linea",
            "motivo", "estado_producto", "accion", "resultado_inspeccion",
            "inspeccionada", "reingresa_stock", "unidades_reingresadas", "fecha_carga",
        ]

    # ── Extracción ───────────────────────────────────────────────────────────

    def sql_extraccion(self) -> str:
        """
        El producto se DENORMALIZA aquí (nombre, categoría, marca) en vez de
        dejarse para un JOIN con `dim_producto` en el informe. Aquí el grano es
        la VARIANTE, así que el JOIN sería 1:1 y no habría fan-out — pero la
        tabla se lee siempre junto a `fact_resena`, cuyo grano es el producto
        PADRE y donde el mismo JOIN sí multiplica (C4.4). Denormalizar las dos
        de la misma manera deja una sola forma de escribir SOP-08.

        La categoría se resuelve con el MISMO JOIN que `dim_producto`
        (`es_principal`), para que las dos tablas nunca clasifiquen distinto el
        mismo producto.
        """
        return f"""
        SELECT
            dd.id                                       AS devolucion_detalle_id,
            dd.devolucion_id,
            d.numero                                    AS numero_devolucion,
            d.pedido_id,
            d.cliente_id,
            d.fecha_creacion                            AS fecha_solicitud,
            (date_trunc('month',
                d.fecha_creacion AT TIME ZONE '{ZONA_HORARIA}'))::date AS mes,
            d.estado                                    AS estado_devolucion,
            pd.producto_variante_id,
            pv.sku,
            p.id                                        AS producto_id,
            p.nombre                                    AS producto_nombre,
            COALESCE(c.nombre, 'sin_categoria')         AS categoria,
            COALESCE(m.nombre, 'sin_marca')             AS marca,
            dd.cantidad,
            pd.precio_unitario,
            -- MISMA fórmula que el trigger `fn_recalcular_total_devolucion`.
            ROUND(dd.cantidad * pd.precio_unitario, 2)  AS monto_linea,
            md.nombre                                   AS motivo,
            COALESCE(dd.estado_producto, '{SIN_ESTADO_PRODUCTO}') AS estado_producto,
            dd.accion,
            COALESCE(dd.resultado_inspeccion, '{SIN_INSPECCION}') AS resultado_inspeccion,
            CASE WHEN dd.resultado_inspeccion IS NOT NULL THEN 1 ELSE 0 END
                                                        AS inspeccionada,
            -- LA REGLA DE NEGOCIO, precalculada (§1 del docstring).
            CASE WHEN dd.resultado_inspeccion = '{RESULTADO_REINGRESA}'
                 THEN 1 ELSE 0 END                      AS reingresa_stock,
            CASE WHEN dd.resultado_inspeccion = '{RESULTADO_REINGRESA}'
                 THEN dd.cantidad ELSE 0 END            AS unidades_reingresadas
        FROM devolucion_detalle dd
        JOIN devolucion d            ON d.id  = dd.devolucion_id
        JOIN motivo_devolucion md    ON md.id = d.motivo_devolucion_id
        JOIN pedido_detalle pd       ON pd.id = dd.pedido_detalle_id
        JOIN producto_variante pv    ON pv.id = pd.producto_variante_id
        JOIN producto p              ON p.id  = pv.producto_id
        LEFT JOIN marca m            ON m.id  = p.marca_id
        LEFT JOIN producto_categoria pc
                                     ON pc.producto_id = p.id AND pc.es_principal
        LEFT JOIN categoria c        ON c.id  = pc.categoria_id
        ORDER BY d.fecha_creacion, dd.devolucion_id, dd.id
        """

    # ── Transformación: lista blanca del resultado de inspección ─────────────

    def transformar(self, lote: list[tuple]) -> list[tuple]:
        ahora = datetime.now(ZoneInfo(ZONA_HORARIA)).replace(microsecond=0)
        i_res = self.columnas().index("resultado_inspeccion")
        i_est = self.columnas().index("estado_producto")

        salida = []
        for fila in lote:
            self._vigilar(fila[i_res], RESULTADOS_CONOCIDOS | {SIN_INSPECCION},
                          "resultado_inspeccion")
            self._vigilar(fila[i_est], ESTADOS_PRODUCTO | {SIN_ESTADO_PRODUCTO},
                          "estado_producto")
            salida.append(tuple(fila) + (ahora,))

        self.excepciones = sum(self.valores_nuevos.values())
        return salida

    def _vigilar(self, valor: str, conocidos: frozenset, campo: str) -> None:
        if valor in conocidos:
            return
        clave = f"{campo}={valor}"
        self.valores_nuevos[clave] = self.valores_nuevos.get(clave, 0) + 1
        if self.valores_nuevos[clave] == 1:
            logger.warning(
                f"[{self.nombre}] valor NO previsto en `{campo}`: «{valor}». Se carga "
                f"tal cual. OJO si es de `resultado_inspeccion`: `reingresa_stock` solo "
                f"marca '{RESULTADO_REINGRESA}', así que un resultado nuevo que también "
                f"devolviera mercancía al stock quedaría fuera de OTD-LOG-08 sin avisar."
            )

    # ── Controles ────────────────────────────────────────────────────────────

    def sql_controles(self) -> str:
        return f"""
        SELECT
            (SELECT count(*) FROM devolucion_detalle)                   AS filas,
            (SELECT count(DISTINCT devolucion_id) FROM devolucion_detalle) AS devoluciones,
            (SELECT SUM(cantidad) FROM devolucion_detalle)              AS unidades,
            (SELECT count(*) FROM devolucion_detalle
              WHERE resultado_inspeccion IS NOT NULL)                   AS inspeccionadas,
            (SELECT count(*) FROM devolucion_detalle
              WHERE resultado_inspeccion = '{RESULTADO_REINGRESA}')     AS aptas,
            (SELECT COALESCE(SUM(cantidad), 0) FROM devolucion_detalle
              WHERE resultado_inspeccion = '{RESULTADO_REINGRESA}')     AS uds_aptas,
            (SELECT count(*) FROM devolucion_detalle
              WHERE resultado_inspeccion = 'defectuoso')                AS defectuosas,
            (SELECT count(*) FROM devolucion_detalle
              WHERE resultado_inspeccion = 'rechazado')                 AS rechazadas,
            (SELECT count(DISTINCT resultado_inspeccion) FROM devolucion_detalle
              WHERE resultado_inspeccion IS NOT NULL)                   AS resultados,
            (SELECT count(DISTINCT accion) FROM devolucion_detalle)     AS acciones,
            (SELECT count(DISTINCT estado_producto) FROM devolucion_detalle
              WHERE estado_producto IS NOT NULL)                        AS estados_producto,
            (SELECT count(DISTINCT pd.producto_variante_id)
               FROM devolucion_detalle dd
               JOIN pedido_detalle pd ON pd.id = dd.pedido_detalle_id)  AS variantes,
            (SELECT ROUND(SUM(dd.cantidad * pd.precio_unitario), 2)
               FROM devolucion_detalle dd
               JOIN pedido_detalle pd ON pd.id = dd.pedido_detalle_id)  AS suma_monto,
            (SELECT ROUND(SUM(monto_total), 2) FROM devolucion)         AS suma_cabeceras,
            (SELECT count(DISTINCT date_trunc('month',
                 d.fecha_creacion AT TIME ZONE '{ZONA_HORARIA}'))
               FROM devolucion_detalle dd JOIN devolucion d ON d.id = dd.devolucion_id)
                                                                        AS meses,
            -- ── Integridad ────────────────────────────────────────────────────
            (SELECT count(*) FROM devolucion_detalle dd
              WHERE NOT EXISTS (SELECT 1 FROM pedido_detalle pd
                                 WHERE pd.id = dd.pedido_detalle_id))   AS sin_pedido_detalle,
            (SELECT count(*) FROM devolucion_detalle dd
              WHERE NOT EXISTS (SELECT 1 FROM devolucion d
                                 WHERE d.id = dd.devolucion_id))        AS sin_cabecera,
            -- Una línea que apunte a un pedido distinto del de su devolución
            -- valoraría el retorno al precio de OTRA venta.
            (SELECT count(*) FROM devolucion_detalle dd
               JOIN devolucion d      ON d.id  = dd.devolucion_id
               JOIN pedido_detalle pd ON pd.id = dd.pedido_detalle_id
              WHERE pd.pedido_id <> d.pedido_id)                        AS linea_de_otro_pedido,
            (SELECT count(*) FROM devolucion_detalle dd
               JOIN pedido_detalle pd ON pd.id = dd.pedido_detalle_id
              WHERE NOT EXISTS (SELECT 1 FROM producto_variante v
                                 WHERE v.id = pd.producto_variante_id)) AS sin_variante,
            -- No se puede devolver más de lo que se compró.
            (SELECT count(*) FROM (
                SELECT dd.pedido_detalle_id, SUM(dd.cantidad) AS devuelta,
                       max(pd.cantidad) AS vendida
                  FROM devolucion_detalle dd
                  JOIN pedido_detalle pd ON pd.id = dd.pedido_detalle_id
                 GROUP BY 1 HAVING SUM(dd.cantidad) > max(pd.cantidad)) x)
                                                                        AS devuelto_de_mas,
            (SELECT count(*) FROM devolucion d
              WHERE NOT EXISTS (SELECT 1 FROM devolucion_detalle dd
                                 WHERE dd.devolucion_id = d.id))        AS cabeceras_sin_linea
        """

    _EQUIVALENCIAS = (
        ("devoluciones",     "countDistinct(devolucion_id)"),
        ("unidades",         "sum(cantidad)"),
        ("inspeccionadas",   "countIf(inspeccionada = 1)"),
        ("aptas",            "countIf(reingresa_stock = 1)"),
        ("uds_aptas",        "sum(unidades_reingresadas)"),
        ("defectuosas",      "countIf(resultado_inspeccion = 'defectuoso')"),
        ("rechazadas",       "countIf(resultado_inspeccion = 'rechazado')"),
        ("acciones",         "countDistinct(accion)"),
        ("variantes",        "countDistinct(producto_variante_id)"),
        ("suma_monto",       "sum(monto_linea)"),
        ("meses",            "countDistinct(mes)"),
    )

    def validar(self, client, tabla_staging: str, controles: dict) -> list[str]:
        errores = []

        for clave, explicacion in (
            ("sin_pedido_detalle",   "apuntan a una línea de pedido inexistente"),
            ("sin_cabecera",         "apuntan a una devolución inexistente"),
            ("linea_de_otro_pedido", "pertenecen a un pedido distinto del de su devolución"),
            ("sin_variante",         "apuntan a una variante inexistente"),
            ("devuelto_de_mas",      "devuelven más unidades de las que se vendieron"),
            ("cabeceras_sin_linea",  "son cabeceras de devolución SIN ninguna línea"),
        ):
            if int(controles[clave]) != 0:
                errores.append(f"{controles[clave]} líneas {explicacion}.")

        # El invariante que ata esta tabla con `fact_devolucion`: LOG-08 suma
        # líneas y VEN-14 suma cabeceras, y tienen que ser el mismo dinero.
        if round(float(controles["suma_monto"]), 2) != \
                round(float(controles["suma_cabeceras"]), 2):
            errores.append(
                f"Σ líneas {controles['suma_monto']} ≠ Σ cabeceras "
                f"{controles['suma_cabeceras']}. `monto_linea` reproduce la fórmula del "
                f"trigger `fn_recalcular_total_devolucion`; si dejan de coincidir, "
                f"OTD-LOG-08 y OTD-VEN-14 reportan dinero distinto del mismo hecho."
            )

        seleccion = ", ".join(expr for _, expr in self._EQUIVALENCIAS)
        fila = client.query(f"SELECT {seleccion} FROM {tabla_staging}").result_rows[0]
        for (clave, _), valor_ch in zip(self._EQUIVALENCIAS, fila):
            valor_pg = controles.get(clave)
            if valor_pg is None:
                continue
            if type(valor_pg)(valor_ch) != valor_pg:
                errores.append(
                    f"{clave}: PostgreSQL {valor_pg} vs ClickHouse {valor_ch} "
                    f"(diferencia {type(valor_pg)(valor_ch) - valor_pg})."
                )

        errores += self._validar_regla(client, tabla_staging, controles)
        return errores

    def _validar_regla(self, client, tabla_staging: str, controles: dict) -> list[str]:
        """
        `reingresa_stock` es una regla de negocio codificada: se comprueba que
        NADA más que `apto_reventa` la active, en las dos direcciones.
        """
        errores = []
        fila = client.query(f"""
            SELECT countIf(reingresa_stock = 1
                           AND resultado_inspeccion != '{RESULTADO_REINGRESA}'),
                   countIf(reingresa_stock = 0
                           AND resultado_inspeccion  = '{RESULTADO_REINGRESA}'),
                   countIf(reingresa_stock = 1 AND unidades_reingresadas != cantidad),
                   countIf(reingresa_stock = 0 AND unidades_reingresadas != 0),
                   countIf(resultado_inspeccion = '{SIN_INSPECCION}'),
                   countIf(inspeccionada = 1
                           AND resultado_inspeccion = '{SIN_INSPECCION}'),
                   countIf(monto_linea != round(cantidad * precio_unitario, 2)),
                   countDistinct(resultado_inspeccion)
            FROM {tabla_staging}
        """).result_rows[0]
        (falso_positivo, falso_negativo, uds_mal, uds_sobrantes, sin_inspeccionar,
         incoherente, monto_mal, resultados) = fila

        if falso_positivo:
            errores.append(f"{falso_positivo} líneas marcadas `reingresa_stock` sin ser "
                           f"'{RESULTADO_REINGRESA}': LOG-08 devolvería al stock "
                           f"mercancía que no volvió.")
        if falso_negativo:
            errores.append(f"{falso_negativo} líneas '{RESULTADO_REINGRESA}' SIN marcar "
                           f"como reingreso.")
        if uds_mal or uds_sobrantes:
            errores.append(f"Unidades reingresadas incoherentes con la marca "
                           f"({uds_mal} aptas mal contadas, {uds_sobrantes} no aptas "
                           f"con unidades).")
        if incoherente:
            errores.append(f"{incoherente} líneas marcadas como inspeccionadas y con "
                           f"resultado '{SIN_INSPECCION}'.")
        if monto_mal:
            errores.append(f"{monto_mal} líneas cuyo `monto_linea` no es "
                           f"cantidad × precio_unitario.")

        # Las sin inspeccionar TIENEN que llegar y ser distinguibles: son el 41 %
        # y un informe que las ocultara diría que toda la mercancía ya se revisó.
        esperadas = int(controles["filas"]) - int(controles["inspeccionadas"])
        if sin_inspeccionar != esperadas:
            errores.append(
                f"Líneas sin inspeccionar: origen {esperadas} vs destino "
                f"{sin_inspeccionar} etiquetadas '{SIN_INSPECCION}'. Son el desenlace "
                f"PENDIENTE del RMA, no un hueco: ocultarlas haría creer que toda la "
                f"mercancía devuelta ya se revisó."
            )
        # `sin_inspeccionar` es una etiqueta del ETL: el destino tiene una más.
        esperados = int(controles["resultados"]) + (1 if sin_inspeccionar else 0)
        if resultados != esperados:
            errores.append(f"Resultados de inspección distintos: origen "
                           f"{controles['resultados']} (+1 etiqueta) vs destino "
                           f"{resultados}.")
        return errores
