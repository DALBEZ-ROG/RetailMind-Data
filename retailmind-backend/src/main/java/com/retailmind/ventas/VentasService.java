package com.retailmind.ventas;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import com.retailmind.inventario.StockService;

/**
 * Ciclo de venta (Order-to-Cash) con compuertas enforzadas en backend:
 *
 *   confirmado -> [pago(s) del cliente] -> pagado -> [factura] -> [despacho]
 *   -> despachado -> [entrega] -> entregado -> [devolución] -> devuelto
 *
 * Cada paso valida el estado anterior (mensajes claros vía IllegalState/
 * IllegalArgument -> GlobalExceptionHandler). Réplica del patrón de compras/:
 *  - subtotales de detalle GENERATED y totales de cabecera por trigger: la app
 *    inserta sin ellos y LEE el total después.
 *  - stock via StockService (upsert + FOR UPDATE + kardex + update).
 *  - Decisión documentada: el pedido DESCUENTA stock directo (salida_venta)
 *    al confirmarse — más simple de demostrar que reservar; la devolución lo
 *    reingresa (entrada_devolucion_cliente).
 */
@Service
public class VentasService {

    private static final BigDecimal IVA_DEFECTO = new BigDecimal("15");

    private final JdbcTemplate pg;
    private final StockService stock;

    public VentasService(@Qualifier("pgJdbcTemplate") JdbcTemplate pg, StockService stock) {
        this.pg = pg;
        this.stock = stock;
    }

    // ── Caso 7: realizar pedido ──────────────────────────────────────────

    public record ItemPedido(long varianteId, int cantidad) {}

    @Transactional
    public Map<String, Object> crearPedido(long clienteId, long bodegaId, String canal,
                                           List<ItemPedido> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("El pedido requiere al menos un item");
        }
        String numero = siguienteNumero("PED");
        Long pedidoId = pg.queryForObject("""
                INSERT INTO pedido (numero, cliente_id, estado_pedido_id, moneda_id, canal)
                VALUES (?, ?, (SELECT id FROM estado_pedido WHERE codigo = 'confirmado'),
                        (SELECT id FROM moneda WHERE es_base LIMIT 1), ?)
                RETURNING id""",
                Long.class, numero, clienteId,
                canal != null && List.of("web", "tienda", "telefono").contains(canal) ? canal : "web");

        for (ItemPedido it : items) {
            if (it.cantidad() <= 0) {
                throw new IllegalArgumentException(
                        "La cantidad de cada producto debe ser mayor a cero");
            }
            // Snapshot de nombre, sku y precio de venta vigente
            List<Map<String, Object>> variantes = pg.queryForList("""
                    SELECT pv.sku, pv.precio, pr.nombre
                    FROM producto_variante pv JOIN producto pr ON pr.id = pv.producto_id
                    WHERE pv.id = ? AND pv.activo""", it.varianteId());
            if (variantes.isEmpty()) {
                throw new IllegalArgumentException("El producto (variante "
                        + it.varianteId() + ") no existe o esta inactivo");
            }
            Map<String, Object> v = variantes.get(0);
            BigDecimal precio = (BigDecimal) v.get("precio");
            BigDecimal impuesto = precio.multiply(BigDecimal.valueOf(it.cantidad()))
                    .multiply(IVA_DEFECTO).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

            // subtotal NO se inserta (columna generada)
            pg.update("""
                    INSERT INTO pedido_detalle
                        (pedido_id, producto_variante_id, nombre_producto, sku,
                         cantidad, precio_unitario, monto_impuesto)
                    VALUES (?, ?, ?, ?, ?, ?, ?)""",
                    pedidoId, it.varianteId(), v.get("nombre"), v.get("sku"),
                    it.cantidad(), precio, impuesto);

            // Descuento directo de stock con kardex (decisión documentada arriba)
            stock.mover(it.varianteId(), bodegaId, "salida_venta", it.cantidad(),
                    "pedido", pedidoId, precio, usuarioActualId(), null);
        }

        registrarHistorial(pedidoId, "confirmado", "Pedido creado y stock descontado");
        return obtenerPedido(pedidoId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> obtenerPedido(long pedidoId) {
        Map<String, Object> pedido = pg.queryForMap("""
                SELECT p.id, p.numero, ep.codigo AS estado, p.canal, p.fecha_pedido,
                       p.subtotal, p.monto_impuesto, p.costo_envio, p.total,
                       c.nombre || ' ' || COALESCE(c.apellido,'') AS cliente, c.email AS cliente_email
                FROM pedido p
                JOIN estado_pedido ep ON ep.id = p.estado_pedido_id
                JOIN cliente c ON c.id = p.cliente_id
                WHERE p.id = ?""", pedidoId);
        pedido.put("detalles", pg.queryForList("""
                SELECT id, sku, nombre_producto, cantidad, precio_unitario, subtotal, monto_impuesto
                FROM pedido_detalle WHERE pedido_id = ? ORDER BY id""", pedidoId));
        // grp_cliente ya tiene SELECT sobre historial_estado_pedido con RLS de
        // propiedad (script 30): la misma consulta sirve para todos los roles y
        // al cliente el motor lo limita al historial de SUS pedidos.
        pedido.put("historial", pg.queryForList("""
                SELECT ep.codigo AS estado, h.comentario, h.fecha_creacion
                FROM historial_estado_pedido h
                JOIN estado_pedido ep ON ep.id = h.estado_pedido_id
                WHERE h.pedido_id = ? ORDER BY h.id""", pedidoId));
        pedido.put("notas", listarNotas(pedidoId));

        // Documentos encadenados: factura y envío del pedido (si existen), para
        // que el detalle muestre el proceso completo y las acciones siguientes.
        List<Map<String, Object>> facturas = pg.queryForList("""
                SELECT id, numero, estado FROM factura_venta
                WHERE pedido_id = ? ORDER BY id DESC""", pedidoId);
        pedido.put("factura", facturas.isEmpty() ? null : facturas.get(0));
        List<Map<String, Object>> envios = pg.queryForList("""
                SELECT id, numero, numero_guia, estado, fecha_despacho, fecha_entrega_real
                FROM envio WHERE pedido_id = ? ORDER BY id DESC""", pedidoId);
        pedido.put("envio", envios.isEmpty() ? null : envios.get(0));

        // Pagos del cliente: solo personal (grp_cliente no tiene SELECT sobre
        // pago; su vista del proceso es el historial + factura + envío)
        if (!"CLIENTE".equalsIgnoreCase(rolActual())) {
            List<Map<String, Object>> pagos = pg.queryForList("""
                    SELECT pa.id, pa.monto, pa.estado, pa.referencia_externa, pa.fecha_pago,
                           mp.nombre AS metodo
                    FROM pago pa JOIN metodo_pago mp ON mp.id = pa.metodo_pago_id
                    WHERE pa.pedido_id = ? ORDER BY pa.id""", pedidoId);
            pedido.put("pagos", pagos);
            BigDecimal total = (BigDecimal) pedido.get("total");
            BigDecimal pagado = totalPagado(pedidoId);
            pedido.put("total_pagado", pagado);
            pedido.put("saldo_pendiente", total.subtract(pagado));
        } else {
            pedido.put("pagos", List.of());
        }
        return pedido;
    }

    // ── Notas / observaciones del pedido (nota_pedido, script 31) ────────

    /**
     * CLIENTE: solo notas de sus pedidos marcadas es_visible_cliente (RLS lo
     * refuerza) y sin autor (grp_cliente no lee usuario). Personal: todas,
     * con autor.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarNotas(long pedidoId) {
        if ("CLIENTE".equalsIgnoreCase(rolActual())) {
            return pg.queryForList("""
                    SELECT id, nota, fecha_creacion
                    FROM nota_pedido
                    WHERE pedido_id = ? AND es_visible_cliente ORDER BY id""", pedidoId);
        }
        return pg.queryForList("""
                SELECT n.id, n.nota, n.es_visible_cliente, n.fecha_creacion,
                       trim(concat(u.nombre, ' ', COALESCE(u.apellido, ''))) AS autor
                FROM nota_pedido n
                LEFT JOIN usuario u ON u.id = n.usuario_id
                WHERE n.pedido_id = ? ORDER BY n.id""", pedidoId);
    }

    /** Nota de bitácora del personal sobre un pedido; el autor sale del JWT. */
    @Transactional
    public Map<String, Object> crearNota(long pedidoId, String nota, boolean esVisibleCliente) {
        if (nota == null || nota.isBlank()) {
            throw new IllegalArgumentException("La nota no puede estar vacía");
        }
        estadoPedido(pedidoId); // 400 con mensaje claro si el pedido no existe
        Long id = pg.queryForObject("""
                INSERT INTO nota_pedido (pedido_id, usuario_id, nota, es_visible_cliente)
                VALUES (?, ?, ?, ?) RETURNING id""",
                Long.class, pedidoId, usuarioActualId(), nota.trim(), esVisibleCliente);
        return Map.of("id", id);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarPedidos() {
        // tiene_factura permite a los selectores ofrecer solo pedidos válidos
        return pg.queryForList("""
                SELECT p.id, p.numero, ep.codigo AS estado, p.total, p.fecha_pedido,
                       c.nombre || ' ' || COALESCE(c.apellido,'') AS cliente,
                       EXISTS (SELECT 1 FROM factura_venta fv
                               WHERE fv.pedido_id = p.id) AS tiene_factura
                FROM pedido p
                JOIN estado_pedido ep ON ep.id = p.estado_pedido_id
                JOIN cliente c ON c.id = p.cliente_id
                ORDER BY p.id DESC""");
    }

    // ── Pago del cliente (pago + transaccion_pago) ───────────────────────

    /**
     * Registra el cobro de un pedido (efectivo/transferencia). Compuerta:
     * solo pedidos pendientes/confirmados con saldo; al cubrir el total el
     * pedido pasa a 'pagado' (lo que habilita facturar y despachar).
     * Admite abonos parciales; monto null = saldo completo.
     */
    @Transactional
    public Map<String, Object> registrarPago(long pedidoId, long metodoPagoId,
                                             BigDecimal monto, String referencia) {
        List<Map<String, Object>> filas = pg.queryForList("""
                SELECT p.id, p.numero, p.total, p.moneda_id, ep.codigo AS estado
                FROM pedido p JOIN estado_pedido ep ON ep.id = p.estado_pedido_id
                WHERE p.id = ? FOR UPDATE OF p""", pedidoId);
        if (filas.isEmpty()) {
            throw new NoSuchElementException("No existe el pedido " + pedidoId);
        }
        Map<String, Object> ped = filas.get(0);
        String estado = (String) ped.get("estado");
        String numero = (String) ped.get("numero");
        switch (estado) {
            case "pagado", "en_preparacion", "despachado", "entregado" ->
                    throw new IllegalStateException("El pedido " + numero
                            + " ya esta pagado; no admite mas cobros");
            case "cancelado", "devuelto" -> throw new IllegalStateException(
                    "No se puede cobrar un pedido en estado '" + estado + "'");
            default -> { } // pendiente / confirmado: cobrables
        }
        List<String> metodos = pg.queryForList(
                "SELECT nombre FROM metodo_pago WHERE id = ? AND activo", String.class, metodoPagoId);
        if (metodos.isEmpty()) {
            throw new IllegalArgumentException("El metodo de pago no existe o esta inactivo");
        }

        BigDecimal total = (BigDecimal) ped.get("total");
        BigDecimal saldo = total.subtract(totalPagado(pedidoId));
        if (monto == null) monto = saldo;
        if (monto.signum() <= 0) {
            throw new IllegalArgumentException("El monto del pago debe ser mayor a cero");
        }
        if (monto.compareTo(saldo) > 0) {
            throw new IllegalArgumentException("El pago (" + monto
                    + ") excede el saldo pendiente del pedido (" + saldo + ")");
        }

        Long pagoId = pg.queryForObject("""
                INSERT INTO pago (pedido_id, metodo_pago_id, moneda_id, monto, estado,
                                  referencia_externa, fecha_pago)
                VALUES (?, ?, ?, ?, 'completado', NULLIF(?, ''), now())
                RETURNING id""",
                Long.class, pedidoId, metodoPagoId,
                ((Number) ped.get("moneda_id")).longValue(), monto, referencia);
        pg.update("""
                INSERT INTO transaccion_pago (pago_id, tipo, estado, monto)
                VALUES (?, 'captura', 'exitosa', ?)""", pagoId, monto);

        BigDecimal nuevoSaldo = saldo.subtract(monto);
        boolean cubierto = nuevoSaldo.signum() == 0;
        if (cubierto) {
            cambiarEstadoPedido(pedidoId, "pagado",
                    "Pago del cliente registrado (" + metodos.get(0) + ") — total cubierto");
        } else {
            registrarHistorial(pedidoId, estado, "Abono del cliente por " + monto
                    + " (" + metodos.get(0) + ") — saldo pendiente " + nuevoSaldo);
        }
        return Map.of("pagoId", pagoId, "totalPagado", total.subtract(nuevoSaldo),
                "saldoPendiente", nuevoSaldo, "estadoPedido", cubierto ? "pagado" : estado);
    }

    /** Suma de pagos completados del pedido. */
    private BigDecimal totalPagado(long pedidoId) {
        return pg.queryForObject("""
                SELECT COALESCE(SUM(monto), 0) FROM pago
                WHERE pedido_id = ? AND estado = 'completado'""", BigDecimal.class, pedidoId);
    }

    // ── Caso 8: factura de venta ─────────────────────────────────────────

    @Transactional
    public Map<String, Object> emitirFactura(long pedidoId) {
        String estado = estadoPedido(pedidoId);
        if (List.of("cancelado", "devuelto").contains(estado)) {
            throw new IllegalStateException(
                    "No se puede facturar un pedido en estado '" + estado + "'");
        }
        // Compuerta: la factura se emite sobre un pedido ya COBRADO
        if (List.of("pendiente", "confirmado").contains(estado)) {
            throw new IllegalStateException(
                    "El pedido debe estar pagado antes de emitir la factura; "
                    + "registra primero el pago del cliente (estado actual: '" + estado + "')");
        }
        // Guardia de idempotencia: un pedido se factura una sola vez
        List<String> existentes = pg.queryForList(
                "SELECT numero FROM factura_venta WHERE pedido_id = ?", String.class, pedidoId);
        if (!existentes.isEmpty()) {
            throw new IllegalStateException("El pedido ya fue facturado (factura "
                    + existentes.get(0) + "); no se puede facturar de nuevo");
        }

        Map<String, Object> datos = pg.queryForMap("""
                SELECT p.cliente_id, p.moneda_id,
                       c.nombre || ' ' || COALESCE(c.apellido,'') AS razon_social,
                       COALESCE(c.numero_identificacion, '9999999999') AS identificacion,
                       (SELECT d.calle_principal || COALESCE(', ' || d.referencia, '')
                        FROM direccion d WHERE d.usuario_id = c.usuario_id
                        ORDER BY d.es_predeterminada DESC LIMIT 1) AS direccion
                FROM pedido p JOIN cliente c ON c.id = p.cliente_id
                WHERE p.id = ?""", pedidoId);

        String numero = siguienteNumero("FV");
        Long facturaId = pg.queryForObject("""
                INSERT INTO factura_venta
                    (numero, pedido_id, cliente_id, moneda_id, razon_social,
                     identificacion, direccion_facturacion, estado)
                VALUES (?, ?, ?, ?, trim(?), ?, ?, 'emitida')
                RETURNING id""",
                Long.class, numero, pedidoId,
                ((Number) datos.get("cliente_id")).longValue(),
                ((Number) datos.get("moneda_id")).longValue(),
                (String) datos.get("razon_social"), (String) datos.get("identificacion"),
                (String) datos.get("direccion"));

        // Detalle copiado del pedido (snapshot). subtotal = columna generada.
        pg.update("""
                INSERT INTO factura_venta_detalle
                    (factura_venta_id, pedido_detalle_id, producto_variante_id,
                     descripcion, cantidad, precio_unitario, monto_impuesto)
                SELECT ?, id, producto_variante_id, nombre_producto || ' (' || sku || ')',
                       cantidad, precio_unitario, monto_impuesto
                FROM pedido_detalle WHERE pedido_id = ?""", facturaId, pedidoId);

        // El estado NO cambia al facturar (el pago ya lo puso en 'pagado');
        // se deja constancia en la línea de tiempo del pedido.
        registrarHistorial(pedidoId, estado, "Factura de venta " + numero + " emitida");
        return obtenerFactura(facturaId); // totales ya recalculados por el trigger
    }

    /** Listado de facturas de venta emitidas, con búsqueda y paginación. */
    @Transactional(readOnly = true)
    public Map<String, Object> listarFacturas(String q, int page, int size) {
        int tam = Math.min(Math.max(size, 1), 100);
        int pagina = Math.max(page, 0);
        String filtro = "%" + (q == null ? "" : q.trim()) + "%";
        Long total = pg.queryForObject("""
                SELECT COUNT(*) FROM factura_venta fv
                JOIN pedido p ON p.id = fv.pedido_id
                WHERE fv.numero ILIKE ? OR fv.razon_social ILIKE ? OR p.numero ILIKE ?""",
                Long.class, filtro, filtro, filtro);
        List<Map<String, Object>> items = pg.queryForList("""
                SELECT fv.id, fv.numero, fv.estado, fv.fecha_emision, fv.total,
                       fv.razon_social AS cliente, fv.pedido_id, p.numero AS numero_pedido
                FROM factura_venta fv
                JOIN pedido p ON p.id = fv.pedido_id
                WHERE fv.numero ILIKE ? OR fv.razon_social ILIKE ? OR p.numero ILIKE ?
                ORDER BY fv.id DESC LIMIT ? OFFSET ?""",
                filtro, filtro, filtro, tam, pagina * tam);
        return Map.of("items", items, "total", total, "page", pagina, "size", tam);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> obtenerFactura(long facturaId) {
        Map<String, Object> f = pg.queryForMap("""
                SELECT fv.id, fv.numero, fv.estado, fv.fecha_emision, fv.razon_social,
                       fv.identificacion, fv.direccion_facturacion, fv.pedido_id,
                       fv.subtotal, fv.monto_descuento, fv.monto_impuesto, fv.total,
                       p.numero AS numero_pedido
                FROM factura_venta fv JOIN pedido p ON p.id = fv.pedido_id
                WHERE fv.id = ?""", facturaId);
        f.put("detalles", pg.queryForList("""
                SELECT id, descripcion, cantidad, precio_unitario, subtotal, monto_impuesto
                FROM factura_venta_detalle WHERE factura_venta_id = ? ORDER BY id""", facturaId));
        return f;
    }

    // ── Caso 9: despachar pedido ─────────────────────────────────────────

    @Transactional
    public Map<String, Object> despachar(long pedidoId, long transportistaId, long metodoEnvioId,
                                         Long bodegaId, String observacion) {
        // Guardia de estado: solo se despacha una vez y desde un estado valido
        String estado = estadoPedido(pedidoId);
        if (List.of("despachado", "entregado").contains(estado)) {
            List<String> guias = pg.queryForList(
                    "SELECT numero_guia FROM envio WHERE pedido_id = ? ORDER BY id",
                    String.class, pedidoId);
            throw new IllegalStateException("El pedido ya fue despachado"
                    + (guias.isEmpty() ? "" : " (guia " + guias.get(0) + ")")
                    + "; no se puede despachar de nuevo");
        }
        // Compuertas: pagado -> facturado -> despachable
        if (List.of("pendiente", "confirmado").contains(estado)) {
            throw new IllegalStateException(
                    "El pedido debe estar pagado antes de despachar; "
                    + "registra primero el pago del cliente (estado actual: '" + estado + "')");
        }
        if (!List.of("pagado", "en_preparacion").contains(estado)) {
            throw new IllegalStateException(
                    "No se puede despachar un pedido en estado '" + estado + "'");
        }
        List<String> facturas = pg.queryForList(
                "SELECT numero FROM factura_venta WHERE pedido_id = ?", String.class, pedidoId);
        if (facturas.isEmpty()) {
            throw new IllegalStateException(
                    "El pedido debe tener factura de venta emitida antes del despacho");
        }

        String direccion = pg.queryForObject("""
                SELECT COALESCE(
                    (SELECT d.calle_principal || COALESCE(', ' || d.referencia, '')
                     FROM pedido p JOIN cliente c ON c.id = p.cliente_id
                     JOIN direccion d ON d.usuario_id = c.usuario_id
                     WHERE p.id = ? ORDER BY d.es_predeterminada DESC LIMIT 1),
                    'Retiro en tienda')""", String.class, pedidoId);

        String numero = siguienteNumero("EN");
        String guia = "GUIA-" + numero.substring(3);
        Long envioId = pg.queryForObject("""
                INSERT INTO envio (numero, pedido_id, transportista_id, metodo_envio_id,
                                   bodega_id, direccion_entrega, numero_guia, estado, fecha_despacho)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'en_transito', now())
                RETURNING id""",
                Long.class, numero, pedidoId, transportistaId, metodoEnvioId,
                bodegaId, direccion, guia);

        pg.update("""
                INSERT INTO envio_detalle (envio_id, pedido_detalle_id, cantidad)
                SELECT ?, id, cantidad FROM pedido_detalle WHERE pedido_id = ?""",
                envioId, pedidoId);

        pg.update("""
                INSERT INTO seguimiento_envio (envio_id, estado, descripcion, ubicacion)
                VALUES (?, 'en_transito', ?, 'Bodega RetailMind - Quevedo')""",
                envioId, "Paquete entregado al transportista"
                        + (observacion != null ? " · " + observacion : ""));

        cambiarEstadoPedido(pedidoId, "despachado", "Despachado con guia " + guia);
        return obtenerEnvio(envioId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> obtenerEnvio(long envioId) {
        Map<String, Object> envio = pg.queryForMap("""
                SELECT e.id, e.numero, e.numero_guia, e.estado, e.fecha_despacho,
                       e.direccion_entrega, t.nombre AS transportista, me.nombre AS metodo_envio,
                       p.numero AS numero_pedido
                FROM envio e
                LEFT JOIN transportista t ON t.id = e.transportista_id
                LEFT JOIN metodo_envio me ON me.id = e.metodo_envio_id
                JOIN pedido p ON p.id = e.pedido_id
                WHERE e.id = ?""", envioId);
        envio.put("detalles", pg.queryForList("""
                SELECT ed.cantidad, pd.sku, pd.nombre_producto
                FROM envio_detalle ed JOIN pedido_detalle pd ON pd.id = ed.pedido_detalle_id
                WHERE ed.envio_id = ?""", envioId));
        return envio;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> seguimiento(long envioId) {
        return pg.queryForList("""
                SELECT estado, descripcion, ubicacion, fecha_evento
                FROM seguimiento_envio WHERE envio_id = ? ORDER BY id""", envioId);
    }

    // ── Entrega del pedido (cierra la logística) ─────────────────────────

    /** Compuerta: solo un pedido despachado puede marcarse entregado. */
    @Transactional
    public Map<String, Object> entregar(long pedidoId, String observacion) {
        String estado = estadoPedido(pedidoId);
        if ("entregado".equals(estado)) {
            throw new IllegalStateException(
                    "El pedido ya fue marcado como entregado; no se puede entregar de nuevo");
        }
        if (!"despachado".equals(estado)) {
            throw new IllegalStateException(
                    "Solo se puede marcar la entrega de un pedido despachado "
                    + "(estado actual: '" + estado + "')");
        }
        // Cierra el envío vigente (el más reciente) y deja rastro de seguimiento
        List<Map<String, Object>> envios = pg.queryForList(
                "SELECT id, numero_guia FROM envio WHERE pedido_id = ? ORDER BY id DESC", pedidoId);
        String guia = null;
        if (!envios.isEmpty()) {
            long envioId = ((Number) envios.get(0).get("id")).longValue();
            guia = (String) envios.get(0).get("numero_guia");
            pg.update("""
                    UPDATE envio SET estado = 'entregado', fecha_entrega_real = now()
                    WHERE id = ?""", envioId);
            pg.update("""
                    INSERT INTO seguimiento_envio (envio_id, estado, descripcion, ubicacion)
                    VALUES (?, 'entregado', ?, 'Domicilio del cliente')""",
                    envioId, "Paquete entregado al cliente"
                            + (observacion != null && !observacion.isBlank()
                               ? " · " + observacion : ""));
        }
        cambiarEstadoPedido(pedidoId, "entregado",
                "Pedido entregado al cliente" + (guia != null ? " (guia " + guia + ")" : ""));
        return obtenerPedido(pedidoId);
    }

    // ── Caso 10: devolución (RMA) ────────────────────────────────────────

    public record ItemDevolucion(long pedidoDetalleId, int cantidad,
                                 String estadoProducto, String accion) {}

    @Transactional
    public Map<String, Object> procesarDevolucion(long pedidoId, String motivoCodigo,
                                                  long bodegaId, String descripcion,
                                                  List<ItemDevolucion> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("La devolucion requiere al menos un item");
        }
        // Compuerta: la devolución (RMA) aplica a mercancía ya ENTREGADA;
        // 'devuelto' sigue admitiendo devoluciones parciales adicionales.
        String estado = estadoPedido(pedidoId);
        if (!List.of("entregado", "devuelto").contains(estado)) {
            throw new IllegalStateException(
                    "Solo se puede registrar la devolucion de un pedido entregado "
                    + "(estado actual: '" + estado + "')");
        }
        Long motivoOk = pg.queryForObject(
                "SELECT COUNT(*) FROM motivo_devolucion WHERE codigo = ? AND activo",
                Long.class, motivoCodigo);
        if (motivoOk == null || motivoOk == 0) {
            throw new IllegalArgumentException(
                    "El motivo de devolucion '" + motivoCodigo + "' no existe o esta inactivo");
        }
        String numero = siguienteNumero("DV");
        Long devolucionId = pg.queryForObject("""
                INSERT INTO devolucion (numero, pedido_id, motivo_devolucion_id,
                                        usuario_gestiona_id, estado, descripcion)
                VALUES (?, ?, (SELECT id FROM motivo_devolucion WHERE codigo = ? AND activo),
                        ?, 'recibida', ?)
                RETURNING id""",
                Long.class, numero, pedidoId, motivoCodigo, usuarioActualId(), descripcion);

        BigDecimal montoTotal = BigDecimal.ZERO;
        for (ItemDevolucion it : items) {
            Map<String, Object> det = pg.queryForMap("""
                    SELECT producto_variante_id, precio_unitario, cantidad, sku
                    FROM pedido_detalle WHERE id = ? AND pedido_id = ?""",
                    it.pedidoDetalleId(), pedidoId);
            int cantidadPedida = ((Number) det.get("cantidad")).intValue();
            if (it.cantidad() <= 0) {
                throw new IllegalArgumentException("La cantidad a devolver del SKU "
                        + det.get("sku") + " debe ser mayor a cero");
            }
            // Guardia acumulada: no devolver mas de lo comprado entre TODAS las devoluciones
            Integer yaDevueltas = pg.queryForObject("""
                    SELECT COALESCE(SUM(cantidad), 0) FROM devolucion_detalle
                    WHERE pedido_detalle_id = ?""", Integer.class, it.pedidoDetalleId());
            int devueltas = yaDevueltas != null ? yaDevueltas : 0;
            if (devueltas + it.cantidad() > cantidadPedida) {
                throw new IllegalArgumentException("No se puede devolver " + it.cantidad()
                        + " del SKU " + det.get("sku") + ": se compraron " + cantidadPedida
                        + " y ya se devolvieron " + devueltas);
            }
            String estadoProducto = it.estadoProducto() != null
                    && List.of("nuevo", "abierto", "danado").contains(it.estadoProducto())
                    ? it.estadoProducto() : "nuevo";
            String accion = it.accion() != null
                    && List.of("reembolso", "cambio", "credito").contains(it.accion())
                    ? it.accion() : "reembolso";

            pg.update("""
                    INSERT INTO devolucion_detalle
                        (devolucion_id, pedido_detalle_id, cantidad, estado_producto, accion)
                    VALUES (?, ?, ?, ?, ?)""",
                    devolucionId, it.pedidoDetalleId(), it.cantidad(), estadoProducto, accion);

            BigDecimal precio = (BigDecimal) det.get("precio_unitario");
            montoTotal = montoTotal.add(precio.multiply(BigDecimal.valueOf(it.cantidad())));

            // Reingreso al inventario con kardex (entrada por devolución de cliente)
            stock.mover(((Number) det.get("producto_variante_id")).longValue(), bodegaId,
                    "entrada_devolucion_cliente", it.cantidad(),
                    "devolucion", devolucionId, precio, usuarioActualId(), null);
        }

        pg.update("UPDATE devolucion SET monto_total = ? WHERE id = ?", montoTotal, devolucionId);
        cambiarEstadoPedido(pedidoId, "devuelto", "Devolucion " + numero + " procesada");
        return obtenerDevolucion(devolucionId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> obtenerDevolucion(long devolucionId) {
        Map<String, Object> d = pg.queryForMap("""
                SELECT d.id, d.numero, d.estado, d.monto_total, d.descripcion,
                       md.nombre AS motivo, p.numero AS numero_pedido
                FROM devolucion d
                JOIN motivo_devolucion md ON md.id = d.motivo_devolucion_id
                JOIN pedido p ON p.id = d.pedido_id
                WHERE d.id = ?""", devolucionId);
        d.put("detalles", pg.queryForList("""
                SELECT dd.cantidad, dd.estado_producto, dd.accion, pd.sku, pd.nombre_producto
                FROM devolucion_detalle dd
                JOIN pedido_detalle pd ON pd.id = dd.pedido_detalle_id
                WHERE dd.devolucion_id = ?""", devolucionId));
        return d;
    }

    // ── Utilitarios ──────────────────────────────────────────────────────

    /** Estado actual del pedido; falla con mensaje claro si el pedido no existe. */
    private String estadoPedido(long pedidoId) {
        List<String> estados = pg.queryForList("""
                SELECT ep.codigo FROM pedido p
                JOIN estado_pedido ep ON ep.id = p.estado_pedido_id
                WHERE p.id = ?""", String.class, pedidoId);
        if (estados.isEmpty()) {
            throw new IllegalArgumentException("No existe el pedido " + pedidoId);
        }
        return estados.get(0);
    }

    private void cambiarEstadoPedido(long pedidoId, String estadoCodigo, String comentario) {
        int filas = pg.update("""
                UPDATE pedido SET estado_pedido_id = (SELECT id FROM estado_pedido WHERE codigo = ?)
                WHERE id = ?""", estadoCodigo, pedidoId);
        if (filas == 0) throw new IllegalArgumentException("No existe pedido " + pedidoId);
        registrarHistorial(pedidoId, estadoCodigo, comentario);
    }

    private void registrarHistorial(long pedidoId, String estadoCodigo, String comentario) {
        pg.update("""
                INSERT INTO historial_estado_pedido (pedido_id, estado_pedido_id, usuario_id, comentario)
                VALUES (?, (SELECT id FROM estado_pedido WHERE codigo = ?), ?, ?)""",
                pedidoId, estadoCodigo, usuarioActualId(), comentario);
    }

    private String siguienteNumero(String prefijo) {
        return pg.queryForObject(
                "SELECT ? || '-' || to_char(now(), 'YYYYMMDD') || '-' || lpad(floor(random()*100000)::text, 5, '0')",
                String.class, prefijo);
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
