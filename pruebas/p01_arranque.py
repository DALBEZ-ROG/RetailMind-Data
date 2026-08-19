"""
p01_arranque.py — Arranque, configuración y salud (suite P01).

Comprueba las decisiones de despliegue que solo se ven cuando algo va mal, y
que por eso nadie prueba: que el backend se NIEGUE a arrancar sin sus secretos,
que el healthcheck mire donde debe, y que nada del repositorio pueda destruir un
volumen irreproducible.

La prueba central es **P01-002/003**, y es contraintuitiva: se exige que el
sistema **FALLE**. `application.properties` dejó a propósito sin valor por
defecto `postgres.datasource.password` y `jwt.secret`, para que una instalación
mal configurada se detenga en el arranque en vez de levantarse a medias. Un
backend que arranca sin secreto de JWT firma tokens con un valor vacío o
adivinable — es peor que no arrancar, porque parece que funciona.

Se levantan contenedores DESECHABLES para eso: nunca se toca el backend de demo.
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from comun.arnes import Cliente, Registro, esperar_api                     # noqa: E402

RAIZ = Path(__file__).resolve().parents[1]
IMAGEN = "retailmind-backend:latest"


def _sh(*args: str, timeout: int = 300) -> tuple[int, str]:
    p = subprocess.run(args, capture_output=True, text=True, cwd=RAIZ,
                       encoding="utf-8", errors="replace", timeout=timeout)
    return p.returncode, (p.stdout or "") + (p.stderr or "")


def _arrancar_desechable(nombre: str, entorno: dict[str, str]) -> str:
    """
    Lanza un backend DESECHABLE con el entorno dado y devuelve su log.

    Va sin publicar puerto y se borra siempre (`--rm` no sirve porque hace falta
    leer el log después de que muera, así que se elimina a mano en el `finally`).
    """
    _sh("docker", "rm", "-f", nombre)
    args = ["docker", "run", "-d", "--name", nombre, "--network", "retailmind_net"]
    for k, v in entorno.items():
        args += ["-e", f"{k}={v}"]
    args.append(IMAGEN)
    codigo, salida = _sh(*args)
    if codigo != 0:
        return f"NO SE PUDO LANZAR: {salida[:200]}"
    # Se espera a que muera o a que se dé por arrancado.
    _sh("docker", "wait", nombre, timeout=120)
    _, log = _sh("docker", "logs", nombre)
    _sh("docker", "rm", "-f", nombre)
    return log


def correr(estado_datos: str = "E3") -> Registro:
    reg = Registro(estado_datos)

    # ── P01-002/003 · sin secretos, el backend NO debe arrancar ────────────
    base_url = "jdbc:postgresql://postgres:5432/retailmind"
    entorno_ok = {
        "POSTGRES_DATASOURCE_URL": base_url,
        "POSTGRES_DATASOURCE_USERNAME": "retailmind_app",
        "CLICKHOUSE_DATASOURCE_URL": "jdbc:ch://clickhouse:8123/retailmind?compress=0",
        "DWH_CRON": "-",
    }

    log = _arrancar_desechable("prueba-p01-sin-pass", dict(entorno_ok, JWT_SECRET="x" * 64))
    murio = ("Application run failed" in log or "APPLICATION FAILED TO START" in log
             or "Started RetailmindApplication" not in log)
    reg.caso("P01-002", "Sin `postgres.datasource.password` el backend NO arranca",
             condicion=murio, severidad="S1",
             observado="no arrancó (correcto)" if murio else "ARRANCÓ sin contraseña de base",
             esperado="que falle — arrancar sin credencial deja el sistema a medias "
                      "y parece que funciona",
             detalle=log[-200:].replace("\n", " ") if not murio else "")

    # El valor es deliberadamente absurdo y NO es una credencial: aquí lo que se
    # prueba es la AUSENCIA del `jwt.secret`, así que la contraseña de base solo
    # tiene que estar presente para que el arranque llegue a fallar por el otro
    # motivo. Se escribe así de explícito para que el barrido de credenciales
    # del cierre de sesión no tenga que decidir si lo es.
    valor_ficticio_no_es_una_clave = "VALOR-FICTICIO-DE-PRUEBA-NO-ES-UNA-CLAVE"
    log = _arrancar_desechable(
        "prueba-p01-sin-jwt",
        dict(entorno_ok, POSTGRES_DATASOURCE_PASSWORD=valor_ficticio_no_es_una_clave))
    murio = ("Application run failed" in log or "APPLICATION FAILED TO START" in log
             or "Started RetailmindApplication" not in log)
    reg.caso("P01-003", "Sin `jwt.secret` el backend NO arranca",
             condicion=murio, severidad="S1",
             observado="no arrancó (correcto)" if murio else "ARRANCÓ sin secreto de JWT",
             esperado="que falle — firmar tokens con un secreto vacío o adivinable "
                      "es peor que no arrancar")

    # ── P01-006 · el healthcheck del frontend apunta a 127.0.0.1 ───────────
    compose = (RAIZ / "docker-compose.yml").read_text(encoding="utf-8", errors="replace")
    # Se mira SOLO la línea `test:` del healthcheck, no el bloque entero: el
    # compose explica en un COMENTARIO por qué no se usa `localhost`, y buscar
    # la palabra suelta suspende con la configuración correcta delante. Es la
    # misma trampa que ya costó una corrida con `service_healthy` (FP-08).
    i = compose.find("frontend:")
    bloque = compose[i:i + 2500] if i > 0 else ""
    linea_test = next((l for l in bloque.splitlines()
                       if l.strip().startswith("test:")), "")
    usa_localhost = "localhost" in linea_test
    usa_ipv4 = "127.0.0.1" in linea_test
    reg.caso("P01-006", "El healthcheck del frontend usa 127.0.0.1, no localhost",
             condicion=usa_ipv4 and not usa_localhost, severidad="S2",
             observado=f"test: {linea_test.strip()[:90]}",
             esperado="127.0.0.1 — dentro del contenedor `localhost` resuelve a ::1 "
                      "primero y nginx solo escucha IPv4: el contenedor queda "
                      "«unhealthy» ETERNO con la página sirviéndose bien")

    # ── P01-004/005 · la salud responde y dice la verdad ───────────────────
    if esperar_api():
        admin = Cliente("ADMIN")
        r = admin.get("/api/health")
        cuerpo = {}
        try:
            cuerpo = r.json() if r is not None else {}
        except Exception:
            pass
        reg.caso("P01-004", "`/api/health` responde sin autenticación",
                 condicion=admin.codigo(r) == 200, severidad="S1",
                 observado=f"HTTP {admin.codigo(r)}",
                 esperado="200 — es el healthcheck de los contenedores")
        for clave in ("status", "database", "postgres"):
            reg.caso("P01-004", f"La salud declara «{clave}»",
                     condicion=clave in cuerpo, severidad="S3",
                     observado=f"claves: {sorted(cuerpo)}", esperado=f"incluye {clave}")

    # ── P01-009 · los datos viven en el VOLUMEN, no en la imagen ───────────
    volumenes = "volumes:" in compose
    externo = "external: true" in compose
    reg.caso("P01-009", "El compose declara volúmenes, y alguno EXTERNO",
             condicion=volumenes and externo, severidad="S1",
             observado=f"volumes={volumenes} external={externo}",
             esperado="el volumen de ClickHouse va `external: true` porque guarda "
                      "`fact_eventos` (2.823.245 filas irreproducibles)")

    # ── P01-008 · el proyecto compila ──────────────────────────────────────
    # Maven se invoca DENTRO de `retailmind-backend`: desde la raíz del
    # repositorio no hay `pom.xml` y falla con `MissingProjectException`, que
    # parece un error de compilación y es un error de directorio.
    backend = RAIZ / "retailmind-backend"
    p = subprocess.run(["cmd", "/c", "mvn -q -o compile"] if sys.platform == "win32"
                       else ["mvn", "-q", "-o", "compile"],
                       capture_output=True, text=True, cwd=backend,
                       encoding="utf-8", errors="replace", timeout=900)
    codigo, salida = p.returncode, (p.stdout or "") + (p.stderr or "")
    if "not recognized" in salida or "no se reconoce" in salida.lower():
        reg.omitir("P01-008", "El backend compila",
                   motivo="Maven no está disponible en este entorno")
    else:
        reg.caso("P01-008", "El backend compila sin errores",
                 condicion=codigo == 0, severidad="S1",
                 observado=f"exit={codigo} · {salida[-200:]}" if codigo else "exit=0",
                 esperado="exit 0")

    return reg


if __name__ == "__main__":
    estado = sys.argv[1] if len(sys.argv) > 1 else "E3"
    reg = correr(estado)
    print("\n" + "=" * 70)
    print(reg.resumen())
    print("informe:", reg.volcar("p01_arranque"))
    sys.exit(1 if reg.fallos else 0)
