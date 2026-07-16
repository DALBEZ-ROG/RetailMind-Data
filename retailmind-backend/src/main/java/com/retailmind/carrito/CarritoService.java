package com.retailmind.carrito;

import java.math.BigDecimal;
import java.util.ArrayList;
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
import com.retailmind.catalogo.EventoTiendaService;
import com.retailmind.ventas.VentasService;

/**
 * Carrito de la tienda del cliente sobre PostgreSQL (carrito/carrito_item).
 * El aislamiento lo da el RLS (app.cliente_id): todas las consultas corren
 * bajo SET LOCAL ROLE grp_cliente y solo ven el carrito propio.
 *
 * El checkout convierte el carrito en un PEDIDO REAL del ciclo de venta
 * (VentasService.crearPedido): mismo modelo, mismos triggers de totales y
 * mismo descuento de stock con kardex que usa el back-office. No existen
 * pedidos paralelos.
 */
@Service
public class CarritoService {

    private final JdbcTemplate pg;
    private final VentasService ventas;
    private final EventoTiendaService eventos;

    public CarritoService(@Qualifier("pgJdbcTemplate") JdbcTemplate pg,
                          VentasService ventas,
                          EventoTiendaService eventos) {
        this.pg = pg;
        this.ventas = ventas;
        this.eventos = eventos;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getItems() {
        List<Long> ids = carritosActivos();
        return ids.isEmpty() ? List.of() : itemsDe(ids.get(0));
    }

    @Transactional
    public void agregarItem(long varianteId, int cantidad) {
        if (cantidad <= 0) {
            throw new IllegalArgumentException("La cantidad debe ser mayor a cero");
        }
        List<Map<String, Object>> variantes = pg.queryForList("""
                SELECT pv.precio, pr.nombre,
                       (SELECT COALESCE(SUM(stock_actual), 0) FROM inventario i
                        WHERE i.producto_variante_id = pv.id) AS stock
                FROM producto_variante pv
                JOIN producto pr ON pr.id = pv.producto_id
                WHERE pv.id = ? AND pv.activo AND pr.publicado AND pr.activo""", varianteId);
        if (variantes.isEmpty()) {
            throw new NoSuchElementException("El producto no existe o no está disponible");
        }
        BigDecimal precio = (BigDecimal) variantes.get(0).get("precio");
        long stock = ((Number) variantes.get(0).get("stock")).longValue();

        long carritoId = carritoActivoOCrear();
        Integer enCarrito = pg.queryForObject("""
                SELECT COALESCE(SUM(cantidad), 0) FROM carrito_item
                WHERE carrito_id = ? AND producto_variante_id = ?""",
                Integer.class, carritoId, varianteId);
        int yaAgregado = enCarrito != null ? enCarrito : 0;
        if (yaAgregado + cantidad > stock) {
            throw new IllegalArgumentException("Stock insuficiente: disponible " + stock
                    + (yaAgregado > 0 ? " (ya tienes " + yaAgregado + " en el carrito)" : ""));
        }

        int actualizadas = pg.update("""
                UPDATE carrito_item SET cantidad = cantidad + ?
                WHERE carrito_id = ? AND producto_variante_id = ?""",
                cantidad, carritoId, varianteId);
        if (actualizadas == 0) {
            pg.update("""
                    INSERT INTO carrito_item (carrito_id, producto_variante_id, cantidad, precio_unitario)
                    VALUES (?, ?, ?, ?)""",
                    carritoId, varianteId, cantidad, precio);
        }
        eventos.registrar(usuarioEmail(), String.valueOf(varianteId), "add_to_cart",
                "web", precio.doubleValue(), null);
    }

    @Transactional
    public void cambiarCantidad(long varianteId, int cantidad) {
        if (cantidad <= 0) {
            throw new IllegalArgumentException(
                    "La cantidad debe ser mayor a cero; para quitar el producto usa eliminar");
        }
        List<Long> ids = carritosActivos();
        int filas = ids.isEmpty() ? 0 : pg.update("""
                UPDATE carrito_item SET cantidad = ?
                WHERE carrito_id = ? AND producto_variante_id = ?""",
                cantidad, ids.get(0), varianteId);
        if (filas == 0) {
            throw new NoSuchElementException("El producto no está en tu carrito");
        }
    }

    @Transactional
    public void eliminarItem(long varianteId) {
        List<Long> ids = carritosActivos();
        int filas = ids.isEmpty() ? 0 : pg.update(
                "DELETE FROM carrito_item WHERE carrito_id = ? AND producto_variante_id = ?",
                ids.get(0), varianteId);
        if (filas == 0) {
            throw new NoSuchElementException("El producto no está en tu carrito");
        }
        eventos.registrar(usuarioEmail(), String.valueOf(varianteId), "drop", "web", null, null);
    }

    public record TarjetaReq(String numero, String titular, String vencimiento, String cvv) {}
    public record CheckoutReq(Long direccionId, Long metodoPagoId, String cupon,
                              TarjetaReq tarjeta, String referenciaTransferencia) {}

    /** Métodos de pago disponibles en el checkout online (pago simulado). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> metodosCheckout() {
        return pg.queryForList("""
                SELECT id, codigo, nombre, tipo FROM metodo_pago
                WHERE activo AND tipo IN ('tarjeta', 'transferencia')
                ORDER BY orden, id""");
    }

    /**
     * Checkout ONLINE completo (tipo tienda real): valida dirección de envío
     * y método de pago, crea el pedido del ciclo de venta (canal 'web'),
     * registra el pago SIMULADO y deja el pedido 'pagado' — todo en UNA
     * transacción bajo grp_cliente. El stock se descuenta dentro de
     * crearPedido vía StockService (kardex incluido); si algo se agotó, la
     * transacción completa se revierte con mensaje claro.
     *
     * Tarjeta: se valida formato (número 16 dígitos, MM/AA vigente,
     * CVV 3-4 dígitos) pero NO hay pasarela real y NUNCA se persiste el número
     * completo ni el CVV: solo marca + últimos 4 como referencia.
     *
     * Cupón: el campo viaja en la request pero su validación/aplicación es de
     * la fase de descuentos. Enganche futuro: validar contra cupon/uso_cupon
     * (marketing) ANTES de crearPedido y pasar el descuento al pedido. Hoy
     * solo se informa que aún no aplica, sin romper el checkout.
     */
    @Transactional
    public Map<String, Object> checkout(CheckoutReq req) {
        AppUserPrincipal principal = principal();
        if (principal == null || principal.getClienteId() == null) {
            throw new IllegalStateException("Solo un cliente puede hacer checkout");
        }
        List<Long> ids = carritosActivos();
        if (ids.isEmpty()) {
            throw new IllegalStateException("El carrito esta vacio");
        }
        long carritoId = ids.get(0);
        List<Map<String, Object>> items = pg.queryForList("""
                SELECT producto_variante_id, cantidad, precio_unitario
                FROM carrito_item WHERE carrito_id = ? ORDER BY id""", carritoId);
        if (items.isEmpty()) {
            throw new IllegalStateException("El carrito esta vacio");
        }

        // Dirección de envío obligatoria y del propio cliente (RLS: una
        // dirección ajena simplemente no es visible para grp_cliente)
        if (req == null || req.direccionId() == null) {
            throw new IllegalArgumentException("Selecciona una direccion de envio");
        }
        List<Long> direcciones = pg.queryForList(
                "SELECT id FROM direccion WHERE id = ? AND activo", Long.class, req.direccionId());
        if (direcciones.isEmpty()) {
            throw new IllegalArgumentException(
                    "La direccion de envio seleccionada no existe o no te pertenece");
        }

        // Método de pago obligatorio y habilitado para compras online
        if (req.metodoPagoId() == null) {
            throw new IllegalArgumentException("Selecciona un metodo de pago");
        }
        List<Map<String, Object>> metodos = pg.queryForList("""
                SELECT nombre, tipo FROM metodo_pago
                WHERE id = ? AND activo AND tipo IN ('tarjeta', 'transferencia')""",
                req.metodoPagoId());
        if (metodos.isEmpty()) {
            throw new IllegalArgumentException(
                    "El metodo de pago no esta disponible para compras online");
        }
        String tipoMetodo = (String) metodos.get(0).get("tipo");

        // Datos del pago simulado (referencia + autorización + detalle jsonb)
        String referencia;
        String detalleJson;
        String autorizacion = "SIM-" + siglasAleatorias();
        if ("tarjeta".equals(tipoMetodo)) {
            referencia = validarTarjeta(req.tarjeta());   // "VISA ****1234"
            detalleJson = "{\"simulado\": true, \"tipo\": \"tarjeta\", \"referencia\": \""
                    + referencia + "\"}";
        } else {
            String ref = req.referenciaTransferencia();
            referencia = ref != null && !ref.isBlank()
                    ? ref.trim() : "TRANSF-" + siglasAleatorias();
            detalleJson = "{\"simulado\": true, \"tipo\": \"transferencia\"}";
        }

        List<Long> bodegas = pg.queryForList("""
                SELECT id FROM bodega WHERE es_principal AND activo ORDER BY id LIMIT 1""",
                Long.class);
        if (bodegas.isEmpty()) {
            throw new IllegalStateException("No hay bodega principal configurada para despachar");
        }

        List<VentasService.ItemPedido> itemsPedido = new ArrayList<>();
        for (Map<String, Object> it : items) {
            itemsPedido.add(new VentasService.ItemPedido(
                    ((Number) it.get("producto_variante_id")).longValue(),
                    ((Number) it.get("cantidad")).intValue()));
        }

        // Pedido real del ciclo de venta (descuenta stock) + pago simulado:
        // el pedido ONLINE nace PAGADO, listo para facturar y despachar.
        Map<String, Object> pedido = ventas.crearPedido(
                principal.getClienteId(), bodegas.get(0), "web", itemsPedido,
                req.direccionId());
        long pedidoId = ((Number) pedido.get("id")).longValue();
        ventas.pagarCheckoutOnline(pedidoId, req.metodoPagoId(),
                referencia, autorizacion, detalleJson);

        pg.update("UPDATE carrito SET estado = 'convertido' WHERE id = ?", carritoId);

        for (Map<String, Object> it : items) {
            eventos.registrar(usuarioEmail(),
                    String.valueOf(it.get("producto_variante_id")), "purchase", "web",
                    ((BigDecimal) it.get("precio_unitario")).doubleValue(), null);
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("pedidoId", pedidoId);
        res.put("ordenId", pedido.get("numero"));
        res.put("total", pedido.get("total"));
        res.put("items", items.size());
        res.put("estado", "pagado");
        res.put("metodoPago", metodos.get(0).get("nombre"));
        res.put("referenciaPago", referencia);
        if (req.cupon() != null && !req.cupon().isBlank()) {
            // Fase de descuentos pendiente: no rompe el checkout, solo informa
            res.put("cuponMensaje", "El cupon '" + req.cupon().trim()
                    + "' se validara cuando el modulo de descuentos este activo; "
                    + "tu pedido se proceso sin descuento");
        }
        return res;
    }

    // ── Validación de tarjeta (pago simulado; nunca se persiste PAN/CVV) ─

    /** Valida formato y devuelve la referencia segura "MARCA ****9999". */
    private static String validarTarjeta(TarjetaReq t) {
        if (t == null) {
            throw new IllegalArgumentException("Completa los datos de la tarjeta");
        }
        String numero = t.numero() == null ? "" : t.numero().replaceAll("[\\s-]", "");
        if (!numero.matches("\\d{16}")) {
            throw new IllegalArgumentException(
                    "El numero de tarjeta debe tener 16 digitos");
        }
        if (t.titular() == null || t.titular().isBlank()) {
            throw new IllegalArgumentException("El nombre del titular es requerido");
        }
        String venc = t.vencimiento() == null ? "" : t.vencimiento().trim();
        if (!venc.matches("(0[1-9]|1[0-2])/\\d{2}")) {
            throw new IllegalArgumentException("El vencimiento debe tener formato MM/AA");
        }
        java.time.YearMonth vencimiento = java.time.YearMonth.of(
                2000 + Integer.parseInt(venc.substring(3)), Integer.parseInt(venc.substring(0, 2)));
        if (vencimiento.isBefore(java.time.YearMonth.now())) {
            throw new IllegalArgumentException("La tarjeta esta vencida (" + venc + ")");
        }
        if (t.cvv() == null || !t.cvv().matches("\\d{3,4}")) {
            throw new IllegalArgumentException("El CVV debe tener 3 o 4 digitos");
        }
        return marcaDe(numero) + " ****" + numero.substring(numero.length() - 4);
    }

    private static String marcaDe(String numero) {
        if (numero.startsWith("4")) return "VISA";
        if (numero.matches("^(5[1-5]|2[2-7]).*")) return "MASTERCARD";
        if (numero.matches("^3[47].*")) return "AMEX";
        if (numero.startsWith("6")) return "DISCOVER";
        return "TARJETA";
    }

    private static String siglasAleatorias() {
        return String.valueOf((int) (Math.random() * 900000) + 100000);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /** Carritos activos visibles (RLS: solo los del cliente autenticado). */
    private List<Long> carritosActivos() {
        return pg.queryForList(
                "SELECT id FROM carrito WHERE estado = 'activo' ORDER BY id DESC LIMIT 1",
                Long.class);
    }

    private long carritoActivoOCrear() {
        List<Long> ids = carritosActivos();
        if (!ids.isEmpty()) return ids.get(0);
        AppUserPrincipal principal = principal();
        if (principal == null || principal.getClienteId() == null) {
            throw new IllegalStateException("Solo un cliente puede usar el carrito");
        }
        Long id = pg.queryForObject(
                "INSERT INTO carrito (cliente_id, estado) VALUES (?, 'activo') RETURNING id",
                Long.class, principal.getClienteId());
        return id != null ? id : 0L;
    }

    private List<Map<String, Object>> itemsDe(long carritoId) {
        return pg.query("""
                SELECT ci.producto_variante_id, ci.cantidad, ci.precio_unitario,
                       ci.fecha_creacion, pr.nombre, m.nombre AS marca,
                       COALESCE(cat.categoria_id, 0) AS categoria_id,
                       (SELECT COALESCE(SUM(stock_actual), 0) FROM inventario i
                        WHERE i.producto_variante_id = ci.producto_variante_id) AS stock
                FROM carrito_item ci
                JOIN producto_variante pv ON pv.id = ci.producto_variante_id
                JOIN producto pr ON pr.id = pv.producto_id
                LEFT JOIN marca m ON m.id = pr.marca_id
                LEFT JOIN LATERAL (SELECT pc.categoria_id FROM producto_categoria pc
                                   WHERE pc.producto_id = pr.id
                                   ORDER BY pc.es_principal DESC, pc.id LIMIT 1) cat ON true
                WHERE ci.carrito_id = ? ORDER BY ci.id""",
                (rs, rn) -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("productoId", rs.getLong("producto_variante_id"));
                    r.put("cantidad", rs.getInt("cantidad"));
                    r.put("precioUnitario", rs.getBigDecimal("precio_unitario"));
                    r.put("fechaAgregado", rs.getString("fecha_creacion"));
                    r.put("nombre", rs.getString("nombre"));
                    r.put("brand", rs.getString("marca"));
                    r.put("categoriaId", rs.getInt("categoria_id"));
                    r.put("stock", rs.getLong("stock"));
                    return r;
                }, carritoId);
    }

    private AppUserPrincipal principal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof AppUserPrincipal p ? p : null;
    }

    private String usuarioEmail() {
        AppUserPrincipal p = principal();
        return p != null ? p.getUsername() : null;
    }
}
