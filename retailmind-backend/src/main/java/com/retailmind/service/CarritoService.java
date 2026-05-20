package com.retailmind.service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class CarritoService {

    private final JdbcTemplate ch;
    private final ProductoCatalogoService catalogoService;

    public CarritoService(@Qualifier("clickHouseJdbc") JdbcTemplate ch,
                          ProductoCatalogoService catalogoService) {
        this.ch = ch;
        this.catalogoService = catalogoService;
    }

    public void agregarItem(String userId, String productoId, int cantidad) {
        // Obtener precio del producto
        Map<String, Object> producto = catalogoService.getProductoById(productoId);
        if (producto == null) throw new NoSuchElementException("Producto no encontrado: " + productoId);

        float precio = ((Number) producto.get("price")).floatValue();
        String carritoId = UUID.randomUUID().toString();
        String now = LocalDateTime.now().toString();

        ch.execute(String.format(
                "INSERT INTO retailmind.carrito_items (carrito_id, user_id, producto_id, cantidad, precio_unitario, fecha_agregado, activo) " +
                "VALUES ('%s', '%s', '%s', %d, %f, '%s', 1)",
                carritoId, userId, productoId, cantidad, precio, now));

        // Registrar evento add_to_cart
        catalogoService.registrarEvento(userId, productoId, "add_to_cart", "web", precio, null);
    }

    public List<Map<String, Object>> getCarrito(String userId) {
        return ch.query(
                "SELECT ci.carrito_id, ci.producto_id, ci.cantidad, ci.precio_unitario, ci.fecha_agregado, " +
                "p.nombre, p.brand, p.categoria_id " +
                "FROM retailmind.carrito_items ci " +
                "LEFT JOIN retailmind.productos_catalogo p ON ci.producto_id = p.producto_id " +
                "WHERE ci.user_id = ? AND ci.activo = 1 ORDER BY ci.fecha_agregado DESC",
                (rs, rn) -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("carritoId", rs.getString("carrito_id"));
                    r.put("productoId", rs.getString("producto_id"));
                    r.put("cantidad", rs.getInt("cantidad"));
                    r.put("precioUnitario", rs.getFloat("precio_unitario"));
                    r.put("fechaAgregado", rs.getString("fecha_agregado"));
                    r.put("nombre", rs.getString("nombre"));
                    r.put("brand", rs.getString("brand"));
                    r.put("categoriaId", rs.getInt("categoria_id"));
                    return r;
                }, userId);
    }

    public void eliminarItem(String userId, String productoId) {
        ch.execute(String.format(
                "ALTER TABLE retailmind.carrito_items UPDATE activo = 0 " +
                "WHERE user_id = '%s' AND producto_id = '%s' AND activo = 1 " +
                "SETTINGS mutations_sync = 1", userId, productoId));

        // Registrar evento drop
        catalogoService.registrarEvento(userId, productoId, "drop", "web", null, null);
    }

    public Map<String, Object> checkout(String userId) {
        List<Map<String, Object>> items = getCarrito(userId);
        if (items.isEmpty()) throw new IllegalStateException("El carrito esta vacio");

        String ordenId = "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String now = LocalDateTime.now().toString();
        float total = 0;

        // Calcular total e insertar orden_items
        for (Map<String, Object> item : items) {
            int cantidad = (int) item.get("cantidad");
            float precio = (float) item.get("precioUnitario");
            total += cantidad * precio;
            String productoId = (String) item.get("productoId");

            ch.execute(String.format(
                    "INSERT INTO retailmind.orden_items (orden_id, producto_id, cantidad, precio_unitario) " +
                    "VALUES ('%s', '%s', %d, %f)", ordenId, productoId, cantidad, precio));

            // Evento purchase por cada producto
            catalogoService.registrarEvento(userId, productoId, "purchase", "web", precio, null);
        }

        // Crear orden
        ch.execute(String.format(
                "INSERT INTO retailmind.ordenes (orden_id, user_id, total, estado, fecha_orden, canal) " +
                "VALUES ('%s', '%s', %f, 'COMPLETADA', '%s', 'web')", ordenId, userId, total, now));

        // Limpiar carrito
        ch.execute(String.format(
                "ALTER TABLE retailmind.carrito_items UPDATE activo = 0 " +
                "WHERE user_id = '%s' AND activo = 1 SETTINGS mutations_sync = 1", userId));

        return Map.of("ordenId", ordenId, "total", total, "items", items.size());
    }
}
