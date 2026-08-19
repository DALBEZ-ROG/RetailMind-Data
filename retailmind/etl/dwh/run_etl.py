"""
etl/dwh/run_etl.py
ORQUESTADOR del pipeline DWH: las 21 cargas en orden topológico, una corrida.

    python -m etl.dwh.run_etl                     # corrida completa + validación
    python -m etl.dwh.run_etl --orden             # solo imprime el orden resuelto
    python -m etl.dwh.run_etl --tablas a,b,c      # subconjunto (respeta el orden)
    python -m etl.dwh.run_etl --sin-validacion    # sin los 44 controles finales
    python -m etl.dwh.run_etl --corrida-id UUID   # id impuesto desde fuera

Se ejecuta desde `retailmind/`.

Es el paso que §7.3 del diseño pide ANTES de decidir sobre Airflow: mover las
tareas con un orquestador propio y sencillo. No se pierde nada si mañana se
envuelve en un DAG — cada tarea sigue siendo autónoma e idempotente, y este
archivo solo aporta ORDEN, REINTENTOS y REPORTE. Un `BashOperator` por tarea
reproduciría el mismo grafo sin tocar una línea de lógica de negocio.

TRES decisiones que conviene leer antes de tocar esto:

1. **El grafo NO está escrito aquí.** Se deriva de `depende_de`, que cada tarea
   declara en su propia clase. Copiar el orden a este archivo crearía una
   segunda fuente de verdad que se desincronizaría en la primera tabla nueva.
   El desempate entre tareas igual de listas es la posición en
   `registro.ORDEN_SUGERIDO`, para que dos corridas den EXACTAMENTE la misma
   secuencia: un orden que baila hace irreproducible un fallo.

2. **Un fallo NO detiene la corrida, pero SÍ detiene su descendencia.** Si
   `fact_movimiento_inventario` aborta, `fact_stock_mensual` no se intenta —
   se derivaría de un dato que acaba de rechazarse—, y se marca `omitido`. Las
   17 tablas que no dependen de ella se cargan igual. El resumen final dice
   cuántas quedaron fuera y por culpa de quién, y el código de salida es
   distinto de 0. Lo que el diseño no tolera es lo contrario: que una carga que
   no cuadra pase por buena, o que el resto se detenga en silencio.

3. **Una validación fallida SÍ se reintenta.** Es la decisión menos obvia del
   archivo. `ValidacionFallida` significa «los conteos no cuadran», que suena a
   error permanente, pero el origen es una base OLTP VIVA: si entra un pedido
   entre el `INSERT` del staging y la consulta de control, el conteo difiere en
   uno y la tabla se rechaza con razón. Ese caso es transitorio y el reintento
   lo resuelve. Si el descuadre es real, los 3 intentos fallan igual y la tabla
   no se publica — el patrón atómico sigue siendo el que decide, no esta
   política. Lo que NO se hace jamás es rebajar el criterio a una tolerancia.

La bitácora `etl_ejecucion` recibe UNA fila por tarea (la del resultado final,
con los reintentos anotados en el mensaje) más dos filas marcadoras de la
corrida (`corrida`, al abrir y al cerrar), que son las que permiten a la
aplicación saber si hay una ejecución en curso sin conocer este proceso.
"""

import argparse
import sys
import time
import unicodedata
import uuid
from dataclasses import dataclass, field

from etl.dwh import bitacora, registro, validar_dwh
from etl.dwh.carga_atomica import ValidacionFallida, cargar_atomico
from etl.dwh.conexiones import CH_DATABASE, get_ch_client, logger

# ── Política de reintentos ───────────────────────────────────────────────────

INTENTOS_POR_DEFECTO = 3
ESPERA_BASE_SEG = 5.0        # se duplica en cada reintento (5 s, 10 s)

# ── Nombres reservados en la bitácora ────────────────────────────────────────
# No son tablas: son las filas que describen la CORRIDA. Se distinguen de las
# 19 tablas por nombre, y la aplicación los filtra por él.

TAREA_CORRIDA = "corrida"
TAREA_VALIDACION = "validar_dwh"

RESULTADO_EN_CURSO = "en_curso"
RESULTADO_OMITIDO = "omitido"
RESULTADO_FALLO_PARCIAL = "fallo_parcial"

# ── Códigos de salida ────────────────────────────────────────────────────────

SALIDA_OK = 0
SALIDA_ERROR_ARRANQUE = 1    # ni siquiera se pudo empezar (ClickHouse, grafo…)
SALIDA_FALLO_PARCIAL = 2     # alguna tabla falló, abortó o quedó omitida
SALIDA_VALIDACION = 3        # las 21 cargaron, pero los controles no cuadran


def _ascii(texto: str) -> str:
    """
    Deja el mensaje en ASCII antes de escribirlo en la bitácora.

    La bitácora es lo ÚNICO que cruza la frontera Python → ClickHouse → Java, y
    ahí el texto no acentuado deja de ser una preferencia. El driver JDBC de
    ClickHouse (0.6.5) decodifica la respuesta como latin-1: un simple `·`
    escrito desde aquí llega al navegador como `Â·`, y «validación» como
    «validaciÃ³n». No es un fallo de este orquestador —lo mismo le pasaría a
    cualquier texto acentuado que se cargara en el almacén, y está declarado
    como hallazgo—, pero el parte de una corrida es telemetría de máquina y no
    tiene por qué apostar a que las tres capas coincidan en la codificación.

    Solo afecta a lo que se GUARDA. La consola sigue imprimiendo el texto
    completo con sus acentos, y las cadenas de la interfaz viven en el código
    Java, que sí viaja bien.
    """
    equivalencias = {"·": "-", "—": "-", "–": "-", "«": '"', "»": '"',
                     "↳": "->", "…": "..."}
    for original, llano in equivalencias.items():
        texto = texto.replace(original, llano)
    return unicodedata.normalize("NFKD", texto).encode("ascii", "ignore").decode("ascii")


@dataclass
class ResultadoTarea:
    tabla: str
    resultado: str
    filas: int = 0
    segundos: float = 0.0
    intentos: int = 1
    mensaje: str = ""
    #: Dependencia que la dejó fuera, cuando `resultado == omitido`.
    bloqueada_por: str = ""


@dataclass
class ResultadoCorrida:
    corrida_id: uuid.UUID
    tareas: list[ResultadoTarea] = field(default_factory=list)
    segundos: float = 0.0
    validacion_ejecutada: bool = False
    validacion_ok: bool = False

    def por_resultado(self, resultado: str) -> list[ResultadoTarea]:
        return [t for t in self.tareas if t.resultado == resultado]

    @property
    def exitosas(self) -> list[ResultadoTarea]:
        return self.por_resultado(bitacora.RESULTADO_EXITO)

    @property
    def todo_bien(self) -> bool:
        return all(t.resultado == bitacora.RESULTADO_EXITO for t in self.tareas)


# ── Orden topológico ─────────────────────────────────────────────────────────

class GrafoInvalido(RuntimeError):
    """Las dependencias declaradas no forman un orden ejecutable."""


def dependencias() -> dict[str, tuple[str, ...]]:
    """
    {tabla → tablas que deben haber corrido antes}, leído de las CLASES.

    Se instancia cada tarea porque `depende_de` es un atributo de clase y se
    quiere el valor efectivo de la subclase, no el vacío del molde.
    """
    return {nombre: tuple(clase.depende_de) for nombre, clase in registro.TAREAS.items()}


def orden_topologico(seleccion: list[str] | None = None) -> list[str]:
    """
    Resuelve el orden de ejecución por Kahn, con desempate DETERMINISTA.

    Entre varias tareas listas a la vez se elige la que antes aparece en
    `registro.ORDEN_SUGERIDO` — así dos corridas producen la misma secuencia y
    un fallo se puede reproducir tal cual. Sin ese desempate el orden dependería
    del recorrido de un diccionario y cambiaría entre versiones de Python.

    Las dependencias que caen FUERA de la selección se ignoran: pedir
    `--tablas fact_stock_mensual` a solas es legítimo (recalcula la derivada
    sobre el kardex ya publicado) y no debe arrastrar media corrida.
    """
    grafo = dependencias()
    nombres = list(seleccion) if seleccion is not None else list(grafo)

    desconocidas = [n for n in nombres if n not in grafo]
    if desconocidas:
        raise GrafoInvalido(
            f"Tareas no registradas: {', '.join(desconocidas)}. "
            f"Disponibles: {', '.join(sorted(grafo))}"
        )

    dentro = set(nombres)
    pendientes = {n: {d for d in grafo[n] if d in dentro} for n in nombres}

    prioridad = {n: i for i, n in enumerate(registro.ORDEN_SUGERIDO)}
    def clave(n: str) -> tuple[int, str]:
        return (prioridad.get(n, len(prioridad)), n)

    orden: list[str] = []
    while pendientes:
        listas = sorted((n for n, deps in pendientes.items() if not deps), key=clave)
        if not listas:
            raise GrafoInvalido(
                f"Ciclo de dependencias entre: {', '.join(sorted(pendientes))}. "
                f"Revisa los `depende_de` de esas tareas."
            )
        siguiente = listas[0]
        orden.append(siguiente)
        del pendientes[siguiente]
        for deps in pendientes.values():
            deps.discard(siguiente)
    return orden


# ── Ejecución de una tarea ───────────────────────────────────────────────────

def _ejecutar_tarea(client, nombre: str, corrida: uuid.UUID,
                    intentos: int, espera: float) -> ResultadoTarea:
    """
    Carga UNA tabla con reintentos y deja UNA fila en la bitácora.

    Se apoya en `cargar_atomico`, que es la pieza compartida del §6.2, y no en
    `cargar.py`: ese es el envoltorio de línea de comandos y abre una corrida
    propia por invocación, lo que partiría en 19 corridas lo que es una sola.
    La mecánica de staging → validar → EXCHANGE es exactamente la misma.
    """
    tarea = registro.obtener(nombre)
    inicio = bitacora.ahora()
    t0 = time.perf_counter()
    ultimo_error = ""
    resultado_final = bitacora.RESULTADO_FALLO

    for intento in range(1, intentos + 1):
        try:
            res = cargar_atomico(client, tarea)
            segundos = time.perf_counter() - t0
            nota = f" (tras {intento} intentos)" if intento > 1 else ""
            bitacora.registrar(
                tarea=nombre, inicio=inicio, resultado=bitacora.RESULTADO_EXITO,
                filas_leidas=res.filas_leidas, filas_escritas=res.filas_escritas,
                excepciones=res.excepciones, mensaje=_ascii(res.mensaje + nota),
                corrida_id=corrida, client=client, duracion_seg=segundos,
            )
            return ResultadoTarea(
                tabla=nombre, resultado=bitacora.RESULTADO_EXITO,
                filas=res.filas_escritas, segundos=segundos, intentos=intento,
                mensaje=res.mensaje,
            )

        except ValidacionFallida as e:
            # Puede ser deriva del origen VIVO (una venta entre el volcado y el
            # control) o un descuadre real. Se reintenta; si es real, los tres
            # intentos fallan igual y la tabla NO se publica.
            ultimo_error = str(e)
            resultado_final = bitacora.RESULTADO_ABORTADO
            logger.warning(f"[{nombre}] intento {intento}/{intentos} ABORTÓ: {e}")

        except Exception as e:                       # noqa: BLE001 - se reporta
            ultimo_error = f"{type(e).__name__}: {e}"
            resultado_final = bitacora.RESULTADO_FALLO
            logger.warning(f"[{nombre}] intento {intento}/{intentos} falló: {ultimo_error}")

        if intento < intentos:
            pausa = espera * (2 ** (intento - 1))
            print(f"      reintento {intento + 1}/{intentos} en {pausa:.0f} s…")
            time.sleep(pausa)
            # Un fallo de conexión deja el cliente inservible: se renueva antes
            # del siguiente intento o los 3 fallarían por la misma razón.
            client = _renovar(client)

    segundos = time.perf_counter() - t0
    bitacora.registrar(
        tarea=nombre, inicio=inicio, resultado=resultado_final,
        mensaje=_ascii(f"{ultimo_error} (tras {intentos} intentos)"),
        corrida_id=corrida, client=client, duracion_seg=segundos,
    )
    return ResultadoTarea(
        tabla=nombre, resultado=resultado_final, segundos=segundos,
        intentos=intentos, mensaje=ultimo_error,
    )


def _renovar(client):
    """Cliente de ClickHouse nuevo; si tampoco se puede, se devuelve el viejo."""
    try:
        client.close()
    except Exception:
        pass
    try:
        return get_ch_client()
    except Exception as e:
        logger.warning(f"No se pudo renovar la conexión a ClickHouse: {e}")
        return client


# ── La corrida ───────────────────────────────────────────────────────────────

def ejecutar(seleccion: list[str] | None = None,
             corrida_id: uuid.UUID | None = None,
             intentos: int = INTENTOS_POR_DEFECTO,
             espera: float = ESPERA_BASE_SEG,
             con_validacion: bool = True) -> ResultadoCorrida:
    """Recorre el grafo, carga lo que puede y devuelve el parte completo."""
    orden = orden_topologico(seleccion)
    corrida = corrida_id or bitacora.nueva_corrida()
    resultado = ResultadoCorrida(corrida_id=corrida)
    t0 = time.perf_counter()

    client = get_ch_client()
    try:
        bitacora.asegurar_bitacora(client)
        # Marcador de apertura: es lo que permite a la aplicación saber que hay
        # una corrida viva sin hablar con este proceso. `filas_leidas` lleva
        # AQUÍ el número de tareas en cola —y no filas— para que el endpoint de
        # estado pueda decir «7 de 19» sin mantener por su cuenta una constante
        # que caducaría con la primera tabla nueva del modelo.
        bitacora.registrar(
            tarea=TAREA_CORRIDA, inicio=bitacora.ahora(),
            resultado=RESULTADO_EN_CURSO, filas_leidas=len(orden),
            mensaje=f"{len(orden)} tareas en cola", corrida_id=corrida,
            client=client, duracion_seg=0.0,
        )

        _cabecera(orden, corrida)

        #: tabla → resultado, para decidir si su descendencia puede correr.
        estado: dict[str, str] = {}

        for i, nombre in enumerate(orden, start=1):
            bloqueo = _dependencia_rota(nombre, estado)
            if bloqueo:
                print(f"  [{i:>2}/{len(orden)}] {nombre:<28} OMITIDA "
                      f"— depende de '{bloqueo}', que no quedó publicada")
                bitacora.registrar(
                    tarea=nombre, inicio=bitacora.ahora(), resultado=RESULTADO_OMITIDO,
                    mensaje=_ascii(f"Omitida: su dependencia '{bloqueo}' no se "
                                   f"publicó en esta corrida."),
                    corrida_id=corrida, client=client, duracion_seg=0.0,
                )
                estado[nombre] = RESULTADO_OMITIDO
                resultado.tareas.append(ResultadoTarea(
                    tabla=nombre, resultado=RESULTADO_OMITIDO, bloqueada_por=bloqueo,
                    mensaje=f"Depende de '{bloqueo}'.",
                ))
                continue

            print(f"  [{i:>2}/{len(orden)}] {nombre:<28} …", flush=True)
            parte = _ejecutar_tarea(client, nombre, corrida, intentos, espera)
            # `_ejecutar_tarea` pudo renovar el cliente por dentro; se recupera
            # uno sano para la siguiente si el actual quedó cerrado.
            client = _vivo(client)
            estado[nombre] = parte.resultado
            resultado.tareas.append(parte)
            _linea(i, len(orden), parte)

        # ── Validación final: la última tarea de la corrida ──────────────────
        if con_validacion:
            resultado.validacion_ejecutada = True
            resultado.validacion_ok = _validar(client, corrida)

    finally:
        resultado.segundos = time.perf_counter() - t0
        try:
            _cerrar(client, resultado)
        finally:
            try:
                client.close()
            except Exception:
                pass

    return resultado


def _dependencia_rota(nombre: str, estado: dict[str, str]) -> str:
    """
    Primera dependencia de `nombre` que NO quedó publicada en esta corrida.

    Una dependencia que no se ejecutó en esta corrida (porque no estaba en la
    selección) no bloquea: su tabla sigue publicada de la corrida anterior.
    """
    for dep in registro.TAREAS[nombre].depende_de:
        if estado.get(dep, bitacora.RESULTADO_EXITO) != bitacora.RESULTADO_EXITO:
            return dep
    return ""


def _vivo(client):
    """Devuelve un cliente utilizable; si el actual murió, abre otro."""
    try:
        client.query("SELECT 1")
        return client
    except Exception:
        return _renovar(client)


def _validar(client, corrida: uuid.UUID) -> bool:
    """
    Ejecuta los controles de `validar_dwh` y los registra como una tarea más.

    Se invoca EN PROCESO y no como un subproceso: comparte configuración y
    conexión, y su salida se intercala en el mismo informe de consola. El valor
    de retorno de `validar()` ya es 0/1, el mismo criterio que usa su CLI.
    """
    print("\n" + "-" * 78)
    print("  ÚLTIMA TAREA — validación cruzada PostgreSQL ↔ ClickHouse")
    print("-" * 78)

    inicio = bitacora.ahora()
    t0 = time.perf_counter()
    try:
        codigo = validar_dwh.validar()
        segundos = time.perf_counter() - t0
        ok = codigo == 0
        total = len(validar_dwh.CONTROLES)
        bitacora.registrar(
            tarea=TAREA_VALIDACION, inicio=inicio,
            resultado=bitacora.RESULTADO_EXITO if ok else bitacora.RESULTADO_FALLO,
            filas_leidas=total, filas_escritas=total if ok else 0,
            mensaje=_ascii(f"Los {total} controles cuadran exactamente." if ok
                           else f"Hay controles que DIFIEREN de {total}. "
                                f"Revisa la salida de validar_dwh."),
            corrida_id=corrida, client=client, duracion_seg=segundos,
        )
        return ok
    except Exception as e:                            # noqa: BLE001 - se reporta
        segundos = time.perf_counter() - t0
        logger.exception("La validación final falló")
        bitacora.registrar(
            tarea=TAREA_VALIDACION, inicio=inicio, resultado=bitacora.RESULTADO_FALLO,
            mensaje=_ascii(f"{type(e).__name__}: {e}"), corrida_id=corrida,
            client=client, duracion_seg=segundos,
        )
        print(f"  [ERROR] No se pudo validar: {type(e).__name__}: {e}")
        return False


#: Prefijo con el que se sella la identidad del ORIGEN en la bitácora.
#: La aplicación lo busca por este prefijo exacto (`InformeCompuestoServiceBase`).
SELLO_ORIGEN = "origen="


def identidad_del_origen() -> str:
    """
    Identifica de forma inequívoca la base PostgreSQL de la que salió esta carga.

    ── POR QUÉ HACE FALTA (defecto D-07) ────────────────────────────────────
    Un informe compuesto lee ClickHouse. Si el almacén quedara desalineado de la
    base operativa —restaurado de otra copia, backend repuntado a otra base, ETL
    parado mucho tiempo— **el informe seguiría respondiendo 200 con cifras
    perfectamente formadas de OTRO conjunto de datos**, y nada lo detectaría. Se
    comprobó midiendo: con PostgreSQL vacío, los compuestos publicaban 14.333.990
    unidades vendidas.
    La frescura ya estaba resuelta (`datosAl`); lo que faltaba era la
    CORRESPONDENCIA. Este sello es la mitad del ETL: deja escrito de dónde vino
    el dato para que quien lo lea pueda comprobar que habla de su propia base.

    ── QUÉ SE USA COMO IDENTIDAD ────────────────────────────────────────────
    El `system_identifier` de `pg_control_system()`: un entero de 64 bits que
    PostgreSQL genera en el `initdb` y que es **único por CLÚSTER**. No sirve el
    nombre de la base a secas —dos clústeres distintos tienen su `retailmind`, y
    ése es justo el escenario peligroso—, así que van los dos juntos.

    Nunca lanza: si no se puede leer, devuelve una marca de desconocido y la
    corrida sigue. Un sello de auditoría no puede tumbar una carga que funcionó.
    """
    try:
        from etl.dwh.conexiones import get_pg_connection
        with get_pg_connection() as conexion:
            with conexion.cursor() as cur:
                cur.execute(
                    "SELECT current_database(), system_identifier FROM pg_control_system()")
                base, identificador = cur.fetchone()
        return f"{SELLO_ORIGEN}{base}@{identificador}"
    except Exception as e:                                   # noqa: BLE001
        logger.warning("No se pudo sellar la identidad del origen: %s", e)
        return f"{SELLO_ORIGEN}desconocido"


def _cerrar(client, resultado: ResultadoCorrida) -> None:
    """Marcador de cierre: es lo que apaga el «en curso» de la aplicación."""
    fallidas = [t for t in resultado.tareas if t.resultado != bitacora.RESULTADO_EXITO]
    ok = not fallidas and (not resultado.validacion_ejecutada or resultado.validacion_ok)
    filas = sum(t.filas for t in resultado.tareas)

    if ok:
        mensaje = (f"{len(resultado.exitosas)} tareas publicadas · {filas:,} filas"
                   + (" · controles en verde" if resultado.validacion_ejecutada else ""))
    else:
        partes = []
        if fallidas:
            partes.append(", ".join(f"{t.tabla} ({t.resultado})" for t in fallidas))
        if resultado.validacion_ejecutada and not resultado.validacion_ok:
            partes.append("la validación final NO cuadra")
        mensaje = "Fallo parcial: " + " | ".join(partes)

    # El sello de origen va SIEMPRE, cuadre o no la corrida: si algo salió mal,
    # saber de qué base salió es todavía más útil.
    mensaje = f"{mensaje} · {identidad_del_origen()}"

    bitacora.registrar(
        tarea=TAREA_CORRIDA, inicio=bitacora.ahora(),
        resultado=bitacora.RESULTADO_EXITO if ok else RESULTADO_FALLO_PARCIAL,
        filas_leidas=filas, filas_escritas=filas,
        mensaje=_ascii(mensaje), corrida_id=resultado.corrida_id,
        client=client, duracion_seg=resultado.segundos,
    )


# ── Informe por consola ──────────────────────────────────────────────────────

def _cabecera(orden: list[str], corrida: uuid.UUID) -> None:
    print("\n" + "=" * 78)
    print(f"  PIPELINE DWH — {len(orden)} tareas hacia {CH_DATABASE}")
    print(f"  Corrida {corrida}")
    print(f"  Inicio  {bitacora.ahora():%Y-%m-%d %H:%M:%S} (America/Guayaquil)")
    print("=" * 78 + "\n")


def _linea(i: int, total: int, t: ResultadoTarea) -> None:
    if t.resultado == bitacora.RESULTADO_EXITO:
        reintento = f"  (intento {t.intentos})" if t.intentos > 1 else ""
        print(f"  [{i:>2}/{total}] {t.tabla:<28} OK      "
              f"{t.filas:>9,} filas  {t.segundos:>7.2f} s{reintento}")
    elif t.resultado == bitacora.RESULTADO_ABORTADO:
        print(f"  [{i:>2}/{total}] {t.tabla:<28} ABORTADA {t.segundos:>7.2f} s")
        print(f"      {t.mensaje}")
        print("      La tabla publicada NO se modificó.")
    else:
        print(f"  [{i:>2}/{total}] {t.tabla:<28} ERROR   {t.segundos:>7.2f} s")
        print(f"      {t.mensaje}")


def _resumen(r: ResultadoCorrida) -> None:
    exitosas = r.exitosas
    abortadas = r.por_resultado(bitacora.RESULTADO_ABORTADO)
    fallidas = r.por_resultado(bitacora.RESULTADO_FALLO)
    omitidas = r.por_resultado(RESULTADO_OMITIDO)
    filas = sum(t.filas for t in exitosas)

    print("\n" + "=" * 78)
    print("  RESUMEN DE LA CORRIDA")
    print("=" * 78)
    print(f"  Corrida        {r.corrida_id}")
    print(f"  Duración       {r.segundos:.1f} s")
    print(f"  Publicadas     {len(exitosas)} de {len(r.tareas)} tareas · {filas:,} filas")
    if abortadas:
        print(f"  Abortadas      {len(abortadas)} (validación; NO se publicaron): "
              f"{', '.join(t.tabla for t in abortadas)}")
    if fallidas:
        print(f"  Con error      {len(fallidas)}: {', '.join(t.tabla for t in fallidas)}")
    if omitidas:
        print(f"  Omitidas       {len(omitidas)} por dependencia rota: "
              + ", ".join(f"{t.tabla} ← {t.bloqueada_por}" for t in omitidas))
    if r.validacion_ejecutada:
        veredicto = (f"los {len(validar_dwh.CONTROLES)} controles CUADRAN"
                     if r.validacion_ok else "HAY CONTROLES QUE DIFIEREN")
        print(f"  Validación     {veredicto}")

    if r.todo_bien and (not r.validacion_ejecutada or r.validacion_ok):
        print("\n  ESTADO: CORRIDA COMPLETA Y VALIDADA.")
    else:
        print("\n  ESTADO: FALLO PARCIAL — las tablas no publicadas conservan el dato "
              "de la corrida anterior.")
        print("  Ningún informe queda vacío ni a medias; sí queda desactualizado.")
    print("=" * 78 + "\n")


def _codigo_salida(r: ResultadoCorrida) -> int:
    if any(t.resultado != bitacora.RESULTADO_EXITO for t in r.tareas):
        return SALIDA_FALLO_PARCIAL
    if r.validacion_ejecutada and not r.validacion_ok:
        return SALIDA_VALIDACION
    return SALIDA_OK


# ── CLI ──────────────────────────────────────────────────────────────────────

def main(argv=None) -> int:
    # La salida lleva acentos y cajas: sin esto, una consola Windows en cp1252
    # rompe la corrida con UnicodeEncodeError al imprimir el resumen — y el
    # backend, que captura este stdout, recibiría basura.
    for flujo in (sys.stdout, sys.stderr):
        try:
            flujo.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError):
            pass

    parser = argparse.ArgumentParser(
        prog="python -m etl.dwh.run_etl",
        description="Orquesta las 21 cargas del DWH en orden topológico y valida "
                    "el resultado contra PostgreSQL.",
    )
    parser.add_argument("--tablas", metavar="A,B,C",
                        help="subconjunto de tareas (por defecto, las 21)")
    parser.add_argument("--corrida-id", metavar="UUID",
                        help="id de corrida impuesto (lo usa la aplicación para "
                             "poder consultar el progreso desde el primer instante)")
    parser.add_argument("--intentos", type=int, default=INTENTOS_POR_DEFECTO,
                        help=f"intentos por tarea (defecto {INTENTOS_POR_DEFECTO})")
    parser.add_argument("--espera", type=float, default=ESPERA_BASE_SEG,
                        help=f"segundos antes del primer reintento, se duplica "
                             f"(defecto {ESPERA_BASE_SEG:.0f})")
    parser.add_argument("--sin-validacion", action="store_true",
                        help="no ejecutar los controles finales")
    parser.add_argument("--orden", action="store_true",
                        help="imprime el orden topológico resuelto y termina")
    args = parser.parse_args(argv)

    seleccion = None
    if args.tablas:
        seleccion = [t.strip() for t in args.tablas.split(",") if t.strip()]

    try:
        orden = orden_topologico(seleccion)
    except GrafoInvalido as e:
        print(f"[ERROR] {e}")
        return SALIDA_ERROR_ARRANQUE

    if args.orden:
        deps = dependencias()
        print(f"\nOrden topológico ({len(orden)} tareas):\n")
        for i, n in enumerate(orden, start=1):
            detalle = f"  ← {', '.join(deps[n])}" if deps[n] else ""
            print(f"  {i:>2}. {n}{detalle}")
        print()
        return SALIDA_OK

    corrida = None
    if args.corrida_id:
        try:
            corrida = uuid.UUID(args.corrida_id)
        except ValueError:
            print(f"[ERROR] --corrida-id no es un UUID válido: {args.corrida_id}")
            return SALIDA_ERROR_ARRANQUE

    if args.intentos < 1:
        print("[ERROR] --intentos debe ser al menos 1.")
        return SALIDA_ERROR_ARRANQUE

    try:
        resultado = ejecutar(
            seleccion=seleccion, corrida_id=corrida, intentos=args.intentos,
            espera=args.espera, con_validacion=not args.sin_validacion,
        )
    except Exception as e:                            # noqa: BLE001 - se reporta
        # Aquí solo caen los fallos de ARRANQUE (ClickHouse inalcanzable al abrir
        # el cliente): el fallo de una tarea ya lo absorbe `_ejecutar_tarea`.
        logger.exception("La corrida no pudo arrancar")
        print(f"\n[ERROR] La corrida no pudo arrancar: {type(e).__name__}: {e}")
        print("        Comprueba con: python -m etl.dwh.cargar --verificar")
        return SALIDA_ERROR_ARRANQUE

    _resumen(resultado)
    return _codigo_salida(resultado)


if __name__ == "__main__":
    sys.exit(main())
