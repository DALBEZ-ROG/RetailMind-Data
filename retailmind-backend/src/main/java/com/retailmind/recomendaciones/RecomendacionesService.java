package com.retailmind.recomendaciones;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Motor de recomendaciones híbrido:
 *
 * - La SEÑAL sigue viniendo de ClickHouse (fact_eventos): qué productos vio
 *   el usuario. El product_id de los eventos corresponde al slug del
 *   producto en PostgreSQL (p####, convención de la carga del catálogo).
 * - Los PRODUCTOS recomendados salen SIEMPRE de PostgreSQL (catálogo real,
 *   con precio y stock vigentes), en las categorías favoritas del usuario.
 * - DEGRADACIÓN: si ClickHouse está apagado o hay pocos eventos, se devuelven
 *   productos destacados de PostgreSQL con un aviso; la tienda nunca se rompe.
 *
 * "Similares" es lógica de catálogo puro (misma categoría, precio ±30%) y
 * corre 100% en PostgreSQL.
 */
@Service
public class RecomendacionesService {

    private static final Logger logger = LoggerFactory.getLogger(RecomendacionesService.class);
    private static final int MAX_VISTOS = 300;

    private static final String SELECT_PRODUCTO = """
            SELECT pv.id AS variante_id, pr.nombre,
                   COALESCE(pr.descripcion_corta, pr.descripcion) AS descripcion,
                   COALESCE(cat.categoria_id, 0) AS categoria_id,
                   cat.nombre AS categoria_nombre,
                   m.nombre AS brand, pv.precio AS price,
                   (SELECT COALESCE(SUM(stock_actual), 0) FROM inventario i
                    WHERE i.producto_variante_id = pv.id) AS stock
            FROM producto pr
            JOIN LATERAL (SELECT v.id, v.precio FROM producto_variante v
                          WHERE v.producto_id = pr.id AND v.activo
                          ORDER BY v.es_predeterminada DESC, v.id LIMIT 1) pv ON true
            LEFT JOIN marca m ON m.id = pr.marca_id
            LEFT JOIN LATERAL (SELECT pc.categoria_id, c.nombre
                               FROM producto_categoria pc
                               JOIN categoria c ON c.id = pc.categoria_id
                               WHERE pc.producto_id = pr.id
                               ORDER BY pc.es_principal DESC, pc.id LIMIT 1) cat ON true
            WHERE pr.publicado AND pr.activo
            """;

    private final JdbcTemplate ch;
    private final JdbcTemplate pg;

    public RecomendacionesService(@Qualifier("clickHouseJdbc") JdbcTemplate ch,
                                  @Qualifier("pgJdbcTemplate") JdbcTemplate pg) {
        this.ch = ch;
        this.pg = pg;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getRecomendaciones(String username) {
        long inicio = System.currentTimeMillis();

        boolean analyticsDisponible = true;
        long totalEventos = 0;
        List<String> slugsVistos = List.of();
        try {
            Long total = ch.queryForObject(
                    "SELECT count() FROM retailmind.fact_eventos WHERE user_id = ?",
                    Long.class, username);
            totalEventos = total != null ? total : 0L;
            if (totalEventos >= 10) {
                slugsVistos = ch.query(
                        "SELECT DISTINCT lower(product_id) FROM retailmind.fact_eventos " +
                        "WHERE user_id = ? AND product_id != '' LIMIT " + MAX_VISTOS,
                        (rs, rn) -> rs.getString(1), username);
            }
        } catch (RuntimeException e) {
            analyticsDisponible = false;
            logger.warn("ClickHouse no disponible para recomendaciones: {}", e.getMessage());
        }

        Map<String, Object> resultado = new LinkedHashMap<>();
        resultado.put("username", username);
        resultado.put("totalEventos", totalEventos);
        resultado.put("analyticsDisponible", analyticsDisponible);

        List<Map<String, Object>> recomendados = List.of();
        String categoriaFavorita = null;

        if (analyticsDisponible && !slugsVistos.isEmpty()) {
            List<Map<String, Object>> topCategorias = topCategorias(slugsVistos);
            if (!topCategorias.isEmpty()) {
                categoriaFavorita = (String) topCategorias.get(0).get("nombre");
                List<Long> categoriaIds = topCategorias.stream()
                        .map(c -> ((Number) c.get("id")).longValue()).toList();
                recomendados = productosPorCategorias(categoriaIds, slugsVistos);
            }
        }

        boolean personalizado = !recomendados.isEmpty();
        if (!personalizado) {
            recomendados = productosDestacados();
        }

        resultado.put("recomendaciones", recomendados);
        resultado.put("esPersonalizado", personalizado);
        resultado.put("tipo", personalizado ? "personalizado" : "populares");
        resultado.put("categoriaFavorita", personalizado ? categoriaFavorita : null);
        resultado.put("mensajeFallback", personalizado ? null
                : (!analyticsDisponible
                    ? "La analítica está fuera de línea: te mostramos productos destacados del catálogo"
                    : "Navega más productos para recibir recomendaciones personalizadas"));
        resultado.put("queryMs", System.currentTimeMillis() - inicio);
        return resultado;
    }

    /** Similares por catálogo (misma categoría, precio ±30%). 100% PostgreSQL. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getSimilares(long varianteId) {
        List<Map<String, Object>> base = pg.queryForList("""
                SELECT pv.precio,
                       (SELECT pc.categoria_id FROM producto_categoria pc
                        WHERE pc.producto_id = pv.producto_id
                        ORDER BY pc.es_principal DESC, pc.id LIMIT 1) AS categoria_id
                FROM producto_variante pv WHERE pv.id = ? AND pv.activo""", varianteId);
        if (base.isEmpty() || base.get(0).get("categoria_id") == null) return List.of();

        java.math.BigDecimal precio = (java.math.BigDecimal) base.get(0).get("precio");
        long categoriaId = ((Number) base.get(0).get("categoria_id")).longValue();

        return pg.query(SELECT_PRODUCTO + """
                  AND pv.id <> ?
                  AND EXISTS (SELECT 1 FROM producto_categoria f
                              WHERE f.producto_id = pr.id AND f.categoria_id = ?)
                  AND pv.precio BETWEEN ? * 0.7 AND ? * 1.3
                ORDER BY random() LIMIT 6""",
                this::mapProducto, varianteId, categoriaId, precio, precio);
    }

    // ── PostgreSQL helpers ────────────────────────────────────────────────

    /** Categorías favoritas a partir de los slugs vistos en los eventos. */
    private List<Map<String, Object>> topCategorias(List<String> slugs) {
        String placeholders = String.join(",", java.util.Collections.nCopies(slugs.size(), "?"));
        List<Object> params = new ArrayList<>(slugs);
        return pg.queryForList("""
                SELECT pc.categoria_id AS id, c.nombre, count(*) AS score
                FROM producto pr
                JOIN producto_categoria pc ON pc.producto_id = pr.id
                JOIN categoria c ON c.id = pc.categoria_id
                WHERE pr.slug IN (%s)
                GROUP BY pc.categoria_id, c.nombre
                ORDER BY score DESC LIMIT 3""".formatted(placeholders), params.toArray());
    }

    private List<Map<String, Object>> productosPorCategorias(List<Long> categoriaIds,
                                                             List<String> slugsExcluidos) {
        String catPh = String.join(",", java.util.Collections.nCopies(categoriaIds.size(), "?"));
        String slugPh = String.join(",", java.util.Collections.nCopies(slugsExcluidos.size(), "?"));
        List<Object> params = new ArrayList<>(categoriaIds);
        params.addAll(slugsExcluidos);
        return pg.query(SELECT_PRODUCTO + """
                  AND EXISTS (SELECT 1 FROM producto_categoria f
                              WHERE f.producto_id = pr.id AND f.categoria_id IN (%s))
                  AND pr.slug NOT IN (%s)
                ORDER BY pr.destacado DESC, random() LIMIT 12""".formatted(catPh, slugPh),
                this::mapProducto, params.toArray());
    }

    private List<Map<String, Object>> productosDestacados() {
        return pg.query(SELECT_PRODUCTO +
                " ORDER BY pr.destacado DESC, random() LIMIT 12", this::mapProducto);
    }

    private Map<String, Object> mapProducto(java.sql.ResultSet rs, int rn)
            throws java.sql.SQLException {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("productoId", rs.getLong("variante_id"));
        r.put("nombre", rs.getString("nombre"));
        r.put("brand", rs.getString("brand"));
        r.put("price", rs.getBigDecimal("price"));
        r.put("categoriaId", rs.getInt("categoria_id"));
        r.put("categoriaNombre", rs.getString("categoria_nombre"));
        r.put("descripcion", rs.getString("descripcion"));
        r.put("stock", rs.getLong("stock"));
        r.put("imagenUrl", null);
        r.put("totalCompras", 0L);
        return r;
    }
}
