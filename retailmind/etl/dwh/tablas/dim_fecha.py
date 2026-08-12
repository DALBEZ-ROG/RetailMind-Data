"""
etl/dwh/tablas/dim_fecha.py — D1 del modelo (§4.1 del diseño).

El calendario del DWH. Es la ÚNICA de las 19 tablas que no consulta PostgreSQL:
se GENERA dentro de ClickHouse con `numbers()`, porque un calendario no es un
dato del negocio sino una convención.

Su razón de ser no es decorativa: hace visibles los meses SIN actividad. El
catálogo táctico ya pagó ese precio en OTD-GER-01, que tuvo que emitir a mano
una fila «Día sin movimiento» porque un `GROUP BY` sobre los hechos solo puede
devolver los períodos que existen en los hechos.

Grano: un día. Rango 2025-01-01 → 2034-12-31 = **3.652 filas** (la década
declarada en `carga_fase_parametro.ventana_temporal`: 8 años comunes de 365 más
2028 y 2032, bisiestos, de 366 → 2.920 + 732).

EXTENDIDO EN LA FASE 2 DE LA CARGA MASIVA (2026-08-11), de 730 a 3.652 días.
El calendario cubría 2025-2026 y la carga entró en 2026-09 → 2027-08. No es un
detalle cosmético: `fact_prevision_demanda._malla()` LEVANTA UNA EXCEPCIÓN si
el calendario no cubre el período del hecho sin huecos —y hace bien, porque una
serie con un hueco produce un factor estacional de un mes que no existe—, así
que un pedido fuera de rango no degrada nada: tumba la tarea y con ella el DAG.

Se extiende a la DÉCADA COMPLETA y no solo a lo que la Fase 2 necesita, porque
el mismo muro está esperando a la Fase 3 en cada uno de sus años. Que el
calendario llegue más lejos que la venta es el comportamiento previsto: `_malla`
lo recorta al período del hecho, precisamente para no fabricar meses vacíos al
final que el modelo leería como una caída a cero.

`mes_etiqueta` viaja como TEXTO ya formateado, y eso es una lección cara ya
pagada por este proyecto: un `date` puro serializado «AAAA-MM-DD» lo interpreta
el formateador del frontend como UTC y RESTA UN DÍA. La regla vigente de
`PATRON_INFORMES.md` §11 es que las fechas-día del resumen viajan con `to_char`
y tipo texto; aquí se aplica igual, precalculada en la dimensión.
"""

from datetime import date, timedelta

from etl.dwh.conexiones import logger
from etl.dwh.tarea import TareaCarga

FECHA_INICIO = date(2025, 1, 1)
FECHA_FIN = date(2034, 12, 31)

#: 1 = lunes. `toDayOfWeek` de ClickHouse ya usa esta convención ISO.
DIAS_SEMANA = ("lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo")


class DimFecha(TareaCarga):
    """
    Calendario generado en ClickHouse. No lee PostgreSQL, así que sobreescribe
    `cargar_en` y `controles` en vez de `sql_extraccion`/`sql_controles`.

    No es una `TareaDerivada`: aquélla se calcula desde OTRAS tablas del DWH y
    depende de que existan. Ésta no depende de nada — se genera de la nada.
    """

    nombre = "dim_fecha"

    def ddl(self, nombre_tabla: str) -> str:
        return f"""
        CREATE TABLE {nombre_tabla}
        (
            fecha             Date,
            anio              UInt16,
            mes               UInt8,
            mes_inicio        Date,
            mes_etiqueta      LowCardinality(String),
            trimestre         UInt8,
            dia_semana        UInt8,
            dia_semana_nombre LowCardinality(String),
            es_fin_semana     UInt8
        )
        ENGINE = MergeTree
        ORDER BY fecha
        """

    def columnas(self) -> list[str]:
        return [
            "fecha", "anio", "mes", "mes_inicio", "mes_etiqueta",
            "trimestre", "dia_semana", "dia_semana_nombre", "es_fin_semana",
        ]

    def sql_extraccion(self) -> str:  # pragma: no cover - no aplica
        raise NotImplementedError("dim_fecha se genera en ClickHouse, no se extrae.")

    def sql_controles(self) -> str:  # pragma: no cover - no aplica
        raise NotImplementedError("dim_fecha no tiene origen en PostgreSQL.")

    # ── Generación dentro de ClickHouse ──────────────────────────────────────

    def cargar_en(self, client, tabla_staging: str) -> int:
        """
        `numbers()` produce la serie de días y las funciones de fecha del motor
        derivan cada atributo. El nombre del día se traduce con un `transform`
        en vez de `dateName()`, porque `dateName` devuelve el nombre en inglés y
        depende de la configuración de locale del servidor: fijarlo aquí hace
        que la carga sea reproducible en cualquier contenedor.
        """
        dias = (FECHA_FIN - FECHA_INICIO).days + 1
        nombres = ", ".join(f"'{d}'" for d in DIAS_SEMANA)

        client.command(f"""
            INSERT INTO {tabla_staging}
            SELECT
                d                                                 AS fecha,
                toYear(d)                                         AS anio,
                toMonth(d)                                        AS mes,
                toStartOfMonth(d)                                 AS mes_inicio,
                formatDateTime(d, '%Y-%m')                        AS mes_etiqueta,
                toQuarter(d)                                      AS trimestre,
                toDayOfWeek(d)                                    AS dia_semana,
                transform(toDayOfWeek(d), [1,2,3,4,5,6,7],
                          [{nombres}], 'desconocido')             AS dia_semana_nombre,
                if(toDayOfWeek(d) >= 6, 1, 0)                     AS es_fin_semana
            FROM (
                SELECT toDate('{FECHA_INICIO.isoformat()}') + toIntervalDay(number) AS d
                FROM numbers({dias})
            )
        """)

        filas = client.query(f"SELECT count() FROM {tabla_staging}").result_rows[0][0]
        logger.info(f"[{self.nombre}] {filas:,} días generados "
                    f"({FECHA_INICIO} → {FECHA_FIN})")
        return filas

    def extraer(self):
        """No hay extracción: la carga la hace `cargar_en` dentro del motor."""
        return iter(())

    def controles(self) -> dict:
        """
        La cifra de control no viene de PostgreSQL sino de la aritmética del
        calendario. Sigue siendo un control real: si el rango o el `numbers()`
        se descuadran, el conteo no coincide y la tabla NO se publica.
        """
        return {"filas": (FECHA_FIN - FECHA_INICIO).days + 1}

    def validar(self, client, tabla_staging: str, controles: dict) -> list[str]:
        """
        Tres invariantes del calendario. Un hueco en la serie de días sería
        invisible en el `count(*)` si a la vez sobrara un día duplicado, así que
        se comprueban los extremos y la unicidad, no solo el total.
        """
        errores = []
        fila = client.query(f"""
            SELECT count(), countDistinct(fecha), min(fecha), max(fecha),
                   countDistinct(mes_inicio), sum(es_fin_semana)
            FROM {tabla_staging}
        """).result_rows[0]
        total, distintas, minimo, maximo, meses, fines = fila

        if distintas != total:
            errores.append(f"Fechas duplicadas: {total} filas, {distintas} fechas distintas.")
        if minimo != FECHA_INICIO:
            errores.append(f"La serie empieza en {minimo}, se esperaba {FECHA_INICIO}.")
        if maximo != FECHA_FIN:
            errores.append(f"La serie termina en {maximo}, se esperaba {FECHA_FIN}.")
        # Los esperados se DERIVAN del rango, no se escriben a mano. Estaban
        # cableados a 24 meses y 208 fines de semana —las cifras de los dos años
        # originales— y al extender el calendario a la década la tarea abortó
        # tres veces seguidas y tumbó el DAG entero: `fact_prevision_demanda` y
        # `validar_dwh` quedaron en `upstream_failed`. Un control cuyo esperado
        # es una constante deja de ser un control en cuanto cambia el diseño;
        # solo protege mientras nadie toca nada.
        meses_esperados = ((FECHA_FIN.year - FECHA_INICIO.year) * 12
                           + FECHA_FIN.month - FECHA_INICIO.month + 1)
        if meses != meses_esperados:
            errores.append(f"El calendario cubre {meses} meses, se esperaban {meses_esperados}.")

        fines_esperados = sum(
            1 for n in range((FECHA_FIN - FECHA_INICIO).days + 1)
            if (FECHA_INICIO + timedelta(days=n)).weekday() >= 5)
        if fines != fines_esperados:
            errores.append(f"Fines de semana: {fines}, se esperaban {fines_esperados}.")
        return errores
