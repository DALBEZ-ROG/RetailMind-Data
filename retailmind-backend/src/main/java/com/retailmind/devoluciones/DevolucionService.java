package com.retailmind.devoluciones;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
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
import com.retailmind.inventario.StockService;
import com.retailmind.soporte.SoporteService;

/**
 * RMA / logística inversa (script 38). El proceso NACE DEL CLIENTE y cada
 * transición tiene UN rol responsable (SecurityConfig) y UNA compuerta de
 * estado (aquí, fuente de verdad):
 *
 *   solicitada (CLIENTE, pedido entregado/devuelto dentro de PLAZO_DIAS o
 *               despachado = rechazo en puerta; crea ticket 'Devolución')
 *     → en_revision (SOPORTE toma el caso)            [opcional]
 *     → aprobada   (SOPORTE: genera guía de retorno + transportista, PDF)
 *     |  rechazada (SOPORTE con motivo; TERMINAL, ticket resuelto)
 *     → en_transito (DESPACHO: el cliente envió el paquete)
 *     → recibida    (DESPACHO/BODEGA: el paquete llegó al almacén)
 *     → inspeccionada (BODEGA: resultado POR ÍTEM; SOLO aquí y SOLO el
 *                      apto_reventa reingresa stock vía StockService; el
 *                      defectuoso queda como merma/pendiente de devolución a
 *                      proveedor SIN stock vendible; el rechazado —daño
 *                      imputable al cliente— no se reembolsa)
 *     → reembolsada (GERENTE/ADMIN: monto = ítems apto+defectuoso, simulado)
 *     → cerrada     (SOPORTE/ADMIN; ticket resuelto)
 *
 * Todo @Transactional (SET LOCAL ROLE); la BD refuerza con GRANTs + RLS.
 */
@Service
public class DevolucionService {

    /** Plazo máximo (días corridos) desde la ENTREGA para solicitar la devolución. */
    public static final int PLAZO_DIAS_DEVOLUCION = 30;

    /** Métodos del reembolso SIMULADO (lista blanca de la app; no hay pasarela). */
    private static final Set<String> METODOS_REEMBOLSO =
            Set.of("transferencia", "tarjeta", "credito_tienda", "efectivo");

    private static final Set<String> ESTADOS = Set.of(
            "solicitada", "en_revision", "aprobada", "rechazada", "en_transito",
            "recibida", "inspeccionada", "reembolsada", "cerrada");

    private static final Set<String> RESULTADOS_INSPECCION =
            Set.of("apto_reventa", "defectuoso", "rechazado");

    /** Estados que aún cuentan mercancía comprometida (para el tope por ítem). */
    private static final String SQL_DEVUELTAS_POR_DETALLE = """
            SELECT COALESCE(SUM(dd.cantidad), 0)
            FROM devolucion_detalle dd
            JOIN devolucion d ON d.id = dd.devolucion_id
            WHERE dd.pedido_detalle_id = ? AND d.estado <> 'rechazada'""";

    private final JdbcTemplate pg;
    private final StockService stock;
    private final SoporteService soporte;

    public DevolucionService(@Qualifier("pgJdbcTemplate") JdbcTemplate pg,
                             StockService stock, SoporteService soporte) {
        this.pg = pg;
        this.stock = stock;
        this.soporte = soporte;
    }

    // ── Consultas ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listar(String estado) {
        String filtro = null;
        if (estado != null && !estado.isBlank()) {
            if (!ESTADOS.contains(estado)) {
                throw new IllegalArgumentException("Estado de devolución inválido: '" + estado
                        + "'. Válidos: " + String.join(", ", ESTADOS.stream().sorted().toList()));
            }
            filtro = estado;
        }
        if (esCliente()) {
            return pg.queryForList("""
                    SELECT d.id, d.numero, d.estado, d.monto_total, d.monto_reembolsado,
                           d.guia_retorno, d.fecha_creacion, d.ticket_soporte_id,
                           md.nombre AS motivo, p.numero AS numero_pedido,
                           t.nombre AS transportista
                    FROM devolucion d
                    JOIN motivo_devolucion md ON md.id = d.motivo_devolucion_id
                    JOIN pedido p ON p.id = d.pedido_id
                    LEFT JOIN transportista t ON t.id = d.transportista_id
                    WHERE d.cliente_id = ? AND (?::text IS NULL OR d.estado = ?::text)
                    ORDER BY d.id DESC""", clienteActualId(), filtro, filtro);
        }
        return pg.queryForList("""
                SELECT d.id, d.numero, d.estado, d.monto_total, d.monto_reembolsado,
                       d.guia_retorno, d.fecha_creacion, d.ticket_soporte_id,
                       md.nombre AS motivo, p.numero AS numero_pedido,
                       c.nombre || ' ' || COALESCE(c.apellido,'') AS cliente,
                       t.nombre AS transportista
                FROM devolucion d
                JOIN motivo_devolucion md ON md.id = d.motivo_devolucion_id
                JOIN pedido p ON p.id = d.pedido_id
                LEFT JOIN cliente c ON c.id = d.cliente_id
                LEFT JOIN transportista t ON t.id = d.transportista_id
                WHERE (?::text IS NULL OR d.estado = ?::text)
                ORDER BY CASE d.estado WHEN 'cerrada' THEN 1 WHEN 'rechazada' THEN 1 ELSE 0 END,
                         d.id DESC""", filtro, filtro);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> obtener(long id) {
        List<Map<String, Object>> filas;
        if (esCliente()) {
            filas = pg.queryForList("""
                    SELECT d.id, d.numero, d.estado, d.descripcion, d.monto_total,
                           d.monto_reembolsado, d.metodo_reembolso, d.fecha_reembolso,
                           d.guia_retorno, d.motivo_rechazo, d.fecha_creacion,
                           d.ticket_soporte_id, d.pedido_id,
                           md.nombre AS motivo, p.numero AS numero_pedido,
                           c.nombre || ' ' || COALESCE(c.apellido,'') AS cliente,
                           c.email AS cliente_email,
                           t.nombre AS transportista, b.nombre AS bodega,
                           b.direccion AS bodega_direccion
                    FROM devolucion d
                    JOIN motivo_devolucion md ON md.id = d.motivo_devolucion_id
                    JOIN pedido p ON p.id = d.pedido_id
                    JOIN cliente c ON c.id = d.cliente_id
                    LEFT JOIN transportista t ON t.id = d.transportista_id
                    LEFT JOIN bodega b ON b.id = d.bodega_id
                    WHERE d.id = ? AND d.cliente_id = ?""", id, clienteActualId());
        } else {
            filas = pg.queryForList("""
                    SELECT d.id, d.numero, d.estado, d.descripcion, d.monto_total,
                           d.monto_reembolsado, d.metodo_reembolso, d.fecha_reembolso,
                           d.guia_retorno, d.motivo_rechazo, d.fecha_creacion,
                           d.ticket_soporte_id, d.pedido_id,
                           md.nombre AS motivo, p.numero AS numero_pedido,
                           c.nombre || ' ' || COALESCE(c.apellido,'') AS cliente,
                           c.email AS cliente_email,
                           t.nombre AS transportista, b.nombre AS bodega,
                           b.direccion AS bodega_direccion
                    FROM devolucion d
                    JOIN motivo_devolucion md ON md.id = d.motivo_devolucion_id
                    JOIN pedido p ON p.id = d.pedido_id
                    LEFT JOIN cliente c ON c.id = d.cliente_id
                    LEFT JOIN transportista t ON t.id = d.transportista_id
                    LEFT JOIN bodega b ON b.id = d.bodega_id
                    WHERE d.id = ?""", id);
        }
        if (filas.isEmpty()) {
            throw new NoSuchElementException("No existe la devolución " + id);
        }
        Map<String, Object> d = filas.get(0);
        d.put("detalles", pg.queryForList("""
                SELECT dd.id, dd.cantidad, dd.estado_producto, dd.accion,
                       dd.resultado_inspeccion, dd.nota_inspeccion,
                       pd.sku, pd.nombre_producto, pd.precio_unitario
                FROM devolucion_detalle dd
                JOIN pedido_detalle pd ON pd.id = dd.pedido_detalle_id
                WHERE dd.devolucion_id = ? ORDER BY dd.id""", id));
        // Línea de tiempo: quién hizo qué y cuándo. grp_cliente no lee usuario,
        // así que su consulta no la toca (autor genérico), patrón de soporte.
        if (esCliente()) {
            d.put("historial", pg.queryForList("""
                    SELECT h.estado, h.comentario, h.fecha_creacion,
                           CASE WHEN h.cliente_id IS NOT NULL THEN 'Cliente'
                                ELSE 'Equipo RetailMind' END AS autor
                    FROM historial_estado_devolucion h
                    WHERE h.devolucion_id = ? ORDER BY h.id""", id));
        } else {
            d.put("historial", pg.queryForList("""
                    SELECT h.estado, h.comentario, h.fecha_creacion,
                           CASE WHEN h.usuario_id IS NOT NULL
                                THEN trim(concat(u.nombre, ' ', COALESCE(u.apellido, '')))
                                WHEN h.cliente_id IS NOT NULL THEN cl.nombre || ' (cliente)'
                                ELSE 'Sistema' END AS autor
                    FROM historial_estado_devolucion h
                    LEFT JOIN usuario u ON u.id = h.usuario_id
                    LEFT JOIN cliente cl ON cl.id = h.cliente_id
                    WHERE h.devolucion_id = ? ORDER BY h.id""", id));
        }
        // Número del ticket enganchado, solo para roles que leen ticket_soporte
        // (ADMIN/GERENTE/SOPORTE todos; CLIENTE el suyo por RLS). BODEGA/
        // DESPACHO/VENDEDOR no tienen SELECT sobre la tabla: ven solo el id.
        Object ticketId = d.get("ticket_soporte_id");
        if (ticketId != null && List.of("ADMIN", "GERENTE", "SOPORTE", "CLIENTE")
                .contains(rolActual() == null ? "" : rolActual().toUpperCase())) {
            List<String> nums = pg.queryForList(
                    "SELECT numero FROM ticket_soporte WHERE id = ?",
                    String.class, ((Number) ticketId).longValue());
            d.put("ticket_numero", nums.isEmpty() ? null : nums.get(0));
        }
        return d;
    }

    /** Motivos activos para el selector del cliente. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> motivosRef() {
        return pg.queryForList(
                "SELECT id, codigo, nombre FROM motivo_devolucion WHERE activo ORDER BY nombre");
    }

    /** Transportistas activos para la guía de retorno (soporte al aprobar). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> transportistasRef() {
        return pg.queryForList(
                "SELECT id, nombre FROM transportista WHERE activo ORDER BY nombre");
    }

    /**
     * ¿Qué puede devolver el cliente de este pedido? Estado del pedido, plazo
     * restante y cantidad disponible por ítem (comprada - ya devuelta en
     * devoluciones no rechazadas). Es la MISMA regla que valida solicitar().
     */
    @Transactional(readOnly = true)
    public Map<String, Object> elegibilidad(long pedidoId) {
        Map<String, Object> ped = pedidoPropio(pedidoId);
        String estado = (String) ped.get("estado");
        OffsetDateTime entrega = fechaEntrega(pedidoId);
        long diasRestantes = 0;
        boolean dentroPlazo = false;
        if (entrega != null) {
            long transcurridos = ChronoUnit.DAYS.between(entrega, OffsetDateTime.now());
            diasRestantes = Math.max(0, PLAZO_DIAS_DEVOLUCION - transcurridos);
            dentroPlazo = transcurridos <= PLAZO_DIAS_DEVOLUCION;
        }
        boolean estadoElegible = List.of("entregado", "devuelto", "despachado").contains(estado);
        // 'despachado' = rechazo en puerta: aún no hay entrega, no corre plazo
        boolean elegible = estadoElegible && ("despachado".equals(estado) || dentroPlazo);

        List<Map<String, Object>> items = pg.queryForList("""
                SELECT pd.id AS pedido_detalle_id, pd.sku, pd.nombre_producto,
                       pd.cantidad AS comprada, pd.precio_unitario,
                       COALESCE((SELECT SUM(dd.cantidad)
                                 FROM devolucion_detalle dd
                                 JOIN devolucion d ON d.id = dd.devolucion_id
                                 WHERE dd.pedido_detalle_id = pd.id
                                   AND d.estado <> 'rechazada'), 0) AS devuelta
                FROM pedido_detalle pd WHERE pd.pedido_id = ? ORDER BY pd.id""", pedidoId);
        items.forEach(it -> it.put("disponible",
                ((Number) it.get("comprada")).intValue() - ((Number) it.get("devuelta")).intValue()));

        Map<String, Object> res = new HashMap<>();
        res.put("pedido_id", pedidoId);
        res.put("numero_pedido", ped.get("numero"));
        res.put("estado_pedido", estado);
        res.put("fecha_entrega", entrega);
        res.put("plazo_dias", PLAZO_DIAS_DEVOLUCION);
        res.put("dias_restantes", diasRestantes);
        res.put("elegible", elegible && items.stream()
                .anyMatch(it -> ((Number) it.get("disponible")).intValue() > 0));
        res.put("items", items);
        return res;
    }

    // ── 1) SOLICITAR (CLIENTE) ───────────────────────────────────────────

    public record ItemSolicitud(long pedidoDetalleId, int cantidad, String estadoProducto) {}

    /**
     * El cliente solicita la devolución desde Mis Pedidos. Compuertas: pedido
     * propio entregado/devuelto dentro del plazo (o despachado = rechazo en
     * puerta), cantidades dentro de lo comprado menos lo ya devuelto (las
     * devoluciones rechazadas liberan su cupo). Crea el TICKET de soporte
     * categoría 'Devolución' y lo engancha (ticket_soporte_id) para que
     * soporte gestione el caso desde su bandeja. NO toca stock.
     */
    @Transactional
    public Map<String, Object> solicitar(long pedidoId, String motivoCodigo,
                                         String descripcion, List<ItemSolicitud> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("La devolución requiere al menos un ítem");
        }
        Map<String, Object> ped = pedidoPropio(pedidoId);
        String estado = (String) ped.get("estado");
        if (!List.of("entregado", "devuelto", "despachado").contains(estado)) {
            throw new IllegalStateException("Solo se puede solicitar la devolución de un "
                    + "pedido entregado (o despachado, si lo rechazaste en la puerta); "
                    + "estado actual: '" + estado + "'");
        }
        if (!"despachado".equals(estado)) {
            OffsetDateTime entrega = fechaEntrega(pedidoId);
            if (entrega == null) {
                throw new IllegalStateException("El pedido no registra fecha de entrega; "
                        + "contacta a soporte para gestionar la devolución");
            }
            long transcurridos = ChronoUnit.DAYS.between(entrega, OffsetDateTime.now());
            if (transcurridos > PLAZO_DIAS_DEVOLUCION) {
                throw new IllegalStateException("El plazo de devolución venció: pasaron "
                        + transcurridos + " días desde la entrega y el máximo es "
                        + PLAZO_DIAS_DEVOLUCION + " días");
            }
        }
        List<Long> motivos = pg.queryForList(
                "SELECT id FROM motivo_devolucion WHERE codigo = ? AND activo",
                Long.class, motivoCodigo);
        Long motivoId = motivos.isEmpty() ? null : motivos.get(0);
        if (motivoId == null) {
            throw new IllegalArgumentException(
                    "El motivo de devolución '" + motivoCodigo + "' no existe o está inactivo");
        }

        // Valida ítems ANTES de crear nada (mensajes claros, sin basura a medias)
        for (ItemSolicitud it : items) {
            if (it.cantidad() <= 0) {
                throw new IllegalArgumentException("La cantidad a devolver debe ser mayor a cero");
            }
            List<Map<String, Object>> dets = pg.queryForList("""
                    SELECT precio_unitario, cantidad, sku
                    FROM pedido_detalle WHERE id = ? AND pedido_id = ?""",
                    it.pedidoDetalleId(), pedidoId);
            if (dets.isEmpty()) {
                throw new IllegalArgumentException(
                        "El ítem " + it.pedidoDetalleId() + " no pertenece al pedido");
            }
            Map<String, Object> det = dets.get(0);
            int comprada = ((Number) det.get("cantidad")).intValue();
            Integer devueltas = pg.queryForObject(SQL_DEVUELTAS_POR_DETALLE,
                    Integer.class, it.pedidoDetalleId());
            int ya = devueltas == null ? 0 : devueltas;
            if (ya + it.cantidad() > comprada) {
                throw new IllegalArgumentException("No puedes devolver " + it.cantidad()
                        + " del SKU " + det.get("sku") + ": compraste " + comprada
                        + " y ya hay " + ya + " en devoluciones previas o en curso");
            }
        }

        // Ticket de soporte 'Devolución' (prioridad automática por categoría)
        List<Long> categorias = pg.queryForList("""
                SELECT id FROM categoria_ticket
                WHERE lower(nombre) = 'devolución' AND activo""", Long.class);
        Long categoriaId = categorias.isEmpty() ? null : categorias.get(0);
        String numero = siguienteNumero("DV");
        Map<String, Object> ticket = soporte.crearTicket(null, categoriaId, pedidoId,
                "Devolución " + numero + " del pedido " + ped.get("numero"),
                (descripcion == null || descripcion.isBlank()
                        ? "Solicitud de devolución" : descripcion.trim())
                        + " · Motivo: " + motivoCodigo);

        // monto_total NO se escribe: lo mantiene el trigger de devolucion_detalle
        Long devolucionId = pg.queryForObject("""
                INSERT INTO devolucion (numero, pedido_id, cliente_id, motivo_devolucion_id,
                                        ticket_soporte_id, estado, descripcion)
                VALUES (?, ?, ?, ?, ?, 'solicitada', ?)
                RETURNING id""",
                Long.class, numero, pedidoId, clienteActualId(), motivoId,
                ((Number) ticket.get("id")).longValue(),
                descripcion == null || descripcion.isBlank() ? null : descripcion.trim());

        for (ItemSolicitud it : items) {
            String estadoProducto = it.estadoProducto() != null
                    && List.of("nuevo", "abierto", "danado").contains(it.estadoProducto())
                    ? it.estadoProducto() : "nuevo";
            pg.update("""
                    INSERT INTO devolucion_detalle
                        (devolucion_id, pedido_detalle_id, cantidad, estado_producto, accion)
                    VALUES (?, ?, ?, ?, 'reembolso')""",
                    devolucionId, it.pedidoDetalleId(), it.cantidad(), estadoProducto);
        }
        historial(devolucionId, "solicitada",
                "Solicitud del cliente (" + items.size() + " ítem/s) — ticket "
                        + ticket.get("numero"));
        return obtener(devolucionId);
    }

    // ── 2) REVISIÓN DE SOPORTE ───────────────────────────────────────────

    /** SOPORTE toma el caso: solicitada → en_revision (paso opcional). */
    @Transactional
    public Map<String, Object> iniciarRevision(long id) {
        exigirTransicion(id, Set.of("solicitada"), "en_revision");
        pg.update("UPDATE devolucion SET estado = 'en_revision' WHERE id = ?", id);
        historial(id, "en_revision", "Soporte está revisando la solicitud");
        ticketEnProceso(id);
        return obtener(id);
    }

    /**
     * SOPORTE aprueba: genera la GUÍA DE RETORNO (número + transportista +
     * bodega destino) que el cliente descarga en PDF. Defaults: primer
     * transportista activo y bodega principal.
     */
    @Transactional
    public Map<String, Object> aprobar(long id, Long transportistaId, Long bodegaId) {
        exigirTransicion(id, Set.of("solicitada", "en_revision"), "aprobada");
        List<Long> transportistas = transportistaId != null ? List.of(transportistaId)
                : pg.queryForList(
                        "SELECT id FROM transportista WHERE activo ORDER BY id LIMIT 1", Long.class);
        Long transportista = transportistas.isEmpty() ? null : transportistas.get(0);
        if (transportista == null) {
            throw new IllegalStateException("No hay transportistas activos para la guía de retorno");
        }
        List<Long> bodegas = bodegaId != null ? List.of(bodegaId)
                : pg.queryForList(
                        "SELECT id FROM bodega WHERE es_principal AND activo ORDER BY id LIMIT 1",
                        Long.class);
        Long bodega = bodegas.isEmpty() ? null : bodegas.get(0);
        if (bodega == null) {
            throw new IllegalStateException("No hay bodega principal activa para recibir el retorno");
        }
        String guia = "RET-" + siguienteNumero("X").substring(2);
        pg.update("""
                UPDATE devolucion
                SET estado = 'aprobada', transportista_id = ?, bodega_id = ?,
                    guia_retorno = ?, usuario_gestiona_id = ?
                WHERE id = ?""", transportista, bodega, guia, usuarioActualId(), id);
        String nombreTransportista = pg.queryForObject(
                "SELECT nombre FROM transportista WHERE id = ?", String.class, transportista);
        historial(id, "aprobada", "Aprobada por soporte — guía de retorno " + guia
                + " (" + nombreTransportista + ")");
        mensajeTicket(id, "Tu devolución fue APROBADA. Descarga la guía de retorno " + guia
                + " desde Mis Devoluciones, pégala al paquete y entrégalo a "
                + nombreTransportista + ".");
        ticketEnProceso(id);
        return obtener(id);
    }

    /** SOPORTE rechaza con motivo; estado TERMINAL y ticket resuelto. */
    @Transactional
    public Map<String, Object> rechazar(long id, String motivo) {
        if (motivo == null || motivo.isBlank()) {
            throw new IllegalArgumentException("El motivo del rechazo es requerido");
        }
        exigirTransicion(id, Set.of("solicitada", "en_revision"), "rechazada");
        pg.update("""
                UPDATE devolucion
                SET estado = 'rechazada', motivo_rechazo = ?, usuario_gestiona_id = ?
                WHERE id = ?""", motivo.trim(), usuarioActualId(), id);
        historial(id, "rechazada", "Rechazada por soporte: " + motivo.trim());
        mensajeTicket(id, "Tu solicitud de devolución fue RECHAZADA. Motivo: " + motivo.trim()
                + ". Si no estás de acuerdo puedes responder este ticket.");
        ticketResuelto(id);
        return obtener(id);
    }

    // ── 3-4) LOGÍSTICA INVERSA (DESPACHO / BODEGA) ───────────────────────

    /** DESPACHO: el cliente entregó el paquete al transportista. */
    @Transactional
    public Map<String, Object> marcarTransito(long id, String observacion) {
        exigirTransicion(id, Set.of("aprobada"), "en_transito");
        pg.update("UPDATE devolucion SET estado = 'en_transito' WHERE id = ?", id);
        historial(id, "en_transito", "Paquete en tránsito hacia el almacén"
                + (observacion != null && !observacion.isBlank() ? " · " + observacion.trim() : ""));
        return obtener(id);
    }

    /** DESPACHO/BODEGA: el paquete llegó al almacén (aún sin abrir/inspeccionar). */
    @Transactional
    public Map<String, Object> marcarRecibida(long id, String observacion) {
        exigirTransicion(id, Set.of("en_transito"), "recibida");
        pg.update("UPDATE devolucion SET estado = 'recibida' WHERE id = ?", id);
        historial(id, "recibida", "Paquete recibido en el almacén; pendiente de inspección"
                + (observacion != null && !observacion.isBlank() ? " · " + observacion.trim() : ""));
        return obtener(id);
    }

    // ── 5) INSPECCIÓN DE CALIDAD (BODEGA) — único punto de reingreso ─────

    public record ItemInspeccion(long devolucionDetalleId, String resultado, String nota) {}

    /**
     * BODEGA registra el resultado POR ÍTEM (todos los ítems, de una vez):
     *  - apto_reventa: reingresa a inventario AQUÍ (StockService, kardex
     *    entrada_devolucion_cliente) — único punto del proceso que toca stock.
     *  - defectuoso: NO entra al stock vendible; queda documentado como merma
     *    pendiente de devolución a proveedor (no existe devolucion_proveedor
     *    en la BD — ver DEUDA_TECNICA).
     *  - rechazado: daño imputable al cliente; ni stock ni reembolso.
     * Marca además el pedido como 'devuelto' (la mercancía retornó).
     */
    @Transactional
    public Map<String, Object> inspeccionar(long id, Long bodegaId, List<ItemInspeccion> items) {
        exigirTransicion(id, Set.of("recibida"), "inspeccionada");
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("La inspección requiere el resultado de cada ítem");
        }
        Map<String, Object> dev = pg.queryForMap(
                "SELECT pedido_id, bodega_id, numero FROM devolucion WHERE id = ?", id);
        long bodega = bodegaId != null ? bodegaId
                : dev.get("bodega_id") != null ? ((Number) dev.get("bodega_id")).longValue()
                : pg.queryForObject(
                        "SELECT id FROM bodega WHERE es_principal AND activo ORDER BY id LIMIT 1",
                        Long.class);

        List<Map<String, Object>> detalles = pg.queryForList("""
                SELECT dd.id, dd.cantidad, pd.producto_variante_id, pd.precio_unitario, pd.sku
                FROM devolucion_detalle dd
                JOIN pedido_detalle pd ON pd.id = dd.pedido_detalle_id
                WHERE dd.devolucion_id = ?""", id);
        Map<Long, ItemInspeccion> porId = new HashMap<>();
        for (ItemInspeccion it : items) {
            if (it.resultado() == null || !RESULTADOS_INSPECCION.contains(it.resultado())) {
                throw new IllegalArgumentException("Resultado de inspección inválido para el ítem "
                        + it.devolucionDetalleId() + ". Válidos: apto_reventa, defectuoso, rechazado");
            }
            porId.put(it.devolucionDetalleId(), it);
        }
        int aptos = 0;
        int defectuosos = 0;
        int rechazados = 0;
        for (Map<String, Object> det : detalles) {
            long detalleId = ((Number) det.get("id")).longValue();
            ItemInspeccion it = porId.remove(detalleId);
            if (it == null) {
                throw new IllegalArgumentException("Falta el resultado de inspección del ítem "
                        + det.get("sku") + " (id " + detalleId + "): la inspección es de TODOS los ítems");
            }
            pg.update("""
                    UPDATE devolucion_detalle
                    SET resultado_inspeccion = ?, nota_inspeccion = NULLIF(?, '')
                    WHERE id = ?""", it.resultado(), it.nota(), detalleId);
            int cantidad = ((Number) det.get("cantidad")).intValue();
            switch (it.resultado()) {
                case "apto_reventa" -> {
                    // ÚNICO reingreso de stock del proceso RMA
                    stock.mover(((Number) det.get("producto_variante_id")).longValue(), bodega,
                            "entrada_devolucion_cliente", cantidad,
                            "devolucion", id, (BigDecimal) det.get("precio_unitario"),
                            usuarioActualId(),
                            "Inspección RMA " + dev.get("numero") + ": apto para reventa");
                    aptos++;
                }
                case "defectuoso" -> defectuosos++;
                default -> rechazados++;
            }
        }
        if (!porId.isEmpty()) {
            throw new IllegalArgumentException(
                    "La inspección incluye ítems que no pertenecen a la devolución: " + porId.keySet());
        }

        pg.update("UPDATE devolucion SET estado = 'inspeccionada', bodega_id = ? WHERE id = ?",
                bodega, id);
        historial(id, "inspeccionada", "Inspección de calidad: " + aptos
                + " apto/s reingresado/s a stock, " + defectuosos
                + " defectuoso/s (merma, pendiente devolución a proveedor), "
                + rechazados + " rechazado/s (sin reembolso)");

        // La mercancía retornó: el pedido queda 'devuelto' (grant de columna a
        // grp_bodega, script 38) con su rastro en el historial del pedido.
        long pedidoId = ((Number) dev.get("pedido_id")).longValue();
        pg.update("""
                UPDATE pedido SET estado_pedido_id =
                    (SELECT id FROM estado_pedido WHERE codigo = 'devuelto')
                WHERE id = ?""", pedidoId);
        pg.update("""
                INSERT INTO historial_estado_pedido (pedido_id, estado_pedido_id, usuario_id, comentario)
                VALUES (?, (SELECT id FROM estado_pedido WHERE codigo = 'devuelto'), ?, ?)""",
                pedidoId, usuarioActualId(),
                "Devolución " + dev.get("numero") + " inspeccionada en bodega");
        return obtener(id);
    }

    // ── 6) REEMBOLSO (GERENTE/ADMIN) ─────────────────────────────────────

    /**
     * Reembolso SIMULADO según la inspección: pagan los ítems apto_reventa y
     * defectuoso; el rechazado (daño del cliente) no. Si nada es reembolsable
     * la devolución se cierra sin reembolso (endpoint cerrar).
     */
    @Transactional
    public Map<String, Object> reembolsar(long id, String metodo, String referencia) {
        exigirTransicion(id, Set.of("inspeccionada"), "reembolsada");
        if (metodo == null || !METODOS_REEMBOLSO.contains(metodo)) {
            throw new IllegalArgumentException("Método de reembolso inválido. Permitidos: "
                    + String.join(", ", METODOS_REEMBOLSO.stream().sorted().toList()));
        }
        BigDecimal monto = montoReembolsable(id);
        if (monto.signum() <= 0) {
            throw new IllegalStateException("Ningún ítem quedó con derecho a reembolso "
                    + "(todos fueron rechazados en la inspección); usa 'cerrar' para finalizar");
        }
        pg.update("""
                UPDATE devolucion
                SET estado = 'reembolsada', monto_reembolsado = ?, metodo_reembolso = ?,
                    fecha_reembolso = now()
                WHERE id = ?""", monto, metodo, id);
        historial(id, "reembolsada", "Reembolso procesado (simulado) por $" + monto
                + " vía " + metodo
                + (referencia != null && !referencia.isBlank() ? " · ref. " + referencia.trim() : ""));
        mensajeTicket(id, "Tu reembolso por $" + monto + " fue procesado vía " + metodo
                + ". Verás el crédito según los tiempos de tu banco.");
        return obtener(id);
    }

    // ── 7) CIERRE (SOPORTE/ADMIN) ────────────────────────────────────────

    /**
     * Cierra el caso: tras el reembolso, o tras una inspección donde ningún
     * ítem resultó reembolsable. El ticket enganchado queda 'resuelto'.
     */
    @Transactional
    public Map<String, Object> cerrar(long id) {
        String actual = estadoDevolucion(id);
        if ("cerrada".equals(actual)) {
            throw new IllegalStateException("La devolución ya está cerrada");
        }
        boolean sinReembolso = "inspeccionada".equals(actual)
                && montoReembolsable(id).signum() == 0;
        if (!"reembolsada".equals(actual) && !sinReembolso) {
            throw new IllegalStateException("Solo se cierra una devolución reembolsada "
                    + "(o inspeccionada sin ítems reembolsables); estado actual: '" + actual
                    + "'" + ("inspeccionada".equals(actual)
                            ? ". Hay monto pendiente de reembolso: procesa el reembolso primero" : ""));
        }
        pg.update("UPDATE devolucion SET estado = 'cerrada' WHERE id = ?", id);
        historial(id, "cerrada", sinReembolso
                ? "Cerrada sin reembolso (ítems rechazados en inspección)"
                : "Proceso de devolución concluido");
        mensajeTicket(id, "Tu caso de devolución quedó concluido. ¡Gracias por tu paciencia!");
        ticketResuelto(id);
        return obtener(id);
    }

    // ── Utilitarios ──────────────────────────────────────────────────────

    /** Suma reembolsable según inspección: apto_reventa + defectuoso. */
    private BigDecimal montoReembolsable(long id) {
        return pg.queryForObject("""
                SELECT COALESCE(SUM(pd.precio_unitario * dd.cantidad), 0)
                FROM devolucion_detalle dd
                JOIN pedido_detalle pd ON pd.id = dd.pedido_detalle_id
                WHERE dd.devolucion_id = ?
                  AND dd.resultado_inspeccion IN ('apto_reventa', 'defectuoso')""",
                BigDecimal.class, id);
    }

    /** Pedido del cliente autenticado (o cualquiera para el personal). */
    private Map<String, Object> pedidoPropio(long pedidoId) {
        List<Map<String, Object>> filas = esCliente()
                ? pg.queryForList("""
                        SELECT p.id, p.numero, ep.codigo AS estado
                        FROM pedido p JOIN estado_pedido ep ON ep.id = p.estado_pedido_id
                        WHERE p.id = ? AND p.cliente_id = ?""", pedidoId, clienteActualId())
                : pg.queryForList("""
                        SELECT p.id, p.numero, ep.codigo AS estado
                        FROM pedido p JOIN estado_pedido ep ON ep.id = p.estado_pedido_id
                        WHERE p.id = ?""", pedidoId);
        if (filas.isEmpty()) {
            throw new NoSuchElementException("No existe el pedido " + pedidoId);
        }
        return filas.get(0);
    }

    /** Fecha real de entrega: el envío la registra; el historial es respaldo. */
    private OffsetDateTime fechaEntrega(long pedidoId) {
        OffsetDateTime entrega = pg.queryForObject(
                "SELECT max(fecha_entrega_real) FROM envio WHERE pedido_id = ?",
                OffsetDateTime.class, pedidoId);
        if (entrega != null) return entrega;
        return pg.queryForObject("""
                SELECT max(h.fecha_creacion)
                FROM historial_estado_pedido h
                JOIN estado_pedido ep ON ep.id = h.estado_pedido_id
                WHERE h.pedido_id = ? AND ep.codigo = 'entregado'""",
                OffsetDateTime.class, pedidoId);
    }

    private String estadoDevolucion(long id) {
        List<String> estados = esCliente()
                ? pg.queryForList("SELECT estado FROM devolucion WHERE id = ? AND cliente_id = ?",
                        String.class, id, clienteActualId())
                : pg.queryForList("SELECT estado FROM devolucion WHERE id = ?", String.class, id);
        if (estados.isEmpty()) {
            throw new NoSuchElementException("No existe la devolución " + id);
        }
        return estados.get(0);
    }

    /** Compuerta central: el estado actual debe estar entre los permitidos. */
    private void exigirTransicion(long id, Set<String> desde, String hacia) {
        String actual = estadoDevolucion(id);
        if (actual.equals(hacia)) {
            throw new IllegalStateException("La devolución ya está en estado '" + hacia + "'");
        }
        if (!desde.contains(actual)) {
            throw new IllegalStateException("Transición inválida: '" + actual + "' → '" + hacia
                    + "'. Este paso requiere que la devolución esté en: "
                    + String.join(" o ", desde.stream().sorted().toList()));
        }
    }

    /** Historial con autor del JWT: cliente_id si es CLIENTE, usuario_id si es personal. */
    private void historial(long devolucionId, String estado, String comentario) {
        if (esCliente()) {
            pg.update("""
                    INSERT INTO historial_estado_devolucion
                        (devolucion_id, estado, cliente_id, comentario)
                    VALUES (?, ?, ?, ?)""", devolucionId, estado, clienteActualId(), comentario);
        } else {
            pg.update("""
                    INSERT INTO historial_estado_devolucion
                        (devolucion_id, estado, usuario_id, comentario)
                    VALUES (?, ?, ?, ?)""", devolucionId, estado, usuarioActualId(), comentario);
        }
    }

    /** Mensaje VISIBLE en el hilo del ticket enganchado (si existe). */
    private void mensajeTicket(long devolucionId, String mensaje) {
        Long ticketId = ticketDe(devolucionId);
        if (ticketId == null) return;
        pg.update("""
                INSERT INTO mensaje_ticket (ticket_soporte_id, usuario_id, mensaje)
                VALUES (?, ?, ?)""", ticketId, usuarioActualId(), mensaje);
    }

    /** El ticket enganchado pasa a 'en_proceso' si seguía 'abierto'. */
    private void ticketEnProceso(long devolucionId) {
        Long ticketId = ticketDe(devolucionId);
        if (ticketId == null) return;
        pg.update("""
                UPDATE ticket_soporte SET estado = 'en_proceso',
                       asignado_usuario_id = COALESCE(asignado_usuario_id, ?)
                WHERE id = ? AND estado = 'abierto'""", usuarioActualId(), ticketId);
    }

    /** El ticket enganchado queda 'resuelto' (el cliente puede reabrir respondiendo). */
    private void ticketResuelto(long devolucionId) {
        Long ticketId = ticketDe(devolucionId);
        if (ticketId == null) return;
        pg.update("""
                UPDATE ticket_soporte SET estado = 'resuelto'
                WHERE id = ? AND estado NOT IN ('resuelto', 'cerrado')""", ticketId);
    }

    private Long ticketDe(long devolucionId) {
        List<Long> ids = pg.queryForList(
                "SELECT ticket_soporte_id FROM devolucion WHERE id = ?", Long.class, devolucionId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private String siguienteNumero(String prefijo) {
        return pg.queryForObject(
                "SELECT ? || '-' || to_char(now(), 'YYYYMMDD') || '-' || lpad(floor(random()*100000)::text, 5, '0')",
                String.class, prefijo);
    }

    private boolean esCliente() {
        return "CLIENTE".equalsIgnoreCase(rolActual());
    }

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
