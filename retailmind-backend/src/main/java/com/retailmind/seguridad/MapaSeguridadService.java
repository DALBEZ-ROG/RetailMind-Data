package com.retailmind.seguridad;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mapa REAL de la seguridad del motor: roles, usuarios, privilegios de tabla y
 * de columna, políticas RLS y ventana horaria.
 *
 * <h2>De dónde sale el dato (y de dónde NO)</h2>
 *
 * Todo se lee de los CATÁLOGOS de PostgreSQL. Las tablas {@code permiso} y
 * {@code rol_permiso} del esquema están VACÍAS y son vestigiales: el control de
 * acceso de este sistema lo hace el motor, así que preguntarle a la aplicación
 * qué puede hacer cada rol daría una respuesta inventada.
 *
 * <h2>Dos trampas de catálogo que decidieron las consultas</h2>
 *
 * <b>1. {@code information_schema} MIENTE por debajo, y en silencio.</b> Sus
 * vistas filtran por {@code pg_has_role(...)}: solo muestran los privilegios de
 * roles de los que el usuario actual es miembro. Medido en esta base, el mismo
 * {@code SELECT count(*) FROM information_schema.role_table_grants} devuelve
 * <b>1.354</b> como superusuario y <b>738</b> bajo {@code grp_administrador},
 * que es el rol con el que corre esta pantalla. No da error: da la mitad. Por
 * eso todo sale de {@code pg_catalog} con {@code aclexplode()}, que no filtra.
 *
 * <b>2. Los privilegios de COLUMNA se leen de {@code pg_attribute.attacl}</b> y
 * jamás de {@code information_schema.column_privileges}: esta última EXPANDE a
 * cada columna el privilegio heredado de la tabla y devuelve miles de filas
 * donde el sistema tiene 109 excepciones reales. La segregación financiera
 * —Bodega y Despacho sin columnas de dinero— vive justo ahí y quedaría
 * enterrada bajo el ruido.
 *
 * <h2>Por qué NO hace falta SECURITY DEFINER para leer</h2>
 *
 * {@code pg_catalog} es legible por PUBLIC y el script 19 solo revocó USAGE
 * sobre el esquema {@code public}. Verificado bajo {@code SET ROLE
 * grp_administrador}: 9 roles de grupo, 95 políticas, 109 columnas con ACL y
 * 1.467 entradas de ACL de tabla, <b>idénticos</b> a los del superusuario. La
 * función SECURITY DEFINER del script 86 hace falta solo para ESCRIBIR
 * (ver {@link PermisosMotorService}).
 */
@Service
public class MapaSeguridadService {

    /**
     * Los 7 privilegios de tabla del estándar SQL. `MAINTAIN` (PostgreSQL 17+)
     * se cuenta aparte a propósito: {@code information_schema} no lo modela, y
     * es justo la diferencia entre las 1.467 entradas del ACL y las <b>1.354</b>
     * documentadas en {@code docs/DESPLIEGUE_EJECUTADO.md} (113 tablas × 1).
     * Sin esta separación, la pantalla contradiría a la bitácora del despliegue
     * sin que ninguna de las dos estuviera equivocada.
     */
    private static final String PRIV_ESTANDAR =
            "'SELECT','INSERT','UPDATE','DELETE','TRUNCATE','REFERENCES','TRIGGER'";

    /** Filtros aceptados por tipo de permiso (lista blanca; nunca se concatena). */
    private static final List<String> TIPOS = List.of("tabla", "columna");

    /** Privilegios que la pantalla acepta como filtro. */
    private static final List<String> PRIVILEGIOS = List.of(
            "SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER", "MAINTAIN");

    /**
     * Traducción de cada política RLS a lenguaje comprensible. La clave es el
     * NOMBRE de la política, que en esta base es un conjunto cerrado de 13
     * valores. El texto explica la regla; los "ingredientes" de la condición se
     * detectan aparte sobre la expresión real, para que la pantalla no dependa
     * solo de un nombre que alguien podría cambiar.
     */
    private static final Map<String, String> EXPLICACION_POLITICA = Map.ofEntries(
            Map.entry("pol_horario",
                    "Solo deja pasar dentro de la ventana horaria del rol (esta_en_horario). "
                    + "Está declarada con cmd = ALL, y ALL incluye SELECT: fuera de hora no "
                    + "devuelve un error, devuelve CERO FILAS."),
            Map.entry("pol_cliente_propio",
                    "Aísla al cliente a SUS propios registros, comparando contra el "
                    + "app.cliente_id que la aplicación fija por transacción."),
            Map.entry("pol_soporte",
                    "Deja al agente de soporte ver y gestionar los casos que le corresponden."),
            Map.entry("pol_cliente_pago",
                    "El cliente solo puede registrar el pago de un pedido suyo."),
            Map.entry("pol_cliente_emision",
                    "El cliente solo puede emitir la factura de su propio pedido "
                    + "(es la que nace automáticamente al pagar el checkout online)."),
            Map.entry("pol_cliente_checkout",
                    "El cliente solo puede escribir sobre el pedido que está pagando."),
            Map.entry("pol_cliente_uso",
                    "El cliente solo puede registrar el canje de un cupón en su propio pedido."),
            Map.entry("pol_cliente_lectura",
                    "Lectura abierta al cliente, sujeta a su ventana horaria."),
            Map.entry("pol_resena_publica",
                    "Solo son visibles las reseñas ya aprobadas por moderación."),
            Map.entry("pol_cliente_activo",
                    "El cliente solo ve los cupones vigentes o los que él mismo ya usó."),
            Map.entry("pol_cliente_tienda",
                    "Catálogo visible en la tienda del cliente."));

    private final JdbcTemplate pg;

    public MapaSeguridadService(@Qualifier("pgJdbcTemplate") JdbcTemplate pg) {
        this.pg = pg;
    }

    // ── Bloques 1, 2 y 5: roles, usuarios por rol y ventana horaria ──────────

    /**
     * El mapa completo salvo permisos y políticas, que van por su endpoint
     * porque se filtran y son miles de filas.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> mapa() {
        Map<String, Object> sobre = new LinkedHashMap<>();
        sobre.put("resumen", resumen());
        sobre.put("roles", roles());
        sobre.put("usuarios", usuariosPorRol());
        sobre.put("horarios", horarios());
        sobre.put("protegidos", PermisosMotorService.reglasProtegidas());
        return sobre;
    }

    /** Cifras de portada, todas contrastables contra el motor. */
    private Map<String, Object> resumen() {
        return pg.queryForMap("""
                SELECT
                  (SELECT count(*) FROM pg_roles
                    WHERE rolname LIKE 'grp\\_%%')                        AS roles_grupo,
                  (SELECT count(*) FROM pg_roles
                    WHERE rolname NOT LIKE 'pg\\_%%' AND rolcanlogin)     AS roles_login,
                  (SELECT count(*) FROM pg_policies
                    WHERE schemaname = 'public')                         AS politicas_rls,
                  (SELECT count(*) FROM pg_class c
                     JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'public' AND c.relrowsecurity)      AS tablas_con_rls,
                  (SELECT count(*) FROM pg_attribute a
                     JOIN pg_class c ON c.oid = a.attrelid
                     JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'public' AND a.attacl IS NOT NULL
                      AND a.attnum > 0 AND NOT a.attisdropped)            AS columnas_con_acl,
                  (SELECT count(DISTINCT c.relname) FROM pg_attribute a
                     JOIN pg_class c ON c.oid = a.attrelid
                     JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'public' AND a.attacl IS NOT NULL
                      AND a.attnum > 0 AND NOT a.attisdropped)            AS tablas_con_acl_columna,
                  (SELECT count(*) FROM pg_class c
                     JOIN pg_namespace n ON n.oid = c.relnamespace,
                          aclexplode(c.relacl) ae
                     JOIN pg_roles g ON g.oid = ae.grantee
                    WHERE n.nspname = 'public' AND c.relkind = 'r'
                      AND g.rolname LIKE 'grp\\_%%'
                      AND ae.privilege_type IN (%s))                      AS grants_tabla,
                  (SELECT count(*) FROM pg_class c
                     JOIN pg_namespace n ON n.oid = c.relnamespace,
                          aclexplode(c.relacl) ae
                     JOIN pg_roles g ON g.oid = ae.grantee
                    WHERE n.nspname = 'public' AND c.relkind = 'r'
                      AND g.rolname LIKE 'grp\\_%%'
                      AND ae.privilege_type = 'MAINTAIN')                 AS grants_maintain,
                  (SELECT count(*) FROM pg_class c
                     JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'public' AND c.relkind = 'r')       AS tablas_totales,
                  (SELECT count(*) FROM usuario WHERE activo)             AS usuarios_activos
                """.formatted(PRIV_ESTANDAR));
    }

    /**
     * Bloque 1 — los 9 grupos y los roles de sistema, con lo que cuelga de cada
     * uno: usuarios de la aplicación, privilegios, columnas con excepción y
     * políticas que lo nombran.
     */
    private List<Map<String, Object>> roles() {
        return pg.queryForList("""
                WITH mapa_rol(codigo, rol_motor) AS (
                    -- Los 9 del sistema son un mapeo FIJO (espeja el enum
                    -- DbGroupRole), y los personalizados salen de la tabla del
                    -- script 87. Sin el UNION, un rol creado desde la pantalla
                    -- aparecería con 0 usuarios aunque tuviera varios.
                    VALUES ('ADMIN','grp_administrador'), ('GERENTE','grp_gerente'),
                           ('VENDEDOR','grp_vendedor'),   ('COMPRAS','grp_compras'),
                           ('BODEGA','grp_bodega'),       ('DESPACHO','grp_despacho'),
                           ('CLIENTE','grp_cliente'),     ('ANALISTA','grp_analista'),
                           ('SOPORTE','grp_soporte')
                    UNION ALL
                    SELECT r.codigo, rp.rol_grupo
                    FROM rol r JOIN rol_personalizado rp ON rp.rol_id = r.id
                ),
                gr_tabla AS (
                    SELECT g.rolname, count(*) AS n
                    FROM pg_class c
                    JOIN pg_namespace n ON n.oid = c.relnamespace,
                         aclexplode(c.relacl) ae
                    JOIN pg_roles g ON g.oid = ae.grantee
                    WHERE n.nspname = 'public' AND c.relkind = 'r'
                      AND ae.privilege_type IN (%s)
                    GROUP BY 1
                ),
                gr_col AS (
                    SELECT g.rolname, count(*) AS n
                    FROM pg_attribute a
                    JOIN pg_class c ON c.oid = a.attrelid
                    JOIN pg_namespace n ON n.oid = c.relnamespace,
                         aclexplode(a.attacl) ae
                    JOIN pg_roles g ON g.oid = ae.grantee
                    WHERE n.nspname = 'public' AND a.attnum > 0 AND NOT a.attisdropped
                    GROUP BY 1
                ),
                pol AS (
                    SELECT r AS rolname, count(*) AS n
                    FROM pg_policies p, unnest(p.roles) AS r
                    WHERE p.schemaname = 'public'
                    GROUP BY 1
                ),
                usr AS (
                    SELECT m.rol_motor, count(*) FILTER (WHERE u.activo) AS activos, count(*) AS total
                    FROM usuario u
                    JOIN usuario_rol ur ON ur.usuario_id = u.id
                    JOIN rol ro ON ro.id = ur.rol_id
                    JOIN mapa_rol m ON m.codigo = ro.codigo
                    GROUP BY 1
                )
                SELECT r.rolname                              AS rol_motor,
                       mr.codigo                              AS rol_app,
                       CASE WHEN r.rolname LIKE 'grp\\_%%' THEN 'grupo'
                            WHEN r.rolsuper                THEN 'superusuario'
                            ELSE 'servicio' END               AS clase,
                       r.rolcanlogin                          AS puede_login,
                       r.rolbypassrls                         AS bypass_rls,
                       r.rolsuper                             AS es_superusuario,
                       COALESCE(u.activos, 0)                 AS usuarios_activos,
                       COALESCE(u.total, 0)                   AS usuarios_total,
                       COALESCE(gt.n, 0)                      AS permisos_tabla,
                       COALESCE(gc.n, 0)                      AS permisos_columna,
                       COALESCE(po.n, 0)                      AS politicas,
                       -- DOS medidas distintas que es fácil confundir:
                       -- `miembros_motor` = quién es miembro DE este rol (cada
                       -- grp_* tiene 1: retailmind_app). `pertenece_a` = de
                       -- cuántos roles es miembro ESTE. La segunda es la que
                       -- importa: los 9 de `retailmind_app` son lo que hace
                       -- posible el SET LOCAL ROLE, y sin ellos la aplicación
                       -- entera responde 403 (por eso están protegidos, R4).
                       (SELECT count(*) FROM pg_auth_members am
                         WHERE am.roleid = r.oid)             AS miembros_motor,
                       (SELECT count(*) FROM pg_auth_members am
                         WHERE am.member = r.oid)             AS pertenece_a
                FROM pg_roles r
                LEFT JOIN mapa_rol mr ON mr.rol_motor = r.rolname
                LEFT JOIN gr_tabla gt ON gt.rolname   = r.rolname
                LEFT JOIN gr_col   gc ON gc.rolname   = r.rolname
                LEFT JOIN pol      po ON po.rolname   = r.rolname
                LEFT JOIN usr      u  ON u.rol_motor  = r.rolname
                WHERE r.rolname NOT LIKE 'pg\\_%%'
                ORDER BY (r.rolname LIKE 'grp\\_%%') DESC, r.rolname
                """.formatted(PRIV_ESTANDAR));
    }

    /** Bloque 2 — quién pertenece a qué. */
    private List<Map<String, Object>> usuariosPorRol() {
        return pg.queryForList("""
                SELECT u.id, u.nombre, u.apellido, u.email, u.activo,
                       ro.codigo AS rol_app, ro.nombre AS rol_nombre,
                       -- COALESCE y no CASE a secas: un rol PERSONALIZADO no
                       -- está en la lista fija y saldría con rol_motor NULL.
                       COALESCE(rp.rol_grupo,
                           CASE ro.codigo
                                WHEN 'ADMIN'    THEN 'grp_administrador'
                                WHEN 'GERENTE'  THEN 'grp_gerente'
                                WHEN 'VENDEDOR' THEN 'grp_vendedor'
                                WHEN 'COMPRAS'  THEN 'grp_compras'
                                WHEN 'BODEGA'   THEN 'grp_bodega'
                                WHEN 'DESPACHO' THEN 'grp_despacho'
                                WHEN 'CLIENTE'  THEN 'grp_cliente'
                                WHEN 'ANALISTA' THEN 'grp_analista'
                                WHEN 'SOPORTE'  THEN 'grp_soporte'
                           END) AS rol_motor
                FROM usuario u
                JOIN usuario_rol ur ON ur.usuario_id = u.id
                JOIN rol ro ON ro.id = ur.rol_id
                LEFT JOIN rol_personalizado rp ON rp.rol_id = ro.id
                ORDER BY ro.id, u.email""");
    }

    /**
     * Bloque 5 — la ventana horaria de cada rol por día, con el veredicto de
     * AHORA MISMO calculado por el propio motor.
     *
     * El «ahora» sale de {@code esta_en_horario()}, la MISMA función que
     * evalúan las 50 políticas {@code pol_horario}: preguntárselo a Java daría
     * una segunda opinión que podría discrepar de la que de verdad decide.
     */
    private List<Map<String, Object>> horarios() {
        return pg.queryForList("""
                SELECT h.id, h.rol_grupo, h.dia_semana,
                       to_char(h.hora_inicio, 'HH24:MI') AS hora_inicio,
                       to_char(h.hora_fin,    'HH24:MI') AS hora_fin,
                       h.activo,
                       (h.dia_semana = EXTRACT(DOW FROM now() AT TIME ZONE 'America/Guayaquil')::int)
                                                         AS es_hoy,
                       esta_en_horario(h.rol_grupo)      AS dentro_ahora
                FROM grupo_horario h
                ORDER BY h.rol_grupo, h.dia_semana, h.hora_inicio""");
    }

    // ── Bloque 3: permisos de TABLA y de COLUMNA ────────────────────────────

    /**
     * Privilegios vigentes, separados en dos listas porque son cosas
     * distintas: el de TABLA alcanza toda la fila; el de COLUMNA es la
     * excepción quirúrgica que implementa la segregación financiera.
     *
     * Los filtros usan la guarda NULL por parámetro —{@code (?::text IS NULL OR
     * col = ?::text)} con el valor pasado DOS veces— igual que los informes
     * tácticos. Nada se concatena.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> permisos(String rol, String tabla, String privilegio, String tipo) {
        String priv = normalizar(privilegio, PRIVILEGIOS, "privilegio");
        String tip = normalizarMinuscula(tipo, TIPOS, "tipo");

        List<Map<String, Object>> deTabla = List.of();
        List<Map<String, Object>> deColumna = List.of();

        if (tip == null || "tabla".equals(tip)) {
            deTabla = pg.queryForList("""
                    SELECT g.rolname            AS rol_motor,
                           c.relname            AS tabla,
                           ae.privilege_type    AS privilegio,
                           ae.is_grantable      AS transferible
                    FROM pg_class c
                    JOIN pg_namespace n ON n.oid = c.relnamespace,
                         aclexplode(c.relacl) ae
                    JOIN pg_roles g ON g.oid = ae.grantee
                    WHERE n.nspname = 'public' AND c.relkind = 'r'
                      AND g.rolname LIKE 'grp\\_%'
                      AND (?::text IS NULL OR g.rolname = ?::text)
                      AND (?::text IS NULL OR c.relname = ?::text)
                      AND (?::text IS NULL OR ae.privilege_type = ?::text)
                    ORDER BY c.relname, g.rolname, ae.privilege_type""",
                    rol, rol, tabla, tabla, priv, priv);
        }

        if (tip == null || "columna".equals(tip)) {
            deColumna = pg.queryForList("""
                    SELECT g.rolname            AS rol_motor,
                           c.relname            AS tabla,
                           a.attname            AS columna,
                           ae.privilege_type    AS privilegio,
                           -- La trampa semántica de los privilegios de columna:
                           -- si el rol YA tiene el privilegio a nivel de tabla,
                           -- esta entrada no restringe nada (los de columna solo
                           -- SUMAN). Se marca para que la pantalla no presente
                           -- como excepción algo que el motor ignora.
                           has_table_privilege(g.rolname, c.oid, ae.privilege_type)
                                                AS cubierto_por_tabla
                    FROM pg_attribute a
                    JOIN pg_class c ON c.oid = a.attrelid
                    JOIN pg_namespace n ON n.oid = c.relnamespace,
                         aclexplode(a.attacl) ae
                    JOIN pg_roles g ON g.oid = ae.grantee
                    WHERE n.nspname = 'public' AND a.attnum > 0 AND NOT a.attisdropped
                      AND g.rolname LIKE 'grp\\_%'
                      AND (?::text IS NULL OR g.rolname = ?::text)
                      AND (?::text IS NULL OR c.relname = ?::text)
                      AND (?::text IS NULL OR ae.privilege_type = ?::text)
                    ORDER BY c.relname, a.attname, g.rolname, ae.privilege_type""",
                    rol, rol, tabla, tabla, priv, priv);
        }

        Map<String, Object> sobre = new LinkedHashMap<>();
        sobre.put("tabla", deTabla);
        sobre.put("columna", deColumna);
        sobre.put("totalTabla", deTabla.size());
        sobre.put("totalColumna", deColumna.size());
        // El desglose evita una contradicción aparente entre la portada y la
        // lista: el KPI publica 1.354 (los 7 privilegios del estándar SQL, la
        // cifra de docs/DESPLIEGUE_EJECUTADO.md) y la lista trae 1.467, porque
        // MAINTAIN existe en el motor desde PostgreSQL 17 y information_schema
        // no lo modela. Las dos cifras son correctas; sin este desglose una de
        // las dos parece un error.
        sobre.put("totalTablaEstandar",
                deTabla.stream().filter(f -> !"MAINTAIN".equals(f.get("privilegio"))).count());
        sobre.put("totalTablaMaintain",
                deTabla.stream().filter(f -> "MAINTAIN".equals(f.get("privilegio"))).count());
        return sobre;
    }

    // ── Bloque 4: políticas RLS ─────────────────────────────────────────────

    /**
     * Las políticas, con su condición traducida. La explicación sale del NOMBRE
     * (conjunto cerrado de 13) y los «ingredientes» se detectan sobre la
     * EXPRESIÓN real, para no fiarse solo del nombre.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> politicas(String tabla, String rol) {
        List<Map<String, Object>> crudas = pg.queryForList("""
                SELECT p.tablename                       AS tabla,
                       p.policyname                      AS politica,
                       p.cmd                             AS comando,
                       p.permissive                      AS tipo,
                       array_to_string(p.roles, ', ')    AS roles,
                       p.qual                            AS condicion_lectura,
                       p.with_check                      AS condicion_escritura
                FROM pg_policies p
                WHERE p.schemaname = 'public'
                  AND (?::text IS NULL OR p.tablename = ?::text)
                  AND (?::text IS NULL OR ?::text = ANY (p.roles))
                ORDER BY p.tablename, p.policyname""",
                tabla, tabla, rol, rol);

        List<Map<String, Object>> salida = new ArrayList<>(crudas.size());
        for (Map<String, Object> p : crudas) {
            Map<String, Object> fila = new LinkedHashMap<>(p);
            String nombre = String.valueOf(p.get("politica"));
            String expr = texto(p.get("condicion_lectura")) + " " + texto(p.get("condicion_escritura"));

            fila.put("explicacion", EXPLICACION_POLITICA.getOrDefault(nombre,
                    "Política sin traducción registrada: leer la condición."));
            fila.put("restringePorHorario", expr.contains("esta_en_horario"));
            fila.put("restringePorCliente", expr.contains("fn_cliente_actual")
                    || expr.contains("app.cliente_id"));
            fila.put("usaSubconsulta", expr.contains("SELECT"));
            salida.add(fila);
        }
        return salida;
    }

    // ── Catálogo de objetos para el formulario de GRANT/REVOKE ──────────────

    /**
     * Tablas administrables y sus columnas. Excluye las PROTEGIDAS: si no se
     * pueden tocar, ofrecerlas en el desplegable solo produce un rechazo
     * evitable — la guardia del backend sigue viva para quien llame por API.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> objetos() {
        return pg.queryForList("""
                SELECT c.relname AS tabla,
                       -- Con RLS, conceder SELECT NO basta: sin una política
                       -- que nombre al rol, el motor devuelve CERO FILAS sin
                       -- error. La pantalla lo avisa por tabla.
                       c.relrowsecurity AS con_rls,
                       -- string_agg y NO array_agg: un text[] llega al JSON como
                       -- un java.sql.Array que Jackson no sabe serializar (500
                       -- limpio en /objetos la primera vez). La pantalla parte
                       -- por coma, que además es más barato que un array anidado.
                       string_agg(a.attname, ',' ORDER BY a.attnum) AS columnas
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                JOIN pg_attribute a ON a.attrelid = c.oid
                WHERE n.nspname = 'public' AND c.relkind = 'r'
                  AND a.attnum > 0 AND NOT a.attisdropped
                  AND NOT (c.relname = ANY (string_to_array(?::text, ',')))
                GROUP BY c.relname, c.relrowsecurity
                ORDER BY c.relname""",
                PermisosMotorService.tablasProtegidasCsv());
    }

    // ── Utilidades ──────────────────────────────────────────────────────────

    private static String texto(Object o) {
        return o == null ? "" : o.toString();
    }

    /** Lista blanca en MAYÚSCULA (privilegios). Vacío = sin filtro. */
    private static String normalizar(String valor, List<String> permitidos, String campo) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        String v = valor.trim().toUpperCase();
        if (!permitidos.contains(v)) {
            throw new IllegalArgumentException(
                    "Valor no permitido para " + campo + ": " + valor);
        }
        return v;
    }

    /** Lista blanca en minúscula (tipo). Vacío = sin filtro. */
    private static String normalizarMinuscula(String valor, List<String> permitidos, String campo) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        String v = valor.trim().toLowerCase();
        if (!permitidos.contains(v)) {
            throw new IllegalArgumentException(
                    "Valor no permitido para " + campo + ": " + valor);
        }
        return v;
    }
}
