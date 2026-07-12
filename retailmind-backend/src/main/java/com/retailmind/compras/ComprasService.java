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

    public ComprasService(@Qualifier("pgJdbcTemplate") JdbcTemplate pg) {
        this.pg = pg;
    }

    // ── a) Emitir orden de compra ────────────────────────────────────────

    public record ItemOrden(long varianteId, int cantidad, BigDecimal precioUnitario,
                            BigDecimal ivaPorcentaje) {}

    @Transactional
    public Map<String, Object> emitirOrden(long proveedorId, long bodegaId, Long monedaId,
                                           String fechaEntregaEsperada, String observacion,
                                           List<ItemOrden> items) {
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
        String numero = siguienteNumero("OC");
        Long ordenId = pg.queryForObject("""
                INSERT INTO orden_compra
                    (numero, proveedor_id, bodega_id, moneda_id, usuario_id, estado,
                     fecha_entrega_esperada, observacion)
                VALUES (?, ?, ?, ?, ?, 'enviada', ?::date, ?)
                RETURNING id""",
                Long.class, numero, proveedorId, bodegaId,
                monedaId != null ? monedaId : monedaBaseId(), usuarioActualId(),
                fechaEntregaEsperada, observacion);

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

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarOrdenes() {
        // tiene_factura permite al frontend ofrecer solo ordenes facturables
        return pg.queryForList("""
                SELECT oc.id, oc.numero, oc.estado, oc.fecha_emision, oc.total,
                       p.razon_social AS proveedor, b.nombre AS bodega,
                       EXISTS (SELECT 1 FROM factura_compra fc
                               WHERE fc.orden_compra_id = oc.id) AS tiene_factura
                FROM orden_compra oc
                JOIN proveedor p ON p.id = oc.proveedor_id
                JOIN bodega b ON b.id = oc.bodega_id
                ORDER BY oc.id DESC""");
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
        pg.update("""
                INSERT INTO log_auditoria
                    (usuario_id, tabla, registro_id, accion, datos_anteriores, datos_nuevos)
                VALUES (?, 'orden_compra', ?, 'UPDATE', ?::jsonb, ?::jsonb)""",
                usuarioActualId(), ordenId,
                "{\"estado\": \"" + estado + "\"}",
                "{\"estado\": \"confirmada\"}");
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
        Map<String, Object> orden = pg.queryForMap(
                "SELECT id, numero, bodega_id, estado FROM orden_compra WHERE id = ? FOR UPDATE",
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

            pg.update("""
                    INSERT INTO recepcion_detalle
                        (recepcion_mercancia_id, orden_compra_detalle_id, cantidad_recibida,
                         cantidad_rechazada, motivo_rechazo)
                    VALUES (?, ?, ?, ?, ?)""",
                    recepcionId, it.ordenCompraDetalleId(), it.cantidadRecibida(),
                    it.cantidadRechazada() != null ? it.cantidadRechazada() : 0, it.motivoRechazo());

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

        // Numero autogenerado por el sistema (mismo patron que OC/PED/FV)
        String numeroFactura = siguienteNumero("FC");
        Long facturaId = pg.queryForObject("""
                INSERT INTO factura_compra
                    (proveedor_id, orden_compra_id, moneda_id, numero_factura,
                     fecha_emision, fecha_vencimiento, estado)
                VALUES (?, ?, ?, ?, CURRENT_DATE, CURRENT_DATE + ?::int, 'registrada')
                RETURNING id""",
                Long.class, proveedorId, ordenId, ((Number) orden.get("moneda_id")).longValue(),
                numeroFactura, diasCredito);

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

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarCuentasPorPagar() {
        return pg.queryForList("""
                SELECT cxp.id, cxp.monto_original, cxp.saldo_pendiente, cxp.estado,
                       cxp.fecha_vencimiento, fc.numero_factura, p.razon_social AS proveedor
                FROM cuenta_por_pagar cxp
                JOIN factura_compra fc ON fc.id = cxp.factura_compra_id
                JOIN proveedor p ON p.id = cxp.proveedor_id
                ORDER BY cxp.id DESC""");
    }

    // ── Utilitarios ──────────────────────────────────────────────────────

    private String siguienteNumero(String prefijo) {
        // Numeración legible y única (unique en BD respalda ante colisión)
        return pg.queryForObject(
                "SELECT ? || '-' || to_char(now(), 'YYYYMMDD') || '-' || lpad(floor(random()*100000)::text, 5, '0')",
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

}
