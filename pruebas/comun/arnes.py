"""
arnes.py — Cliente HTTP, sesiones por rol y registro de resultados.

Las credenciales se piden POR ENTORNO y **sin valor por defecto**, igual que
`verificar_ven0304.py` y por el mismo motivo: no engordar la lista de archivos
versionados que reproducen la clave de demo (deuda C-4). Si falta una variable
el arnés se planta y dice cuál.

    export RETAILMIND_ADMIN_PASS='…'
    export RETAILMIND_STAFF_PASS='…'
    export RETAILMIND_CLIENTE_PASS='…'
"""

from __future__ import annotations

import json
import os
import sys
import time
from dataclasses import dataclass, field, asdict
from datetime import datetime, timezone
from pathlib import Path

import requests

RAIZ = Path(__file__).resolve().parents[2]
INFORMES = RAIZ / "pruebas" / "informes"
BASE_URL = os.environ.get("RETAILMIND_API", "http://localhost:8080")

#: Los 10 usuarios de demo. La authority que el JWT debe traer va al lado
#: porque media suite se apoya en ella (matriz de roles).
USUARIOS = {
    "ADMIN":    ("admin@retailmind.com",    "RETAILMIND_ADMIN_PASS"),
    "GERENTE":  ("gerente@retailmind.com",  "RETAILMIND_STAFF_PASS"),
    "VENDEDOR": ("vendedor@retailmind.com", "RETAILMIND_STAFF_PASS"),
    "COMPRAS":  ("compras@retailmind.com",  "RETAILMIND_STAFF_PASS"),
    "BODEGA":   ("bodega@retailmind.com",   "RETAILMIND_STAFF_PASS"),
    "DESPACHO": ("despacho@retailmind.com", "RETAILMIND_STAFF_PASS"),
    "ANALISTA": ("analista@retailmind.com", "RETAILMIND_STAFF_PASS"),
    "SOPORTE":  ("soporte@retailmind.com",  "RETAILMIND_STAFF_PASS"),
    "CLIENTE":  ("maria.lopez@demo.com",    "RETAILMIND_CLIENTE_PASS"),
}


def _clave(variable: str) -> str:
    valor = os.environ.get(variable)
    if not valor:
        sys.exit(
            f"FALTA la variable de entorno {variable}.\n"
            "Las credenciales no se escriben en el repo (deuda C-4): expórtalas "
            "antes de correr. Ver «Credenciales de desarrollo» en CLAUDE.md."
        )
    return valor


# ─────────────────────────────────────────────────────────────────────────────
# Resultado de un caso
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class Resultado:
    caso: str                  # P07-002
    titulo: str
    estado_datos: str          # E0 | E1 | E2 | E3
    veredicto: str             # PASA | FALLA | ERROR | OMITIDO
    severidad: str = ""        # S1..S4, solo si FALLA
    observado: str = ""
    esperado: str = ""
    reproducir: str = ""       # la petición exacta
    detalle: str = ""
    ms: int = 0


class Registro:
    """Acumula resultados y los vuelca a JSON + Markdown."""

    def __init__(self, estado_datos: str) -> None:
        self.estado_datos = estado_datos
        self.resultados: list[Resultado] = []
        self.inicio = time.time()

    def anotar(self, r: Resultado) -> Resultado:
        self.resultados.append(r)
        marca = {"PASA": "ok", "FALLA": "FALLA", "ERROR": "ERROR", "OMITIDO": "--"}[r.veredicto]
        linea = f"[{marca:5}] {r.caso}  {r.titulo}"
        if r.veredicto in ("FALLA", "ERROR"):
            linea += f"\n         observado: {r.observado}\n         esperado : {r.esperado}"
            if r.reproducir:
                linea += f"\n         repro    : {r.reproducir}"
        print(linea, flush=True)
        return r

    def omitir(self, caso: str, titulo: str, *, motivo: str):
        """
        Marca un caso como NO APLICABLE en este estado de datos.

        Existe para no tener que elegir entre dos mentiras: dar por bueno lo que
        no se ha comprobado, o suspender por falta de muestra. Un OMITIDO se
        cuenta aparte y lleva escrito POR QUÉ, que es lo que permite revisarlo.
        """
        return self.anotar(Resultado(
            caso=caso, titulo=titulo, estado_datos=self.estado_datos,
            veredicto="OMITIDO", observado=motivo,
            esperado="aplicable en un estado con muestra suficiente"))

    def medir(self, caso: str, titulo: str, fn, *, esperado: str = "",
              severidad: str = "S3", detalle: str = ""):
        """
        Ejecuta `fn()` y, si revienta, lo anota como ERROR en vez de tumbar la
        corrida entera.

        Una consulta de control mal escrita no puede costar las otras cuarenta
        comprobaciones: el arnés debe seguir y decir CUÁL falló. Devuelve el
        valor de `fn()`, o None si falló.
        """
        try:
            return fn()
        except Exception as e:                                   # noqa: BLE001
            self.anotar(Resultado(
                caso=caso, titulo=titulo, estado_datos=self.estado_datos,
                veredicto="ERROR", severidad=severidad,
                observado=f"{type(e).__name__}: {str(e).splitlines()[0][:160]}",
                esperado=esperado or "la comprobación debería poder ejecutarse",
                detalle=detalle))
            return None

    def caso(self, caso: str, titulo: str, *, condicion: bool,
             observado: str = "", esperado: str = "", severidad: str = "S3",
             reproducir: str = "", detalle: str = "", ms: int = 0) -> Resultado:
        return self.anotar(Resultado(
            caso=caso, titulo=titulo, estado_datos=self.estado_datos,
            veredicto="PASA" if condicion else "FALLA",
            severidad="" if condicion else severidad,
            observado=observado, esperado=esperado,
            reproducir=reproducir, detalle=detalle, ms=ms,
        ))

    # ── volcado ──────────────────────────────────────────────────────────────
    @property
    def fallos(self) -> list[Resultado]:
        return [r for r in self.resultados if r.veredicto in ("FALLA", "ERROR")]

    def resumen(self) -> str:
        por = {}
        for r in self.resultados:
            por[r.veredicto] = por.get(r.veredicto, 0) + 1
        partes = [f"{k}={v}" for k, v in sorted(por.items())]
        return f"{len(self.resultados)} casos · " + " · ".join(partes)

    def volcar(self, nombre: str) -> Path:
        INFORMES.mkdir(parents=True, exist_ok=True)
        sello = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        base = INFORMES / f"{nombre}_{self.estado_datos}_{sello}"

        base.with_suffix(".json").write_text(
            json.dumps([asdict(r) for r in self.resultados], indent=2, ensure_ascii=False),
            encoding="utf-8")

        filas = ["| Caso | Título | Veredicto | Sev | Observado | Esperado |",
                 "|---|---|---|---|---|---|"]
        for r in self.resultados:
            if r.veredicto == "PASA":
                continue
            filas.append(f"| {r.caso} | {r.titulo} | **{r.veredicto}** | {r.severidad} | "
                         f"{r.observado} | {r.esperado} |")
        cuerpo = (f"# Corrida {nombre} — estado {self.estado_datos}\n\n"
                  f"{self.resumen()} · {time.time()-self.inicio:.0f} s\n\n"
                  + ("\n".join(filas) if len(filas) > 2 else "Sin fallos.\n"))
        md = base.with_suffix(".md")
        md.write_text(cuerpo, encoding="utf-8")
        return md


# ─────────────────────────────────────────────────────────────────────────────
# Cliente
# ─────────────────────────────────────────────────────────────────────────────

class Cliente:
    """Sesión autenticada de un rol. Reutiliza la conexión (TCP_NODELAY incluido)."""

    def __init__(self, rol: str, base_url: str = BASE_URL) -> None:
        self.rol = rol
        self.base = base_url.rstrip("/")
        self.sesion = requests.Session()
        # Pool holgado: con el defecto de 10 conexiones, un barrido de 9 roles
        # provoca descartes de urllib3 que en el informe parecen fallos del
        # servidor. El arnés no puede ser la causa de sus propios hallazgos.
        adaptador = requests.adapters.HTTPAdapter(pool_connections=20, pool_maxsize=20)
        self.sesion.mount("http://", adaptador)
        self.sesion.mount("https://", adaptador)
        self.token: str | None = None
        self.ultimo_error: str | None = None
        self.authority: str | None = None
        self.error_login: str | None = None

    def entrar(self) -> bool:
        correo, variable = USUARIOS[self.rol]
        try:
            r = self.sesion.post(f"{self.base}/api/auth/login",
                                 json={"username": correo, "password": _clave(variable)},
                                 timeout=30)
        except requests.RequestException as e:
            self.error_login = f"{type(e).__name__}: {e}"
            return False
        if r.status_code != 200:
            self.error_login = f"HTTP {r.status_code}: {r.text[:200]}"
            return False
        cuerpo = r.json()
        self.token = cuerpo.get("token") or cuerpo.get("accessToken")
        self.authority = (cuerpo.get("rol") or cuerpo.get("role")
                          or (cuerpo.get("usuario") or {}).get("rol"))
        if not self.token:
            self.error_login = f"login 200 pero sin token: {list(cuerpo)}"
            return False
        self.sesion.headers["Authorization"] = f"Bearer {self.token}"
        return True

    def pedir(self, metodo: str, ruta: str, **kw) -> requests.Response | None:
        """
        Una petición. Devuelve None si la conexión falló — y en ese caso deja el
        motivo en `self.ultimo_error`.

        Tragarse la excepción y devolver un `-1` pelado convierte «el servidor
        tardó más de 120 s» y «la conexión se rechazó al instante» en el mismo
        síntoma, que son diagnósticos opuestos. Se reintenta UNA vez ante un
        fallo de conexión (no de lectura) porque el pool de urllib3 descarta
        conexiones cuando se satura, y ese descarte no es un defecto del sistema
        bajo prueba: es ruido del arnés, y sin el reintento se cuela como fallo.
        """
        kw.setdefault("timeout", 120)
        self.ultimo_error = None
        for intento in (1, 2):
            try:
                return self.sesion.request(metodo, f"{self.base}{ruta}", **kw)
            except requests.Timeout as e:
                self.ultimo_error = f"timeout tras {kw['timeout']}s: {e}"
                return None
            except requests.RequestException as e:
                self.ultimo_error = f"{type(e).__name__}: {e}"
                if intento == 2:
                    return None
                time.sleep(1.0)
        return None

    def get(self, ruta: str, **kw):
        return self.pedir("GET", ruta, **kw)

    # ── TRAMPA DEL ARNÉS, documentada porque ya costó una corrida entera ─────
    #
    # `requests.Response.__bool__` devuelve `self.ok`, o sea **False para todo
    # 4xx**. Escribir `codigo = r.status_code if r else -1` convierte cada 400
    # —que en esta suite es la RESPUESTA CORRECTA— en «sin respuesta», y el
    # informe acusa al sistema de no contestar cuando estaba contestando lo que
    # debía. Pasó: 90 casos de lista blanca salieron en rojo con el rechazo
    # funcionando perfectamente.
    #
    # En este arnés se comprueba SIEMPRE `r is not None`, nunca `if r`.
    # `codigo()` existe para no tener que acordarse.

    @staticmethod
    def codigo(r: requests.Response | None) -> int:
        """Código HTTP de una respuesta, o -1 si la conexión falló."""
        return r.status_code if r is not None else -1

    def curl(self, metodo: str, ruta: str) -> str:
        return f'curl -X {metodo} -H "Authorization: Bearer <{self.rol}>" {self.base}{ruta}'


def sesiones(roles: list[str] | None = None) -> dict[str, Cliente]:
    """Abre sesión con cada rol. Devuelve solo las que entraron."""
    salida = {}
    for rol in (roles or list(USUARIOS)):
        c = Cliente(rol)
        if c.entrar():
            salida[rol] = c
        else:
            print(f"[AVISO] {rol} no pudo entrar: {c.error_login}", flush=True)
    return salida


def esperar_api(intentos: int = 60, espera: float = 3.0) -> bool:
    """Bloquea hasta que /api/health responda, o se rinde."""
    for i in range(intentos):
        try:
            r = requests.get(f"{BASE_URL}/api/health", timeout=10)
            if r.status_code < 500:
                return True
        except requests.RequestException:
            pass
        time.sleep(espera)
    return False
