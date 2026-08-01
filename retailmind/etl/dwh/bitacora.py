"""
etl/dwh/bitacora.py
Bitácora de corridas del pipeline DWH: tabla `retailmind_dwh.etl_ejecucion`.

Es el equivalente del `carga_historial` que ya usaba el ETL viejo
(`utils/load_tracker.py`) y sigue su mismo patrón: el DDL vive junto al código,
es `CREATE ... IF NOT EXISTS` y se asegura antes de cualquier escritura.
Diferencias: vive en ClickHouse (no en PostgreSQL, que para este pipeline es
SOLO LECTURA) y el grano es la TAREA, no la semana.

Campos exigidos por §8.4 del diseño: tarea, inicio, fin, filas leídas, filas
escritas, resultado y mensaje. Se añaden tres que el propio diseño reclama:

  * `corrida_id`  — agrupa las ~19 tareas de una misma corrida del DAG.
  * `excepciones` — contador de filas que la transformación no pudo resolver y
    decidió no silenciar (§5.2, los 6 pedidos legacy de descuento; §5.3, los
    valores de `motivo_fallo` no previstos que caen a 'otro').
  * `duracion_seg`— para el Gantt, sin tener que restar en cada consulta.
"""

import uuid
from datetime import datetime
from zoneinfo import ZoneInfo

from etl.dwh.conexiones import CH_DATABASE, ZONA_HORARIA, get_ch_client, logger

TABLA_BITACORA = "etl_ejecucion"

RESULTADO_EXITO    = "exito"
RESULTADO_FALLO    = "fallo"
RESULTADO_ABORTADO = "abortado"   # la validación falló: NO se publicó la tabla

_DDL_BITACORA = f"""
CREATE TABLE IF NOT EXISTS {{db}}.{TABLA_BITACORA}
(
    corrida_id     UUID,
    tarea          LowCardinality(String),
    inicio         DateTime('{ZONA_HORARIA}'),
    fin            Nullable(DateTime('{ZONA_HORARIA}')),
    duracion_seg   Nullable(Float32),
    filas_leidas   UInt64,
    filas_escritas UInt64,
    excepciones    UInt32,
    resultado      LowCardinality(String),
    mensaje        String
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(inicio)
ORDER BY (inicio, tarea)
"""


def ahora():
    """Instante actual en la zona del negocio (§8.6: nunca UTC implícito)."""
    return datetime.now(ZoneInfo(ZONA_HORARIA)).replace(microsecond=0)


def asegurar_bitacora(client=None, db: str = CH_DATABASE) -> None:
    """Crea `etl_ejecucion` si no existe. Idempotente."""
    propio = client is None
    client = client or get_ch_client()
    try:
        client.command(_DDL_BITACORA.format(db=db))
    finally:
        if propio:
            client.close()


def nueva_corrida() -> uuid.UUID:
    """Identificador que agrupa todas las tareas de una misma ejecución."""
    return uuid.uuid4()


def registrar(
    tarea: str,
    inicio: datetime,
    resultado: str,
    filas_leidas: int = 0,
    filas_escritas: int = 0,
    excepciones: int = 0,
    mensaje: str = "",
    corrida_id: uuid.UUID | None = None,
    client=None,
    duracion_seg: float | None = None,
) -> None:
    """
    Inserta una fila en la bitácora. NUNCA propaga su propia excepción: que
    falle el registro no puede tumbar una carga que sí funcionó (mismo criterio
    que `utils/error_reporter.py`).

    `duracion_seg` se recibe medida con un reloj MONÓTONO desde `cargar.py`.
    Restar `fin - inicio` daría un número redondo y falso: ambos vienen de
    `ahora()`, que trunca los microsegundos para la columna DateTime, así que
    toda carga de menos de un segundo se registraría como 0 o 1 s exactos. Si
    no se pasa, se cae a la resta como aproximación.
    """
    propio = client is None
    try:
        client = client or get_ch_client()
        asegurar_bitacora(client)
        fin = ahora()
        if duracion_seg is None:
            duracion_seg = (fin - inicio).total_seconds()
        client.insert(
            f"{CH_DATABASE}.{TABLA_BITACORA}",
            [[
                corrida_id or nueva_corrida(),
                tarea,
                inicio,
                fin,
                round(duracion_seg, 3),
                filas_leidas,
                filas_escritas,
                excepciones,
                resultado,
                mensaje[:4000],
            ]],
            column_names=[
                "corrida_id", "tarea", "inicio", "fin", "duracion_seg",
                "filas_leidas", "filas_escritas", "excepciones",
                "resultado", "mensaje",
            ],
        )
    except Exception as e:
        logger.warning(f"No se pudo registrar la corrida de '{tarea}' en la bitácora: {e}")
    finally:
        if propio and client is not None:
            try:
                client.close()
            except Exception:
                pass


def listar(limite: int = 20) -> None:
    """Imprime las últimas corridas registradas."""
    client = get_ch_client()
    try:
        asegurar_bitacora(client)
        filas = client.query(
            f"SELECT inicio, tarea, resultado, filas_leidas, filas_escritas, "
            f"excepciones, duracion_seg, mensaje "
            f"FROM {CH_DATABASE}.{TABLA_BITACORA} ORDER BY inicio DESC LIMIT {int(limite)}"
        ).result_rows

        if not filas:
            print("  (sin corridas registradas)")
            return

        print(f"\n{'Inicio':<20} {'Tarea':<28} {'Resultado':<10} "
              f"{'Leídas':>9} {'Escritas':>9} {'Exc':>5} {'Seg':>7}")
        print("-" * 96)
        for inicio, tarea, res, leidas, escritas, exc, seg, msg in filas:
            print(f"{str(inicio)[:19]:<20} {tarea:<28} {res:<10} "
                  f"{leidas:>9,} {escritas:>9,} {exc:>5} {seg or 0:>7.2f}")
            if res != RESULTADO_EXITO and msg:
                print(f"    ↳ {msg[:110]}")
        print()
    finally:
        client.close()
