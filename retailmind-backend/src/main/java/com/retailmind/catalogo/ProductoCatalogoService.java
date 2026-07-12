package com.retailmind.catalogo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Catálogo público de la tienda del cliente, servido desde PostgreSQL
 * (producto/producto_variante/inventario): la MISMA base que administra el
 * back-office. El identificador expuesto como productoId es el id de la
 * VARIANTE (predeterminada), porque carrito_item, wishlist_item y
 * pedido_detalle referencian producto_variante_id.
 *
 * ClickHouse ya no participa aquí; los eventos de navegación se registran
 * best-effort vía EventoTiendaService (analítica).
 */
@Service
public class ProductoCatalogoService {

    /** Joins comunes: variante representativa + marca + categoría + stock total. */
    private static final String FROM_CATALOGO = """
            FROM producto pr
            JOIN LATERAL (SELECT v.id, v.sku, v.precio
                          FROM producto_variante v
                          WHERE v.producto_id = pr.id AND v.activo
                          ORDER BY v.es_predeterminada DESC, v.id
                          LIMIT 1) pv ON true
            LEFT JOIN marca m ON m.id = pr.marca_id
            LEFT JOIN LATERAL (SELECT pc.categoria_id, c.nombre
                               FROM producto_categoria pc
                               JOIN categoria c ON c.id = pc.categoria_id
                               WHERE pc.producto_id = pr.id
                               ORDER BY pc.es_principal DESC, pc.id
                               LIMIT 1) cat ON true
            LEFT JOIN (SELECT producto_variante_id, SUM(stock_actual) AS stock
                       FROM inventario GROUP BY producto_variante_id) inv
                   ON inv.producto_variante_id = pv.id
            """;

    private static final RowMapper<Map<String, Object>> PRODUCTO_MAPPER = (rs, rn) -> {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("productoId", rs.getLong("variante_id"));
        r.put("nombre", rs.getString("nombre"));
        r.put("descripcion", rs.getString("descripcion"));
        r.put("categoriaId", rs.getInt("categoria_id"));
        r.put("categoriaNombre", rs.getString("categoria_nombre"));
        r.put("brand", rs.getString("brand"));
        r.put("price", rs.getBigDecimal("price"));
        r.put("stock", rs.getInt("stock"));
        r.put("sku", rs.getString("sku"));
        r.put("imagenUrl", null);
        return r;
    };

    private final JdbcTemplate pg;

    public ProductoCatalogoService(@Qualifier("pgJdbcTemplate") JdbcTemplate pg) {
        this.pg = pg;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getProductos(Long categoriaId, String brand, String q,
                                            Double minPrice, Double maxPrice,
                                            int page, int size) {
        if (size < 1 || size > 100) size = 20;
        if (page < 0) page = 0;

        StringBuilder where = new StringBuilder(" WHERE pr.publicado AND pr.activo");
        List<Object> params = new ArrayList<>();
        if (categoriaId != null) {
            where.append(" AND EXISTS (SELECT 1 FROM producto_categoria f")
                 .append(" WHERE f.producto_id = pr.id AND f.categoria_id = ?)");
            params.add(categoriaId);
        }
        if (brand != null && !brand.isBlank()) {
            where.append(" AND m.nombre = ?");
            params.add(brand.trim());
        }
        if (q != null && !q.isBlank()) {
            where.append(" AND (pr.nombre ILIKE '%' || ? || '%'")
                 .append(" OR pr.descripcion_corta ILIKE '%' || ? || '%'")
                 .append(" OR pv.sku ILIKE '%' || ? || '%'")
                 .append(" OR m.nombre ILIKE '%' || ? || '%')");
            String term = q.trim();
            params.add(term); params.add(term); params.add(term); params.add(term);
        }
        if (minPrice != null) { where.append(" AND pv.precio >= ?"); params.add(minPrice); }
        if (maxPrice != null) { where.append(" AND pv.precio <= ?"); params.add(maxPrice); }

        Long total = pg.queryForObject(
                "SELECT count(*) " + FROM_CATALOGO + where, Long.class, params.toArray());

        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add(page * size);
        List<Map<String, Object>> rows = pg.query(
                "SELECT pv.id AS variante_id, pr.nombre, " +
                "COALESCE(pr.descripcion_corta, pr.descripcion) AS descripcion, " +
                "COALESCE(cat.categoria_id, 0) AS categoria_id, cat.nombre AS categoria_nombre, " +
                "m.nombre AS brand, pv.precio AS price, COALESCE(inv.stock, 0) AS stock, pv.sku " +
                FROM_CATALOGO + where + " ORDER BY pr.id LIMIT ? OFFSET ?",
                PRODUCTO_MAPPER, pageParams.toArray());

        long t = total != null ? total : 0L;
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("content", rows);
        res.put("totalElements", t);
        res.put("totalPages", (int) Math.ceil((double) t / size));
        res.put("number", page);
        res.put("size", size);
        return res;
    }

    /** Detalle por id de VARIANTE (el id que usan carrito/wishlist/pedido). */
    @Transactional(readOnly = true)
    public Map<String, Object> getProductoById(long varianteId) {
        List<Map<String, Object>> rows = pg.query("""
                SELECT pv.id AS variante_id, pr.nombre,
                       COALESCE(pr.descripcion, pr.descripcion_corta) AS descripcion,
                       COALESCE(cat.categoria_id, 0) AS categoria_id,
                       cat.nombre AS categoria_nombre,
                       m.nombre AS brand, pv.precio AS price,
                       COALESCE(inv.stock, 0) AS stock, pv.sku
                FROM producto_variante pv
                JOIN producto pr ON pr.id = pv.producto_id
                LEFT JOIN marca m ON m.id = pr.marca_id
                LEFT JOIN LATERAL (SELECT pc.categoria_id, c.nombre
                                   FROM producto_categoria pc
                                   JOIN categoria c ON c.id = pc.categoria_id
                                   WHERE pc.producto_id = pr.id
                                   ORDER BY pc.es_principal DESC, pc.id
                                   LIMIT 1) cat ON true
                LEFT JOIN (SELECT producto_variante_id, SUM(stock_actual) AS stock
                           FROM inventario GROUP BY producto_variante_id) inv
                       ON inv.producto_variante_id = pv.id
                WHERE pv.id = ? AND pv.activo AND pr.publicado AND pr.activo""",
                PRODUCTO_MAPPER, varianteId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getCategorias() {
        return pg.query("""
                SELECT c.id, c.nombre, count(DISTINCT pr.id) AS total
                FROM categoria c
                JOIN producto_categoria pc ON pc.categoria_id = c.id
                JOIN producto pr ON pr.id = pc.producto_id AND pr.publicado AND pr.activo
                GROUP BY c.id, c.nombre
                ORDER BY c.id""",
                (rs, rn) -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("categoriaId", rs.getLong("id"));
                    r.put("nombre", rs.getString("nombre"));
                    r.put("total", rs.getLong("total"));
                    return r;
                });
    }

    @Transactional(readOnly = true)
    public List<String> getMarcas() {
        return pg.query("""
                SELECT DISTINCT m.nombre
                FROM marca m
                JOIN producto pr ON pr.marca_id = m.id AND pr.publicado AND pr.activo
                ORDER BY m.nombre""",
                (rs, rn) -> rs.getString("nombre"));
    }
}
