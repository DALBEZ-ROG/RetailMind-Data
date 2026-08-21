package com.retailmind.perfil;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.retailmind.auth.AppUserPrincipal;

/**
 * Perfil del usuario autenticado sobre PostgreSQL.
 *
 * - CLIENTE: datos de la tabla cliente (RLS: solo su fila) + estadísticas de
 *   compra reales (pedido/wishlist) + CRUD de direcciones (tabla direccion,
 *   RLS por usuario_id; baja lógica con activo=false).
 * - Roles operativos: ficha básica derivada del JWT, sin tocar tablas de
 *   tienda (no tienen fila en cliente).
 */
@Service
public class PerfilService {

    private static final List<String> TIPOS_DIRECCION = List.of("envio", "facturacion", "ambas");

    private final JdbcTemplate pg;

    public PerfilService(@Qualifier("pgJdbcTemplate") JdbcTemplate pg) {
        this.pg = pg;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getPerfil() {
        AppUserPrincipal p = principal();
        Map<String, Object> perfil = new LinkedHashMap<>();
        perfil.put("username", p.getUsername());
        perfil.put("rol", p.getRolCodigo());
        perfil.put("esCliente", p.getClienteId() != null);

        if (p.getClienteId() == null) {
            perfil.put("nombre", p.getNombre());
            return perfil;
        }

        Map<String, Object> cliente = pg.queryForMap("""
                SELECT nombre, apellido, email, telefono, fecha_nacimiento, genero,
                       acepta_marketing, fecha_creacion
                FROM cliente WHERE id = ?""", p.getClienteId());
        perfil.put("nombre", cliente.get("nombre"));
        perfil.put("apellido", cliente.get("apellido"));
        perfil.put("email", cliente.get("email"));
        perfil.put("telefono", cliente.get("telefono"));
        perfil.put("fechaNacimiento", cliente.get("fecha_nacimiento"));
        perfil.put("genero", cliente.get("genero"));
        perfil.put("aceptaMarketing", cliente.get("acepta_marketing"));
        perfil.put("fechaCreacion", cliente.get("fecha_creacion"));

        // Estadísticas reales del ciclo de venta (RLS limita a SUS pedidos)
        perfil.put("totalCompras", pg.queryForObject(
                "SELECT count(*) FROM pedido", Long.class));
        perfil.put("totalGastado", pg.queryForObject(
                "SELECT COALESCE(SUM(total), 0) FROM pedido", Double.class));
        perfil.put("productosWishlist", pg.queryForObject(
                "SELECT count(*) FROM wishlist_item", Long.class));
        perfil.put("direcciones", pg.queryForObject(
                "SELECT count(*) FROM direccion WHERE activo", Long.class));
        return perfil;
    }

    /**
     * Valores que admite `cliente_genero_check`. La lista se repite aquí —y no
     * se deduce del motor— para poder RECHAZAR con un mensaje que diga cuáles
     * son: sin ella, un valor fuera de la lista llega al UPDATE y la BD
     * responde con el 400 genérico de restricción, que no dice ni qué campo
     * estaba mal. Si el CHECK cambia, esta lista cambia con él.
     */
    private static final List<String> GENEROS =
            List.of("masculino", "femenino", "otro", "no_indica");

    /**
     * Actualiza los datos del cliente. **Un campo AUSENTE del cuerpo no se
     * toca; un campo presente y vacío SÍ borra el valor.**
     *
     * La distinción no es teórica: la pantalla de perfil no ofrecía la fecha
     * de nacimiento y tampoco la enviaba, así que cada «Guardar cambios»
     * la ponía a NULL sin avisar —y 50.070 de los 50.072 clientes tienen
     * una—. Con `containsKey` decidiendo, omitir un campo es no tocarlo, que
     * es lo que cualquiera espera, y para borrarlo hay que pedirlo mandándolo
     * en blanco.
     */
    @Transactional
    public void actualizarDatos(Map<String, Object> body) {
        AppUserPrincipal p = clienteObligatorio();
        String nombre = str(body.get("nombre"));
        if (nombre == null || nombre.isBlank()) {
            throw new IllegalArgumentException("El nombre es requerido");
        }

        // Lista blanca: el género se valida ANTES de tocar la base, para poder
        // nombrar el campo y los valores admitidos en el mensaje.
        String genero = str(body.get("genero"));
        if (genero != null && !genero.isBlank()
                && !GENEROS.contains(genero.trim().toLowerCase())) {
            throw new IllegalArgumentException(
                    "El género debe ser uno de: " + String.join(", ", GENEROS));
        }

        // La fecha llega como texto «AAAA-MM-DD» (el `input[type=date]` del
        // navegador). Se comprueba el formato y que no sea del futuro; el cast
        // a `date` del UPDATE daría si no un 400 sin explicación.
        String fecha = str(body.get("fechaNacimiento"));
        if (fecha != null && !fecha.isBlank()) {
            LocalDate d;
            try {
                d = LocalDate.parse(fecha.trim());
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException(
                        "La fecha de nacimiento debe tener el formato AAAA-MM-DD");
            }
            if (d.isAfter(LocalDate.now())) {
                throw new IllegalArgumentException(
                        "La fecha de nacimiento no puede ser posterior a hoy");
            }
        }

        boolean tocaApellido = body.containsKey("apellido");
        boolean tocaTelefono = body.containsKey("telefono");
        boolean tocaGenero = body.containsKey("genero");
        boolean tocaFecha = body.containsKey("fechaNacimiento");

        int filas = pg.update("""
                UPDATE cliente SET
                    nombre = ?,
                    apellido = CASE WHEN ?::boolean THEN NULLIF(?, '') ELSE apellido END,
                    telefono = CASE WHEN ?::boolean THEN NULLIF(?, '') ELSE telefono END,
                    genero = CASE WHEN ?::boolean THEN NULLIF(?, '') ELSE genero END,
                    fecha_nacimiento = CASE WHEN ?::boolean
                        THEN NULLIF(?, '')::date ELSE fecha_nacimiento END,
                    acepta_marketing = COALESCE(?::boolean, acepta_marketing)
                WHERE id = ?""",
                nombre.trim(),
                tocaApellido, str(body.get("apellido")),
                tocaTelefono, str(body.get("telefono")),
                tocaGenero, genero != null ? genero.trim().toLowerCase() : null,
                tocaFecha, fecha != null ? fecha.trim() : null,
                body.get("aceptaMarketing") != null ? body.get("aceptaMarketing").toString() : null,
                p.getClienteId());
        if (filas == 0) {
            throw new NoSuchElementException("No se encontró el perfil del cliente");
        }
    }

    // ── Direcciones ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarDirecciones() {
        clienteObligatorio();
        return pg.queryForList("""
                SELECT d.id, d.tipo, d.alias, d.destinatario, d.calle_principal AS "callePrincipal",
                       d.calle_secundaria AS "calleSecundaria", d.numero, d.referencia,
                       d.codigo_postal AS "codigoPostal", d.telefono,
                       d.es_predeterminada AS "esPredeterminada",
                       d.ciudad_id AS "ciudadId", c.nombre AS ciudad
                FROM direccion d
                JOIN ciudad c ON c.id = d.ciudad_id
                WHERE d.activo
                ORDER BY d.es_predeterminada DESC, d.id""");
    }

    @Transactional
    public Map<String, Object> crearDireccion(Map<String, Object> body) {
        AppUserPrincipal p = clienteObligatorio();
        validarDireccion(body);
        boolean predeterminada = Boolean.parseBoolean(String.valueOf(body.get("esPredeterminada")));
        if (predeterminada) {
            pg.update("UPDATE direccion SET es_predeterminada = false WHERE es_predeterminada");
        }
        Long id = pg.queryForObject("""
                INSERT INTO direccion (usuario_id, ciudad_id, tipo, alias, destinatario,
                    calle_principal, calle_secundaria, numero, referencia, codigo_postal,
                    telefono, es_predeterminada)
                VALUES (?, ?, ?, NULLIF(?, ''), ?, ?, NULLIF(?, ''), NULLIF(?, ''),
                        NULLIF(?, ''), NULLIF(?, ''), NULLIF(?, ''), ?)
                RETURNING id""",
                Long.class,
                p.getUsuarioId(), longVal(body.get("ciudadId")), tipoDireccion(body),
                str(body.get("alias")), str(body.get("destinatario")),
                str(body.get("callePrincipal")), str(body.get("calleSecundaria")),
                str(body.get("numero")), str(body.get("referencia")),
                str(body.get("codigoPostal")), str(body.get("telefono")),
                predeterminada);
        return Map.of("id", id);
    }

    @Transactional
    public void actualizarDireccion(long id, Map<String, Object> body) {
        clienteObligatorio();
        validarDireccion(body);
        boolean predeterminada = Boolean.parseBoolean(String.valueOf(body.get("esPredeterminada")));
        if (predeterminada) {
            pg.update("UPDATE direccion SET es_predeterminada = false WHERE es_predeterminada AND id <> ?", id);
        }
        int filas = pg.update("""
                UPDATE direccion SET
                    ciudad_id = ?, tipo = ?, alias = NULLIF(?, ''), destinatario = ?,
                    calle_principal = ?, calle_secundaria = NULLIF(?, ''),
                    numero = NULLIF(?, ''), referencia = NULLIF(?, ''),
                    codigo_postal = NULLIF(?, ''), telefono = NULLIF(?, ''),
                    es_predeterminada = ?
                WHERE id = ? AND activo""",
                longVal(body.get("ciudadId")), tipoDireccion(body),
                str(body.get("alias")), str(body.get("destinatario")),
                str(body.get("callePrincipal")), str(body.get("calleSecundaria")),
                str(body.get("numero")), str(body.get("referencia")),
                str(body.get("codigoPostal")), str(body.get("telefono")),
                predeterminada, id);
        if (filas == 0) {
            throw new NoSuchElementException("La dirección no existe o no es tuya");
        }
    }

    /** Baja lógica: grp_cliente no tiene DELETE sobre direccion (por diseño). */
    @Transactional
    public void eliminarDireccion(long id) {
        clienteObligatorio();
        int filas = pg.update(
                "UPDATE direccion SET activo = false, es_predeterminada = false WHERE id = ? AND activo", id);
        if (filas == 0) {
            throw new NoSuchElementException("La dirección no existe o no es tuya");
        }
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> ciudades() {
        return pg.queryForList("SELECT id, nombre FROM ciudad ORDER BY nombre");
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private void validarDireccion(Map<String, Object> body) {
        if (str(body.get("destinatario")) == null || str(body.get("destinatario")).isBlank()) {
            throw new IllegalArgumentException("El destinatario es requerido");
        }
        if (str(body.get("callePrincipal")) == null || str(body.get("callePrincipal")).isBlank()) {
            throw new IllegalArgumentException("La calle principal es requerida");
        }
        if (longVal(body.get("ciudadId")) == null) {
            throw new IllegalArgumentException("La ciudad es requerida");
        }
    }

    private String tipoDireccion(Map<String, Object> body) {
        String tipo = str(body.get("tipo"));
        return tipo != null && TIPOS_DIRECCION.contains(tipo) ? tipo : "envio";
    }

    private AppUserPrincipal clienteObligatorio() {
        AppUserPrincipal p = principal();
        if (p.getClienteId() == null) {
            throw new IllegalStateException("Esta operación es solo para clientes de la tienda");
        }
        return p;
    }

    private AppUserPrincipal principal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppUserPrincipal p) {
            return p;
        }
        throw new IllegalStateException("No hay usuario autenticado");
    }

    private static String str(Object o) {
        return o != null ? String.valueOf(o) : null;
    }

    private static Long longVal(Object o) {
        if (o == null || String.valueOf(o).isBlank()) return null;
        try {
            return Long.valueOf(String.valueOf(o));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Identificador inválido: " + o);
        }
    }
}
