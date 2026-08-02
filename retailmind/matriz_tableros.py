"""Matriz rol × tablero de los SIETE TABLEROS DE DIRECCIÓN, por API.

Prueba los SIETE tableros con los OCHO roles de personal del sistema y comprueba que la
respuesta es la esperada: 200 con sus bloques para los destinatarios, 403 para
todos los demás. El corte lo hace la RUTA (`SecurityConfig`) porque ClickHouse
no tiene privilegio por columna, así que ésta es la ÚNICA forma de verificarlo:
no hay barrera de motor a la que preguntarle.

## La ventana horaria, y por qué este script la toca

`fuera_horario` BLOQUEA el login (script 53), y los horarios de `grupo_horario`
son por día de la semana: un sábado por la tarde, GERENTE y ANALISTA no pueden
entrar y los siete tableros les responderían 403 — un 403 correcto por el motivo
equivocado, que arruinaría la matriz sin dar ninguna señal de ello.

El script ensancha la ventana del día en curso, corre la matriz y **restaura el
estado exacto en un `finally`**, verificando después que la tabla volvió a ser
la de antes. Si la restauración no cuadra, sale con error y lo dice: dejar el
sistema con los horarios abiertos sería peor que no haber probado.

Uso:
    py matriz_tableros.py
"""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request
from datetime import datetime

import psycopg2

API = os.environ.get("RETAILMIND_API", "http://localhost:8080")

#: Superusuario: ensanchar un horario es una ESCRITURA y `retailmind_etl` es de
#: solo lectura por construcción. La verificación de cifras (validar_tableros.py)
#: sigue yendo con el rol de lectura; esto es una preparación de la prueba.
PG_ADMIN = dict(host="localhost", port=5432, dbname="retailmind",
                user="postgres", password="1250143656")

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

TABLEROS = ["omnicanal", "rentabilidad", "cliente-posventa",
            "operacion", "costo-operacion", "abastecimiento", "gobierno-dato"]

#: Lo que DEBE pasar. `None` = 403. Un texto = 200 con ese alcance.
#:
#: Los cortes, y por qué cada uno:
#:   · T-4 es el ÚNICO sin dinero, y por eso el único que DESPACHO y BODEGA
#:     abren. Lo sostienen dos cosas a la vez: esta línea y que su consulta no
#:     seleccione un importe — lo segundo se comprueba aparte, en cada rol.
#:   · T-5 es su gemelo CON dinero: existe separado para que T-4 pueda estar
#:     abierto a la operación.
#:   · T-6 suma COMPRAS: es su objetivo y su centro de costo.
#:   · T-7 es DATO SENSIBLE y deja fuera al ANALISTA, que entra en los otros
#:     SEIS. Y en la auditoría el corte lo hace la RUTA y no el motor:
#:     grp_analista SÍ tiene SELECT sobre log_auditoria.
ESPERADO: dict[str, dict[str, str | None]] = {
    "ADMIN":    {"omnicanal": "ok", "rentabilidad": "ok", "cliente-posventa": "completo",
                 "operacion": "ok", "costo-operacion": "ok", "abastecimiento": "ok",
                 "gobierno-dato": "ok"},
    "GERENTE":  {"omnicanal": "ok", "rentabilidad": "ok", "cliente-posventa": "completo",
                 "operacion": "ok", "costo-operacion": "ok", "abastecimiento": "ok",
                 "gobierno-dato": "ok"},
    "ANALISTA": {"omnicanal": "ok", "rentabilidad": "ok", "cliente-posventa": "completo",
                 "operacion": "ok", "costo-operacion": "ok", "abastecimiento": "ok",
                 "gobierno-dato": None},
    "SOPORTE":  {"omnicanal": None, "rentabilidad": None, "cliente-posventa": "posventa",
                 "operacion": None, "costo-operacion": None, "abastecimiento": None,
                 "gobierno-dato": None},
    "VENDEDOR": {"omnicanal": None, "rentabilidad": None, "cliente-posventa": None,
                 "operacion": None, "costo-operacion": None, "abastecimiento": None,
                 "gobierno-dato": None},
    "COMPRAS":  {"omnicanal": None, "rentabilidad": None, "cliente-posventa": None,
                 "operacion": None, "costo-operacion": None, "abastecimiento": "ok",
                 "gobierno-dato": None},
    "BODEGA":   {"omnicanal": None, "rentabilidad": None, "cliente-posventa": None,
                 "operacion": "ok", "costo-operacion": None, "abastecimiento": None,
                 "gobierno-dato": None},
    "DESPACHO": {"omnicanal": None, "rentabilidad": None, "cliente-posventa": None,
                 "operacion": "ok", "costo-operacion": None, "abastecimiento": None,
                 "gobierno-dato": None},
}

#: Cuántos bloques trae cada tablero cuando se sirve entero.
BLOQUES_ESPERADOS = {
    "omnicanal": 6, "rentabilidad": 6, "cliente-posventa": 6,
    "operacion": 6, "costo-operacion": 4, "abastecimiento": 8, "gobierno-dato": 3,
}

#: Palabras que delatan una columna de dinero en T-4, el tablero SIN importes.
#: La comprobación se repite POR ROL: el corte no puede depender de quién mira.
DINERO = ("costo", "monto", "total", "precio", "importe", "venta", "gasto",
          "saldo", "pagado", "reembols", "margen", "subtotal", "credito")
NO_DINERO = {"es_total", "unidades_perdidas", "unidades_sobrantes",
             "unidades_reingresadas"}

#: Bloques que cada alcance debe traer. El de SOPORTE no es un subconjunto
#: cualquiera: son EXACTAMENTE los de tickets y devoluciones.
BLOQUES_T3 = {
    "completo": ["pareto_clientes", "nuevo_recurrente", "tickets_categoria",
                 "devolucion_producto", "calificacion_producto", "reclama_y_devuelve"],
    "posventa": ["tickets_categoria", "devolucion_producto", "reclama_y_devuelve"],
}


# ── Ventana horaria ──────────────────────────────────────────────────────────

#: Las horas se leen y se escriben como TEXTO, nunca como `datetime.time`.
#:
#: PostgreSQL admite `24:00:00` en una columna `time` —y `grupo_horario` lo usa:
#: es como está declarada la cobertura 24/7 del rol SOPORTE—. `datetime.time` de
#: Python NO puede representar esa hora, así que psycopg2 la convierte en
#: silencio a `00:00`. Leer y volver a escribir el mismo valor deja de ser una
#: operación neutra: `00:00 → 00:00` viola el `CHECK (hora_inicio < hora_fin)` y
#: la restauración revienta A MITAD, con parte de las filas ya devueltas y el
#: resto abiertas. Ocurrió, y dejó una ventana horaria abierta hasta que se
#: arregló a mano. Con `::text` en los dos sentidos, el valor viaja intacto.
_LEER = ("SELECT id, hora_inicio::text, hora_fin::text, activo "
         "FROM grupo_horario WHERE {} ORDER BY id")


def ensanchar_horario(cur) -> list[tuple]:
    """Abre el día EN CURSO para todos los grupos y devuelve el estado previo."""
    dia = (datetime.now().weekday() + 1) % 7          # lunes=0 en Python, domingo=0 en la BD
    cur.execute(_LEER.format("dia_semana = %s"), (dia,))
    previo = cur.fetchall()
    cur.execute("UPDATE grupo_horario SET hora_inicio = '00:00', hora_fin = '23:59', "
                "activo = true WHERE dia_semana = %s", (dia,))
    print(f"Ventana del día {dia} ensanchada en {len(previo)} filas "
          f"(se restaurará al terminar).")
    return previo


def restaurar_horario(cur, previo: list[tuple]) -> bool:
    for ident, inicio, fin, activo in previo:
        cur.execute("UPDATE grupo_horario "
                    "SET hora_inicio = %s::time, hora_fin = %s::time, activo = %s "
                    "WHERE id = %s", (inicio, fin, activo, ident))
    # No basta con escribir: hay que LEER lo escrito. Una restauración que se da
    # por buena sin comprobarla deja el sistema abierto sin que nadie se entere.
    ids = [p[0] for p in previo]
    cur.execute(_LEER.format("id = ANY(%s)"), (ids,))
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


def pedir(tablero: str, jwt: str) -> tuple[int, dict]:
    pet = urllib.request.Request(f"{API}/api/tableros/{tablero}",
                                 headers={"Authorization": f"Bearer {jwt}"})
    try:
        with urllib.request.urlopen(pet, timeout=180) as r:
            return r.status, json.load(r)
    except urllib.error.HTTPError as e:
        return e.code, {}


# ── Prueba ───────────────────────────────────────────────────────────────────

def correr() -> int:
    fallos: list[str] = []
    cabecera = f"{'ROL':<10}"
    for i, t in enumerate(TABLEROS, start=1):
        cabecera += f" {('T-' + str(i)):>14}"
    print("\n" + cabecera)
    print("  " + " · ".join(f"T-{i} {t}" for i, t in enumerate(TABLEROS, start=1)))
    print("-" * len(cabecera))

    for rol, (usuario, clave) in CREDENCIALES.items():
        jwt = token(usuario, clave)
        if jwt is None:
            fallos.append(f"{rol}: no se pudo iniciar sesión (¿ventana horaria?)")
            print(f"{rol:<10} {'SIN LOGIN':>16}")
            continue

        celdas = []
        for t in TABLEROS:
            codigo, cuerpo = pedir(t, jwt)
            esperado = ESPERADO[rol][t]

            if esperado is None:
                celdas.append(f"{codigo}")
                if codigo != 403:
                    fallos.append(f"{rol} × {t}: se esperaba 403 y llegó {codigo}")
                continue

            if codigo != 200:
                celdas.append(f"{codigo}")
                fallos.append(f"{rol} × {t}: se esperaba 200 y llegó {codigo}")
                continue

            ids = [b["id"] for b in cuerpo.get("bloques", [])]
            alcance = cuerpo.get("alcance", "ok")
            celdas.append(f"200·{len(ids)}b"
                          + (f"·{alcance[:4]}" if t == "cliente-posventa" else ""))

            if t == "cliente-posventa":
                if alcance != esperado:
                    fallos.append(f"{rol} × {t}: alcance «{alcance}», se esperaba «{esperado}»")
                if ids != BLOQUES_T3[esperado]:
                    fallos.append(f"{rol} × {t}: bloques {ids}, "
                                  f"se esperaban {BLOQUES_T3[esperado]}")
            elif len(ids) != BLOQUES_ESPERADOS[t]:
                fallos.append(f"{rol} × {t}: {len(ids)} bloques, se esperaban "
                              f"{BLOQUES_ESPERADOS[t]}")

            # T-4 no puede devolver un importe A NADIE, ni siquiera al ADMIN:
            # si la consulta selecciona dinero, el corte ya no existe para el
            # día en que alguien amplíe la ruta.
            if t == "operacion":
                sucias = set()
                for b in cuerpo.get("bloques", []):
                    for fila in b.get("items", []):
                        for clave in fila:
                            c = clave.lower()
                            if c in NO_DINERO:
                                continue
                            if any(pal in c for pal in DINERO):
                                sucias.add(clave)
                if sucias:
                    fallos.append(f"{rol} × {t}: columnas de dinero "
                                  f"{sorted(sucias)}")

            # Un tablero que responde 200 pero degradado no prueba nada sobre
            # los permisos: se avisa para no dar la matriz por buena.
            if cuerpo.get("analiticaDisponible") is False:
                fallos.append(f"{rol} × {t}: respondió DEGRADADO "
                              f"(el almacén no estaba disponible)")

        fila = f"{rol:<10}"
        for c in celdas:
            fila += f" {c:>14}"
        print(fila)

    print("-" * len(cabecera))
    total = len(CREDENCIALES) * len(TABLEROS)
    if fallos:
        print(f"\n{len(fallos)} discrepancia(s) sobre {total} celdas:")
        for f in fallos:
            print(f"  - {f}")
        return 1
    print(f"\n{total} celdas verificadas, 0 discrepancias.")
    return 0


def main() -> int:
    print("Matriz rol × tablero — nivel estratégico, los SIETE tableros")
    cx = psycopg2.connect(**PG_ADMIN)
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
