"""
etl/dwh/cargar.py
Punto de entrada del pipeline DWH. Carga UNA tabla por invocación.

    python -m etl.dwh.cargar --verificar          # ¿responden PostgreSQL y ClickHouse?
    python -m etl.dwh.cargar --init               # crea retailmind_dwh + etl_ejecucion
    python -m etl.dwh.cargar --listar             # estado de las 21 tablas
    python -m etl.dwh.cargar --bitacora           # últimas corridas
    python -m etl.dwh.cargar --tabla fact_pedido  # carga esa tabla

Se ejecuta desde `retailmind/`.

Que cada tabla sea un script AUTÓNOMO e IDEMPOTENTE es la decisión de §7.4 del
diseño, y es la que desacopla el riesgo de la infraestructura del riesgo del
dato: el mismo comando lo invoca a mano una persona, lo invoca `run_etl.py` en
orden topológico, o lo invoca un `BashOperator` de Airflow de una sola línea. El
DAG no contendría lógica de negocio, solo orden y dependencias — y por eso la
decisión de montar Airflow o no sigue siendo reversible.
"""

import argparse
import sys
import time

from etl.dwh import bitacora, registro
from etl.dwh.carga_atomica import ValidacionFallida, cargar_atomico
from etl.dwh.conexiones import (
    CH_DATABASE,
    ZONA_HORARIA,
    get_ch_client,
    get_ch_client_sin_base,
    logger,
    verificar_clickhouse,
    verificar_postgres,
)


def inicializar() -> int:
    """
    Fase 0: crea la base del DWH y la bitácora. Idempotente.

    NO toca la base `retailmind` legada de ClickHouse, que queda congelada como
    archivo (§6.3 del diagnóstico). `conexiones._validar_destino` lo impide por
    construcción.
    """
    cliente = get_ch_client_sin_base()
    try:
        # Motor `Atomic` EXPLÍCITO: es el que hace que `EXCHANGE TABLES` sea
        # atómico, y ese intercambio es todo el patrón de publicación (§6.2).
        cliente.command(f"CREATE DATABASE IF NOT EXISTS {CH_DATABASE} ENGINE = Atomic")
        print(f"[OK] Base '{CH_DATABASE}' lista (ENGINE = Atomic).")
    finally:
        cliente.close()

    cliente = get_ch_client()
    try:
        bitacora.asegurar_bitacora(cliente)
        print(f"[OK] Bitácora '{CH_DATABASE}.{bitacora.TABLA_BITACORA}' lista "
              f"(zona horaria {ZONA_HORARIA}).")
    finally:
        cliente.close()

    return 0


def cargar_tabla(nombre: str) -> int:
    """
    Ejecuta el patrón atómico completo del §6.2 para una tabla y deja constancia
    en la bitácora pase lo que pase.
    """
    tarea = registro.obtener(nombre)
    corrida = bitacora.nueva_corrida()
    inicio = bitacora.ahora()
    # Reloj MONÓTONO para la duración: `inicio`/`fin` van truncados al segundo
    # porque alimentan columnas DateTime, y restarlos daría siempre 0 o 1 s.
    t0 = time.perf_counter()
    cliente = get_ch_client()

    try:
        resultado = cargar_atomico(cliente, tarea)
        segundos = time.perf_counter() - t0
        bitacora.registrar(
            tarea=nombre, inicio=inicio, resultado=bitacora.RESULTADO_EXITO,
            filas_leidas=resultado.filas_leidas,
            filas_escritas=resultado.filas_escritas,
            excepciones=resultado.excepciones,
            mensaje=resultado.mensaje, corrida_id=corrida, client=cliente,
            duracion_seg=segundos,
        )
        print(f"[OK] {nombre}: {resultado.mensaje} · {segundos:.2f} s")
        # Gancho para las tareas que producen un PARTE además de filas. Hoy solo
        # el modelo de previsión: su backtest es el entregable de la fase y
        # dejarlo únicamente en el log de un archivo sería esconderlo.
        parte = getattr(tarea, "resumen", None)
        if callable(parte):
            print(parte())
        return 0

    except ValidacionFallida as e:
        # El caso que el diseño quiere que NO pase desapercibido: los conteos no
        # cuadran, así que la tabla NO se publica y el informe sigue mostrando el
        # dato de ayer. Degradación correcta, no pantalla en blanco.
        logger.error(f"[{nombre}] ABORTADA sin publicar: {e}")
        bitacora.registrar(
            tarea=nombre, inicio=inicio, resultado=bitacora.RESULTADO_ABORTADO,
            mensaje=str(e), corrida_id=corrida, client=cliente,
        )
        print(f"[ABORTADA] {nombre}: {e}")
        print("           La tabla publicada NO se modificó.")
        return 2

    except Exception as e:
        logger.exception(f"[{nombre}] falló")
        bitacora.registrar(
            tarea=nombre, inicio=inicio, resultado=bitacora.RESULTADO_FALLO,
            mensaje=f"{type(e).__name__}: {e}", corrida_id=corrida, client=cliente,
        )
        print(f"[ERROR] {nombre}: {type(e).__name__}: {e}")
        return 1

    finally:
        cliente.close()


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        prog="python -m etl.dwh.cargar",
        description="Carga una tabla del data warehouse RetailMind "
                    "(PostgreSQL → ClickHouse, full refresh atómico).",
    )
    grupo = parser.add_mutually_exclusive_group(required=True)
    grupo.add_argument("--tabla", metavar="NOMBRE",
                       help="tabla del DWH a cargar (ver --listar)")
    grupo.add_argument("--init", action="store_true",
                       help=f"crea la base {CH_DATABASE} y la bitácora")
    grupo.add_argument("--listar", action="store_true",
                       help="estado de las 21 tablas del modelo")
    grupo.add_argument("--verificar", action="store_true",
                       help="comprueba que PostgreSQL y ClickHouse responden")
    grupo.add_argument("--bitacora", action="store_true",
                       help="últimas corridas registradas")
    args = parser.parse_args(argv)

    if args.verificar:
        return 0 if (verificar_postgres() and verificar_clickhouse()) else 1
    if args.init:
        return inicializar()
    if args.listar:
        registro.listar()
        return 0
    if args.bitacora:
        bitacora.listar()
        return 0

    try:
        return cargar_tabla(args.tabla)
    except KeyError as e:
        print(f"[ERROR] {e.args[0]}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
