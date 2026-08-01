"""
etl/dwh/tablas/fact_venta_linea.py — F2 del modelo (§5.2 del diseño).

La venta vista POR PRODUCTO. Grano: una línea de pedido. 10.384 filas.
Es la tabla del margen y del descuento, y sirve 7 informes compuestos
(VEN-03, VEN-04, VEN-06, GER-03, GER-07, GER-10, GER-11).

═══════════════════════════════════════════════════════════════════════════════
LAS DOS CAPAS DE DESCUENTO — el punto delicado de todo el piloto
═══════════════════════════════════════════════════════════════════════════════

Los scripts 72-73 escriben el descuento en TRES lugares distintos:

    pedido.monto_descuento               → el CUPÓN, en la cabecera
    pedido_detalle.monto_descuento       → la PROMOCIÓN, en la línea
    factura_venta_detalle.monto_descuento→ las DOS capas ya prorrateadas al
                                           producto (`emitirFactura` reparte el
                                           cupón entre líneas)

De ahí sale la identidad que permite despejar la porción de cupón por línea,
que es justo lo que pide OTD-GER-11 («descuento por mes Y POR PRODUCTO»):

    descuento_cupon_prorrateado = factura_venta_detalle.monto_descuento
                                − pedido_detalle.monto_descuento

═══════════════════════════════════════════════════════════════════════════════
DOS HALLAZGOS DE ESTA FASE QUE EL DISEÑO EN PAPEL NO TENÍA (2026-07-30)
═══════════════════════════════════════════════════════════════════════════════

(1) LA FACTURA CANÓNICA, o el join que habría inflado la tabla en 2 filas.

    `factura_venta` NO es 1:1 con el pedido. El pedido 2 tiene DOS facturas
    ambas en estado 'emitida' (FV-20260704-02744 y FV-20260705-88152, idénticas
    en importes: duplicado legacy), y sus líneas aparecen dos veces en
    `factura_venta_detalle`. Filtrar solo por `estado <> 'anulada'` —que es lo
    que dice el diseño— produce 10.386 filas donde hay 10.384, y ese +2 es
    exactamente la clase de error que el criterio «igualdad al centavo» existe
    para atrapar.

    Regla aplicada: por pedido se toma UNA factura canónica, la no anulada más
    reciente por (fecha_emision, id). Cubre los dos casos reales — la reemisión
    tras anulación (24662) y el duplicado idéntico (pedido 2)— y se verificó
    que devuelve exactamente 10.384 líneas, 9.874 de ellas con factura.

(2) LAS 6 EXCEPCIONES NO SON LAS QUE EL DISEÑO SUPONÍA.

    El diseño anticipó que los 6 pedidos que no cuadran serían «20, 21 y el
    24662». No lo son. Verificado, los 6 son pedidos CON descuento y SIN
    ninguna factura vigente de la que prorratear:

        40    PED-20260715-79957   telefono  pagado   promoción $3,49
        4031  PED-20260709-111677  web       pagado   cupón    $147,73
        4078  PED-20260705-111786  web       pagado   cupón     $11,28
        4106  PED-20260720-111841  web       pagado   cupón      $5,00
        4161  PED-20260715-111969  web       pagado   cupón     $64,03
        4176  PED-20260720-112000  web       pagado   cupón     $29,22

    Todos quedaron en estado 'pagado' sin llegar a 'facturado', así que no hay
    `factura_venta_detalle` donde leer el reparto. No es un error del ETL ni de
    los datos: es un pedido a medio camino del ciclo.

    Tratamiento (el que pide §5.2, y la razón de que exista el contador de la
    bitácora): `descuento_cupon_prorrateado = 0`, la fila se MARCA con
    `excepcion_descuento = 1` —de modo que la excepción quede consultable en el
    propio almacén, no solo contada— y el número de pedidos afectados se
    registra en `etl_ejecucion.excepciones`. No se silencian y no se descartan.

═══════════════════════════════════════════════════════════════════════════════
`pedido_total`: COLUMNA RETIRADA EN LA FASE 2 (2026-07-30)
═══════════════════════════════════════════════════════════════════════════════

Durante la Fase 1 esta tabla llevó una columna `pedido_total` (= `pedido.total`)
que era un ATRIBUTO DEGENERADO de cabecera: viajaba repetida en cada línea del
pedido y sumarla a grano de línea la contaba 2,5 veces. Existía por una razón
concreta y temporal: el criterio de aceptación del piloto (§9.2) incluye el
control mes a mes de **pedidos no cancelados y total** —3.924 pedidos /
$5.498.570,35— y, sin `fact_pedido`, la Fase 1 no tenía forma de validarse a sí
misma contra PostgreSQL.

Con `fact_pedido` cargada (Fase 2), esa medida tiene su hogar canónico y la
columna se retiró: el control mes a mes se reapuntó a la tabla de cabecera
—donde se lee sin colapsar ningún grano— tanto en `validar_dwh.py` como en la
validación de la propia tarea. Se comprobó antes de quitarla que **ningún
informe la leía** (búsqueda en `retailmind-backend/src` y
`retailmind-frontend/src`: cero apariciones), y que los cuatro controles de la
Fase 1 siguen cuadrando exactamente después de retirarla.

Mantenerla habría dejado en el modelo la invitación permanente a escribir
`sum(pedido_total)` sobre 10.384 líneas y obtener un ingreso inflado 2,5 veces
que parece plausible. La regla de §3 del diseño —una tabla por GRANO— solo
protege si el grano ajeno no se queda de recuerdo.

`costo_envio` NO se trae: `pedido.total` ya lo incluye, y el invariante real
del sistema es `factura_venta.total = pedido.total − pedido.costo_envio`
(la factura no factura el flete). Traer el flete a grano de línea repetiría el
mismo problema sin comprar ningún control.

═══════════════════════════════════════════════════════════════════════════════

Sobre el mes: `mes` se calcula en PostgreSQL con
`date_trunc('month', fecha_pedido AT TIME ZONE 'America/Guayaquil')` y viaja ya
resuelto. No se deriva en ClickHouse. Es la defensa directa contra §8.6: si el
corte mensual se calculara en el destino y alguna columna llegara sin zona, los
pedidos del día 1 de madrugada caerían al mes anterior y el control mes a mes
de §9.2 lo delataría. Calculándolo en el origen, ambos motores parten del mismo
número por construcción.
"""

from datetime import datetime
from zoneinfo import ZoneInfo

from etl.dwh.conexiones import ZONA_HORARIA
from etl.dwh.tarea import TareaCarga

#: Estado que excluye la línea del ingreso. Los informes de venta filtran
#: `es_cancelado = 0`: son los 159 pedidos que separan $5.716.436,55 de
#: $5.498.570,35.
ESTADO_CANCELADO = "cancelado"


class FactVentaLinea(TareaCarga):

    nombre = "fact_venta_linea"
    depende_de = ("dim_producto",)

    def __init__(self):
        super().__init__()
        #: Pedidos con descuento pero sin factura canónica. Se acumulan como
        #: conjunto (y no como contador) porque la extracción llega en lotes y
        #: un mismo pedido tiene varias líneas: contar filas daría un número
        #: mayor que el de pedidos afectados.
        self.pedidos_excepcion: set[int] = set()

    # ── Definición ───────────────────────────────────────────────────────────

    def ddl(self, nombre_tabla: str) -> str:
        return f"""
        CREATE TABLE {nombre_tabla}
        (
            pedido_detalle_id           UInt64,
            pedido_id                   UInt32,
            numero_pedido               String,
            fecha_pedido                DateTime('{ZONA_HORARIA}'),
            mes                         Date,
            canal                       LowCardinality(String),
            estado_pedido               LowCardinality(String),
            es_cancelado                UInt8,
            cliente_id                  UInt32,
            producto_variante_id        UInt32,
            sku                         String,
            producto_nombre             String,
            categoria                   LowCardinality(String),
            marca                       LowCardinality(String),
            cantidad                    UInt32,
            precio_unitario             Decimal(14,2),
            subtotal_bruto              Decimal(14,2),
            descuento_promocion         Decimal(14,2),
            descuento_cupon_prorrateado Decimal(14,2),
            descuento_total             Decimal(14,2),
            monto_impuesto              Decimal(14,2),
            venta_neta                  Decimal(14,2),
            costo_unitario              Decimal(14,2),
            costo_total                 Decimal(14,2),
            margen                      Decimal(14,2),
            margen_pct                  Decimal(6,2),
            tuvo_promocion              UInt8,
            excepcion_descuento         UInt8,
            fecha_carga                 DateTime('{ZONA_HORARIA}')
        )
        ENGINE = MergeTree
        PARTITION BY toYYYYMM(fecha_pedido)
        ORDER BY (fecha_pedido, categoria, producto_variante_id)
        """

    def columnas(self) -> list[str]:
        return [
            "pedido_detalle_id", "pedido_id", "numero_pedido", "fecha_pedido", "mes",
            "canal", "estado_pedido", "es_cancelado", "cliente_id",
            "producto_variante_id", "sku", "producto_nombre", "categoria", "marca",
            "cantidad", "precio_unitario", "subtotal_bruto",
            "descuento_promocion", "descuento_cupon_prorrateado", "descuento_total",
            "monto_impuesto", "venta_neta", "costo_unitario", "costo_total",
            "margen", "margen_pct", "tuvo_promocion", "excepcion_descuento",
            "fecha_carga",
        ]

    # ── Extracción ───────────────────────────────────────────────────────────

    #: Factura canónica del pedido: la NO anulada más reciente. Ver el hallazgo
    #: (1) de la cabecera — sin este `DISTINCT ON` la tabla sale con 10.386
    #: filas. Se define una vez y se reutiliza en extracción y en controles,
    #: para que la cifra de control no pueda divergir de lo que se carga.
    _CTE_FACTURA = """
        factura_canonica AS (
            SELECT DISTINCT ON (fv.pedido_id) fv.id, fv.pedido_id
            FROM factura_venta fv
            WHERE fv.estado <> 'anulada'
            ORDER BY fv.pedido_id, fv.fecha_emision DESC, fv.id DESC
        ),
        descuento_factura AS (
            SELECT fvd.pedido_detalle_id,
                   fvd.monto_descuento AS descuento_facturado
            FROM factura_venta_detalle fvd
            JOIN factura_canonica fc ON fc.id = fvd.factura_venta_id
        ),
        descuento_pedido AS (
            SELECT p.id AS pedido_id,
                   (p.monto_descuento > 0
                    OR COALESCE(dl.desc_lineas, 0) > 0) AS tiene_descuento
            FROM pedido p
            LEFT JOIN (
                SELECT pedido_id, SUM(monto_descuento) AS desc_lineas
                FROM pedido_detalle GROUP BY pedido_id
            ) dl ON dl.pedido_id = p.id
        )
    """

    def sql_extraccion(self) -> str:
        return f"""
        WITH {self._CTE_FACTURA}
        SELECT
            pd.id                                              AS pedido_detalle_id,
            p.id                                               AS pedido_id,
            p.numero                                           AS numero_pedido,
            p.fecha_pedido,
            (date_trunc('month',
                p.fecha_pedido AT TIME ZONE '{ZONA_HORARIA}'))::date AS mes,
            p.canal,
            ep.codigo                                          AS estado_pedido,
            CASE WHEN ep.codigo = '{ESTADO_CANCELADO}' THEN 1 ELSE 0 END AS es_cancelado,
            p.cliente_id,
            pd.producto_variante_id,
            pv.sku,
            pd.nombre_producto                                 AS producto_nombre,
            COALESCE(c.nombre, 'sin_categoria')                AS categoria,
            COALESCE(m.nombre, 'sin_marca')                    AS marca,
            pd.cantidad,
            pd.precio_unitario,
            (pd.cantidad * pd.precio_unitario)                 AS subtotal_bruto,
            pd.monto_descuento                                 AS descuento_promocion,
            cup.cupon                                          AS descuento_cupon_prorrateado,
            (pd.monto_descuento + cup.cupon)                   AS descuento_total,
            pd.monto_impuesto,
            (pd.cantidad * pd.precio_unitario
                - pd.monto_descuento - cup.cupon)              AS venta_neta,
            pv.costo                                           AS costo_unitario,
            (pd.cantidad * pv.costo)                           AS costo_total,
            (pd.cantidad * pd.precio_unitario
                - pd.monto_descuento - cup.cupon
                - pd.cantidad * pv.costo)                      AS margen,
            ROUND(
                (pd.cantidad * pd.precio_unitario
                    - pd.monto_descuento - cup.cupon
                    - pd.cantidad * pv.costo)
                / NULLIF(pd.cantidad * pd.precio_unitario
                    - pd.monto_descuento - cup.cupon, 0) * 100, 2
            )                                                  AS margen_pct,
            CASE WHEN pd.monto_descuento > 0 THEN 1 ELSE 0 END AS tuvo_promocion,
            CASE WHEN df.descuento_facturado IS NULL
                      AND dp.tiene_descuento THEN 1 ELSE 0 END AS excepcion_descuento
        FROM pedido_detalle pd
        JOIN pedido p              ON p.id  = pd.pedido_id
        JOIN estado_pedido ep      ON ep.id = p.estado_pedido_id
        JOIN producto_variante pv  ON pv.id = pd.producto_variante_id
        JOIN producto pr           ON pr.id = pv.producto_id
        LEFT JOIN marca m          ON m.id  = pr.marca_id
        LEFT JOIN producto_categoria pc
                                   ON pc.producto_id = pr.id AND pc.es_principal
        LEFT JOIN categoria c      ON c.id  = pc.categoria_id
        LEFT JOIN descuento_factura df ON df.pedido_detalle_id = pd.id
        LEFT JOIN descuento_pedido  dp ON dp.pedido_id         = p.id
        CROSS JOIN LATERAL (
            -- El despeje del cupón, con dos guardas explícitas:
            --   * sin factura canónica → 0 (las 6 excepciones de la cabecera);
            --   * GREATEST(...,0) → nunca un descuento negativo. Hoy no ocurre
            --     en ninguna de las 9.874 líneas facturadas, pero un prorrateo
            --     negativo restaría descuento en vez de sumarlo, y eso pasaría
            --     inadvertido en un agregado.
            SELECT CASE
                       WHEN df.descuento_facturado IS NULL THEN 0::numeric
                       ELSE GREATEST(df.descuento_facturado - pd.monto_descuento,
                                     0::numeric)
                   END AS cupon
        ) cup
        ORDER BY pd.id
        """

    def transformar(self, lote: list[tuple]) -> list[tuple]:
        """
        Añade `fecha_carga` y acumula los pedidos en excepción.

        `costo_unitario` se congela aquí, en la corrida: no existe costo
        histórico en el sistema (§8.3), así que el margen se calcula con el
        costo VIGENTE. `fecha_carga` deja constancia de a qué fecha corresponde
        ese costo, y es lo que hace auditable un margen que puede cambiar si
        alguien edita el costo de una variante.
        """
        ahora = datetime.now(ZoneInfo(ZONA_HORARIA)).replace(microsecond=0)
        indice_pedido = self.columnas().index("pedido_id")
        indice_excepcion = self.columnas().index("excepcion_descuento")

        for fila in lote:
            if fila[indice_excepcion] == 1:
                self.pedidos_excepcion.add(fila[indice_pedido])

        self.excepciones = len(self.pedidos_excepcion)
        return [tuple(fila) + (ahora,) for fila in lote]

    # ── Controles ────────────────────────────────────────────────────────────

    def sql_controles(self) -> str:
        """
        Las siete cifras de control de §9.2, tomadas del ORIGEN en la misma
        corrida (no constantes escritas a mano: si el negocio crece, el control
        crece con él).

        Ojo con `venta_neta_linea`: el control de §9.2 es
        `cantidad*precio_unitario − monto_descuento`, es decir SOLO la capa de
        promoción. La columna `venta_neta` de la tabla resta ADEMÁS el cupón
        prorrateado, así que son dos números distintos y ambos se validan por
        separado (`venta_neta_linea` = $4.991.078,85 contra
        `venta_neta_final` = $4.940.745,86). Confundirlos sería dar por bueno un
        descuadre de $50.332,99.
        """
        return f"""
        WITH {self._CTE_FACTURA},
        linea AS (
            SELECT
                pd.id, pd.pedido_id, pd.cantidad,
                pd.cantidad * pd.precio_unitario AS bruto,
                pd.monto_descuento               AS promocion,
                CASE WHEN df.descuento_facturado IS NULL THEN 0::numeric
                     ELSE GREATEST(df.descuento_facturado - pd.monto_descuento,
                                   0::numeric) END AS cupon,
                pd.cantidad * pv.costo           AS costo,
                pv.costo                         AS costo_unitario,
                (date_trunc('month',
                    p.fecha_pedido AT TIME ZONE '{ZONA_HORARIA}'))::date AS mes,
                CASE WHEN ep.codigo = '{ESTADO_CANCELADO}' THEN 1 ELSE 0 END AS cancelado,
                CASE WHEN df.descuento_facturado IS NULL
                          AND dp.tiene_descuento THEN 1 ELSE 0 END AS excepcion
            FROM pedido_detalle pd
            JOIN pedido p             ON p.id  = pd.pedido_id
            JOIN estado_pedido ep     ON ep.id = p.estado_pedido_id
            JOIN producto_variante pv ON pv.id = pd.producto_variante_id
            LEFT JOIN descuento_factura df ON df.pedido_detalle_id = pd.id
            LEFT JOIN descuento_pedido  dp ON dp.pedido_id         = p.id
        )
        SELECT
            COUNT(*)                                          AS filas,
            SUM(cantidad)                                     AS unidades,
            SUM(bruto - promocion)                            AS venta_neta_linea,
            SUM(bruto - promocion - cupon)                    AS venta_neta_final,
            SUM(costo)                                        AS costo_total,
            SUM(promocion)                                    AS descuento_promocion,
            SUM(cupon)                                        AS descuento_cupon,
            COUNT(*) FILTER (WHERE costo_unitario IS NULL)    AS lineas_sin_costo,
            COUNT(DISTINCT mes)                               AS meses_con_venta,
            COUNT(DISTINCT pedido_id)                         AS pedidos,
            COUNT(DISTINCT pedido_id) FILTER (WHERE excepcion = 1) AS pedidos_excepcion,
            SUM(cancelado)                                    AS lineas_canceladas
        FROM linea
        """

    def validar(self, client, tabla_staging: str, controles: dict) -> list[str]:
        """
        Compara ClickHouse contra PostgreSQL cifra por cifra. El criterio es la
        igualdad EXACTA: una diferencia de un centavo es un bug (redondeo por
        Float en vez de Decimal), y una fila de más o de menos es un JOIN mal
        planteado. Ninguna de las dos se acepta como tolerancia.
        """
        errores = []
        fila = client.query(f"""
            SELECT
                count(),
                sum(cantidad),
                sum(subtotal_bruto - descuento_promocion),
                sum(venta_neta),
                sum(costo_total),
                sum(descuento_promocion),
                sum(descuento_cupon_prorrateado),
                countIf(costo_unitario = 0),
                countDistinct(mes),
                countDistinct(pedido_id),
                countDistinctIf(pedido_id, excepcion_descuento = 1),
                sum(es_cancelado)
            FROM {tabla_staging}
        """).result_rows[0]

        obtenido = dict(zip(
            ("unidades", "venta_neta_linea", "venta_neta_final", "costo_total",
             "descuento_promocion", "descuento_cupon", "lineas_sin_costo",
             "meses_con_venta", "pedidos", "pedidos_excepcion", "lineas_canceladas"),
            fila[1:],
        ))

        for clave, valor_ch in obtenido.items():
            valor_pg = controles.get(clave)
            if valor_pg is None:
                continue
            # Se comparan como Decimal/int, nunca como float: el motivo entero
            # de que el dinero sea Decimal(14,2) y no Float64 es que esta
            # comparación sea significativa.
            if type(valor_pg)(valor_ch) != valor_pg:
                errores.append(
                    f"{clave}: PostgreSQL {valor_pg} vs ClickHouse {valor_ch} "
                    f"(diferencia {type(valor_pg)(valor_ch) - valor_pg})."
                )

        errores += self._validar_mes_a_mes(client, tabla_staging)
        return errores

    def _validar_mes_a_mes(self, client, tabla_staging: str) -> list[str]:
        """
        El control que detecta los errores de zona horaria (§8.6): unidades y
        venta bruta por mes, mes a mes. Un desplazamiento de zona no mueve el
        total —los pedidos siguen estando— pero SÍ mueve los pedidos del día 1
        de madrugada al mes anterior, y eso solo se ve comparando la serie.
        """
        conn_sql = f"""
        WITH {self._CTE_FACTURA}
        SELECT
            (date_trunc('month',
                p.fecha_pedido AT TIME ZONE '{ZONA_HORARIA}'))::date AS mes,
            COUNT(*)                          AS lineas,
            SUM(pd.cantidad)                  AS unidades,
            SUM(pd.cantidad * pd.precio_unitario - pd.monto_descuento) AS venta
        FROM pedido_detalle pd
        JOIN pedido p ON p.id = pd.pedido_id
        GROUP BY 1 ORDER BY 1
        """
        from etl.dwh.conexiones import get_pg_connection

        conn = get_pg_connection()
        try:
            with conn.cursor() as cur:
                cur.execute(conn_sql)
                origen = {f[0]: (f[1], f[2], f[3]) for f in cur.fetchall()}
        finally:
            conn.close()

        destino = {
            f[0]: (f[1], f[2], f[3])
            for f in client.query(f"""
                SELECT mes, count(), sum(cantidad),
                       sum(subtotal_bruto - descuento_promocion)
                FROM {tabla_staging} GROUP BY mes ORDER BY mes
            """).result_rows
        }

        errores = []
        if set(origen) != set(destino):
            faltan = sorted(set(origen) - set(destino))
            sobran = sorted(set(destino) - set(origen))
            errores.append(
                f"Los meses no coinciden — faltan {faltan or 'ninguno'}, "
                f"sobran {sobran or 'ninguno'}. Síntoma de zona horaria (§8.6)."
            )
            return errores

        for mes in sorted(origen):
            pg_lineas, pg_uds, pg_venta = origen[mes]
            ch_lineas, ch_uds, ch_venta = destino[mes]
            if (pg_lineas, int(pg_uds)) != (ch_lineas, int(ch_uds)) \
                    or type(pg_venta)(ch_venta) != pg_venta:
                errores.append(
                    f"Mes {mes}: PostgreSQL ({pg_lineas} líneas, {pg_uds} uds, "
                    f"{pg_venta}) vs ClickHouse ({ch_lineas}, {ch_uds}, {ch_venta})."
                )
        return errores
