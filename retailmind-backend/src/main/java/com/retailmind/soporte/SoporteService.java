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

    /**
     * SLA: horas máximas de atención según prioridad (urgente=2h, alta=4h,
     * media=24h, baja=72h). ÚNICO punto con el mapeo prioridad→horas
     * (parametrizado con ?): desde el script 49 el vencimiento ya no se
     * calcula al vuelo — se PERSISTE en ticket_soporte.fecha_limite al crear
     * el ticket y se RECALCULA al cambiar la prioridad; las consultas leen la
     * columna (OTD-SOP-02: plazo consultable para medir cumplimiento).
     */
    private static final String SLA_SQL = """
            CASE ? WHEN 'urgente' THEN interval '2 hours'
                   WHEN 'alta'    THEN interval '4 hours'
                   WHEN 'media'   THEN interval '24 hours'
                   ELSE                interval '72 hours' END""";

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
                SELECT c.id, c.nombre, c.descripcion, c.prioridad_defecto, c.activo, c.fecha_creacion,
                       (SELECT count(*) FROM ticket_soporte t WHERE t.categoria_ticket_id = c.id) AS tickets,
                       (SELECT count(*) FROM faq f WHERE f.categoria_ticket_id = c.id) AS faqs
                FROM categoria_ticket c ORDER BY c.nombre""");
    }

    /** Categorías activas para los selectores (cliente incluido). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarCategoriasRef() {
        return pg.queryForList(
                "SELECT id, nombre, descripcion FROM categoria_ticket WHERE activo ORDER BY nombre");
    }

    @Transactional
    public long crearCategoria(String nombre, String descripcion, String prioridadDefecto) {
        exigirTexto(nombre, "El nombre de la categoría es requerido");
        exigirNombreCategoriaLibre(nombre, null);
        String prio = prioridadDefecto == null || prioridadDefecto.isBlank()
                ? "media" : prioridadDefecto;
        validarEnLista(prio, PRIORIDADES, "prioridad por defecto de la categoría");
        return idDe(pg.queryForObject("""
                INSERT INTO categoria_ticket (nombre, descripcion, prioridad_defecto)
                VALUES (?, ?, ?) RETURNING id""",
                Long.class, nombre.trim(), descripcion, prio));
    }

    @Transactional
    public void editarCategoria(long id, String nombre, String descripcion,
                                String prioridadDefecto) {
        exigirTexto(nombre, "El nombre de la categoría es requerido");
        exigirNombreCategoriaLibre(nombre, id);
        String prio = prioridadDefecto == null || prioridadDefecto.isBlank()
                ? "media" : prioridadDefecto;
        validarEnLista(prio, PRIORIDADES, "prioridad por defecto de la categoría");
        exigir(pg.update("""
                UPDATE categoria_ticket
                SET nombre = ?, descripcion = ?, prioridad_defecto = ? WHERE id = ?""",
                nombre.trim(), descripcion, prio, id), "categoria_ticket", id);
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

    /** Estados posibles del ticket: las claves de TRANSICIONES son la lista blanca. */
    private static final Set<String> ESTADOS_TICKET = TRANSICIONES.keySet();

    /** Bandejas del personal, tal cual las ofrece el conmutador de la pantalla. */
    private static final Set<String> BANDEJAS = Set.of("todos", "sin_asignar", "mios");

    /** El cliente no cuenta las notas internas ni ve a quién está asignado. */
    private static final String SEL_TICKETS_CLIENTE = """
            SELECT t.id, t.numero, t.asunto, t.prioridad, t.estado, t.pedido_id,
                   t.fecha_creacion, t.fecha_cierre, ct.nombre AS categoria,
                   (SELECT count(*) FROM mensaje_ticket m
                    WHERE m.ticket_soporte_id = t.id AND NOT m.es_interno) AS mensajes
            FROM ticket_soporte t
            LEFT JOIN categoria_ticket ct ON ct.id = t.categoria_ticket_id
            """;

    /** Bandeja del personal: incluye SLA (vencimiento por prioridad). */
    private static final String SEL_TICKETS_STAFF = """
            SELECT t.id, t.numero, t.asunto, t.prioridad, t.estado, t.pedido_id,
                   t.fecha_creacion, t.fecha_cierre, ct.nombre AS categoria,
                   t.cliente_id, c.nombre AS cliente,
                   t.asignado_usuario_id,
                   trim(concat(u.nombre, ' ', COALESCE(u.apellido, ''))) AS asignado,
                   (t.asignado_usuario_id IS NOT NULL
                    AND t.asignado_usuario_id = ?::bigint) AS asignado_a_mi,
                   (SELECT count(*) FROM mensaje_ticket m
                    WHERE m.ticket_soporte_id = t.id) AS mensajes,
                   t.fecha_limite,
                   (now() > t.fecha_limite
                    AND t.estado NOT IN ('resuelto', 'cerrado')) AS sla_vencido
            FROM ticket_soporte t
            LEFT JOIN categoria_ticket ct ON ct.id = t.categoria_ticket_id
            LEFT JOIN cliente c ON c.id = t.cliente_id
            LEFT JOIN usuario u ON u.id = t.asignado_usuario_id
            """;

    /**
     * Bandeja de tickets, PAGINADA EN EL SERVIDOR y con SUS CUATRO FILTROS EN SQL.
     *
     * <h3>Por qué dejó de devolver una lista</h3>
     * Devolvía los 179.851 tickets (78,98 MB medidos) en cada apertura.
     *
     * <h3>Los cuatro filtros vivían en el navegador y ese es el riesgo real</h3>
     * `tickets.component.aplicarFiltros()` recorría el array completo con
     * bandeja / estado / categoría / prioridad. Paginar sin mudarlos habría
     * dejado cada filtro mirando las 25 filas visibles: pedir «urgentes» sobre
     * una página de tickets ordenados por urgencia habría devuelto algo
     * plausible, y pedir «cerrado» —que el ORDER BY manda al final de 179.851
     * filas— habría devuelto SIEMPRE cero sin un solo error. Los cuatro se
     * evalúan aquí, contra el conjunto completo, y `total` los cuenta.
     *
     * <h3>La categoría viaja por NOMBRE</h3>
     * Es lo que ya elegía el selector de la pantalla, y sigue siendo un
     * parámetro ligado contra `categoria_ticket.nombre`: no se concatena.
     *
     * <h3>El desempate del ORDER BY</h3>
     * Ordenaba por estado, prioridad y `fecha_creacion DESC`, y ninguna de las
     * tres es única —hay 179.851 tickets en 4 prioridades—: con LIMIT/OFFSET
     * eso reparte filas repetidas entre páginas. Se desempata por `t.id DESC`.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> listarTickets(Integer page, Integer size, String bandeja,
                                             String estado, String categoria,
                                             String prioridad, Boolean conTotal) {
        boolean cliente = esCliente();

        // El WHERE se arma SOLO con los filtros presentes; las piezas son
        // constantes del código y los valores viajan ligados.
        StringBuilder w = new StringBuilder(" WHERE 1 = 1\n");
        List<Object> args = new java.util.ArrayList<>();

        // El SELECT del personal lleva un parámetro propio (asignado_a_mi) y va
        // ANTES que los del WHERE: el orden de los argumentos es el del SQL.
        if (!cliente) { args.add(usuarioActualId()); }

        if (cliente) {
            w.append(" AND t.cliente_id = ?\n");
            args.add(clienteActualId());
        } else if (bandeja != null && !bandeja.isBlank() && !"todos".equals(bandeja)) {
            validarEnLista(bandeja, BANDEJAS, "bandeja");
            if ("sin_asignar".equals(bandeja)) {
                w.append(" AND t.asignado_usuario_id IS NULL\n");
            } else {
                w.append(" AND t.asignado_usuario_id = ?::bigint\n");
                args.add(usuarioActualId());
            }
        }
        if (estado != null && !estado.isBlank()) {
            validarEnLista(estado, ESTADOS_TICKET, "estado del ticket");
            w.append(" AND t.estado = ?\n");
            args.add(estado);
        }
        if (prioridad != null && !prioridad.isBlank()) {
            validarEnLista(prioridad, PRIORIDADES, "prioridad del ticket");
            w.append(" AND t.prioridad = ?\n");
            args.add(prioridad);
        }
        boolean porCategoria = categoria != null && !categoria.isBlank();
        if (porCategoria) {
            w.append(" AND ct.nombre = ?\n");
            args.add(categoria.trim());
        }
        String where = w.toString();
        Object[] a = args.toArray();

        String sqlItems = (cliente ? SEL_TICKETS_CLIENTE : SEL_TICKETS_STAFF) + where
                + (cliente
                   ? " ORDER BY t.fecha_creacion DESC, t.id DESC"
                   : " ORDER BY (t.estado IN ('resuelto', 'cerrado')),"
                   + " CASE t.prioridad WHEN 'urgente' THEN 0 WHEN 'alta' THEN 1"
                   + " WHEN 'media' THEN 2 ELSE 3 END,"
                   + " t.fecha_creacion DESC, t.id DESC");

        int pag = com.retailmind.comun.Paginacion.pagina(page);
        int tam = com.retailmind.comun.Paginacion.tamano(size);

        if (Boolean.FALSE.equals(conTotal)) {
            return com.retailmind.comun.Paginacion.paginarSinTotal(pg, sqlItems, a, pag, tam);
        }

        // El conteo omite el parámetro de `asignado_a_mi` (es del SELECT) y los
        // joins de pintado; solo conserva `categoria_ticket` si se filtra por él.
        Object[] aCount = cliente ? a : java.util.Arrays.copyOfRange(a, 1, a.length);
        String sqlCount = "SELECT count(*) FROM ticket_soporte t"
                + (porCategoria ? " LEFT JOIN categoria_ticket ct ON ct.id = t.categoria_ticket_id" : "")
                + where;
        return com.retailmind.comun.Paginacion.paginar(pg, sqlItems, a, sqlCount, aCount, pag, tam);
    }

    /**
     * Crea un ticket. Si quien llama es CLIENTE, el ticket es sobre sí mismo
     * (se ignora el clienteId del body); el personal lo crea en nombre del
     * cliente indicado.
     *
     * La PRIORIDAD es AUTOMÁTICA: sale de categoria_ticket.prioridad_defecto
     * (script 37). Nadie la elige al crear — cualquier valor que venga en la
     * request se ignora; después solo SOPORTE/ADMIN puede cambiarla
     * (cambiarPrioridad + SecurityConfig).
     */
    @Transactional
    public Map<String, Object> crearTicket(Long clienteId, Long categoriaId, Long pedidoId,
                                           Long productoVarianteId, String asunto,
                                           String descripcion) {
        exigirTexto(asunto, "El asunto del ticket es requerido");

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
        if (productoVarianteId != null) {
            // Opcional (script 50): si viene, debe existir. Se admite variante
            // inactiva a propósito: el reclamo puede ser de un producto ya
            // retirado del catálogo que el cliente compró en su momento.
            Integer existe = pg.queryForObject(
                    "SELECT count(*) FROM producto_variante WHERE id = ?",
                    Integer.class, productoVarianteId);
            if (existe == null || existe == 0) {
                throw new IllegalArgumentException(
                        "No existe el producto indicado en el ticket");
            }
        }
        // Prioridad automática según la categoría (sin categoría → media)
        String prio = "media";
        if (categoriaId != null) {
            prio = pg.queryForObject(
                    "SELECT prioridad_defecto FROM categoria_ticket WHERE id = ?",
                    String.class, categoriaId);
        }

        // Número legible secuencial por año: TICK-2026-0001. La función
        // SECURITY DEFINER (script 43) reserva el correlativo bajo lock de
        // fila: dos creaciones simultáneas se serializan sin chocar.
        String numero = pg.queryForObject(
                "SELECT fn_siguiente_numero_ticket()", String.class);
        // fecha_limite = fecha_creacion + horas(prioridad): now() es estable en
        // la transacción, así que coincide con el default de fecha_creacion
        long id = idDe(pg.queryForObject("""
                INSERT INTO ticket_soporte (numero, cliente_id, categoria_ticket_id, pedido_id,
                                            producto_variante_id, asunto, descripcion,
                                            prioridad, fecha_limite)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, now() + """ + SLA_SQL + """
                ) RETURNING id""",
                Long.class, numero, duenio, categoriaId, pedidoId, productoVarianteId,
                asunto.trim(), descripcion, prio, prio));
        return Map.of("id", id, "numero", numero, "prioridad", prio);
    }

    /** Detalle del ticket con su hilo de mensajes en orden cronológico. */
    @Transactional(readOnly = true)
    public Map<String, Object> obtenerTicket(long id) {
        if (esCliente()) {
            List<Map<String, Object>> filas = pg.queryForList("""
                    SELECT t.id, t.numero, t.asunto, t.descripcion, t.prioridad, t.estado,
                           t.pedido_id, t.fecha_creacion, t.fecha_cierre,
                           ct.nombre AS categoria, t.producto_variante_id,
                           (SELECT p.nombre FROM producto_variante pv
                            JOIN producto p ON p.id = pv.producto_id
                            WHERE pv.id = t.producto_variante_id) AS producto
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
                       t.producto_variante_id,
                       (SELECT p.nombre FROM producto_variante pv
                        JOIN producto p ON p.id = pv.producto_id
                        WHERE pv.id = t.producto_variante_id) AS producto,
                       t.asignado_usuario_id,
                       trim(concat(u.nombre, ' ', COALESCE(u.apellido, ''))) AS asignado,
                       t.fecha_limite,
                       (now() > t.fecha_limite
                        AND t.estado NOT IN ('resuelto', 'cerrado')) AS sla_vencido
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
            throw new IllegalStateException("Un ticket cerrado no admite nuevos mensajes; "
                    + "crea un ticket nuevo si necesitas más ayuda");
        }
        if (esCliente()) {
            long id = idDe(pg.queryForObject("""
                    INSERT INTO mensaje_ticket (ticket_soporte_id, cliente_id, mensaje)
                    VALUES (?, ?, ?) RETURNING id""",
                    Long.class, ticketId, clienteActualId(), mensaje.trim()));
            // Reapertura: si el cliente responde a un ticket resuelto, vuelve
            // a la cola del agente ('en_proceso'). Cerrado sigue siendo terminal.
            if ("resuelto".equals(estado)) {
                pg.update("UPDATE ticket_soporte SET estado = 'en_proceso' WHERE id = ?", ticketId);
            }
            return id;
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

    /**
     * Cambia la prioridad del ticket (solo SOPORTE/ADMIN vía SecurityConfig).
     * La prioridad inicial es automática por categoría; esto es el ajuste
     * manual posterior del agente.
     */
    @Transactional
    public void cambiarPrioridad(long id, String prioridad) {
        validarEnLista(prioridad, PRIORIDADES, "prioridad del ticket");
        String estado = estadoTicket(id);
        if ("cerrado".equals(estado)) {
            throw new IllegalStateException("Un ticket cerrado no admite cambios de prioridad");
        }
        // El cambio de prioridad recalcula el SLA sobre la fecha de creación
        // (misma regla del alta): la fecha límite queda consultable (script 49)
        int filas = pg.update("""
                UPDATE ticket_soporte
                SET prioridad = ?, fecha_limite = fecha_creacion + """ + SLA_SQL + """
                WHERE id = ? AND prioridad <> ?""",
                prioridad, prioridad, id, prioridad);
        if (filas == 0) {
            throw new IllegalStateException("El ticket ya tiene prioridad '" + prioridad + "'");
        }
    }

    /**
     * El agente TOMA el ticket (se lo auto-asigna). Guardias: no cerrado y no
     * tomado ya por otro agente (un ADMIN puede reasignar con asignarAgente).
     * Si el ticket estaba 'abierto' pasa a 'en_proceso' en el mismo acto.
     */
    @Transactional
    public Map<String, Object> tomarTicket(long id) {
        Long usuarioId = usuarioActualId();
        if (usuarioId == null) {
            throw new IllegalStateException("No se pudo identificar al agente autenticado");
        }
        String estado = estadoTicket(id);
        if ("cerrado".equals(estado)) {
            throw new IllegalStateException("Un ticket cerrado no se puede tomar");
        }
        List<Map<String, Object>> filas = pg.queryForList("""
                SELECT t.asignado_usuario_id,
                       trim(concat(u.nombre, ' ', COALESCE(u.apellido, ''))) AS asignado
                FROM ticket_soporte t
                LEFT JOIN usuario u ON u.id = t.asignado_usuario_id
                WHERE t.id = ?""", id);
        Object asignadoA = filas.get(0).get("asignado_usuario_id");
        if (asignadoA != null) {
            if (((Number) asignadoA).longValue() == usuarioId) {
                throw new IllegalStateException("Este ticket ya está asignado a ti");
            }
            throw new IllegalStateException("El ticket ya fue tomado por "
                    + filas.get(0).get("asignado") + "; un administrador puede reasignarlo");
        }
        String nuevoEstado = "abierto".equals(estado) ? "en_proceso" : estado;
        pg.update("""
                UPDATE ticket_soporte SET asignado_usuario_id = ?, estado = ?
                WHERE id = ?""", usuarioId, nuevoEstado, id);
        return Map.of("success", true, "estado", nuevoEstado);
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

    /**
     * Buscador del selector "producto relacionado" del ticket (script 50):
     * búsqueda en servidor por nombre o SKU (el catálogo tiene ~1.221
     * variantes; no se carga completo). Mínimo 2 caracteres, tope 20 filas.
     * grp_soporte busca con sus grants de columna sin dinero (script 50).
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> buscarProductosRef(String q) {
        String filtro = q == null ? "" : q.trim();
        if (filtro.length() < 2) {
            return List.of();
        }
        return pg.queryForList("""
                SELECT pv.id, p.nombre, pv.sku
                FROM producto_variante pv
                JOIN producto p ON p.id = pv.producto_id
                WHERE pv.activo AND p.activo
                  AND (p.nombre ILIKE '%' || ? || '%' OR pv.sku ILIKE '%' || ? || '%')
                ORDER BY p.nombre, pv.sku
                LIMIT 20""", filtro, filtro);
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
