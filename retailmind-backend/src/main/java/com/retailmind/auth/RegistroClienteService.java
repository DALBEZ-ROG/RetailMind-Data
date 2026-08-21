package com.retailmind.auth;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Alta PÚBLICA de un cliente de la tienda: el «crea tu cuenta» del muro de
 * sesión, sin que haya nadie autenticado detrás.
 *
 * <h2>Por qué no reutiliza {@code POST /api/auth/register}</h2>
 *
 * Aquel endpoint toma el <b>rol del cuerpo de la petición</b>. Es correcto para
 * lo que hace —un administrador dando de alta a su gente— y por eso sigue y
 * seguirá reservado a ADMIN. Abrirlo al público sería regalar un
 * {@code {"rol":"ADMIN"}} a cualquiera con curl: escalada de privilegios en una
 * línea. Aquí el rol <b>no es un parámetro en ningún punto del camino</b>: ni en
 * el controlador, ni en este servicio, ni en la firma de {@code fn_registrar_cliente},
 * que es donde el script 112 lo comprueba con una guardia. Para pedir otro rol
 * habría que cambiar las tres cosas a la vez.
 *
 * <h2>Por qué el INSERT lo hace una función del motor</h2>
 *
 * Un visitante anónimo no trae JWT, así que el aspecto {@code PgSessionRoleAspect}
 * no asume ningún rol y la transacción corre como {@code retailmind_app}, que es
 * LOGIN <b>NOINHERIT</b> y no tiene un solo privilegio de negocio. Escribir en
 * {@code usuario}, {@code usuario_rol} y {@code cliente} exige entonces una
 * función {@code SECURITY DEFINER} —el patrón que el proyecto ya usa en
 * {@code fn_admin_cambiar_permiso} y {@code fn_registrar_uso_cupon}—, con el
 * {@code search_path} clavado.
 *
 * <h2>Lo que NO hace</h2>
 *
 * No inicia sesión: devuelve los datos del alta y el controlador reutiliza el
 * login de siempre. Así el token nace del MISMO camino que cualquier otro —con
 * su registro en {@code log_acceso} incluido— y no hay una segunda forma de
 * emitir credenciales que mantener en pie.
 */
@Service
public class RegistroClienteService {

    /** Lo mínimo para que la contraseña no sea un trámite. */
    private static final int CLAVE_MINIMA = 8;

    /**
     * Valores que admite {@code cliente_genero_check}. Se enumeran aquí porque
     * la alternativa es dejar que el motor conteste con el texto del CHECK, que
     * no dice cuáles son los válidos — el defecto D-13, exactamente.
     */
    private static final Set<String> GENEROS =
            Set.of("masculino", "femenino", "otro", "no_indica");

    /** Valores que admite {@code cliente_tipo_identificacion_check}. */
    private static final Set<String> TIPOS_IDENT = Set.of("cedula", "ruc", "pasaporte");

    private final JdbcTemplate pg;
    private final PasswordEncoder passwordEncoder;

    public RegistroClienteService(@Qualifier("pgJdbcTemplate") JdbcTemplate pg,
                                  PasswordEncoder passwordEncoder) {
        this.pg = pg;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Crea la cuenta y devuelve sus identificadores.
     *
     * @throws IllegalArgumentException con el motivo → 400
     * @throws IllegalStateException    si el correo ya existe → 409
     */
    @Transactional
    public Map<String, Object> registrar(Map<String, Object> body) {
        String email    = texto(body, "email");
        String clave    = texto(body, "password");
        String nombre   = texto(body, "nombre");
        String apellido = texto(body, "apellido");
        String telefono = texto(body, "telefono");

        if (email == null) { throw new IllegalArgumentException("El correo es obligatorio."); }
        if (!email.matches("^[A-Za-z0-9._+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+$")) {
            throw new IllegalArgumentException(
                    "Escribe un correo con la forma nombre@dominio.com");
        }
        if (nombre == null) { throw new IllegalArgumentException("El nombre es obligatorio."); }
        if (clave == null || clave.length() < CLAVE_MINIMA) {
            throw new IllegalArgumentException(
                    "La contraseña debe tener al menos " + CLAVE_MINIMA + " caracteres.");
        }

        String tipoIdent = enumerado(body, "tipoIdentificacion", TIPOS_IDENT,
                "El tipo de identificación");
        String genero    = enumerado(body, "genero", GENEROS, "El género");
        String numIdent  = texto(body, "numeroIdentificacion");
        LocalDate nacido = fecha(body, "fechaNacimiento");
        boolean marketing = Boolean.TRUE.equals(body.get("aceptaMarketing"));

        // El número de identificación es UNIQUE junto con su tipo, y uno sin el
        // otro no significa nada: se exigen los dos o ninguno.
        if ((tipoIdent == null) != (numIdent == null)) {
            throw new IllegalArgumentException(
                    "El tipo y el número de identificación van juntos: indica los dos o ninguno.");
        }
        if (numIdent != null && !numIdent.matches("\\d{5,20}")) {
            throw new IllegalArgumentException(
                    "El número de identificación son solo dígitos (entre 5 y 20).");
        }
        if (nacido != null && !nacido.isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("La fecha de nacimiento debe ser anterior a hoy.");
        }

        // La contraseña se cifra ANTES de salir de Java: el motor nunca ve una
        // en claro, ni siquiera de paso por un parámetro.
        String hash = passwordEncoder.encode(clave);

        try {
            List<Map<String, Object>> filas = pg.queryForList("""
                    SELECT usuario_id, cliente_id
                    FROM fn_registrar_cliente(?, ?, ?, ?, ?, ?, ?, ?::date, ?, ?)
                    """,
                    email, hash, nombre, apellido, telefono,
                    tipoIdent, numIdent,
                    nacido == null ? null : nacido.toString(),
                    genero, marketing);
            Map<String, Object> fila = filas.get(0);
            return Map.of("usuarioId", fila.get("usuario_id"),
                          "clienteId", fila.get("cliente_id"),
                          "email", email);
        } catch (DataAccessException e) {
            // La función levanta etiquetas propias para que la respuesta pueda
            // ser un 409 con sentido en vez de un 500 con el texto del motor.
            String causa = String.valueOf(e.getMostSpecificCause().getMessage());
            if (causa.contains("REGISTRO_EMAIL_DUPLICADO")) {
                throw new IllegalStateException(
                        "Ya hay una cuenta con ese correo. Inicia sesión o usa otro.");
            }
            if (causa.contains("REGISTRO_IDENT_DUPLICADA")) {
                throw new IllegalStateException(
                        "Ya hay una cuenta con esa identificación. "
                        + "Si es tuya, inicia sesión; si no, revisa el número.");
            }
            throw e;
        }
    }

    // ── Lectura del cuerpo ───────────────────────────────────────────────────

    /** Texto recortado, o null si venía vacío. Un "" y un ausente son lo mismo. */
    private static String texto(Map<String, Object> body, String clave) {
        Object v = body.get(clave);
        if (v == null) { return null; }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * Valor de una lista blanca. Se valida AQUÍ y no en el motor porque el
     * mensaje del CHECK no enumera lo aceptado, y ése fue exactamente el
     * defecto D-13: la pantalla mandaba «F» y el usuario leía un 400 ilegible.
     */
    private static String enumerado(Map<String, Object> body, String clave,
                                    Set<String> validos, String etiqueta) {
        String v = texto(body, clave);
        if (v == null) { return null; }
        String norm = v.toLowerCase();
        if (!validos.contains(norm)) {
            throw new IllegalArgumentException(
                    etiqueta + " admite: " + String.join(", ", validos) + ".");
        }
        return norm;
    }

    private static LocalDate fecha(Map<String, Object> body, String clave) {
        String v = texto(body, clave);
        if (v == null) { return null; }
        try {
            // El campo <input type="date"> manda AAAA-MM-DD; si llega con hora
            // se recorta, porque la columna es `date` y la hora no significaría nada.
            return LocalDate.parse(v.length() > 10 ? v.substring(0, 10) : v);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("La fecha de nacimiento no tiene un formato válido.");
        }
    }
}
