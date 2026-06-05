package com.retailmind.analytics.region;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RegionService {

    private static final Logger logger = LoggerFactory.getLogger(RegionService.class);
    private final JdbcTemplate ch;

    public RegionService(@Qualifier("clickHouseJdbc") JdbcTemplate ch) {
        this.ch = ch;
    }

    public List<Map<String, Object>> getResumen(Integer semana, String canal) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (semana != null) where.append(" AND fe.semana = ").append(semana);
        if (canal != null && !canal.isBlank()) where.append(" AND fe.channel = '").append(canal).append("'");

        String sql =
            "SELECT r.region_nombre, " +
            "count() AS total_eventos, " +
            "uniqExact(fe.session_id) AS total_sesiones, " +
            "uniqExact(fe.user_id) AS total_usuarios, " +
            "sum(fe.is_conversion) AS total_conversiones, " +
            "sum(fe.drop_off_flag) AS total_abandonos, " +
            "round(sum(fe.is_conversion) / uniqExact(fe.session_id) * 100, 2) AS tasa_conversion, " +
            "round(avg(fe.price), 2) AS precio_promedio, " +
            "round(sum(fe.price * fe.is_conversion), 2) AS revenue_total " +
            "FROM retailmind.fact_eventos fe " +
            "JOIN retailmind.dim_usuario du ON fe.user_id = du.user_id " +
            "JOIN retailmind.dim_region r ON du.region_id = r.region_id" +
            where +
            " GROUP BY r.region_nombre ORDER BY total_sesiones DESC";

        try {
            return ch.query(sql, (rs, rn) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("regionNombre",     rs.getString("region_nombre"));
                row.put("totalEventos",     rs.getLong("total_eventos"));
                row.put("totalSesiones",    rs.getLong("total_sesiones"));
                row.put("totalUsuarios",    rs.getLong("total_usuarios"));
                row.put("totalConversiones",rs.getLong("total_conversiones"));
                row.put("totalAbandonos",   rs.getLong("total_abandonos"));
                row.put("tasaConversion",   rs.getDouble("tasa_conversion"));
                row.put("precioPromedio",   rs.getDouble("precio_promedio"));
                row.put("revenueTotal",     rs.getDouble("revenue_total"));
                return row;
            });
        } catch (Exception e) {
            logger.error("Error en getResumenRegion: {}", e.getMessage());
            return List.of();
        }
    }

    public List<Map<String, Object>> getTopProductos(String regionNombre) {
        String sql =
            "SELECT fe.product_id, pc.nombre, pc.brand, count() AS compras " +
            "FROM retailmind.fact_eventos fe " +
            "JOIN retailmind.dim_usuario du ON fe.user_id = du.user_id " +
            "JOIN retailmind.dim_region r ON du.region_id = r.region_id " +
            "JOIN retailmind.productos_catalogo pc ON fe.product_id = pc.producto_id " +
            "WHERE r.region_nombre = '" + regionNombre.replace("'", "''") + "' " +
            "AND fe.user_action = 'purchase' " +
            "GROUP BY fe.product_id, pc.nombre, pc.brand " +
            "ORDER BY compras DESC LIMIT 5";

        try {
            return ch.query(sql, (rs, rn) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("productoId", rs.getString("product_id"));
                row.put("nombre",     rs.getString("nombre"));
                row.put("brand",      rs.getString("brand"));
                row.put("compras",    rs.getLong("compras"));
                return row;
            });
        } catch (Exception e) {
            logger.error("Error en getTopProductosRegion: {}", e.getMessage());
            return List.of();
        }
    }
}
