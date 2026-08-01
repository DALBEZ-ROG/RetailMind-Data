"""
etl/dwh/tablas/fact_pedido.py — F1 del modelo (§5.1 del diseño).

La venta vista POR DOCUMENTO. Grano: un pedido. 4.083 filas.
Es la tabla más solicitada del modelo — 8 informes: OTD-VEN-05, VEN-07, VEN-13,
VEN-14 (denominador), LOG-12, GER-02 (devengado), GER-05 y GER-11.

═══════════════════════════════════════════════════════════════════════════════
1. EL PIVOTE DE HITOS — de 24.608 transiciones a una fila por pedido
═══════════════════════════════════════════════════════════════════════════════

`historial_estado_pedido` guarda una fila por transición. LOG-12 no pregunta por
transiciones sino por PEDIDOS («cuántas horas tarda un pedido del pago a la
preparación»), así que los hitos se pivotan aquí con `min(fecha) FILTER (…)` y
la tabla larga desaparece del modelo (§5.1, decisión declarada).

`min` y no `max`: hay pedidos con el MISMO hito registrado dos veces —verificado
hoy: 19 pedidos repiten 'confirmado', 8 repiten 'pagado' y 5 repiten
'despachado'—. El primer registro es cuándo ocurrió el hecho; el segundo es una
reescritura del ciclo. Con `max` los tramos saldrían artificialmente largos en
esos pedidos y nadie lo notaría en un promedio.

COBERTURA REAL, medida hoy sobre los 4.083 pedidos (no estimada):

    confirmado ....... 4.083   (todos: el pedido nace confirmado)
    pagado ........... 3.906
    facturado ........ 3.872
    en_preparacion ... 2.883
    preparado ........ 2.868
    despachado ....... 2.868
    entregado ........ 3.696

Y los TRAMOS completos, que es lo que LOG-12 promedia:

    pago → preparado ......... 2.868
    preparado → despachado ... 2.856
    despachado → entregado ... 2.727
    ciclo total (pago→entrega) 3.696

**El desnivel entre 3.696 entregados y 2.868 despachados es real y hay que
decirlo**: 828 pedidos llegaron a 'entregado' sin que el seed les registrara el
paso por 'despachado'. Por eso el tramo despacho→entrega promedia sobre 2.727
pedidos y no sobre 3.696, y por eso cada tramo del informe declara SU propia
base. Un promedio de tramo calculado sobre un denominador distinto al del tramo
vecino, presentado sin decirlo, es la manera silenciosa de que un cuello de
botella parezca estar donde no está.

Verificado además que ningún hito va hacia atrás (0 pedidos con preparado antes
del pago, despacho antes de preparado o entrega antes del despacho), así que
ningún tramo sale negativo.

Sobre los NOMBRES de los tramos: «preparación» en `horas_pago_a_preparacion` y
`horas_preparacion_a_despacho` significa el hito **'preparado'** (picking
terminado), de modo que un tramo empieza exactamente donde acaba el anterior y
los tres suman el ciclo. `fecha_en_preparacion` viaja igualmente como columna
—es un hito real del ciclo y lo tienen 2.883 pedidos— para que quien quiera
separar «espera en la cola de bodega» de «tiempo de picking» pueda hacerlo sin
volver a PostgreSQL.

═══════════════════════════════════════════════════════════════════════════════
2. LA FACTURA CANÓNICA — el mismo hallazgo de la Fase 1, y por qué se repite
═══════════════════════════════════════════════════════════════════════════════

`factura_venta` NO es 1:1 con el pedido. Cifras de hoy:

    facturas totales .................. 3.887
    facturas no anuladas .............. 3.886
    pedidos con factura canónica ...... 3.885   ← 3.886 ≠ 3.885

El pedido 2 tiene DOS facturas 'emitida' (FV-20260704-02744 y FV-20260705-88152,
$402,16 ambas: duplicado legacy). Unir por `estado <> 'anulada'` sin más
duplicaría ESA cabecera y `fact_pedido` saldría con 4.084 filas — en una tabla
de 4.083, un +1 es justo el error que se cuela. Se aplica el mismo criterio que
la Fase 1 fijó para `fact_venta_linea`: una factura canónica por pedido, la no
anulada más reciente por `(fecha_emision, id)`. Cubre los dos casos reales, el
duplicado idéntico (pedido 2) y la reemisión tras anulación (pedido 22).

═══════════════════════════════════════════════════════════════════════════════
3. EL CUPÓN: `monto_cupon` Y `monto_descuento` NO SON LA MISMA CIFRA
═══════════════════════════════════════════════════════════════════════════════

    uso_cupon ....................... 564 filas / 564 pedidos distintos ⇒ 1:1
    Σ uso_cupon.monto_descontado .... $50.727,89
    pedidos con monto_descuento > 0 . 562
    Σ pedido.monto_descuento ........ $50.590,25

La diferencia son los **pedidos 20 y 21**, legacy: tienen su fila en `uso_cupon`
pero el script 72 no les tocó la cabecera porque no tienen fila en `pago` (la
excepción ya declarada en `CLAUDE.md`). Las dos columnas viajan por separado y
NO se reconcilian: `monto_cupon` es lo que el cupón dice haber descontado y
`monto_descuento` es lo que el pedido efectivamente descontó. Igualarlas —o
cargar solo una— tomaría partido en silencio sobre dos pedidos reales.

OTD-GER-05 («qué cupones se usaron y cuánto costaron») se sirve de
`monto_cupon`, que es la pregunta que hace; el dinero del pedido sigue siendo
`total`, que ya trae aplicado lo que el sistema aplicó.

═══════════════════════════════════════════════════════════════════════════════
4. LOS TRAMOS EN Decimal Y NO EN Float32 — desviación declarada del §5.1
═══════════════════════════════════════════════════════════════════════════════

El diseño especifica `Nullable(Float32)` para las cuatro columnas de horas. Se
cargan como `Nullable(Decimal(12,2))` por una razón operativa: el criterio de
aceptación del proyecto es la igualdad EXACTA contra PostgreSQL, y el
comparador de `validar_dwh.py` rechaza cualquier float por diseño («pasar por
float es exactamente el error que este script existe para detectar»). Con
Float32 los tramos serían la única medida de la tabla imposible de validar al
centavo. No son dinero, pero sí son una medida que se promedia y se compara
entre motores, y ese es el requisito que manda aquí.

═══════════════════════════════════════════════════════════════════════════════
5. GEOGRAFÍA DE ENTREGA: 20 pedidos sin dirección
═══════════════════════════════════════════════════════════════════════════════

4.063 de 4.083 pedidos tienen `direccion_envio_id`; los 20 restantes son legacy
(ids bajos, del arranque del sistema). Resuelven a `'sin_direccion'` en vez de a
NULL: la columna es `LowCardinality` y se agrupa en los informes, y un NULL en
un `GROUP BY` se lee como una zona más.

El `mes` se calcula en PostgreSQL (`date_trunc(… AT TIME ZONE …)`) y viaja
resuelto, igual que en `fact_venta_linea`: es la defensa contra §8.6.
"""

from datetime import datetime
from zoneinfo import ZoneInfo

from etl.dwh.conexiones import ZONA_HORARIA
from etl.dwh.tarea import TareaCarga

ESTADO_CANCELADO = "cancelado"

#: Vendedor de un pedido nacido en el checkout del cliente. `pedido.vendedor_id`
#: es NULL a propósito en el canal 'web' (script 42): el autor es el CLIENTE, y
#: la trazabilidad va por `cliente_id` + historial. Verificado: los 2.213
#: pedidos web tienen vendedor NULL y los 1.870 internos lo tienen poblado.
VENDEDOR_ONLINE = "(canal en línea)"


class FactPedido(TareaCarga):

    nombre = "fact_pedido"
    depende_de = ("dim_cliente",)

    # ── Definición ───────────────────────────────────────────────────────────

    def ddl(self, nombre_tabla: str) -> str:
        return f"""
        CREATE TABLE {nombre_tabla}
        (
            pedido_id                     UInt32,
            numero                        String,
            fecha_pedido                  DateTime('{ZONA_HORARIA}'),
            mes                           Date,
            cliente_id                    UInt32,
            canal                         LowCardinality(String),
            estado                        LowCardinality(String),
            es_cancelado                  UInt8,
            vendedor                      LowCardinality(String),
            subtotal                      Decimal(14,2),
            monto_descuento               Decimal(14,2),
            monto_impuesto                Decimal(14,2),
            costo_envio                   Decimal(14,2),
            total                         Decimal(14,2),
            lineas                        UInt16,
            unidades                      UInt32,
            codigo_cupon                  LowCardinality(String),
            monto_cupon                   Decimal(14,2),
            factura_numero                String,
            factura_total                 Decimal(14,2),
            fecha_factura                 Nullable(DateTime('{ZONA_HORARIA}')),
            fecha_pagado                  Nullable(DateTime('{ZONA_HORARIA}')),
            fecha_facturado               Nullable(DateTime('{ZONA_HORARIA}')),
            fecha_en_preparacion          Nullable(DateTime('{ZONA_HORARIA}')),
            fecha_preparado               Nullable(DateTime('{ZONA_HORARIA}')),
            fecha_despachado              Nullable(DateTime('{ZONA_HORARIA}')),
            fecha_entregado               Nullable(DateTime('{ZONA_HORARIA}')),
            horas_pago_a_preparacion      Nullable(Decimal(12,2)),
            horas_preparacion_a_despacho  Nullable(Decimal(12,2)),
            horas_despacho_a_entrega      Nullable(Decimal(12,2)),
            horas_ciclo_total             Nullable(Decimal(12,2)),
            ciudad_entrega                LowCardinality(String),
            provincia_entrega             LowCardinality(String),
            fecha_carga                   DateTime('{ZONA_HORARIA}')
        )
        ENGINE = MergeTree
        PARTITION BY toYYYYMM(fecha_pedido)
        ORDER BY (fecha_pedido, canal, cliente_id)
        """

    def columnas(self) -> list[str]:
        return [
            "pedido_id", "numero", "fecha_pedido", "mes", "cliente_id", "canal",
            "estado", "es_cancelado", "vendedor",
            "subtotal", "monto_descuento", "monto_impuesto", "costo_envio", "total",
            "lineas", "unidades", "codigo_cupon", "monto_cupon",
            "factura_numero", "factura_total", "fecha_factura",
            "fecha_pagado", "fecha_facturado", "fecha_en_preparacion",
            "fecha_preparado", "fecha_despachado", "fecha_entregado",
            "horas_pago_a_preparacion", "horas_preparacion_a_despacho",
            "horas_despacho_a_entrega", "horas_ciclo_total",
            "ciudad_entrega", "provincia_entrega", "fecha_carga",
        ]

    # ── Extracción ───────────────────────────────────────────────────────────

    #: Los tres CTE que definen las uniones no triviales. Se escriben UNA vez y
    #: se reutilizan en extracción y en controles: si la regla de la factura
    #: canónica cambiara solo en uno de los dos, la validación daría por buena
    #: una tabla mal cargada.
    _CTES = """
        factura_canonica AS (
            -- Ver el bloque 2 de la cabecera: `factura_venta` NO es 1:1 con el
            -- pedido. Una por pedido, la no anulada más reciente.
            SELECT DISTINCT ON (fv.pedido_id)
                   fv.pedido_id, fv.numero, fv.total, fv.fecha_emision
            FROM factura_venta fv
            WHERE fv.estado <> 'anulada'
            ORDER BY fv.pedido_id, fv.fecha_emision DESC, fv.id DESC
        ),
        agregado_linea AS (
            SELECT pedido_id, count(*) AS lineas, SUM(cantidad) AS unidades
            FROM pedido_detalle
            GROUP BY pedido_id
        ),
        hitos AS (
            -- El pivote: 24.608 transiciones → una fila por pedido.
            -- `min` y no `max`: hay hitos repetidos (ver bloque 1).
            SELECT
                h.pedido_id,
                min(h.fecha_creacion) FILTER (WHERE ep.codigo = 'pagado')         AS f_pagado,
                min(h.fecha_creacion) FILTER (WHERE ep.codigo = 'facturado')      AS f_facturado,
                min(h.fecha_creacion) FILTER (WHERE ep.codigo = 'en_preparacion') AS f_en_preparacion,
                min(h.fecha_creacion) FILTER (WHERE ep.codigo = 'preparado')      AS f_preparado,
                min(h.fecha_creacion) FILTER (WHERE ep.codigo = 'despachado')     AS f_despachado,
                min(h.fecha_creacion) FILTER (WHERE ep.codigo = 'entregado')      AS f_entregado
            FROM historial_estado_pedido h
            JOIN estado_pedido ep ON ep.id = h.estado_pedido_id
            GROUP BY h.pedido_id
        )
    """

    #: Horas entre dos instantes, redondeadas a dos decimales. Se define como
    #: fragmento porque aparece cuatro veces y un paréntesis mal puesto en una
    #: sola de ellas daría un tramo plausible y equivocado.
    @staticmethod
    def _horas(desde: str, hasta: str) -> str:
        return (f"ROUND((EXTRACT(EPOCH FROM ({hasta} - {desde})) / 3600.0)::numeric, 2)")

    def sql_extraccion(self) -> str:
        h = self._horas
        return f"""
        WITH {self._CTES}
        SELECT
            p.id                                               AS pedido_id,
            p.numero,
            p.fecha_pedido,
            (date_trunc('month',
                p.fecha_pedido AT TIME ZONE '{ZONA_HORARIA}'))::date AS mes,
            p.cliente_id,
            p.canal,
            ep.codigo                                          AS estado,
            CASE WHEN ep.codigo = '{ESTADO_CANCELADO}' THEN 1 ELSE 0 END AS es_cancelado,
            -- El pedido web no tiene vendedor POR DISEÑO, no por falta de dato.
            -- Etiquetarlo evita que el informe por vendedor muestre un hueco que
            -- se lea como «vendedor sin identificar».
            COALESCE(TRIM(u.nombre || ' ' || COALESCE(u.apellido, '')),
                     '{VENDEDOR_ONLINE}')                      AS vendedor,
            p.subtotal,
            p.monto_descuento,
            p.monto_impuesto,
            p.costo_envio,
            p.total,
            COALESCE(al.lineas, 0)                             AS lineas,
            COALESCE(al.unidades, 0)                           AS unidades,
            COALESCE(cu.codigo, '')                            AS codigo_cupon,
            COALESCE(uc.monto_descontado, 0)                   AS monto_cupon,
            COALESCE(fc.numero, '')                            AS factura_numero,
            COALESCE(fc.total, 0)                              AS factura_total,
            fc.fecha_emision                                   AS fecha_factura,
            hi.f_pagado,
            hi.f_facturado,
            hi.f_en_preparacion,
            hi.f_preparado,
            hi.f_despachado,
            hi.f_entregado,
            {h('hi.f_pagado', 'hi.f_preparado')}               AS horas_pago_a_preparacion,
            {h('hi.f_preparado', 'hi.f_despachado')}           AS horas_preparacion_a_despacho,
            {h('hi.f_despachado', 'hi.f_entregado')}           AS horas_despacho_a_entrega,
            {h('hi.f_pagado', 'hi.f_entregado')}               AS horas_ciclo_total,
            COALESCE(ci.nombre, 'sin_direccion')               AS ciudad_entrega,
            COALESCE(pv.nombre, 'sin_direccion')               AS provincia_entrega
        FROM pedido p
        JOIN estado_pedido ep      ON ep.id = p.estado_pedido_id
        LEFT JOIN usuario u        ON u.id  = p.vendedor_id
        LEFT JOIN agregado_linea al ON al.pedido_id = p.id
        LEFT JOIN uso_cupon uc     ON uc.pedido_id = p.id
        LEFT JOIN cupon cu         ON cu.id = uc.cupon_id
        LEFT JOIN factura_canonica fc ON fc.pedido_id = p.id
        LEFT JOIN hitos hi         ON hi.pedido_id = p.id
        LEFT JOIN direccion d      ON d.id  = p.direccion_envio_id
        LEFT JOIN ciudad ci        ON ci.id = d.ciudad_id
        LEFT JOIN provincia pv     ON pv.id = ci.provincia_id
        ORDER BY p.id
        """

    def transformar(self, lote: list[tuple]) -> list[tuple]:
        ahora = datetime.now(ZoneInfo(ZONA_HORARIA)).replace(microsecond=0)
        return [tuple(fila) + (ahora,) for fila in lote]

    # ── Controles ────────────────────────────────────────────────────────────

    def sql_controles(self) -> str:
        """
        Las cifras de §9.3 tomadas del ORIGEN en la misma corrida.

        `uso_cupon` entra con un LEFT JOIN igual que en la extracción: si algún
        día dejara de ser 1:1 con el pedido, este control contaría más filas que
        pedidos y el conteo abortaría la carga — que es exactamente lo que debe
        pasar antes de publicar una cabecera duplicada.
        """
        return f"""
        WITH {self._CTES},
        base AS (
            SELECT
                p.id, p.canal, p.total, p.monto_descuento, p.costo_envio,
                p.subtotal, p.monto_impuesto,
                CASE WHEN ep.codigo = '{ESTADO_CANCELADO}' THEN 1 ELSE 0 END AS cancelado,
                COALESCE(al.lineas, 0)   AS lineas,
                COALESCE(al.unidades, 0) AS unidades,
                COALESCE(uc.monto_descontado, 0) AS monto_cupon,
                (uc.pedido_id IS NOT NULL) AS con_cupon,
                (fc.pedido_id IS NOT NULL) AS con_factura,
                COALESCE(fc.total, 0)    AS factura_total,
                hi.f_pagado, hi.f_facturado, hi.f_en_preparacion,
                hi.f_preparado, hi.f_despachado, hi.f_entregado,
                (date_trunc('month',
                    p.fecha_pedido AT TIME ZONE '{ZONA_HORARIA}'))::date AS mes
            FROM pedido p
            JOIN estado_pedido ep       ON ep.id = p.estado_pedido_id
            LEFT JOIN agregado_linea al ON al.pedido_id = p.id
            LEFT JOIN uso_cupon uc      ON uc.pedido_id = p.id
            LEFT JOIN factura_canonica fc ON fc.pedido_id = p.id
            LEFT JOIN hitos hi          ON hi.pedido_id = p.id
        )
        SELECT
            count(*)                                          AS filas,
            count(*) FILTER (WHERE cancelado = 0)             AS no_cancelados,
            SUM(total)                                        AS total_todos,
            SUM(total) FILTER (WHERE cancelado = 0)           AS total_no_cancelados,
            SUM(subtotal)                                     AS subtotal,
            SUM(monto_descuento)                              AS monto_descuento,
            SUM(monto_impuesto)                               AS monto_impuesto,
            SUM(costo_envio)                                  AS costo_envio,
            SUM(lineas)                                       AS lineas,
            SUM(unidades)                                     AS unidades,
            count(*) FILTER (WHERE canal = 'web')             AS canal_web,
            count(*) FILTER (WHERE canal = 'tienda')          AS canal_tienda,
            count(*) FILTER (WHERE canal = 'telefono')        AS canal_telefono,
            count(*) FILTER (WHERE canal = 'web' AND cancelado = 0)      AS web_vivos,
            count(*) FILTER (WHERE canal = 'tienda' AND cancelado = 0)   AS tienda_vivos,
            count(*) FILTER (WHERE canal = 'telefono' AND cancelado = 0) AS telefono_vivos,
            count(*) FILTER (WHERE con_cupon)                 AS con_cupon,
            SUM(monto_cupon)                                  AS monto_cupon,
            count(*) FILTER (WHERE con_factura)               AS con_factura,
            SUM(factura_total)                                AS factura_total,
            count(f_pagado)                                   AS hito_pagado,
            count(f_facturado)                                AS hito_facturado,
            count(f_en_preparacion)                           AS hito_en_preparacion,
            count(f_preparado)                                AS hito_preparado,
            count(f_despachado)                               AS hito_despachado,
            count(f_entregado)                                AS hito_entregado,
            count(*) FILTER (WHERE f_pagado IS NOT NULL
                               AND f_preparado IS NOT NULL)   AS tramo_pago_prep,
            count(*) FILTER (WHERE f_preparado IS NOT NULL
                               AND f_despachado IS NOT NULL)  AS tramo_prep_desp,
            count(*) FILTER (WHERE f_despachado IS NOT NULL
                               AND f_entregado IS NOT NULL)   AS tramo_desp_entrega,
            count(*) FILTER (WHERE f_pagado IS NOT NULL
                               AND f_entregado IS NOT NULL)   AS ciclo_total,
            COUNT(DISTINCT mes)                               AS meses
        FROM base
        """

    #: Pares {clave del control ← consulta equivalente en ClickHouse}. Se
    #: declara como tabla y no como un SELECT gigante para que añadir una cifra
    #: de control sea una línea y no una reescritura.
    _EQUIVALENCIAS = (
        ("no_cancelados",       "countIf(es_cancelado = 0)"),
        ("total_todos",         "sum(total)"),
        ("total_no_cancelados", "sumIf(total, es_cancelado = 0)"),
        ("subtotal",            "sum(subtotal)"),
        ("monto_descuento",     "sum(monto_descuento)"),
        ("monto_impuesto",      "sum(monto_impuesto)"),
        ("costo_envio",         "sum(costo_envio)"),
        ("lineas",              "sum(lineas)"),
        ("unidades",            "sum(unidades)"),
        ("canal_web",           "countIf(canal = 'web')"),
        ("canal_tienda",        "countIf(canal = 'tienda')"),
        ("canal_telefono",      "countIf(canal = 'telefono')"),
        ("web_vivos",           "countIf(canal = 'web' AND es_cancelado = 0)"),
        ("tienda_vivos",        "countIf(canal = 'tienda' AND es_cancelado = 0)"),
        ("telefono_vivos",      "countIf(canal = 'telefono' AND es_cancelado = 0)"),
        ("con_cupon",           "countIf(codigo_cupon != '')"),
        ("monto_cupon",         "sum(monto_cupon)"),
        ("con_factura",         "countIf(factura_numero != '')"),
        ("factura_total",       "sum(factura_total)"),
        ("hito_pagado",         "countIf(fecha_pagado IS NOT NULL)"),
        ("hito_facturado",      "countIf(fecha_facturado IS NOT NULL)"),
        ("hito_en_preparacion", "countIf(fecha_en_preparacion IS NOT NULL)"),
        ("hito_preparado",      "countIf(fecha_preparado IS NOT NULL)"),
        ("hito_despachado",     "countIf(fecha_despachado IS NOT NULL)"),
        ("hito_entregado",      "countIf(fecha_entregado IS NOT NULL)"),
        ("tramo_pago_prep",     "countIf(horas_pago_a_preparacion IS NOT NULL)"),
        ("tramo_prep_desp",     "countIf(horas_preparacion_a_despacho IS NOT NULL)"),
        ("tramo_desp_entrega",  "countIf(horas_despacho_a_entrega IS NOT NULL)"),
        ("ciclo_total",         "countIf(horas_ciclo_total IS NOT NULL)"),
        ("meses",               "countDistinct(mes)"),
    )

    def validar(self, client, tabla_staging: str, controles: dict) -> list[str]:
        """
        Igualdad EXACTA cifra a cifra, más el control mes a mes. Un centavo de
        diferencia es un bug de tipo; un pedido de más es un JOIN con fan-out.
        """
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

        errores += self._validar_mes_a_mes(client, tabla_staging)
        errores += self._validar_unicidad(client, tabla_staging)
        return errores

    def _validar_unicidad(self, client, tabla_staging: str) -> list[str]:
        """
        El grano es UN pedido. Un `count(*)` correcto por casualidad —una fila de
        más y otra de menos— pasaría la validación de conteo; los ids distintos,
        no. Es el control que atrapa el fan-out de `uso_cupon` o de la factura.
        """
        total, ids, numeros = client.query(
            f"SELECT count(), countDistinct(pedido_id), countDistinct(numero) "
            f"FROM {tabla_staging}"
        ).result_rows[0]
        errores = []
        if ids != total:
            errores.append(f"El grano se rompió: {total} filas pero {ids} pedidos "
                           f"distintos (fan-out en uso_cupon o en factura_venta).")
        if numeros != total:
            errores.append(f"{total} filas pero {numeros} números de pedido distintos.")
        return errores

    def _validar_mes_a_mes(self, client, tabla_staging: str) -> list[str]:
        """
        El control mes a mes de §9.2 en su hogar definitivo: pedidos NO
        cancelados y `sum(total)` por mes — 3.924 pedidos y $5.498.570,35 en 19
        meses. Hasta ahora lo sostenía `fact_venta_linea.pedido_total`
        colapsando el grano; desde esta fase lo sostiene la tabla de cabecera,
        que es donde la medida vive de verdad.
        """
        from etl.dwh.conexiones import get_pg_connection

        conn = get_pg_connection()
        try:
            with conn.cursor() as cur:
                cur.execute(f"""
                    SELECT (date_trunc('month',
                               p.fecha_pedido AT TIME ZONE '{ZONA_HORARIA}'))::date,
                           COUNT(*), SUM(p.total)
                    FROM pedido p
                    JOIN estado_pedido ep ON ep.id = p.estado_pedido_id
                    WHERE ep.codigo <> '{ESTADO_CANCELADO}'
                    GROUP BY 1 ORDER BY 1
                """)
                origen = {f[0]: (f[1], f[2]) for f in cur.fetchall()}
        finally:
            conn.close()

        destino = {
            f[0]: (f[1], f[2])
            for f in client.query(f"""
                SELECT mes, count(), sum(total) FROM {tabla_staging}
                WHERE es_cancelado = 0 GROUP BY mes ORDER BY mes
            """).result_rows
        }

        errores = []
        if set(origen) != set(destino):
            errores.append(
                f"Los meses no coinciden — solo en PG "
                f"{sorted(set(origen) - set(destino))}, solo en CH "
                f"{sorted(set(destino) - set(origen))}. Síntoma de zona horaria (§8.6)."
            )
            return errores

        for mes in sorted(origen):
            pg_pedidos, pg_total = origen[mes]
            ch_pedidos, ch_total = destino[mes]
            if pg_pedidos != ch_pedidos or type(pg_total)(ch_total) != pg_total:
                errores.append(
                    f"Mes {mes}: PostgreSQL ({pg_pedidos} pedidos, {pg_total}) "
                    f"vs ClickHouse ({ch_pedidos}, {ch_total})."
                )
        return errores
