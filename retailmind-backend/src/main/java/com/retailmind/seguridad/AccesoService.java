package com.retailmind.seguridad;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.retailmind.security.DbGroupRole;

/**
 * Registro y consulta de intentos de acceso al sistema (log_acceso,
 * OTD-GER-09, script 53).
 *
 * ESCRITURA — {@link #registrar}: la invoca el flujo de login. Un intento
 * FALLIDO ocurre ANTES de la autenticación: no hay JWT, no hay usuario y no se
 * asumió ningún grp_* (la tx corre como retailmind_app, sin INSERT sobre
 * log_acceso). Por eso la inserción va por la función SECURITY DEFINER
 * fn_registrar_intento_acceso (mismo patrón que fn_registrar_intento_pago_
 * fallido del script 52). Se ejecuta en REQUIRES_NEW para que el rastro se
 * confirme aunque la transacción del intento de autenticación se revierta.
 * NUNCA recibe ni almacena la contraseña.
 *
 * COMPUERTA HORARIA — {@link #estaEnHorario}: el login la consulta también
 * como retailmind_app (esta_en_horario es SECURITY DEFINER). Admin y roles sin
 * mapeo a grupo no se bloquean.
 *
 * LECTURA — {@link #consultar}: el informe corre autenticado como
 * grp_administrador / grp_gerente (PgSessionRoleAspect asume el rol); los
 * GRANTs del script 53 limitan la lectura a esos dos grupos.
 */
@Service
public class AccesoService {

    private final JdbcTemplate pg;

    public AccesoService(@Qualifier("pgJdbcTemplate") JdbcTemplate pg) {
        this.pg = pg;
    }

    /**
     * Persiste un intento de acceso. Corre en su PROPIA transacción para que el
     * rastro sobreviva aunque el intento de autenticación se revierta. La
     * función SECURITY DEFINER existe porque el login no tiene privilegios de
     * negocio (retailmind_app) y no hay rol de grupo que asumir.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long registrar(Long usuarioId, String email, boolean exitoso,
                          String motivo, String ip, String userAgent) {
        Long id = pg.queryForObject(
                "SELECT fn_registrar_intento_acceso(?::bigint, ?::varchar, ?::boolean, "
                        + "?::varchar, ?::text, ?::text)",
                Long.class, usuarioId, email, exitoso, motivo, ip, userAgent);
        if (id == null) {
            throw new IllegalStateException("No se pudo registrar el intento de acceso");
        }
        return id;
    }

    /**
     * ¿El rol del usuario está dentro de su ventana horaria AHORA? Espeja la
     * restricción de motor (grupo_horario + esta_en_horario). Los roles sin
     * mapeo a grupo de PostgreSQL no se bloquean por horario.
     */
    @Transactional(readOnly = true)
    public boolean estaEnHorario(String rolCodigo) {
        Optional<DbGroupRole> grp = DbGroupRole.fromCodigo(rolCodigo);
        String pgRole = grp.map(DbGroupRole::getPgRole).orElse(null);

        if (pgRole == null) {
            // Rol PERSONALIZADO (script 87): su grp_* vive en rol_personalizado.
            // Sin esto la compuerta horaria lo dejaría pasar SIEMPRE —el
            // comportamiento para roles sin mapeo— y un rol nuevo quedaría
            // exento de la única restricción que sus 7 ventanas declaran.
            pgRole = pg.query("""
                    SELECT rp.rol_grupo
                    FROM rol_personalizado rp
                    JOIN rol r ON r.id = rp.rol_id
                    WHERE r.codigo = ? AND r.activo""",
                    rs -> rs.next() ? rs.getString(1) : null, rolCodigo);
        }
        if (pgRole == null) {
            return true;
        }
        Boolean ok = pg.queryForObject("SELECT esta_en_horario(?)", Boolean.class, pgRole);
        return ok == null || ok;
    }

    /**
     * Informe de intentos de acceso (solo Admin/Gerencia). Filtros por rango de
     * fecha, resultado (exitoso/fallido) y correo, con paginación server-side.
     * Todos los filtros van por parámetros con guarda NULL (nada de SQL armado
     * con texto del usuario).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> consultar(String desde, String hasta, String resultado,
                                         String email, int page, int size) {
        String d = vacioANull(desde);
        String h = vacioANull(hasta);
        String em = vacioANull(email);
        Boolean exitoso = resultadoABoolean(resultado);
        int limit = Math.min(Math.max(size, 1), 200);
        int offset = Math.max(page, 0) * limit;

        // Guarda NULL por parámetro: el mismo valor se pasa dos veces (para el
        // IS NULL y para la comparación). Cero concatenación de datos.
        String filtro = """
                WHERE (?::date    IS NULL OR la.fecha_creacion >= ?::date)
                  AND (?::date    IS NULL OR la.fecha_creacion <  (?::date + 1))
                  AND (?::boolean IS NULL OR la.exitoso = ?::boolean)
                  AND (?::varchar IS NULL OR la.email_intentado ILIKE '%' || ?::varchar || '%')
                """;
        Object[] filtroArgs = { d, d, h, h, exitoso, exitoso, em, em };

        Integer total = pg.queryForObject(
                "SELECT count(*) FROM log_acceso la " + filtro, Integer.class, filtroArgs);

        Object[] pageArgs = new Object[filtroArgs.length + 2];
        System.arraycopy(filtroArgs, 0, pageArgs, 0, filtroArgs.length);
        pageArgs[filtroArgs.length] = limit;
        pageArgs[filtroArgs.length + 1] = offset;

        List<Map<String, Object>> items = pg.queryForList("""
                SELECT la.id, la.fecha_creacion, la.email_intentado, la.exitoso,
                       la.motivo_fallo, host(la.ip_origen) AS ip_origen, la.user_agent,
                       la.usuario_id,
                       NULLIF(trim(concat(u.nombre, ' ', COALESCE(u.apellido, ''))), '') AS usuario
                FROM log_acceso la
                LEFT JOIN usuario u ON u.id = la.usuario_id
                """ + filtro + """
                ORDER BY la.fecha_creacion DESC, la.id DESC
                LIMIT ? OFFSET ?""", pageArgs);

        Map<String, Object> res = new HashMap<>();
        res.put("items", items);
        res.put("total", total == null ? 0 : total);
        res.put("page", page);
        res.put("size", limit);
        return res;
    }

    private static String vacioANull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** 'exitoso' → true; 'fallido' → false; cualquier otra cosa → sin filtro. */
    private static Boolean resultadoABoolean(String resultado) {
        if (resultado == null) {
            return null;
        }
        return switch (resultado.trim().toLowerCase()) {
            case "exitoso", "exitosos", "true" -> Boolean.TRUE;
            case "fallido", "fallidos", "false" -> Boolean.FALSE;
            default -> null;
        };
    }
}
