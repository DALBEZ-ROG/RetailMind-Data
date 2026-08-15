package com.retailmind.compras;

import java.math.BigDecimal;
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

import com.retailmind.auditoria.AuditoriaService;
import com.retailmind.auth.AppUserPrincipal;
import com.retailmind.inventario.StockService;

/**
 * Devolución a proveedor / gestión de producto defectuoso (script 45) — el
 * espejo del RMA pero hacia el proveedor.
 *
 * ORÍGENES del ítem defectuoso (pool item_defectuoso, estado 'pendiente'):
 *  - RMA: la inspección de bodega marca 'defectuoso' (DevolucionService) y el
 *    ítem cae aquí automáticamente. NUNCA estuvo en stock vendible.
 *  - RECEPCIÓN: cantidad_rechazada al recibir cae aquí automáticamente
 *    (ComprasService.registrarRecepcion; jamás entró a stock) o BODEGA marca
 *    DESPUÉS unidades ya recibidas (aquí SÍ salen del stock vendible vía
 *    StockService con kardex salida_devolucion_proveedor).
 *
 * CICLO (compuertas aquí; un rol por transición vía SecurityConfig):
 *   registrada (COMPRAS agrupa pendientes de UN proveedor)
 *     → enviada  (sale al proveedor; SIN movimiento de stock vendible: la
 *                 mercancía nunca estuvo o ya salió al marcarse defectuosa)
 *     → resuelta (nota_credito = crédito SIMULADO, sin stock;
 *                 reposicion = reingreso APTO vía StockService, kardex
 *                 entrada_reposicion_proveedor — ÚNICO reingreso del proceso)
 *     → cerrada
 *
 * Todo @Transactional (SET LOCAL ROLE); autor del JWT vía AuditoriaService y
 * columnas registrado_por/usuario_id.
 */
@Service
public class DevolucionProveedorService {

    private static final Set<String> ESTADOS =
            Set.of("registrada", "enviada", "resuelta", "cerrada");
    private static final Set<String> ESTADOS_ITEM =
            Set.of("pendiente", "en_devolucion", "resuelto");
    private static final Set<String> RESOLUCIONES = Set.of("nota_credito", "reposicion");

    private final JdbcTemplate pg;
    private final StockService stock;
    private final AuditoriaService auditoria;

    public DevolucionProveedorService(@Qualifier("pgJdbcTemplate") JdbcTemplate pg,
                                      StockService stock, AuditoriaService auditoria) {
        this.pg = pg;
        this.stock = stock;
        this.auditoria = auditoria;
    }

    // ── 1) Pool de ítems defectuosos ─────────────────────────────────────

    private static final String SEL_ITEMS = """
            SELECT it.id, it.cantidad, it.origen, it.estado, it.nota, it.costo_unitario,
                   it.fecha_creacion, it.proveedor_id,
                   pv.sku, pr.nombre AS producto, b.nombre AS bodega,
                   prov.razon_social AS proveedor,
                   u.nombre || ' ' || COALESCE(u.apellido, '') AS registrado_por,
                   d.numero  AS numero_rma,
                   rm.numero AS numero_recepcion,
                   dp.numero AS numero_devolucion_proveedor
            FROM item_defectuoso it
            JOIN producto_variante pv ON pv.id = it.producto_variante_id
            JOIN producto pr ON pr.id = pv.producto_id
            JOIN bodega b ON b.id = it.bodega_id
            LEFT JOIN proveedor prov ON prov.id = it.proveedor_id
            LEFT JOIN usuario u ON u.id = it.registrado_por
            LEFT JOIN devolucion_detalle dd ON dd.id = it.devolucion_detalle_id
            LEFT JOIN devolucion d ON d.id = dd.devolucion_id
            LEFT JOIN recepcion_detalle rd ON rd.id = it.recepcion_detalle_id
            LEFT JOIN recepcion_mercancia rm ON rm.id = rd.recepcion_mercancia_id
            LEFT JOIN devolucion_proveedor_detalle dpd ON dpd.item_defectuoso_id = it.id
            LEFT JOIN devolucion_proveedor dp ON dp.id = dpd.devolucion_proveedor_id
            """;

    /**
     * Pool de ítems defectuosos, PAGINADO EN EL SERVIDOR.
     *
     * Devolvía los 27.831 ítems (12,66 MB medidos) y la pantalla les pintaba a
     * cada uno su casilla de selección. El filtro por `estado` ya se resolvía
     * aquí, en SQL, y sigue igual: `total` cuenta el conjunto FILTRADO
     * (27.803 pendientes, 25 resueltos, 3 en devolución).
     *
     * OJO con la selección múltiple: `seleccion` es un Set de ids que ahora
     * sobrevive al cambio de página. Eso es deliberado —COMPRAS agrupa ítems de
     * varias páginas en una misma devolución— y el backend valida cada id.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> listarItems(String estado, Integer page, Integer size,
                                           Boolean conTotal) {
        StringBuilder w = new StringBuilder(" WHERE 1 = 1\n");
        List<Object> args = new java.util.ArrayList<>();
        if (estado != null && !estado.isBlank()) {
            if (!ESTADOS_ITEM.contains(estado)) {
                throw new IllegalArgumentException("Estado de ítem inválido: '" + estado
                        + "'. Válidos: " + String.join(", ", ESTADOS_ITEM.stream().sorted().toList()));
            }
            w.append(" AND it.estado = ?\n");
            args.add(estado);
        }
        String where = w.toString();
        Object[] a = args.toArray();

        String sqlItems = SEL_ITEMS + where
                + " ORDER BY CASE it.estado WHEN 'pendiente' THEN 0"
                + " WHEN 'en_devolucion' THEN 1 ELSE 2 END, it.id DESC";

        int pag = com.retailmind.comun.Paginacion.pagina(page);
        int tam = com.retailmind.comun.Paginacion.tamano(size);

        if (Boolean.FALSE.equals(conTotal)) {
            return com.retailmind.comun.Paginacion.paginarSinTotal(pg, sqlItems, a, pag, tam);
        }
        // El conteo no arrastra los ocho joins que solo sirven para pintar.
        String sqlCount = "SELECT count(*) FROM item_defectuoso it" + where;
        return com.retailmind.comun.Paginacion.paginar(pg, sqlItems, sqlCount, a, pag, tam);
    }

    /**
     * Referencia para el marcado posterior: líneas de recepciones CONFIRMADAS
     * recientes con su margen aún marcable (recibidas - marcadas después).
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarDetallesRecepcion() {
        return pg.queryForList("""
                SELECT rd.id, rm.numero AS recepcion, rm.fecha_recepcion,
                       pv.sku, pr.nombre AS producto, b.nombre AS bodega,
                       rd.cantidad_recibida,
                       rd.cantidad_recibida
                         - (COALESCE((SELECT SUM(it.cantidad) FROM item_defectuoso it
                                      WHERE it.recepcion_detalle_id = rd.id), 0)
                            - rd.cantidad_rechazada) AS marcable
                FROM recepcion_detalle rd
                JOIN recepcion_mercancia rm ON rm.id = rd.recepcion_mercancia_id
                JOIN orden_compra_detalle ocd ON ocd.id = rd.orden_compra_detalle_id
                JOIN producto_variante pv ON pv.id = ocd.producto_variante_id
                JOIN producto pr ON pr.id = pv.producto_id
                JOIN bodega b ON b.id = rm.bodega_id
                WHERE rm.estado = 'confirmada'
                ORDER BY rd.id DESC LIMIT 50""");
    }

    /**
     * BODEGA marca como defectuosas unidades YA RECIBIDAS de una recepción
     * (el defecto se detectó después de recibir). Criterio de stock: esas
     * unidades SÍ estaban en el stock vendible, así que aquí SALEN con kardex
     * salida_devolucion_proveedor. (El rechazo EN puerta no pasa por aquí:
     * cantidad_rechazada nunca entró a stock y su ítem nace en la recepción.)
     */
    @Transactional
    public Map<String, Object> marcarDefectuosoRecepcion(long recepcionDetalleId,
                                                         int cantidad, String nota) {
        if (cantidad <= 0) {
            throw new IllegalArgumentException("La cantidad defectuosa debe ser mayor a cero");
        }
        List<Map<String, Object>> filas = pg.queryForList("""
                SELECT rd.id, rd.cantidad_recibida, rd.cantidad_rechazada,
                       rm.numero, rm.estado, rm.bodega_id,
                       oc.proveedor_id, ocd.producto_variante_id, ocd.precio_unitario, pv.sku
                FROM recepcion_detalle rd
                JOIN recepcion_mercancia rm ON rm.id = rd.recepcion_mercancia_id
                JOIN orden_compra_detalle ocd ON ocd.id = rd.orden_compra_detalle_id
                JOIN orden_compra oc ON oc.id = ocd.orden_compra_id
                JOIN producto_variante pv ON pv.id = ocd.producto_variante_id
                WHERE rd.id = ?""", recepcionDetalleId);
        if (filas.isEmpty()) {
            throw new NoSuchElementException("No existe el detalle de recepción " + recepcionDetalleId);
        }
        Map<String, Object> det = filas.get(0);
        if (!"confirmada".equals(det.get("estado"))) {
            throw new IllegalStateException("La recepción " + det.get("numero")
                    + " no está confirmada (estado: " + det.get("estado")
                    + "); no admite marcado de defectuosos");
        }
        // Tope: lo marcado DESPUÉS de recibir sale de las unidades recibidas.
        // (sum de ítems del detalle - rechazo en puerta = marcadas posteriores)
        int yaMarcadas = pg.queryForObject("""
                SELECT COALESCE(SUM(cantidad), 0) FROM item_defectuoso
                WHERE recepcion_detalle_id = ?""", Integer.class, recepcionDetalleId)
                - ((Number) det.get("cantidad_rechazada")).intValue();
        int recibidas = ((Number) det.get("cantidad_recibida")).intValue();
        if (yaMarcadas + cantidad > recibidas) {
            throw new IllegalArgumentException("No se pueden marcar " + cantidad
                    + " defectuosas del SKU " + det.get("sku") + ": se recibieron " + recibidas
                    + " y ya hay " + yaMarcadas + " marcadas de esta recepción");
        }
        long varianteId = ((Number) det.get("producto_variante_id")).longValue();
        long bodegaId = ((Number) det.get("bodega_id")).longValue();
        Long itemId = pg.queryForObject("""
                INSERT INTO item_defectuoso
                    (producto_variante_id, bodega_id, cantidad, origen, recepcion_detalle_id,
                     proveedor_id, costo_unitario, nota, registrado_por)
                VALUES (?, ?, ?, 'recepcion', ?, ?, ?, NULLIF(?, ''), ?)
                RETURNING id""", Long.class,
                varianteId, bodegaId, cantidad, recepcionDetalleId,
                det.get("proveedor_id"), det.get("precio_unitario"), nota, usuarioActualId());

        // El defectuoso deja de ser vendible: salida de stock con su kardex
        stock.mover(varianteId, bodegaId, "salida_devolucion_proveedor", cantidad,
                "item_defectuoso", itemId, (BigDecimal) det.get("precio_unitario"),
                usuarioActualId(), "Defectuoso detectado tras la recepción " + det.get("numero")
                        + "; pendiente de devolución a proveedor");

        auditoria.registrar("item_defectuoso", itemId, "INSERT", null,
                Map.of("origen", "recepcion", "recepcionDetalleId", recepcionDetalleId,
                       "sku", det.get("sku"), "cantidad", cantidad,
                       "salidaStockVendible", true));
        return Map.of("id", itemId, "sku", det.get("sku"), "cantidad", cantidad,
                "estado", "pendiente");
    }

    /** COMPRAS asigna proveedor a un ítem RMA cuyo origen no fue rastreable. */
    @Transactional
    public Map<String, Object> asignarProveedor(long itemId, long proveedorId) {
        Map<String, Object> item = itemPorId(itemId);
        if (!"pendiente".equals(item.get("estado"))) {
            throw new IllegalStateException("El ítem defectuoso " + itemId
                    + " no está pendiente (estado: " + item.get("estado")
                    + "); su proveedor ya no se puede cambiar");
        }
        Boolean activo = pg.query("SELECT activo FROM proveedor WHERE id = ?",
                rs -> rs.next() ? rs.getBoolean(1) : null, proveedorId);
        if (activo == null) {
            throw new NoSuchElementException("No existe el proveedor " + proveedorId);
        }
        if (!activo) {
            throw new IllegalArgumentException("El proveedor " + proveedorId
                    + " está inactivo; no puede recibir devoluciones");
        }
        pg.update("UPDATE item_defectuoso SET proveedor_id = ? WHERE id = ?", proveedorId, itemId);
        auditoria.registrar("item_defectuoso", itemId, "UPDATE",
                Map.of("proveedorId", String.valueOf(item.get("proveedor_id"))),
                Map.of("proveedorId", proveedorId));
        return Map.of("id", itemId, "proveedorId", proveedorId);
    }

    // ── 2) Crear devolución a proveedor (COMPRAS agrupa) ─────────────────

    @Transactional
    public Map<String, Object> crear(long proveedorId, List<Long> itemIds, String observacion) {
        if (itemIds == null || itemIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "La devolución a proveedor requiere al menos un ítem defectuoso");
        }
        // Lock de los ítems elegidos (grp_compras tiene UPDATE de tabla completa)
        List<Map<String, Object>> items = pg.queryForList("""
                SELECT id, estado, proveedor_id, cantidad, costo_unitario
                FROM item_defectuoso
                WHERE id IN (%s)
                FOR UPDATE""".formatted(placeholders(itemIds.size())), itemIds.toArray());
        if (items.size() != itemIds.size()) {
            throw new NoSuchElementException("Algún ítem defectuoso no existe (enviados: "
                    + itemIds + ", encontrados: " + items.size() + ")");
        }
        for (Map<String, Object> it : items) {
            if (!"pendiente".equals(it.get("estado"))) {
                throw new IllegalStateException("El ítem defectuoso " + it.get("id")
                        + " no está pendiente (estado: " + it.get("estado")
                        + "); solo ítems pendientes pueden agruparse en una devolución");
            }
            Object prov = it.get("proveedor_id");
            if (prov != null && ((Number) prov).longValue() != proveedorId) {
                throw new IllegalArgumentException("El ítem " + it.get("id")
                        + " pertenece a otro proveedor; una devolución agrupa ítems de UN solo proveedor");
            }
        }
        String numero = siguienteNumero("DP");
        Long id = pg.queryForObject("""
                INSERT INTO devolucion_proveedor
                    (numero, proveedor_id, estado, observacion, registrado_por)
                VALUES (?, ?, 'registrada', NULLIF(?, ''), ?)
                RETURNING id""", Long.class, numero, proveedorId, observacion, usuarioActualId());

        for (Map<String, Object> it : items) {
            pg.update("""
                    INSERT INTO devolucion_proveedor_detalle
                        (devolucion_proveedor_id, item_defectuoso_id, cantidad, costo_unitario)
                    VALUES (?, ?, ?, ?)""",
                    id, it.get("id"), it.get("cantidad"), it.get("costo_unitario"));
            // El ítem queda tomado por esta devolución (y con el proveedor fijado)
            pg.update("""
                    UPDATE item_defectuoso SET estado = 'en_devolucion', proveedor_id = ?
                    WHERE id = ?""", proveedorId, it.get("id"));
        }
        historial(id, "registrada", "Devolución a proveedor registrada con "
                + items.size() + " ítem/s defectuoso/s");
        auditoria.registrar("devolucion_proveedor", id, "INSERT", null,
                Map.of("numero", numero, "proveedorId", proveedorId, "items", itemIds));
        return obtener(id);
    }

    // ── 3) Transiciones ──────────────────────────────────────────────────

    /**
     * La mercancía defectuosa sale hacia el proveedor. SIN movimiento de
     * stock vendible: nunca estuvo (RMA / rechazo en puerta) o ya salió al
     * marcarse defectuosa tras la recepción.
     */
    @Transactional
    public Map<String, Object> enviar(long id, String nota) {
        exigirTransicion(id, Set.of("registrada"), "enviada");
        pg.update("""
                UPDATE devolucion_proveedor SET estado = 'enviada', fecha_envio = now()
                WHERE id = ?""", id);
        historial(id, "enviada", "Mercancía defectuosa enviada al proveedor (sin movimiento "
                + "de stock vendible)" + sufijo(nota));
        auditoria.registrar("devolucion_proveedor", id, "UPDATE",
                Map.of("estado", "registrada"), Map.of("estado", "enviada"));
        return obtener(id);
    }

    /**
     * COMPRAS registra la respuesta del proveedor:
     *  - nota_credito: crédito SIMULADO a favor por el valor de los ítems;
     *    el stock NO se mueve.
     *  - reposicion: el proveedor repone mercancía NUEVA; ÚNICO punto del
     *    proceso que reingresa stock apto (StockService, kardex
     *    entrada_reposicion_proveedor, en la bodega de origen de cada ítem).
     */
    @Transactional
    public Map<String, Object> resolver(long id, String tipoResolucion, String nota) {
        exigirTransicion(id, Set.of("enviada"), "resuelta");
        if (tipoResolucion == null || !RESOLUCIONES.contains(tipoResolucion)) {
            throw new IllegalArgumentException("Tipo de resolución inválido. Permitidos: "
                    + String.join(", ", RESOLUCIONES.stream().sorted().toList()));
        }
        Map<String, Object> dev = pg.queryForMap(
                "SELECT numero FROM devolucion_proveedor WHERE id = ?", id);

        BigDecimal montoCredito = null;
        String detalleHistorial;
        if ("nota_credito".equals(tipoResolucion)) {
            montoCredito = pg.queryForObject("""
                    SELECT COALESCE(SUM(cantidad * COALESCE(costo_unitario, 0)), 0)
                    FROM devolucion_proveedor_detalle
                    WHERE devolucion_proveedor_id = ?""", BigDecimal.class, id);
            detalleHistorial = "Nota de crédito del proveedor por $" + montoCredito
                    + " (simulada); el stock no se mueve";
        } else {
            List<Map<String, Object>> lineas = pg.queryForList("""
                    SELECT dpd.cantidad, dpd.costo_unitario,
                           it.producto_variante_id, it.bodega_id, pv.sku
                    FROM devolucion_proveedor_detalle dpd
                    JOIN item_defectuoso it ON it.id = dpd.item_defectuoso_id
                    JOIN producto_variante pv ON pv.id = it.producto_variante_id
                    WHERE dpd.devolucion_proveedor_id = ?""", id);
            int unidades = 0;
            for (Map<String, Object> l : lineas) {
                int cantidad = ((Number) l.get("cantidad")).intValue();
                // Reposición = mercancía nueva y APTA: reingresa al stock vendible
                stock.mover(((Number) l.get("producto_variante_id")).longValue(),
                        ((Number) l.get("bodega_id")).longValue(),
                        "entrada_reposicion_proveedor", cantidad,
                        "devolucion_proveedor", id, (BigDecimal) l.get("costo_unitario"),
                        usuarioActualId(),
                        "Reposición del proveedor · devolución " + dev.get("numero"));
                unidades += cantidad;
            }
            detalleHistorial = "El proveedor repuso la mercancía: " + unidades
                    + " unidad/es apta/s reingresada/s a stock (kardex entrada_reposicion_proveedor)";
        }
        pg.update("""
                UPDATE devolucion_proveedor
                SET estado = 'resuelta', tipo_resolucion = ?, monto_credito = ?,
                    nota_resolucion = NULLIF(?, ''), fecha_resolucion = now()
                WHERE id = ?""", tipoResolucion, montoCredito, nota, id);
        pg.update("""
                UPDATE item_defectuoso SET estado = 'resuelto'
                WHERE id IN (SELECT item_defectuoso_id FROM devolucion_proveedor_detalle
                             WHERE devolucion_proveedor_id = ?)""", id);
        historial(id, "resuelta", detalleHistorial + sufijo(nota));
        auditoria.registrar("devolucion_proveedor", id, "UPDATE",
                Map.of("estado", "enviada"),
                montoCredito != null
                        ? Map.of("estado", "resuelta", "tipoResolucion", tipoResolucion,
                                 "montoCredito", montoCredito)
                        : Map.of("estado", "resuelta", "tipoResolucion", tipoResolucion));
        return obtener(id);
    }

    @Transactional
    public Map<String, Object> cerrar(long id, String nota) {
        exigirTransicion(id, Set.of("resuelta"), "cerrada");
        pg.update("UPDATE devolucion_proveedor SET estado = 'cerrada' WHERE id = ?", id);
        historial(id, "cerrada", "Caso de devolución a proveedor concluido" + sufijo(nota));
        auditoria.registrar("devolucion_proveedor", id, "UPDATE",
                Map.of("estado", "resuelta"), Map.of("estado", "cerrada"));
        return obtener(id);
    }

    // ── 4) Consultas ─────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listar(String estado) {
        String filtro = null;
        if (estado != null && !estado.isBlank()) {
            if (!ESTADOS.contains(estado)) {
                throw new IllegalArgumentException("Estado inválido: '" + estado
                        + "'. Válidos: " + String.join(", ", ESTADOS.stream().sorted().toList()));
            }
            filtro = estado;
        }
        return pg.queryForList("""
                SELECT dp.id, dp.numero, dp.estado, dp.tipo_resolucion, dp.monto_credito,
                       dp.fecha_creacion, dp.fecha_envio, dp.fecha_resolucion,
                       prov.razon_social AS proveedor,
                       (SELECT COUNT(*) FROM devolucion_proveedor_detalle d
                        WHERE d.devolucion_proveedor_id = dp.id) AS total_items
                FROM devolucion_proveedor dp
                JOIN proveedor prov ON prov.id = dp.proveedor_id
                WHERE (?::text IS NULL OR dp.estado = ?::text)
                ORDER BY CASE dp.estado WHEN 'cerrada' THEN 1 ELSE 0 END, dp.id DESC""",
                filtro, filtro);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> obtener(long id) {
        List<Map<String, Object>> filas = pg.queryForList("""
                SELECT dp.id, dp.numero, dp.estado, dp.tipo_resolucion, dp.monto_credito,
                       dp.nota_resolucion, dp.observacion, dp.fecha_creacion, dp.fecha_envio,
                       dp.fecha_resolucion, dp.proveedor_id, prov.razon_social AS proveedor,
                       u.nombre || ' ' || COALESCE(u.apellido, '') AS registrado_por
                FROM devolucion_proveedor dp
                JOIN proveedor prov ON prov.id = dp.proveedor_id
                LEFT JOIN usuario u ON u.id = dp.registrado_por
                WHERE dp.id = ?""", id);
        if (filas.isEmpty()) {
            throw new NoSuchElementException("No existe la devolución a proveedor " + id);
        }
        Map<String, Object> dev = new HashMap<>(filas.get(0));
        dev.put("detalles", pg.queryForList("""
                SELECT dpd.id, dpd.cantidad, dpd.costo_unitario,
                       it.id AS item_defectuoso_id, it.origen, it.nota,
                       pv.sku, pr.nombre AS producto, b.nombre AS bodega,
                       ui.nombre || ' ' || COALESCE(ui.apellido, '') AS marcado_por,
                       d.numero  AS numero_rma,
                       rm.numero AS numero_recepcion
                FROM devolucion_proveedor_detalle dpd
                JOIN item_defectuoso it ON it.id = dpd.item_defectuoso_id
                JOIN producto_variante pv ON pv.id = it.producto_variante_id
                JOIN producto pr ON pr.id = pv.producto_id
                JOIN bodega b ON b.id = it.bodega_id
                LEFT JOIN usuario ui ON ui.id = it.registrado_por
                LEFT JOIN devolucion_detalle dd ON dd.id = it.devolucion_detalle_id
                LEFT JOIN devolucion d ON d.id = dd.devolucion_id
                LEFT JOIN recepcion_detalle rd ON rd.id = it.recepcion_detalle_id
                LEFT JOIN recepcion_mercancia rm ON rm.id = rd.recepcion_mercancia_id
                WHERE dpd.devolucion_proveedor_id = ?
                ORDER BY dpd.id""", id));
        dev.put("historial", pg.queryForList("""
                SELECT h.estado, h.comentario, h.fecha_creacion,
                       COALESCE(u.nombre || ' ' || COALESCE(u.apellido, ''), 'Sistema') AS autor
                FROM historial_devolucion_proveedor h
                LEFT JOIN usuario u ON u.id = h.usuario_id
                WHERE h.devolucion_proveedor_id = ?
                ORDER BY h.id""", id));
        return dev;
    }

    // ── Utilitarios ──────────────────────────────────────────────────────

    private void exigirTransicion(long id, Set<String> desde, String hacia) {
        List<String> estados = pg.queryForList(
                "SELECT estado FROM devolucion_proveedor WHERE id = ? FOR UPDATE",
                String.class, id);
        if (estados.isEmpty()) {
            throw new NoSuchElementException("No existe la devolución a proveedor " + id);
        }
        String actual = estados.get(0);
        if (hacia.equals(actual)) {
            throw new IllegalStateException("La devolución a proveedor ya está '" + hacia + "'");
        }
        if (!desde.contains(actual)) {
            throw new IllegalStateException("Transición inválida: la devolución a proveedor está '"
                    + actual + "' y pasar a '" + hacia + "' requiere estar en "
                    + String.join("/", desde.stream().sorted().toList()));
        }
    }

    private Map<String, Object> itemPorId(long itemId) {
        List<Map<String, Object>> filas = pg.queryForList(
                "SELECT id, estado, proveedor_id FROM item_defectuoso WHERE id = ?", itemId);
        if (filas.isEmpty()) {
            throw new NoSuchElementException("No existe el ítem defectuoso " + itemId);
        }
        return filas.get(0);
    }

    private void historial(long id, String estado, String comentario) {
        pg.update("""
                INSERT INTO historial_devolucion_proveedor
                    (devolucion_proveedor_id, estado, usuario_id, comentario)
                VALUES (?, ?, ?, ?)""", id, estado, usuarioActualId(), comentario);
    }

    private String sufijo(String nota) {
        return nota != null && !nota.isBlank() ? " · " + nota.trim() : "";
    }

    private String placeholders(int n) {
        return String.join(", ", java.util.Collections.nCopies(n, "?"));
    }

    private String siguienteNumero(String prefijo) {
        // Secuencia global (script 43): número único garantizado
        return pg.queryForObject(
                "SELECT ? || '-' || to_char(now(), 'YYYYMMDD') || '-' || nextval('seq_numero_documento')::text",
                String.class, prefijo);
    }

    private Long usuarioActualId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppUserPrincipal p) {
            return p.getUsuarioId();
        }
        return null;
    }
}
