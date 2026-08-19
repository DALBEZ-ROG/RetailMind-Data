"""
catalogo.py — Extrae del CÓDIGO FUENTE el catálogo de endpoints del backend.

Se lee del código y no de una lista escrita a mano por la misma razón que
`validar_dwh.py` toma sus cifras del motor: una lista a mano se desincroniza en
la primera sesión y nadie se entera. Aquí el catálogo se regenera en cada
corrida, así que un endpoint nuevo entra a las pruebas **solo por existir**.

Cada entrada: (metodo, ruta, controlador, metodo_java).

OJO con dos detalles del parseo, que son los que hacen que el conteo cuadre:

 1. La ruta efectiva es `@RequestMapping` de la CLASE + el valor del
    `@GetMapping`/etc. del método. Un `@GetMapping` sin valor hereda la ruta de
    la clase tal cual.
 2. Spring admite `@GetMapping({"/a","/b"})` y `@RequestMapping(value=…,
    method=…)`. Los dos aparecen en este código y los dos se contemplan.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

RAIZ = Path(__file__).resolve().parents[2]
FUENTE = RAIZ / "retailmind-backend" / "src" / "main" / "java"

_VERBOS = {
    "GetMapping": "GET",
    "PostMapping": "POST",
    "PutMapping": "PUT",
    "PatchMapping": "PATCH",
    "DeleteMapping": "DELETE",
}

# @RequestMapping("/api/x") o @RequestMapping(value = "/api/x", ...)
_RE_CLASE = re.compile(r'@RequestMapping\(\s*(?:value\s*=\s*)?"([^"]*)"')
# @GetMapping / @GetMapping("/x") / @GetMapping({"/x","/y"}) / @GetMapping(value="/x")
_RE_METODO = re.compile(
    r'@(' + "|".join(_VERBOS) + r')\b(?:\(\s*(?:value\s*=\s*)?((?:\{[^}]*\})|(?:"[^"]*"))?[^)]*\))?'
)
_RE_FIRMA = re.compile(r'public\s+[\w<>,\[\]\s?]+\s+(\w+)\s*\(')


@dataclass(frozen=True)
class Endpoint:
    metodo: str
    ruta: str
    controlador: str
    java: str

    @property
    def clave(self) -> str:
        return f"{self.metodo} {self.ruta}"

    def __str__(self) -> str:            # pragma: no cover - presentación
        return self.clave


def _rutas_de(bloque: str | None) -> list[str]:
    """Devuelve la lista de rutas declaradas en el paréntesis de la anotación."""
    if not bloque:
        return [""]
    return re.findall(r'"([^"]*)"', bloque) or [""]


def _juntar(base: str, sufijo: str) -> str:
    base = base.rstrip("/")
    if not sufijo:
        return base or "/"
    if not sufijo.startswith("/"):
        sufijo = "/" + sufijo
    return (base + sufijo) or "/"


def extraer() -> list[Endpoint]:
    salida: list[Endpoint] = []
    for archivo in sorted(FUENTE.rglob("*Controller.java")):
        texto = archivo.read_text(encoding="utf-8", errors="replace")
        m = _RE_CLASE.search(texto)
        base = m.group(1) if m else ""
        controlador = archivo.stem

        for anot in _RE_METODO.finditer(texto):
            verbo = _VERBOS[anot.group(1)]
            # Nombre del método Java: la primera firma pública tras la anotación.
            firma = _RE_FIRMA.search(texto, anot.end())
            java = firma.group(1) if firma else "?"
            for sufijo in _rutas_de(anot.group(2)):
                salida.append(Endpoint(verbo, _juntar(base, sufijo), controlador, java))
    return salida


def concretar(ruta: str, muestras: dict[str, str] | None = None) -> str:
    """
    Sustituye `{id}` y `{loQueSea}` por un valor concreto para poder llamar.

    Por defecto usa `1`, que es el id más probable de existir en cualquier
    estado de datos. `muestras` permite afinar por nombre de variable cuando
    un `1` no sirve (p. ej. `{departamento}` o `{tablero}`).
    """
    muestras = muestras or {}

    def sust(m: re.Match) -> str:
        nombre = m.group(1).split(":")[0]
        return muestras.get(nombre, "1")

    return re.sub(r"\{(\w+(?::[^}]*)?)\}", sust, ruta)


if __name__ == "__main__":                # pragma: no cover
    eps = extraer()
    print(f"{len(eps)} endpoints en {len({e.controlador for e in eps})} controladores")
    for e in sorted(eps, key=lambda x: (x.ruta, x.metodo)):
        print(f"  {e.metodo:6} {e.ruta:60} {e.controlador}.{e.java}")
