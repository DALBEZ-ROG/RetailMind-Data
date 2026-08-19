"""
motor.py — Ejecutar SQL como un ROL CONCRETO de PostgreSQL.

Va por `docker compose exec postgres psql` y no por psycopg2 a propósito:
dentro del contenedor la autenticación local es por socket, así que se puede
asumir cualquier rol SIN contraseña. Eso evita meter en el arnés las claves de
`retailmind_app` y `retailmind_etl` —que viven fuera del índice de git— y
mantiene la deuda C-4 donde está.

TODA consulta que pueda escribir va envuelta en una transacción con ROLLBACK
explícito por quien la llama. Esta capa no impone el rollback porque hay pruebas
que necesitan ver el efecto de un COMMIT ajeno; lo que sí hace es negarse a
tocar una base que no sea de pruebas cuando se pide modo escritura.
"""

from __future__ import annotations

import os
import subprocess
from pathlib import Path

RAIZ = Path(__file__).resolve().parents[2]

#: Base por defecto de las consultas. Configurable para poder correr los
#: mismos invariantes contra E0/E1 sin duplicar una sola línea de la suite.
BASE_POR_DEFECTO = os.environ.get("RETAILMIND_DB", "retailmind")

#: Bases sobre las que se permite ESCRIBIR desde el arnés.
BASES_ESCRIBIBLES = {"retailmind_pruebas"}


class ErrorSql(Exception):
    """El motor devolvió un error. `texto` trae el mensaje crudo."""

    def __init__(self, texto: str) -> None:
        super().__init__(texto)
        self.texto = texto


def sql(consulta: str, *, rol_login: str = "postgres", base: str | None = None,
        asumir: str | None = None, cliente_id: int | None = None,
        permitir_escritura: bool = False) -> str:
    """
    Ejecuta `consulta` y devuelve la salida cruda (sin cabeceras, sin bordes).

    · `rol_login`  — con qué rol de LOGIN se conecta (`retailmind_app`, `retailmind_etl`, `postgres`).
    · `asumir`     — rol de grupo a asumir con `set_config('role', …, true)`,
                     que es como lo hace la aplicación: por PARÁMETRO LIGADO y
                     dentro de la transacción, nunca concatenando el nombre.
    · `cliente_id` — valor de `app.cliente_id` para las políticas RLS de cliente.

    Lanza `ErrorSql` si el motor responde con error, para poder distinguir
    «me lo negó» de «devolvió cero filas», que es la distinción central de esta
    suite: RLS **filtra en silencio**, no da 403.
    """
    base = base or BASE_POR_DEFECTO
    if permitir_escritura and base not in BASES_ESCRIBIBLES:
        raise RuntimeError(f"ABORTA: escritura pedida sobre '{base}', que no es base de pruebas.")

    partes = []
    if asumir or cliente_id is not None:
        partes.append("BEGIN;")
        if asumir:
            # Parámetro LIGADO: el nombre del rol nunca se concatena.
            partes.append(f"SELECT set_config('role', {_lit(asumir)}, true);")
        if cliente_id is not None:
            partes.append(f"SELECT set_config('app.cliente_id', {_lit(str(cliente_id))}, true);")
        partes.append(consulta.rstrip().rstrip(";") + ";")
        partes.append("ROLLBACK;")
    else:
        partes.append(consulta)

    guion = "\n".join(partes)
    proc = subprocess.run(
        ["docker", "compose", "exec", "-T", "postgres",
         "psql", "-U", rol_login, "-d", base, "-t", "-A", "-F", "|",
         "-v", "ON_ERROR_STOP=1", "-q"],
        input=guion, capture_output=True, text=True, cwd=RAIZ,
        encoding="utf-8", errors="replace", timeout=180,
    )
    if proc.returncode != 0:
        raise ErrorSql((proc.stderr or proc.stdout or "").strip())
    lineas = [l for l in (proc.stdout or "").splitlines()
              if l.strip() and l.strip() not in ("BEGIN", "ROLLBACK", "COMMIT")]
    # Las filas que devuelve el propio `set_config` se descartan: son eco.
    if asumir:
        lineas = [l for l in lineas if l.strip() not in (asumir, str(cliente_id))]
    return "\n".join(lineas).strip()


def escalar(consulta: str, **kw) -> str:
    """Primera celda de la primera fila."""
    salida = sql(consulta, **kw)
    return salida.splitlines()[0].split("|")[0].strip() if salida else ""


def entero(consulta: str, **kw) -> int:
    v = escalar(consulta, **kw)
    try:
        return int(v)
    except ValueError:
        return -1


def _lit(texto: str) -> str:
    """Literal SQL con las comillas escapadas. Solo para el guion de psql."""
    return "'" + texto.replace("'", "''") + "'"
