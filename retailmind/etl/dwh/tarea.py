"""
etl/dwh/tarea.py
Contrato que cumple CADA una de las 19 tablas del DWH.

El molde es deliberadamente parecido al de `InformeServiceBase` del backend: la
mecánica común (lotes, validación, publicación atómica, bitácora) vive UNA vez
—en `carga_atomica.py`— y cada tabla aporta solo lo suyo: su DDL, su consulta
de extracción y sus cifras de control.

Una tabla nueva = una subclase + una línea en `registro.py`. Nada más.

Dos sabores:
  * `TareaCarga`    — lee de PostgreSQL. Son 18 de las 19.
  * `TareaDerivada` — se calcula DENTRO de ClickHouse con un INSERT … SELECT y
    no vuelve a consultar PostgreSQL. Hoy solo `fact_stock_mensual` (§5.7).
"""

from abc import ABC, abstractmethod
from collections.abc import Iterator

from etl.dwh.carga_atomica import BATCH_SIZE
from etl.dwh.conexiones import CH_DATABASE, get_pg_connection, logger


class TareaCarga(ABC):
    """
    Una tabla del DWH que se carga desde PostgreSQL.

    Subclases OBLIGADAS a definir: `nombre`, `ddl()`, `columnas()`, `sql_extraccion()`
    y `sql_controles()`. Todo lo demás tiene comportamiento por defecto correcto.
    """

    #: Nombre de la tabla destino, p. ej. 'fact_venta_linea'.
    nombre: str = ""

    #: Tareas que deben haber corrido antes (orden topológico de `run_etl.py`).
    #: Solo hay una dependencia real en el modelo:
    #: fact_movimiento_inventario → fact_stock_mensual (§7.1).
    depende_de: tuple[str, ...] = ()

    def __init__(self):
        #: Filas que la transformación no pudo resolver y que se REGISTRAN en la
        #: bitácora en vez de silenciarse (§5.2 y §5.3 del diseño).
        self.excepciones: int = 0

    # ── Definición de la tabla ───────────────────────────────────────────────

    @abstractmethod
    def ddl(self, nombre_tabla: str) -> str:
        """
        `CREATE TABLE` completo para `nombre_tabla`.

        Recibe el nombre como PARÁMETRO —y no lo hardcodea— porque el patrón de
        carga atómica crea primero `<tabla>_new` con la misma definición.
        """

    @abstractmethod
    def columnas(self) -> list[str]:
        """Nombres de columna, en el MISMO orden que devuelve `sql_extraccion()`."""

    # ── Extracción ───────────────────────────────────────────────────────────

    @abstractmethod
    def sql_extraccion(self) -> str:
        """
        SELECT contra PostgreSQL. Sus columnas deben coincidir en número y orden
        con `columnas()`.

        Recordatorio del proyecto: se valida por LISTA BLANCA y se parametriza;
        jamás se concatena texto del usuario. Aquí no hay entrada de usuario —
        estas consultas son fijas—, pero la disciplina se mantiene.
        """

    def extraer(self) -> Iterator[list[tuple]]:
        """
        Genera lotes de filas desde PostgreSQL usando un cursor con nombre
        (server-side), para no traer la tabla entera a memoria.

        Mismo patrón de lotes que `etl/carga/09_load_clickhouse.py`, pero
        transmitiendo desde la base en vez de troceando un DataFrame.
        """
        conn = get_pg_connection()
        try:
            with conn.cursor(name=f"cur_{self.nombre}") as cur:
                cur.itersize = BATCH_SIZE
                cur.execute(self.sql_extraccion())
                while True:
                    lote = cur.fetchmany(BATCH_SIZE)
                    if not lote:
                        break
                    yield self.transformar(lote)
        finally:
            conn.close()

    def cargar_en(self, client, tabla_staging: str) -> int:
        """
        Llena la tabla de staging y devuelve las filas escritas.

        Es el paso 2 del §6.2 y el ÚNICO punto que `carga_atomica` invoca para
        llenar, de modo que una tarea derivada (que no lee PostgreSQL) solo
        tiene que sobreescribir este método.
        """
        columnas = self.columnas()
        escritas = 0
        for lote in self.extraer():
            if not lote:
                continue
            client.insert(tabla_staging, lote, column_names=columnas)
            escritas += len(lote)
            logger.info(f"[{self.nombre}] {escritas:,} filas insertadas")
        return escritas

    def transformar(self, lote: list[tuple]) -> list[tuple]:
        """
        Gancho para la transformación fila a fila que no cabe en SQL
        (normalizar `motivo_fallo`, despejar el cupón por línea, …).
        Por defecto no toca nada: la mayoría de las tablas transforman en el SELECT.
        """
        return lote

    # ── Validación ───────────────────────────────────────────────────────────

    @abstractmethod
    def sql_controles(self) -> str:
        """
        SELECT de UNA fila contra PostgreSQL con las cifras de control de la
        tabla. DEBE incluir una columna `filas` (el `count(*)` del origen), que
        es la validación mínima obligatoria del §6.2 paso 3.

        Las demás columnas son las cifras de §9.2–§9.5: sumas de dinero,
        unidades, meses con actividad… El criterio de aceptación del proyecto es
        la igualdad EXACTA al centavo, no la aproximación.
        """

    def controles(self) -> dict:
        """Ejecuta `sql_controles()` y devuelve {columna: valor}."""
        conn = get_pg_connection()
        try:
            with conn.cursor() as cur:
                cur.execute(self.sql_controles())
                fila = cur.fetchone()
                nombres = [d[0] for d in cur.description]
            return dict(zip(nombres, fila))
        finally:
            conn.close()

    def validar(self, client, tabla_staging: str, controles: dict) -> list[str]:
        """
        Validaciones PROPIAS de la tabla, más allá del conteo (que ya hace
        `carga_atomica.validar_conteo`). Devuelve la lista de errores; vacía = OK.

        Aquí es donde cada fase compara sus sumas de dinero contra ClickHouse.
        Por defecto no añade ninguna.
        """
        return []


class TareaDerivada(TareaCarga):
    """
    Tabla calculada DENTRO de ClickHouse desde otras tablas del DWH; no consulta
    PostgreSQL para extraer. Hoy solo `fact_stock_mensual` (§5.7 y §6.3, F7).

    Conserva el mismo patrón atómico: staging → validar → EXCHANGE.
    """

    def sql_extraccion(self) -> str:  # pragma: no cover - no aplica
        raise NotImplementedError("Una tarea derivada no extrae de PostgreSQL.")

    @abstractmethod
    def sql_insert(self, tabla_destino: str) -> str:
        """`INSERT INTO <tabla_destino> SELECT …` desde otras tablas del DWH."""

    def cargar_en(self, client, tabla_staging: str) -> int:
        """Ejecuta el INSERT … SELECT y devuelve las filas escritas."""
        client.command(self.sql_insert(tabla_staging))
        filas = client.query(f"SELECT count() FROM {tabla_staging}").result_rows[0][0]
        logger.info(f"[{self.nombre}] {filas:,} filas derivadas dentro de {CH_DATABASE}")
        return filas

    def extraer(self) -> Iterator[list[tuple]]:
        """Una tarea derivada no produce lotes: la carga la hace `cargar_en`."""
        return iter(())

    def columnas(self) -> list[str]:
        """El INSERT … SELECT nombra sus propias columnas."""
        return []
