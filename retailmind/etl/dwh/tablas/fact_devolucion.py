"""
etl/dwh/tablas/fact_devolucion.py — F10 del modelo (§5.10 del diseño).

La cabecera del RMA. Grano: **una devolución**. 196 filas. Alimenta OTD-VEN-14,
OTD-LOG-07, OTD-LOG-09 (numerador) y OTD-LOG-10.

═══════════════════════════════════════════════════════════════════════════════
1. EL REEMBOLSO TIENE DOS ORÍGENES Y NO DICEN LO MISMO — C4.1
═══════════════════════════════════════════════════════════════════════════════

§5.10 declara el origen como «… ⟕ `reembolso` ⟕ `cliente`» y a la vez pone en la
tabla las columnas `monto_reembolsado` / `metodo_reembolso` / `fecha_reembolso`,
que **no son de `reembolso`: son de `devolucion`**. Son dos registros distintos
del mismo hecho y difieren:

    devolucion.monto_reembolsado poblado ....  86 devoluciones / $44.695,33
    filas en la tabla `reembolso` ...........  85 devoluciones / $44.525,63
                                     diferencia   1 devolución / $   169,70

La que sobra es la **devolución 8** (`DV-20260716-53942`, cerrada, del día en
que se construyó el RMA): tiene su monto en la cabecera y ninguna fila en
`reembolso`. Donde existen los dos, coinciden **siempre** (0 discrepancias de
monto, 0 reembolsos sin monto en la cabecera).

Y hay una asimetría que decide cuál sirve para el informe: **la VÍA del
reembolso (`metodo_reembolso`) solo existe en `devolucion`.** La tabla
`reembolso` guarda el asiento —`pago_id`, estado, referencia— pero no por dónde
se devolvió el dinero, que es media pregunta de OTD-LOG-10.

**Cómo se resolvió.** Las dos magnitudes viajan por separado y **no se
reconcilian**, mismo criterio que C2.4 con el cupón: `monto_reembolsado` es lo
que el RMA dice haber devuelto (86) y `reembolso_registrado` marca si además
existe el asiento de tesorería (85). LOG-10 declara en pantalla cuál usa.

═══════════════════════════════════════════════════════════════════════════════
2. EL CICLO COMPLETO SOLO ES MEDIBLE EN 35 DE 196 — C4.2
═══════════════════════════════════════════════════════════════════════════════

§5.10 define `dias_ciclo_total` = «cierre − solicitud → LOG-07» sin decir sobre
cuántas devoluciones existe ese cierre. La respuesta es incómoda:

    devoluciones ....................... 196
    con hito 'cerrada' .................  35   ← el 17,9 %
    con desenlace TERMINAL .............  53   (35 cerradas + 18 rechazadas)
    con ≥3 pasos fechados .............. 161   ← esto sí, como decía el diseño

Una devolución **rechazada** es un ciclo que terminó: el estado es terminal por
diseño del RMA (`rechazada` no tiene salida). Medir «cuántos días tarda una
devolución» solo sobre las 35 cerradas descarta 18 desenlaces reales y —peor—
descarta justo los más rápidos, porque rechazar no exige recibir la mercancía.

**Cómo se resolvió.** Se cargan las DOS medidas: `dias_ciclo_total` (cierre −
solicitud, 35, la del diseño) y `dias_hasta_desenlace` (cierre **o** rechazo −
solicitud, 53). LOG-07 muestra las dos con su base y declara cuál es cuál.

Cobertura de los hitos, medida contra `historial_estado_devolucion` (1.008
registros, ninguno repetido dentro de la misma devolución):

    solicitada .... 191      en_transito ... 131      reembolsada ... 86
    en_revision ... 176      recibida ...... 121      cerrada ....... 35
    aprobada ...... 143      inspeccionada . 107      rechazada ..... 18

═══════════════════════════════════════════════════════════════════════════════
3. LA FECHA DE SOLICITUD SALE DE LA CABECERA, NO DEL HISTORIAL
═══════════════════════════════════════════════════════════════════════════════

5 de las 196 no tienen registro histórico de `solicitada` (son legacy anteriores
al script 38). Donde el hito existe, su fecha coincide **exactamente** con
`devolucion.fecha_creacion` en las 191. Por eso el origen de `fecha_solicitud`
es la cabecera: es el hecho, está en las 196, y el historial solo lo confirma.

Tomarla del historial habría dejado 5 devoluciones sin fecha —y por tanto fuera
de toda partición por mes— exactamente como pasó con los cobros fallidos en la
Fase 2 (C2.1).

═══════════════════════════════════════════════════════════════════════════════
4. LAS DOS FECHAS DE MES, Y POR QUÉ VEN-14 TIENE QUE ELEGIR
═══════════════════════════════════════════════════════════════════════════════

El numerador de VEN-14 (lo devuelto) se fecha por la devolución; el denominador
(la venta) por el pedido. Una devolución de julio puede corresponder a un pedido
de mayo, así que el porcentaje **depende de contra qué mes se divida**. La tabla
lleva `mes` y `mes_pedido`, y el informe declara cuál usa (el diseño recomienda
`mes`, y así se hace: la pregunta del gerente es «cuánto me devuelven este mes»).

`total_pedido` viaja como atributo degenerado de cabecera y solo se lee tras
agrupar por pedido — misma advertencia que costó retirar `pedido_total` de
`fact_venta_linea` en la Fase 2. Aquí es seguro porque el grano YA es una
devolución por pedido... salvo que un pedido tenga dos devoluciones, cosa que el
control vigila explícitamente.

Los tramos van en `Decimal(12,2)` y no en el `Float32` de §5.10, por la razón ya
registrada en C2.5: el comparador de `validar_dwh.py` rechaza todo float.

Bitácora completa: `docs/estrategico/CORRECCIONES_DISENO_ETL.md`.
"""

from datetime import datetime
from zoneinfo import ZoneInfo

from etl.dwh.conexiones import ZONA_HORARIA, logger
from etl.dwh.tarea import TareaCarga

#: Los 9 estados del CHECK de `devolucion`, todos en uso.
ESTADOS_CONOCIDOS = frozenset({
    "solicitada", "en_revision", "aprobada", "rechazada", "en_transito",
    "recibida", "inspeccionada", "reembolsada", "cerrada",
})

#: Estados de los que no se sale: el ciclo terminó, bien o mal.
ESTADOS_TERMINALES = frozenset({"cerrada", "rechazada"})

#: Ausencias que NO son datos perdidos.
SIN_TRANSPORTISTA = "sin_asignar"
SIN_BODEGA = "sin_asignar"
SIN_METODO = "sin_reembolso"

#: Pivote de hitos. `min` y no `max` por el mismo motivo que en `fact_pedido`
#: (C2.6): si algún día un estado se repitiera, el hito es la PRIMERA vez que
#: se alcanzó, no la última. Hoy no se repite ninguno — verificado.
_HITOS = ("aprobada", "en_transito", "recibida", "inspeccionada", "cerrada",
          "rechazada")


def _pivote() -> str:
    """CTE que pivota `historial_estado_devolucion` a una fila por devolución."""
    columnas = ",\n            ".join(
        f"min(fecha_creacion) FILTER (WHERE estado = '{h}') AS f_{h}"
        for h in _HITOS
    )
    return f"""
        hitos AS (
            SELECT devolucion_id,
            {columnas},
            count(DISTINCT estado) AS pasos
            FROM historial_estado_devolucion
            GROUP BY devolucion_id
        )"""


def _dias(fin: str, inicio: str) -> str:
    """
    Días entre dos instantes, con dos decimales.

    Se mide en segundos y se divide, en vez de restar `::date`: aquí interesa la
    DURACIÓN («cuánto tardó») y no el salto de calendario, así que la zona
    horaria no cambia el resultado —a diferencia de C3.4 y C3C.1, donde lo que
    se restaba eran DÍAS y el día sí depende de la zona—. Media jornada es 0,5
    días y no 0 ni 1.
    """
    return (f"CASE WHEN {fin} IS NULL OR {inicio} IS NULL THEN NULL "
            f"ELSE ROUND((EXTRACT(EPOCH FROM ({fin} - {inicio})) / 86400.0)::numeric, 2) "
            f"END")


class FactDevolucion(TareaCarga):

    nombre = "fact_devolucion"

    def __init__(self):
        super().__init__()
        self.valores_nuevos: dict[str, int] = {}

    # ── Definición ───────────────────────────────────────────────────────────

    def ddl(self, nombre_tabla: str) -> str:
        return f"""
        CREATE TABLE {nombre_tabla}
        (
            devolucion_id           UInt32,
            numero                  String,
            pedido_id               UInt32,
            numero_pedido           String,
            cliente_id              UInt32,
            fecha_solicitud         DateTime('{ZONA_HORARIA}'),
            mes                     Date,
            estado                  LowCardinality(String),
            es_terminal             UInt8,
            motivo                  LowCardinality(String),
            monto_total             Decimal(14,2),
            lineas                  UInt16,
            unidades                UInt32,
            fecha_aprobacion        Nullable(DateTime('{ZONA_HORARIA}')),
            fecha_transito          Nullable(DateTime('{ZONA_HORARIA}')),
            fecha_recepcion         Nullable(DateTime('{ZONA_HORARIA}')),
            fecha_inspeccion        Nullable(DateTime('{ZONA_HORARIA}')),
            fecha_cierre            Nullable(DateTime('{ZONA_HORARIA}')),
            fecha_rechazo           Nullable(DateTime('{ZONA_HORARIA}')),
            pasos_registrados       UInt8,
            dias_ciclo_total        Nullable(Decimal(12,2)),
            dias_hasta_desenlace    Nullable(Decimal(12,2)),
            dias_hasta_aprobacion   Nullable(Decimal(12,2)),
            dias_transito_retorno   Nullable(Decimal(12,2)),
            dias_hasta_inspeccion   Nullable(Decimal(12,2)),
            dias_hasta_reembolso    Nullable(Decimal(12,2)),
            monto_reembolsado       Decimal(14,2),
            metodo_reembolso        LowCardinality(String),
            fecha_reembolso         Nullable(DateTime('{ZONA_HORARIA}')),
            reembolso_registrado    UInt8,
            monto_reembolso_asiento Decimal(14,2),
            mes_pedido              Date,
            fecha_pedido            DateTime('{ZONA_HORARIA}'),
            canal_pedido            LowCardinality(String),
            total_pedido            Decimal(14,2),
            transportista_retorno   LowCardinality(String),
            bodega_retorno          LowCardinality(String),
            tiene_ticket            UInt8,
            fecha_carga             DateTime('{ZONA_HORARIA}')
        )
        ENGINE = MergeTree
        PARTITION BY toYYYYMM(fecha_solicitud)
        ORDER BY (fecha_solicitud, estado, motivo)
        """

    def columnas(self) -> list[str]:
        return [
            "devolucion_id", "numero", "pedido_id", "numero_pedido", "cliente_id",
            "fecha_solicitud", "mes", "estado", "es_terminal", "motivo",
            "monto_total", "lineas", "unidades",
            "fecha_aprobacion", "fecha_transito", "fecha_recepcion",
            "fecha_inspeccion", "fecha_cierre", "fecha_rechazo", "pasos_registrados",
            "dias_ciclo_total", "dias_hasta_desenlace", "dias_hasta_aprobacion",
            "dias_transito_retorno", "dias_hasta_inspeccion", "dias_hasta_reembolso",
            "monto_reembolsado", "metodo_reembolso", "fecha_reembolso",
            "reembolso_registrado", "monto_reembolso_asiento",
            "mes_pedido", "fecha_pedido", "canal_pedido", "total_pedido",
            "transportista_retorno", "bodega_retorno", "tiene_ticket", "fecha_carga",
        ]

    # ── Extracción ───────────────────────────────────────────────────────────

    def sql_extraccion(self) -> str:
        terminales = ", ".join(f"'{e}'" for e in sorted(ESTADOS_TERMINALES))
        return f"""
        WITH {_pivote()},
        detalle AS (
            SELECT devolucion_id, count(*) AS lineas, SUM(cantidad) AS unidades
            FROM devolucion_detalle GROUP BY devolucion_id
        ),
        asiento AS (
            -- El registro de TESORERÍA, que no es el mismo hecho que el monto
            -- de la cabecera (C4.1). Agregado y no unido en crudo: aunque hoy
            -- sea 1:1 (85 filas / 85 devoluciones), un segundo reembolso sobre
            -- la misma devolución duplicaría la fila del hecho.
            SELECT devolucion_id, count(*) AS asientos, SUM(monto) AS monto
            FROM reembolso WHERE devolucion_id IS NOT NULL GROUP BY devolucion_id
        )
        SELECT
            d.id                                            AS devolucion_id,
            d.numero,
            d.pedido_id,
            p.numero                                        AS numero_pedido,
            d.cliente_id,
            -- De la CABECERA y no del historial: 5 devoluciones no tienen hito
            -- 'solicitada' y se quedarían sin período (mismo fallo que C2.1).
            d.fecha_creacion                                AS fecha_solicitud,
            (date_trunc('month',
                d.fecha_creacion AT TIME ZONE '{ZONA_HORARIA}'))::date AS mes,
            d.estado,
            CASE WHEN d.estado IN ({terminales}) THEN 1 ELSE 0 END AS es_terminal,
            md.nombre                                       AS motivo,
            d.monto_total,
            COALESCE(det.lineas, 0)                         AS lineas,
            COALESCE(det.unidades, 0)                       AS unidades,
            h.f_aprobada                                    AS fecha_aprobacion,
            h.f_en_transito                                 AS fecha_transito,
            h.f_recibida                                    AS fecha_recepcion,
            h.f_inspeccionada                               AS fecha_inspeccion,
            h.f_cerrada                                     AS fecha_cierre,
            h.f_rechazada                                   AS fecha_rechazo,
            COALESCE(h.pasos, 0)                            AS pasos_registrados,
            -- El tramo del DISEÑO: solo las 35 con cierre.
            {_dias('h.f_cerrada', 'd.fecha_creacion')}      AS dias_ciclo_total,
            -- El tramo CORREGIDO (C4.2): cierre O rechazo, los 53 desenlaces.
            {_dias('COALESCE(h.f_cerrada, h.f_rechazada)', 'd.fecha_creacion')}
                                                            AS dias_hasta_desenlace,
            {_dias('h.f_aprobada', 'd.fecha_creacion')}     AS dias_hasta_aprobacion,
            {_dias('h.f_recibida', 'h.f_en_transito')}      AS dias_transito_retorno,
            {_dias('h.f_inspeccionada', 'h.f_recibida')}    AS dias_hasta_inspeccion,
            {_dias('d.fecha_reembolso', 'd.fecha_creacion')} AS dias_hasta_reembolso,
            COALESCE(d.monto_reembolsado, 0)                AS monto_reembolsado,
            COALESCE(NULLIF(TRIM(d.metodo_reembolso), ''), '{SIN_METODO}')
                                                            AS metodo_reembolso,
            d.fecha_reembolso,
            CASE WHEN a.devolucion_id IS NOT NULL THEN 1 ELSE 0 END
                                                            AS reembolso_registrado,
            COALESCE(a.monto, 0)                            AS monto_reembolso_asiento,
            (date_trunc('month',
                p.fecha_pedido AT TIME ZONE '{ZONA_HORARIA}'))::date AS mes_pedido,
            p.fecha_pedido,
            p.canal                                         AS canal_pedido,
            p.total                                         AS total_pedido,
            COALESCE(t.nombre, '{SIN_TRANSPORTISTA}')       AS transportista_retorno,
            COALESCE(b.nombre, '{SIN_BODEGA}')              AS bodega_retorno,
            CASE WHEN d.ticket_soporte_id IS NOT NULL THEN 1 ELSE 0 END AS tiene_ticket
        FROM devolucion d
        JOIN pedido p              ON p.id  = d.pedido_id
        JOIN motivo_devolucion md  ON md.id = d.motivo_devolucion_id
        LEFT JOIN hitos h          ON h.devolucion_id = d.id
        LEFT JOIN detalle det      ON det.devolucion_id = d.id
        LEFT JOIN asiento a        ON a.devolucion_id = d.id
        LEFT JOIN transportista t  ON t.id = d.transportista_id
        LEFT JOIN bodega b         ON b.id = d.bodega_id
        ORDER BY d.fecha_creacion, d.id
        """

    # ── Transformación: lista blanca de estados ──────────────────────────────

    def transformar(self, lote: list[tuple]) -> list[tuple]:
        ahora = datetime.now(ZoneInfo(ZONA_HORARIA)).replace(microsecond=0)
        i_estado = self.columnas().index("estado")

        salida = []
        for fila in lote:
            self._vigilar(fila[i_estado])
            salida.append(tuple(fila) + (ahora,))

        self.excepciones = sum(self.valores_nuevos.values())
        return salida

    def _vigilar(self, estado: str) -> None:
        """
        No remapea: avisa. `devolucion.estado` está cerrado por CHECK, así que
        un valor nuevo significa que el ciclo del RMA cambió y hay que revisar
        qué es terminal — no traducirlo aquí a escondidas.
        """
        if estado in ESTADOS_CONOCIDOS:
            return
        self.valores_nuevos[estado] = self.valores_nuevos.get(estado, 0) + 1
        if self.valores_nuevos[estado] == 1:
            logger.warning(
                f"[{self.nombre}] estado NO previsto en `devolucion`: «{estado}». "
                f"Se carga tal cual y queda contado. Revisa si es TERMINAL: "
                f"`es_terminal` decide el denominador de OTD-LOG-07."
            )

    # ── Controles ────────────────────────────────────────────────────────────

    def sql_controles(self) -> str:
        return f"""
        WITH {_pivote()}
        SELECT
            (SELECT count(*) FROM devolucion)                          AS filas,
            (SELECT count(DISTINCT pedido_id) FROM devolucion)         AS pedidos,
            (SELECT ROUND(SUM(monto_total), 2) FROM devolucion)        AS suma_monto,
            (SELECT count(DISTINCT estado) FROM devolucion)            AS estados,
            (SELECT count(DISTINCT motivo_devolucion_id) FROM devolucion) AS motivos,
            (SELECT count(*) FROM devolucion WHERE estado IN ('cerrada','rechazada'))
                                                                       AS terminales,
            (SELECT count(DISTINCT date_trunc('month',
                 fecha_creacion AT TIME ZONE '{ZONA_HORARIA}')) FROM devolucion)
                                                                       AS meses,
            -- ── Reembolso: las DOS cifras (C4.1), a propósito sin reconciliar ──
            (SELECT count(*) FROM devolucion WHERE monto_reembolsado IS NOT NULL)
                                                                       AS con_monto_reembolsado,
            (SELECT ROUND(COALESCE(SUM(monto_reembolsado), 0), 2) FROM devolucion)
                                                                       AS suma_reembolsado,
            (SELECT count(*) FROM reembolso WHERE devolucion_id IS NOT NULL)
                                                                       AS asientos_reembolso,
            (SELECT ROUND(COALESCE(SUM(monto), 0), 2) FROM reembolso
              WHERE devolucion_id IS NOT NULL)                         AS suma_asientos,
            (SELECT count(DISTINCT devolucion_id) FROM reembolso
              WHERE devolucion_id IS NOT NULL)                         AS devs_con_asiento,
            (SELECT count(*) FROM reembolso r JOIN devolucion d ON d.id = r.devolucion_id
              WHERE d.monto_reembolsado IS DISTINCT FROM r.monto)      AS asiento_discrepante,
            (SELECT count(DISTINCT metodo_reembolso) FROM devolucion
              WHERE metodo_reembolso IS NOT NULL)                      AS metodos,
            -- ── Cobertura de hitos ────────────────────────────────────────────
            (SELECT count(*) FROM hitos WHERE f_aprobada      IS NOT NULL) AS con_aprobacion,
            (SELECT count(*) FROM hitos WHERE f_en_transito   IS NOT NULL) AS con_transito,
            (SELECT count(*) FROM hitos WHERE f_recibida      IS NOT NULL) AS con_recepcion,
            (SELECT count(*) FROM hitos WHERE f_inspeccionada IS NOT NULL) AS con_inspeccion,
            (SELECT count(*) FROM hitos WHERE f_cerrada       IS NOT NULL) AS con_cierre,
            (SELECT count(*) FROM hitos WHERE f_rechazada     IS NOT NULL) AS con_rechazo,
            (SELECT count(*) FROM hitos WHERE pasos >= 3)                  AS con_3_pasos,
            (SELECT count(*) FROM historial_estado_devolucion)             AS registros_historial,
            -- Un hito repetido rompería el pivote por `min` sin dar error.
            (SELECT count(*) FROM (SELECT devolucion_id, estado
                                     FROM historial_estado_devolucion
                                    GROUP BY 1,2 HAVING count(*) > 1) r)   AS hitos_repetidos,
            -- La fecha de solicitud: cabecera vs historial (§3 del docstring).
            (SELECT count(*) FROM devolucion d
              WHERE NOT EXISTS (SELECT 1 FROM historial_estado_devolucion he
                                 WHERE he.devolucion_id = d.id AND he.estado = 'solicitada'))
                                                                       AS sin_hito_solicitada,
            (SELECT count(*) FROM devolucion d
               JOIN historial_estado_devolucion he
                 ON he.devolucion_id = d.id AND he.estado = 'solicitada'
              WHERE he.fecha_creacion <> d.fecha_creacion)             AS solicitud_discrepante,
            -- ── Integridad ────────────────────────────────────────────────────
            (SELECT count(*) FROM devolucion d
              WHERE NOT EXISTS (SELECT 1 FROM pedido p WHERE p.id = d.pedido_id))
                                                                       AS sin_pedido,
            (SELECT count(*) FROM devolucion WHERE cliente_id IS NULL) AS sin_cliente,
            (SELECT count(*) FROM (SELECT pedido_id FROM devolucion
                                    GROUP BY 1 HAVING count(*) > 1) x) AS pedidos_con_2_devs,
            -- Ningún hito hacia atrás: si los hubiera, un tramo saldría negativo.
            (SELECT count(*) FROM devolucion d JOIN hitos h ON h.devolucion_id = d.id
              WHERE h.f_aprobada < d.fecha_creacion
                 OR h.f_cerrada  < d.fecha_creacion
                 OR h.f_recibida < h.f_en_transito
                 OR h.f_inspeccionada < h.f_recibida
                 OR d.fecha_reembolso < d.fecha_creacion)              AS hitos_hacia_atras,
            -- Σ de las líneas contra el total que mantiene el trigger, CON LA
            -- FÓRMULA DEL TRIGGER. Decía comparar contra el trigger y usaba
            -- `cantidad * precio_unitario`, que NO resta el descuento de la
            -- línea; `fn_recalcular_total_devolucion` sí lo resta:
            --     SUM(cantidad * (precio_unitario - monto_descuento/cantidad))
            -- El control pasaba por casualidad: de las 275 líneas de las 197
            -- devoluciones sembradas, solo 16 caen sobre pedidos con descuento.
            -- Al cargar la posventa de la década el sesgo se hizo visible —
            -- 10.168.161,15 contra 10.157.265,70, y la diferencia de 10.895,45
            -- era EXACTAMENTE el descuento—, así que el control denunciaba al
            -- trigger por su propio error de fórmula.
            (SELECT ROUND(SUM(dd.cantidad *
                              (pd.precio_unitario - (pd.monto_descuento / pd.cantidad))), 2)
               FROM devolucion_detalle dd
               JOIN pedido_detalle pd ON pd.id = dd.pedido_detalle_id) AS suma_lineas,
            -- Comparar SUMAS deja que dos errores de signo contrario se
            -- cancelen. Se cuenta la discrepancia fila a fila, que es más
            -- estricto, y se separa la parte heredada de la nueva.
            (SELECT count(*) FROM devolucion d
               JOIN (SELECT dd.devolucion_id,
                            SUM(dd.cantidad *
                                (pd.precio_unitario - (pd.monto_descuento / pd.cantidad))) s
                       FROM devolucion_detalle dd
                       JOIN pedido_detalle pd ON pd.id = dd.pedido_detalle_id
                      GROUP BY 1) x ON x.devolucion_id = d.id
              WHERE ROUND(x.s, 2) <> d.monto_total)                    AS totales_discrepantes,
            (SELECT count(*) FROM devolucion d
               JOIN (SELECT dd.devolucion_id,
                            SUM(dd.cantidad *
                                (pd.precio_unitario - (pd.monto_descuento / pd.cantidad))) s
                       FROM devolucion_detalle dd
                       JOIN pedido_detalle pd ON pd.id = dd.pedido_detalle_id
                      GROUP BY 1) x ON x.devolucion_id = d.id
              WHERE ROUND(x.s, 2) <> d.monto_total
                AND d.id >= 2600000000)                                AS discrepantes_nuevas,
            (SELECT SUM(cantidad) FROM devolucion_detalle)             AS unidades_totales,
            (SELECT count(*) FROM devolucion WHERE ticket_soporte_id IS NOT NULL)
                                                                       AS con_ticket,
            (SELECT ROUND(SUM(p.total), 2) FROM devolucion d
               JOIN pedido p ON p.id = d.pedido_id)                    AS suma_total_pedido
        """

    #: Cifras que deben coincidir EXACTAS entre origen y destino.
    _EQUIVALENCIAS = (
        ("pedidos",               "countDistinct(pedido_id)"),
        ("suma_monto",            "sum(monto_total)"),
        ("estados",               "countDistinct(estado)"),
        ("motivos",               "countDistinct(motivo)"),
        ("terminales",            "countIf(es_terminal = 1)"),
        ("meses",                 "countDistinct(mes)"),
        ("con_monto_reembolsado", "countIf(monto_reembolsado > 0)"),
        ("suma_reembolsado",      "sum(monto_reembolsado)"),
        ("devs_con_asiento",      "countIf(reembolso_registrado = 1)"),
        ("suma_asientos",         "sum(monto_reembolso_asiento)"),
        ("con_aprobacion",        "countIf(fecha_aprobacion IS NOT NULL)"),
        ("con_transito",          "countIf(fecha_transito IS NOT NULL)"),
        ("con_recepcion",         "countIf(fecha_recepcion IS NOT NULL)"),
        ("con_inspeccion",        "countIf(fecha_inspeccion IS NOT NULL)"),
        ("con_cierre",            "countIf(fecha_cierre IS NOT NULL)"),
        ("con_rechazo",           "countIf(fecha_rechazo IS NOT NULL)"),
        ("con_3_pasos",           "countIf(pasos_registrados >= 3)"),
        ("unidades_totales",      "sum(unidades)"),
        ("con_ticket",            "countIf(tiene_ticket = 1)"),
        ("suma_total_pedido",     "sum(total_pedido)"),
    )

    def validar(self, client, tabla_staging: str, controles: dict) -> list[str]:
        errores = self._validar_origen(controles)

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

        errores += self._validar_tramos(client, tabla_staging, controles)
        return errores

    def _validar_origen(self, controles: dict) -> list[str]:
        errores = []
        if int(controles["sin_pedido"]):
            errores.append(f"{controles['sin_pedido']} devoluciones apuntan a un pedido "
                           f"inexistente: el JOIN las perdería en silencio.")
        if int(controles["sin_cliente"]):
            errores.append(f"{controles['sin_cliente']} devoluciones sin cliente.")
        if int(controles["hitos_repetidos"]):
            errores.append(
                f"{controles['hitos_repetidos']} pares (devolución, estado) repetidos en "
                f"el historial. El pivote usa `min`, así que se quedaría con el primero "
                f"y perdería el reintento sin avisar."
            )
        if int(controles["hitos_hacia_atras"]):
            errores.append(f"{controles['hitos_hacia_atras']} devoluciones con un hito "
                           f"anterior al anterior: algún tramo saldría negativo.")
        if int(controles["solicitud_discrepante"]):
            errores.append(
                f"{controles['solicitud_discrepante']} devoluciones cuyo hito "
                f"'solicitada' NO coincide con `devolucion.fecha_creacion`. La fecha de "
                f"solicitud se toma de la cabecera; si las dos divergen, hay que decidir "
                f"cuál es la buena antes de fechar el informe con ella."
            )
        # El trigger `fn_recalcular_total_devolucion` mantiene `monto_total`: si
        # deja de cuadrar con las líneas, el monto devuelto de VEN-14 y el
        # detalle de LOG-08 dejan de ser la misma cifra.
        # El veredicto es la discrepancia FILA A FILA de lo que esta fase
        # escribió. Cero tolerancia ahí.
        if int(controles["discrepantes_nuevas"]):
            errores.append(
                f"{controles['discrepantes_nuevas']} devoluciones tienen un "
                f"monto_total distinto de la suma de sus líneas. El trigger de "
                f"total dejó de cuadrar."
            )
        # Y 16 devoluciones HEREDADAS que no cuadran, y que no son un fallo de
        # carga sino una foto vieja: se sembraron en el script 63 y los
        # descuentos llegaron después, en los 71-73, sin que nada volviera a
        # disparar el trigger. Su total quedó calculado a precio sin descuento
        # (la 23 guarda 165,29 donde la fórmula da 140,50). Se declaran y se
        # vigilan: si aparece una decimoséptima, es que algo nuevo se rompió.
        _LEGADAS_DESCUADRADAS = 16
        heredadas = int(controles["totales_discrepantes"]) - int(controles["discrepantes_nuevas"])
        if heredadas > _LEGADAS_DESCUADRADAS:
            errores.append(
                f"{heredadas} devoluciones heredadas descuadran, y solo "
                f"{_LEGADAS_DESCUADRADAS} están declaradas (total sembrado antes "
                f"de que los scripts 71-73 aplicaran los descuentos)."
            )
        # C4.1 en forma de control: las dos cifras del reembolso son distintas y
        # tienen que SEGUIR siéndolo de forma explicable.
        diferencia = int(controles["con_monto_reembolsado"]) - int(controles["devs_con_asiento"])
        if diferencia < 0:
            errores.append(
                f"Hay {controles['devs_con_asiento']} asientos de reembolso sobre "
                f"{controles['con_monto_reembolsado']} devoluciones con monto: sobra un "
                f"asiento sin monto en la cabecera. Las dos cifras se cargan sin "
                f"reconciliar (C4.1), pero un asiento huérfano no está previsto."
            )
        if int(controles["asiento_discrepante"]):
            errores.append(
                f"{controles['asiento_discrepante']} reembolsos cuyo monto NO coincide "
                f"con el de la cabecera de su devolución. C4.1 declara que difieren en "
                f"COBERTURA (una devolución sin asiento), no en importe: si además "
                f"discrepan en el monto, LOG-10 tiene que elegir y decirlo."
            )
        return errores

    def _validar_tramos(self, client, tabla_staging: str, controles: dict) -> list[str]:
        """
        Los tramos: cada uno con su denominador, y ninguno negativo.

        Es la lección de C2.7 aplicada aquí: cuatro medias de días puestas en
        fila, calculadas sobre poblaciones distintas y presentadas sin decirlo,
        hacen que el cuello de botella del RMA parezca estar donde no está.
        """
        errores = []
        fila = client.query(f"""
            SELECT countIf(dias_ciclo_total      IS NOT NULL),
                   countIf(dias_hasta_desenlace  IS NOT NULL),
                   countIf(dias_hasta_aprobacion IS NOT NULL),
                   countIf(dias_transito_retorno IS NOT NULL),
                   countIf(dias_hasta_reembolso  IS NOT NULL),
                   countIf(dias_ciclo_total < 0 OR dias_hasta_desenlace < 0
                        OR dias_hasta_aprobacion < 0 OR dias_transito_retorno < 0
                        OR dias_hasta_inspeccion < 0 OR dias_hasta_reembolso < 0),
                   countIf(es_terminal = 1 AND dias_hasta_desenlace IS NULL),
                   countIf(metodo_reembolso = '{SIN_METODO}' AND monto_reembolsado > 0)
            FROM {tabla_staging}
        """).result_rows[0]
        (ciclo, desenlace, aprobacion, transito, reembolso, negativos,
         terminal_sin_dias, monto_sin_metodo) = fila

        for etiqueta, obtenido, esperado in (
            ("dias_ciclo_total",        ciclo,      int(controles["con_cierre"])),
            ("dias_hasta_aprobacion",   aprobacion, int(controles["con_aprobacion"])),
            ("dias_transito_retorno",   transito,   int(controles["con_recepcion"])),
            ("dias_hasta_reembolso",    reembolso,  int(controles["con_monto_reembolsado"])),
        ):
            if obtenido != esperado:
                errores.append(f"{etiqueta}: {obtenido} medidos donde el origen permite "
                               f"{esperado}.")

        esperado_desenlace = int(controles["con_cierre"]) + int(controles["con_rechazo"])
        if desenlace != esperado_desenlace:
            errores.append(f"dias_hasta_desenlace: {desenlace} medidos donde hay "
                           f"{esperado_desenlace} desenlaces (cierre + rechazo).")
        if negativos:
            errores.append(f"{negativos} devoluciones con algún tramo NEGATIVO.")
        if terminal_sin_dias:
            errores.append(
                f"{terminal_sin_dias} devoluciones en estado terminal sin "
                f"`dias_hasta_desenlace`. El estado dice que terminaron y el historial "
                f"no lo registra: OTD-LOG-07 mediría sobre menos casos de los que hay."
            )
        if monto_sin_metodo:
            errores.append(f"{monto_sin_metodo} devoluciones con monto reembolsado y sin "
                           f"vía: OTD-LOG-10 agrupa por vía y las perdería.")
        return errores
