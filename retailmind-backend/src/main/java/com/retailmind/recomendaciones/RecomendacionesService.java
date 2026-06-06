package com.retailmind.recomendaciones;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@SuppressWarnings({"null", "resource"})
public class RecomendacionesService {

    private static final Logger logger = LoggerFactory.getLogger(RecomendacionesService.class);
    private final JdbcTemplate ch;

    public RecomendacionesService(@Qualifier("clickHouseJdbc") JdbcTemplate ch) {
        this.ch = ch;
    }

    public Map<String, Object> getRecomendaciones(String username) {
        long inicio = System.currentTimeMillis();

        Long totalEventos = queryLong(
                "SELECT count() FROM retailmind.fact_eventos WHERE user_id = '" + username + "'");

        Map<String, Object> resultado = new LinkedHashMap<>();
        resultado.put("username", username);
        resultado.put("totalEventos", totalEventos);

        if (totalEventos < 10) {
            // ── FALLBACK: productos más comprados globalmente ──────────────────
            List<Map<String, Object>> populares = getProductosPopulares();
            resultado.put("recomendaciones", populares);
            resultado.put("esPersonalizado", false);
            resultado.put("tipo", "populares");
            resultado.put("mensajeFallback",
                    "Navega más productos para recibir recomendaciones personalizadas");
            resultado.put("categoriaFavorita", null);
        } else {
            // ── PERSONALIZADO ─────────────────────────────────────────────────
            List<Integer> topCategorias = getTopCategoriasPonderadas(username);
            List<String> productosVistos = getProductosVistos(username);
            List<Map<String, Object>> recomendados =
                    getProductosRecomendados(topCategorias, productosVistos);

            // Si tras el filtrado quedaron menos de 4, completar con populares
            if (recomendados.size() < 4) {
                recomendados = getProductosPopulares();
            }

            String categoriaFavorita = getCategoriaFavorita(username);

            resultado.put("recomendaciones", recomendados);
            resultado.put("esPersonalizado", true);
            resultado.put("tipo", "personalizado");
            resultado.put("mensajeFallback", null);
            resultado.put("categoriaFavorita",
                    categoriaFavorita != null ? categoriaFavorita : "General");
        }

        resultado.put("queryMs", System.currentTimeMillis() - inicio);
        return resultado;
    }

    public List<Map<String, Object>> getSimilares(String username, String productoId) {
        try {
            List<Map<String, Object>> productoInfo = ch.query(
                    "SELECT categoria_id, price " +
                    "FROM retailmind.productos_catalogo " +
                    "WHERE producto_id = '" + productoId + "' AND activo = 1 LIMIT 1",
                    (rs, rn) -> {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("categoriaId", rs.getInt("categoria_id"));
                        r.put("price", rs.getFloat("price"));
                        return r;
                    });

            if (productoInfo.isEmpty()) return List.of();

            int categoriaId = (int) productoInfo.get(0).get("categoriaId");
            float price     = (float) productoInfo.get(0).get("price");
            float minPrice  = price * 0.7f;
            float maxPrice  = price * 1.3f;

            List<String> productosVistos = getProductosVistos(username);
            String exclusion = buildExclusion(productosVistos);

            return ch.query(
                    "SELECT producto_id, nombre, brand, price, categoria_id, imagen_url " +
                    "FROM retailmind.productos_catalogo " +
                    "WHERE categoria_id = " + categoriaId +
                    " AND price >= " + minPrice +
                    " AND price <= " + maxPrice +
                    " AND producto_id != '" + productoId + "'" +
                    (exclusion.isEmpty() ? "" : " AND producto_id NOT IN (" + exclusion + ")") +
                    " AND activo = 1 ORDER BY rand() LIMIT 6",
                    (rs, rn) -> {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("productoId",   rs.getString("producto_id"));
                        r.put("nombre",       rs.getString("nombre"));
                        r.put("brand",        rs.getString("brand"));
                        r.put("price",        rs.getFloat("price"));
                        r.put("categoriaId",  rs.getInt("categoria_id"));
                        r.put("imagenUrl",    rs.getString("imagen_url"));
                        return r;
                    });
        } catch (RuntimeException e) {
            logger.warn("Error al obtener similares para {}: {}", productoId, e.getMessage());
            return List.of();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Productos más comprados globalmente — usado como fallback. */
    private List<Map<String, Object>> getProductosPopulares() {
        try {
            return ch.query(
                    "SELECT pc.producto_id, pc.nombre, pc.brand, pc.price, " +
                    "pc.categoria_id, pc.imagen_url, pc.descripcion, pc.stock, " +
                    "dc.categoria_nombre, count() AS total_compras " +
                    "FROM retailmind.fact_eventos fe " +
                    "JOIN retailmind.productos_catalogo pc ON fe.product_id = pc.producto_id " +
                    "JOIN retailmind.dim_categoria dc ON pc.categoria_id = dc.categoria_id " +
                    "WHERE fe.user_action = 'purchase' AND pc.activo = 1 " +
                    "GROUP BY pc.producto_id, pc.nombre, pc.brand, pc.price, " +
                    "         pc.categoria_id, pc.imagen_url, pc.descripcion, pc.stock, " +
                    "         dc.categoria_nombre " +
                    "ORDER BY total_compras DESC LIMIT 12",
                    (rs, rn) -> mapProductoCompleto(rs));
        } catch (RuntimeException e) {
            logger.warn("Error en getProductosPopulares: {}", e.getMessage());
            // Último recurso: productos del catálogo sin join a eventos
            return getProductosCatalogoFallback();
        }
    }

    /** Fallback de último recurso si fact_eventos está vacía. */
    private List<Map<String, Object>> getProductosCatalogoFallback() {
        try {
            return ch.query(
                    "SELECT pc.producto_id, pc.nombre, pc.brand, pc.price, " +
                    "pc.categoria_id, pc.imagen_url, pc.descripcion, pc.stock, " +
                    "dc.categoria_nombre, toUInt64(0) AS total_compras " +
                    "FROM retailmind.productos_catalogo pc " +
                    "JOIN retailmind.dim_categoria dc ON pc.categoria_id = dc.categoria_id " +
                    "WHERE pc.activo = 1 ORDER BY pc.stock DESC LIMIT 12",
                    (rs, rn) -> mapProductoCompleto(rs));
        } catch (RuntimeException e) {
            logger.warn("Error en getProductosCatalogoFallback: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Integer> getTopCategoriasPonderadas(String username) {
        try {
            return ch.query(
                    "SELECT p.categoria_id, " +
                    "sum(multiIf(fe.user_action='purchase', 5, " +
                    "           fe.user_action='add_to_cart', 3, " +
                    "           fe.user_action='wishlist', 2, " +
                    "           fe.user_action='click', 1, 0.5)) AS score " +
                    "FROM retailmind.fact_eventos fe " +
                    "JOIN retailmind.dim_producto p ON fe.product_id = p.producto_id " +
                    "WHERE fe.user_id = '" + username + "' " +
                    "GROUP BY p.categoria_id ORDER BY score DESC LIMIT 3",
                    (rs, rn) -> rs.getInt("categoria_id"));
        } catch (RuntimeException e) {
            logger.warn("Error en getTopCategoriasPonderadas: {}", e.getMessage());
            return List.of();
        }
    }

    private List<String> getProductosVistos(String username) {
        try {
            return ch.query(
                    "SELECT DISTINCT product_id FROM retailmind.fact_eventos " +
                    "WHERE user_id = '" + username + "'",
                    (rs, rn) -> rs.getString("product_id"));
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private List<Map<String, Object>> getProductosRecomendados(List<Integer> categorias,
                                                                List<String> productosVistos) {
        if (categorias.isEmpty()) return getProductosPopulares();

        String catList   = categorias.stream().map(String::valueOf).collect(Collectors.joining(","));
        String exclusion = buildExclusion(productosVistos);

        try {
            return ch.query(
                    "SELECT pc.producto_id, pc.nombre, pc.brand, pc.price, " +
                    "pc.categoria_id, pc.imagen_url, pc.descripcion, pc.stock, " +
                    "dc.categoria_nombre, toUInt64(0) AS total_compras " +
                    "FROM retailmind.productos_catalogo pc " +
                    "JOIN retailmind.dim_producto dp ON pc.producto_id = dp.producto_id " +
                    "JOIN retailmind.dim_categoria dc ON dp.categoria_id = dc.categoria_id " +
                    "WHERE dp.categoria_id IN (" + catList + ")" +
                    (exclusion.isEmpty() ? "" : " AND pc.producto_id NOT IN (" + exclusion + ")") +
                    " AND pc.activo = 1 ORDER BY rand() LIMIT 12",
                    (rs, rn) -> mapProductoCompleto(rs));
        } catch (RuntimeException e) {
            logger.warn("Error en getProductosRecomendados: {}", e.getMessage());
            return getProductosPopulares();
        }
    }

    private String getCategoriaFavorita(String username) {
        try {
            List<String> result = ch.query(
                    "SELECT dc.categoria_nombre " +
                    "FROM retailmind.fact_eventos fe " +
                    "JOIN retailmind.dim_producto p ON fe.product_id = p.producto_id " +
                    "JOIN retailmind.dim_categoria dc ON p.categoria_id = dc.categoria_id " +
                    "WHERE fe.user_id = '" + username + "' " +
                    "GROUP BY p.categoria_id, dc.categoria_nombre " +
                    "ORDER BY count() DESC LIMIT 1",
                    (rs, rn) -> rs.getString("categoria_nombre"));
            return result.isEmpty() ? null : result.get(0);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String buildExclusion(List<String> ids) {
        if (ids.isEmpty()) return "";
        return ids.stream().map(id -> "'" + id + "'").collect(Collectors.joining(","));
    }

    private Map<String, Object> mapProductoCompleto(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("productoId",      rs.getString("producto_id"));
        r.put("nombre",          rs.getString("nombre"));
        r.put("brand",           rs.getString("brand"));
        r.put("price",           rs.getFloat("price"));
        r.put("categoriaId",     rs.getInt("categoria_id"));
        r.put("imagenUrl",       rs.getString("imagen_url"));
        r.put("descripcion",     rs.getString("descripcion"));
        r.put("stock",           rs.getInt("stock"));
        r.put("categoriaNombre", rs.getString("categoria_nombre"));
        r.put("totalCompras",    rs.getLong("total_compras"));
        return r;
    }

    @SuppressWarnings("null")
    private Long queryLong(String sql) {
        try {
            Long val = ch.queryForObject(sql, Long.class);
            return val != null ? val : 0L;
        } catch (RuntimeException e) {
            return 0L;
        }
    }
}
