package com.retailmind.seguridad;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.retailmind.auditoria.AuditoriaService;

/**
 * GRANT y REVOKE en vivo sobre tablas y columnas, para los 9 roles de grupo.
 *
 * <h2>Por qué esto no puede ser un {@code pg.execute("GRANT ...")}</h2>
 *
 * Dos razones, y la segunda es la peligrosa:
 *
 * <ol>
 *   <li>La app conecta como {@code retailmind_app} y asume el rol del usuario
 *       ({@code SET LOCAL ROLE grp_administrador}). Las 110 tablas son de
 *       {@code postgres}, y solo el propietario puede otorgar sobre ellas.</li>
 *   <li><b>Un GRANT ejecutado por quien no es propietario NO FALLA.</b> Emite
 *       un {@code WARNING: no privileges were granted} y no hace nada. Medido
 *       en este sistema antes de escribir esta clase. Sin la función del script
 *       86, esta pantalla respondería 200 a cada clic sin cambiar el motor.</li>
 * </ol>
 *
 * Por eso todo pasa por {@code fn_admin_cambiar_permiso} (script 86, SECURITY
 * DEFINER, EXECUTE solo para {@code grp_administrador}), que valida contra el
 * catálogo real, ejecuta con identificadores citados por {@code format('%I')} y
 * <b>devuelve el privilegio efectivo ANTES y DESPUÉS</b>. Si no cambió, esta
 * clase lo dice; nunca afirma un cambio que el motor no confirmó.
 *
 * <h2>La lista de protegidos está en los DOS lados</h2>
 *
 * Aquí y dentro de la función. Esta copia existe para dar un mensaje útil —el
 * handler global convierte el 42501 del motor en un «Acceso denegado» genérico
 * que no explicaría nada—; la que MANDA es la del motor, porque es la que sigue
 * en pie si alguien llama a la función por fuera de la aplicación.
 */
@Service
public class PermisosMotorService {

    /** Los 9 roles administrables. Lista BLANCA: nadie más es destinatario. */
    private static final List<String> ROLES_DESTINO = List.of(
            "grp_gerente", "grp_vendedor", "grp_compras", "grp_bodega",
            "grp_despacho", "grp_cliente", "grp_analista", "grp_soporte",
            "grp_administrador");

    /**
     * R1 — El rol del propio administrador. Se acepta como destinatario en la
     * lista de arriba (la pantalla lo LISTA y muestra sus permisos) pero
     * ninguna operación lo modifica.
     */
    private static final String ROL_INTOCABLE = "grp_administrador";

    /**
     * R2a — Núcleo de IDENTIDAD: ni GRANT ni REVOKE. Un GRANT aquí es escalada
     * de privilegio ({@code usuario} lleva {@code password_hash}) y un REVOKE
     * rompe el reparto de roles.
     */
    private static final List<String> TABLAS_IDENTIDAD = List.of(
            "usuario", "usuario_rol", "rol", "permiso", "rol_permiso");

    /**
     * R2b — RASTRO y COMPUERTA: revocar NUNCA, conceder lo seguro SÍ.
     *
     * La primera versión bloqueaba las dos direcciones y se vio al clonar un
     * rol: {@code grp_vendedor} tiene {@code INSERT} sobre {@code log_auditoria}
     * y {@code SELECT} sobre {@code grupo_horario}, así que un rol nuevo se
     * quedaba SIN poder ejecutar ninguna acción auditada. Prohibir ese GRANT no
     * protegía nada — el peligro es el REVOKE, que ciega el control.
     */
    private static final List<String> TABLAS_RASTRO = List.of("log_auditoria", "log_acceso");
    private static final String TABLA_COMPUERTA = "grupo_horario";

    /** Todo lo que la pantalla no ofrece en el desplegable de alta. */
    private static final List<String> TABLAS_PROTEGIDAS = List.of(
            "usuario", "usuario_rol", "rol", "permiso", "rol_permiso");

    /**
     * R3 — Los roles que NO son destinatarios posibles, con el motivo. No hace
     * falta prohibirlos (la lista blanca ya los deja fuera), pero se enumeran
     * para poder devolver el motivo REAL en vez de un «rol desconocido».
     */
    private static final Map<String, String> ROLES_NO_ADMINISTRABLES = Map.of(
            "retailmind_app",
            "es el rol con el que la aplicación se conecta. Sus 10 privilegios "
            + "(usuario, usuario_rol, rol y 2 secuencias) son los que usa el LOGIN, antes "
            + "de que exista rol que asumir: sin ellos NADIE vuelve a entrar al sistema.",
            "retailmind_etl",
            "es el rol de lectura del pipeline. Quitarle un SELECT no da error visible "
            + "hasta la carga de las 02:00, y entonces el almacén se publica incompleto o "
            + "el informe compuesto sale vacío sin un solo mensaje de error.",
            "postgres",
            "es el superusuario propietario de las 110 tablas: es quien concede, no quien "
            + "recibe.",
            "PUBLIC",
            "no es un rol de negocio; conceder a PUBLIC daría el privilegio a todos.");

    /** R4 — Privilegios administrables desde la pantalla. */
    private static final List<String> PRIVILEGIOS_TABLA =
            List.of("SELECT", "INSERT", "UPDATE", "DELETE");

    /** DELETE no existe a nivel de columna: se borra la fila entera. */
    private static final List<String> PRIVILEGIOS_COLUMNA =
            List.of("SELECT", "INSERT", "UPDATE");

    private final JdbcTemplate pg;
    private final AuditoriaService auditoria;

    public PermisosMotorService(@Qualifier("pgJdbcTemplate") JdbcTemplate pg,
                                AuditoriaService auditoria) {
        this.pg = pg;
        this.auditoria = auditoria;
    }

    // ── Lo que la pantalla necesita saber de las protecciones ───────────────

    /** Las cuatro reglas, con su justificación, para pintarlas en la pantalla. */
    public static List<Map<String, Object>> reglasProtegidas() {
        return List.of(
                regla("R1", "El rol grp_administrador no se toca",
                        "Es el rol con el que opera quien usa esta pantalla. Revocarle un "
                        + "privilegio puede dejarlo sin leer usuario, grupo_horario o "
                        + "log_auditoria, que es justo lo que necesita para volver a entrar y "
                        + "deshacerlo. Conceder es además inútil: ya tiene ALL sobre las 113 "
                        + "relaciones.",
                        List.of(ROL_INTOCABLE)),
                regla("R2", "Núcleo de identidad, rastro y compuerta",
                        "IDENTIDAD (usuario, usuario_rol, rol, permiso, rol_permiso): ni "
                        + "conceder ni revocar. Contienen password_hash y el reparto de roles, "
                        + "así que aquí la dirección peligrosa es CONCEDER — sería escalada de "
                        + "privilegio, no un permiso más. RASTRO (log_auditoria, log_acceso) y "
                        + "COMPUERTA (grupo_horario): revocar NUNCA —cegar la auditoría o quitar "
                        + "la compuerta no le quita acceso a nadie, solo apaga el control—, pero "
                        + "conceder lo seguro SÍ: SELECT/INSERT en los registros y SELECT en el "
                        + "horario. Sin eso, un rol nuevo no podría ejecutar ninguna acción "
                        + "auditada y la prohibición no protegía nada.",
                        List.of("usuario", "usuario_rol", "rol", "permiso", "rol_permiso",
                                "log_auditoria", "log_acceso", "grupo_horario")),
                regla("R3", "Solo los 9 roles de grupo son destinatarios",
                        "Es una lista BLANCA. retailmind_app, retailmind_etl, postgres y PUBLIC "
                        + "no son destinatarios posibles: sus privilegios quedan protegidos por "
                        + "CONSTRUCCIÓN, no por enumeración.",
                        List.copyOf(ROLES_NO_ADMINISTRABLES.keySet())),
                regla("R4", "Solo privilegios de tabla y de columna",
                        "SELECT, INSERT, UPDATE y DELETE. Quedan fuera porque no hay parámetro "
                        + "que los exprese: USAGE ON SCHEMA public (sin él un rol deja de ver "
                        + "todo, y el script 19 se lo revocó a PUBLIC) y las MEMBRESÍAS de "
                        + "retailmind_app sobre los 9 grupos (sin ellas SET LOCAL ROLE falla y la "
                        + "aplicación entera responde 403). También TRUNCATE, por destructivo.",
                        List.of("USAGE ON SCHEMA", "membresías de rol", "TRUNCATE", "REFERENCES",
                                "TRIGGER", "MAINTAIN")));
    }

    private static Map<String, Object> regla(String id, String titulo, String porque,
                                             List<String> alcance) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("titulo", titulo);
        m.put("porque", porque);
        m.put("alcance", alcance);
        return m;
    }

    /** Para que el catálogo de objetos no ofrezca lo que no se puede tocar. */
    static String tablasProtegidasCsv() {
        return String.join(",", TABLAS_PROTEGIDAS);
    }

    // ── La operación ────────────────────────────────────────────────────────

    /**
     * Ejecuta un GRANT o un REVOKE y lo registra en {@code log_auditoria}.
     *
     * Va en una sola {@code @Transactional}: el aspecto pone el
     * {@code SET LOCAL ROLE grp_administrador} que la función exige para su
     * EXECUTE, y la fila de auditoría se confirma o se revierte junto con el
     * cambio de privilegio que documenta. Un permiso cambiado sin rastro —o un
     * rastro de algo que no llegó a pasar— serían las dos mitades del mismo
     * fallo.
     *
     * @param accion {@code conceder} o {@code revocar}
     * @return sobre con el efecto REAL medido por el motor
     */
    @Transactional
    public Map<String, Object> cambiar(String accion, String rol, String tabla,
                                       String columna, String privilegio) {
        String acc = exigir(accion, "accion").toLowerCase();
        if (!List.of("conceder", "revocar").contains(acc)) {
            throw new IllegalArgumentException(
                    "Acción no permitida: " + accion + ". Solo conceder o revocar.");
        }
        String rolDestino = exigir(rol, "rol");
        String tablaObj = exigir(tabla, "tabla");
        String priv = exigir(privilegio, "privilegio").toUpperCase();
        String col = (columna == null || columna.isBlank()) ? null : columna.trim();

        validarProtecciones(acc, rolDestino, tablaObj, priv, col);
        validarObjetoExiste(tablaObj, col);

        Map<String, Object> efecto = pg.queryForMap("""
                SELECT aplicado, antes, despues, sentencia
                FROM fn_admin_cambiar_permiso(?, ?, ?, ?, ?)""",
                acc, rolDestino, tablaObj, priv, col);

        boolean aplicado = Boolean.TRUE.equals(efecto.get("aplicado"));

        // Se audita SIEMPRE, aplicado o no: el intento de cambiar un privilegio
        // es en sí mismo un hecho que la auditoría de seguridad debe conservar.
        Map<String, Object> antes = new LinkedHashMap<>();
        antes.put("rol", rolDestino);
        antes.put("objeto", col == null ? tablaObj : tablaObj + "." + col);
        antes.put("privilegio", priv);
        antes.put("tenia", efecto.get("antes"));

        Map<String, Object> despues = new LinkedHashMap<>(antes);
        despues.put("tenia", efecto.get("despues"));
        despues.put("accion", acc);
        despues.put("sentencia", efecto.get("sentencia"));
        despues.put("aplicado", aplicado);

        auditoria.registrar("pg_privilegio", null, "OTRO", antes, despues);

        Map<String, Object> sobre = new LinkedHashMap<>(efecto);
        sobre.put("accion", acc);
        sobre.put("rol", rolDestino);
        sobre.put("tabla", tablaObj);
        sobre.put("columna", col);
        sobre.put("privilegio", priv);
        sobre.put("mensaje", mensajeEfecto(acc, aplicado, col != null,
                Boolean.TRUE.equals(efecto.get("despues"))));
        return sobre;
    }

    /**
     * Las cuatro reglas, aplicadas ANTES de viajar al motor.
     *
     * Se lanzan como {@link IllegalStateException} (409) y no como un 403,
     * porque el 403 del motor lo traduce el handler global a un texto genérico
     * y aquí lo importante es exactamente CUÁL de las protecciones saltó.
     */
    private void validarProtecciones(String accion, String rol, String tabla,
                                     String priv, String col) {
        boolean revocando = "revocar".equals(accion);
        // R3 — destinatario
        if (ROLES_NO_ADMINISTRABLES.containsKey(rol)) {
            throw new IllegalStateException(
                    "PERMISO PROTEGIDO. No se administran los privilegios de «" + rol
                    + "»: " + ROLES_NO_ADMINISTRABLES.get(rol));
        }
        // La lista blanca se resuelve contra el CATÁLOGO y no contra una lista
        // escrita: si no, los roles PERSONALIZADOS (script 87) —que es donde el
        // administrador debería experimentar en vez de tocar los 9 que
        // funcionan— serían los únicos a los que no se les podría conceder
        // nada. Los tres atributos juntos describen exactamente a un rol de
        // grupo y siguen dejando fuera a retailmind_app, retailmind_etl y
        // postgres. Es el MISMO criterio que aplica la función del script 86.
        if (!ROLES_DESTINO.contains(rol) && !esRolDeGrupo(rol)) {
            throw new IllegalArgumentException(
                    "Rol no administrable: " + rol + ". Solo roles de grupo grp_* "
                    + "(los 9 del sistema o los creados desde esta pantalla).");
        }

        // R1 — el rol del propio administrador
        if (ROL_INTOCABLE.equals(rol)) {
            throw new IllegalStateException(
                    "PERMISO PROTEGIDO. «grp_administrador» es el rol con el que estás "
                    + "operando: revocarle un privilegio puede dejarte sin poder entrar a "
                    + "deshacerlo, y concederle no cambia nada porque ya tiene ALL sobre "
                    + "todas las tablas.");
        }

        // R2a — núcleo de identidad: las dos direcciones
        if (TABLAS_IDENTIDAD.contains(tabla)) {
            throw new IllegalStateException(
                    "PERMISO PROTEGIDO. La tabla «" + tabla + "» es del núcleo de identidad "
                    + "del sistema (" + String.join(", ", TABLAS_IDENTIDAD) + "): contiene "
                    + "password_hash o el reparto de roles, así que no se administra desde "
                    + "aquí ni para conceder ni para revocar.");
        }

        // R2b — rastro y compuerta: revocar nunca; conceder, solo lo seguro
        boolean esRastro = TABLAS_RASTRO.contains(tabla);
        if (esRastro || TABLA_COMPUERTA.equals(tabla)) {
            if (revocando) {
                throw new IllegalStateException(
                        "PERMISO PROTEGIDO. No se revoca nada sobre «" + tabla + "». Cegar el "
                        + "rastro de auditoría o dejar a un rol sin compuerta horaria no le "
                        + "quita acceso a nadie: solo apaga el control. Conceder sí se puede.");
            }
            if (TABLA_COMPUERTA.equals(tabla) && !"SELECT".equals(priv)) {
                throw new IllegalStateException(
                        "Sobre «grupo_horario» solo se puede conceder SELECT: escribir en ella "
                        + "es mover la compuerta de acceso, y eso se hace en Horarios de Acceso.");
            }
            if (esRastro && !List.of("SELECT", "INSERT").contains(priv)) {
                throw new IllegalStateException(
                        "Sobre «" + tabla + "» solo se puede conceder SELECT o INSERT: es un "
                        + "registro de solo anexar, y UPDATE o DELETE permitirían reescribir "
                        + "el rastro.");
            }
        }

        // R4 — alcance
        List<String> permitidos = col == null ? PRIVILEGIOS_TABLA : PRIVILEGIOS_COLUMNA;
        if (!permitidos.contains(priv)) {
            throw new IllegalArgumentException(
                    "Privilegio no administrable" + (col == null ? "" : " a nivel de columna")
                    + ": " + priv + ". Permitidos: " + String.join(", ", permitidos)
                    + (col != null ? " (DELETE no existe por columna: se borra la fila entera)"
                                   : ""));
        }
    }

    /** ¿Es un rol de grupo real? NOLOGIN, sin BYPASSRLS y con prefijo grp_. */
    private boolean esRolDeGrupo(String rol) {
        Integer hay = pg.queryForObject("""
                SELECT count(*) FROM pg_roles
                WHERE rolname = ? AND rolname LIKE 'grp\\_%'
                  AND NOT rolcanlogin AND NOT rolbypassrls AND NOT rolsuper""",
                Integer.class, rol);
        return hay != null && hay > 0;
    }

    /**
     * El objeto tiene que EXISTIR en el catálogo antes de viajar a la función.
     *
     * La función también lo comprueba —es su tercera validación— pero lanza
     * {@code 42P01}, un SQLState que el handler global no traduce y que sale
     * como un <b>500</b>. Detectado probando la inyección
     * {@code marca; DROP TABLE marca}: el ataque estaba bloqueado (el
     * identificador va citado con {@code %I} y esa tabla no existe), pero la
     * respuesta era un error interno en vez de un «no existe esa tabla». Un 500
     * en una pantalla de seguridad se lee como un fallo del sistema, no como un
     * dato mal escrito.
     */
    private void validarObjetoExiste(String tabla, String columna) {
        Integer hay = pg.queryForObject("""
                SELECT count(*) FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relname = ? AND c.relkind = 'r'""",
                Integer.class, tabla);
        if (hay == null || hay == 0) {
            throw new IllegalArgumentException(
                    "No existe la tabla «" + tabla + "» en el esquema public.");
        }
        if (columna != null) {
            Integer hayCol = pg.queryForObject("""
                    SELECT count(*) FROM pg_attribute a
                    JOIN pg_class c ON c.oid = a.attrelid
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'public' AND c.relname = ? AND a.attname = ?
                      AND a.attnum > 0 AND NOT a.attisdropped""",
                    Integer.class, tabla, columna);
            if (hayCol == null || hayCol == 0) {
                throw new IllegalArgumentException(
                        "No existe la columna «" + tabla + "." + columna + "».");
            }
        }
    }

    /**
     * El mensaje distingue los tres desenlaces que el motor separa y la
     * pantalla tiene que contar distinto:
     * cambió · no cambió porque ya estaba así · no cambió porque el privilegio
     * de TABLA tapa al de columna.
     */
    private static String mensajeEfecto(String accion, boolean aplicado,
                                        boolean esColumna, boolean tieneAhora) {
        if (aplicado) {
            return "conceder".equals(accion)
                    ? "Privilegio concedido y verificado en el motor."
                    : "Privilegio revocado y verificado en el motor.";
        }
        if ("conceder".equals(accion)) {
            return "Sin cambios: el rol ya tenía ese privilegio.";
        }
        if (esColumna && tieneAhora) {
            return "Sin cambios EFECTIVOS: se quitó la entrada de columna, pero el rol "
                 + "conserva el privilegio a nivel de TABLA, que la incluye. Los permisos de "
                 + "columna solo SUMAN: para restringir una columna hay que revocar primero el "
                 + "privilegio de la tabla y conceder después las columnas permitidas.";
        }
        return "Sin cambios: el rol no tenía ese privilegio.";
    }

    private static String exigir(String valor, String campo) {
        if (valor == null || valor.isBlank()) {
            throw new IllegalArgumentException("El campo «" + campo + "» es requerido");
        }
        return valor.trim();
    }
}
