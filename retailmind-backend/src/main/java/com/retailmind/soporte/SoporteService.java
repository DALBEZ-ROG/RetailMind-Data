package com.retailmind.soporte;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.retailmind.auth.AppUserPrincipal;

/**
 * Módulo de soporte / atención al cliente sobre PostgreSQL
 * (categoria_ticket, ticket_soporte, mensaje_ticket, faq).
 *
 * Todo dentro de @Transactional para que PgSessionRoleAspect asuma el rol de
 * grupo (grp_administrador/grp_gerente gestionan; grp_cliente crea y consulta
 * los suyos). Las tablas de soporte NO tienen política RLS: el aislamiento del
 * CLIENTE se aplica aquí filtrando por el cliente_id del JWT (ver script
 * 28_grants_soporte.sql; queda pendiente decidir si se lleva al motor).
 *
 * Nunca se escriben fecha_creacion (default) ni fecha_actualizacion (trigger
 * touch); fecha_cierre sí es de la app (se fija al cerrar el ticket).
 *
 * Ojo: grp_cliente no tiene SELECT sobre usuario — las consultas del cliente
 * no tocan esa tabla (el autor interno se muestra como "Equipo de soporte") y
 * las notas internas (es_interno) se excluyen de su vista.
 */
@Service
public class SoporteService {

    /** Listas blancas que espejan los CHECK de la BD (mensaje claro antes del 400 genérico). */
    private static final Set<String> PRIORIDADES = Set.of("baja", "media", "alta", "urgente");

    /** Transiciones válidas del ciclo de vida del ticket; cerrado es terminal. */
    private static final Map<String, Set<String>> TRANSICIONES = Map.of(
            "abierto", Set.of("en_proceso", "cerrado"),
            "en_proceso", Set.of("esperando_cliente", "resuelto", "cerrado"),
            "esperando_cliente", Set.of("en_proceso", "resuelto", "cerrado"),
            "resuelto", Set.of("en_proceso", "cerrado"),
            "cerrado", Set.of());

    private final JdbcTemplate pg;

    public SoporteService(@Qualifier("pgJdbcTemplate") JdbcTemplate pg) {
        this.pg = pg;
    }

    // ── Categorías de ticket ─────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarCategorias() {
        return pg.queryForList("""
                SELECT c.id, c.nombre, c.descripcion, c.activo, c.fecha_creacion,
                       (SELECT count(*) FROM ticket_soporte t WHERE t.categoria_ticket_id = c.id) AS tickets,
                       (SELECT count(*) FROM faq f WHERE f.categoria_ticket_id = c.id) AS faqs
                FROM categoria_ticket c ORDER BY c.nombre""");
    }

    /** Categorías activas para los selectores (cliente incluido). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarCategoriasRef() {
        return pg.queryForList(
                "SELECT id, nombre FROM categoria_ticket WHERE activo ORDER BY nombre");
    }

    @Transactional
    public long crearCategoria(String nombre, String descripcion) {
        exigirTexto(nombre, "El nombre de la categoría es requerido");
        exigirNombreCategoriaLibre(nombre, null);
        return idDe(pg.queryForObject("""
                INSERT INTO categoria_ticket (nombre, descripcion)
                VALUES (?, ?) RETURNING id""",
                Long.class, nombre.trim(), descripcion));
    }

    @Transactional
    public void editarCategoria(long id, String nombre, String descripcion) {
        exigirTexto(nombre, "El nombre de la categoría es requerido");
        exigirNombreCategoriaLibre(nombre, id);
        exigir(pg.update("UPDATE categoria_ticket SET nombre = ?, descripcion = ? WHERE id = ?",
                nombre.trim(), descripcion, id), "categoria_ticket", id);
    }

    @Transactional
    public void activarCategoria(long id, boolean activo) {
        exigir(pg.update("UPDATE categoria_ticket SET activo = ? WHERE id = ?", activo, id),
                "categoria_ticket", id);
    }

    /** Guardia: nombre único (la BD también lo exige, pero aquí el mensaje es claro). */
    private void exigirNombreCategoriaLibre(String nombre, Long idActual) {
        Integer repetidos = pg.queryForObject("""
                SELECT count(*) FROM categoria_ticket
                WHERE lower(nombre) = lower(?) AND id <> COALESCE(?::bigint, -1)""",
                Integer.class, nombre.trim(), idActual);
        if (repetidos != null && repetidos > 0) {
            throw new IllegalStateException("Ya existe una categoría con el nombre '"
                    + nombre.trim() + "'");
        }
    }

    // ── Tickets de soporte ───────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarTickets() {
        Long clienteId = clienteActualId();
        if (esCliente()) {
            // Aislamiento por propiedad: el cliente solo ve sus tickets y no
            // cuenta las notas internas ni ve a quién está asignado.
            return pg.queryForList("""
                    SELECT t.id, t.numero, t.asunto, t.prioridad, t.estado, t.pedido_id,
                           t.fecha_creacion, t.fecha_cierre, ct.nombre AS categoria,
                           (SELECT count(*) FROM mensaje_ticket m
                            WHERE m.ticket_soporte_id = t.id AND NOT m.es_interno) AS mensajes
                    FROM ticket_soporte t
                    LEFT JOIN categoria_ticket ct ON ct.id = t.categoria_ticket_id
                    WHERE t.cliente_id = ?
                    ORDER BY t.fecha_creacion DESC""", clienteId);
        }
        return pg.queryForList("""
                SELECT t.id, t.numero, t.asunto, t.prioridad, t.estado, t.pedido_id,
                       t.fecha_creacion, t.fecha_cierre, ct.nombre AS categoria,
                       t.cliente_id, c.nombre AS cliente,
                       t.asignado_usuario_id,
                       trim(concat(u.nombre, ' ', COALESCE(u.apellido, ''))) AS asignado,
                       (SELECT count(*) FROM mensaje_ticket m
                        WHERE m.ticket_soporte_id = t.id) AS mensajes
                FROM ticket_soporte t
                LEFT JOIN categoria_ticket ct ON ct.id = t.categoria_ticket_id
                LEFT JOIN cliente c ON c.id = t.cliente_id
                LEFT JOIN usuario u ON u.id = t.asignado_usuario_id
                ORDER BY t.fecha_creacion DESC""");
    }

    /**
     * Crea un ticket. Si quien llama es CLIENTE, el ticket es sobre sí mismo
     * (se ignora el clienteId del body); el personal lo crea en nombre del
     * cliente indicado.
     */
    @Transactional
    public Map<String, Object> crearTicket(Long clienteId, Long categoriaId, Long pedidoId,
                                           String asunto, String descripcion, String prioridad) {
        exigirTexto(asunto, "El asunto del ticket es requerido");
        String prio = (prioridad == null || prioridad.isBlank()) ? "media" : prioridad;
        validarEnLista(prio, PRIORIDADES, "prioridad del ticket");

        long duenio;
        if (esCliente()) {
            duenio = clienteActualId();
        } else {
            if (clienteId == null) {
                throw new IllegalArgumentException("Debe indicar el cliente del ticket");
            }
            Integer existe = pg.queryForObject("SELECT count(*) FROM cliente WHERE id = ?",
                    Integer.class, clienteId);
            if (existe == null || existe == 0) {
                throw new IllegalArgumentException("No existe cliente con id " + clienteId);
            }
            duenio = clienteId;
        }
        if (categoriaId != null) {
            Integer activa = pg.queryForObject(
                    "SELECT count(*) FROM categoria_ticket WHERE id = ? AND activo",
                    Integer.class, categoriaId);
            if (activa == null || activa == 0) {
                throw new IllegalArgumentException(
                        "La categoría de ticket no existe o está inactiva");
            }
        }
        if (pedidoId != null) {
            // Para CLIENTE la RLS de pedido ya lo limita a sus filas; para el
            // personal esta guardia evita colgar el ticket de un pedido ajeno.
            Integer propio = pg.queryForObject(
                    "SELECT count(*) FROM pedido WHERE id = ? AND cliente_id = ?",
                    Integer.class, pedidoId, duenio);
            if (propio == null || propio == 0) {
                throw new IllegalArgumentException(
                        "El pedido no existe o no pertenece al cliente del ticket");
            }
        }
        String numero = siguienteNumero("TK");
        long id = idDe(pg.queryForObject("""
                INSERT INTO ticket_soporte (numero, cliente_id, categoria_ticket_id, pedido_id,
                                            asunto, descripcion, prioridad)
                VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id""",
                Long.class, numero, duenio, categoriaId, pedidoId, asunto.trim(),
                descripcion, prio));
        return Map.of("id", id, "numero", numero);
    }

    /** Detalle del ticket con su hilo de mensajes en orden cronológico. */
    @Transactional(readOnly = true)
    public Map<String, Object> obtenerTicket(long id) {
        if (esCliente()) {
            List<Map<String, Object>> filas = pg.queryForList("""
                    SELECT t.id, t.numero, t.asunto, t.descripcion, t.prioridad, t.estado,
                           t.pedido_id, t.fecha_creacion, t.fecha_cierre,
                           ct.nombre AS categoria
                    FROM ticket_soporte t
                    LEFT JOIN categoria_ticket ct ON ct.id = t.categoria_ticket_id
                    WHERE t.id = ? AND t.cliente_id = ?""", id, clienteActualId());
            if (filas.isEmpty()) {
                throw new NoSuchElementException("No existe el ticket " + id);
            }
            Map<String, Object> ticket = filas.get(0);
            ticket.put("mensajes", pg.queryForList("""
                    SELECT m.id, m.mensaje, m.fecha_creacion,
                           (m.cliente_id IS NOT NULL) AS de_cliente,
                           CASE WHEN m.cliente_id IS NOT NULL THEN cl.nombre
                                ELSE 'Equipo de soporte' END AS autor
                    FROM mensaje_ticket m
                    LEFT JOIN cliente cl ON cl.id = m.cliente_id
                    WHERE m.ticket_soporte_id = ? AND NOT m.es_interno
                    ORDER BY m.fecha_creacion, m.id""", id));
            return ticket;
        }
        List<Map<String, Object>> filas = pg.queryForList("""
                SELECT t.id, t.numero, t.asunto, t.descripcion, t.prioridad, t.estado,
                       t.pedido_id, t.fecha_creacion, t.fecha_cierre,
                       ct.nombre AS categoria, t.cliente_id, c.nombre AS cliente,
                       t.asignado_usuario_id,
                       trim(concat(u.nombre, ' ', COALESCE(u.apellido, ''))) AS asignado
                FROM ticket_soporte t
                LEFT JOIN categoria_ticket ct ON ct.id = t.categoria_ticket_id
                LEFT JOIN cliente c ON c.id = t.cliente_id
                LEFT JOIN usuario u ON u.id = t.asignado_usuario_id
                WHERE t.id = ?""", id);
        if (filas.isEmpty()) {
            throw new NoSuchElementException("No existe el ticket " + id);
        }
        Map<String, Object> ticket = filas.get(0);
        ticket.put("mensajes", pg.queryForList("""
                SELECT m.id, m.mensaje, m.es_interno, m.fecha_creacion,
                       (m.cliente_id IS NOT NULL) AS de_cliente,
                       CASE WHEN m.cliente_id IS NOT NULL THEN cl.nombre
                            ELSE trim(concat(u.nombre, ' ', COALESCE(u.apellido, ''))) END AS autor
                FROM mensaje_ticket m
                LEFT JOIN cliente cl ON cl.id = m.cliente_id
                LEFT JOIN usuario u ON u.id = m.usuario_id
                WHERE m.ticket_soporte_id = ?
                ORDER BY m.fecha_creacion, m.id""", id));
        return ticket;
    }

    /**
     * Agrega un mensaje al hilo. El autor sale del JWT: cliente_id si es
     * CLIENTE (nunca nota interna), usuario_id si es personal.
     */
    @Transactional
    public long agregarMensaje(long ticketId, String mensaje, boolean esInterno) {
        exigirTexto(mensaje, "El mensaje no puede estar vacío");
        String estado = estadoTicket(ticketId);
        if ("cerrado".equals(estado)) {
            throw new IllegalStateException("Un ticket cerrado no admite nuevos mensajes");
        }
        if (esCliente()) {
            return idDe(pg.queryForObject("""
                    INSERT INTO mensaje_ticket (ticket_soporte_id, cliente_id, mensaje)
                    VALUES (?, ?, ?) RETURNING id""",
                    Long.class, ticketId, clienteActualId(), mensaje.trim()));
        }
        return idDe(pg.queryForObject("""
                INSERT INTO mensaje_ticket (ticket_soporte_id, usuario_id, mensaje, es_interno)
                VALUES (?, ?, ?, ?) RETURNING id""",
                Long.class, ticketId, usuarioActualId(), mensaje.trim(), esInterno));
    }

    /** Cambia el estado del ticket validando la transición; al cerrar fija fecha_cierre. */
    @Transactional
    public void cambiarEstado(long id, String estado) {
        validarEnLista(estado, TRANSICIONES.keySet(), "estado del ticket");
        String actual = estadoTicket(id);
        if (estado.equals(actual)) {
            throw new IllegalStateException("El ticket ya está en estado '" + estado + "'");
        }
        Set<String> permitidas = TRANSICIONES.get(actual);
        if (!permitidas.contains(estado)) {
            throw new IllegalStateException("Transición inválida: '" + actual + "' → '" + estado
                    + "'. Permitidas desde '" + actual + "': "
                    + (permitidas.isEmpty() ? "ninguna (estado terminal)"
                       : String.join(", ", permitidas.stream().sorted().toList())));
        }
        pg.update("""
                UPDATE ticket_soporte
                SET estado = ?, fecha_cierre = CASE WHEN ? = 'cerrado' THEN now() ELSE fecha_cierre END
                WHERE id = ?""", estado, estado, id);
    }

    /** Asigna (o des-asigna con usuarioId null) un agente interno al ticket. */
    @Transactional
    public void asignarAgente(long id, Long usuarioId) {
        String estado = estadoTicket(id);
        if ("cerrado".equals(estado)) {
            throw new IllegalStateException("Un ticket cerrado no admite cambios de asignación");
        }
        if (usuarioId != null) {
            Integer valido = pg.queryForObject("""
                    SELECT count(*) FROM usuario u
                    JOIN usuario_rol ur ON ur.usuario_id = u.id
                    JOIN rol r ON r.id = ur.rol_id
                    WHERE u.id = ? AND u.activo AND r.codigo <> 'CLIENTE'""",
                    Integer.class, usuarioId);
            if (valido == null || valido == 0) {
                throw new IllegalArgumentException(
                        "El usuario no existe, está inactivo o no es personal interno");
            }
        }
        pg.update("UPDATE ticket_soporte SET asignado_usuario_id = ? WHERE id = ?", usuarioId, id);
    }

    /**
     * Pedidos para el selector "pedido relacionado" del formulario de ticket.
     * CLIENTE: siempre los suyos (se ignora el parámetro; RLS refuerza).
     * Personal: los del cliente indicado (sin cliente aún elegido, lista vacía).
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarPedidosRef(Long clienteId) {
        Long duenio = esCliente() ? clienteActualId() : clienteId;
        if (duenio == null) {
            return List.of();
        }
        return pg.queryForList("""
                SELECT p.id, p.numero, p.total, p.fecha_pedido, ep.codigo AS estado
                FROM pedido p
                JOIN estado_pedido ep ON ep.id = p.estado_pedido_id
                WHERE p.cliente_id = ?
                ORDER BY p.fecha_pedido DESC, p.id DESC""", duenio);
    }

    /** Personal interno activo para el selector de asignación. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarUsuariosRef() {
        return pg.queryForList("""
                SELECT u.id, trim(concat(u.nombre, ' ', COALESCE(u.apellido, ''))) AS nombre,
                       min(r.codigo) AS rol
                FROM usuario u
                JOIN usuario_rol ur ON ur.usuario_id = u.id
                JOIN rol r ON r.id = ur.rol_id
                WHERE u.activo AND r.codigo <> 'CLIENTE'
                GROUP BY u.id, u.nombre, u.apellido
                ORDER BY nombre""");
    }

    /** Estado actual; para CLIENTE además exige propiedad (404 si es ajeno: no filtra existencia). */
    private String estadoTicket(long ticketId) {
        List<String> estados = esCliente()
                ? pg.queryForList("SELECT estado FROM ticket_soporte WHERE id = ? AND cliente_id = ?",
                        String.class, ticketId, clienteActualId())
                : pg.queryForList("SELECT estado FROM ticket_soporte WHERE id = ?",
                        String.class, ticketId);
        if (estados.isEmpty()) {
            throw new NoSuchElementException("No existe el ticket " + ticketId);
        }
        return estados.get(0);
    }

    // ── FAQ ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarFaqs() {
        return pg.queryForList("""
                SELECT f.id, f.categoria_ticket_id, ct.nombre AS categoria, f.pregunta,
                       f.respuesta, f.orden, f.activo, f.fecha_creacion
                FROM faq f
                LEFT JOIN categoria_ticket ct ON ct.id = f.categoria_ticket_id
                ORDER BY f.orden, f.id""");
    }

    /** FAQ activas (centro de ayuda) para los roles con SELECT sobre faq. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarFaqsActivas() {
        return pg.queryForList("""
                SELECT f.id, ct.nombre AS categoria, f.pregunta, f.respuesta, f.orden
                FROM faq f
                LEFT JOIN categoria_ticket ct ON ct.id = f.categoria_ticket_id
                WHERE f.activo
                ORDER BY f.orden, f.id""");
    }

    @Transactional
    public long crearFaq(Long categoriaId, String pregunta, String respuesta, Integer orden) {
        exigirTexto(pregunta, "La pregunta es requerida");
        exigirTexto(respuesta, "La respuesta es requerida");
        validarCategoriaFaq(categoriaId);
        return idDe(pg.queryForObject("""
                INSERT INTO faq (categoria_ticket_id, pregunta, respuesta, orden)
                VALUES (?, ?, ?, COALESCE(?, 0)) RETURNING id""",
                Long.class, categoriaId, pregunta.trim(), respuesta.trim(), orden));
    }

    @Transactional
    public void editarFaq(long id, Long categoriaId, String pregunta, String respuesta,
                          Integer orden) {
        exigirTexto(pregunta, "La pregunta es requerida");
        exigirTexto(respuesta, "La respuesta es requerida");
        validarCategoriaFaq(categoriaId);
        exigir(pg.update("""
                UPDATE faq
                SET categoria_ticket_id = ?, pregunta = ?, respuesta = ?, orden = COALESCE(?, orden)
                WHERE id = ?""",
                categoriaId, pregunta.trim(), respuesta.trim(), orden, id), "faq", id);
    }

    @Transactional
    public void activarFaq(long id, boolean activo) {
        exigir(pg.update("UPDATE faq SET activo = ? WHERE id = ?", activo, id), "faq", id);
    }

    private void validarCategoriaFaq(Long categoriaId) {
        if (categoriaId == null) return;
        Integer existe = pg.queryForObject("SELECT count(*) FROM categoria_ticket WHERE id = ?",
                Integer.class, categoriaId);
        if (existe == null || existe == 0) {
            throw new IllegalArgumentException("No existe categoría de ticket con id " + categoriaId);
        }
    }

    // ── Utilitarios ──────────────────────────────────────────────────────

    private String siguienteNumero(String prefijo) {
        return pg.queryForObject(
                "SELECT ? || '-' || to_char(now(), 'YYYYMMDD') || '-' || lpad(floor(random()*100000)::text, 5, '0')",
                String.class, prefijo);
    }

    private static void validarEnLista(String valor, Set<String> validos, String campo) {
        if (valor == null || !validos.contains(valor)) {
            throw new IllegalArgumentException("Valor inválido para " + campo
                    + ". Permitidos: " + String.join(", ", validos.stream().sorted().toList()));
        }
    }

    private static void exigirTexto(String valor, String mensaje) {
        if (valor == null || valor.isBlank()) throw new IllegalArgumentException(mensaje);
    }

    private static long idDe(Long id) {
        if (id == null) throw new IllegalStateException("INSERT no devolvio id");
        return id;
    }

    private static void exigir(int filas, String tabla, long id) {
        if (filas == 0) throw new IllegalArgumentException("No existe " + tabla + " con id " + id);
    }

    private boolean esCliente() {
        return "CLIENTE".equalsIgnoreCase(rolActual());
    }

    /** El cliente autenticado; para CLIENTE nunca es null (lo garantiza el login). */
    private Long clienteActualId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppUserPrincipal p) {
            return p.getClienteId();
        }
        return null;
    }

    private Long usuarioActualId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppUserPrincipal p) {
            return p.getUsuarioId();
        }
        return null;
    }

    private String rolActual() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppUserPrincipal p) {
            return p.getRolCodigo();
        }
        return null;
    }
}
