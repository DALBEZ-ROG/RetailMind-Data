"""
etl/dwh/tablas/fact_ticket.py — F12 del modelo (§5.12 del diseño).

La mesa de ayuda. Grano: **un ticket**. 248 filas. Alimenta OTD-SOP-02, SOP-03,
SOP-06, SOP-07 y el lado «reclamos» de SOP-08 — cinco informes sobre una tabla
de 248 filas, la mejor relación informes/filas del modelo.

═══════════════════════════════════════════════════════════════════════════════
1. UN TICKET NO TIENE CATEGORÍA, Y EL JOIN DEL DISEÑO LO PERDERÍA — C4.3
═══════════════════════════════════════════════════════════════════════════════

§5.12 declara el origen como «`ticket_soporte` ⋈ `categoria_ticket` ⟕ …»: JOIN
interno con la categoría. Y `ticket_soporte.categoria_ticket_id` es **nullable**:

    tickets ...................... 248
    con categoría ................ 247
    SIN categoría ................   1     ← el JOIN interno lo tira

Un ticket menos sobre 248 es un 0,4 %: ningún conteo escandaliza, ninguna suma
falla, y SOP-04 («tickets por categoría») publicaría 247 como si fueran todos.
Es la misma mecánica de C1.1, esta vez con un LEFT JOIN de por medio.

**Cómo se resolvió.** `LEFT JOIN` y centinela `'sin_categoria'`. Un ticket sin
clasificar no es un ticket que no existe: es precisamente el que hay que ir a
clasificar, así que aparece en el informe con su etiqueta.

═══════════════════════════════════════════════════════════════════════════════
2. «RESUELTO» Y «CERRADO» NO SON EL MISMO HECHO — C4.5
═══════════════════════════════════════════════════════════════════════════════

Solo `cerrado` escribe `fecha_cierre`. El estado `resuelto` es un paso previo —
el cliente todavía puede responder y reabrirlo (grant de `UPDATE(estado)` a
`grp_cliente`, script 37):

    estado 'cerrado' .................  76   ← y los 76 tienen fecha_cierre
    estado 'resuelto' ................  44   ← ninguno tiene fecha_cierre
    'resuelto' + 'cerrado' ........... 120
    tickets con fecha_cierre .........  76
    tickets con cierre anterior a su creación .... 0

Los tres informes de tiempos (SOP-03, SOP-07 y el cumplimiento de SOP-02) miden
con la FECHA, así que su base son 76 y no 120. Es la elección correcta —no hay
instante que restar en los 44— pero hay que decirla: un lector que sepa que «hay
120 tickets resueltos» y lea «76 medidos» necesita saber por qué.

La tabla lleva las dos: `fecha_cierre` (76) y `resuelto_por_estado` (120).

═══════════════════════════════════════════════════════════════════════════════
3. LA PRIMERA RESPUESTA ES UNA DECISIÓN, Y SE MIDE CUÁNTO CUESTA — C4.6
═══════════════════════════════════════════════════════════════════════════════

Definición adoptada (la del catálogo): *el primer mensaje cuyo autor es del
equipo (`usuario_id` poblado) y que el cliente puede ver (`es_interno = false`)*.
Una nota interna entre agentes no es una respuesta al cliente.

El diseño advertía que otra definición da otro número. Medido, la diferencia no
es teórica — es una base distinta Y un tiempo distinto:

    tickets con ALGÚN mensaje del equipo ................ 244
      cuya primera intervención es una NOTA INTERNA .....  32
      sin ninguna respuesta visible (solo notas) ........  51
    tickets con primera respuesta VISIBLE ............... 193     ← la adoptada

    retraso medio entre la primera nota interna y la
    primera respuesta visible, en los 32 .............. +1,35 h

Con la definición laxa, SOP-06 mediría sobre **244** tickets y con un tiempo
sistemáticamente MENOR. Ninguna de las dos cifras es falsa; presentar una sin
decir cuál lo es. Por eso la tabla trae también `fecha_primer_mensaje_equipo`,
y la pantalla del informe escribe la definición encima de la tabla.

═══════════════════════════════════════════════════════════════════════════════
4. EL CUMPLIMIENTO DE SOP-02 NO SE CONGELA EN LA CARGA
═══════════════════════════════════════════════════════════════════════════════

`cumplio_sla` solo se calcula donde hay cierre: `fecha_cierre <= fecha_limite`,
y viaja **NULL** en los 172 abiertos. NULL y no 0, por lo mismo que
`entregado_a_tiempo` en `fact_envio`: un 0 dice «incumplió» y lo que pasa es que
«no se sabe todavía».

Y la cuarta categoría de SOP-02 —«abierto y ya vencido»— **no se precalcula**:
depende de `now()`, y un `UInt8` grabado a las 03:00 de la carga estaría
equivocado a las 09:00. La tabla da `fecha_limite` y `fecha_cierre`; el informe
parte la base en las cuatro categorías **en el momento de la consulta**.

Medido hoy (2026-07-31), la partición es reveladora:

    cerrados a tiempo ..........  12
    cerrados tarde .............  64
    abiertos dentro de plazo ...   0
    abiertos y ya vencidos ..... 172     ← toda la cola viva está vencida

Los 172 son la categoría accionable, y un porcentaje calculado sobre 248 «como
si todos hubieran cerrado» daría 12/248 = 4,8 % de cumplimiento: un número
falso, porque mezcla lo incumplido con lo desconocido.

`horas_sla_comprometidas` (2 / 4 / 24 / 72 según prioridad) viaja como columna
propia y NO se usa para recalcular la fecha límite: `fecha_limite` está poblada
en las 248 desde el script 49 y es la que rige. La columna existe para que el
informe pueda agrupar por compromiso sin traducir prioridades.

Los tiempos van en `Decimal(12,2)` y no en el `Float32` de §5.12, por la razón
ya registrada en C2.5.

Bitácora completa: `docs/estrategico/CORRECCIONES_DISENO_ETL.md`.
"""

from datetime import datetime
from zoneinfo import ZoneInfo

from etl.dwh.conexiones import ZONA_HORARIA, logger
from etl.dwh.tarea import TareaCarga

#: Los 5 estados del ciclo del ticket, todos en uso.
ESTADOS_CONOCIDOS = frozenset({
    "abierto", "en_proceso", "esperando_cliente", "resuelto", "cerrado",
})

#: Estados que el negocio considera «ya atendido». OJO: solo `cerrado` escribe
#: `fecha_cierre` (C4.5).
ESTADOS_RESUELTOS = frozenset({"resuelto", "cerrado"})

#: Las 4 prioridades con su compromiso en horas (script 49; la misma tabla que
#: usa `SoporteService` para calcular `fecha_limite`).
SLA_HORAS = {"urgente": 2, "alta": 4, "media": 24, "baja": 72}

#: Ausencias que son el dato, no un hueco.
SIN_AGENTE = "(sin asignar)"
SIN_CATEGORIA = "sin_categoria"
SIN_PRODUCTO = "sin_producto"


def _horas(fin: str, inicio: str) -> str:
    """Horas entre dos instantes, con dos decimales. Ver `_dias` en fact_devolucion."""
    return (f"CASE WHEN {fin} IS NULL OR {inicio} IS NULL THEN NULL "
            f"ELSE ROUND((EXTRACT(EPOCH FROM ({fin} - {inicio})) / 3600.0)::numeric, 2) "
            f"END")


#: CTE de la primera respuesta. Vive en una constante porque el control lo
#: reutiliza: la definición de «primera respuesta» tiene que estar escrita UNA
#: vez, o la validación acabaría confirmando la misma equivocación que el ETL.
_CTE_RESPUESTA = """
    respuesta AS (
        SELECT ticket_soporte_id,
               min(fecha_creacion) FILTER (
                   WHERE usuario_id IS NOT NULL AND es_interno = false
               )                                        AS primera_visible,
               min(fecha_creacion) FILTER (
                   WHERE usuario_id IS NOT NULL
               )                                        AS primer_mensaje_equipo,
               count(*)                                 AS mensajes,
               count(*) FILTER (WHERE es_interno)        AS mensajes_internos
        FROM mensaje_ticket
        GROUP BY ticket_soporte_id
    )"""


class FactTicket(TareaCarga):

    nombre = "fact_ticket"

    def __init__(self):
        super().__init__()
        self.valores_nuevos: dict[str, int] = {}

    # ── Definición ───────────────────────────────────────────────────────────

    def ddl(self, nombre_tabla: str) -> str:
        return f"""
        CREATE TABLE {nombre_tabla}
        (
            ticket_id                   UInt32,
            numero                      String,
            fecha_creacion              DateTime('{ZONA_HORARIA}'),
            mes                         Date,
            categoria                   LowCardinality(String),
            prioridad                   LowCardinality(String),
            estado                      LowCardinality(String),
            resuelto_por_estado         UInt8,
            agente                      LowCardinality(String),
            agente_id                   UInt32,
            cliente_id                  UInt32,
            pedido_id                   UInt32,
            producto_variante_id        UInt32,
            producto_id                 UInt32,
            producto_nombre             String,
            categoria_producto          LowCardinality(String),
            tiene_producto              UInt8,
            fecha_limite                DateTime('{ZONA_HORARIA}'),
            fecha_cierre                Nullable(DateTime('{ZONA_HORARIA}')),
            fecha_primera_respuesta     Nullable(DateTime('{ZONA_HORARIA}')),
            fecha_primer_mensaje_equipo Nullable(DateTime('{ZONA_HORARIA}')),
            mensajes                    UInt16,
            mensajes_internos           UInt16,
            horas_primera_respuesta     Nullable(Decimal(12,2)),
            horas_hasta_mensaje_equipo  Nullable(Decimal(12,2)),
            horas_resolucion            Nullable(Decimal(12,2)),
            cumplio_sla                 Nullable(UInt8),
            horas_sla_comprometidas     UInt16,
            fecha_carga                 DateTime('{ZONA_HORARIA}')
        )
        ENGINE = MergeTree
        PARTITION BY toYYYYMM(fecha_creacion)
        ORDER BY (fecha_creacion, categoria, prioridad)
        """

    def columnas(self) -> list[str]:
        return [
            "ticket_id", "numero", "fecha_creacion", "mes", "categoria", "prioridad",
            "estado", "resuelto_por_estado", "agente", "agente_id", "cliente_id",
            "pedido_id", "producto_variante_id", "producto_id", "producto_nombre",
            "categoria_producto", "tiene_producto", "fecha_limite", "fecha_cierre",
            "fecha_primera_respuesta", "fecha_primer_mensaje_equipo", "mensajes",
            "mensajes_internos", "horas_primera_respuesta",
            "horas_hasta_mensaje_equipo", "horas_resolucion", "cumplio_sla",
            "horas_sla_comprometidas", "fecha_carga",
        ]

    # ── Extracción ───────────────────────────────────────────────────────────

    def sql_extraccion(self) -> str:
        resueltos = ", ".join(f"'{e}'" for e in sorted(ESTADOS_RESUELTOS))
        sla = " ".join(f"WHEN '{p}' THEN {h}" for p, h in SLA_HORAS.items())
        return f"""
        WITH {_CTE_RESPUESTA}
        SELECT
            t.id                                        AS ticket_id,
            t.numero,
            t.fecha_creacion,
            (date_trunc('month',
                t.fecha_creacion AT TIME ZONE '{ZONA_HORARIA}'))::date AS mes,
            -- LEFT JOIN + centinela: el JOIN interno de §5.12 tiraría 1 de 248
            -- sin dar error (C4.3).
            COALESCE(ct.nombre, '{SIN_CATEGORIA}')      AS categoria,
            t.prioridad,
            t.estado,
            CASE WHEN t.estado IN ({resueltos}) THEN 1 ELSE 0 END AS resuelto_por_estado,
            -- El ticket sin agente es EL DATO ACCIONABLE de SOP-01/SOP-07, no
            -- un hueco: 33 de 248 esperan a que alguien los tome.
            COALESCE(NULLIF(TRIM(CONCAT_WS(' ', u.nombre, u.apellido)), ''),
                     '{SIN_AGENTE}')                    AS agente,
            COALESCE(t.asignado_usuario_id, 0)          AS agente_id,
            t.cliente_id,
            COALESCE(t.pedido_id, 0)                    AS pedido_id,
            COALESCE(t.producto_variante_id, 0)         AS producto_variante_id,
            COALESCE(p.id, 0)                           AS producto_id,
            COALESCE(p.nombre, '{SIN_PRODUCTO}')        AS producto_nombre,
            COALESCE(c.nombre, '{SIN_CATEGORIA}')       AS categoria_producto,
            CASE WHEN t.producto_variante_id IS NOT NULL THEN 1 ELSE 0 END
                                                        AS tiene_producto,
            t.fecha_limite,
            t.fecha_cierre,
            r.primera_visible                           AS fecha_primera_respuesta,
            r.primer_mensaje_equipo                     AS fecha_primer_mensaje_equipo,
            COALESCE(r.mensajes, 0)                     AS mensajes,
            COALESCE(r.mensajes_internos, 0)            AS mensajes_internos,
            {_horas('r.primera_visible', 't.fecha_creacion')}
                                                        AS horas_primera_respuesta,
            {_horas('r.primer_mensaje_equipo', 't.fecha_creacion')}
                                                        AS horas_hasta_mensaje_equipo,
            {_horas('t.fecha_cierre', 't.fecha_creacion')} AS horas_resolucion,
            -- NULL —y no 0— donde no hay cierre: «no se sabe» no es «incumplió».
            CASE WHEN t.fecha_cierre IS NULL OR t.fecha_limite IS NULL THEN NULL
                 WHEN t.fecha_cierre <= t.fecha_limite THEN 1 ELSE 0 END AS cumplio_sla,
            CASE t.prioridad {sla} ELSE 0 END           AS horas_sla_comprometidas
        FROM ticket_soporte t
        LEFT JOIN categoria_ticket ct ON ct.id = t.categoria_ticket_id
        LEFT JOIN usuario u           ON u.id  = t.asignado_usuario_id
        LEFT JOIN producto_variante v ON v.id  = t.producto_variante_id
        LEFT JOIN producto p          ON p.id  = v.producto_id
        LEFT JOIN producto_categoria pc
                                      ON pc.producto_id = p.id AND pc.es_principal
        LEFT JOIN categoria c         ON c.id  = pc.categoria_id
        LEFT JOIN respuesta r         ON r.ticket_soporte_id = t.id
        ORDER BY t.fecha_creacion, t.id
        """

    # ── Transformación ───────────────────────────────────────────────────────

    def transformar(self, lote: list[tuple]) -> list[tuple]:
        ahora = datetime.now(ZoneInfo(ZONA_HORARIA)).replace(microsecond=0)
        i_estado = self.columnas().index("estado")
        i_prio = self.columnas().index("prioridad")
        i_sla = self.columnas().index("horas_sla_comprometidas")

        salida = []
        for fila in lote:
            fila = list(fila)
            self._vigilar(fila[i_estado], ESTADOS_CONOCIDOS, "estado")
            # Una prioridad no prevista sale con SLA 0, que en un informe de
            # cumplimiento significaría «venció al nacer». Se avisa aquí porque
            # el CASE de SQL no puede.
            if fila[i_prio] not in SLA_HORAS:
                self._vigilar(fila[i_prio], frozenset(SLA_HORAS), "prioridad")
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
                f"tal cual y queda contado. Si es una prioridad, además llega con "
                f"`horas_sla_comprometidas = 0`: revísalo antes de que SOP-02 la "
                f"muestre como vencida desde el primer minuto."
            )

    # ── Controles ────────────────────────────────────────────────────────────

    def sql_controles(self) -> str:
        return f"""
        WITH {_CTE_RESPUESTA}
        SELECT
            (SELECT count(*) FROM ticket_soporte)                       AS filas,
            (SELECT count(*) FROM ticket_soporte WHERE fecha_cierre IS NOT NULL)
                                                                        AS cerrados,
            (SELECT count(*) FROM ticket_soporte
              WHERE estado IN ('resuelto','cerrado'))                   AS resueltos_por_estado,
            (SELECT count(*) FROM ticket_soporte WHERE fecha_limite IS NOT NULL)
                                                                        AS con_fecha_limite,
            (SELECT count(*) FROM ticket_soporte WHERE producto_variante_id IS NOT NULL)
                                                                        AS con_producto,
            (SELECT count(*) FROM ticket_soporte WHERE asignado_usuario_id IS NULL)
                                                                        AS sin_agente,
            (SELECT count(DISTINCT asignado_usuario_id) FROM ticket_soporte
              WHERE asignado_usuario_id IS NOT NULL)                    AS agentes,
            (SELECT count(*) FROM ticket_soporte WHERE categoria_ticket_id IS NULL)
                                                                        AS sin_categoria,
            (SELECT count(DISTINCT categoria_ticket_id) FROM ticket_soporte
              WHERE categoria_ticket_id IS NOT NULL)                    AS categorias,
            (SELECT count(DISTINCT prioridad) FROM ticket_soporte)      AS prioridades,
            (SELECT count(DISTINCT estado) FROM ticket_soporte)         AS estados,
            (SELECT count(DISTINCT date_trunc('month',
                 fecha_creacion AT TIME ZONE '{ZONA_HORARIA}')) FROM ticket_soporte)
                                                                        AS meses,
            (SELECT count(*) FROM mensaje_ticket)                       AS mensajes,
            (SELECT count(*) FROM mensaje_ticket WHERE es_interno)      AS mensajes_internos,
            -- ── Primera respuesta: la adoptada y la laxa (C4.6) ───────────────
            (SELECT count(*) FROM respuesta WHERE primera_visible IS NOT NULL)
                                                                        AS con_primera_respuesta,
            (SELECT count(*) FROM respuesta WHERE primer_mensaje_equipo IS NOT NULL)
                                                                        AS con_mensaje_equipo,
            (SELECT count(*) FROM respuesta
              WHERE primer_mensaje_equipo IS NOT NULL AND primera_visible IS NULL)
                                                                        AS solo_notas_internas,
            (SELECT count(*) FROM respuesta
              WHERE primera_visible IS NOT NULL
                AND primera_visible <> primer_mensaje_equipo)           AS empiezan_por_nota,
            (SELECT ROUND(SUM(ROUND((EXTRACT(EPOCH FROM
                     (r.primera_visible - t.fecha_creacion)) / 3600.0)::numeric, 2)), 2)
               FROM ticket_soporte t JOIN respuesta r ON r.ticket_soporte_id = t.id
              WHERE r.primera_visible IS NOT NULL)                      AS suma_horas_respuesta,
            (SELECT ROUND(SUM(ROUND((EXTRACT(EPOCH FROM
                     (fecha_cierre - fecha_creacion)) / 3600.0)::numeric, 2)), 2)
               FROM ticket_soporte WHERE fecha_cierre IS NOT NULL)      AS suma_horas_resolucion,
            -- ── SLA: las CUATRO categorías, tal como las parte SOP-02 ─────────
            (SELECT count(*) FROM ticket_soporte
              WHERE fecha_cierre IS NOT NULL AND fecha_cierre <= fecha_limite)
                                                                        AS cerrados_a_tiempo,
            (SELECT count(*) FROM ticket_soporte
              WHERE fecha_cierre IS NOT NULL AND fecha_cierre > fecha_limite)
                                                                        AS cerrados_tarde,
            -- ── Integridad ────────────────────────────────────────────────────
            (SELECT count(*) FROM ticket_soporte WHERE fecha_cierre < fecha_creacion)
                                                                        AS cierre_antes_creacion,
            (SELECT count(*) FROM ticket_soporte t
               JOIN respuesta r ON r.ticket_soporte_id = t.id
              WHERE r.primera_visible < t.fecha_creacion)               AS respuesta_antes,
            (SELECT count(*) FROM ticket_soporte t
              WHERE t.producto_variante_id IS NOT NULL
                AND NOT EXISTS (SELECT 1 FROM producto_variante v
                                 WHERE v.id = t.producto_variante_id))  AS producto_inexistente,
            (SELECT count(*) FROM ticket_soporte t
              WHERE NOT EXISTS (SELECT 1 FROM cliente c WHERE c.id = t.cliente_id))
                                                                        AS sin_cliente,
            (SELECT count(*) FROM mensaje_ticket m
              WHERE NOT EXISTS (SELECT 1 FROM ticket_soporte t
                                 WHERE t.id = m.ticket_soporte_id))     AS mensajes_huerfanos,
            -- Un mensaje con los DOS autores rompería la definición de «del
            -- equipo»: hoy son 0, y si dejaran de serlo habría que decidir.
            (SELECT count(*) FROM mensaje_ticket
              WHERE usuario_id IS NOT NULL AND cliente_id IS NOT NULL)  AS mensaje_doble_autor,
            (SELECT count(*) FROM mensaje_ticket
              WHERE usuario_id IS NULL AND cliente_id IS NULL)          AS mensaje_sin_autor
        """

    _EQUIVALENCIAS = (
        ("cerrados",              "countIf(fecha_cierre IS NOT NULL)"),
        ("resueltos_por_estado",  "countIf(resuelto_por_estado = 1)"),
        ("con_producto",          "countIf(tiene_producto = 1)"),
        ("sin_agente",            f"countIf(agente = '{SIN_AGENTE}')"),
        ("prioridades",           "countDistinct(prioridad)"),
        ("estados",               "countDistinct(estado)"),
        ("meses",                 "countDistinct(mes)"),
        ("mensajes",              "sum(mensajes)"),
        ("mensajes_internos",     "sum(mensajes_internos)"),
        ("con_primera_respuesta", "countIf(fecha_primera_respuesta IS NOT NULL)"),
        ("con_mensaje_equipo",    "countIf(fecha_primer_mensaje_equipo IS NOT NULL)"),
        ("suma_horas_respuesta",  "sum(ifNull(horas_primera_respuesta, 0))"),
        ("suma_horas_resolucion", "sum(ifNull(horas_resolucion, 0))"),
        ("cerrados_a_tiempo",     "countIf(cumplio_sla = 1)"),
        ("cerrados_tarde",        "countIf(cumplio_sla = 0)"),
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

        errores += self._validar_categoria_y_sla(client, tabla_staging, controles)
        return errores

    def _validar_origen(self, controles: dict) -> list[str]:
        errores = []
        for clave, explicacion in (
            ("cierre_antes_creacion", "tickets cerrados antes de haberse abierto"),
            ("respuesta_antes",       "tickets respondidos antes de haberse abierto"),
            ("producto_inexistente",  "tickets que apuntan a una variante inexistente"),
            ("sin_cliente",           "tickets sin cliente"),
            ("mensajes_huerfanos",    "mensajes de un ticket inexistente"),
            ("mensaje_sin_autor",     "mensajes sin autor (ni usuario ni cliente)"),
        ):
            if int(controles[clave]) != 0:
                errores.append(f"{controles[clave]} {explicacion}.")

        if int(controles["mensaje_doble_autor"]):
            errores.append(
                f"{controles['mensaje_doble_autor']} mensajes con usuario Y cliente. "
                f"La definición de «primera respuesta» se apoya en que `usuario_id` "
                f"poblado significa «lo escribió el equipo» (C4.6): con los dos "
                f"autores, esa lectura deja de ser cierta."
            )
        # §5.12 declara `fecha_limite` poblada en las 248 (script 49). Si dejara
        # de estarlo, SOP-02 perdería la referencia contra la que juzga.
        if int(controles["con_fecha_limite"]) != int(controles["filas"]):
            errores.append(
                f"Solo {controles['con_fecha_limite']} de {controles['filas']} tickets "
                f"tienen `fecha_limite`. SOP-02 juzga contra ella y los que falten "
                f"quedarían fuera de las cuatro categorías sin aparecer en ninguna."
            )
        return errores

    def _validar_categoria_y_sla(self, client, tabla_staging: str,
                                 controles: dict) -> list[str]:
        errores = []
        fila = client.query(f"""
            SELECT countIf(categoria = '{SIN_CATEGORIA}'),
                   countDistinct(categoria),
                   countIf(cumplio_sla IS NULL),
                   countIf(cumplio_sla IS NOT NULL AND fecha_cierre IS NULL),
                   countIf(cumplio_sla IS NULL AND fecha_cierre IS NOT NULL),
                   countIf(horas_sla_comprometidas = 0),
                   countIf(horas_primera_respuesta < 0 OR horas_resolucion < 0),
                   countIf(tiene_producto = 1 AND producto_id = 0),
                   countIf(tiene_producto = 0 AND producto_id != 0),
                   countIf(fecha_primera_respuesta IS NOT NULL
                           AND fecha_primer_mensaje_equipo IS NULL)
            FROM {tabla_staging}
        """).result_rows[0]
        (sin_cat, categorias, sla_nulo, sla_sin_cierre, cierre_sin_sla, sla_cero,
         negativas, producto_incoherente, producto_sobrante, respuesta_sin_equipo) = fila

        # C4.3 en forma de control: el ticket sin categoría TIENE que llegar.
        if sin_cat != int(controles["sin_categoria"]):
            errores.append(
                f"Tickets sin categoría: origen {controles['sin_categoria']} vs destino "
                f"{sin_cat} etiquetados '{SIN_CATEGORIA}'. §5.12 los uniría con JOIN "
                f"interno y los perdería: un ticket sin clasificar es justo el que hay "
                f"que ir a clasificar."
            )
        esperadas = int(controles["categorias"]) + (1 if sin_cat else 0)
        if categorias != esperadas:
            errores.append(f"Categorías: origen {controles['categorias']} "
                           f"(+1 centinela) vs destino {categorias}.")

        abiertos = int(controles["filas"]) - int(controles["cerrados"])
        if sla_nulo != abiertos:
            errores.append(
                f"`cumplio_sla` en NULL: {sla_nulo} donde hay {abiertos} tickets sin "
                f"cerrar. El veredicto viaja NULL y no 0 porque «no se sabe» no es "
                f"«incumplió»: con un 0, SOP-02 contaría {abiertos} incumplimientos "
                f"que nadie ha cometido todavía."
            )
        if sla_sin_cierre or cierre_sin_sla:
            errores.append(f"Veredicto de SLA incoherente con el cierre "
                           f"({sla_sin_cierre} sin cierre y con veredicto, "
                           f"{cierre_sin_sla} con cierre y sin veredicto).")
        if sla_cero:
            errores.append(f"{sla_cero} tickets con `horas_sla_comprometidas = 0`: hay "
                           f"una prioridad fuera de las cuatro conocidas y SOP-02 la "
                           f"mostraría vencida desde el primer minuto.")
        if negativas:
            errores.append(f"{negativas} tickets con horas NEGATIVAS.")
        if producto_incoherente or producto_sobrante:
            errores.append(f"Marca de producto incoherente ({producto_incoherente} con "
                           f"marca y sin producto, {producto_sobrante} al revés).")
        if respuesta_sin_equipo:
            errores.append(f"{respuesta_sin_equipo} tickets con respuesta visible y sin "
                           f"primer mensaje del equipo: imposible por construcción, la "
                           f"visible es un subconjunto.")

        # C4.6 en forma de control: las dos definiciones tienen que SEGUIR
        # difiriendo de la forma medida, o la advertencia de la pantalla miente.
        laxa = int(controles["con_mensaje_equipo"])
        estricta = int(controles["con_primera_respuesta"])
        if laxa - estricta != int(controles["solo_notas_internas"]):
            errores.append(
                f"La distancia entre las dos definiciones de «primera respuesta» dejó "
                f"de cuadrar: {laxa} con mensaje del equipo − {estricta} con respuesta "
                f"visible ≠ {controles['solo_notas_internas']} solo con notas internas."
            )
        return errores
