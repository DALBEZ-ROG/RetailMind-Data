"""Matriz rol × endpoint, recorte del VENDEDOR y cifras de la ALERTA DE ABANDONO.

Fase E3 del nivel estratégico (§5.2 de
`docs/estrategico/DISENO_NIVEL_ESTRATEGICO.md`). Hace CUATRO cosas que ni el
pipeline ni `validar_dwh.py` pueden hacer por su cuenta:

1. **La matriz de permisos.** El endpoint lleva monto (facturación 12m y valor
   en riesgo) y ClickHouse no tiene privilegio por columna: el corte lo hace
   ÍNTEGRAMENTE la ruta. No hay barrera de motor a la que preguntarle, así que
   se prueba por API o no se prueba.

2. **El recorte del VENDEDOR a su cartera**, que es la parte que puede fallar
   ABIERTO —enseñando clientes ajenos— sin que nada dé error. Se comprueba
   contra PostgreSQL cliente por cliente: cada uno de los que el vendedor ve
   tiene que tener al menos un pedido suyo dentro de la ventana, y los clientes
   que el ADMIN ve y él no, ninguno.

3. **Las cifras, tomadas de la RESPUESTA HTTP.** Misma disciplina que
   `validar_tableros.py` y `matriz_prevision.py`: no se consulta ClickHouse y se
   compara con PostgreSQL, se consulta **lo que la pantalla recibe**. Una
   consulta correcta sobre una tabla correcta puede llegar mal a la pantalla —un
   filtro que no se aplica, un sobre que pierde un campo— y eso solo se ve aquí.

4. **Las cinco reglas de presentación y las cinco limitaciones de §5.2.9-10**,
   que son requisitos del entregable y no adornos. En particular la regla 4:
   **el lift y su muestra tienen que estar en la CABECERA**, antes que cualquier
   cifra de negocio. Se exige que los TRES primeros KPI del resumen sean el
   veredicto del modelo; si alguien los reordenara «para que se vea mejor el
   dinero», este script falla.

## La ventana horaria, y por qué este script la toca

`fuera_horario` BLOQUEA el login (script 53) y los horarios de `grupo_horario`
son por día de la semana: según el día y la hora, VENDEDOR o GERENTE no podrían
entrar y el endpoint les respondería 403 — un 403 correcto por el motivo
equivocado. Se ensancha la ventana del día en curso, se corre la matriz y se
**restaura el estado exacto verificándolo** en un `finally`.

Uso:
    py matriz_alerta_cliente.py
"""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime

import psycopg2

API = os.environ.get("RETAILMIND_API", "http://localhost:8080")

#: Superusuario: ensanchar un horario es una ESCRITURA y `retailmind_etl` es de
#: solo lectura por construcción.
PG_ADMIN = dict(host="localhost", port=5432, dbname="retailmind",
                user="postgres", password="1250143656")

#: Rol de SOLO LECTURA para las cifras de control: con BYPASSRLS, para que
#: `pol_horario` no filtre en silencio y devuelva cero filas sin dar error.
PG_LECTURA = dict(host="localhost", port=5432, dbname="retailmind",
                  user="retailmind_etl", password="Etl2026!")

CREDENCIALES = {
    "ADMIN":    ("admin@retailmind.com",    "Admin2026!"),
    "GERENTE":  ("gerente@retailmind.com",  "Retail2026!"),
    "VENDEDOR": ("vendedor@retailmind.com", "Retail2026!"),
    "ANALISTA": ("analista@retailmind.com", "Retail2026!"),
    "COMPRAS":  ("compras@retailmind.com",  "Retail2026!"),
    "SOPORTE":  ("soporte@retailmind.com",  "Retail2026!"),
    "BODEGA":   ("bodega@retailmind.com",   "Retail2026!"),
    "DESPACHO": ("despacho@retailmind.com", "Retail2026!"),
}

RUTA = "ventas/clientes-en-riesgo"

#: Lo que DEBE pasar: True = 200, False = 403.
#:
#: BODEGA y DESPACHO fuera porque el informe lleva DINERO. El ANALISTA también,
#: y no por el dinero: esto no es una lectura de análisis, es una lista de
#: personas a las que hay que llamar, y §5.2.8 la reserva a quien ejecuta el
#: gesto comercial y a quien lo dirige.
ESPERADO = {
    "ADMIN": True, "GERENTE": True, "VENDEDOR": True,
    "ANALISTA": False, "COMPRAS": False, "SOPORTE": False,
    "BODEGA": False, "DESPACHO": False,
}

#: Parámetros del modelo, escritos AQUÍ y no importados: este script debe poder
#: contradecir al ETL, y compartir la constante sería una tautología.
MESES_VENTANA = 7
MIN_PEDIDOS = 3
ALPHA = 0.05
UMBRAL_ATENCION = 0.10


# ── Ventana horaria ──────────────────────────────────────────────────────────

_LEER = ("SELECT id, hora_inicio::text, hora_fin::text, activo "
         "FROM grupo_horario WHERE {} ORDER BY id")


def ensanchar_horario(cur) -> list[tuple]:
    dia = (datetime.now().weekday() + 1) % 7      # lunes=0 en Python, domingo=0 en la BD
    cur.execute(_LEER.format("dia_semana = %s"), (dia,))
    previo = cur.fetchall()
    cur.execute("UPDATE grupo_horario SET hora_inicio = '00:00', hora_fin = '23:59', "
                "activo = true WHERE dia_semana = %s", (dia,))
    print(f"Ventana del día {dia} ensanchada en {len(previo)} filas "
          f"(se restaurará al terminar).")
    return previo


def restaurar_horario(cur, previo: list[tuple]) -> bool:
    for ident, inicio, fin, activo in previo:
        cur.execute("UPDATE grupo_horario SET hora_inicio = %s::time, "
                    "hora_fin = %s::time, activo = %s WHERE id = %s",
                    (inicio, fin, activo, ident))
    cur.execute(_LEER.format("id = ANY(%s)"), ([p[0] for p in previo],))
    return cur.fetchall() == previo


# ── API ──────────────────────────────────────────────────────────────────────

def token(usuario: str, clave: str) -> str | None:
    datos = json.dumps({"username": usuario, "password": clave}).encode()
    pet = urllib.request.Request(f"{API}/api/auth/login", data=datos,
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(pet, timeout=30) as r:
            return json.load(r)["token"]
    except urllib.error.HTTPError:
        return None


def pedir(jwt: str, **filtros) -> tuple[int, dict]:
    consulta = urllib.parse.urlencode(
        {k: v for k, v in filtros.items() if v not in (None, "")})
    url = f"{API}/api/informes/{RUTA}" + (f"?{consulta}" if consulta else "")
    pet = urllib.request.Request(url, headers={"Authorization": f"Bearer {jwt}"})
    try:
        with urllib.request.urlopen(pet, timeout=180) as r:
            return r.status, json.load(r)
    except urllib.error.HTTPError as e:
        return e.code, {}


# ── Cifras de control desde PostgreSQL ───────────────────────────────────────

_VENTA = """
    WITH venta AS (
        SELECT p.cliente_id AS cid,
               (p.fecha_pedido AT TIME ZONE 'America/Guayaquil')::date AS dia,
               p.vendedor_id
        FROM pedido p
        JOIN estado_pedido ep ON ep.id = p.estado_pedido_id
        WHERE ep.codigo <> 'cancelado' AND p.cliente_id IS NOT NULL
    ),
    ancla AS (SELECT max(dia) AS d FROM venta),
    ventana AS (
        SELECT (date_trunc('month', (SELECT d FROM ancla))
                - interval '%s months')::date AS ini
    )
""" % (MESES_VENTANA - 1)


def control() -> dict:
    """El reparto por nivel, recalculado en PostgreSQL desde cero."""
    cx = psycopg2.connect(**PG_LECTURA)
    try:
        with cx.cursor() as cur:
            cur.execute(_VENTA + """
                , agg AS (
                    SELECT cid,
                           count(*) FILTER (WHERE dia >= (SELECT ini FROM ventana)) AS n,
                           min(dia) FILTER (WHERE dia >= (SELECT ini FROM ventana)) AS pri,
                           max(dia) AS ult
                    FROM venta GROUP BY cid
                ),
                prob AS (
                    SELECT cid, n,
                           (SELECT d FROM ancla) - ult AS silencio,
                           CASE WHEN n >= %s THEN
                               exp(- (n::float8 / (((SELECT d FROM ancla) - pri) + 1))
                                   * ((SELECT d FROM ancla) - ult))
                           END AS p
                    FROM agg
                )
                SELECT count(*),
                       count(*) FILTER (WHERE p IS NOT NULL AND p < %s),
                       count(*) FILTER (WHERE p IS NOT NULL AND p >= %s AND p < %s),
                       count(*) FILTER (WHERE p IS NULL),
                       count(*) FILTER (WHERE p IS NOT NULL AND p < %s),
                       (SELECT to_char(d, 'DD/MM/YYYY') FROM ancla),
                       (SELECT to_char(ini, 'DD/MM/YYYY') FROM ventana)
                FROM prob
            """, (MIN_PEDIDOS, ALPHA, ALPHA, UMBRAL_ATENCION, UMBRAL_ATENCION))
            (clientes, criticas, atencion, sin_muestra, en_alerta,
             ancla, ventana) = cur.fetchone()
        return {"clientes": clientes, "criticas": criticas, "atencion": atencion,
                "sin_muestra": sin_muestra, "en_alerta": en_alerta,
                "ancla": ancla, "ventana": ventana}
    finally:
        cx.close()


def cartera_de(email_vendedor: str) -> set[str]:
    """
    Clientes que ese vendedor atendió DENTRO de la ventana, según PostgreSQL.

    Es la definición contra la que se contrasta el recorte: el almacén no guarda
    `vendedor_id` y casa por nombre, así que aquí se parte del id —que es lo que
    el JWT identifica— y se compara el conjunto resultante.
    """
    cx = psycopg2.connect(**PG_LECTURA)
    try:
        with cx.cursor() as cur:
            cur.execute(_VENTA + """
                SELECT DISTINCT c.nombre || ' ' || COALESCE(c.apellido, '')
                FROM venta v
                JOIN cliente c  ON c.id = v.cid
                JOIN usuario u  ON u.id = v.vendedor_id
                WHERE u.email = %s AND v.dia >= (SELECT ini FROM ventana)
            """, (email_vendedor,))
            return {f[0].strip() for f in cur.fetchall()}
    finally:
        cx.close()


# ── Comprobaciones sobre la RESPUESTA ────────────────────────────────────────

def comprobar_contenido(cuerpo: dict, ctrl: dict) -> list[str]:
    fallos = []

    def exigir(cond, mensaje):
        if not cond:
            fallos.append(mensaje)

    exigir(cuerpo.get("analiticaDisponible") is True,
           "respondió DEGRADADO — la matriz no probaría nada sobre permisos")
    exigir(bool(cuerpo.get("datosAl")), "sin marca de agua `datosAl`")

    resumen = cuerpo.get("resumen", [])
    exigir(len(resumen) >= 3, "el resumen no llega a tres tarjetas")

    # ── REGLA 4 — el lift y su muestra, en la CABECERA ────────────────────
    if len(resumen) >= 3:
        etiquetas = [k["etiqueta"].lower() for k in resumen[:3]]
        exigir("lift" in etiquetas[0],
               f"la PRIMERA tarjeta no es el lift, es «{resumen[0]['etiqueta']}». "
               f"Regla 4 de §5.2.9: el lift va antes que cualquier cifra de negocio")
        exigir("casos positivos" in str(resumen[1]["valor"]),
               "la segunda tarjeta no declara los casos positivos del backtest")
        exigir("azar" in etiquetas[2],
               "la tercera tarjeta no dice si el modelo supera al azar")
        # Y ninguna de las tres puede ser dinero: el veredicto va antes.
        exigir(all(k["tipo"] != "moneda" for k in resumen[:3]),
               "hay una cifra de DINERO entre las tres primeras tarjetas")

    # ── REGLA 5 — la fecha ancla, en el TÍTULO ────────────────────────────
    sufijo = cuerpo.get("sufijoTitulo", "")
    exigir(ctrl["ancla"] in sufijo,
           f"el título no lleva la fecha ancla {ctrl['ancla']} (trae «{sufijo}»)")

    # ── LAS CINCO LIMITACIONES DE §5.2.10 ─────────────────────────────────
    salvedad = cuerpo.get("salvedad", "").lower()
    for clave, texto in (
        ("alerta de silencio", "no dice que sea una alerta de silencio y no una predicción"),
        ("100 %", "no explica la rampa de cartera (el 100 % de un solo comprador)"),
        ("azar", "no declara que en la validación no supera al azar"),
        ("almacén", "no dice que la recencia se mide contra el almacén y no contra hoy"),
        ("sin muestra", "no nombra a los clientes sin muestra suficiente"),
    ):
        exigir(clave in salvedad, f"salvedad incompleta: {texto}")

    return fallos


def comprobar_filas(items: list[dict]) -> list[str]:
    """Reglas 1, 2 y 3 sobre las filas que la pantalla recibió."""
    fallos = []
    if not items:
        return ["sin filas"]

    # Regla 1 — la medida principal es «veces su intervalo propio», y viaja con
    # las dos columnas que la hacen derivable.
    faltan = [i for i in items if "silencio_en_intervalos" not in i
              or "intervalo_medio_dias" not in i or "dias_silencio" not in i]
    if faltan:
        fallos.append(f"{len(faltan)} filas sin la medida en intervalos propios "
                      f"o sin las columnas que la sostienen (regla 1)")
    incoherentes = [
        i for i in items
        if int(i["pedidos_ventana"]) >= MIN_PEDIDOS
        and abs(float(i["silencio_en_intervalos"])
                - float(i["dias_silencio"]) / float(i["intervalo_medio_dias"])) > 0.05
    ]
    if incoherentes:
        fallos.append(f"{len(incoherentes)} filas donde «veces su ritmo» no es "
                      f"días ÷ intervalo medio")

    # Regla 2 — el sparkline EN LA FILA.
    sin_spark = [i for i in items
                 if not isinstance(i.get("compras_por_mes"), list)
                 or not i["compras_por_mes"]]
    if sin_spark:
        fallos.append(f"{len(sin_spark)} filas sin sparkline (regla 2)")

    # Regla 3 — la lista viene ORDENADA por valor en riesgo.
    valores = [float(i["valor_en_riesgo"]) for i in items]
    if valores != sorted(valores, reverse=True):
        fallos.append("la lista NO viene ordenada por valor en riesgo (regla 3)")

    # La MUESTRA en la fila (limitación 5 de §5.2.10).
    if any("pedidos_ventana" not in i for i in items):
        fallos.append("falta `pedidos_ventana`: la muestra no viaja en la fila")

    # El contexto que NO es variable del modelo, pero sí tiene que verse.
    if any("reclamos_abiertos" not in i or "devoluciones_12m" not in i for i in items):
        fallos.append("falta el contexto (reclamos abiertos / devoluciones)")
    return fallos


# ── Programa ─────────────────────────────────────────────────────────────────

def main() -> int:
    print("=" * 78)
    print("  ALERTA DE ABANDONO (OTD-VEN-19) · matriz de permisos, recorte y cifras")
    print(f"  API {API}")
    print("=" * 78)

    ctrl = control()
    print(f"\nControl desde PostgreSQL: {ctrl['clientes']} clientes con compra · "
          f"ancla {ctrl['ancla']} · ventana desde {ctrl['ventana']}")
    print(f"  criticas {ctrl['criticas']} · atencion {ctrl['atencion']} · "
          f"en alerta {ctrl['en_alerta']} · sin muestra {ctrl['sin_muestra']}")

    admin = psycopg2.connect(**PG_ADMIN)
    admin.autocommit = False
    fallos: list[str] = []
    celdas = ok = 0
    try:
        with admin.cursor() as cur:
            previo = ensanchar_horario(cur)
        admin.commit()

        # ── 1) Matriz de permisos ────────────────────────────────────────
        print("\n── MATRIZ ROL × ENDPOINT " + "─" * 52)
        cuerpos: dict[str, dict] = {}
        for rol, (usuario, clave) in CREDENCIALES.items():
            jwt = token(usuario, clave)
            if not jwt:
                fallos.append(f"{rol}: no pudo autenticarse")
                print(f"  {rol:<9} LOGIN FALLIDO")
                continue
            codigo, cuerpo = pedir(jwt, nivel="todos", size=200)
            esperado = ESPERADO[rol]
            correcto = (codigo == 200) == esperado
            celdas += 1
            ok += 1 if correcto else 0
            if correcto and codigo == 200:
                cuerpos[rol] = cuerpo
            if not correcto:
                fallos.append(f"{rol}: esperado {'200' if esperado else '403'}, "
                              f"recibido {codigo}")
            print(f"  {rol:<9} {codigo}  {'OK' if correcto else 'DISCREPANCIA'}"
                  f"   (esperado {'200' if esperado else '403'})")

        # ── 2) Contenido, con el rol de dirección ────────────────────────
        print("\n── CONTENIDO Y REGLAS DE PRESENTACIÓN " + "─" * 39)
        cuerpo = cuerpos.get("ADMIN", {})
        if cuerpo:
            problemas = comprobar_contenido(cuerpo, ctrl)
            problemas += comprobar_filas(cuerpo.get("items", []))
            for p in problemas:
                print(f"  ✗ {p}")
                fallos.append(f"contenido: {p}")
            if not problemas:
                print("  ✓ las 5 reglas de presentación y las 5 limitaciones, en el sobre")

            items = cuerpo.get("items", [])
            print(f"  filas recibidas: {len(items)} · total {cuerpo.get('total')}")
            for etiqueta, valor in (
                    (k["etiqueta"], k["valor"]) for k in cuerpo.get("resumen", [])):
                print(f"    {etiqueta:<34} {valor}")

        # ── 3) Cifras contra PostgreSQL, desde la RESPUESTA ──────────────
        print("\n── CIFRAS (tomadas de la respuesta HTTP) " + "─" * 36)
        if cuerpo:
            items = cuerpo.get("items", [])
            medidas = {
                "clientes": len(items),
                "criticas": sum(1 for i in items if i["nivel_alerta"] == "critica"),
                "atencion": sum(1 for i in items if i["nivel_alerta"] == "atencion"),
                "sin_muestra": sum(1 for i in items
                                   if i["nivel_alerta"] == "sin_muestra"),
            }
            for clave, obtenido in medidas.items():
                esperado = ctrl[clave]
                marca = "✓" if obtenido == esperado else "✗"
                print(f"  {marca} {clave:<14} API {obtenido:>4}   PostgreSQL "
                      f"{esperado:>4}   Δ {obtenido - esperado:+d}")
                if obtenido != esperado:
                    fallos.append(f"cifra {clave}: API {obtenido} vs PG {esperado}")

            # El filtro por defecto tiene que ser «en alerta», no «todos».
            jwt = token(*CREDENCIALES["ADMIN"])
            _, defecto = pedir(jwt, size=200)
            n_defecto = len(defecto.get("items", []))
            marca = "✓" if n_defecto == ctrl["en_alerta"] else "✗"
            print(f"  {marca} filtro por defecto = «en alerta»: {n_defecto} filas, "
                  f"PostgreSQL {ctrl['en_alerta']}")
            if n_defecto != ctrl["en_alerta"]:
                fallos.append(f"el filtro por defecto devuelve {n_defecto} y "
                              f"deberían ser {ctrl['en_alerta']}")

        # ── 4) El recorte del VENDEDOR ───────────────────────────────────
        print("\n── RECORTE DEL VENDEDOR A SU CARTERA " + "─" * 40)
        vend = cuerpos.get("VENDEDOR")
        if vend is not None:
            propios = {i["cliente"] for i in vend.get("items", [])}
            todos = {i["cliente"] for i in cuerpo.get("items", [])}
            real = cartera_de(CREDENCIALES["VENDEDOR"][0])
            print(f"  el vendedor ve {len(propios)} clientes de los {len(todos)} "
                  f"que ve la dirección")
            print(f"  su cartera según PostgreSQL: {len(real)} clientes")

            if vend.get("alcance") != "propio":
                fallos.append("el sobre del vendedor no declara `alcance: propio`")
                print("  ✗ el sobre no declara `alcance: propio`")
            else:
                print("  ✓ el sobre declara `alcance: propio` y su aviso")

            ajenos = propios - real
            if ajenos:
                fallos.append(f"el vendedor ve {len(ajenos)} clientes que NO son "
                              f"suyos: {sorted(ajenos)[:5]}")
                print(f"  ✗ ve {len(ajenos)} clientes ajenos — el recorte falla ABIERTO")
            else:
                print("  ✓ todos los clientes que ve son de su cartera")

            perdidos = real - propios
            if perdidos:
                fallos.append(f"al vendedor le faltan {len(perdidos)} clientes "
                              f"suyos: {sorted(perdidos)[:5]}")
                print(f"  ✗ le faltan {len(perdidos)} clientes de su cartera")
            else:
                print("  ✓ no le falta ninguno de su cartera")

            if propios and propios >= todos:
                fallos.append("el recorte no recorta nada: el vendedor ve lo mismo "
                              "que la dirección")

    finally:
        with admin.cursor() as cur:
            intacto = restaurar_horario(cur, previo)
        admin.commit()
        admin.close()
        print(f"\nHorario restaurado y VERIFICADO: "
              f"{'idéntico al original' if intacto else 'NO COINCIDE — revisar'}")
        if not intacto:
            fallos.append("la restauración del horario no dejó el estado original")

    print("\n" + "=" * 78)
    print(f"  Matriz: {ok} de {celdas} celdas correctas")
    if fallos:
        print(f"  RESULTADO: {len(fallos)} DISCREPANCIA(S)")
        for f in fallos:
            print(f"    - {f}")
    else:
        print("  RESULTADO: matriz, recorte, cifras y reglas de presentación, TODO OK")
    print("=" * 78 + "\n")
    return 1 if fallos else 0


if __name__ == "__main__":
    sys.exit(main())
