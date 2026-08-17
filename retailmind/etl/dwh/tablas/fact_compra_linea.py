"""
etl/dwh/tablas/fact_compra_linea.py — F5 del modelo (§5.5 del diseño).

La compra vista POR PRODUCTO: qué se compró, a qué precio y cuánto llegó mal.
Grano: una línea de orden de compra, enriquecida con su recepción. 2.949 filas
(2.855 con línea de recepción). Alimenta OTD-COM-07 y OTD-COM-12.

═══════════════════════════════════════════════════════════════════════════════
1. EL ORDER BY INVERTIDO — la única tabla del modelo donde ocurre
═══════════════════════════════════════════════════════════════════════════════

    ORDER BY (producto_variante_id, proveedor_id, fecha_emision)

y NO `(fecha_emision, …)` como las otras trece tablas de hechos. No es un
descuido y §5.5 lo declara: **OTD-COM-12 es una serie POR PRODUCTO, no un corte
por período.** Pregunta cómo cambia el precio que cobra un proveedor por *cada
producto* entre una compra y la siguiente, y esa respuesta se calcula con
`neighbor()` o `lagInFrame()` sobre compras sucesivas de la misma variante al
mismo proveedor.

Con esta clave esas compras quedan **físicamente contiguas y ya ordenadas en el
tiempo**: la ventana no tiene que ordenar nada en tiempo de consulta. Con la
clave habitual estarían dispersas por toda la tabla y cada consulta de COM-12
pagaría un `ORDER BY` completo.

COM-07 sí filtra por período, pero agrupa por proveedor sobre 2.949 filas: le
sobra con la partición mensual. La asimetría está a favor del informe que la
necesita.

UNA SALVEDAD QUE COM-12 DEBE CONOCER: la serie tiene **923 pares
(variante, proveedor) con más de una compra**, y en **16 de ellos dos líneas
comparten el MISMO día** (35 líneas en total). Para esas, «la compra anterior»
es ambigua: `fecha_emision` es un `date`, no un instante, y el desempate lo
decide el orden físico. No es un error de carga —los dos precios son reales— pero
el informe no debería presentar la variación entre dos compras del mismo día como
una evolución temporal. Desempatar por `orden_compra_id` en la ventana es la
salida limpia si algún día importa.

═══════════════════════════════════════════════════════════════════════════════
2. `pct_rechazo`: SOBRE LO QUE LLEGÓ, NO SOBRE LO PEDIDO — corrección C3.2
═══════════════════════════════════════════════════════════════════════════════

§5.5 lista `pct_rechazo Decimal(6,2)` **sin declarar su denominador**, y las tres
columnas de cantidad que la rodean sugieren la lectura obvia: rechazadas ÷
pedidas. Es la lectura equivocada, porque `cantidad_recibida` y
`cantidad_rechazada` no guardan una relación constante con lo pedido. Sobre las
92 líneas con rechazo:

    recibida + rechazada = pedida .... 49 líneas   el rechazo se DESCONTÓ
    recibida + rechazada > pedida .... 37 líneas   el rechazo va ENCIMA
    recibida + rechazada < pedida ....  6 líneas   rechazo Y entrega parcial

Un caso real del grupo aditivo (línea 649, `OC-20251121-100338`): `cantidad = 7`,
`cantidad_recibida = 7`, `cantidad_rechazada = 3`. El proveedor entregó **10**,
se aceptaron 7 y se rechazaron 3.

El denominador correcto es lo que **físicamente llegó**, que está bien definido
en los tres grupos:

    pct_rechazo = 100 × rechazada / (recibida + rechazada)

Sobre lo pedido, esa línea daría 3/7 = **42,9 %** donde la verdad es 3/10 =
**30,0 %**. En el agregado la diferencia parece nimia (0,1559 % contra 0,1542 %),
pero **COM-07 no se lee en el agregado: se lee por proveedor**, y el sesgo solo
afecta a las 37 líneas del grupo aditivo — siempre hacia arriba. Un proveedor
cuyos rechazos se registraron de esa manera aparece sistemáticamente peor que
otro idéntico cuyo almacén los registró de la otra. El informe existe para
comparar proveedores, y ése es justo el eje que se corrompe.

═══════════════════════════════════════════════════════════════════════════════
3. `motivo_rechazo`: 6 VALORES DONDE EL NEGOCIO TIENE 5 — corrección C3.3
═══════════════════════════════════════════════════════════════════════════════

§5.5 lo trata como un catálogo cerrado que se carga tal cual. Es **texto libre**,
y ya tiene una entrada escrita a mano:

    Empaque danado en transito ............ 25
    Producto con defecto de fabrica ....... 21
    Fecha de caducidad proxima ............ 19
    No coincide con especificacion ........ 16
    Unidades incompletas en caja .......... 10
    cajas mojadas en el transporte ........  1   ← tecleado en la app
                                            ───
                                            92 líneas con rechazo

El sexto valor viene de `RM-20260718-100010` (usuario 9, 2026-07-18): una
recepción hecha desde la aplicación real durante el desarrollo del script 45, no
del seed. Es el mismo hallazgo que `motivo_fallo` en la Fase 2 — conviven el
catálogo y el texto libre— y se resuelve con el mismo patrón: normalización en
`transformar()`, en Python y no en un `CASE` de SQL, con lista blanca, mapa de
sinónimos y **regla de escape**. Un valor no previsto se carga como `'Otro'`, se
escribe en el log y se cuenta en `etl_ejecucion.excepciones`; nunca se descarta
ni se silencia. Un `CASE` puede mapear, pero no puede avisar.

DIFERENCIA DELIBERADA CON LA FASE 2: allí la normalización produce **códigos**
(`tarjeta_rechazada`) porque el origen ya guardaba códigos y el texto libre era
la anomalía. Aquí el origen guarda **frases legibles** y el canónico es la frase,
no un slug: convertirlas a `empaque_danado_transito` obligaría a cada informe a
traducir de vuelta para mostrar algo leíble, y no compra nada. Se normaliza el
vocabulario, no se cambia el idioma.

`cajas mojadas en el transporte → Empaque danado en transito` es una decisión de
criterio y se declara como tal: es la misma incidencia —daño del embalaje en el
traslado— escrita por una persona en vez de elegida de una lista. Las
alternativas (dejarla cruda, o mandarla a `'Otro'`) dan **igualmente** una sexta
categoría en COM-07, y encima una que no significa nada.

═══════════════════════════════════════════════════════════════════════════════
4. LAS 94 LÍNEAS SIN RECEPCIÓN SE CARGAN — corrección C3.7
═══════════════════════════════════════════════════════════════════════════════

Pertenecen a las 26 órdenes que nunca se recibieron y suman **2.372 unidades
pedidas por $273.265,18 que jamás llegaron**:

                     líneas   pedidas   recibidas   rechazadas
    recibida ......... 2.611   109.305     109.209          169
    recibida_parcial ..  244    10.682       9.264           16
    cancelada .........   53     1.331           0            0
    confirmada ........   25       620           0            0
    enviada ...........   16       421           0            0

Entran por `LEFT JOIN` con `COALESCE(…, 0)` en las tres cantidades. Un `INNER
JOIN` contra `recepcion_detalle` daría 2.855 filas —el conteo lo atraparía— pero
sobre todo haría **invisible el 100 % de incumplimiento**: un proveedor cuya
orden se canceló entera desaparecería del informe en vez de aparecer con cero
unidades servidas.

Verificado antes de confiar en el LEFT JOIN: `recepcion_detalle` es
estrictamente 1:1 con `orden_compra_detalle` donde existe (0 líneas con dos
recepciones, 2.855 distintas sobre 2.855 filas) y
`orden_compra_detalle.cantidad_recibida` coincide SIEMPRE con
`recepcion_detalle.cantidad_recibida` (0 discrepancias), así que la
denormalización no introduce una segunda verdad. Se toma la de la RECEPCIÓN, que
es el documento que también trae el rechazo y el motivo.

**AVISO (2026-08-17): ese 1:1 CADUCÓ y con él el `LEFT JOIN` que autorizaba.**
Hay una línea con dos recepciones (la 2.957 de la OC 920) y el join la partía en
dos filas. La recepción entra ahora AGREGADA por línea: ver la constante
`_RECEPCION_AGREGADA` y la corrección C6.3. Lo que sigue en pie es la segunda
mitad de la frase —`cantidad_recibida` del detalle coincide con la SUMA de las
recepciones de la línea—, y es justo lo que hace que agregar sea lícito.

Bitácora completa de correcciones: `docs/estrategico/CORRECCIONES_DISENO_ETL.md`.
"""

from datetime import datetime
from zoneinfo import ZoneInfo

from etl.dwh.conexiones import ZONA_HORARIA, logger
from etl.dwh.tarea import TareaCarga

#: Los CINCO motivos del negocio, en su forma canónica. Lista blanca: lo que no
#: esté aquí ni en el mapa de sinónimos es un valor nuevo y se trata como tal.
MOTIVOS_CONOCIDOS = (
    "Empaque danado en transito",
    "Producto con defecto de fabrica",
    "Fecha de caducidad proxima",
    "No coincide con especificacion",
    "Unidades incompletas en caja",
)

#: Índice {forma normalizada → forma canónica} para el cotejo. La clave va en
#: minúsculas y sin espacios en los bordes porque así llega del SELECT.
_CANONICO = {m.lower(): m for m in MOTIVOS_CONOCIDOS}

#: Sinónimos escritos a mano en la aplicación. Ver el punto 3 de la cabecera:
#: cada entrada es una decisión de criterio, no una regla automática.
MAPA_MOTIVOS = {
    "cajas mojadas en el transporte": "Empaque danado en transito",
}

#: Destino de un valor no previsto. Regla de escape de §5.3, aplicada aquí.
MOTIVO_OTRO = "Otro"

#: Línea sin rechazo. Cadena vacía y no NULL: la columna es `LowCardinality` y
#: se agrupa en COM-07.
SIN_RECHAZO = ""


class FactCompraLinea(TareaCarga):

    nombre = "fact_compra_linea"

    def __init__(self):
        super().__init__()
        #: Motivos no previstos, con su recuento. Se vuelcan al log y su total
        #: va a `etl_ejecucion.excepciones`.
        self.motivos_desconocidos: dict[str, int] = {}

    # ── Definición ───────────────────────────────────────────────────────────

    def ddl(self, nombre_tabla: str) -> str:
        return f"""
        CREATE TABLE {nombre_tabla}
        (
            orden_compra_detalle_id UInt32,
            orden_compra_id         UInt32,
            numero_oc               String,
            fecha_emision           Date,
            mes                     Date,
            proveedor_id            UInt16,
            proveedor               LowCardinality(String),
            producto_variante_id    UInt32,
            sku                     String,
            producto_nombre         String,
            categoria               LowCardinality(String),
            cantidad_pedida         UInt32,
            cantidad_recibida       UInt32,
            cantidad_rechazada      UInt32,
            motivo_rechazo          LowCardinality(String),
            pct_rechazo             Decimal(6,2),
            precio_unitario         Decimal(14,2),
            subtotal                Decimal(14,2),
            completa                UInt8,
            fecha_carga             DateTime('{ZONA_HORARIA}')
        )
        ENGINE = MergeTree
        PARTITION BY toYYYYMM(fecha_emision)
        ORDER BY (producto_variante_id, proveedor_id, fecha_emision)
        """

    def columnas(self) -> list[str]:
        return [
            "orden_compra_detalle_id", "orden_compra_id", "numero_oc",
            "fecha_emision", "mes", "proveedor_id", "proveedor",
            "producto_variante_id", "sku", "producto_nombre", "categoria",
            "cantidad_pedida", "cantidad_recibida", "cantidad_rechazada",
            "motivo_rechazo", "pct_rechazo", "precio_unitario", "subtotal",
            "completa", "fecha_carga",
        ]

    # ── Extracción ───────────────────────────────────────────────────────────

    #: Lo que el proveedor entregó realmente: aceptado + rechazado. Es el
    #: denominador de `pct_rechazo` y se aísla como constante para que la
    #: consulta y el control no puedan definirlo de dos maneras (C3.2).
    _LLEGADO = ("(COALESCE(rd.cantidad_recibida, 0) "
                "+ COALESCE(rd.cantidad_rechazada, 0))")

    #: LA RECEPCIÓN DE LA LÍNEA, AGREGADA — corrección C6.3 (2026-08-17).
    #:
    #: El grano de esta tabla es UNA LÍNEA DE ORDEN, y hasta hoy la recepción
    #: entraba con `LEFT JOIN recepcion_detalle` amparándose en una medición que
    #: caducó: «`recepcion_detalle` es estrictamente 1:1 con `orden_compra_detalle`
    #: donde existe». Ya no lo es. La línea 2.957 (OC 920) tiene DOS líneas de
    #: recepción —11 aceptadas con 1 rechazada, y la que faltaba al día
    #: siguiente— y ese JOIN la partía en dos filas, rompiendo el grano y sumando
    #: 12 unidades pedidas y $1.020,00 de subtotal que no existen.
    #:
    #: La recepción se AGREGA, no se elige: una línea puede recibirse en varios
    #: actos y lo recibido es la SUMA de todos. Así `cantidad_recibida` sigue
    #: cuadrando al centavo con `SUM(cantidad_recibida) FROM recepcion_detalle`,
    #: que es lo que mide el control.
    #:
    #: `lineas_recepcion` (count, nunca NULL) sustituye al viejo `rd.id IS NOT
    #: NULL` como prueba de «esta línea tuvo recepción»: los SUM sobre cero filas
    #: devuelven NULL y no distinguirían «recibí 0» de «no hubo recepción».
    #:
    #: EL MOTIVO no se puede sumar y hay que elegirlo. Se toma el de la recepción
    #: que de verdad RECHAZÓ algo y, entre varias, la última por `id`. Un motivo
    #: sin rechazo no debe viajar —`_validar_motivos` aborta si aparece— y en la
    #: línea real el segundo acto no rechazó nada y no trae motivo.
    _RECEPCION_AGREGADA = """
        SELECT
            count(*)                    AS lineas_recepcion,
            SUM(r2.cantidad_recibida)   AS cantidad_recibida,
            SUM(r2.cantidad_rechazada)  AS cantidad_rechazada,
            (SELECT NULLIF(TRIM(r3.motivo_rechazo), '')
             FROM recepcion_detalle r3
             WHERE r3.orden_compra_detalle_id = d.id
               AND NULLIF(TRIM(r3.motivo_rechazo), '') IS NOT NULL
             ORDER BY (r3.cantidad_rechazada > 0) DESC, r3.id DESC
             LIMIT 1)                   AS motivo_rechazo
        FROM recepcion_detalle r2
        WHERE r2.orden_compra_detalle_id = d.id
    """

    def sql_extraccion(self) -> str:
        """
        `motivo_rechazo` sale CRUDO del SELECT —solo recortado y con NULL
        convertido a cadena vacía—: la normalización y el escape a 'Otro' viven
        en `transformar()`, que es el único sitio capaz de avisar.
        """
        return f"""
        SELECT
            d.id                                           AS orden_compra_detalle_id,
            d.orden_compra_id,
            oc.numero                                      AS numero_oc,
            oc.fecha_emision,
            (date_trunc('month', oc.fecha_emision))::date  AS mes,
            oc.proveedor_id,
            pv.razon_social                                AS proveedor,
            d.producto_variante_id,
            v.sku,
            p.nombre                                       AS producto_nombre,
            COALESCE(c.nombre, 'sin_categoria')            AS categoria,

            d.cantidad                                     AS cantidad_pedida,
            COALESCE(rd.cantidad_recibida, 0)              AS cantidad_recibida,
            COALESCE(rd.cantidad_rechazada, 0)             AS cantidad_rechazada,
            COALESCE(NULLIF(TRIM(rd.motivo_rechazo), ''), '') AS motivo_rechazo,

            -- C3.2: el denominador es lo que LLEGÓ, no lo que se pidió.
            COALESCE(
                ROUND(100.0 * COALESCE(rd.cantidad_rechazada, 0)
                      / NULLIF({self._LLEGADO}, 0), 2),
                0
            )                                              AS pct_rechazo,

            d.precio_unitario,
            d.subtotal,
            CASE WHEN rd.lineas_recepcion > 0 AND rd.cantidad_recibida >= d.cantidad
                 THEN 1 ELSE 0 END                         AS completa
        FROM orden_compra_detalle d
        JOIN orden_compra oc        ON oc.id = d.orden_compra_id
        JOIN proveedor pv           ON pv.id = oc.proveedor_id
        JOIN producto_variante v    ON v.id  = d.producto_variante_id
        JOIN producto p             ON p.id  = v.producto_id
        LEFT JOIN producto_categoria pc
                                    ON pc.producto_id = p.id AND pc.es_principal
        LEFT JOIN categoria c       ON c.id  = pc.categoria_id
        LEFT JOIN LATERAL ({self._RECEPCION_AGREGADA}) rd ON TRUE
        ORDER BY d.producto_variante_id, oc.proveedor_id, oc.fecha_emision, d.id
        """

    # ── Transformación: la normalización del motivo ──────────────────────────

    def transformar(self, lote: list[tuple]) -> list[tuple]:
        ahora = datetime.now(ZoneInfo(ZONA_HORARIA)).replace(microsecond=0)
        i_motivo = self.columnas().index("motivo_rechazo")

        salida = []
        for fila in lote:
            fila = list(fila)
            fila[i_motivo] = self._normalizar_motivo(fila[i_motivo])
            salida.append(tuple(fila) + (ahora,))

        self.excepciones = sum(self.motivos_desconocidos.values())
        return salida

    def _normalizar_motivo(self, crudo: str | None) -> str:
        """
        Tres caminos y ninguno silencioso: canónico ya conocido, sinónimo del
        mapa, o valor nuevo → 'Otro' anotado y registrado.
        """
        if not crudo:
            return SIN_RECHAZO
        valor = crudo.strip().lower()
        if valor in _CANONICO:
            return _CANONICO[valor]
        if valor in MAPA_MOTIVOS:
            return MAPA_MOTIVOS[valor]

        self.motivos_desconocidos[valor] = self.motivos_desconocidos.get(valor, 0) + 1
        if self.motivos_desconocidos[valor] == 1:
            logger.warning(
                f"[{self.nombre}] motivo de rechazo NO previsto: «{crudo}». Se carga "
                f"como '{MOTIVO_OTRO}' y queda contado en la bitácora. `motivo_rechazo` "
                f"es TEXTO LIBRE en la aplicación (C3.3): si es un motivo real del "
                f"negocio, añádelo a MOTIVOS_CONOCIDOS o a MAPA_MOTIVOS antes de que "
                f"OTD-COM-07 lo muestre como una categoría propia."
            )
        return MOTIVO_OTRO

    # ── Controles ────────────────────────────────────────────────────────────

    #: La MISMA normalización, expresada en SQL, para poder contar los motivos
    #: canónicos en el origen. Es una traducción del mapa de Python: si se añade
    #: un sinónimo allí, hay que añadirlo aquí — y el control de conteo de
    #: motivos avisa si se olvida.
    #: Lleva la REGLA DE ESCAPE desde el 2026-08-17. Antes traducía el sinónimo y
    #: dejaba pasar lo demás tal cual, así que contaba un motivo nuevo como una
    #: categoría propia mientras Python lo mandaba a 'Otro'. Con los 6 crudos de
    #: entonces daba 5 y cuadraba; con «Caja dañada» (2026-08-16) daba 6 y seguía
    #: cuadrando POR CASUALIDAD —5 canónicos + 'Otro' también son 6—, y con dos
    #: motivos nuevos habría dado 7 contra 6. Un control que acierta por
    #: coincidencia no es un control.
    _MOTIVO_NORMALIZADO_SQL = """
        CASE LOWER(TRIM(rd.motivo_rechazo))
             WHEN 'empaque danado en transito'     THEN 'Empaque danado en transito'
             WHEN 'producto con defecto de fabrica' THEN 'Producto con defecto de fabrica'
             WHEN 'fecha de caducidad proxima'     THEN 'Fecha de caducidad proxima'
             WHEN 'no coincide con especificacion' THEN 'No coincide con especificacion'
             WHEN 'unidades incompletas en caja'   THEN 'Unidades incompletas en caja'
             WHEN 'cajas mojadas en el transporte' THEN 'Empaque danado en transito'
             ELSE 'Otro'
        END
    """

    def sql_controles(self) -> str:
        return f"""
        SELECT
            (SELECT count(*) FROM orden_compra_detalle)               AS filas,
            (SELECT count(*) FROM recepcion_detalle)                  AS con_recepcion,
            (SELECT SUM(cantidad) FROM orden_compra_detalle)          AS unidades_pedidas,
            (SELECT SUM(cantidad_recibida) FROM recepcion_detalle)    AS unidades_recibidas,
            (SELECT SUM(cantidad_rechazada) FROM recepcion_detalle)   AS unidades_rechazadas,
            (SELECT SUM(subtotal) FROM orden_compra_detalle)          AS subtotal,
            -- LÍNEAS con rechazo, contadas por LÍNEA y no por fila de recepción
            -- (C6.3): una línea recibida en dos actos que rechace en ambos es UNA
            -- línea con rechazo, no dos. El criterio no se toca —«rechazó algo»—,
            -- solo el grano en el que se cuenta, que ahora es el de la tabla.
            (SELECT count(*) FROM orden_compra_detalle d
             WHERE (SELECT SUM(rd.cantidad_rechazada) FROM recepcion_detalle rd
                    WHERE rd.orden_compra_detalle_id = d.id) > 0)
                                                                      AS lineas_con_rechazo,
            (SELECT count(DISTINCT {self._MOTIVO_NORMALIZADO_SQL})
             FROM recepcion_detalle rd WHERE rd.motivo_rechazo IS NOT NULL)
                                                                      AS motivos_normalizados,
            (SELECT count(DISTINCT rd.motivo_rechazo) FROM recepcion_detalle rd
             WHERE rd.motivo_rechazo IS NOT NULL)                     AS motivos_crudos,
            -- «Completa» es una propiedad de la LÍNEA y se mide sobre el TOTAL
            -- recibido (C6.3). Por fila de recepción, la línea 2.957 —12 pedidas,
            -- servidas 11 + 1— no contaba como completa en NINGUNA de sus dos
            -- recepciones, cuando la verdad es que se sirvió entera. El SUM sobre
            -- cero filas es NULL y no cuenta: la línea sin recepción sigue fuera.
            (SELECT count(*) FROM orden_compra_detalle d
             WHERE (SELECT SUM(rd.cantidad_recibida) FROM recepcion_detalle rd
                    WHERE rd.orden_compra_detalle_id = d.id) >= d.cantidad)
                                                                      AS completas,
            (SELECT count(DISTINCT producto_variante_id) FROM orden_compra_detalle)
                                                                      AS variantes,
            (SELECT count(DISTINCT orden_compra_id) FROM orden_compra_detalle)
                                                                      AS ordenes,
            (SELECT count(DISTINCT date_trunc('month', oc.fecha_emision))
             FROM orden_compra_detalle d
             JOIN orden_compra oc ON oc.id = d.orden_compra_id)       AS meses,
            -- El numerador y el denominador de C3.2, por separado: si alguien
            -- cambiara el denominador a `cantidad`, esta cifra lo delata.
            (SELECT SUM(rd.cantidad_recibida + rd.cantidad_rechazada)
             FROM recepcion_detalle rd)                               AS unidades_llegadas,
            (SELECT count(*) FROM orden_compra_detalle d
             WHERE (SELECT SUM(rd.cantidad_recibida + rd.cantidad_rechazada)
                    FROM recepcion_detalle rd
                    WHERE rd.orden_compra_detalle_id = d.id) > d.cantidad)
                                                                      AS rechazo_aditivo
        """

    _EQUIVALENCIAS = (
        ("unidades_pedidas",    "sum(cantidad_pedida)"),
        ("unidades_recibidas",  "sum(cantidad_recibida)"),
        ("unidades_rechazadas", "sum(cantidad_rechazada)"),
        ("subtotal",            "sum(subtotal)"),
        ("lineas_con_rechazo",  "countIf(cantidad_rechazada > 0)"),
        ("completas",           "countIf(completa = 1)"),
        ("variantes",           "countDistinct(producto_variante_id)"),
        ("ordenes",             "countDistinct(orden_compra_id)"),
        ("meses",               "countDistinct(mes)"),
        ("unidades_llegadas",   "sum(cantidad_recibida + cantidad_rechazada)"),
    )

    def validar(self, client, tabla_staging: str, controles: dict) -> list[str]:
        errores = []
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

        errores += self._validar_grano(client, tabla_staging, controles)
        errores += self._validar_motivos(client, tabla_staging, controles)
        errores += self._validar_pct_rechazo(client, tabla_staging)
        return errores

    def _validar_grano(self, client, tabla_staging: str, controles: dict) -> list[str]:
        """
        El grano es UNA línea de orden. El join de categoría es el sospechoso
        habitual de fan-out (`producto_categoria` admite varias por producto),
        y el de recepción lo sería si alguna vez dejara de ser 1:1.
        """
        errores = []
        total, ids, con_recep = client.query(f"""
            SELECT count(), countDistinct(orden_compra_detalle_id),
                   countIf(cantidad_recibida > 0 OR cantidad_rechazada > 0)
            FROM {tabla_staging}
        """).result_rows[0]

        if ids != total:
            errores.append(
                f"El grano se rompió: {total} filas pero {ids} líneas distintas. "
                f"Sospechosos: `producto_categoria` (varias categorías por "
                f"producto) o una línea con dos recepciones."
            )
        # No es igual a `con_recepcion` del origen: una línea recibida puede
        # traer 0 unidades. Se compara contra el origen con la misma definición.
        esperado = int(controles["con_recepcion"])
        if con_recep > esperado:
            errores.append(f"{con_recep} líneas con unidades movidas donde el origen "
                           f"tiene {esperado} líneas de recepción.")
        return errores

    def _validar_motivos(self, client, tabla_staging: str, controles: dict) -> list[str]:
        """
        El control que da sentido a la normalización de C3.3: los motivos deben
        quedar en CINCO —uno menos que los 6 crudos del origen— y toda línea con
        rechazo debe tener el suyo.
        """
        errores = []
        filas = client.query(f"""
            SELECT motivo_rechazo, count() FROM {tabla_staging}
            WHERE cantidad_rechazada > 0
            GROUP BY motivo_rechazo ORDER BY count() DESC
        """).result_rows
        motivos = {m: n for m, n in filas}

        if SIN_RECHAZO in motivos:
            errores.append(
                f"{motivos[SIN_RECHAZO]} líneas con unidades rechazadas se quedaron "
                f"SIN motivo. En el origen no hay ninguna (0 de 92): si aparece, el "
                f"join con `recepcion_detalle` se rompió."
            )
        distintos = len([m for m in motivos if m])
        esperados = int(controles["motivos_normalizados"])
        if distintos != esperados:
            errores.append(
                f"Motivos de rechazo distintos: origen normalizado {esperados} vs "
                f"destino {distintos} → {sorted(motivos)}. El mapa de normalización "
                f"(C3.3) dejó de cubrir los datos, o Python y SQL discrepan."
            )
        # Una línea sin rechazo NO puede llevar motivo, y el origen lo cumple
        # (0 de 2.855 con motivo y cero rechazadas).
        sobrantes = client.query(f"""
            SELECT count() FROM {tabla_staging}
            WHERE cantidad_rechazada = 0 AND motivo_rechazo != '{SIN_RECHAZO}'
        """).result_rows[0][0]
        if sobrantes:
            errores.append(f"{sobrantes} líneas sin rechazo traen motivo de rechazo.")

        if MOTIVO_OTRO in motivos:
            # No aborta: la regla de escape existe para que un valor nuevo se
            # cargue en vez de tumbar la corrida. Pero se deja dicho.
            logger.warning(
                f"[{self.nombre}] {motivos[MOTIVO_OTRO]} líneas cayeron en "
                f"'{MOTIVO_OTRO}': {self.motivos_desconocidos}"
            )
        return errores

    def _validar_pct_rechazo(self, client, tabla_staging: str) -> list[str]:
        """
        C3.2 en forma de control. Dos preguntas que solo tienen sentido con el
        denominador correcto:

          * ninguna línea puede superar el 100 % de rechazo;
          * ninguna línea SIN unidades llegadas puede tener porcentaje.

        Y la tercera, la que de verdad cierra la corrección: recalcular el
        porcentaje desde las columnas del propio almacén y exigir que coincida
        con el que se cargó. Si alguien cambiara el denominador a
        `cantidad_pedida`, las 37 líneas del grupo aditivo dejarían de cuadrar.
        """
        fila = client.query(f"""
            SELECT
                countIf(pct_rechazo > 100),
                countIf((cantidad_recibida + cantidad_rechazada) = 0 AND pct_rechazo != 0),
                countIf(
                    (cantidad_recibida + cantidad_rechazada) > 0
                    AND pct_rechazo != round(
                        100 * toDecimal64(cantidad_rechazada, 4)
                            / toDecimal64(cantidad_recibida + cantidad_rechazada, 4), 2)
                )
            FROM {tabla_staging}
        """).result_rows[0]
        sobre_cien, sin_llegada, descuadradas = fila

        errores = []
        if sobre_cien:
            errores.append(f"{sobre_cien} líneas con `pct_rechazo` > 100 %: el "
                           f"denominador de C3.2 dejó de ser lo que llegó.")
        if sin_llegada:
            errores.append(f"{sin_llegada} líneas sin unidades llegadas tienen "
                           f"`pct_rechazo` distinto de 0.")
        if descuadradas:
            errores.append(
                f"{descuadradas} líneas cuyo `pct_rechazo` no se reproduce desde "
                f"sus propias cantidades. La fórmula declarada en C3.2 es "
                f"100 × rechazada / (recibida + rechazada)."
            )
        return errores
