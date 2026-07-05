package com.retailmind.ventas;

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
import com.retailmind.inventario.StockService;

/**
 * Ciclo de venta (Order-to-Cash): pedido -> factura -> despacho -> devolución.
 * Réplica del patrón de compras/:
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
        pedido.put("historial", pg.queryForList("""
                SELECT ep.codigo AS estado, h.comentario, h.fecha_creacion
                FROM historial_estado_pedido h
                JOIN estado_pedido ep ON ep.id = h.estado_pedido_id
                WHERE h.pedido_id = ? ORDER BY h.id""", pedidoId));
        return pedido;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarPedidos() {
        return pg.queryForList("""
                SELECT p.id, p.numero, ep.codigo AS estado, p.total, p.fecha_pedido,
                       c.nombre || ' ' || COALESCE(c.apellido,'') AS cliente
                FROM pedido p
                JOIN estado_pedido ep ON ep.id = p.estado_pedido_id
                JOIN cliente c ON c.id = p.cliente_id
                ORDER BY p.id DESC""");
    }

    // ── Caso 8: factura de venta ─────────────────────────────────────────

    @Transactional
    public Map<String, Object> emitirFactura(long pedidoId) {
        String estado = estadoPedido(pedidoId);
        if (List.of("cancelado", "devuelto").contains(estado)) {
            throw new IllegalStateException(
                    "No se puede facturar un pedido en estado '" + estado + "'");
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

        registrarHistorial(pedidoId, "pagado", "Factura de venta " + numero + " emitida");
        return obtenerFactura(facturaId); // totales ya recalculados por el trigger
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
        if (!List.of("confirmado", "pagado", "en_preparacion").contains(estado)) {
            throw new IllegalStateException(
                    "No se puede despachar un pedido en estado '" + estado + "'");
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
        String estado = estadoPedido(pedidoId);
        if (List.of("cancelado", "pendiente").contains(estado)) {
            throw new IllegalStateException(
                    "No se puede devolver un pedido en estado '" + estado + "'");
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
}
