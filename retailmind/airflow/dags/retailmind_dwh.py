"""
retailmind_dwh — el pipeline DWH de RetailMind como DAG de Airflow.

21 tareas de carga (una por tabla del almacen) + 1 tarea de validacion que
corre los 49 controles cruzados PostgreSQL <-> ClickHouse.

===========================================================================
QUE HACE ESTE ARCHIVO, Y SOBRE TODO QUE **NO** HACE
===========================================================================

Airflow **envuelve** el ETL; no lo reimplementa. Cada tarea de aqui es una
invocacion de `python -m etl.dwh.run_etl`, el MISMO comando que se ejecuta a
mano en una terminal y el mismo que lanza el backend desde el boton
«Actualizar almacen». Ni una linea de logica de negocio vive en este archivo.

Consecuencia practica: si una tarea falla, se reproduce copiando su comando
del log y pegandolo en `docker compose --profile tools run --rm etl ...`.
No hay que depurar Airflow para depurar el pipeline.

Por eso NO se usa `python -m etl.dwh.cargar`, que seria la eleccion
"natural" por tener `--tabla` en singular: ese CLI **abre una corrida propia
por invocacion** (`run_etl.py:210-214`), asi que 21 tareas produciran 21
corridas en la bitacora en vez de una. `run_etl.py --tablas` respeta el
`--corrida-id` que se le impone, que es justo lo que hace falta.

===========================================================================
EL GRAFO: SIETE ARISTAS, LEIDAS DEL ETL Y NO INVENTADAS
===========================================================================

Las dependencias NO se deducen por intuicion (p. ej. "los hechos van despues
de las dimensiones"): eso seria una segunda fuente de verdad que se
desincronizaria con el ETL en la primera tabla nueva. Estan declaradas en el
atributo de clase `depende_de` de cada tarea, en `retailmind/etl/dwh/tablas/`,
y son SIETE. Verificadas contra el codigo y contra la salida de
`run_etl.py --orden`:

    fact_pedido             <- dim_cliente                        (fact_pedido.py:142)
    fact_venta_linea        <- dim_producto                       (fact_venta_linea.py:124)
    fact_novedad_envio      <- fact_envio                         (fact_novedad_envio.py:116)
    fact_devolucion_linea   <- fact_devolucion                    (fact_devolucion_linea.py:101)
    fact_stock_mensual      <- fact_movimiento_inventario,
                               dim_producto                       (fact_stock_mensual.py:141)
    fact_prevision_demanda  <- fact_venta_linea, dim_producto,
                               dim_fecha                          (fact_prevision_demanda.py:151)
    fact_alerta_cliente     <- fact_pedido, dim_cliente,
                               fact_ticket, fact_devolucion        (fact_alerta_cliente.py:91)

Las otras 14 tablas no declaran ninguna: son independientes y Airflow las
puede correr en paralelo. Eso es una PROPIEDAD REAL del pipeline, no una
licencia de este DAG - `run_etl.py` las serializa solo porque es un proceso
unico.

`DEPENDENCIAS` de abajo es la transcripcion literal de esas siete. Si alguien
anade una tabla al ETL con un `depende_de` nuevo y no la refleja aqui, el DAG
seguira corriendo pero con un grafo mentiroso; el `assert` del final del
archivo protege lo que se puede proteger desde aqui (que toda tabla nombrada
en una arista exista en la lista de tablas).

===========================================================================
UNA SOLA CORRIDA PARA LAS 22 TAREAS
===========================================================================

La bitacora `retailmind_dwh.etl_ejecucion` agrupa por `corrida_id`. Si cada
tarea generase el suyo, una ejecucion del DAG dejaria 22 corridas y el
endpoint `/api/dwh/estado` del backend - que lee esa bitacora - mostraria
basura.

El identificador se deriva con `uuid5(NAMESPACE_URL, run_id)`: es una funcion
PURA del `run_id` de Airflow, asi que las 22 tareas calculan el MISMO valor
sin hablar entre ellas, y una tarea que se reintenta vuelve a calcular el
mismo. Es deliberadamente mejor que generarlo en una tarea inicial y pasarlo
por XCom: eso anadiria una dependencia artificial de las 21 cargas a esa
tarea, ensuciando el grafo que este DAG existe para mostrar.

===========================================================================
CODIGOS DE SALIDA DE run_etl.py  (run_etl.py:80-83)
===========================================================================

    0  todo bien
    1  no arranco (ClickHouse inalcanzable, grafo invalido)
    2  fallo parcial: alguna tabla no se publico
    3  las 21 cargaron, pero los 49 controles NO cuadran

`BashOperator` falla ante cualquier codigo != 0, que es lo que se quiere para
0/1/2. El 3 es un caso aparte y merece explicarse en el log: significa que el
almacen ESTA PUBLICADO y las pantallas funcionan, pero alguna cifra no
coincide con PostgreSQL. La tarea se marca en rojo - debe verse - pero las
cargas ya publicadas no se tocan ni se revierten: `EXCHANGE TABLES` ya
ocurrio. El envoltorio de `validar` imprime esa distincion antes de salir.
"""

from __future__ import annotations

import pendulum
from airflow.models.dag import DAG
from airflow.operators.bash import BashOperator

# ── Constantes de ejecucion ──────────────────────────────────────────────

#: Directorio desde el que se invoca `python -m etl.dwh.run_etl`. Es el
#: montaje que declara el compose (./retailmind -> /opt/retailmind), el mismo
#: patron que usa el servicio `backend` con /etl.
ETL_HOME = "/opt/retailmind"

ZONA = "America/Guayaquil"

#: Las 21 tablas del modelo, en el orden de `registro.ORDEN_SUGERIDO`. El
#: orden de esta lista NO impone ejecucion (eso lo hacen las aristas): solo
#: fija como se dibujan y se leen en la interfaz.
TABLAS = [
    "dim_fecha",
    "dim_producto",
    "dim_cliente",
    "dim_proveedor",
    "dim_promocion_producto",
    "fact_pedido",
    "fact_venta_linea",
    "fact_flujo_caja",
    "fact_orden_compra",
    "fact_compra_linea",
    "fact_movimiento_inventario",
    "fact_envio",
    "fact_novedad_envio",
    "fact_devolucion",
    "fact_devolucion_linea",
    "fact_ticket",
    "fact_resena",
    "fact_devolucion_proveedor",
    "fact_stock_mensual",
    "fact_prevision_demanda",
    "fact_alerta_cliente",
]

#: {tabla -> tablas que deben haber terminado antes}. Transcripcion literal
#: de los `depende_de` del ETL. Son SIETE entradas; ni una mas.
DEPENDENCIAS: dict[str, tuple[str, ...]] = {
    "fact_pedido":            ("dim_cliente",),
    "fact_venta_linea":       ("dim_producto",),
    "fact_novedad_envio":     ("fact_envio",),
    "fact_devolucion_linea":  ("fact_devolucion",),
    "fact_stock_mensual":     ("fact_movimiento_inventario", "dim_producto"),
    "fact_prevision_demanda": ("fact_venta_linea", "dim_producto", "dim_fecha"),
    "fact_alerta_cliente":    ("fact_pedido", "dim_cliente",
                               "fact_ticket", "fact_devolucion"),
}

# Red de seguridad barata: que ninguna arista nombre una tabla inexistente.
# No puede comprobar que las aristas sigan siendo las del ETL (este proceso no
# importa el paquete `etl`), pero si que este archivo sea coherente consigo
# mismo.
_desconocidas = {
    t for destino, origenes in DEPENDENCIAS.items()
    for t in (destino, *origenes) if t not in TABLAS
}
assert not _desconocidas, f"Aristas hacia tablas que no existen: {_desconocidas}"


def _corrida_id(run_id: str) -> str:
    """
    UUID de corrida, funcion PURA del `run_id` de Airflow.

    Se expone como macro de Jinja para que las 22 plantillas de comando lo
    resuelvan al MISMO valor sin coordinarse, y para que un reintento
    reproduzca el de su primer intento.
    """
    import uuid
    return str(uuid.uuid5(uuid.NAMESPACE_URL, f"retailmind-dwh/{run_id}"))


# ── Plantillas de comando ────────────────────────────────────────────────
# `set -euo pipefail` para que el fallo del python se propague y no lo tape
# el `echo` siguiente.

CARGA = r"""
set -euo pipefail
cd {etl_home}
echo "corrida : {{{{ corrida_id(run_id) }}}}"
echo "tabla   : {tabla}"
python -m etl.dwh.run_etl \
    --tablas "{tabla}" \
    --corrida-id "{{{{ corrida_id(run_id) }}}}" \
    --sin-validacion
"""

# La validacion se aisla con `--tablas ","`.
#
# NO es un truco gratuito ni un abuso del parser: es la unica forma de correr
# los 49 controles CON el mismo `--corrida-id` sin modificar `run_etl.py`,
# que esta explicitamente fuera del alcance de este trabajo.
#   * `python -m etl.dwh.validar_dwh` ejecuta los mismos 49 controles, pero su
#     CLI no acepta `--corrida-id` y NO escribe en la bitacora: la corrida
#     quedaria sin su fila `validar_dwh` y el estado del backend la veria
#     incompleta.
#   * `--tablas ","` -> `[t.strip() for t in ",".split(",") if t.strip()]` da
#     la lista VACIA (run_etl.py:553-554), el orden topologico resuelve a cero
#     tareas, no se carga NI SE TOCA ninguna tabla, y `_validar()` corre igual
#     porque es incondicional cuando no se pasa `--sin-validacion`.
#   * Comprobado en vivo antes de escribir este DAG: «Publicadas 0 de 0 tareas
#     - 0 filas» + «los 49 controles CUADRAN», salida 0, en 1,7 s.
#
# Ojo con la comilla: `--tablas ","` con comillas dobles. Sin ellas, la coma
# suelta la interpreta el shell y el argumento llega vacio -> `args.tablas` es
# falsy -> `seleccion = None` -> **recarga las 21 tablas**. La comilla es
# funcional, no estilo.
VALIDACION = r"""
set -uo pipefail
cd {etl_home}
echo "corrida : {{{{ corrida_id(run_id) }}}}"
echo "Ejecutando los 49 controles cruzados PostgreSQL <-> ClickHouse."
set +e
python -m etl.dwh.run_etl \
    --tablas "," \
    --corrida-id "{{{{ corrida_id(run_id) }}}}"
rc=$?
set -e
case "$rc" in
  0)
    echo "OK: los 49 controles cuadran EXACTAMENTE."
    ;;
  3)
    echo "======================================================================"
    echo "CODIGO 3 - CONTROLES DESCUADRADOS (no es una carga fallida)"
    echo "======================================================================"
    echo "Las 21 tablas SI se publicaron: el EXCHANGE TABLES de cada una ya"
    echo "ocurrio y las pantallas de analitica siguen sirviendo datos. Lo que"
    echo "falla es la COMPARACION contra PostgreSQL: alguna cifra de control no"
    echo "coincide al centavo."
    echo ""
    echo "Esta tarea se marca FALLIDA a proposito, para que se vea en rojo, y"
    echo "NO revierte nada: no hay nada que revertir. Revisa arriba que control"
    echo "difiere y reproducelo con:"
    echo "  docker compose --profile tools run --rm etl \\"
    echo "      python -m etl.dwh.validar_dwh --detalle"
    echo "======================================================================"
    ;;
  1)
    echo "CODIGO 1: la corrida no pudo ARRANCAR (ClickHouse inalcanzable?)."
    ;;
  2)
    echo "CODIGO 2: fallo parcial - alguna tarea no quedo publicada."
    ;;
esac
exit "$rc"
"""


with DAG(
    dag_id="retailmind_dwh",
    description="Carga completa del almacen: 21 tablas + los 49 controles cruzados",
    # 02:00 America/Guayaquil, replicando el cron que tenia el @Scheduled del
    # backend (`application.properties:54`, DWH_CRON=0 0 2 * * *). Ese
    # @Scheduled se apaga con DWH_CRON=- para que no disparen los dos y
    # compitan por el EXCHANGE TABLES del mismo destino.
    schedule="0 2 * * *",
    start_date=pendulum.datetime(2026, 8, 1, tz=ZONA),
    # El pipeline es de CARGA COMPLETA: no hay marca de agua ni ventana
    # incremental, asi que recuperar dias perdidos no significa nada - cada
    # corrida reconstruye el almacen entero desde el estado presente de
    # PostgreSQL. Ponerse al dia solo produciria N corridas identicas.
    catchup=False,
    # Dos corridas simultaneas competirian por el `EXCHANGE TABLES` de la
    # misma tabla. Es el mismo guardia que el backend implementa en
    # `DwhActualizacionService` con su AtomicReference.
    max_active_runs=1,
    tags=["retailmind", "dwh", "clickhouse"],
    user_defined_macros={"corrida_id": _corrida_id},
    default_args={
        # UN solo reintento, y es una decision, no un descuido.
        #
        # `run_etl.py` YA reintenta por dentro: `--intentos` vale 3 por
        # defecto, con espera 5 s y 10 s (run_etl.py:64-65, 239-258), y esos
        # reintentos cubren justo el fallo transitorio esperable - una
        # `ValidacionFallida` por deriva del origen vivo, o una conexion que
        # se cae. Poner `retries=3` aqui daria 3 x 3 = 9 intentos por tabla y
        # convertiria un fallo real en cuatro minutos de espera antes de
        # verlo en rojo.
        #
        # El unico reintento de Airflow cubre lo que el ETL no puede: que el
        # PROCESO entero muera (contenedor reiniciado, ClickHouse aun
        # levantandose). Peor caso: 2 x 3 = 6 intentos, y como una carga
        # completa tarda ~6 s, sigue siendo cuestion de segundos.
        "retries": 1,
        "retry_delay": pendulum.duration(seconds=30),
        # Una corrida entera son ~6 s. Un minuto por tabla es margen de sobra
        # y evita que una tarea colgada bloquee el DAG indefinidamente.
        "execution_timeout": pendulum.duration(minutes=5),
        "depends_on_past": False,
    },
    doc_md=__doc__,
) as dag:

    tareas = {
        tabla: BashOperator(
            task_id=tabla,
            bash_command=CARGA.format(etl_home=ETL_HOME, tabla=tabla),
            doc_md=(
                f"Carga COMPLETA de `{tabla}`.\n\n"
                "Patron `staging -> validar -> EXCHANGE TABLES` "
                "(`carga_atomica.py:112-141`): idempotente, y si la validacion "
                "propia de la tabla no cuadra **no se publica** y queda la "
                "version anterior."
            ),
        )
        for tabla in TABLAS
    }

    validar = BashOperator(
        task_id="validar_dwh",
        bash_command=VALIDACION.format(etl_home=ETL_HOME),
        doc_md=(
            "Los **49 controles cruzados** PostgreSQL <-> ClickHouse, con el "
            "mismo `corrida_id` que las 21 cargas.\n\n"
            "No carga ninguna tabla (`--tablas \",\"` = seleccion vacia). "
            "Codigo de salida **3** = las tablas estan publicadas pero alguna "
            "cifra no cuadra: la tarea se marca fallida y no revierte nada."
        ),
    )

    # ── Las SIETE aristas del ETL ────────────────────────────────────────
    for destino, origenes in DEPENDENCIAS.items():
        for origen in origenes:
            tareas[origen] >> tareas[destino]

    # ── La validacion cierra la corrida ──────────────────────────────────
    # Depende de las 21, no solo de las hojas: los controles comparan el
    # almacen COMPLETO contra PostgreSQL, asi que no puede empezar mientras
    # cualquier tabla siga cargandose.
    for tabla in TABLAS:
        tareas[tabla] >> validar
