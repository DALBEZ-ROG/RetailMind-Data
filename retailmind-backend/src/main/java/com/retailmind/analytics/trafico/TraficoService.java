package com.retailmind.analytics.trafico;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class TraficoService {

    private static final Logger logger = LoggerFactory.getLogger(TraficoService.class);
    private final JdbcTemplate ch;

    public TraficoService(@Qualifier("clickHouseJdbc") JdbcTemplate ch) {
        this.ch = ch;
    }

    public List<Map<String, Object>> getResumen(Integer semana) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (semana != null) where.append(" AND semana = ").append(semana);

        String sql =
            "SELECT fe.channel AS fuente, " +
            "count() AS total_eventos, " +
            "uniqExact(fe.session_id) AS total_sesiones, " +
            "uniqExact(fe.user_id) AS total_usuarios, " +
            "sum(fe.is_conversion) AS total_conversiones, " +
            "sum(fe.drop_off_flag) AS total_abandonos, " +
            "round(sum(fe.is_conversion) / uniqExact(fe.session_id) * 100, 2) AS tasa_conversion, " +
            "round(sum(fe.price * fe.is_conversion), 2) AS revenue_total, " +
            "round(avg(fe.time_spent_sec), 2) AS tiempo_promedio " +
            "FROM retailmind.fact_eventos fe" +
            where +
            " GROUP BY fe.channel ORDER BY total_sesiones DESC";

        try {
            return ch.query(sql, (rs, rn) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("fuente",            rs.getString("fuente"));
                row.put("totalEventos",      rs.getLong("total_eventos"));
                row.put("totalSesiones",     rs.getLong("total_sesiones"));
                row.put("totalUsuarios",     rs.getLong("total_usuarios"));
                row.put("totalConversiones", rs.getLong("total_conversiones"));
                row.put("totalAbandonos",    rs.getLong("total_abandonos"));
                row.put("tasaConversion",    rs.getDouble("tasa_conversion"));
                row.put("revenueTotal",      rs.getDouble("revenue_total"));
                row.put("tiempoPromedio",    rs.getDouble("tiempo_promedio"));
                return row;
            });
        } catch (Exception e) {
            logger.error("Error en getResumenTrafico: {}", e.getMessage());
            return List.of();
        }
    }

    public List<Map<String, Object>> getEmbudoPorCanal(Integer semana) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (semana != null) where.append(" AND semana = ").append(semana);

        String sql =
            "SELECT channel, " +
            "uniqExactIf(session_id, user_action = 'view')        AS vistas, " +
            "uniqExactIf(session_id, user_action = 'click')       AS clicks, " +
            "uniqExactIf(session_id, user_action = 'add_to_cart') AS carritos, " +
            "uniqExactIf(session_id, user_action = 'purchase')    AS compras, " +
            "uniqExactIf(session_id, user_action = 'drop')        AS abandonos " +
            "FROM retailmind.fact_eventos" +
            where +
            " GROUP BY channel ORDER BY vistas DESC";

        try {
            return ch.query(sql, (rs, rn) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("canal",     rs.getString("channel"));
                row.put("vistas",    rs.getLong("vistas"));
                row.put("clicks",    rs.getLong("clicks"));
                row.put("carritos",  rs.getLong("carritos"));
                row.put("compras",   rs.getLong("compras"));
                row.put("abandonos", rs.getLong("abandonos"));
                return row;
            });
        } catch (Exception e) {
            logger.error("Error en getEmbudoPorCanal: {}", e.getMessage());
            return List.of();
        }
    }
}
