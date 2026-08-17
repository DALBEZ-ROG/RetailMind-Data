"""
etl/dwh/tablas/fact_orden_compra.py — F4 del modelo (§5.4 del diseño).

La compra vista COMO UN SOLO DOCUMENTO: el ciclo orden → recepción → factura →
cuenta por pagar unificado en una fila. Grano: una orden de compra. 865 filas.
Alimenta OTD-COM-04, COM-05, COM-06 y el lado *devengado* de GER-02.

═══════════════════════════════════════════════════════════════════════════════
1. LA CADENA NO ES 1:1 COMPLETA — corrección C3.1, el hallazgo de esta fase
═══════════════════════════════════════════════════════════════════════════════

§5.4 afirma literalmente que «las cuatro son 1:1 (probado, §3.1)». Se verificó
antes de escribir un solo JOIN, que es lo que el encargo de la fase mandaba, y
el resultado tiene dos mitades:

    LO QUE SÍ SE SOSTIENE — la multiplicidad
        máx. recepciones por OC ..................... 1   ← YA NO, ver C6.1 abajo
        OC con más de una factura ................... 0
        facturas con más de una cuenta por pagar .... 0
        facturas sin orden de compra ................ 0

**AVISO (2026-08-17): la primera línea de ese bloque CADUCÓ.** Una orden de compra
puede tener DOS recepciones y desde el 2026-08-16 las tiene (OC 920). El
`LEFT JOIN recepcion_mercancia` que esa medición autorizaba se sustituyó por la
recepción CANÓNICA — ver la constante `_RECEPCION_CANONICA` y la corrección C6.1.
Las otras tres siguen verificadas.

    LO QUE NO — la cobertura
        órdenes de compra ........................... 865
        OC con recepción ............................ 839
        OC con factura .............................. 839    ← el mismo número…
        OC con recepción Y factura .................. 838    ← …y otro conjunto

**Dos conteos idénticos que describen conjuntos distintos.** Sobran dos
documentos, ambos del desarrollo del 2026-07-18, y rompen la cadena en
direcciones opuestas:

    FC-20260710-25356  →  OC 8  (confirmada)  factura SIN recepción   $34,50
    RM-20260718-100010 →  OC 20 (recibida)    recepción SIN factura   $54,63

El primero se saltó la compuerta «sin recibir completo no se factura»; el
segundo dejó el ciclo a medias. Ninguno es corrupción de datos: son dos
documentos reales creados a mano contra la aplicación.

CÓMO SE RESUELVE. La extracción parte SIEMPRE de `orden_compra` y llega a los
tres documentos por LEFT JOIN encadenado: 865 filas pase lo que pase, con NULL
—y su centinela— donde el documento no existe. Y la validación compara los
CONJUNTOS y no los conteos: `con_recepcion`, `con_factura` y `con_ambos` se
validan por separado, precisamente porque los dos primeros coinciden en 839
mientras hablan de OCs diferentes.

QUÉ HABRÍA ROTO. Un `orden_compra ⋈ recepcion ⋈ factura` con INNER JOIN —que es
lo natural si uno se cree el 1:1— deja la tabla en 838 filas facturadas y hace
desaparecer $34,50 del gasto de compras. Treinta y cuatro dólares sobre veintidós
millones: imposible de ver, imposible de explicar medio año después. Y un
control escrito como «839 = 839 ⇒ la cadena está completa» pasa en VERDE con los
conjuntos mal.

═══════════════════════════════════════════════════════════════════════════════
2. LA FACTURA NO VALE LO QUE LA ORDEN — corrección C3.6
═══════════════════════════════════════════════════════════════════════════════

§5.4 pone en la misma fila `subtotal/monto_impuesto/total` («de la orden») y
`factura_total`, sin advertir que NO son intercambiables:

    Σ total de órdenes de compra .................. $23.007.732,68
    Σ total de facturas de compra ................. $22.467.387,27
                                          brecha    $   540.345,41
        de la que:
        26 órdenes sin factura ..................... $   314.275,10
        119 órdenes cuya factura difiere ........... $   226.070,31

La causa es el propio flujo del sistema: **72 órdenes quedaron en
`recibida_parcial`** y el proveedor factura lo que entregó, no lo que se le
pidió (de 10.682 unidades pedidas en esas órdenes llegaron 9.264).

Por eso las dos magnitudes viajan en columnas distintas y este docstring declara
cuál responde a qué: **el gasto de compras de OTD-COM-04 y GER-02 es
`factura_total`** —lo que se debe—, y `total` es el compromiso emitido. Sumar el
total de la orden reportaría un **+2,4 % de gasto que nunca se facturó**, cifra
lo bastante plausible para no levantar sospecha y lo bastante grande para que la
balanza de GER-02 no cuadre jamás contra contabilidad.

═══════════════════════════════════════════════════════════════════════════════
3. LOS DOS INDICADORES DE PLAZO, Y LA ZONA HORARIA — corrección C3.4
═══════════════════════════════════════════════════════════════════════════════

    dias_ciclo_real     = recepción − emisión           → OTD-COM-06
    dias_desvio_promesa = recepción − entrega esperada  → OTD-COM-05
    cumplio_promesa     = dias_desvio_promesa <= 0

Los tres operandos NO son del mismo tipo: `recepcion_mercancia.fecha_recepcion`
es `timestamptz`, mientras `fecha_emision` y `fecha_entrega_esperada` son `date`
puros. Restarlos exige convertir el primero a día — y el día depende de la zona:

    recepciones ................................................. 839
      cuyo día en UTC ≠ su día en America/Guayaquil ..............   5

Cinco recepciones registradas después de las 19:00 locales caen al día siguiente
en UTC. La conversión es EXPLÍCITA en el SELECT (`AT TIME ZONE`), nunca
implícita ni dependiente de la zona de sesión del rol `retailmind_etl`.

Un día de más en 5 de 839 no se ve en el promedio de COM-06 (10,81 días), pero
COM-05 clasifica en cumplió/no cumplió con el corte exacto en `desvio <= 0`, y
ahí un día CAMBIA DE LADO. Cifras reales hoy: ciclo de 0 a 25 días; desvío de −7
a +10; 825 pares comparables, 449 puntuales.

`cumplio_promesa` es `Nullable`: sin recepción o sin promesa no hay veredicto, y
un 0 querría decir «incumplió», que es una afirmación distinta de «no se sabe».

═══════════════════════════════════════════════════════════════════════════════
4. AGREGADOS DEL DETALLE Y ETIQUETA DEL PROVEEDOR
═══════════════════════════════════════════════════════════════════════════════

`lineas`, `unidades_pedidas` y `unidades_recibidas` se calculan en un LATERAL
sobre el detalle de la ORDEN, y `unidades_rechazadas` en un LATERAL propio sobre
`recepcion_detalle`.

Están SEPARADOS desde el 2026-08-17 y esa separación es la corrección C6.2. Antes
los cuatro salían de un solo LATERAL que unía el detalle de la orden con
`recepcion_detalle`, apoyándose en una medición que ya no se sostiene: «0 líneas
con dos recepciones». Hoy hay una (la línea 2.957 de la OC 920), y ese LEFT JOIN
la duplicaba DENTRO del LATERAL: `lineas` contaba 3 donde hay 2 y las unidades se
sumaban dos veces. Lo pedido y lo recibido son del DETALLE DE LA ORDEN —una fila
por línea, pase lo que pase—; el rechazo es del documento de RECEPCIÓN y se suma
sobre todas las recepciones de la orden.

Lo que sí sigue verificado: `orden_compra_detalle.cantidad_recibida` coincide
SIEMPRE con la SUMA de `recepcion_detalle.cantidad_recibida` de esa línea (12 = 11
+ 1 en la línea de dos recepciones), de modo que la denormalización no introduce
una segunda verdad.

El rótulo `proveedor` es `razon_social` y no `nombre_comercial`, a propósito: es
el mismo que `fact_flujo_caja.contraparte_nombre` usa para el egreso. La misma
entidad debe llamarse igual en todas las tablas de hechos o el usuario ve dos
proveedores donde hay uno. El nombre corto está en `dim_proveedor`, a un JOIN.

`estado`: aprobar una orden la deja en **'confirmada'**; NO existe 'aprobada'
(§5.4 acierta aquí). Los cinco estados reales son `recibida` 767,
`recibida_parcial` 72, `cancelada` 13, `confirmada` 7 y `enviada` 6.

Bitácora completa de correcciones: `docs/estrategico/CORRECCIONES_DISENO_ETL.md`.
"""

from datetime import datetime
from zoneinfo import ZoneInfo

from etl.dwh.conexiones import ZONA_HORARIA
from etl.dwh.tarea import TareaCarga

#: Centinela del estado de la cuenta por pagar cuando la orden no llegó a
#: generar una. 26 órdenes hoy. No es 'pendiente': pendiente es una CxP que
#: existe y aún no se ha pagado.
SIN_CXP = "sin_cxp"


class FactOrdenCompra(TareaCarga):

    nombre = "fact_orden_compra"

    # ── Definición ───────────────────────────────────────────────────────────

    def ddl(self, nombre_tabla: str) -> str:
        return f"""
        CREATE TABLE {nombre_tabla}
        (
            orden_compra_id        UInt32,
            numero                 String,
            proveedor_id           UInt16,
            proveedor              LowCardinality(String),
            bodega                 LowCardinality(String),
            estado                 LowCardinality(String),
            fecha_emision          Date,
            mes                    Date,
            fecha_entrega_esperada Nullable(Date),
            fecha_recepcion        Nullable(DateTime('{ZONA_HORARIA}')),
            dias_ciclo_real        Nullable(Int16),
            dias_desvio_promesa    Nullable(Int16),
            cumplio_promesa        Nullable(UInt8),
            subtotal               Decimal(14,2),
            monto_impuesto         Decimal(14,2),
            total                  Decimal(14,2),
            factura_numero         String,
            fecha_factura          Nullable(Date),
            factura_total          Decimal(14,2),
            cxp_monto_original     Decimal(14,2),
            cxp_saldo_pendiente    Decimal(14,2),
            cxp_estado             LowCardinality(String),
            cxp_fecha_vencimiento  Nullable(Date),
            lineas                 UInt16,
            unidades_pedidas       UInt32,
            unidades_recibidas     UInt32,
            unidades_rechazadas    UInt32,
            fecha_carga            DateTime('{ZONA_HORARIA}')
        )
        ENGINE = MergeTree
        PARTITION BY toYYYYMM(fecha_emision)
        ORDER BY (fecha_emision, proveedor_id)
        """

    def columnas(self) -> list[str]:
        return [
            "orden_compra_id", "numero", "proveedor_id", "proveedor", "bodega",
            "estado", "fecha_emision", "mes", "fecha_entrega_esperada",
            "fecha_recepcion", "dias_ciclo_real", "dias_desvio_promesa",
            "cumplio_promesa", "subtotal", "monto_impuesto", "total",
            "factura_numero", "fecha_factura", "factura_total",
            "cxp_monto_original", "cxp_saldo_pendiente", "cxp_estado",
            "cxp_fecha_vencimiento", "lineas", "unidades_pedidas",
            "unidades_recibidas", "unidades_rechazadas", "fecha_carga",
        ]

    # ── Extracción ───────────────────────────────────────────────────────────

    #: El día de la recepción en la zona del negocio. Se aísla como constante y
    #: se usa en las TRES restas de plazo para que la conversión de §8.6 no
    #: pueda aplicarse en un sitio y olvidarse en otro (corrección C3.4).
    _DIA_RECEPCION = f"(r.fecha_recepcion AT TIME ZONE '{ZONA_HORARIA}')::date"

    #: LA RECEPCIÓN CANÓNICA DE LA ORDEN — corrección C6.1 (2026-08-17).
    #:
    #: Una orden puede tener MÁS DE UNA recepción y desde el 2026-08-16 la tiene:
    #: la OC 920 se recibió en dos actos (11 unidades aceptadas y 1 rechazada por
    #: «Caja dañada», y al día siguiente la que faltaba). El dato es correcto y es
    #: el flujo que la aplicación permite —`recepcion_mercancia` no tiene UNIQUE
    #: sobre `orden_compra_id`—; lo que estaba mal era esta consulta, que con un
    #: `LEFT JOIN recepcion_mercancia` devolvía DOS filas para esa orden y rompía
    #: el grano.
    #:
    #: Se toma la ÚLTIMA por `(fecha_recepcion, id)` y no la primera, porque las
    #: unidades que viajan en la fila son el TOTAL recibido (de
    #: `orden_compra_detalle.cantidad_recibida`, la suma de todas las recepciones).
    #: Emparejar ese total con la fecha de la PRIMERA recepción afirmaría que las
    #: 12 unidades estaban en bodega cuando solo había 11. Con la última, la fila
    #: dice: «todo lo que llegó, había llegado en esta fecha».
    #:
    #: Consecuencia declarada para COM-05/COM-06: la promesa se juzga contra la
    #: orden COMPLETA. Un proveedor que entrega el 99 % a tiempo y el resto tarde
    #: cuenta como incumplimiento, que es la lectura estricta de «cumplió el plazo».
    #:
    #: `estado` NO se filtra. El CHECK admite 'anulada', pero hoy las 134.563
    #: recepciones son 'confirmada' (verificado) y los controles cuentan por
    #: EXISTS sin mirar el estado. Si algún día aparece una anulada, el filtro
    #: tiene que entrar AQUÍ Y EN `sql_controles` a la vez, o la extracción y su
    #: cifra de control dejarán de hablar del mismo conjunto.
    _RECEPCION_CANONICA = """
        SELECT rm.id, rm.fecha_recepcion
        FROM recepcion_mercancia rm
        WHERE rm.orden_compra_id = oc.id
        ORDER BY rm.fecha_recepcion DESC, rm.id DESC
        LIMIT 1
    """

    def sql_extraccion(self) -> str:
        """
        Se parte de `orden_compra` y TODO lo demás entra por LEFT JOIN: son 865
        filas pase lo que pase con los documentos aguas abajo (corrección C3.1).

        El LATERAL de agregados va sin `GROUP BY` y devuelve siempre una fila,
        de modo que `LEFT JOIN … ON TRUE` no puede alterar el conteo. Una orden
        sin líneas —hoy no hay ninguna— saldría con ceros, no desaparecería.
        """
        return f"""
        SELECT
            oc.id                                          AS orden_compra_id,
            oc.numero,
            oc.proveedor_id,
            pv.razon_social                                AS proveedor,
            b.nombre                                       AS bodega,
            oc.estado,
            oc.fecha_emision,
            (date_trunc('month', oc.fecha_emision))::date  AS mes,
            oc.fecha_entrega_esperada,
            r.fecha_recepcion,

            -- COM-06: días reales del ciclo, exista o no promesa.
            ({self._DIA_RECEPCION} - oc.fecha_emision)::int          AS dias_ciclo_real,
            -- COM-05: promesa contra llegada. NULL si falta cualquiera de las dos.
            ({self._DIA_RECEPCION} - oc.fecha_entrega_esperada)::int AS dias_desvio_promesa,
            CASE
                WHEN r.id IS NULL OR oc.fecha_entrega_esperada IS NULL THEN NULL
                WHEN {self._DIA_RECEPCION} <= oc.fecha_entrega_esperada THEN 1
                ELSE 0
            END                                            AS cumplio_promesa,

            oc.subtotal,
            oc.monto_impuesto,
            oc.total,

            -- El documento que SÍ mide el gasto (COM-04, GER-02). Ver C3.6.
            COALESCE(fc.numero_factura, '')                AS factura_numero,
            fc.fecha_emision                               AS fecha_factura,
            COALESCE(fc.total, 0)                          AS factura_total,

            COALESCE(cpp.monto_original, 0)                AS cxp_monto_original,
            COALESCE(cpp.saldo_pendiente, 0)               AS cxp_saldo_pendiente,
            COALESCE(cpp.estado, '{SIN_CXP}')              AS cxp_estado,
            cpp.fecha_vencimiento                          AS cxp_fecha_vencimiento,

            agg.lineas,
            agg.unidades_pedidas,
            agg.unidades_recibidas,
            rech.unidades_rechazadas
        FROM orden_compra oc
        JOIN proveedor pv                ON pv.id  = oc.proveedor_id
        JOIN bodega b                    ON b.id   = oc.bodega_id
        LEFT JOIN LATERAL ({self._RECEPCION_CANONICA}) r ON TRUE
        LEFT JOIN factura_compra fc      ON fc.orden_compra_id    = oc.id
        LEFT JOIN cuenta_por_pagar cpp   ON cpp.factura_compra_id = fc.id
        LEFT JOIN LATERAL (
            -- Sin `recepcion_detalle` aquí dentro: una línea puede tener DOS
            -- líneas de recepción y el LEFT JOIN duplicaba la línea de la orden,
            -- inflando `lineas`, `unidades_pedidas` y `unidades_recibidas`
            -- (corrección C6.2). Lo pedido y lo recibido viven en el DETALLE de
            -- la orden, que es 1 fila por línea pase lo que pase.
            SELECT
                count(*)                                   AS lineas,
                COALESCE(SUM(d.cantidad), 0)               AS unidades_pedidas,
                COALESCE(SUM(d.cantidad_recibida), 0)      AS unidades_recibidas
            FROM orden_compra_detalle d
            WHERE d.orden_compra_id = oc.id
        ) agg ON TRUE
        LEFT JOIN LATERAL (
            -- El rechazo sí es del documento de recepción, y se suma sobre TODAS
            -- las recepciones de la orden: es la misma cifra que el control toma
            -- con `SUM(cantidad_rechazada) FROM recepcion_detalle`.
            SELECT COALESCE(SUM(rd.cantidad_rechazada), 0)  AS unidades_rechazadas
            FROM recepcion_detalle rd
            JOIN orden_compra_detalle d2 ON d2.id = rd.orden_compra_detalle_id
            WHERE d2.orden_compra_id = oc.id
        ) rech ON TRUE
        ORDER BY oc.fecha_emision, oc.proveedor_id, oc.id
        """

    def transformar(self, lote: list[tuple]) -> list[tuple]:
        ahora = datetime.now(ZoneInfo(ZONA_HORARIA)).replace(microsecond=0)
        return [tuple(fila) + (ahora,) for fila in lote]

    # ── Controles ────────────────────────────────────────────────────────────

    def sql_controles(self) -> str:
        """
        Las cifras de §9.4 desde el origen, más las tres que la corrección C3.1
        obliga a separar.

        `con_recepcion` y `con_factura` valen 839 las dos y NO son el mismo
        conjunto: `con_ambos` (838) es la única que lo delata. Validar solo las
        dos primeras dejaría pasar un JOIN que pierde o inventa una orden.
        """
        return f"""
        SELECT
            (SELECT count(*) FROM orden_compra)                        AS filas,
            (SELECT count(*) FROM orden_compra oc
             WHERE EXISTS (SELECT 1 FROM recepcion_mercancia r
                           WHERE r.orden_compra_id = oc.id))           AS con_recepcion,
            (SELECT count(*) FROM orden_compra oc
             WHERE EXISTS (SELECT 1 FROM factura_compra f
                           WHERE f.orden_compra_id = oc.id))           AS con_factura,
            (SELECT count(*) FROM orden_compra oc
             WHERE EXISTS (SELECT 1 FROM recepcion_mercancia r
                           WHERE r.orden_compra_id = oc.id)
               AND EXISTS (SELECT 1 FROM factura_compra f
                           WHERE f.orden_compra_id = oc.id))           AS con_ambos,
            (SELECT count(*) FROM orden_compra
             WHERE fecha_entrega_esperada IS NOT NULL)                 AS con_esperada,
            -- Las TRES cifras de plazo van contra la recepción CANÓNICA, la misma
            -- que usa `sql_extraccion` (C6.1). Con un `JOIN recepcion_mercancia`
            -- a secas, la orden con dos recepciones aportaba DOS pares y sumaba
            -- su ciclo dos veces: el control habría culpado a la extracción de un
            -- descuadre que estaba en el propio control. El `JOIN LATERAL … LIMIT 1`
            -- no cambia el criterio —sigue contando solo órdenes con recepción y
            -- sigue exigiendo igualdad EXACTA—, cambia el GRANO.
            (SELECT count(*) FROM orden_compra oc
             JOIN LATERAL ({self._RECEPCION_CANONICA}) r ON TRUE
             WHERE oc.fecha_entrega_esperada IS NOT NULL)              AS pares_comparables,
            (SELECT count(*) FROM orden_compra oc
             JOIN LATERAL ({self._RECEPCION_CANONICA}) r ON TRUE
             WHERE oc.fecha_entrega_esperada IS NOT NULL
               AND (r.fecha_recepcion AT TIME ZONE 'America/Guayaquil')::date
                   <= oc.fecha_entrega_esperada)                       AS cumplieron,
            (SELECT SUM((r.fecha_recepcion AT TIME ZONE 'America/Guayaquil')::date
                        - oc.fecha_emision)
             FROM orden_compra oc
             JOIN LATERAL ({self._RECEPCION_CANONICA}) r ON TRUE)      AS suma_dias_ciclo,
            (SELECT SUM(total) FROM orden_compra)                      AS total_ordenes,
            (SELECT SUM(total) FROM factura_compra)                    AS total_facturas,
            (SELECT SUM(monto_original) FROM cuenta_por_pagar)         AS cxp_original,
            (SELECT SUM(saldo_pendiente) FROM cuenta_por_pagar)        AS cxp_saldo,
            (SELECT count(*) FROM orden_compra_detalle)                AS lineas,
            (SELECT SUM(cantidad) FROM orden_compra_detalle)           AS unidades_pedidas,
            (SELECT SUM(cantidad_recibida) FROM orden_compra_detalle)  AS unidades_recibidas,
            (SELECT SUM(cantidad_rechazada) FROM recepcion_detalle)    AS unidades_rechazadas,
            (SELECT count(DISTINCT date_trunc('month', fecha_emision))
             FROM orden_compra)                                        AS meses,
            (SELECT count(DISTINCT estado) FROM orden_compra)          AS estados,
            (SELECT count(*) FROM orden_compra WHERE estado = 'aprobada') AS estado_aprobada
        """

    #: Pares (cifra de control del origen, expresión equivalente en ClickHouse).
    #: El comparador es genérico: añadir una cifra es añadir una línea aquí y su
    #: gemela en `sql_controles`.
    _EQUIVALENCIAS = (
        ("con_recepcion",       "countIf(fecha_recepcion IS NOT NULL)"),
        ("con_factura",         "countIf(factura_numero != '')"),
        ("con_ambos",           "countIf(fecha_recepcion IS NOT NULL AND factura_numero != '')"),
        ("con_esperada",        "countIf(fecha_entrega_esperada IS NOT NULL)"),
        ("pares_comparables",   "countIf(dias_desvio_promesa IS NOT NULL)"),
        ("cumplieron",          "countIf(cumplio_promesa = 1)"),
        ("suma_dias_ciclo",     "sum(ifNull(dias_ciclo_real, 0))"),
        ("total_ordenes",       "sum(total)"),
        ("total_facturas",      "sum(factura_total)"),
        ("cxp_original",        "sum(cxp_monto_original)"),
        ("cxp_saldo",           "sum(cxp_saldo_pendiente)"),
        ("lineas",              "sum(lineas)"),
        ("unidades_pedidas",    "sum(unidades_pedidas)"),
        ("unidades_recibidas",  "sum(unidades_recibidas)"),
        ("unidades_rechazadas", "sum(unidades_rechazadas)"),
        ("meses",               "countDistinct(mes)"),
        ("estados",             "countDistinct(estado)"),
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

        errores += self._validar_grano(client, tabla_staging)
        errores += self._validar_coherencia(client, tabla_staging, controles)
        return errores

    def _validar_grano(self, client, tabla_staging: str) -> list[str]:
        """
        El grano es UNA orden de compra. Si alguno de los tres LEFT JOIN hiciera
        fan-out —dos recepciones, dos facturas, dos CxP para la misma factura—,
        `count(*)` subiría y este control lo nombra antes de que el conteo
        general lo cuente como «una orden nueva».
        """
        total, ids = client.query(f"""
            SELECT count(), countDistinct(orden_compra_id) FROM {tabla_staging}
        """).result_rows[0]
        if ids != total:
            return [f"El grano se rompió: {total} filas pero {ids} órdenes distintas. "
                    f"Alguno de los LEFT JOIN (recepción / factura / CxP) duplicó."]
        return []

    def _validar_coherencia(self, client, tabla_staging: str, controles: dict) -> list[str]:
        """
        Tres invariantes que las cifras sueltas no cubren.
        """
        errores = []
        fila = client.query(f"""
            SELECT
                countIf(dias_ciclo_real < 0),
                countIf(fecha_recepcion IS NULL AND dias_ciclo_real IS NOT NULL),
                countIf(cumplio_promesa IS NOT NULL AND dias_desvio_promesa IS NULL),
                countIf(estado = 'aprobada'),
                countIf(cxp_estado = '{SIN_CXP}'),
                countIf(factura_numero = '' AND factura_total != 0)
            FROM {tabla_staging}
        """).result_rows[0]
        (ciclo_negativo, ciclo_sin_recepcion, veredicto_sin_desvio,
         estado_aprobada, sin_cxp, factura_fantasma) = fila

        if ciclo_negativo:
            errores.append(
                f"{ciclo_negativo} órdenes con `dias_ciclo_real` NEGATIVO: la "
                f"recepción es anterior a la emisión. Hoy el mínimo real es 0; si "
                f"aparece un negativo, la conversión de zona horaria (C3.4) es la "
                f"primera sospechosa."
            )
        if ciclo_sin_recepcion:
            errores.append(f"{ciclo_sin_recepcion} órdenes sin recepción tienen "
                           f"`dias_ciclo_real` calculado.")
        if veredicto_sin_desvio:
            errores.append(
                f"{veredicto_sin_desvio} órdenes tienen veredicto `cumplio_promesa` "
                f"sin desvío medible. Sin recepción o sin promesa NO hay veredicto: "
                f"un 0 diría «incumplió», que no es «no se sabe»."
            )
        # §5.4 lo declara y el sistema lo cumple: aprobar deja la orden en
        # 'confirmada'. Si apareciera 'aprobada', el flujo de compras cambió y
        # los filtros de estado de COM-01 y COM-04 se quedaron viejos.
        if estado_aprobada or int(controles["estado_aprobada"]):
            errores.append(
                f"Apareció el estado 'aprobada' ({estado_aprobada} filas): §5.4 "
                f"declara que aprobar deja la orden en 'confirmada' y que ese "
                f"estado NO existe. El ciclo de compra cambió."
            )
        # Espejo de C3.1 medido en el destino, no en el origen.
        esperado_sin_cxp = int(controles["filas"]) - int(controles["con_factura"])
        if sin_cxp != esperado_sin_cxp:
            errores.append(
                f"Órdenes con cxp_estado '{SIN_CXP}': {sin_cxp} en destino vs "
                f"{esperado_sin_cxp} esperadas (865 − las que tienen factura). "
                f"La cadena factura → cuenta por pagar dejó de ser 1:1."
            )
        if factura_fantasma:
            errores.append(f"{factura_fantasma} órdenes sin número de factura traen "
                           f"un `factura_total` distinto de cero.")
        return errores
