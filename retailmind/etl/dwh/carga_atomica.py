"""
etl/dwh/carga_atomica.py
El patrón de carga del §6.2 del diseño, idéntico para las 21 tablas:

    1. CREATE TABLE retailmind_dwh.fact_x_new   (misma definición, vacía)
    2. INSERT INTO fact_x_new  ← lotes desde PostgreSQL (o desde el propio DWH
       en las tablas derivadas)
    3. VALIDAR conteos y sumas de control contra el origen
         → si falla, se ABORTA y NO se publica
    4. EXCHANGE TABLES fact_x AND fact_x_new    (atómico, instantáneo)
    5. DROP TABLE fact_x_new

Dos propiedades que este patrón compra, y que son la razón de que exista:

  * **Ningún informe ve jamás una tabla vacía ni a medio llenar.** `EXCHANGE
    TABLES` es atómico en el motor de base `Atomic`. Si el paso 3 falla, la
    tabla vieja sigue publicada y el informe muestra el dato de ayer:
    degradación correcta, no pantalla rota.
  * **Es la red de seguridad contra el riesgo de §8.1.** Una carga de 0 filas
    donde se esperaban 4.083 aborta y no publica. Aunque el rol de lectura
    quedara mal configurado y RLS filtrara en silencio, el informe seguiría
    mostrando el dato de ayer en vez de quedarse en blanco.

Correr esto dos veces seguidas deja exactamente el mismo estado: el pipeline es
idempotente y se puede reintentar sin pensar.
"""

from dataclasses import dataclass, field

from etl.dwh.conexiones import CH_DATABASE, logger

BATCH_SIZE = 50_000   # el universo entero del DWH (~70.000 filas) cabe en dos lotes


class ValidacionFallida(RuntimeError):
    """
    Las cifras de control no cuadran. La tabla NO se publica y la anterior
    sigue en su sitio.
    """


@dataclass
class ResultadoCarga:
    tabla: str
    filas_leidas: int = 0
    filas_escritas: int = 0
    excepciones: int = 0
    publicada: bool = False
    controles: dict = field(default_factory=dict)
    mensaje: str = ""


# ── Utilidades de catálogo ───────────────────────────────────────────────────

def tabla_existe(client, tabla: str, db: str = CH_DATABASE) -> bool:
    filas = client.query(
        "SELECT count() FROM system.tables WHERE database = %(db)s AND name = %(t)s",
        parameters={"db": db, "t": tabla},
    ).result_rows
    return filas[0][0] > 0


def contar(client, tabla: str, db: str = CH_DATABASE) -> int:
    return client.query(f"SELECT count() FROM {db}.{tabla}").result_rows[0][0]


# ── Validaciones estándar ────────────────────────────────────────────────────

def validar_conteo(escritas: int, controles: dict) -> list[str]:
    """
    Validación mínima obligatoria de TODA tarea, y la que ataca el §8.1.

    `controles['filas']` es el conteo tomado del ORIGEN en la misma corrida, no
    una constante escrita a mano: si el negocio crece, el control crece con él.
    """
    errores = []
    esperadas = controles.get("filas")

    if esperadas is None:
        errores.append("La tarea no declaró la cifra de control 'filas' del origen.")
        return errores

    if esperadas > 0 and escritas == 0:
        errores.append(
            f"Se escribieron 0 filas donde el origen tiene {esperadas:,}. "
            f"Síntoma clásico de RLS filtrando en silencio (§8.1): revisa que la "
            f"conexión use el rol 'retailmind_etl' con BYPASSRLS."
        )
    elif escritas != esperadas:
        errores.append(
            f"Conteo distinto: origen {esperadas:,} vs destino {escritas:,} "
            f"(diferencia {escritas - esperadas:+,})."
        )
    return errores


# ── El patrón, en una función ────────────────────────────────────────────────

def cargar_atomico(client, tarea, db: str = CH_DATABASE) -> ResultadoCarga:
    """
    Ejecuta los 5 pasos del §6.2 para una `TareaCarga`.

    `tarea` debe cumplir el contrato de `etl.dwh.tarea.TareaCarga`.
    """
    destino = tarea.nombre
    staging = f"{destino}_new"
    resultado = ResultadoCarga(tabla=destino)

    # ── 1) Tabla de staging limpia ───────────────────────────────────────────
    # El DROP previo barre los restos de una corrida abortada: sin él, un
    # reintento insertaría encima de lo anterior y duplicaría.
    client.command(f"DROP TABLE IF EXISTS {db}.{staging}")
    client.command(tarea.ddl(f"{db}.{staging}"))
    logger.info(f"[{destino}] staging '{staging}' creada")

    try:
        # ── 2) Llenado del staging ───────────────────────────────────────────
        # `cargar_en` es lotes desde PostgreSQL en una TareaCarga, y un
        # INSERT … SELECT dentro del propio DWH en una TareaDerivada.
        resultado.filas_escritas = tarea.cargar_en(client, f"{db}.{staging}")
        resultado.excepciones = tarea.excepciones

        # ── 3) Validación contra el origen — el paso que puede abortar ────────
        controles = tarea.controles()
        resultado.controles = controles
        resultado.filas_leidas = controles.get("filas", resultado.filas_escritas)

        errores = validar_conteo(resultado.filas_escritas, controles)
        errores += tarea.validar(client, f"{db}.{staging}", controles)

        if errores:
            raise ValidacionFallida(" | ".join(errores))

        # ── 4) Publicación atómica ───────────────────────────────────────────
        if tabla_existe(client, destino, db):
            client.command(f"EXCHANGE TABLES {db}.{destino} AND {db}.{staging}")
            logger.info(f"[{destino}] EXCHANGE TABLES — publicada sin ventana de vacío")
        else:
            # Primera carga: no hay nada con qué intercambiar.
            client.command(f"RENAME TABLE {db}.{staging} TO {db}.{destino}")
            logger.info(f"[{destino}] primera carga — RENAME TABLE")

        resultado.publicada = True
        resultado.mensaje = (
            f"{resultado.filas_escritas:,} filas publicadas"
            + (f" · {resultado.excepciones} excepciones registradas"
               if resultado.excepciones else "")
        )
        return resultado

    finally:
        # ── 5) Limpieza ──────────────────────────────────────────────────────
        # Tras el EXCHANGE, `staging` contiene la tabla VIEJA: se descarta.
        # Si hubo aborto, contiene la carga fallida: también se descarta, y la
        # tabla publicada nunca se tocó.
        client.command(f"DROP TABLE IF EXISTS {db}.{staging}")
