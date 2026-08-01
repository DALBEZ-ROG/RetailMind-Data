"""
etl/dwh/tablas/fact_envio.py — F8 del modelo (§5.8 del diseño).

La última milla. Grano: **un envío**. 2.872 filas.
Alimenta OTD-LOG-03, LOG-04, LOG-09 (denominador, Fase 4) y la serie temporal
del costo de envío que el catálogo dejó pendiente para ClickHouse.

El grano NO se puede fundir en `fact_pedido` y está comprobado: hay hasta
**4 envíos para un mismo pedido** (PED-20260705-86955), y 2 pedidos con más de
uno. Colapsarlo en la cabecera perdería envíos enteros.

═══════════════════════════════════════════════════════════════════════════════
1. LA ZONA — la transformación cara, y la razón de que esta tabla exista
═══════════════════════════════════════════════════════════════════════════════

`envio` NO tiene columna de zona: su `direccion_entrega` es TEXTO libre, no una
FK. La zona se resuelve por la cadena
`pedido → direccion → ciudad → provincia → pais → zona_envio`, con
**PRECEDENCIA por especificidad: ciudad > provincia > país**. Es literalmente la
misma consulta que `VentasService.asignarEnvioPorZona` usa para asignar
transportista, y se replica aquí para que el almacén cuente la misma historia
que la operación.

Resolución verificada sobre los 2.872 envíos — y coincide EXACTA con la
referencia del diseño:

        nivel        zona                    envíos    §5.8 esperaba
        ciudad       Quevedo (local)            181    181   ✔
        provincia    Los Rios (provincial)      596    596   ✔
        país         Ecuador (nacional)       2.078  2.078   ✔
        (ninguno)    sin_zona                    17     17   ✔
                                             ─────
                                              2.872

**EL MODO DE FALLO QUE ACECHA AQUÍ.** Solo hay TRES zonas configuradas y las
tres cuelgan del mismo país (Ecuador). Agrupar por país —la simplificación
tentadora, y la que sale sola si uno mira el esquema y no los datos— manda
**2.855 de 2.872 envíos a UNA SOLA FILA** y deja el informe sin nada que
comparar. No falla ningún JOIN, no salta ninguna excepción: sale una tabla de
una fila con el 99,4 % del volumen y parece que el negocio solo opera en un
sitio.

Por eso la tabla trae además `zona_nivel` (`ciudad` / `provincia` / `pais` /
`sin_direccion`): los cuatro conteos de precedencia quedan **auditables desde el
propio almacén**, sin volver a correr el ETL ni releer este docstring. Si un día
la precedencia se rompe, un `GROUP BY zona_nivel` lo enseña en una línea.

El coste de los tres LEFT JOIN con desempate se paga **una vez por corrida** y
no una vez por consulta. Es exactamente para lo que sirve un ETL.

═══════════════════════════════════════════════════════════════════════════════
2. LA ZONA HORARIA MUERDE AQUÍ MÁS FUERTE QUE EN NINGUNA OTRA TABLA — C3C.1
═══════════════════════════════════════════════════════════════════════════════

`fecha_despacho` y `fecha_entrega_real` son `timestamptz`;
`fecha_entrega_estimada` es un `date` puro. Los plazos se calculan restando
días, y el día depende de la zona:

    envíos cuyo DÍA DE DESPACHO cambia entre UTC y America/Guayaquil ...... 597
    envíos cuyo DÍA DE ENTREGA cambia .....................................   6

Y el efecto sobre la medida, que es lo que importa:

    envíos con `dias_transito` DISTINTO según la zona ..... 569 de 2.727  (20,9 %)
    tránsito promedio en America/Guayaquil ............... 3,9754 días
    tránsito promedio en UTC ............................. 3,7675 días   (−5,2 %)

**Uno de cada cinco envíos cambia de valor, y el promedio se mueve a 3,77 días
— una cifra perfectamente creíble.** Ese es el modo de fallo que este proyecto
ya conoce: no hay error, hay un número plausible y equivocado. El despacho ocurre
por la tarde hora local, así que en UTC cae al día siguiente; la entrega ocurre
por la mañana y casi nunca se desplaza. El desajuste es asimétrico y eso lo hace
peor: el tránsito se acorta sistemáticamente.

Sobre OTD-LOG-03 el mismo error es leve (6 envíos, 1.704 → 1.702 a tiempo)
porque la fecha prometida ya es un `date`. Pero la conversión es explícita en las
TRES restas, y se aísla en una constante para que no pueda aplicarse en un sitio
y olvidarse en otro.

═══════════════════════════════════════════════════════════════════════════════
3. COBERTURA REAL DE FECHAS — sobre cuántos envíos se promedia cada cosa
═══════════════════════════════════════════════════════════════════════════════

                                            envíos
    con fecha de despacho ................. 2.872   (los 2.872: ninguno sin despachar)
    con fecha estimada .................... 2.856
    con fecha de entrega real ............. 2.727   = los `entregado`
    con AMBAS (tránsito medible, LOG-04) .. 2.727
    con real + estimada (promesa, LOG-03) . 2.723

    por estado           envíos   real   estimada   con novedad
    entregado             2.727   2.727    2.723         46
    devuelto                120       0      119        120
    en_transito              25       0       14          7

Los 120 `devuelto` son los que acabaron en «devolver al almacén» tras una
novedad — **todos tienen novedad, y ninguno tiene entrega real**. Los informes
de tiempos miden por tanto sobre 2.727 y 2.723, nunca sobre 2.872, y cada uno
declara su base. Un promedio de tránsito calculado sobre 2.872 estaría diluido
por 145 envíos que nunca llegaron.

═══════════════════════════════════════════════════════════════════════════════
4. LOS 24 ENVÍOS SIN COSTO NI PESO — C3C.2
═══════════════════════════════════════════════════════════════════════════════

§5.8 declara `costo Decimal(14,2)` y `peso_total_kg Decimal(10,3)` sin admitir
ausencia, y anota «2.848 con costo». Los otros 24:

    envíos con costo = 0 ............................. 24
    envíos con peso_total_kg NULL .................... 24
    envíos con AMBOS ausentes ........................ 24   ← son los mismos
    ids ................................... 1 a 24, del 4 al 20 de julio de 2026

Son los envíos legacy creados a mano contra la aplicación durante el desarrollo,
antes de que el cálculo de tarifa por zona y peso estuviera en su sitio. **No son
envíos gratuitos**: son envíos sin tarifar. Se cargan con `costo = 0`,
`peso_total_kg = 0` y la marca `sin_tarifa = 1` —mismo criterio que `sin_costo`
en el kardex (C3B.3)—, y `costo_por_kg` queda en NULL en vez de reventar por
división por cero.

Sin la marca, la serie temporal del costo de envío promediaría 24 ceros dentro
de julio de 2026 y ese mes aparecería artificialmente barato. Con ella, el
informe puede excluirlos y decir cuántos excluyó.

Bitácora completa: `docs/estrategico/CORRECCIONES_DISENO_ETL.md`.
"""

from datetime import datetime
from zoneinfo import ZoneInfo

from etl.dwh.conexiones import ZONA_HORARIA
from etl.dwh.tarea import TareaCarga

#: Centinela de envío cuyo pedido no tiene dirección: 17 envíos.
SIN_ZONA = "sin_zona"

#: Nivel de precedencia con que se resolvió la zona. Es lo que hace auditables
#: los cuatro conteos de §5.8 desde el propio almacén.
NIVEL_SIN_DIRECCION = "sin_direccion"


class FactEnvio(TareaCarga):

    nombre = "fact_envio"

    # ── Definición ───────────────────────────────────────────────────────────

    def ddl(self, nombre_tabla: str) -> str:
        return f"""
        CREATE TABLE {nombre_tabla}
        (
            envio_id               UInt32,
            numero                 String,
            pedido_id              UInt32,
            numero_guia            String,
            transportista          LowCardinality(String),
            metodo_envio           LowCardinality(String),
            bodega_origen          LowCardinality(String),
            zona                   LowCardinality(String),
            zona_nivel             LowCardinality(String),
            ciudad_destino         LowCardinality(String),
            provincia_destino      LowCardinality(String),
            estado                 LowCardinality(String),
            canal                  LowCardinality(String),
            fecha_despacho         DateTime('{ZONA_HORARIA}'),
            mes                    Date,
            fecha_entrega_estimada Nullable(Date),
            fecha_entrega_real     Nullable(DateTime('{ZONA_HORARIA}')),
            dias_transito          Nullable(Int16),
            dias_desvio_promesa    Nullable(Int16),
            entregado_a_tiempo     Nullable(UInt8),
            costo                  Decimal(14,2),
            peso_total_kg          Decimal(10,3),
            costo_por_kg           Nullable(Decimal(10,4)),
            sin_tarifa             UInt8,
            novedades              UInt8,
            tuvo_novedad           UInt8,
            fecha_carga            DateTime('{ZONA_HORARIA}')
        )
        ENGINE = MergeTree
        PARTITION BY toYYYYMM(fecha_despacho)
        ORDER BY (fecha_despacho, transportista, zona)
        """

    def columnas(self) -> list[str]:
        return [
            "envio_id", "numero", "pedido_id", "numero_guia", "transportista",
            "metodo_envio", "bodega_origen", "zona", "zona_nivel",
            "ciudad_destino", "provincia_destino", "estado", "canal",
            "fecha_despacho", "mes", "fecha_entrega_estimada",
            "fecha_entrega_real", "dias_transito", "dias_desvio_promesa",
            "entregado_a_tiempo", "costo", "peso_total_kg", "costo_por_kg",
            "sin_tarifa", "novedades", "tuvo_novedad", "fecha_carga",
        ]

    # ── Extracción ───────────────────────────────────────────────────────────

    #: LA RESOLUCIÓN DE ZONA, en un CTE compartido por la extracción y por los
    #: controles. Se aísla —y no se pega dos veces— para que la regla de
    #: precedencia no pueda cambiar en un sitio y no en el otro: es exactamente
    #: el descuido que produciría un conteo de control que valida un cálculo
    #: distinto del que se cargó.
    #:
    #: `DISTINCT ON (e.id)` + el `ORDER BY` de especificidad es la traducción
    #: literal del `ORDER BY … LIMIT 1` de `VentasService.asignarEnvioPorZona`.
    #: Sin él, un envío de Quevedo casaría con las TRES zonas (la de ciudad, la
    #: de provincia y la nacional) y saldría por triplicado — un fan-out que
    #: llevaría la tabla de 2.872 a 5.500 filas.
    _CTE_ZONA = """
        destino AS (
            SELECT e.id            AS envio_id,
                   ci.id           AS ciudad_id,
                   ci.nombre       AS ciudad,
                   pr.id           AS provincia_id,
                   pr.nombre       AS provincia,
                   pr.pais_id      AS pais_id
            FROM envio e
            JOIN pedido p          ON p.id  = e.pedido_id
            LEFT JOIN direccion d  ON d.id  = p.direccion_envio_id
            LEFT JOIN ciudad ci    ON ci.id = d.ciudad_id
            LEFT JOIN provincia pr ON pr.id = ci.provincia_id
        ),
        zona_resuelta AS (
            SELECT DISTINCT ON (t.envio_id)
                   t.envio_id,
                   z.nombre AS zona,
                   CASE WHEN z.ciudad_id    IS NOT NULL THEN 'ciudad'
                        WHEN z.provincia_id IS NOT NULL THEN 'provincia'
                        ELSE 'pais' END AS zona_nivel
            FROM destino t
            JOIN zona_envio z
                   ON z.activo
                  AND z.pais_id = t.pais_id
                  AND (z.provincia_id IS NULL OR z.provincia_id = t.provincia_id)
                  AND (z.ciudad_id    IS NULL OR z.ciudad_id    = t.ciudad_id)
            -- La PRECEDENCIA: ciudad gana a provincia, provincia gana a país.
            ORDER BY t.envio_id,
                     (z.ciudad_id    IS NOT NULL) DESC,
                     (z.provincia_id IS NOT NULL) DESC,
                     z.id
        )
    """

    #: El día del evento en la zona del NEGOCIO. Aislado como constante porque
    #: entra en las tres restas de plazo: aplicarlo en dos y olvidarlo en la
    #: tercera daría un informe coherente consigo mismo y equivocado (C3C.1).
    _DIA_ENTREGA = f"(e.fecha_entrega_real AT TIME ZONE '{ZONA_HORARIA}')::date"
    _DIA_DESPACHO = f"(e.fecha_despacho AT TIME ZONE '{ZONA_HORARIA}')::date"

    def sql_extraccion(self) -> str:
        return f"""
        WITH {self._CTE_ZONA},
        novedades AS (
            SELECT envio_id, count(*) AS n FROM novedad_envio GROUP BY envio_id
        )
        SELECT
            e.id                                               AS envio_id,
            e.numero,
            e.pedido_id,
            COALESCE(e.numero_guia, '')                        AS numero_guia,
            t.nombre                                           AS transportista,
            COALESCE(me.nombre, 'sin_metodo')                  AS metodo_envio,
            COALESCE(b.nombre, 'sin_bodega')                   AS bodega_origen,
            COALESCE(zr.zona, '{SIN_ZONA}')                    AS zona,
            COALESCE(zr.zona_nivel, '{NIVEL_SIN_DIRECCION}')   AS zona_nivel,
            COALESCE(dt.ciudad, 'sin_direccion')               AS ciudad_destino,
            COALESCE(dt.provincia, 'sin_direccion')            AS provincia_destino,
            e.estado,
            COALESCE(p.canal, '')                              AS canal,
            e.fecha_despacho,
            (date_trunc('month',
                e.fecha_despacho AT TIME ZONE '{ZONA_HORARIA}'))::date AS mes,
            e.fecha_entrega_estimada,
            e.fecha_entrega_real,

            -- C3C.1: las TRES restas convierten la zona EXPLÍCITAMENTE.
            -- LOG-04: días reales de tránsito.
            ({self._DIA_ENTREGA} - {self._DIA_DESPACHO})::int  AS dias_transito,
            -- LOG-03: promesa contra llegada. NULL si falta cualquiera de las dos.
            ({self._DIA_ENTREGA} - e.fecha_entrega_estimada)::int
                                                               AS dias_desvio_promesa,
            CASE
                WHEN e.fecha_entrega_real IS NULL
                  OR e.fecha_entrega_estimada IS NULL THEN NULL
                WHEN {self._DIA_ENTREGA} <= e.fecha_entrega_estimada THEN 1
                ELSE 0
            END                                                AS entregado_a_tiempo,

            e.costo,
            COALESCE(e.peso_total_kg, 0)                       AS peso_total_kg,
            -- NULL y no 0 cuando no hay peso: un costo por kg de 0 se
            -- promediaría con los demás y abarataría la serie en silencio.
            CASE WHEN COALESCE(e.peso_total_kg, 0) > 0
                 THEN ROUND(e.costo / e.peso_total_kg, 4)
                 ELSE NULL END                                 AS costo_por_kg,
            -- C3C.2: envío sin tarifar, que NO es un envío gratis.
            CASE WHEN e.costo = 0 AND e.peso_total_kg IS NULL
                 THEN 1 ELSE 0 END                             AS sin_tarifa,

            COALESCE(nv.n, 0)                                  AS novedades,
            CASE WHEN nv.envio_id IS NOT NULL THEN 1 ELSE 0 END AS tuvo_novedad
        FROM envio e
        JOIN pedido p              ON p.id = e.pedido_id
        JOIN transportista t       ON t.id = e.transportista_id
        LEFT JOIN metodo_envio me  ON me.id = e.metodo_envio_id
        LEFT JOIN bodega b         ON b.id = e.bodega_id
        LEFT JOIN destino dt       ON dt.envio_id = e.id
        LEFT JOIN zona_resuelta zr ON zr.envio_id = e.id
        LEFT JOIN novedades nv     ON nv.envio_id = e.id
        ORDER BY e.fecha_despacho, t.nombre, e.id
        """

    def transformar(self, lote: list[tuple]) -> list[tuple]:
        ahora = datetime.now(ZoneInfo(ZONA_HORARIA)).replace(microsecond=0)
        return [tuple(fila) + (ahora,) for fila in lote]

    # ── Controles ────────────────────────────────────────────────────────────

    def sql_controles(self) -> str:
        """
        Además del conteo: los CUATRO conteos de precedencia de zona (que son la
        prueba de que la resolución cara se hizo bien), la cobertura real de
        cada fecha (sobre cuántos envíos se promedia cada informe) y la
        integridad del enlace con el pedido.
        """
        return f"""
        WITH {self._CTE_ZONA}
        SELECT
            (SELECT count(*) FROM envio)                              AS filas,
            (SELECT count(DISTINCT pedido_id) FROM envio)             AS pedidos,
            (SELECT max(n) FROM (SELECT count(*) n FROM envio GROUP BY pedido_id) x)
                                                                      AS max_envios_pedido,
            -- Precedencia de zona: los cuatro conteos de §5.8.
            (SELECT count(*) FROM zona_resuelta WHERE zona_nivel = 'ciudad')
                                                                      AS zona_ciudad,
            (SELECT count(*) FROM zona_resuelta WHERE zona_nivel = 'provincia')
                                                                      AS zona_provincia,
            (SELECT count(*) FROM zona_resuelta WHERE zona_nivel = 'pais')
                                                                      AS zona_pais,
            (SELECT count(*) FROM envio e
             WHERE NOT EXISTS (SELECT 1 FROM zona_resuelta z WHERE z.envio_id = e.id))
                                                                      AS zona_sin_direccion,
            -- Cobertura de fechas.
            (SELECT count(*) FROM envio WHERE fecha_despacho IS NOT NULL)
                                                                      AS con_despacho,
            (SELECT count(*) FROM envio WHERE fecha_entrega_estimada IS NOT NULL)
                                                                      AS con_estimada,
            (SELECT count(*) FROM envio WHERE fecha_entrega_real IS NOT NULL)
                                                                      AS con_real,
            (SELECT count(*) FROM envio
             WHERE fecha_entrega_real IS NOT NULL AND fecha_entrega_estimada IS NOT NULL)
                                                                      AS promesa_medible,
            (SELECT count(*) FROM envio e
             WHERE e.fecha_entrega_real IS NOT NULL
               AND {self._DIA_ENTREGA} <= e.fecha_entrega_estimada)   AS a_tiempo,
            (SELECT SUM({self._DIA_ENTREGA} - {self._DIA_DESPACHO}) FROM envio e
             WHERE e.fecha_entrega_real IS NOT NULL)                  AS suma_transito,
            -- Dinero y peso.
            (SELECT SUM(costo) FROM envio)                            AS costo_total,
            (SELECT count(*) FROM envio WHERE costo = 0)              AS sin_tarifa,
            (SELECT SUM(COALESCE(peso_total_kg, 0)) FROM envio)       AS peso_total,
            -- Catálogo y volumen.
            (SELECT count(DISTINCT transportista_id) FROM envio)      AS transportistas,
            (SELECT count(DISTINCT estado) FROM envio)                AS estados,
            (SELECT count(DISTINCT date_trunc('month',
                 fecha_despacho AT TIME ZONE '{ZONA_HORARIA}')) FROM envio) AS meses,
            (SELECT count(*) FROM novedad_envio)                      AS novedades,
            (SELECT count(DISTINCT envio_id) FROM novedad_envio)      AS envios_con_novedad,
            -- Integridad del enlace: ningún envío puede quedar sin pedido.
            (SELECT count(*) FROM envio e
             WHERE NOT EXISTS (SELECT 1 FROM pedido p WHERE p.id = e.pedido_id))
                                                                      AS envios_huerfanos,
            (SELECT count(*) FROM envio WHERE transportista_id IS NULL)
                                                                      AS sin_transportista
        """

    _EQUIVALENCIAS = (
        ("pedidos",            "countDistinct(pedido_id)"),
        ("zona_ciudad",        "countIf(zona_nivel = 'ciudad')"),
        ("zona_provincia",     "countIf(zona_nivel = 'provincia')"),
        ("zona_pais",          "countIf(zona_nivel = 'pais')"),
        ("zona_sin_direccion", f"countIf(zona_nivel = '{NIVEL_SIN_DIRECCION}')"),
        ("con_estimada",       "countIf(fecha_entrega_estimada IS NOT NULL)"),
        ("con_real",           "countIf(fecha_entrega_real IS NOT NULL)"),
        ("promesa_medible",    "countIf(dias_desvio_promesa IS NOT NULL)"),
        ("a_tiempo",           "countIf(entregado_a_tiempo = 1)"),
        ("suma_transito",      "sum(ifNull(dias_transito, 0))"),
        ("costo_total",        "sum(costo)"),
        ("sin_tarifa",         "countIf(sin_tarifa = 1)"),
        ("peso_total",         "sum(peso_total_kg)"),
        ("transportistas",     "countDistinct(transportista)"),
        ("estados",            "countDistinct(estado)"),
        ("meses",              "countDistinct(mes)"),
        ("novedades",          "sum(novedades)"),
        ("envios_con_novedad", "countIf(tuvo_novedad = 1)"),
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

        errores += self._validar_zona(client, tabla_staging, controles)
        errores += self._validar_plazos(client, tabla_staging)
        return errores

    def _validar_origen(self, controles: dict) -> list[str]:
        """Salud del ORIGEN: lo que haría inválida la tabla entera."""
        errores = []
        if int(controles["envios_huerfanos"]) != 0:
            errores.append(
                f"{controles['envios_huerfanos']} envíos apuntan a un pedido que no "
                f"existe. El control cruzado contra `fact_pedido` fallaría, y con él "
                f"OTD-LOG-09 (envíos que terminan en devolución)."
            )
        if int(controles["sin_transportista"]) != 0:
            errores.append(
                f"{controles['sin_transportista']} envíos sin transportista: el JOIN "
                f"de la extracción es INNER y los perdería en silencio. Los tres "
                f"informes de esta fase comparan POR transportista."
            )
        if int(controles["max_envios_pedido"]) < 2:
            # No aborta: es una nota. Pero si dejara de haber pedidos con varios
            # envíos, el argumento de §5.8 para no fundir esta tabla en
            # `fact_pedido` deja de sostenerse y conviene saberlo.
            errores.append(
                f"Ningún pedido tiene más de un envío (máx. "
                f"{controles['max_envios_pedido']}). §5.8 justifica el grano propio "
                f"de esta tabla en que los hay: revisa el argumento antes de seguir."
            )
        return errores

    def _validar_zona(self, client, tabla_staging: str, controles: dict) -> list[str]:
        """
        La resolución de zona, que es la razón de ser de esta tabla.

        Se comprueba que los cuatro niveles SUMAN el total —sin eso, un envío
        podría haberse quedado sin nivel— y, sobre todo, que la resolución NO
        colapsó a una sola zona, que es el modo de fallo silencioso de §5.8.
        """
        errores = []
        total, zonas, niveles, sin_zona = client.query(f"""
            SELECT count(), countDistinct(zona), countDistinct(zona_nivel),
                   countIf(zona = '{SIN_ZONA}')
            FROM {tabla_staging}
        """).result_rows[0]

        suma_niveles = sum(int(controles[c]) for c in
                           ("zona_ciudad", "zona_provincia", "zona_pais",
                            "zona_sin_direccion"))
        if suma_niveles != int(controles["filas"]):
            errores.append(f"Los cuatro niveles de zona suman {suma_niveles} sobre "
                           f"{controles['filas']} envíos: alguno se quedó sin resolver.")
        if sin_zona != int(controles["zona_sin_direccion"]):
            errores.append(f"Envíos sin zona: origen {controles['zona_sin_direccion']} "
                           f"vs destino {sin_zona}.")
        # EL control que ataca el modo de fallo: si todo cayó en una zona, la
        # precedencia se rompió y el informe sale con una sola fila.
        if zonas <= 1 and total > 0:
            errores.append(
                f"La resolución de zona COLAPSÓ: {total} envíos en {zonas} zona(s). "
                f"Las tres zonas configuradas cuelgan del mismo país, así que agrupar "
                f"por país manda el 99,4 % a una fila y el informe queda sin nada que "
                f"comparar — sin dar ningún error (§5.8)."
            )
        if niveles < 2:
            errores.append(f"Solo {niveles} nivel(es) de precedencia distintos: la "
                           f"regla ciudad > provincia > país no se está aplicando.")
        return errores

    def _validar_plazos(self, client, tabla_staging: str) -> list[str]:
        """
        Coherencia de los tres indicadores de plazo. Un tránsito negativo o un
        veredicto sin medida son síntomas de que la conversión de zona (C3C.1)
        se aplicó a medias.
        """
        fila = client.query(f"""
            SELECT
                countIf(dias_transito < 0),
                countIf(fecha_entrega_real IS NULL AND dias_transito IS NOT NULL),
                countIf(entregado_a_tiempo IS NOT NULL AND dias_desvio_promesa IS NULL),
                countIf(peso_total_kg = 0 AND costo_por_kg IS NOT NULL),
                countIf(sin_tarifa = 1 AND costo != 0)
            FROM {tabla_staging}
        """).result_rows[0]
        (transito_negativo, transito_sin_entrega, veredicto_sin_desvio,
         kg_sin_peso, tarifa_incoherente) = fila

        errores = []
        if transito_negativo:
            errores.append(
                f"{transito_negativo} envíos con `dias_transito` NEGATIVO (entregados "
                f"antes de despacharse). En el origen no hay ninguno: la conversión de "
                f"zona horaria de C3C.1 es la primera sospechosa."
            )
        if transito_sin_entrega:
            errores.append(f"{transito_sin_entrega} envíos sin entrega real tienen "
                           f"tránsito calculado.")
        if veredicto_sin_desvio:
            errores.append(
                f"{veredicto_sin_desvio} envíos tienen veredicto `entregado_a_tiempo` "
                f"sin desvío medible. Sin entrega o sin promesa NO hay veredicto: un 0 "
                f"diría «llegó tarde», que no es «no se sabe»."
            )
        if kg_sin_peso:
            errores.append(f"{kg_sin_peso} envíos sin peso traen `costo_por_kg`.")
        if tarifa_incoherente:
            errores.append(f"{tarifa_incoherente} envíos marcados `sin_tarifa` traen "
                           f"un costo distinto de cero.")
        return errores
