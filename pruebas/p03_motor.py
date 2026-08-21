"""
p03_motor.py — Seguridad de motor (suite P03).

Es la suite más importante del plan, porque en este sistema **la seguridad no
vive en Java**: vive en 95 políticas RLS, 1.355 GRANT, 109 ACL de columna, 34
triggers de horario y un rol de aplicación NOINHERIT que asume el rol del
usuario por transacción.

La distinción que gobierna toda la suite: **«me lo negó» y «devolvió cero filas»
NO son lo mismo**. RLS no da 403 — filtra en silencio. Una prueba que solo mire
«¿falló?» da verde con la seguridad rota al revés (leyendo de menos) y con la
seguridad rota del todo (leyendo de más). Aquí se comprueban las dos direcciones.
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from comun.arnes import Cliente, Registro, esperar_api, sesiones           # noqa: E402
from comun.motor import ErrorSql, entero, escalar, sql                     # noqa: E402

RAIZ = Path(__file__).resolve().parents[1]

#: Los 9 roles de grupo del motor.
GRUPOS = ["grp_administrador", "grp_gerente", "grp_vendedor", "grp_compras",
          "grp_bodega", "grp_despacho", "grp_cliente", "grp_analista", "grp_soporte"]

#: Endpoints con dinero que BODEGA y DESPACHO no deben poder usar.
CON_DINERO = [
    "/api/informes/inventario/capital-inmovilizado",
    "/api/informes/logistica/costo-envio",
    "/api/informes/gerencia/balanza",
    "/api/informes/gerencia/descuento-cupones",
    "/api/tableros/rentabilidad",
    "/api/tableros/abastecimiento",
]

#: Palabras que delatan un importe en el nombre de un campo.
OLOR_A_DINERO = ("monto", "total", "precio", "costo", "importe", "margen",
                 "saldo", "ingreso", "gasto", "capital", "ticket_promedio")


def correr(estado_datos: str = "E3") -> Registro:
    reg = Registro(estado_datos)

    # ─────────────────────────────────────────────────────────────────────────
    # P03-002 · el nombre del rol viaja LIGADO, no concatenado
    # ─────────────────────────────────────────────────────────────────────────
    # Si se concatenara, esto ejecutaría un DROP. Como viaja como parámetro, el
    # motor lo trata como UN nombre de rol entero y no lo encuentra.
    hostil = "grp_x; DROP TABLE marca"
    try:
        salida = sql(f"SELECT set_config('role', '{hostil}', true)",
                     rol_login="retailmind_app")
        veredicto, detalle = False, f"no falló: {salida!r}"
    except ErrorSql as e:
        veredicto = "does not exist" in e.texto or "no existe" in e.texto
        detalle = e.texto.splitlines()[0][:140]
    reg.caso("P03-002", "set_config con un nombre de rol hostil",
             condicion=veredicto, severidad="S1",
             observado=detalle, esperado="«role ... does not exist» — nombre ligado, no concatenado")

    marca_viva = entero("SELECT count(*) FROM pg_class WHERE relname='marca'")
    reg.caso("P03-002", "La tabla `marca` sigue existiendo tras el intento",
             condicion=marca_viva == 1, severidad="S1",
             observado=f"{marca_viva} tabla(s) `marca`", esperado="1")

    # ─────────────────────────────────────────────────────────────────────────
    # P03-003/004 · aislamiento RLS del cliente, en AMBAS direcciones
    # ─────────────────────────────────────────────────────────────────────────
    total_pedidos = entero("SELECT count(*) FROM pedido")
    cli = escalar("SELECT c.id FROM cliente c JOIN usuario u ON u.id=c.usuario_id "
                  "WHERE u.email='maria.lopez@demo.com'")
    if cli:
        suyos = entero("SELECT count(*) FROM pedido",
                       rol_login="retailmind_app", asumir="grp_cliente",
                       cliente_id=int(cli))
        ajenos = entero(f"SELECT count(*) FROM pedido WHERE cliente_id <> {int(cli)}",
                        rol_login="retailmind_app", asumir="grp_cliente",
                        cliente_id=int(cli))
        reales = entero(f"SELECT count(*) FROM pedido WHERE cliente_id = {int(cli)}")

        reg.caso("P03-003", "El cliente NO ve los pedidos de los demás",
                 condicion=ajenos == 0, severidad="S1",
                 observado=f"ve {ajenos} pedidos ajenos (de {total_pedidos} totales)",
                 esperado="0 pedidos ajenos")
        # La otra dirección, que es la que nadie prueba: que SÍ vea los suyos.
        reg.caso("P03-003", "El cliente SÍ ve todos los suyos (RLS no filtra de más)",
                 condicion=suyos == reales and reales > 0, severidad="S2",
                 observado=f"ve {suyos}, tiene {reales}",
                 esperado="iguales y > 0 — filtrar de más también es un fallo, y silencioso")

    # P03-004 · el censo de RLS.
    #
    # Los números son un CENSO y no una constante del universo: suben cuando el
    # sistema gana una tabla protegida. Al 2026-08-21 son 51 tablas y 98
    # políticas — el script 112 sumó `cliente_categoria_interes` (con sus dos
    # políticas, la de cliente y la de horario) y `pol_visitante_catalogo` sobre
    # `inventario`. Bajarlos o dejarlos sin explicación es lo que convierte esta
    # comprobación en decorativa: lo que se vigila es que nadie DESACTIVE RLS,
    # así que al cambiarlos hay que decir de dónde sale cada fila nueva.
    con_rls = entero("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace "
                     "WHERE n.nspname='public' AND c.relrowsecurity")
    politicas = entero("SELECT count(*) FROM pg_policies WHERE schemaname='public'")
    reg.caso("P03-004", "RLS activa en las 51 tablas declaradas",
             condicion=con_rls == 51, severidad="S1",
             observado=f"{con_rls} tablas con RLS", esperado="51")
    reg.caso("P03-004", "Las 98 políticas siguen en su sitio",
             condicion=politicas == 98, severidad="S1",
             observado=f"{politicas} políticas", esperado="98")

    # Ninguna tabla con RLS puede quedarse SIN política: el defecto de RLS es
    # denegar, así que una tabla con RLS y cero políticas devuelve cero filas a
    # todo el mundo sin un solo error.
    huerfanas = sql("""
        SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname='public' AND c.relrowsecurity
          AND NOT EXISTS (SELECT 1 FROM pg_policies p
                          WHERE p.schemaname='public' AND p.tablename=c.relname)
        ORDER BY 1""")
    reg.caso("P03-004", "Ninguna tabla con RLS se quedó sin política",
             condicion=huerfanas == "", severidad="S1",
             observado=huerfanas or "ninguna",
             esperado="ninguna — RLS sin política deniega TODO en silencio")

    # ─────────────────────────────────────────────────────────────────────────
    # P03-005 · RLS filtra en SILENCIO (no da error)
    # ─────────────────────────────────────────────────────────────────────────
    # Se comprueba el mecanismo, no el reloj: `pol_horario` está declarada con
    # cmd = ALL, y ALL incluye SELECT. Ese es el motivo por el que un ETL con un
    # rol grp_* devolvería 0 filas sin fallar (justificación del script 85).
    horario_all = entero("SELECT count(*) FROM pg_policies "
                         "WHERE schemaname='public' AND policyname LIKE 'pol_horario%' AND cmd='ALL'")
    reg.caso("P03-005", "Las políticas de horario siguen cubriendo SELECT (cmd=ALL)",
             condicion=horario_all >= 50, severidad="S2",
             observado=f"{horario_all} políticas pol_horario con cmd=ALL",
             esperado="≥ 50 — si dejaran de cubrir SELECT, cambiaría el modelo de amenaza")

    triggers = entero("SELECT count(*) FROM pg_trigger WHERE tgname LIKE 'trg_horario_%'")
    reg.caso("P03-008", "Los 34 triggers de horario siguen vivos",
             condicion=triggers == 34, severidad="S1",
             observado=f"{triggers} triggers", esperado="34")

    # ─────────────────────────────────────────────────────────────────────────
    # P03-009 · la frontera del horario es 24:00:00 y el intervalo, semiabierto
    # ─────────────────────────────────────────────────────────────────────────
    bloqueados = entero("""
        SELECT count(*) FROM grupo_horario
        WHERE NOT (hora_inicio = '00:00:00' AND hora_fin = '24:00:00')""")
    reg.caso("P03-008", "Las ventanas siguen en 24/7 (script 88/90)",
             condicion=bloqueados == 0, severidad="S3",
             observado=f"{bloqueados} ventanas fuera de [00:00, 24:00)",
             esperado="0 — el 90 aborta si alguna queda estrechada",
             detalle="ya pasó una vez: grp_analista domingo quedó en 00:00-23:30 (C-11)")

    for g in GRUPOS:
        if g == "grp_administrador":
            continue
        try:
            en_horario = escalar(f"SELECT esta_en_horario('{g}')")
        except ErrorSql as e:
            en_horario = f"error: {e.texto[:60]}"
        reg.caso("P03-008", f"{g} está dentro de su ventana ahora",
                 condicion=en_horario == "t", severidad="S2",
                 observado=f"esta_en_horario = {en_horario}",
                 esperado="t — con 24/7 nadie puede estar fuera")

    # ─────────────────────────────────────────────────────────────────────────
    # P03-013/014 · el rol del ETL: solo lectura en cuatro capas + BYPASSRLS
    # ─────────────────────────────────────────────────────────────────────────
    atributos = sql("SELECT rolsuper, rolcreatedb, rolcreaterole, rolbypassrls "
                    "FROM pg_roles WHERE rolname='retailmind_etl'")
    partes = atributos.split("|") if atributos else []
    reg.caso("P03-013", "retailmind_etl no es superusuario ni crea nada (capa 1)",
             condicion=partes[:3] == ["f", "f", "f"], severidad="S1",
             observado=f"super={partes[0] if partes else '?'} createdb={partes[1] if len(partes)>1 else '?'} "
                       f"createrole={partes[2] if len(partes)>2 else '?'}",
             esperado="f/f/f")
    reg.caso("P03-014", "retailmind_etl tiene BYPASSRLS",
             condicion=len(partes) > 3 and partes[3] == "t", severidad="S1",
             observado=f"rolbypassrls={partes[3] if len(partes)>3 else '?'}",
             esperado="t — sin él, pol_horario (cmd=ALL) le devolvería CERO FILAS en silencio")

    # Capa 4: default_transaction_read_only a nivel de ROL. Se prueba SOLA,
    # porque con la sesión en READ WRITE el motor sigue negando por privilegio
    # (capa 2) y las dos capas se taparían entre sí.
    solo_lectura = escalar("SELECT setconfig::text FROM pg_db_role_setting s "
                           "JOIN pg_roles r ON r.oid=s.setrole WHERE r.rolname='retailmind_etl'")
    reg.caso("P03-013", "retailmind_etl lleva default_transaction_read_only (capa 4)",
             condicion="read_only=on" in (solo_lectura or "").replace(" ", ""),
             severidad="S2",
             observado=solo_lectura or "sin ajustes de rol",
             esperado="default_transaction_read_only=on")

    # Y que de verdad no pueda escribir.
    try:
        sql("INSERT INTO marca (nombre, slug, activo) VALUES ('PRUEBA-P03','prueba-p03',true)",
            rol_login="retailmind_etl")
        escritura = "ESCRIBIÓ"
        ok_escritura = False
    except ErrorSql as e:
        escritura = e.texto.splitlines()[0][:120]
        ok_escritura = True
    reg.caso("P03-013", "retailmind_etl no puede escribir (probado, no supuesto)",
             condicion=ok_escritura, severidad="S1",
             observado=escritura, esperado="el motor lo niega")

    # Y que SÍ pueda leer el universo completo (la otra dirección).
    etl_pedidos = entero("SELECT count(*) FROM pedido", rol_login="retailmind_etl")
    reg.caso("P03-014", "retailmind_etl lee el universo completo",
             condicion=etl_pedidos == total_pedidos and etl_pedidos > 0, severidad="S1",
             observado=f"etl ve {etl_pedidos}, hay {total_pedidos}",
             esperado="iguales — si viera menos, el DWH se cargaría incompleto SIN error")

    # ─────────────────────────────────────────────────────────────────────────
    # P03-006/007 · segregación financiera
    # ─────────────────────────────────────────────────────────────────────────
    # (a) En el MOTOR: bodega no tiene SELECT de tabla sobre `pedido`, solo
    #     privilegios por COLUMNA, y ninguna de ellas es de dinero.
    tabla_entera = entero("""
        SELECT count(*) FROM (
          SELECT (aclexplode(relacl)).grantee, (aclexplode(relacl)).privilege_type
          FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
          WHERE n.nspname='public' AND c.relname='pedido') x
        JOIN pg_roles r ON r.oid = x.grantee
        WHERE r.rolname='grp_bodega' AND x.privilege_type='SELECT'""")
    reg.caso("P03-006", "grp_bodega NO tiene SELECT de tabla sobre `pedido`",
             condicion=tabla_entera == 0, severidad="S1",
             observado=f"{tabla_entera} GRANT de tabla",
             esperado="0 — los privilegios de columna solo SUMAN: con el de tabla, "
                      "restringir columnas no cambia nada")

    columnas_dinero = sql("""
        SELECT a.attname FROM pg_attribute a
        JOIN pg_class c ON c.oid=a.attrelid
        JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname='public' AND c.relname='pedido' AND a.attacl IS NOT NULL
          AND EXISTS (SELECT 1 FROM aclexplode(a.attacl) x
                      JOIN pg_roles r ON r.oid=x.grantee
                      WHERE r.rolname='grp_bodega' AND x.privilege_type='SELECT')
          AND (a.attname ~ 'total|monto|costo|precio|descuento')
        ORDER BY 1""")
    reg.caso("P03-006", "Ninguna columna de dinero de `pedido` está concedida a grp_bodega",
             condicion=columnas_dinero == "", severidad="S1",
             observado=columnas_dinero or "ninguna", esperado="ninguna")

    # (b) Por la RUTA y por la CONSULTA: la comprobación de verdad, contra el API.
    if esperar_api():
        clientes = sesiones()
        for rol in ("BODEGA", "DESPACHO"):
            c = clientes.get(rol)
            if not c:
                continue
            for ruta in CON_DINERO:
                r = c.get(ruta)
                codigo = c.codigo(r)
                if codigo == 200:
                    # Si entra, la respuesta NO puede traer importes. ClickHouse
                    # no tiene GRANT por columna, así que lo único que separa a
                    # este rol del dinero es que la consulta no lo seleccione.
                    crudo = (r.text or "").lower()
                    hallados = [p for p in OLOR_A_DINERO if f'"{p}' in crudo or f"_{p}" in crudo]
                    reg.caso("P03-006", f"{rol} entra a {ruta.split('/')[-1]} sin recibir importes",
                             condicion=not hallados, severidad="S1",
                             observado=f"HTTP 200 con campos {hallados}" if hallados else "200 sin importes",
                             esperado="403, o 200 sin un solo campo monetario",
                             reproducir=c.curl("GET", ruta))
                else:
                    reg.caso("P03-006", f"{rol} queda fuera de {ruta.split('/')[-1]}",
                             condicion=codigo == 403, severidad="S2",
                             observado=f"HTTP {codigo}", esperado="403",
                             reproducir=c.curl("GET", ruta))

        # P03-007 · la excepción declarada: bodega SÍ lee precio_unitario de los
        # detalles (valoriza el kardex bajo su rol). Se comprueba que siga
        # siendo así, porque si desaparece se rompe la recepción sin aviso.
        precio_detalle = entero("""
            SELECT count(*) FROM pg_attribute a
            JOIN pg_class c ON c.oid=a.attrelid
            JOIN pg_namespace n ON n.oid=c.relnamespace
            WHERE n.nspname='public' AND c.relname='pedido_detalle'
              AND a.attname='precio_unitario' AND a.attacl IS NOT NULL
              AND EXISTS (SELECT 1 FROM aclexplode(a.attacl) x
                          JOIN pg_roles r ON r.oid=x.grantee
                          WHERE r.rolname='grp_bodega' AND x.privilege_type='SELECT')""")
        reg.caso("P03-007", "Se conserva la excepción: grp_bodega lee precio_unitario del detalle",
                 condicion=precio_detalle == 1, severidad="S3",
                 observado=f"{precio_detalle} concesión",
                 esperado="1 — es deliberada (valoriza el kardex); la UI no lo muestra")

    # ─────────────────────────────────────────────────────────────────────────
    # P03-001 · todo acceso a Postgres dentro de @Transactional
    # ─────────────────────────────────────────────────────────────────────────
    # Fuera de transacción no hay `set_config('role', …)`, así que la consulta
    # corre como `retailmind_app` —sin privilegios de negocio— y o falla, o
    # (peor) se salta la seguridad de motor. Es análisis estático: se buscan
    # métodos públicos que usen pgJdbcTemplate sin @Transactional encima.
    reg.anotar(_revisar_transaccional(reg))

    # ─────────────────────────────────────────────────────────────────────────
    # P03-016 · ningún secreto en el índice de git
    # ─────────────────────────────────────────────────────────────────────────
    for archivo in (".env", "retailmind/.env", "deploy/secrets/pg_superuser.txt",
                    "retailmind-backend/application-local.properties"):
        proc = subprocess.run(["git", "ls-files", "--error-unmatch", archivo],
                              capture_output=True, text=True, cwd=RAIZ)
        reg.caso("P03-016", f"{archivo} fuera del índice de git",
                 condicion=proc.returncode != 0, severidad="S1",
                 observado="RASTREADO por git" if proc.returncode == 0 else "no rastreado",
                 esperado="no rastreado")

    return reg


import re as _re


def _cuerpo_del_metodo(texto: str, inicio: int) -> str:
    """
    Devuelve el cuerpo del método que empieza en `inicio`, emparejando llaves.

    Hace falta de verdad: una ventana de N líneas se mete en el método
    SIGUIENTE, y entonces un método inocente hereda el `pg.query` del de abajo.
    La primera versión de esta comprobación acusaba a 34 métodos por eso.
    """
    abre = texto.find("{", inicio)
    if abre < 0:
        return ""
    nivel, i = 0, abre
    while i < len(texto):
        if texto[i] == "{":
            nivel += 1
        elif texto[i] == "}":
            nivel -= 1
            if nivel == 0:
                return texto[abre:i + 1]
        i += 1
    return texto[abre:]


def _revisar_transaccional(reg: Registro):
    """
    Servicios que tocan `pgJdbcTemplate` en métodos públicos sin @Transactional.

    Se descartan, porque no son accesos a datos y falsean el recuento:
      · CONSTRUCTORES (el nombre del método coincide con el de la clase)
      · `record` / `class` / `interface` / `enum` anidados
      · métodos con @Transactional heredado a nivel de CLASE
      · el paquete `analytics/`, excluido del aspecto por diseño
    """
    from comun.arnes import Resultado

    fuente = RAIZ / "retailmind-backend" / "src" / "main" / "java"
    usos = ("pg.query", "pg.update", "pg.execute", "pg.batchUpdate", "pg.queryFor")
    firma = _re.compile(
        r'^[ \t]*public\s+(?:static\s+|final\s+|synchronized\s+)*'
        r'(?!record\b|class\b|interface\b|enum\b)'
        r'[\w<>,\[\]\.\?\s]+?\s+(\w+)\s*\(', _re.M)

    sospechosos: list[str] = []
    for archivo in sorted(fuente.rglob("*.java")):
        ruta = archivo.as_posix()
        if "/analytics/" in ruta:
            continue
        texto = archivo.read_text(encoding="utf-8", errors="replace")
        if "pgJdbcTemplate" not in texto and "JdbcTemplate pg" not in texto:
            continue

        clase = archivo.stem
        # @Transactional a nivel de clase: cubre todos sus métodos.
        cabecera_clase = texto[:texto.find(f"class {clase}")] if f"class {clase}" in texto else ""
        if "@Transactional" in cabecera_clase:
            continue

        for m in firma.finditer(texto):
            nombre = m.group(1)
            if nombre == clase:            # constructor
                continue

            # Helper ESTÁTICO que recibe el JdbcTemplate por parámetro
            # (`Paginacion.paginar`): corre dentro de la transacción de QUIEN LO
            # LLAMA, y anotarlo no haría nada — Spring no proxea métodos
            # estáticos. Exigirle @Transactional sería exigir un adorno inerte.
            declaracion = texto[m.start():texto.find(")", m.end()) + 1]
            if "static" in m.group(0) and "JdbcTemplate" in declaracion:
                continue

            cuerpo = _cuerpo_del_metodo(texto, m.end())
            if not any(t in cuerpo for t in usos):
                continue
            # Anotaciones inmediatamente anteriores: se camina hacia atrás
            # saltando comentarios y otras anotaciones.
            previo = texto[:m.start()]
            lineas_previas = previo.splitlines()
            anotaciones = []
            for l in reversed(lineas_previas):
                s = l.strip()
                if not s or s.startswith(("//", "*", "/*", "@")):
                    anotaciones.append(s)
                    if s.startswith("@Transactional"):
                        break
                else:
                    break
            if not any(a.startswith("@Transactional") for a in anotaciones):
                linea = previo.count("\n") + 1
                sospechosos.append(f"{archivo.relative_to(fuente).as_posix()}:{linea} {nombre}()")

    return Resultado(
        caso="P03-001", titulo="Todo acceso a PostgreSQL dentro de @Transactional",
        estado_datos=reg.estado_datos,
        veredicto="PASA" if not sospechosos else "FALLA",
        severidad="" if not sospechosos else "S2",
        observado=(f"{len(sospechosos)} métodos: " + "; ".join(sospechosos[:10])
                   if sospechosos else "0 métodos sin transacción"),
        esperado="0 — sin transacción no hay set_config('role'), y la consulta "
                 "corre sin privilegios o se salta la seguridad de motor",
    )


if __name__ == "__main__":
    estado = sys.argv[1] if len(sys.argv) > 1 else "E3"
    reg = correr(estado)
    print("\n" + "=" * 70)
    print(reg.resumen())
    print("informe:", reg.volcar("p03_motor"))
    sys.exit(1 if reg.fallos else 0)
