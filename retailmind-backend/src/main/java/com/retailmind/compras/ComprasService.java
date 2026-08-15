package com.retailmind.compras;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.retailmind.auditoria.AuditoriaService;
import com.retailmind.auth.AppUserPrincipal;

/**
 * Ciclo de compra (Procure-to-Pay) sobre PostgreSQL.
 *
 * Reglas de motor que este servicio RESPETA (no replica):
 *  - orden_compra_detalle.subtotal y factura_compra_detalle.subtotal son
 *    GENERATED ALWAYS: jamás se insertan/actualizan.
 *  - Los totales de cabecera (orden_compra / factura_compra) los recalculan
 *    los triggers de la BD al escribir el detalle: la app solo los LEE.
 *  - Lo que SÍ hace la app: mover stock (inventario) + kardex
 *    (movimiento_inventario) en la recepción, crear la cuenta_por_pagar al
 *    facturar y liquidarla al pagar.
 */
@Service
public class ComprasService {

    private static final BigDecimal IVA_DEFECTO = new BigDecimal("15");

    private final JdbcTemplate pg;
    private final AuditoriaService auditoria;

    public ComprasService(@Qualifier("pgJdbcTemplate") JdbcTemplate pg,
                          AuditoriaService auditoria) {
        this.pg = pg;
        this.auditoria = auditoria;
    }

    // ── a) Emitir orden de compra ────────────────────────────────────────

    public record ItemOrden(long varianteId, int cantidad, BigDecimal precioUnitario,
                            BigDecimal ivaPorcentaje) {}

    @Transactional
    public Map<String, Object> emitirOrden(long proveedorId, long bodegaId, Long monedaId,
                                           String fechaEmision, String fechaEntregaEsperada,
                                           String observacion, List<ItemOrden> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("La orden requiere al menos un item");
        }
        for (ItemOrden it : items) {
            if (it.cantidad() <= 0) {
                throw new IllegalArgumentException(
                        "La cantidad de cada item debe ser mayor a cero");
            }
            if (it.precioUnitario() == null || it.precioUnitario().signum() <= 0) {
                throw new IllegalArgumentException(
                        "El precio unitario de cada item debe ser mayor a cero");
            }
        }
        // Fecha de emisión: OPCIONAL. El formulario NO la envía y la orden nace
        // HOY (comportamiento intacto); solo la carga de órdenes históricas por
        // API la informa (siembra de datos de meses pasados). Nunca futura.
        java.time.LocalDate emision = java.time.LocalDate.now();
        if (fechaEmision != null && !fechaEmision.isBlank()) {
            try {
                emision = java.time.LocalDate.parse(fechaEmision.trim());
            } catch (java.time.format.DateTimeParseException e) {
                throw new IllegalArgumentException(
                        "La fecha de emisión no es válida (formato AAAA-MM-DD)");
            }
            if (emision.isAfter(java.time.LocalDate.now())) {
                throw new IllegalArgumentException(
                        "La fecha de emisión no puede ser futura");
            }
        }
        // Fecha prometida por el proveedor (OTD-COM-05): OPCIONAL — el proveedor
        // puede no comprometer fecha al emitir. DECISIÓN (2026-07-22): se valida
        // contra la fecha de EMISIÓN de la orden, no contra hoy — para la captura
        // por formulario son equivalentes (la orden nace hoy) y una orden
        // histórica puede traer fechas prometidas de meses pasados sin bloquearse.
        if (fechaEntregaEsperada != null && !fechaEntregaEsperada.isBlank()) {
            java.time.LocalDate prometida;
            try {
                prometida = java.time.LocalDate.parse(fechaEntregaEsperada.trim());
            } catch (java.time.format.DateTimeParseException e) {
                throw new IllegalArgumentException(
                        "La fecha de entrega prometida no es válida (formato AAAA-MM-DD)");
            }
            if (prometida.isBefore(emision)) {
                throw new IllegalArgumentException(
                        "La fecha de entrega prometida no puede ser anterior a la "
                        + "fecha de emisión de la orden (" + emision + ")");
            }
        } else {
            fechaEntregaEsperada = null;
        }
        String numero = siguienteNumero("OC");
        Long ordenId = pg.queryForObject("""
                INSERT INTO orden_compra
                    (numero, proveedor_id, bodega_id, moneda_id, usuario_id, estado,
                     fecha_emision, fecha_entrega_esperada, observacion)
                VALUES (?, ?, ?, ?, ?, 'enviada', ?::date, ?::date, ?)
                RETURNING id""",
                Long.class, numero, proveedorId, bodegaId,
                monedaId != null ? monedaId : monedaBaseId(), usuarioActualId(),
                emision.toString(), fechaEntregaEsperada, observacion);

        for (ItemOrden it : items) {
            BigDecimal pct = it.ivaPorcentaje() != null ? it.ivaPorcentaje() : IVA_DEFECTO;
            BigDecimal impuesto = it.precioUnitario()
                    .multiply(BigDecimal.valueOf(it.cantidad()))
                    .multiply(pct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            // subtotal NO se inserta: es columna generada (cantidad * precio_unitario)
            pg.update("""
                    INSERT INTO orden_compra_detalle
                        (orden_compra_id, producto_variante_id, cantidad, precio_unitario, monto_impuesto)
                    VALUES (?, ?, ?, ?, ?)""",
                    ordenId, it.varianteId(), it.cantidad(), it.precioUnitario(), impuesto);
        }
        return obtenerOrden(ordenId); // totales ya recalculados por el trigger
    }

    @Transactional(readOnly = true)
    public Map<String, Object> obtenerOrden(long ordenId) {
        // Segregación financiera: BODEGA ve la orden para RECIBIR (qué y
        // cuánto), nunca costos ni totales (sus grants de columna lo espejan)
        if (esBodega()) {
            Map<String, Object> orden = pg.queryForMap("""
                    SELECT oc.id, oc.numero, oc.estado, oc.fecha_emision, oc.fecha_entrega_esperada,
                           p.razon_social AS proveedor, b.nombre AS bodega
                    FROM orden_compra oc
                    JOIN proveedor p ON p.id = oc.proveedor_id
                    JOIN bodega b ON b.id = oc.bodega_id
                    WHERE oc.id = ?""", ordenId);
            orden.put("detalles", pg.queryForList("""
                    SELECT d.id, d.producto_variante_id, pv.sku, pr.nombre AS producto,
                           d.cantidad, d.cantidad_recibida
                    FROM orden_compra_detalle d
                    JOIN producto_variante pv ON pv.id = d.producto_variante_id
                    JOIN producto pr ON pr.id = pv.producto_id
                    WHERE d.orden_compra_id = ? ORDER BY d.id""", ordenId));
            return orden;
        }
        Map<String, Object> orden = pg.queryForMap("""
                SELECT oc.id, oc.numero, oc.estado, oc.fecha_emision, oc.fecha_entrega_esperada,
                       oc.subtotal, oc.monto_impuesto, oc.total,
                       p.razon_social AS proveedor, b.nombre AS bodega
                FROM orden_compra oc
                JOIN proveedor p ON p.id = oc.proveedor_id
                JOIN bodega b ON b.id = oc.bodega_id
                WHERE oc.id = ?""", ordenId);
        orden.put("detalles", pg.queryForList("""
                SELECT d.id, d.producto_variante_id, pv.sku, pr.nombre AS producto,
                       d.cantidad, d.precio_unitario, d.subtotal, d.monto_impuesto, d.cantidad_recibida
                FROM orden_compra_detalle d
                JOIN producto_variante pv ON pv.id = d.producto_variante_id
                JOIN producto pr ON pr.id = pv.producto_id
                WHERE d.orden_compra_id = ? ORDER BY d.id""", ordenId));
        return orden;
    }

    /** Segregación financiera: BODEGA lista órdenes SIN el total. */
    private static final String SEL_OC_BODEGA = """
            SELECT oc.id, oc.numero, oc.estado, oc.fecha_emision,
                   p.razon_social AS proveedor, b.nombre AS bodega,
                   EXISTS (SELECT 1 FROM factura_compra fc
                           WHERE fc.orden_compra_id = oc.id) AS tiene_factura
            """;

    /** `tiene_factura` permite al frontend distinguir la orden ya facturada. */
    private static final String SEL_OC_COMPLETO = """
            SELECT oc.id, oc.numero, oc.estado, oc.fecha_emision, oc.total,
                   p.razon_social AS proveedor, b.nombre AS bodega,
                   EXISTS (SELECT 1 FROM factura_compra fc
                           WHERE fc.orden_compra_id = oc.id) AS tiene_factura
            """;

    private static final String JOIN_OC = """
            FROM orden_compra oc
            JOIN proveedor p ON p.id = oc.proveedor_id
            JOIN bodega b ON b.id = oc.bodega_id
            """;

    /**
     * El predicado EXACTO que aplicaba la pantalla de facturas de compra en el
     * navegador: recibida COMPLETA y sin factura previa. Es la MISMA regla
     * —las compuertas del backend no cambian—, movida de sitio.
     */
    private static final String W_OC_FACTURABLES = """
             AND oc.estado = 'recibida'
             AND NOT EXISTS (SELECT 1 FROM factura_compra fc
                             WHERE fc.orden_compra_id = oc.id)
            """;

    /**
     * El predicado EXACTO que aplicaba la pantalla de recepciones: aprobada por
     * gerencia ('confirmada') o con recepción parcial. Hoy son **79** órdenes
     * de 134.588 y son las de id más bajo, o sea las últimas del listado.
     */
    private static final String W_OC_RECIBIBLES =
            " AND oc.estado IN ('confirmada', 'recibida_parcial')\n";

    /**
     * Listado de órdenes de compra, PAGINADO EN EL SERVIDOR.
     *
     * <h3>Por qué dejó de devolver una lista</h3>
     * Devolvía las 134.588 órdenes (27,18 MB medidos) en cada apertura de
     * pantalla.
     *
     * <h3>`facturables` es el filtro que estaba en el navegador</h3>
     * `facturas-compra.component` se traía la tabla ENTERA solo para quedarse
     * con las órdenes «recibidas y sin factura» —hoy son **4** de 134.588— y
     * llenar con ellas un selector; `recepciones.component` hacía lo mismo con
     * «confirmada o recibida_parcial» —**79** de 134.588—. Al paginar, esos
     * filtros habrían mirado las 25 filas visibles y los dos selectores habrían
     * salido VACÍOS sin dar un solo error: ambos conjuntos son de id bajo y el
     * listado va por `id DESC`. Por eso los predicados se evalúan aquí, contra
     * el conjunto completo, igual que se hizo en `ventas/pedidos`.
     *
     * @param incluirOrdenId deja pasar SIEMPRE esa orden aunque ya no cumpla el
     *                       predicado. Es la traducción literal del
     *                       `|| x.id === this.ordenId` que tenía la pantalla de
     *                       recepciones para no perder de vista la orden que
     *                       acaba de recibir.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> listarOrdenes(Integer page, Integer size, Boolean facturables,
                                             Boolean recibibles, Long incluirOrdenId,
                                             Boolean conTotal) {
        StringBuilder w = new StringBuilder(" WHERE 1 = 1\n");
        List<Object> args = new java.util.ArrayList<>();
        String predicado = Boolean.TRUE.equals(facturables) ? W_OC_FACTURABLES
                         : Boolean.TRUE.equals(recibibles)  ? W_OC_RECIBIBLES : null;
        if (predicado != null) {
            if (incluirOrdenId != null) {
                w.append(" AND (oc.id = ? OR (TRUE").append(predicado).append("))\n");
                args.add(incluirOrdenId);
            } else {
                w.append(predicado);
            }
        }
        String where = w.toString();
        Object[] a = args.toArray();

        String sqlItems = (esBodega() ? SEL_OC_BODEGA : SEL_OC_COMPLETO)
                        + JOIN_OC + where + " ORDER BY oc.id DESC";

        int pag = com.retailmind.comun.Paginacion.pagina(page);
        int tam = com.retailmind.comun.Paginacion.tamano(size);

        if (Boolean.FALSE.equals(conTotal)) {
            return com.retailmind.comun.Paginacion.paginarSinTotal(pg, sqlItems, a, pag, tam);
        }
        String sqlCount = "SELECT count(*) FROM orden_compra oc" + where;
        return com.retailmind.comun.Paginacion.paginar(pg, sqlItems, sqlCount, a, pag, tam);
    }

    // ── a.2) Aprobar orden de compra (CU-O-12) ───────────────────────────

    /**
     * GERENTE/ADMIN aprueban una orden emitida: enviada -> confirmada
     * ('confirmada' es el estado de aprobación del check de orden_compra;
     * no existe un estado 'aprobada' en el esquema).
     */
    @Transactional
    public Map<String, Object> aprobarOrden(long ordenId) {
        Map<String, Object> orden = pg.queryForMap(
                "SELECT id, numero, estado FROM orden_compra WHERE id = ? FOR UPDATE", ordenId);
        String estado = (String) orden.get("estado");
        String numero = (String) orden.get("numero");
        switch (estado) {
            case "confirmada" -> throw new IllegalStateException(
                    "La orden " + numero + " ya fue aprobada; no se puede aprobar de nuevo");
            case "recibida", "recibida_parcial" -> throw new IllegalStateException(
                    "La orden " + numero + " ya fue recibida (" + estado
                            + "); no admite aprobacion");
            case "cancelada" -> throw new IllegalStateException(
                    "La orden " + numero + " esta cancelada; no se puede aprobar");
            default -> { } // borrador / enviada: aprobables
        }
        pg.update("UPDATE orden_compra SET estado = 'confirmada' WHERE id = ?", ordenId);

        // Auditoría de la aprobación. Solo llegan aquí ADMIN y GERENTE
        // (SecurityConfig) y ambos grupos tienen INSERT sobre log_auditoria
        // (scripts 19 y 30). estadoAnterior sale del check de la BD (lista blanca).
        auditoria.registrar("orden_compra", ordenId, "UPDATE",
                Map.of("estado", estado), Map.of("estado", "confirmada"));
        return Map.of("id", ordenId, "numero", numero,
                "estadoAnterior", estado, "estado", "confirmada");
    }

    // ── b) Recepción de mercancía (stock + kardex) ───────────────────────

    public record ItemRecepcion(long ordenCompraDetalleId, int cantidadRecibida,
                                Integer cantidadRechazada, String motivoRechazo) {}

    @Transactional
    public Map<String, Object> registrarRecepcion(long ordenId, String observacion,
                                                  List<ItemRecepcion> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("La recepcion requiere al menos un item");
        }
        // No se usa "SELECT ... FOR UPDATE": ese lock exige privilegio UPDATE
        // a nivel de TABLA COMPLETA (sin fallback a nivel de columna), y
        // grp_bodega solo tiene UPDATE(estado, fecha_actualizacion) sobre
        // orden_compra (script 24). Un UPDATE real que solo toca 'estado'
        // adquiere el mismo lock de fila y sí respeta el grant column-level.
        Map<String, Object> orden = pg.queryForMap("""
                UPDATE orden_compra SET estado = estado
                WHERE id = ?
                RETURNING id, numero, bodega_id, estado, proveedor_id""",
                ordenId);
        String estadoOrden = (String) orden.get("estado");
        // Compuerta de control interno: sin aprobación de Gerencia no hay recepción
        if ("borrador".equals(estadoOrden) || "enviada".equals(estadoOrden)) {
            throw new IllegalStateException("La orden " + orden.get("numero")
                    + " debe estar aprobada por Gerencia antes de registrar la recepcion"
                    + " (estado actual: " + estadoOrden + ")");
        }
        if ("recibida".equals(estadoOrden)) {
            throw new IllegalStateException("La orden " + orden.get("numero")
                    + " ya fue recibida completamente; no admite otra recepcion");
        }
        if ("cancelada".equals(estadoOrden)) {
            throw new IllegalStateException("La orden " + orden.get("numero")
                    + " esta cancelada; no se puede recibir");
        }
        long bodegaId = ((Number) orden.get("bodega_id")).longValue();
        long tipoEntradaCompra = pg.queryForObject(
                "SELECT id FROM tipo_movimiento WHERE codigo = 'entrada_compra'", Long.class);

        String numero = siguienteNumero("RM");
        Long recepcionId = pg.queryForObject("""
                INSERT INTO recepcion_mercancia
                    (numero, orden_compra_id, bodega_id, usuario_id, estado, observacion)
                VALUES (?, ?, ?, ?, 'confirmada', ?)
                RETURNING id""",
                Long.class, numero, ordenId, bodegaId, usuarioActualId(), observacion);

        for (ItemRecepcion it : items) {
            Map<String, Object> det = pg.queryForMap("""
                    SELECT d.producto_variante_id, d.precio_unitario, d.cantidad,
                           d.cantidad_recibida, pv.sku
                    FROM orden_compra_detalle d
                    JOIN producto_variante pv ON pv.id = d.producto_variante_id
                    WHERE d.id = ? AND d.orden_compra_id = ?""",
                    it.ordenCompraDetalleId(), ordenId);
            long varianteId = ((Number) det.get("producto_variante_id")).longValue();
            BigDecimal costo = (BigDecimal) det.get("precio_unitario");

            if (it.cantidadRecibida() <= 0) {
                throw new IllegalArgumentException("La cantidad recibida para el SKU "
                        + det.get("sku") + " debe ser mayor a cero");
            }
            int pendiente = ((Number) det.get("cantidad")).intValue()
                    - ((Number) det.get("cantidad_recibida")).intValue();
            if (it.cantidadRecibida() > pendiente) {
                throw new IllegalArgumentException("No se puede recibir " + it.cantidadRecibida()
                        + " del SKU " + det.get("sku") + ": solo quedan " + pendiente
                        + " pendientes por recibir en la orden");
            }

            int rechazada = it.cantidadRechazada() != null ? it.cantidadRechazada() : 0;
            Long recepcionDetalleId = pg.queryForObject("""
                    INSERT INTO recepcion_detalle
                        (recepcion_mercancia_id, orden_compra_detalle_id, cantidad_recibida,
                         cantidad_rechazada, motivo_rechazo)
                    VALUES (?, ?, ?, ?, ?)
                    RETURNING id""", Long.class,
                    recepcionId, it.ordenCompraDetalleId(), it.cantidadRecibida(),
                    rechazada, it.motivoRechazo());

            // Rechazo EN PUERTA = defectuoso de origen 'recepcion' (script 45):
            // queda pendiente de devolución a proveedor. SIN movimiento de
            // stock: lo rechazado jamás entra al inventario vendible.
            if (rechazada > 0) {
                Long itemId = pg.queryForObject("""
                        INSERT INTO item_defectuoso
                            (producto_variante_id, bodega_id, cantidad, origen,
                             recepcion_detalle_id, proveedor_id, costo_unitario, nota,
                             registrado_por)
                        VALUES (?, ?, ?, 'recepcion', ?, ?, ?, NULLIF(?, ''), ?)
                        RETURNING id""", Long.class,
                        varianteId, bodegaId, rechazada, recepcionDetalleId,
                        orden.get("proveedor_id"), costo, it.motivoRechazo(), usuarioActualId());
                auditoria.registrar("item_defectuoso", itemId, "INSERT", null,
                        Map.of("origen", "recepcion", "recepcion", numero,
                               "sku", det.get("sku"), "cantidad", rechazada,
                               "salidaStockVendible", false));
            }

            // Stock: asegurar fila, bloquearla y moverla (la app es la dueña de este paso)
            pg.update("""
                    INSERT INTO inventario (producto_variante_id, bodega_id, stock_actual)
                    VALUES (?, ?, 0)
                    ON CONFLICT (producto_variante_id, bodega_id) DO NOTHING""",
                    varianteId, bodegaId);
            Integer stockAnterior = pg.queryForObject("""
                    SELECT stock_actual FROM inventario
                    WHERE producto_variante_id = ? AND bodega_id = ? FOR UPDATE""",
                    Integer.class, varianteId, bodegaId);
            int stockNuevo = stockAnterior + it.cantidadRecibida();

            // Kardex: entrada por compra con referencia polimórfica a la recepción
            pg.update("""
                    INSERT INTO movimiento_inventario
                        (producto_variante_id, bodega_id, tipo_movimiento_id, usuario_id,
                         cantidad, stock_anterior, stock_nuevo, costo_unitario,
                         referencia_tipo, referencia_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'recepcion_mercancia', ?)""",
                    varianteId, bodegaId, tipoEntradaCompra, usuarioActualId(),
                    it.cantidadRecibida(), stockAnterior, stockNuevo, costo, recepcionId);

            pg.update("""
                    UPDATE inventario SET stock_actual = ?
                    WHERE producto_variante_id = ? AND bodega_id = ?""",
                    stockNuevo, varianteId, bodegaId);

            pg.update("""
                    UPDATE orden_compra_detalle SET cantidad_recibida = cantidad_recibida + ?
                    WHERE id = ?""", it.cantidadRecibida(), it.ordenCompraDetalleId());

            // Catálogo proveedor-producto (OTD-COM-10, script 51): cada
            // recepción registra/actualiza AUTOMÁTICAMENTE el par
            // proveedor-variante con el costo realmente recibido (pactado en
            // la OC aprobada: no hay nada que confirmar). SECURITY DEFINER:
            // también corre bajo grp_bodega sin abrirle la tabla (contiene
            // costo, segregación financiera). Nunca pisa los datos comerciales
            // manuales de COMPRAS (preferido, cantidad mínima, plazo).
            pg.queryForObject("SELECT fn_upsert_producto_proveedor(?, ?, ?)", Long.class,
                    ((Number) orden.get("proveedor_id")).longValue(), varianteId, costo);
        }

        Boolean completa = pg.queryForObject("""
                SELECT bool_and(cantidad_recibida >= cantidad)
                FROM orden_compra_detalle WHERE orden_compra_id = ?""", Boolean.class, ordenId);
        pg.update("UPDATE orden_compra SET estado = ? WHERE id = ?",
                Boolean.TRUE.equals(completa) ? "recibida" : "recibida_parcial", ordenId);

        return Map.of("id", recepcionId, "numero", numero, "ordenCompraId", ordenId,
                "estadoOrden", Boolean.TRUE.equals(completa) ? "recibida" : "recibida_parcial");
    }

    // ── c) Factura de compra + cuenta por pagar ──────────────────────────

    @Transactional
    public Map<String, Object> registrarFactura(long ordenId) {
        Map<String, Object> orden = pg.queryForMap("""
                SELECT oc.numero, oc.estado, oc.proveedor_id, oc.moneda_id, p.dias_credito
                FROM orden_compra oc JOIN proveedor p ON p.id = oc.proveedor_id
                WHERE oc.id = ? FOR UPDATE OF oc""", ordenId);
        long proveedorId = ((Number) orden.get("proveedor_id")).longValue();
        int diasCredito = ((Number) orden.get("dias_credito")).intValue();

        // Compuertas: aprobada -> recibida COMPLETA -> factura (el detalle de la
        // factura copia las cantidades pedidas, por eso exige recepcion total)
        String estadoOrden = (String) orden.get("estado");
        switch (estadoOrden) {
            case "borrador", "enviada" -> throw new IllegalStateException(
                    "La orden " + orden.get("numero")
                    + " debe estar aprobada por Gerencia antes de registrar la factura"
                    + " (estado actual: " + estadoOrden + ")");
            case "confirmada" -> throw new IllegalStateException(
                    "La orden " + orden.get("numero")
                    + " aun no registra recepcion de mercancia; debe recibirse antes de facturar");
            case "recibida_parcial" -> throw new IllegalStateException(
                    "La orden " + orden.get("numero")
                    + " esta recibida parcialmente; debe recibirse completa antes de facturar");
            case "cancelada" -> throw new IllegalStateException(
                    "La orden " + orden.get("numero") + " esta cancelada; no se puede facturar");
            default -> { } // recibida: facturable
        }

        // Guardia de idempotencia: una orden se factura una sola vez
        List<String> existentes = pg.queryForList(
                "SELECT numero_factura FROM factura_compra WHERE orden_compra_id = ?",
                String.class, ordenId);
        if (!existentes.isEmpty()) {
            throw new IllegalStateException("La orden " + orden.get("numero")
                    + " ya tiene la factura " + existentes.get(0)
                    + " registrada; no se puede facturar de nuevo");
        }

        // Numero autogenerado por el sistema (mismo patron que OC/PED/FV).
        // registrado_por = autor del JWT (trazabilidad, script 42).
        String numeroFactura = siguienteNumero("FC");
        Long facturaId = pg.queryForObject("""
                INSERT INTO factura_compra
                    (proveedor_id, orden_compra_id, moneda_id, numero_factura,
                     fecha_emision, fecha_vencimiento, estado, registrado_por)
                VALUES (?, ?, ?, ?, CURRENT_DATE, CURRENT_DATE + ?::int, 'registrada', ?)
                RETURNING id""",
                Long.class, proveedorId, ordenId, ((Number) orden.get("moneda_id")).longValue(),
                numeroFactura, diasCredito, usuarioActualId());
        auditoria.registrar("factura_compra", facturaId, "INSERT", null,
                Map.of("numero_factura", numeroFactura, "orden_compra_id", ordenId,
                       "estado", "registrada"));

        // Detalle copiado de la orden (lo efectivamente pactado). subtotal = generado.
        pg.update("""
                INSERT INTO factura_compra_detalle
                    (factura_compra_id, producto_variante_id, cantidad, precio_unitario, monto_impuesto)
                SELECT ?, producto_variante_id, cantidad, precio_unitario, monto_impuesto
                FROM orden_compra_detalle WHERE orden_compra_id = ?""", facturaId, ordenId);

        // El trigger ya recalculo los totales: se LEEN para abrir la CxP
        BigDecimal total = pg.queryForObject(
                "SELECT total FROM factura_compra WHERE id = ?", BigDecimal.class, facturaId);
        pg.update("""
                INSERT INTO cuenta_por_pagar
                    (factura_compra_id, proveedor_id, monto_original, saldo_pendiente,
                     fecha_vencimiento, estado)
                VALUES (?, ?, ?, ?, CURRENT_DATE + ?::int, 'pendiente')""",
                facturaId, proveedorId, total, total, diasCredito);

        return obtenerFactura(facturaId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> obtenerFactura(long facturaId) {
        Map<String, Object> f = pg.queryForMap("""
                SELECT fc.id, fc.numero_factura, fc.estado, fc.fecha_emision, fc.fecha_vencimiento,
                       fc.subtotal, fc.monto_impuesto, fc.total, fc.orden_compra_id,
                       p.razon_social AS proveedor,
                       cxp.id AS cuenta_por_pagar_id, cxp.saldo_pendiente, cxp.estado AS estado_cxp
                FROM factura_compra fc
                JOIN proveedor p ON p.id = fc.proveedor_id
                LEFT JOIN cuenta_por_pagar cxp ON cxp.factura_compra_id = fc.id
                WHERE fc.id = ?""", facturaId);
        f.put("detalles", pg.queryForList("""
                SELECT d.id, pv.sku, pr.nombre AS producto, d.cantidad,
                       d.precio_unitario, d.subtotal, d.monto_impuesto
                FROM factura_compra_detalle d
                JOIN producto_variante pv ON pv.id = d.producto_variante_id
                JOIN producto pr ON pr.id = pv.producto_id
                WHERE d.factura_compra_id = ? ORDER BY d.id""", facturaId));
        return f;
    }

    // ── d) Pago a proveedor ──────────────────────────────────────────────

    @Transactional
    public Map<String, Object> registrarPago(long cuentaPorPagarId, BigDecimal monto,
                                             long metodoPagoId, String referencia) {
        Map<String, Object> cxp = pg.queryForMap("""
                SELECT id, factura_compra_id, saldo_pendiente, estado FROM cuenta_por_pagar
                WHERE id = ? FOR UPDATE""", cuentaPorPagarId);
        BigDecimal saldo = (BigDecimal) cxp.get("saldo_pendiente");
        if (saldo.signum() == 0 || "pagada".equals(cxp.get("estado"))) {
            throw new IllegalStateException(
                    "La cuenta por pagar ya esta liquidada; no admite mas pagos");
        }
        if (monto == null || monto.signum() <= 0) {
            throw new IllegalArgumentException("El monto del pago debe ser mayor a cero");
        }
        if (monto.compareTo(saldo) > 0) {
            throw new IllegalArgumentException("El pago (" + monto
                    + ") excede el saldo pendiente (" + saldo + ")");
        }

        Long pagoId = pg.queryForObject("""
                INSERT INTO pago_proveedor
                    (cuenta_por_pagar_id, metodo_pago_id, usuario_id, monto, referencia)
                VALUES (?, ?, ?, ?, ?) RETURNING id""",
                Long.class, cuentaPorPagarId, metodoPagoId, usuarioActualId(), monto, referencia);

        BigDecimal nuevoSaldo = saldo.subtract(monto);
        boolean liquidada = nuevoSaldo.signum() == 0;
        pg.update("UPDATE cuenta_por_pagar SET saldo_pendiente = ?, estado = ? WHERE id = ?",
                nuevoSaldo, liquidada ? "pagada" : "parcial", cuentaPorPagarId);
        pg.update("UPDATE factura_compra SET estado = ? WHERE id = ?",
                liquidada ? "pagada" : "pagada_parcial",
                ((Number) cxp.get("factura_compra_id")).longValue());

        return Map.of("pagoId", pagoId, "saldoPendiente", nuevoSaldo,
                "estadoCuenta", liquidada ? "pagada" : "parcial");
    }

    /**
     * Cuentas por pagar, PAGINADO EN EL SERVIDOR. Devolvía las 134.558 cuentas
     * (26,11 MB medidos). La pantalla no ofrece ningún filtro sobre esta tabla,
     * así que no hay criterio que mudar a SQL: solo cambia quién recorta.
     * `cxp.id DESC` es único, así que la paginación no repite ni salta filas.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> listarCuentasPorPagar(Integer page, Integer size,
                                                     Boolean conTotal) {
        String sqlItems = """
                SELECT cxp.id, cxp.monto_original, cxp.saldo_pendiente, cxp.estado,
                       cxp.fecha_vencimiento, fc.numero_factura, p.razon_social AS proveedor
                FROM cuenta_por_pagar cxp
                JOIN factura_compra fc ON fc.id = cxp.factura_compra_id
                JOIN proveedor p ON p.id = cxp.proveedor_id
                ORDER BY cxp.id DESC""";
        Object[] a = new Object[0];

        int pag = com.retailmind.comun.Paginacion.pagina(page);
        int tam = com.retailmind.comun.Paginacion.tamano(size);

        if (Boolean.FALSE.equals(conTotal)) {
            return com.retailmind.comun.Paginacion.paginarSinTotal(pg, sqlItems, a, pag, tam);
        }
        return com.retailmind.comun.Paginacion.paginar(pg, sqlItems,
                "SELECT count(*) FROM cuenta_por_pagar cxp", a, pag, tam);
    }

    // ── Utilitarios ──────────────────────────────────────────────────────

    private String siguienteNumero(String prefijo) {
        // Secuencia global (script 43): número único garantizado, sin la
        // colisión posible del sufijo aleatorio legacy.
        return pg.queryForObject(
                "SELECT ? || '-' || to_char(now(), 'YYYYMMDD') || '-' || nextval('seq_numero_documento')::text",
                String.class, prefijo);
    }

    private long monedaBaseId() {
        return pg.queryForObject("SELECT id FROM moneda WHERE es_base LIMIT 1", Long.class);
    }

    private Long usuarioActualId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppUserPrincipal p) {
            return p.getUsuarioId();
        }
        return null;
    }

    /** BODEGA recibe mercancía pero no lee montos (segregación financiera). */
    private boolean esBodega() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppUserPrincipal p) {
            return "BODEGA".equalsIgnoreCase(p.getRolCodigo());
        }
        return false;
    }

}
