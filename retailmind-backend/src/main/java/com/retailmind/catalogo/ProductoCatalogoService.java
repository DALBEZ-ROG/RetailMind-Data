package com.retailmind.catalogo;

import java.time.LocalDateTime;
import java.time.temporal.WeekFields;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ProductoCatalogoService {

    private static final Logger logger = LoggerFactory.getLogger(ProductoCatalogoService.class);
    private final JdbcTemplate ch;

    public ProductoCatalogoService(@Qualifier("clickHouseJdbc") JdbcTemplate ch) {
        this.ch = ch;
    }

    public Map<String, Object> getProductos(Integer categoriaId, String brand,
                                             Float minPrice, Float maxPrice, int page, int size) {
        try {
            StringBuilder where = new StringBuilder(" WHERE p.activo = 1");
            if (categoriaId != null) where.append(" AND p.categoria_id = ").append(categoriaId);
            if (brand != null && !brand.isEmpty()) where.append(" AND p.brand = '").append(brand).append("'");
            if (minPrice != null) where.append(" AND p.price >= ").append(minPrice);
            if (maxPrice != null) where.append(" AND p.price <= ").append(maxPrice);

            String countWhere = where.toString().replace("p.activo", "activo")
                    .replace("p.categoria_id", "categoria_id")
                    .replace("p.brand", "brand")
                    .replace("p.price", "price");

            Long total = ch.queryForObject(
                    "SELECT count() FROM retailmind.productos_catalogo" + countWhere, Long.class);

            List<Map<String, Object>> rows = ch.query(
                    "SELECT p.producto_id, p.nombre, p.descripcion, p.categoria_id, " +
                    "p.brand, p.price, p.stock, p.imagen_url, " +
                    "c.categoria_nombre " +
                    "FROM retailmind.productos_catalogo p " +
                    "LEFT JOIN retailmind.dim_categoria c ON p.categoria_id = c.categoria_id" +
                    where +
                    " ORDER BY p.producto_id LIMIT " + size + " OFFSET " + (page * size),
                    (rs, rn) -> {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("productoId", rs.getString("producto_id"));
                        r.put("nombre", rs.getString("nombre"));
                        r.put("descripcion", rs.getString("descripcion"));
                        r.put("categoriaId", rs.getInt("categoria_id"));
                        r.put("categoriaNombre", rs.getString("categoria_nombre"));
                        r.put("brand", rs.getString("brand"));
                        r.put("price", rs.getFloat("price"));
                        r.put("stock", rs.getInt("stock"));
                        r.put("imagenUrl", rs.getString("imagen_url"));
                        return r;
                    });

            long t = total != null ? total : 0L;
            return Map.of("content", rows, "totalElements", t,
                    "totalPages", (int) Math.ceil((double) t / size), "number", page, "size", size);
        } catch (Exception e) {
            logger.error("Error al obtener productos: {}", e.getMessage());
            return Map.of("content", List.of(), "totalElements", 0L, "totalPages", 0, "number", page, "size", size);
        }
    }

    public Map<String, Object> getProductoById(String productoId) {
        try {
            List<Map<String, Object>> rows = ch.query(
                    "SELECT p.producto_id, p.nombre, p.descripcion, p.categoria_id, " +
                    "p.brand, p.price, p.stock, p.imagen_url, " +
                    "c.categoria_nombre " +
                    "FROM retailmind.productos_catalogo p " +
                    "LEFT JOIN retailmind.dim_categoria c ON p.categoria_id = c.categoria_id " +
                    "WHERE p.producto_id = '" + productoId + "'",
                    (rs, rn) -> {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("productoId", rs.getString("producto_id"));
                        r.put("nombre", rs.getString("nombre"));
                        r.put("descripcion", rs.getString("descripcion"));
                        r.put("categoriaId", rs.getInt("categoria_id"));
                        r.put("categoriaNombre", rs.getString("categoria_nombre"));
                        r.put("brand", rs.getString("brand"));
                        r.put("price", rs.getFloat("price"));
                        r.put("stock", rs.getInt("stock"));
                        r.put("imagenUrl", rs.getString("imagen_url"));
                        return r;
                    });
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            logger.error("Error al obtener producto {}: {}", productoId, e.getMessage());
            return null;
        }
    }

    public List<Map<String, Object>> getCategorias() {
        try {
            return ch.query(
                    "SELECT p.categoria_id, c.categoria_nombre, count() as total " +
                    "FROM retailmind.productos_catalogo p " +
                    "INNER JOIN retailmind.dim_categoria c ON p.categoria_id = c.categoria_id " +
                    "WHERE p.activo = 1 " +
                    "GROUP BY p.categoria_id, c.categoria_nombre " +
                    "ORDER BY p.categoria_id",
                    (rs, rn) -> {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("categoriaId", rs.getInt("categoria_id"));
                        r.put("nombre", rs.getString("categoria_nombre"));
                        r.put("total", rs.getLong("total"));
                        return r;
                    });
        } catch (Exception e) {
            logger.error("Error al obtener categorias: {}", e.getMessage());
            return List.of();
        }
    }

    public List<String> getMarcas() {
        try {
            return ch.query(
                    "SELECT DISTINCT brand FROM retailmind.productos_catalogo WHERE activo = 1 ORDER BY brand",
                    (rs, rn) -> rs.getString("brand"));
        } catch (Exception e) {
            logger.error("Error al obtener marcas: {}", e.getMessage());
            return List.of();
        }
    }

    public void registrarEvento(String userId, String productId, String userAction,
                                 String channel, Float price, String sessionId) {
        try {
            if (sessionId == null || sessionId.isEmpty()) {
                sessionId = "sess_shop_" + UUID.randomUUID().toString().substring(0, 8);
            }
            int semana = LocalDateTime.now().get(WeekFields.ISO.weekOfYear());
            int isConversion = "purchase".equals(userAction) ? 1 : 0;
            int dropOff = "drop".equals(userAction) ? 1 : 0;
            String timestamp = LocalDateTime.now().toString();

            ch.execute(String.format(
                    "INSERT INTO retailmind.fact_eventos " +
                    "(session_id, user_id, timestamp_utc, event_index, user_action, product_id, " +
                    "time_spent_sec, session_length, interaction_count, is_conversion, drop_off_flag, " +
                    "price, channel, semana) VALUES " +
                    "('%s', '%s', '%s', 1, '%s', '%s', 0, 0, 1, %d, %d, %s, '%s', %d)",
                    sessionId, userId != null ? userId : "anonymous", timestamp,
                    userAction, productId != null ? productId : "",
                    isConversion, dropOff,
                    price != null ? price.toString() : "0",
                    channel != null ? channel : "web", semana));
        } catch (Exception e) {
            logger.warn("Error al registrar evento: {}", e.getMessage());
        }
    }
}
