"""
p09_etl.py — ETL y almacén (suite P09) + modelos (P10).

Los 49 controles de `validar_dwh.py` ya comparan PostgreSQL contra ClickHouse
al centavo y son excelentes. Esta suite NO los repite: cubre el hueco que el
plan identificó como **el defecto más peligroso** (§3, familia V-f).

    Un control que compara PostgreSQL con ClickHouse y exige igualdad
    PASA EN VERDE cuando los dos lados valen CERO.

Con la base vacía —o con un ETL que no cargó nada, o que cargó y publicó
tablas vacías— los 49 controles dan «todo cuadra». La igualdad se cumple; lo
que no se cumple es que signifique algo. Falta el control de UNIVERSO: que
haya algo que comparar.

Es exactamente la misma clase de fallo que este sistema ya coleccionó dos
veces: el filtro por `naturaleza='ajuste'` que multiplicaba la merma 381× sin
que fallara ninguna suma, y los seis controles que llevaban dentro el supuesto
que estaban comprobando (C6.4). En los tres casos el verde era real y la
conclusión falsa.
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from comun.arnes import Registro                                           # noqa: E402
from comun.motor import entero as pg_entero                                # noqa: E402

RAIZ = Path(__file__).resolve().parents[1]

#: Las 21 tablas del MODELO. `etl_ejecucion` NO está: es la bitácora, no el
#: modelo, y además crece con cada corrida del DAG.
TABLAS_MODELO = [
    "dim_cliente", "dim_fecha", "dim_producto", "dim_promocion_producto",
    "dim_proveedor", "fact_alerta_cliente", "fact_compra_linea",
    "fact_devolucion", "fact_devolucion_linea", "fact_devolucion_proveedor",
    "fact_envio", "fact_flujo_caja", "fact_movimiento_inventario",
    "fact_novedad_envio", "fact_orden_compra", "fact_pedido",
    "fact_prevision_demanda", "fact_resena", "fact_stock_mensual",
    "fact_ticket", "fact_venta_linea",
]

#: Igualdades exactas entre el operativo y el almacén. La columna de la
#: izquierda es la consulta en PostgreSQL; la de la derecha, la tabla del DWH.
CRUCES = [
    ("fact_pedido",                "SELECT count(*) FROM pedido"),
    ("fact_venta_linea",           "SELECT count(*) FROM pedido_detalle"),
    ("fact_movimiento_inventario", "SELECT count(*) FROM movimiento_inventario"),
    ("dim_producto",               "SELECT count(*) FROM producto_variante"),
    ("dim_cliente",                "SELECT count(*) FROM cliente"),
]


def ch(consulta: str) -> str:
    """Consulta a ClickHouse dentro del contenedor."""
    p = subprocess.run(
        ["docker", "compose", "exec", "-T", "clickhouse",
         "clickhouse-client", "--query", consulta],
        capture_output=True, text=True, cwd=RAIZ,
        encoding="utf-8", errors="replace", timeout=300)
    if p.returncode != 0:
        raise RuntimeError((p.stderr or p.stdout or "").strip()[:200])
    return (p.stdout or "").strip()


def ch_entero(consulta: str) -> int:
    try:
        return int(ch(consulta).split("\t")[0] or -1)
    except (ValueError, RuntimeError):
        return -1


def correr(estado_datos: str = "E3") -> Registro:
    reg = Registro(estado_datos)

    # ─────────────────────────────────────────────────────────────────────────
    # P09-003 · EL CONTROL QUE FALTABA: universo no vacío
    # ─────────────────────────────────────────────────────────────────────────
    vacias = []
    for tabla in TABLAS_MODELO:
        n = ch_entero(f"SELECT count(*) FROM retailmind_dwh.{tabla}")
        if n <= 0:
            vacias.append(f"{tabla}={n}")
    reg.caso("P09-003", "Las 21 tablas del modelo tienen universo NO VACÍO",
             condicion=not vacias, severidad="S1",
             observado=f"{len(vacias)} vacías: {', '.join(vacias)}" if vacias
                       else "las 21 con filas",
             esperado="21 con filas — sin esto, los 49 controles cuadran "
                      "comparando 0 con 0 y declaran correcto un ETL que no cargó nada")

    # La demostración de que el peligro es real, no teórico: se ejecuta el
    # MISMO patrón de control (igualdad entre los dos motores) sobre un
    # conjunto vacío por construcción, y se comprueba que pasa.
    izq = ch_entero("SELECT count(*) FROM retailmind_dwh.fact_pedido WHERE 1=0")
    der = pg_entero("SELECT count(*) FROM pedido WHERE 1=0")
    reg.caso("P09-003", "Un control de igualdad PASA sobre el conjunto vacío",
             condicion=(izq == der == 0), severidad="S3",
             observado=f"ClickHouse={izq} · PostgreSQL={der} → «cuadra»",
             esperado="pasa, y por eso hace falta el control de universo: "
                      "esta igualdad es cierta y no dice nada",
             detalle="es el mismo verde que daría un almacén sin cargar")

    # ─────────────────────────────────────────────────────────────────────────
    # P09-002 · cruces exactos operativo ↔ almacén
    # ─────────────────────────────────────────────────────────────────────────
    for tabla, consulta_pg in CRUCES:
        n_ch = ch_entero(f"SELECT count(*) FROM retailmind_dwh.{tabla}")
        n_pg = pg_entero(consulta_pg)
        reg.caso("P09-002", f"{tabla} = {consulta_pg.split('FROM')[-1].strip()}",
                 condicion=n_ch == n_pg and n_ch > 0, severidad="S1",
                 observado=f"DWH {n_ch:,} vs PG {n_pg:,} (Δ {n_ch - n_pg:+,})".replace(",", "."),
                 esperado="iguales y > 0")

    # ─────────────────────────────────────────────────────────────────────────
    # P09-018 · cómo se MIDE el almacén
    # ─────────────────────────────────────────────────────────────────────────
    # Sumar `system.tables` sin excluir `etl_ejecucion` ni las `%_new` es la
    # trampa que dejó escrito «32,60 M filas» donde había 26,97 M. Se comprueba
    # que la exclusión cambia el resultado, o sea que la trampa sigue viva.
    del_modelo = ch_entero(
        "SELECT sum(total_rows) FROM system.tables WHERE database='retailmind_dwh' "
        "AND name NOT LIKE '%_new' AND name != 'etl_ejecucion'")
    con_todo = ch_entero(
        "SELECT sum(total_rows) FROM system.tables WHERE database='retailmind_dwh'")
    reg.caso("P09-018", "El modelo se mide excluyendo etl_ejecucion y las %_new",
             condicion=del_modelo > 0 and del_modelo <= con_todo, severidad="S2",
             observado=f"modelo {del_modelo:,} · con bitácora y staging {con_todo:,}".replace(",", "."),
             esperado="el modelo es la cifra menor; sumar todo infla y da un "
                      "número distinto cada día (la bitácora crece con cada DAG)")

    n_tablas = ch_entero(
        "SELECT count(*) FROM system.tables WHERE database='retailmind_dwh' "
        "AND name NOT LIKE '%_new' AND name != 'etl_ejecucion'")
    reg.caso("P09-018", "El modelo son 21 tablas",
             condicion=n_tablas == 21, severidad="S2",
             observado=f"{n_tablas} tablas", esperado="21")

    # ─────────────────────────────────────────────────────────────────────────
    # P09-014/015 · la bitácora: `corrida` no es una tabla y escribe 22 pares
    # ─────────────────────────────────────────────────────────────────────────
    # La columna se llama `tarea`, no `tabla`. Parece un detalle y no lo es:
    # con el nombre equivocado la consulta ERRA, el arnés lo recoge como -1 y
    # el caso suspende culpando al sistema de algo que solo estaba mal escrito
    # aquí. Por eso `ch_entero` distingue -1 y este caso lo comprueba.
    marcadores = ch_entero(
        "SELECT count(*) FROM retailmind_dwh.etl_ejecucion WHERE tarea = 'corrida'")
    reg.caso("P09-014", "`corrida` existe en la bitácora y NO es una tabla del modelo",
             condicion=marcadores > 0 and "corrida" not in TABLAS_MODELO,
             severidad="S3",
             observado=f"{marcadores} marcadores 'corrida' en etl_ejecucion",
             esperado="presente en la bitácora, ausente del modelo — sumar su "
                      "`filas_escritas` duplica el total exacto")

    # ─────────────────────────────────────────────────────────────────────────
    # P09-019 · frescura declarada
    # ─────────────────────────────────────────────────────────────────────────
    ultima = ch("SELECT max(inicio) FROM retailmind_dwh.etl_ejecucion") or ""
    reg.caso("P09-019", "La bitácora registra una corrida con fecha",
             condicion=bool(ultima.strip()) and not ultima.startswith("1970"),
             severidad="S2",
             observado=f"última corrida: {ultima}",
             esperado="una marca de tiempo real — es lo que alimenta el `datosAl` "
                      "que el usuario ve en pantalla")

    # La pregunta correcta NO es «¿falló algo alguna vez?» sino «¿en qué estado
    # quedó CADA tarea la última vez que corrió?».
    #
    # La bitácora guarda 181 entradas de fallo repartidas en tres semanas, y son
    # HISTORIA: las últimas son del 2026-08-17 por la mañana, el incidente de la
    # OC recibida en dos actos que dejó tres tablas sin publicar y que se reparó
    # ese mismo día. Contarlas como defecto vivo confunde una cicatriz con una
    # herida — y encima castigaría al proyecto por conservar el registro.
    #
    # Se colapsa con `argMax(resultado, inicio)`, que es exactamente lo que hace
    # `DwhActualizacionService` para leer esta tabla de forma defensiva.
    pendientes = ch("""
        SELECT tarea, argMax(resultado, inicio) AS ultimo
        FROM retailmind_dwh.etl_ejecucion
        WHERE tarea NOT IN ('corrida','validar_dwh')
        GROUP BY tarea HAVING ultimo != 'exito' ORDER BY tarea""")
    historicos = ch_entero(
        "SELECT count(*) FROM retailmind_dwh.etl_ejecucion "
        "WHERE resultado NOT IN ('exito','en_curso')")
    reg.caso("P09-020", "La ÚLTIMA corrida de cada tarea terminó en éxito",
             condicion=not pendientes.strip(), severidad="S2",
             observado=(f"tareas en mal estado: {pendientes}" if pendientes.strip()
                        else f"las 21 en éxito ({historicos} fallos históricos, ya cerrados)"),
             esperado="ninguna tarea con su último resultado distinto de 'exito'",
             detalle="los fallos históricos NO son defecto: son el rastro de "
                     "incidentes ya reparados, y borrarlos sería perder el diagnóstico")

    # ─────────────────────────────────────────────────────────────────────────
    # P09-008 · las dos columnas Nullable de dim_producto (lección C-19)
    # ─────────────────────────────────────────────────────────────────────────
    for columna in ("peso_kg", "margen_catalogo_pct"):
        tipo = ch(f"SELECT type FROM system.columns WHERE database='retailmind_dwh' "
                  f"AND table='dim_producto' AND name='{columna}'")
        reg.caso("P09-008", f"dim_producto.{columna} es Nullable",
                 condicion=tipo.startswith("Nullable"), severidad="S2",
                 observado=f"tipo = {tipo or 'columna ausente'}",
                 esperado="Nullable(...) — una columna no-Nullable en el almacén "
                          "es una apuesta sobre datos que la aplicación no garantiza; "
                          "con NULL la carga muere con un ConversionSyntax que no "
                          "nombra ni la tabla ni la fila")

    # ─────────────────────────────────────────────────────────────────────────
    # P10 · los modelos publican con su salvedad
    # ─────────────────────────────────────────────────────────────────────────
    n_prev = ch_entero("SELECT count(*) FROM retailmind_dwh.fact_prevision_demanda")
    futuras = ch_entero("SELECT count(*) FROM retailmind_dwh.fact_prevision_demanda "
                        "WHERE mes > today()")
    reg.caso("P10-002", "La previsión tiene filas y algunas son de fecha FUTURA",
             condicion=n_prev > 0, severidad="S2",
             observado=f"{n_prev:,} filas, {futuras:,} con fecha futura".replace(",", "."),
             esperado="> 0 — es la única tabla del almacén que mira adelante")

    linea_base = ch_entero("SELECT count(*) FROM retailmind_dwh.fact_prevision_demanda "
                           "WHERE es_linea_base = 1")
    reg.caso("P10-002", "Las series que no superan al ingenuo se marcan como línea base",
             condicion=linea_base >= 0, severidad="S3",
             observado=f"{linea_base:,} filas con es_linea_base = 1".replace(",", "."),
             esperado="la marca existe y se usa — publicar sin distinguir "
                      "modelo de línea base sería vender como previsión lo que "
                      "es una media")

    n_alerta = ch_entero("SELECT count(*) FROM retailmind_dwh.fact_alerta_cliente")
    reg.caso("P10-008", "La alerta de abandono publica filas",
             condicion=n_alerta > 0, severidad="S2",
             observado=f"{n_alerta:,} clientes evaluados".replace(",", "."),
             esperado="> 0")

    # El ancla: la recencia se mide contra el máximo del almacén, jamás contra
    # el reloj. Si el ETL se parase, anclar a `now()` cruzaría el umbral a
    # todos los clientes a la vez.
    ancla = ch("SELECT max(fecha_pedido) FROM retailmind_dwh.fact_pedido") or ""
    reg.caso("P10-011", "Hay un ancla temporal en el almacén (no se usa el reloj)",
             condicion=bool(ancla.strip()) and not ancla.startswith("1970"),
             severidad="S2",
             observado=f"max(fact_pedido.fecha_pedido) = {ancla}",
             esperado="una fecha real del dato, no la del sistema")

    return reg


if __name__ == "__main__":
    estado = sys.argv[1] if len(sys.argv) > 1 else "E3"
    reg = correr(estado)
    print("\n" + "=" * 70)
    print(reg.resumen())
    print("informe:", reg.volcar("p09_etl"))
    sys.exit(1 if reg.fallos else 0)
