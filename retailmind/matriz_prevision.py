"""Matriz rol × endpoint y verificación de cifras de la PREVISIÓN DE DEMANDA.

Fase E2 del nivel estratégico (§5.1 de
`docs/estrategico/DISENO_NIVEL_ESTRATEGICO.md`). Hace DOS cosas que el pipeline
no puede hacer por su cuenta:

1. **La matriz de permisos.** El mismo informe se sirve en dos rutas con dos
   repartos distintos —Gerencia suma al ANALISTA, Compras suma a COMPRAS— y el
   corte lo hace la RUTA, porque ClickHouse no tiene privilegio por columna. No
   hay barrera de motor a la que preguntarle: se prueba por API o no se prueba.

2. **Las cifras, tomadas de la RESPUESTA HTTP.** Es la misma disciplina de
   `validar_tableros.py`: no se consulta ClickHouse y se compara con
   PostgreSQL, se consulta **lo que la pantalla recibe**. Una consulta correcta
   sobre una tabla correcta puede seguir llegando mal a la pantalla —un filtro
   que no se aplica, un sobre que pierde un campo— y eso solo se ve aquí.

   OJO con qué se puede contrastar: una previsión NO se compara contra
   PostgreSQL, porque su mes no ha ocurrido. Lo que se contrasta es el
   **universo** (que estén todas las series y solo ésas), el **ancla** (que la
   previsión empiece donde acaba la venta real) y la **coherencia interna** de
   lo que llega: la banda contiene a su cifra, la muestra viaja en la fila, el
   error viaja en la fila y la serie del gráfico trae observado Y previsto.

## La ventana horaria, y por qué este script la toca

`fuera_horario` BLOQUEA el login (script 53) y los horarios de `grupo_horario`
son por día de la semana: según el día y la hora, GERENTE o COMPRAS no pueden
entrar y los dos endpoints les responderían 403 — un 403 correcto por el motivo
equivocado. Se ensancha la ventana del día en curso, se corre la matriz y se
**restaura el estado exacto verificándolo** en un `finally`.

Uso:
    py matriz_prevision.py
"""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime

from conexion_verificacion import pg_admin, pg_lectura

API = os.environ.get("RETAILMIND_API", "http://localhost:8080")

#: Dos conexiones, las dos resueltas desde el entorno (`ETL_PG_*` + el secreto
#: del superusuario), sin ninguna credencial escrita en el código:
#:   · `pg_admin()`   — superusuario: ensanchar un horario es una ESCRITURA y
#:     `retailmind_etl` es de solo lectura por construcción.
#:   · `pg_lectura()` — rol `retailmind_etl` para las cifras de control, como
#:     en el resto del pipeline: con BYPASSRLS, para que `pol_horario` no
#:     filtre en silencio y la comparación se haga contra cero filas.

CREDENCIALES = {
    "ADMIN":    ("admin@retailmind.com",    "Admin2026!"),
    "GERENTE":  ("gerente@retailmind.com",  "Retail2026!"),
    "ANALISTA": ("analista@retailmind.com", "Retail2026!"),
    "SOPORTE":  ("soporte@retailmind.com",  "Retail2026!"),
    "VENDEDOR": ("vendedor@retailmind.com", "Retail2026!"),
    "COMPRAS":  ("compras@retailmind.com",  "Retail2026!"),
    "BODEGA":   ("bodega@retailmind.com",   "Retail2026!"),
    "DESPACHO": ("despacho@retailmind.com", "Retail2026!"),
}

RUTAS = ["gerencia/prevision-demanda", "compras/prevision-demanda"]

#: Lo que DEBE pasar: True = 200, False = 403.
#:
#: Los dos repartos son DISTINTOS y ninguno es un subconjunto del otro: el
#: ANALISTA entra por Gerencia y no por Compras; COMPRAS al revés. Es la razón
#: de que cada ruta lleve su propia línea en `SecurityConfig` y no se apoye en
#: el comodín de su departamento.
#:
#: BODEGA y DESPACHO quedan fuera de las dos aunque el informe no lleve ni un
#: importe —son unidades—: es material de planificación, no de operación.
ESPERADO: dict[str, dict[str, bool]] = {
    "ADMIN":    {"gerencia/prevision-demanda": True,  "compras/prevision-demanda": True},
    "GERENTE":  {"gerencia/prevision-demanda": True,  "compras/prevision-demanda": True},
    "ANALISTA": {"gerencia/prevision-demanda": True,  "compras/prevision-demanda": False},
    "COMPRAS":  {"gerencia/prevision-demanda": False, "compras/prevision-demanda": True},
    "SOPORTE":  {"gerencia/prevision-demanda": False, "compras/prevision-demanda": False},
    "VENDEDOR": {"gerencia/prevision-demanda": False, "compras/prevision-demanda": False},
    "BODEGA":   {"gerencia/prevision-demanda": False, "compras/prevision-demanda": False},
    "DESPACHO": {"gerencia/prevision-demanda": False, "compras/prevision-demanda": False},
}

HORIZONTE = 3
MESES_MINIMOS = 12


# ── Ventana horaria ──────────────────────────────────────────────────────────

#: Las horas viajan como TEXTO en los dos sentidos. PostgreSQL admite `24:00:00`
#: en una columna `time` —así está declarada la cobertura 24/7 de SOPORTE— y
#: `datetime.time` de Python no puede representarla: psycopg2 la degrada a
#: `00:00` en silencio y la restauración revienta a mitad contra el
#: `CHECK (hora_inicio < hora_fin)`, dejando ventanas abiertas.
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


def pedir(ruta: str, jwt: str, **filtros) -> tuple[int, dict]:
    consulta = urllib.parse.urlencode({k: v for k, v in filtros.items() if v not in (None, "")})
    url = f"{API}/api/informes/{ruta}" + (f"?{consulta}" if consulta else "")
    pet = urllib.request.Request(url, headers={"Authorization": f"Bearer {jwt}"})
    try:
        with urllib.request.urlopen(pet, timeout=180) as r:
            return r.status, json.load(r)
    except urllib.error.HTTPError as e:
        return e.code, {}


# ── Cifras de control desde PostgreSQL ───────────────────────────────────────

def universo() -> dict:
    """El catálogo que DEBE previsionarse, según la base transaccional."""
    cx = pg_lectura()
    try:
        with cx.cursor() as cur:
            cur.execute("""
                WITH linea AS (
                    SELECT (date_trunc('month',
                        p.fecha_pedido AT TIME ZONE 'America/Guayaquil'))::date AS mes,
                        COALESCE(c.nombre, 'sin_categoria') AS categoria,
                        pd.producto_variante_id             AS variante
                    FROM pedido_detalle pd
                    JOIN pedido p             ON p.id  = pd.pedido_id
                    JOIN estado_pedido ep     ON ep.id = p.estado_pedido_id
                    JOIN producto_variante pv ON pv.id = pd.producto_variante_id
                    LEFT JOIN producto_categoria pc
                              ON pc.producto_id = pv.producto_id AND pc.es_principal
                    LEFT JOIN categoria c ON c.id = pc.categoria_id
                    WHERE ep.codigo <> 'cancelado'),
                cat AS (SELECT categoria, count(DISTINCT mes) m FROM linea GROUP BY 1),
                var AS (SELECT variante, count(DISTINCT mes) m FROM linea GROUP BY 1)
                SELECT (SELECT count(*) FROM cat),
                       (SELECT count(*) FROM cat WHERE m >= %s),
                       (SELECT count(*) FROM var WHERE m >= %s),
                       (SELECT count(*) FROM producto_variante),
                       (SELECT count(DISTINCT mes) FROM linea),
                       (SELECT to_char(max(mes), 'YYYY-MM') FROM linea)
            """, (MESES_MINIMOS, MESES_MINIMOS))
            (categorias, cat_previsibles, variantes_largas, catalogo,
             meses, ultimo) = cur.fetchone()
        return {"categorias": categorias, "categorias_previsibles": cat_previsibles,
                "variantes_largas": variantes_largas, "catalogo": catalogo,
                "meses": meses, "ultimo_mes": ultimo}
    finally:
        cx.close()


def serie_real() -> dict[str, int]:
    """Unidades vendidas por mes, no canceladas — la serie del gráfico."""
    cx = pg_lectura()
    try:
        with cx.cursor() as cur:
            cur.execute("""
                SELECT to_char(date_trunc('month',
                           p.fecha_pedido AT TIME ZONE 'America/Guayaquil'), 'YYYY-MM'),
                       sum(pd.cantidad)
                FROM pedido_detalle pd
                JOIN pedido p         ON p.id  = pd.pedido_id
                JOIN estado_pedido ep ON ep.id = p.estado_pedido_id
                WHERE ep.codigo <> 'cancelado'
                GROUP BY 1 ORDER BY 1
            """)
            return {m: int(u) for m, u in cur.fetchall()}
    finally:
        cx.close()


def siguiente(mes: str, k: int) -> str:
    anio, m = (int(x) for x in mes.split("-"))
    total = anio * 12 + (m - 1) + k
    return f"{total // 12:04d}-{total % 12 + 1:02d}"


# ── Comprobaciones sobre la RESPUESTA ────────────────────────────────────────

def comprobar_contenido(cuerpo: dict, ctrl: dict, reales: dict[str, int],
                        etiqueta: str) -> list[str]:
    """Las cifras y las reglas de §5.1.9, sobre lo que la pantalla recibió."""
    fallos = []

    def exigir(cond, mensaje):
        if not cond:
            fallos.append(f"{etiqueta}: {mensaje}")

    exigir(cuerpo.get("analiticaDisponible") is True,
           "respondió DEGRADADO — la matriz no probaría nada sobre permisos")
    exigir(bool(cuerpo.get("datosAl")), "sin marca de agua `datosAl`")

    items = cuerpo.get("items", [])
    exigir(bool(items), "sin filas")
    if not items:
        return fallos

    # Regla 2 — NINGÚN número sin banda, y la banda contiene a su cifra.
    sin_banda = [i for i in items
                 if i.get("metodo") != "sin_prevision"
                 and float(i["limite_superior"]) <= float(i["limite_inferior"])]
    exigir(not sin_banda, f"{len(sin_banda)} filas con banda de anchura cero")
    fuera = [i for i in items
             if i.get("metodo") != "sin_prevision"
             and not (float(i["limite_inferior"]) <= float(i["unidades_previstas"])
                      <= float(i["limite_superior"]))]
    exigir(not fuera, f"{len(fuera)} filas cuya banda NO contiene su previsión")

    # Reglas 3 y 4 — la muestra y el error, POR FILA.
    exigir(all("meses_historia" in i for i in items),
           "falta `meses_historia`: la muestra no viaja en la fila (regla 3)")
    exigir(all("mape_backtest" in i and "mape_linea_base" in i for i in items),
           "falta el error por fila o su línea base (regla 4)")

    # Regla 1 — la serie del gráfico trae observado Y previsto.
    serie = cuerpo.get("serie", [])
    observados = [p for p in serie if p.get("real") is not None]
    previstos = [p for p in serie if p.get("previsto") is not None]
    exigir(len(observados) == ctrl["meses"],
           f"la serie trae {len(observados)} meses observados y PostgreSQL tiene "
           f"{ctrl['meses']}")
    exigir(len(previstos) == HORIZONTE,
           f"la serie trae {len(previstos)} meses previstos y el horizonte es {HORIZONTE}")

    # El ancla: la previsión arranca donde acaba la venta REAL.
    if previstos:
        exigir(previstos[0]["periodo"] == siguiente(ctrl["ultimo_mes"], 1),
               f"la previsión arranca en {previstos[0]['periodo']} y la venta acaba "
               f"en {ctrl['ultimo_mes']}")

    # Las unidades observadas del gráfico, contra PostgreSQL mes a mes.
    discrepan = [p["periodo"] for p in observados
                 if int(p["real"]) != reales.get(p["periodo"], -1)]
    exigir(not discrepan,
           f"{len(discrepan)} meses del gráfico no cuadran con PostgreSQL "
           f"(p. ej. {discrepan[:3]})")

    # Las cinco limitaciones de §5.1.10, en pantalla.
    salvedad = cuerpo.get("salvedad", "")
    for clave, nombre in (("VENTA, no la demanda", "se prevé la venta y no la demanda"),
                          ("SEED SIMULADO", "el histórico es un seed simulado"),
                          ("19 meses", "la historia son 19 meses"),
                          ("previsión individual", "variantes sin previsión propia"),
                          ("INCOMPLETO", "el mes truncado")):
        exigir(clave in salvedad, f"la salvedad NO declara «{nombre}»")
    return fallos


def comprobar_universo(jwt: str, ruta: str, ctrl: dict) -> list[str]:
    """Los tres niveles traen exactamente las series que la base tiene."""
    fallos = []
    for nivel, esperado, que in (
        ("total", 1, "serie total"),
        ("categoria", ctrl["categorias"], "categorías con venta"),
        ("variante", ctrl["variantes_largas"], f"variantes con ≥{MESES_MINIMOS} meses"),
    ):
        codigo, cuerpo = pedir(ruta, jwt, nivel=nivel, horizonte=1, size=200)
        if codigo != 200:
            fallos.append(f"{ruta} nivel={nivel}: HTTP {codigo}")
            continue
        if cuerpo.get("total") != esperado:
            fallos.append(f"{ruta} nivel={nivel}: {cuerpo.get('total')} {que} en la "
                          f"respuesta, {esperado} en PostgreSQL")
    return fallos


# ── Prueba ───────────────────────────────────────────────────────────────────

def correr() -> int:
    ctrl = universo()
    reales = serie_real()
    print(f"\nCifras de control (PostgreSQL, rol retailmind_etl):")
    print(f"  categorías con venta ......... {ctrl['categorias']}"
          f"  (previsibles: {ctrl['categorias_previsibles']})")
    print(f"  variantes con ≥{MESES_MINIMOS} meses ....... {ctrl['variantes_largas']}"
          f"  de {ctrl['catalogo']} del catálogo")
    print(f"  meses de historia ............ {ctrl['meses']}"
          f"  (último: {ctrl['ultimo_mes']})")
    print(f"  filas esperadas .............. "
          f"{(1 + ctrl['categorias'] + ctrl['variantes_largas']) * HORIZONTE}")

    fallos: list[str] = []
    print(f"\n{'ROL':<10} {'gerencia':>12} {'compras':>12}")
    print("-" * 36)

    for rol, (usuario, clave) in CREDENCIALES.items():
        jwt = token(usuario, clave)
        if jwt is None:
            fallos.append(f"{rol}: no se pudo iniciar sesión (¿ventana horaria?)")
            print(f"{rol:<10} {'SIN LOGIN':>12}")
            continue

        celdas = []
        for ruta in RUTAS:
            codigo, cuerpo = pedir(ruta, jwt, nivel="categoria", size=50)
            debe = ESPERADO[rol][ruta]
            celdas.append(str(codigo))
            if not debe:
                if codigo != 403:
                    fallos.append(f"{rol} × {ruta}: se esperaba 403 y llegó {codigo}")
                continue
            if codigo != 200:
                fallos.append(f"{rol} × {ruta}: se esperaba 200 y llegó {codigo}")
                continue
            fallos += comprobar_contenido(cuerpo, ctrl, reales, f"{rol} × {ruta}")

        print(f"{rol:<10} {celdas[0]:>12} {celdas[1]:>12}")

    print("-" * 36)

    # El universo se comprueba UNA vez por ruta, con un rol que sí entra: es
    # una propiedad del dato, no del rol, y repetirla ocho veces solo alarga.
    jwt_admin = token(*CREDENCIALES["ADMIN"])
    if jwt_admin:
        for ruta in RUTAS:
            fallos += comprobar_universo(jwt_admin, ruta, ctrl)

    # Un filtro que no filtra es el fallo silencioso de esta pantalla.
    if jwt_admin:
        for h in (1, 2, 3):
            codigo, cuerpo = pedir(RUTAS[0], jwt_admin, nivel="categoria",
                                   horizonte=h, size=50)
            if codigo != 200 or cuerpo.get("total") != ctrl["categorias"]:
                fallos.append(f"filtro horizonte={h}: {cuerpo.get('total')} filas, "
                              f"se esperaban {ctrl['categorias']}")
        codigo, cuerpo = pedir(RUTAS[0], jwt_admin, nivel="categoria",
                               categoria="Abarrotes", size=50)
        if codigo != 200 or cuerpo.get("total") != HORIZONTE:
            fallos.append(f"filtro categoria=Abarrotes: {cuerpo.get('total')} filas, "
                          f"se esperaban {HORIZONTE}")
        codigo, _ = pedir(RUTAS[0], jwt_admin, nivel="inventado")
        if codigo != 400:
            fallos.append(f"nivel fuera de la lista blanca: HTTP {codigo}, se esperaba 400")

    total = len(CREDENCIALES) * len(RUTAS)
    if fallos:
        print(f"\n{len(fallos)} discrepancia(s):")
        for f in fallos:
            print(f"  - {f}")
        return 1
    print(f"\n{total} celdas de permiso verificadas, 0 discrepancias.")
    print("Universo, ancla, serie del gráfico, banda, muestra, error por fila y las "
          "cinco limitaciones: comprobados sobre la RESPUESTA HTTP.")
    return 0


def main() -> int:
    print("Matriz rol × endpoint y cifras — PREVISIÓN DE DEMANDA (fase E2)")
    cx = pg_admin()
    cx.autocommit = True
    previo: list[tuple] = []
    try:
        with cx.cursor() as cur:
            previo = ensanchar_horario(cur)
        return correr()
    finally:
        with cx.cursor() as cur:
            if previo and restaurar_horario(cur, previo):
                print("Ventana horaria RESTAURADA y verificada contra el estado previo.")
            elif previo:
                print("¡ATENCIÓN! La ventana horaria NO quedó como estaba. Revísala a mano.")
        cx.close()


if __name__ == "__main__":
    sys.exit(main())
