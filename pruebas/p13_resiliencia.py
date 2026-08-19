"""
p13_resiliencia.py — Degradación y resiliencia (suite P13) + arranque (P01).

Verifica EL INVARIANTE DE DISEÑO del sistema, que es lo más importante que hay
aquí y lo único que justifica tener dos motores:

    ClickHouse es SOLO analítica. Con ClickHouse apagado, TODO el sistema
    funciona y solo analytics/recomendaciones se degradan CON AVISO.

Esta suite lo prueba parando el contenedor de verdad. Y prueba también las dos
mitades que se olvidan:

  · que la degradación sea **acotada en el tiempo** — un informe que se cuelga
    30 s no está degradando, está caído;
  · que la recuperación NO exija reiniciar el backend.

⚠️ PARA el contenedor de ClickHouse durante ~1 minuto. No toca ningún dato:
`docker compose stop` / `start`, jamás `down -v`.
"""

from __future__ import annotations

import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from comun.arnes import Cliente, Registro, esperar_api                     # noqa: E402

RAIZ = Path(__file__).resolve().parents[1]

#: Informes SIMPLES: van contra PostgreSQL y deben sobrevivir intactos.
SIMPLES = [
    "/api/informes/ventas/cartera-pedidos",
    "/api/informes/compras/ordenes",
    "/api/informes/soporte/bandeja",
    "/api/informes/gerencia/auditoria",
]

#: COMPUESTOS y tableros: van contra ClickHouse y deben degradar CON AVISO.
COMPUESTOS = [
    "/api/informes/ventas/evolucion-mensual",
    "/api/informes/inventario/rotacion",
    "/api/tableros/omnicanal",
    "/api/tableros/rentabilidad",
]

TOPE_DEGRADADO_S = 8.0


def _docker(*args: str) -> str:
    p = subprocess.run(["docker", "compose", *args], capture_output=True,
                       text=True, cwd=RAIZ, encoding="utf-8", errors="replace",
                       timeout=300)
    return (p.stdout or "") + (p.stderr or "")


def correr(estado_datos: str = "E3") -> Registro:
    reg = Registro(estado_datos)

    # ── P01 · configuración de arranque (estático, no requiere parar nada) ───
    compose = (RAIZ / "docker-compose.yml").read_text(encoding="utf-8", errors="replace")

    # Se busca la CONDICIÓN declarada, no la palabra suelta: el bloque incluye
    # un comentario que dice «a proposito NO service_healthy», y buscar la
    # cadena a secas hacía fallar la prueba con el compose correcto delante.
    bloque_ch = _bloque_depends(compose, "clickhouse")
    declara_started = "condition: service_started" in bloque_ch
    declara_healthy = "condition: service_healthy" in bloque_ch
    reg.caso("P13-004", "El compose declara ClickHouse como service_started",
             condicion=declara_started and not declara_healthy,
             severidad="S1",
             observado=f"started={declara_started} healthy={declara_healthy}",
             esperado="service_started — con service_healthy, ClickHouse caído "
                      "impediría arrancar el backend y el invariante se rompería "
                      "en el arranque, no en la consulta")

    # P01-010 · ningún guion del repo lleva `down -v`
    # Solo se buscan líneas que EJECUTEN el comando. Se excluyen:
    #   · los comentarios y las celdas de tabla — una línea que ADVIERTE contra
    #     `down -v` no es un guion que lo ejecute;
    #   · este mismo archivo y los documentos del plan, que citan la regla para
    #     explicarla;
    #   · `pruebas/informes/`, que son las salidas de corridas anteriores y
    #     repiten el texto del propio hallazgo — un barrido que se lee a sí
    #     mismo nunca vuelve a dar verde.
    hallazgos = []
    propios = {"pruebas/p13_resiliencia.py", "docs/PLAN_DE_PRUEBAS.md",
               "docs/pruebas/DEFECTOS.md", "CLAUDE.md", "pruebas/README.md"}
    for archivo in list(RAIZ.rglob("*.sh")) + list(RAIZ.rglob("*.md")) + \
                   list(RAIZ.rglob("*.py")) + list(RAIZ.rglob("*.yml")):
        rel = archivo.relative_to(RAIZ).as_posix()
        if any(p in rel for p in ("node_modules/", "target/", ".git/",
                                  "pruebas/informes/")) or rel in propios:
            continue
        try:
            texto = archivo.read_text(encoding="utf-8", errors="replace")
        except Exception:
            continue
        for linea in texto.splitlines():
            desnuda = linea.strip()
            if "down -v" not in desnuda:
                continue
            if desnuda.startswith(("#", "*", ">", "//", "--", "|")):
                continue          # comentario o tabla: advierte, no ejecuta
            hallazgos.append(f"{rel}: {desnuda[:70]}")
    reg.caso("P01-010", "Ningún guion ejecuta `docker compose down -v`",
             condicion=not hallazgos, severidad="S1",
             observado=f"{len(hallazgos)}: " + "; ".join(hallazgos[:4]) if hallazgos else "ninguno",
             esperado="0 — el `-v` destruye el volumen de ClickHouse "
                      "(fact_eventos, 2.823.245 filas irreproducibles)")

    if not esperar_api():
        reg.caso("P01-004", "El API responde antes de empezar", condicion=False,
                 severidad="S1", observado="sin respuesta", esperado="HTTP < 500")
        return reg

    admin = Cliente("ADMIN")
    if not admin.entrar():
        reg.caso("P02-001", "Login de ADMIN", condicion=False, severidad="S1",
                 observado=admin.error_login or "?", esperado="200")
        return reg

    # ── línea base con TODO en pie ──────────────────────────────────────────
    base_ok = {}
    for ruta in SIMPLES + COMPUESTOS:
        r = admin.get(ruta)
        base_ok[ruta] = admin.codigo(r)
    reg.caso("P13-000", "Línea base: todo responde antes de tocar nada",
             condicion=all(c == 200 for c in base_ok.values()), severidad="S2",
             observado=", ".join(f"{k.split('/')[-1]}={v}" for k, v in base_ok.items()),
             esperado="todos 200")

    # ─────────────────────────────────────────────────────────────────────────
    # P13-001 · con ClickHouse apagado
    # ─────────────────────────────────────────────────────────────────────────
    print("\n>>> parando ClickHouse…", flush=True)
    _docker("stop", "clickhouse")
    time.sleep(3)

    try:
        t0 = time.time()
        r = admin.get("/api/health")
        ms_health = time.time() - t0
        cuerpo = {}
        try:
            cuerpo = r.json() if r is not None else {}
        except Exception:
            pass
        reg.caso("P13-001", "El sistema sigue UP con ClickHouse caído",
                 condicion=cuerpo.get("status") == "UP", severidad="S1",
                 observado=f"status={cuerpo.get('status')!r} analytics={cuerpo.get('analytics')!r}",
                 esperado="status UP · analytics DEGRADED",
                 ms=int(ms_health * 1000))
        reg.caso("P13-001", "La salud declara la analítica degradada",
                 condicion=str(cuerpo.get("analytics", "")).upper() in ("DEGRADED", "DOWN"),
                 severidad="S2",
                 observado=f"analytics={cuerpo.get('analytics')!r}",
                 esperado="DEGRADED — 'UP' con el contenedor parado sería peor "
                          "que caerse: sería mentir")
        reg.caso("P13-001", "La salud responde ACOTADA en el tiempo",
                 condicion=ms_health <= TOPE_DEGRADADO_S, severidad="S1",
                 observado=f"{ms_health:.1f} s",
                 esperado=f"≤ {TOPE_DEGRADADO_S} s — colgarse no es degradar",
                 ms=int(ms_health * 1000))

        # Los SIMPLES tienen que seguir intactos: son de PostgreSQL.
        for ruta in SIMPLES:
            t0 = time.time()
            r = admin.get(ruta)
            dt = time.time() - t0
            reg.caso("P13-001", f"Simple intacto sin ClickHouse: {ruta.split('/')[-1]}",
                     condicion=admin.codigo(r) == 200, severidad="S1",
                     observado=f"HTTP {admin.codigo(r)} en {dt:.1f} s",
                     esperado="200 — no toca ClickHouse ni de lejos",
                     ms=int(dt * 1000))

        # Los COMPUESTOS deben degradar CON AVISO, no dar 500 ni colgarse.
        for ruta in COMPUESTOS:
            t0 = time.time()
            r = admin.get(ruta)
            dt = time.time() - t0
            codigo = admin.codigo(r)
            disponible = None
            try:
                disponible = (r.json() or {}).get("analiticaDisponible") if codigo == 200 else None
            except Exception:
                pass
            reg.caso("P13-001", f"Compuesto degrada con aviso: {ruta.split('/')[-1]}",
                     condicion=codigo == 200 and disponible is False, severidad="S1",
                     observado=f"HTTP {codigo} · analiticaDisponible={disponible} · {dt:.1f} s",
                     esperado="200 con analiticaDisponible=false — un 500 manda a "
                              "buscar un fallo del servidor donde hay un servicio apagado",
                     ms=int(dt * 1000))
            reg.caso("P13-001", f"…y acotado en el tiempo: {ruta.split('/')[-1]}",
                     condicion=dt <= TOPE_DEGRADADO_S, severidad="S2",
                     observado=f"{dt:.1f} s", esperado=f"≤ {TOPE_DEGRADADO_S} s",
                     ms=int(dt * 1000))

    finally:
        print(">>> levantando ClickHouse…", flush=True)
        _docker("start", "clickhouse")

    # ─────────────────────────────────────────────────────────────────────────
    # P13-002 · recuperación SIN reiniciar el backend
    # ─────────────────────────────────────────────────────────────────────────
    # Es la mitad que se olvida: degradar es fácil, volver sin que alguien
    # reinicie nada es lo que hace la degradación utilizable.
    recuperado, espera = False, 0.0
    for _ in range(40):
        time.sleep(3)
        espera += 3
        r = admin.get("/api/informes/inventario/rotacion")
        if admin.codigo(r) == 200:
            try:
                if (r.json() or {}).get("analiticaDisponible") is not False:
                    recuperado = True
                    break
            except Exception:
                pass
    reg.caso("P13-002", "Recupera sin reiniciar el backend",
             condicion=recuperado, severidad="S1",
             observado=f"{'recuperado' if recuperado else 'NO recuperado'} tras {espera:.0f} s",
             esperado="vuelve solo — el mismo proceso de backend, sin reinicio",
             ms=int(espera * 1000))

    for ruta in COMPUESTOS:
        r = admin.get(ruta)
        reg.caso("P13-002", f"Tras recuperar responde de nuevo: {ruta.split('/')[-1]}",
                 condicion=admin.codigo(r) == 200, severidad="S2",
                 observado=f"HTTP {admin.codigo(r)}", esperado="200")

    return reg


def _bloque_depends(compose: str, servicio: str) -> str:
    """Trozo del compose donde el backend declara su dependencia del servicio."""
    i = compose.find(f"{servicio}:", compose.find("depends_on"))
    return compose[i:i + 160] if i > 0 else ""


if __name__ == "__main__":
    estado = sys.argv[1] if len(sys.argv) > 1 else "E3"
    reg = correr(estado)
    print("\n" + "=" * 70)
    print(reg.resumen())
    print("informe:", reg.volcar("p13_resiliencia"))
    sys.exit(1 if reg.fallos else 0)
