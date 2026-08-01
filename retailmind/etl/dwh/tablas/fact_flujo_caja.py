"""
etl/dwh/tablas/fact_flujo_caja.py — F3 del modelo (§5.3 del diseño).

TODO el dinero que se mueve, en los dos sentidos, en una sola tabla.
Grano: un movimiento de dinero. 4.079 cobros + 902 pagos = 4.981 filas.
Alimenta OTD-VEN-09, VEN-12, COM-03 y el lado *caja* de GER-02.

Cobros de cliente y pagos a proveedor comparten grano (un movimiento) y medida
(un monto); los separa la columna `sentido`. Dos tablas obligarían a cada
informe de balanza a hacer un UNION que el modelo puede hacer una vez.

═══════════════════════════════════════════════════════════════════════════════
LO QUE EL DISEÑO EN PAPEL DABA POR SUPUESTO Y NO ERA ASÍ (2026-07-30)
═══════════════════════════════════════════════════════════════════════════════

(1) LOS 176 COBROS FALLIDOS NO TIENEN NI FECHA DE PAGO NI PEDIDO.

    §5.3 especifica `fecha ← pago.fecha_pago` y `documento ← pedido` para todo
    el lado del ingreso. Verificado contra la base:

        pago total ................. 4.079
        con pedido_id .............. 3.903   ← los 176 fallidos van sin pedido
        con fecha_pago ............. 3.903   ← los 176 fallidos van sin fecha

    Es coherente con cómo nace el dato (script 52): el intento se registra ANTES
    de que exista pedido —el cliente no llegó a comprar— y `fecha_pago` es la
    fecha de LIQUIDACIÓN, que en un cobro rechazado no existe. Tomar
    `fecha_pago` a secas habría dejado 176 filas con fecha nula: imposibles de
    particionar y, peor, invisibles para OTD-VEN-12, que es un informe POR
    PERÍODO de esos mismos 176 intentos. El informe habría salido vacío sin un
    solo error.

    Se carga `COALESCE(fecha_pago, fecha_creacion)` y la distinción se hace
    EXPLÍCITA en la columna `fecha_es_intento`: 1 = el instante es el del
    INTENTO rechazado, 0 = es la liquidación efectiva. No son la misma clase de
    fecha y mezclarlas sin decirlo sería fabricar una serie de cobros que
    incluye dinero que nunca entró.

(2) EL CLIENTE DE UN COBRO FALLIDO CASI NUNCA ES RECUPERABLE.

    Sin `pedido_id` no hay `cliente_id`. Solo 2 de los 176 traen el cliente
    dentro de `transaccion_pago.respuesta_pasarela` (los que produjo el checkout
    real; los otros 174 son del seed y solo llevan `{estado, motivo}`). Se
    recupera lo recuperable —esos 2— y el resto va a `contraparte_id = 0` con
    nombre `'(no identificado)'`. Inventar un cliente para cuadrar la columna
    sería peor que la ausencia.

(3) EL `movimiento_id` NO ES ÚNICO POR SÍ SOLO.

    `pago.id` y `pago_proveedor.id` son secuencias INDEPENDIENTES y se solapan
    (ambas arrancan en 1). La clave real de la tabla es el par
    **(sentido, movimiento_id)**, y así se valida. `MergeTree` no exige unicidad
    en su `ORDER BY`, de modo que esto no rompe nada — pero un informe que
    contara `countDistinct(movimiento_id)` daría de menos, y por eso se dice.

═══════════════════════════════════════════════════════════════════════════════
NORMALIZACIÓN DE `motivo_fallo` — el mapa, y la regla de escape
═══════════════════════════════════════════════════════════════════════════════

Los 176 intentos fallidos guardan su motivo en
`transaccion_pago.respuesta_pasarela` (jsonb). En crudo hay **6 valores
distintos donde el negocio tiene 5**, porque conviven el código y su texto
libre:

    fondos_insuficientes ................ 40
    datos_incorrectos ................... 38
    tarjeta_rechazada ................... 37   ┐ el mismo motivo,
    "Tarjeta rechazada por el emisor" ....  2   ┘ escrito de dos maneras
    error_pasarela ...................... 31
    limite_excedido ..................... 28

Sin normalizar, OTD-VEN-12 muestra seis filas y dos de ellas son el mismo
motivo — un informe que se lee mal precisamente donde debe leerse bien. Tras el
mapa quedan 5 motivos y `tarjeta_rechazada` sube a 39.

La normalización se hace en `transformar()` —en Python y no en el SELECT— por la
regla de escape que exige §5.3: **un valor no previsto se carga como `'otro'` y
se REGISTRA**, nunca se descarta ni se silencia. Eso requiere saber qué valor
apareció, y un `CASE` en SQL puede mapear pero no puede avisar. Los valores
desconocidos se acumulan, se escriben en el log y se cuentan en
`etl_ejecucion.excepciones`.

═══════════════════════════════════════════════════════════════════════════════
EL LADO DEL EGRESO
═══════════════════════════════════════════════════════════════════════════════

902 pagos por $16.084.462,74, cadena `pago_proveedor → cuenta_por_pagar →
factura_compra → proveedor` completa en las 902 filas (verificado: ni una rota).

`pago_proveedor.fecha_pago` es un **`date`**, no un timestamp: se ancla a
medianoche de `America/Guayaquil` al convertirlo, y no a medianoche UTC, que lo
correría al día anterior en la serie mensual (§8.6, el error que ya se pagó una
vez en este proyecto).

`dias_desvio_vencimiento` es `fecha_pago − fecha_vencimiento`, con el signo del
diseño: **negativo = anticipado**. Rango real hoy: de −30 a +19 días, 564 pagos
a tiempo y 338 tarde. Es la medida de OTD-COM-03.
"""

from datetime import datetime
from zoneinfo import ZoneInfo

from etl.dwh.conexiones import ZONA_HORARIA, logger
from etl.dwh.tarea import TareaCarga

#: Los CINCO motivos de rechazo del negocio (`LoginFallidoException` no, esto es
#: la pasarela simulada del script 52). Lista blanca: lo que no está aquí ni en
#: el mapa de sinónimos es un valor nuevo y se trata como tal.
MOTIVOS_CONOCIDOS = frozenset({
    "fondos_insuficientes",
    "datos_incorrectos",
    "tarjeta_rechazada",
    "error_pasarela",
    "limite_excedido",
})

#: Mapa de sinónimos: texto libre → código. La clave va en minúsculas y sin
#: espacios en los bordes porque así llega normalizada del SELECT.
MAPA_MOTIVOS = {
    "tarjeta rechazada por el emisor": "tarjeta_rechazada",
}

#: Destino de un valor no previsto. Ver la regla de escape de §5.3.
MOTIVO_OTRO = "otro"

#: Contraparte de un movimiento cuyo titular no es recuperable (los 174 cobros
#: fallidos del seed).
SIN_CONTRAPARTE = "(no identificado)"


class FactFlujoCaja(TareaCarga):

    nombre = "fact_flujo_caja"

    def __init__(self):
        super().__init__()
        #: Valores de motivo que no estaban previstos, con su recuento. Se
        #: vuelcan al log y su total va a `etl_ejecucion.excepciones`.
        self.motivos_desconocidos: dict[str, int] = {}

    # ── Definición ───────────────────────────────────────────────────────────

    def ddl(self, nombre_tabla: str) -> str:
        return f"""
        CREATE TABLE {nombre_tabla}
        (
            movimiento_id            UInt64,
            sentido                  LowCardinality(String),
            fecha                    DateTime('{ZONA_HORARIA}'),
            fecha_es_intento         UInt8,
            mes                      Date,
            monto                    Decimal(14,2),
            estado                   LowCardinality(String),
            metodo_pago              LowCardinality(String),
            metodo_pago_tipo         LowCardinality(String),
            contraparte_tipo         LowCardinality(String),
            contraparte_id           UInt32,
            contraparte_nombre       String,
            documento_id             UInt32,
            documento_numero         String,
            canal                    LowCardinality(String),
            motivo_fallo             LowCardinality(String),
            fecha_vencimiento        Nullable(Date),
            dias_desvio_vencimiento  Nullable(Int16),
            a_tiempo                 Nullable(UInt8),
            fecha_carga              DateTime('{ZONA_HORARIA}')
        )
        ENGINE = MergeTree
        PARTITION BY toYYYYMM(fecha)
        ORDER BY (fecha, sentido, metodo_pago)
        """

    def columnas(self) -> list[str]:
        return [
            "movimiento_id", "sentido", "fecha", "fecha_es_intento", "mes",
            "monto", "estado", "metodo_pago", "metodo_pago_tipo",
            "contraparte_tipo", "contraparte_id", "contraparte_nombre",
            "documento_id", "documento_numero", "canal", "motivo_fallo",
            "fecha_vencimiento", "dias_desvio_vencimiento", "a_tiempo",
            "fecha_carga",
        ]

    # ── Extracción ───────────────────────────────────────────────────────────

    #: El lado del INGRESO: cobros de cliente.
    #: `transaccion_pago` es 1:1 con `pago` (verificado: los 4.079 pagos tienen
    #: exactamente una transacción), así que el LEFT JOIN no hace fan-out. El
    #: control `filas` lo vigila: si alguna vez hubiera dos transacciones para un
    #: pago, la carga saldría con más filas que `pago` y abortaría.
    _SQL_INGRESO = f"""
        SELECT
            pg.id                                              AS movimiento_id,
            'ingreso'                                          AS sentido,
            COALESCE(pg.fecha_pago, pg.fecha_creacion)         AS fecha,
            CASE WHEN pg.fecha_pago IS NULL THEN 1 ELSE 0 END  AS fecha_es_intento,
            (date_trunc('month',
                COALESCE(pg.fecha_pago, pg.fecha_creacion)
                    AT TIME ZONE '{ZONA_HORARIA}'))::date      AS mes,
            pg.monto,
            pg.estado,
            mp.nombre                                          AS metodo_pago,
            mp.tipo                                            AS metodo_pago_tipo,
            'cliente'                                          AS contraparte_tipo,
            COALESCE(p.cliente_id,
                     (tp.respuesta_pasarela->>'cliente_id')::bigint,
                     0)                                        AS contraparte_id,
            COALESCE(TRIM(c.nombre || ' ' || COALESCE(c.apellido, '')),
                     '{SIN_CONTRAPARTE}')                      AS contraparte_nombre,
            COALESCE(p.id, 0)                                  AS documento_id,
            COALESCE(p.numero, '')                             AS documento_numero,
            COALESCE(p.canal, '')                              AS canal,
            -- Crudo: la normalización y el escape a 'otro' viven en transformar().
            CASE WHEN pg.estado = 'fallido'
                 THEN LOWER(TRIM(COALESCE(tp.respuesta_pasarela->>'motivo', '')))
                 ELSE '' END                                   AS motivo_fallo,
            NULL::date                                         AS fecha_vencimiento,
            NULL::int                                          AS dias_desvio_vencimiento,
            NULL::int                                          AS a_tiempo
        FROM pago pg
        JOIN metodo_pago mp            ON mp.id = pg.metodo_pago_id
        LEFT JOIN transaccion_pago tp  ON tp.pago_id = pg.id
        LEFT JOIN pedido p             ON p.id = pg.pedido_id
        LEFT JOIN cliente c            ON c.id = COALESCE(
                                            p.cliente_id,
                                            (tp.respuesta_pasarela->>'cliente_id')::bigint)
    """

    #: El lado del EGRESO: pagos a proveedor.
    #: `fecha_pago` es un `date`; se ancla a medianoche de la zona del negocio.
    _SQL_EGRESO = f"""
        SELECT
            pp.id                                              AS movimiento_id,
            'egreso'                                           AS sentido,
            (pp.fecha_pago::timestamp AT TIME ZONE '{ZONA_HORARIA}') AS fecha,
            0                                                  AS fecha_es_intento,
            (date_trunc('month', pp.fecha_pago))::date         AS mes,
            pp.monto,
            'completado'                                       AS estado,
            mp.nombre                                          AS metodo_pago,
            mp.tipo                                            AS metodo_pago_tipo,
            'proveedor'                                        AS contraparte_tipo,
            pv.id                                              AS contraparte_id,
            pv.razon_social                                    AS contraparte_nombre,
            fc.id                                              AS documento_id,
            fc.numero_factura                                  AS documento_numero,
            ''                                                 AS canal,
            ''                                                 AS motivo_fallo,
            cpp.fecha_vencimiento,
            (pp.fecha_pago - cpp.fecha_vencimiento)            AS dias_desvio_vencimiento,
            CASE WHEN pp.fecha_pago <= cpp.fecha_vencimiento
                 THEN 1 ELSE 0 END                             AS a_tiempo
        FROM pago_proveedor pp
        JOIN metodo_pago mp        ON mp.id  = pp.metodo_pago_id
        JOIN cuenta_por_pagar cpp  ON cpp.id = pp.cuenta_por_pagar_id
        JOIN factura_compra fc     ON fc.id  = cpp.factura_compra_id
        JOIN proveedor pv          ON pv.id  = cpp.proveedor_id
    """

    def sql_extraccion(self) -> str:
        return f"""
        {self._SQL_INGRESO}
        UNION ALL
        {self._SQL_EGRESO}
        ORDER BY 2, 1
        """

    # ── Transformación: la normalización del motivo ──────────────────────────

    def transformar(self, lote: list[tuple]) -> list[tuple]:
        """
        Normaliza `motivo_fallo` y añade `fecha_carga`.

        Tres caminos y ninguno silencioso:
          * el valor ya es uno de los 5 códigos      → se deja tal cual;
          * es un sinónimo conocido del mapa         → se traduce;
          * es cualquier otra cosa no vacía          → `'otro'`, se anota y se
            registra en la bitácora de la corrida.
        """
        ahora = datetime.now(ZoneInfo(ZONA_HORARIA)).replace(microsecond=0)
        i_motivo = self.columnas().index("motivo_fallo")

        salida = []
        for fila in lote:
            fila = list(fila)
            fila[i_motivo] = self._normalizar_motivo(fila[i_motivo])
            salida.append(tuple(fila) + (ahora,))

        self.excepciones = sum(self.motivos_desconocidos.values())
        return salida

    def _normalizar_motivo(self, crudo: str | None) -> str:
        if not crudo:
            return ""
        valor = crudo.strip().lower()
        if valor in MOTIVOS_CONOCIDOS:
            return valor
        if valor in MAPA_MOTIVOS:
            return MAPA_MOTIVOS[valor]

        # Regla de escape del §5.3: no se descarta, no se inventa; se marca.
        self.motivos_desconocidos[valor] = self.motivos_desconocidos.get(valor, 0) + 1
        if self.motivos_desconocidos[valor] == 1:
            logger.warning(
                f"[{self.nombre}] motivo de fallo NO previsto: «{crudo}». Se carga "
                f"como '{MOTIVO_OTRO}' y queda contado en la bitácora. Si es un "
                f"motivo real del negocio, añádelo a MOTIVOS_CONOCIDOS o a "
                f"MAPA_MOTIVOS antes de que OTD-VEN-12 lo agrupe con el resto."
            )
        return MOTIVO_OTRO

    # ── Controles ────────────────────────────────────────────────────────────

    def sql_controles(self) -> str:
        """
        Cifras de §9.3 desde el origen. El conteo de `filas` suma los dos lados:
        es lo que `validar_conteo` compara contra lo escrito, y lo que detectaría
        un fan-out de `transaccion_pago` sobre el lado del ingreso.
        """
        return """
        SELECT
            (SELECT count(*) FROM pago)
              + (SELECT count(*) FROM pago_proveedor)             AS filas,
            (SELECT count(*) FROM pago)                           AS ingresos,
            (SELECT count(*) FROM pago WHERE estado = 'fallido')  AS ingresos_fallidos,
            (SELECT count(*) FROM pago WHERE estado = 'completado') AS ingresos_completados,
            (SELECT SUM(monto) FROM pago)                         AS monto_ingresos,
            (SELECT SUM(monto) FROM pago WHERE estado = 'completado')
                                                                  AS monto_cobrado,
            (SELECT SUM(monto) FROM pago WHERE estado = 'fallido') AS monto_fallido,
            (SELECT count(*) FROM pago_proveedor)                 AS egresos,
            (SELECT SUM(monto) FROM pago_proveedor)               AS monto_egresos,
            (SELECT count(*) FROM pago WHERE fecha_pago IS NULL)  AS sin_fecha_pago,
            (SELECT count(DISTINCT
                     CASE WHEN pg.estado = 'fallido' THEN
                       CASE LOWER(TRIM(COALESCE(tp.respuesta_pasarela->>'motivo','')))
                            WHEN 'tarjeta rechazada por el emisor' THEN 'tarjeta_rechazada'
                            ELSE LOWER(TRIM(COALESCE(tp.respuesta_pasarela->>'motivo','')))
                       END
                     END)
             FROM pago pg LEFT JOIN transaccion_pago tp ON tp.pago_id = pg.id)
                                                                  AS motivos_normalizados,
            (SELECT count(*) FROM pago_proveedor pp
             JOIN cuenta_por_pagar c ON c.id = pp.cuenta_por_pagar_id
             WHERE pp.fecha_pago <= c.fecha_vencimiento)          AS egresos_a_tiempo,
            (SELECT count(DISTINCT metodo_pago_id) FROM pago)     AS metodos_ingreso,
            (SELECT count(DISTINCT metodo_pago_id) FROM pago_proveedor) AS metodos_egreso
        """

    _EQUIVALENCIAS = (
        ("ingresos",             "countIf(sentido = 'ingreso')"),
        ("ingresos_fallidos",    "countIf(sentido = 'ingreso' AND estado = 'fallido')"),
        ("ingresos_completados", "countIf(sentido = 'ingreso' AND estado = 'completado')"),
        ("monto_ingresos",       "sumIf(monto, sentido = 'ingreso')"),
        ("monto_cobrado",        "sumIf(monto, sentido = 'ingreso' AND estado = 'completado')"),
        ("monto_fallido",        "sumIf(monto, sentido = 'ingreso' AND estado = 'fallido')"),
        ("egresos",              "countIf(sentido = 'egreso')"),
        ("monto_egresos",        "sumIf(monto, sentido = 'egreso')"),
        ("sin_fecha_pago",       "countIf(fecha_es_intento = 1)"),
        ("motivos_normalizados", "countDistinctIf(motivo_fallo, motivo_fallo != '')"),
        ("egresos_a_tiempo",     "countIf(a_tiempo = 1)"),
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

        errores += self._validar_motivos(client, tabla_staging, controles)
        errores += self._validar_grano(client, tabla_staging)
        return errores

    def _validar_motivos(self, client, tabla_staging: str, controles: dict) -> list[str]:
        """
        El control que da sentido a toda la normalización: los motivos deben
        quedar en CINCO, y todos los cobros fallidos deben tener uno.
        """
        errores = []
        filas = client.query(f"""
            SELECT motivo_fallo, count() FROM {tabla_staging}
            WHERE sentido = 'ingreso' AND estado = 'fallido'
            GROUP BY motivo_fallo ORDER BY count() DESC
        """).result_rows
        motivos = {m: n for m, n in filas}

        if "" in motivos:
            errores.append(f"{motivos['']} cobros fallidos se quedaron SIN motivo. "
                           f"El motivo vive en transaccion_pago.respuesta_pasarela; "
                           f"si falta, el join se rompió.")
        distintos = len([m for m in motivos if m])
        esperados = int(controles["motivos_normalizados"])
        if distintos != esperados:
            errores.append(
                f"Motivos de fallo distintos: origen normalizado {esperados} vs "
                f"destino {distintos} → {sorted(motivos)}. El mapa de "
                f"normalización (§5.3) dejó de cubrir los datos."
            )
        if MOTIVO_OTRO in motivos:
            # No aborta: la regla de escape existe justo para que un valor nuevo
            # se cargue en vez de tumbar la corrida. Pero se deja dicho.
            logger.warning(
                f"[{self.nombre}] {motivos[MOTIVO_OTRO]} movimientos cayeron en "
                f"'{MOTIVO_OTRO}': {self.motivos_desconocidos}"
            )
        return errores

    def _validar_grano(self, client, tabla_staging: str) -> list[str]:
        """
        El grano es UN movimiento, y su clave es el PAR (sentido, movimiento_id)
        — `pago.id` y `pago_proveedor.id` son secuencias distintas que se
        solapan (ver el hallazgo 3 de la cabecera).
        """
        total, pares = client.query(f"""
            SELECT count(), countDistinct((sentido, movimiento_id))
            FROM {tabla_staging}
        """).result_rows[0]
        if pares != total:
            return [f"El grano se rompió: {total} filas pero {pares} pares "
                    f"(sentido, movimiento_id) distintos."]
        return []
