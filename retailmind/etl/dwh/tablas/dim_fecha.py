"""
etl/dwh/tablas/dim_fecha.py — D1 del modelo (§4.1 del diseño).

El calendario del DWH. Es la ÚNICA de las 19 tablas que no consulta PostgreSQL:
se GENERA dentro de ClickHouse con `numbers()`, porque un calendario no es un
dato del negocio sino una convención.

Su razón de ser no es decorativa: hace visibles los meses SIN actividad. El
catálogo táctico ya pagó ese precio en OTD-GER-01, que tuvo que emitir a mano
una fila «Día sin movimiento» porque un `GROUP BY` sobre los hechos solo puede
devolver los períodos que existen en los hechos.

Grano: un día. Rango 2025-01-01 → 2026-12-31 = 730 filas (2025 y 2026 no son
bisiestos: 365 + 365).

`mes_etiqueta` viaja como TEXTO ya formateado, y eso es una lección cara ya
pagada por este proyecto: un `date` puro serializado «AAAA-MM-DD» lo interpreta
el formateador del frontend como UTC y RESTA UN DÍA. La regla vigente de
`PATRON_INFORMES.md` §11 es que las fechas-día del resumen viajan con `to_char`
y tipo texto; aquí se aplica igual, precalculada en la dimensión.
"""

from datetime import date

from etl.dwh.conexiones import logger
from etl.dwh.tarea import TareaCarga

FECHA_INICIO = date(2025, 1, 1)
FECHA_FIN = date(2026, 12, 31)

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
        if meses != 24:
            errores.append(f"El calendario cubre {meses} meses, se esperaban 24 (2 años).")
        if fines != 208:
            # 2025 y 2026 tienen 104 fines de semana cada uno (52 sábados + 52 domingos).
            errores.append(f"Fines de semana: {fines}, se esperaban 208.")
        return errores
