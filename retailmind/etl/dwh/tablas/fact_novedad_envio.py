"""
etl/dwh/tablas/fact_novedad_envio.py — F9 del modelo (§5.9 del diseño).

Las incidencias de la última milla. Grano: **una novedad**. 176 filas
(hasta 3 por envío, sobre 173 envíos). Alimenta OTD-LOG-05.

Grano propio y no una columna de `fact_envio` porque el objetivo pregunta por
**tipo, intentos y desenlace**: son atributos de la incidencia, no del envío, y
un envío puede acumular varias con tipos distintos.

═══════════════════════════════════════════════════════════════════════════════
1. LOS VALORES DE `accion` NO SON LOS QUE DICE EL DISEÑO — C3C.3
═══════════════════════════════════════════════════════════════════════════════

§5.9 especifica «`accion` (`reprogramar` / `devolver_almacen`)». Son los nombres
de las OPERACIONES del API (`POST …/novedades/{id}/reprogramar` y
`/devolver-almacen`), no los valores que quedan guardados. En la base:

    devuelto_almacen ....... 120
    reprogramada ............  49
    (NULL, sin resolver) ....   7
                             ───
                             176

**Un filtro escrito desde el diseño —`accion = 'devolver_almacen'`— casa con
CERO filas.** Y no falla: devuelve una tabla vacía, que en un informe de
problemas de entrega se lee perfectamente como «no hubo devoluciones al
almacén». Es el mismo modo de fallo de toda esta bitácora, esta vez a un guion
de distancia.

La lista blanca del informe se toma por tanto de los datos y no del documento, y
la tarea valida que los valores cargados sean exactamente los conocidos: si
apareciera uno nuevo, la carga lo dice en vez de dejarlo pasar.

═══════════════════════════════════════════════════════════════════════════════
2. ABIERTAS Y RESUELTAS — el informe tiene que poder mostrar las dos
═══════════════════════════════════════════════════════════════════════════════

    resueltas (fecha_resolucion NOT NULL, estado 'resuelta') ..... 169
    abiertas  (sin resolución, estado 'abierta') .................   7
                                                                  ───
                                                                  176

Verificado que las dos formas de saberlo coinciden: **0** novedades en estado
'resuelta' sin fecha y **0** en 'abierta' con fecha. La columna `resuelta` se
deriva de la FECHA y no del estado —la fecha es el hecho, el estado es la
etiqueta—, y el control cruza ambas para que no puedan divergir sin avisar.

Las 7 abiertas importan: son las que llevan `horas_hasta_resolucion` en NULL, y
un informe que las descartara mostraría un tiempo medio de resolución calculado
sobre los casos que SÍ se resolvieron — el sesgo clásico de mirar solo los
finalizados. Cada fila del informe declara su base.

Distribución real (los 5 tipos del CHECK, todos en uso):

    cliente_rechazo ....... 39      dano_en_transito ...... 40
    cliente_ausente ....... 40      zona_dificil_acceso ... 29
    direccion_incorrecta .. 28

Y los intentos, que es la otra mitad de LOG-05: `intento_numero` va de 1 a 3.
Las que acaban en `devuelto_almacen` llegan hasta el intento 3; las
`reprogramada` se quedan en 2 — coherente con el tope de 3 intentos que impone
`VentasService` (intentos = 1 + reprogramaciones).

═══════════════════════════════════════════════════════════════════════════════
3. `horas_hasta_resolucion` EN Decimal Y NO EN Float32
═══════════════════════════════════════════════════════════════════════════════

§5.9 la especifica `Nullable(Float32)`. Se carga `Nullable(Decimal(12,2))` por
la misma razón ya declarada en C2.5 para los tramos de `fact_pedido`: el
comparador de `validar_dwh.py` rechaza todo float por construcción, y con
Float32 esta sería la única medida de la tabla imposible de validar contra
PostgreSQL. No es una corrección nueva — es la misma, aplicada otra vez.

`pedido_id` viaja denormalizado desde `novedad_envio` (que lo tiene en columna
propia) y se comprobó que **no diverge** del `envio.pedido_id` en ninguna de las
176 filas. Se carga el del envío de todos modos: una columna denormalizada que
hoy coincide no es garantía de que coincida mañana, y el enlace canónico es el
del envío.

Bitácora completa: `docs/estrategico/CORRECCIONES_DISENO_ETL.md`.
"""

from datetime import datetime
from zoneinfo import ZoneInfo

from etl.dwh.conexiones import ZONA_HORARIA, logger
from etl.dwh.tarea import TareaCarga
from etl.dwh.tablas.fact_envio import SIN_ZONA, FactEnvio

#: Los 5 tipos del CHECK de `novedad_envio`, todos en uso.
TIPOS_CONOCIDOS = frozenset({
    "cliente_ausente",
    "direccion_incorrecta",
    "cliente_rechazo",
    "zona_dificil_acceso",
    "dano_en_transito",
})

#: Las acciones REALES que guarda la base — no las del diseño (C3C.3).
#: `reprogramar` y `devolver_almacen` son los verbos del API; lo que queda
#: escrito es el participio.
ACCIONES_CONOCIDAS = frozenset({"reprogramada", "devuelto_almacen"})

#: Novedad todavía sin resolver: no tiene acción porque nadie la ha tomado.
SIN_ACCION = "sin_resolver"


class FactNovedadEnvio(TareaCarga):

    nombre = "fact_novedad_envio"

    #: La zona se hereda del envío, así que este hecho comparte el CTE de
    #: resolución de `fact_envio` en vez de reescribirlo. Es la misma regla de
    #: precedencia y tiene que dar el mismo resultado por construcción.
    depende_de = ("fact_envio",)

    def __init__(self):
        super().__init__()
        #: Valores no previstos de tipo o acción, con su recuento.
        self.valores_nuevos: dict[str, int] = {}

    # ── Definición ───────────────────────────────────────────────────────────

    def ddl(self, nombre_tabla: str) -> str:
        return f"""
        CREATE TABLE {nombre_tabla}
        (
            novedad_id             UInt32,
            envio_id               UInt32,
            envio_numero           String,
            pedido_id              UInt32,
            fecha_registro         DateTime('{ZONA_HORARIA}'),
            mes                    Date,
            tipo                   LowCardinality(String),
            estado                 LowCardinality(String),
            accion                 LowCardinality(String),
            transportista          LowCardinality(String),
            zona                   LowCardinality(String),
            intento_numero         UInt8,
            fecha_resolucion       Nullable(DateTime('{ZONA_HORARIA}')),
            horas_hasta_resolucion Nullable(Decimal(12,2)),
            resuelta               UInt8,
            estado_envio           LowCardinality(String),
            fecha_carga            DateTime('{ZONA_HORARIA}')
        )
        ENGINE = MergeTree
        PARTITION BY toYYYYMM(fecha_registro)
        ORDER BY (fecha_registro, tipo, transportista)
        """

    def columnas(self) -> list[str]:
        return [
            "novedad_id", "envio_id", "envio_numero", "pedido_id",
            "fecha_registro", "mes", "tipo", "estado", "accion", "transportista",
            "zona", "intento_numero", "fecha_resolucion",
            "horas_hasta_resolucion", "resuelta", "estado_envio", "fecha_carga",
        ]

    # ── Extracción ───────────────────────────────────────────────────────────

    def sql_extraccion(self) -> str:
        """
        La zona se resuelve con el MISMO CTE que `fact_envio` —importado, no
        copiado— para que las dos tablas no puedan discrepar sobre la zona del
        mismo envío. Si la regla de precedencia cambia, cambia en un solo sitio.
        """
        return f"""
        WITH {FactEnvio._CTE_ZONA}
        SELECT
            n.id                                               AS novedad_id,
            n.envio_id,
            e.numero                                           AS envio_numero,
            e.pedido_id,
            n.fecha_registro,
            (date_trunc('month',
                n.fecha_registro AT TIME ZONE '{ZONA_HORARIA}'))::date AS mes,
            n.tipo,
            n.estado,
            -- Crudo: la lista blanca y el aviso viven en transformar().
            COALESCE(n.accion, '')                             AS accion,
            t.nombre                                           AS transportista,
            COALESCE(zr.zona, '{SIN_ZONA}')                    AS zona,
            n.intento_numero,
            n.fecha_resolucion,
            -- `resuelta` se deriva de la FECHA, que es el hecho; `estado` es la
            -- etiqueta. El control cruza las dos para que no diverjan.
            CASE WHEN n.fecha_resolucion IS NULL THEN NULL
                 ELSE ROUND(
                     (EXTRACT(EPOCH FROM (n.fecha_resolucion - n.fecha_registro))
                      / 3600.0)::numeric, 2)
            END                                                AS horas_hasta_resolucion,
            CASE WHEN n.fecha_resolucion IS NOT NULL THEN 1 ELSE 0 END AS resuelta,
            e.estado                                           AS estado_envio
        FROM novedad_envio n
        JOIN envio e               ON e.id = n.envio_id
        JOIN transportista t       ON t.id = e.transportista_id
        LEFT JOIN zona_resuelta zr ON zr.envio_id = e.id
        ORDER BY n.fecha_registro, n.tipo, n.id
        """

    # ── Transformación: lista blanca de tipo y acción ────────────────────────

    def transformar(self, lote: list[tuple]) -> list[tuple]:
        ahora = datetime.now(ZoneInfo(ZONA_HORARIA)).replace(microsecond=0)
        i_tipo = self.columnas().index("tipo")
        i_accion = self.columnas().index("accion")

        salida = []
        for fila in lote:
            fila = list(fila)
            self._vigilar(fila[i_tipo], TIPOS_CONOCIDOS, "tipo")
            fila[i_accion] = self._normalizar_accion(fila[i_accion])
            salida.append(tuple(fila) + (ahora,))

        self.excepciones = sum(self.valores_nuevos.values())
        return salida

    def _normalizar_accion(self, crudo: str | None) -> str:
        """
        La acción vacía es la novedad SIN RESOLVER, no un dato perdido: 7 de 176.
        Se etiqueta para que LOG-05 pueda agrupar por desenlace sin que las
        abiertas caigan en una cadena vacía indistinguible de un fallo del join.
        """
        if not crudo:
            return SIN_ACCION
        valor = crudo.strip()
        self._vigilar(valor, ACCIONES_CONOCIDAS, "accion")
        return valor

    def _vigilar(self, valor: str, conocidos: frozenset, campo: str) -> None:
        """
        NO remapea: solo avisa. Estos son valores de un CHECK del motor, no texto
        libre, así que un valor nuevo significa que el negocio cambió y hay que
        revisar el informe — no que haya que traducirlo aquí a escondidas.
        """
        if valor in conocidos:
            return
        clave = f"{campo}={valor}"
        self.valores_nuevos[clave] = self.valores_nuevos.get(clave, 0) + 1
        if self.valores_nuevos[clave] == 1:
            logger.warning(
                f"[{self.nombre}] valor NO previsto en `{campo}`: «{valor}». Se carga "
                f"tal cual y queda contado en la bitácora. OJO: §5.9 ya se equivocó "
                f"con los valores de `accion` (C3C.3), así que la lista blanca sale de "
                f"los DATOS y no del diseño. Si el negocio añadió un valor, súmalo a "
                f"la constante antes de que el informe lo muestre sin etiqueta."
            )

    # ── Controles ────────────────────────────────────────────────────────────

    def sql_controles(self) -> str:
        return f"""
        SELECT
            (SELECT count(*) FROM novedad_envio)                       AS filas,
            (SELECT count(DISTINCT envio_id) FROM novedad_envio)       AS envios,
            (SELECT max(n) FROM (SELECT count(*) n FROM novedad_envio
                                 GROUP BY envio_id) x)                 AS max_por_envio,
            (SELECT count(*) FROM novedad_envio WHERE fecha_resolucion IS NOT NULL)
                                                                       AS resueltas,
            (SELECT count(*) FROM novedad_envio WHERE fecha_resolucion IS NULL)
                                                                       AS abiertas,
            (SELECT count(DISTINCT tipo) FROM novedad_envio)           AS tipos,
            (SELECT count(DISTINCT accion) FROM novedad_envio WHERE accion IS NOT NULL)
                                                                       AS acciones,
            (SELECT count(*) FROM novedad_envio WHERE accion = 'devuelto_almacen')
                                                                       AS devueltas_almacen,
            (SELECT count(*) FROM novedad_envio WHERE accion = 'reprogramada')
                                                                       AS reprogramadas,
            -- El filtro que el DISEÑO habría escrito. Debe dar 0, y ese 0 es la
            -- prueba viva de C3C.3: si algún día deja de serlo, el diseño tenía
            -- razón y esta nota sobra.
            (SELECT count(*) FROM novedad_envio
             WHERE accion IN ('devolver_almacen', 'reprogramar'))      AS accion_segun_diseno,
            (SELECT max(intento_numero) FROM novedad_envio)            AS intento_max,
            (SELECT SUM(intento_numero) FROM novedad_envio)            AS suma_intentos,
            -- OJO al ORDEN de redondeo: se redondea CADA fila y luego se suma,
            -- que es lo que hace la extracción y lo que la columna
            -- `Decimal(12,2)` puede guardar. Sumar primero y redondear después
            -- da 4.482,09 donde el almacén tiene 4.482,05 — cuatro centésimas
            -- de error acumulado en 169 filas. La validación abortó la primera
            -- carga por esto, que es exactamente su trabajo: un control debe
            -- reproducir la transformación REAL, no una versión idealizada de
            -- ella, o acaba midiendo un número que nadie guarda.
            (SELECT ROUND(SUM(ROUND(
                        (EXTRACT(EPOCH FROM (fecha_resolucion - fecha_registro))
                         / 3600.0)::numeric, 2)), 2)
             FROM novedad_envio WHERE fecha_resolucion IS NOT NULL)    AS suma_horas,
            (SELECT count(DISTINCT date_trunc('month',
                 fecha_registro AT TIME ZONE '{ZONA_HORARIA}')) FROM novedad_envio)
                                                                       AS meses,
            -- Coherencia estado ↔ fecha: las dos formas de saber si está resuelta.
            (SELECT count(*) FROM novedad_envio
             WHERE estado = 'resuelta' AND fecha_resolucion IS NULL)   AS resuelta_sin_fecha,
            (SELECT count(*) FROM novedad_envio
             WHERE estado = 'abierta' AND fecha_resolucion IS NOT NULL) AS abierta_con_fecha,
            (SELECT count(*) FROM novedad_envio
             WHERE fecha_resolucion < fecha_registro)                  AS resolucion_antes,
            -- El `pedido_id` denormalizado de la novedad contra el del envío.
            (SELECT count(*) FROM novedad_envio n JOIN envio e ON e.id = n.envio_id
             WHERE n.pedido_id <> e.pedido_id)                         AS pedido_discrepante,
            (SELECT count(*) FROM novedad_envio n
             WHERE NOT EXISTS (SELECT 1 FROM envio e WHERE e.id = n.envio_id))
                                                                       AS novedades_huerfanas
        """

    _EQUIVALENCIAS = (
        ("envios",            "countDistinct(envio_id)"),
        ("resueltas",         "countIf(resuelta = 1)"),
        ("abiertas",          "countIf(resuelta = 0)"),
        ("tipos",             "countDistinct(tipo)"),
        ("devueltas_almacen", "countIf(accion = 'devuelto_almacen')"),
        ("reprogramadas",     "countIf(accion = 'reprogramada')"),
        ("intento_max",       "max(intento_numero)"),
        ("suma_intentos",     "sum(intento_numero)"),
        ("suma_horas",        "sum(ifNull(horas_hasta_resolucion, 0))"),
        ("meses",             "countDistinct(mes)"),
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

        errores += self._validar_desenlace(client, tabla_staging, controles)
        return errores

    def _validar_origen(self, controles: dict) -> list[str]:
        errores = []
        if int(controles["novedades_huerfanas"]) != 0:
            errores.append(f"{controles['novedades_huerfanas']} novedades apuntan a un "
                           f"envío inexistente: el JOIN las perdería en silencio.")
        for clave, explicacion in (
            ("resuelta_sin_fecha", "en estado 'resuelta' sin fecha de resolución"),
            ("abierta_con_fecha",  "en estado 'abierta' CON fecha de resolución"),
            ("resolucion_antes",   "resueltas antes de haberse registrado"),
        ):
            if int(controles[clave]) != 0:
                errores.append(
                    f"{controles[clave]} novedades {explicacion}. `resuelta` se deriva "
                    f"de la FECHA; si el estado y la fecha divergen, el informe cuenta "
                    f"una cosa y el tiempo medio se calcula sobre otra."
                )
        if int(controles["pedido_discrepante"]) != 0:
            errores.append(
                f"{controles['pedido_discrepante']} novedades cuyo `pedido_id` "
                f"denormalizado no coincide con el de su envío. Se carga el del envío; "
                f"revisa cuál de los dos está mal antes de publicar."
            )
        return errores

    def _validar_desenlace(self, client, tabla_staging: str, controles: dict) -> list[str]:
        """
        C3C.3 en forma de control, más la garantía de que las abiertas siguen
        siendo visibles.
        """
        errores = []
        fila = client.query(f"""
            SELECT countDistinct(accion),
                   countIf(accion = '{SIN_ACCION}'),
                   countIf(accion IN ('devolver_almacen', 'reprogramar')),
                   countIf(resuelta = 0 AND horas_hasta_resolucion IS NOT NULL),
                   countIf(resuelta = 1 AND horas_hasta_resolucion IS NULL),
                   countIf(horas_hasta_resolucion < 0),
                   countIf(intento_numero = 0)
            FROM {tabla_staging}
        """).result_rows[0]
        (acciones, sin_resolver, accion_del_diseno, horas_en_abierta,
         resuelta_sin_horas, horas_negativas, intento_cero) = fila

        # Las abiertas TIENEN que llegar y tienen que ser distinguibles.
        if sin_resolver != int(controles["abiertas"]):
            errores.append(
                f"Novedades sin resolver: origen {controles['abiertas']} vs destino "
                f"{sin_resolver} etiquetadas '{SIN_ACCION}'. LOG-05 debe poder mostrar "
                f"las abiertas: descartarlas sesgaría el tiempo medio de resolución "
                f"hacia los casos que sí se cerraron."
            )
        # La prueba viva de C3C.3.
        if accion_del_diseno or int(controles["accion_segun_diseno"]):
            errores.append(
                f"Aparecieron acciones con los nombres que decía §5.9 "
                f"('devolver_almacen' / 'reprogramar'): {accion_del_diseno} filas. La "
                f"corrección C3C.3 dice que los valores reales son 'devuelto_almacen' "
                f"y 'reprogramada'. Si el negocio cambió, revisa el informe y la nota."
            )
        # `sin_resolver` es una etiqueta del ETL, no un valor del origen: por eso
        # el destino tiene UNA acción más que el origen.
        esperadas = int(controles["acciones"]) + (1 if sin_resolver else 0)
        if acciones != esperadas:
            errores.append(f"Acciones distintas: origen {controles['acciones']} "
                           f"(+1 etiqueta '{SIN_ACCION}') vs destino {acciones}.")
        if horas_en_abierta:
            errores.append(f"{horas_en_abierta} novedades abiertas traen horas hasta "
                           f"la resolución.")
        if resuelta_sin_horas:
            errores.append(f"{resuelta_sin_horas} novedades resueltas SIN horas hasta "
                           f"la resolución.")
        if horas_negativas:
            errores.append(f"{horas_negativas} novedades con horas NEGATIVAS.")
        if intento_cero:
            errores.append(f"{intento_cero} novedades con `intento_numero` = 0: el "
                           f"primer intento es el 1.")
        return errores
