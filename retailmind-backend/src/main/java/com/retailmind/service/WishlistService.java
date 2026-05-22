package com.retailmind.service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class WishlistService {

    private final JdbcTemplate ch;
    private final ProductoCatalogoService catalogoService;

    public WishlistService(@Qualifier("clickHouseJdbc") JdbcTemplate ch,
                           ProductoCatalogoService catalogoService) {
        this.ch = ch;
        this.catalogoService = catalogoService;
    }

    public void agregar(String userId, String productoId) {
        // Verificar que no esté ya en wishlist
        Long count = ch.queryForObject(
                "SELECT count() FROM retailmind.wishlist_items WHERE user_id = '" + userId +
                "' AND producto_id = '" + productoId + "'", Long.class);
        if (count != null && count > 0) {
            throw new IllegalStateException("El producto ya esta en tu wishlist");
        }

        String wishlistId = UUID.randomUUID().toString();
        String now = LocalDateTime.now().toString();
        ch.execute("INSERT INTO retailmind.wishlist_items (wishlist_id, user_id, producto_id, fecha_agregado) " +
                "VALUES ('" + wishlistId + "', '" + userId + "', '" + productoId + "', '" + now + "')");

        // Registrar evento
        catalogoService.registrarEvento(userId, productoId, "wishlist", "web", null, null);
    }

    public List<Map<String, Object>> getWishlist(String userId) {
        return ch.query(
                "SELECT w.producto_id, w.fecha_agregado, " +
                "p.nombre, p.brand, p.price, p.categoria_id, p.imagen_url " +
                "FROM retailmind.wishlist_items w " +
                "LEFT JOIN retailmind.productos_catalogo p ON w.producto_id = p.producto_id " +
                "WHERE w.user_id = '" + userId + "' ORDER BY w.fecha_agregado DESC",
                (rs, rn) -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("productoId", rs.getString("producto_id"));
                    r.put("fechaAgregado", rs.getString("fecha_agregado"));
                    r.put("nombre", rs.getString("nombre"));
                    r.put("brand", rs.getString("brand"));
                    r.put("price", rs.getFloat("price"));
                    r.put("categoriaId", rs.getInt("categoria_id"));
                    r.put("imagenUrl", rs.getString("imagen_url"));
                    return r;
                });
    }

    public void eliminar(String userId, String productoId) {
        ch.execute("ALTER TABLE retailmind.wishlist_items DELETE " +
                "WHERE user_id = '" + userId + "' AND producto_id = '" + productoId + "' " +
                "SETTINGS mutations_sync = 1");
    }
}
