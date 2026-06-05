package com.retailmind.analytics.dispositivo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DispositivoService {

    private static final Logger logger = LoggerFactory.getLogger(DispositivoService.class);
    private final JdbcTemplate ch;

    public DispositivoService(@Qualifier("clickHouseJdbc") JdbcTemplate ch) {
        this.ch = ch;
    }

    public List<Map<String, Object>> getResumen(Integer semana, String canal) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (semana != null) where.append(" AND fe.semana = ").append(semana);
        if (canal != null && !canal.isBlank()) where.append(" AND fe.channel = '").append(canal).append("'");

        String sql =
            "SELECT d.dispositivo_nombre, " +
            "count() AS total_eventos, " +
            "uniqExact(fe.session_id) AS total_sesiones, " +
            "uniqExact(fe.user_id) AS total_usuarios, " +
            "sum(fe.is_conversion) AS total_conversiones, " +
            "sum(fe.drop_off_flag) AS total_abandonos, " +
            "round(sum(fe.is_conversion) / uniqExact(fe.session_id) * 100, 2) AS tasa_conversion, " +
            "round(avg(fe.time_spent_sec), 2) AS tiempo_promedio_sesion, " +
            "round(avg(fe.session_length), 2) AS longitud_promedio_sesion, " +
            "round(avg(fe.interaction_count), 2) AS interacciones_promedio " +
            "FROM retailmind.fact_eventos fe " +
            "JOIN retailmind.dim_usuario du ON fe.user_id = du.user_id " +
            "JOIN retailmind.dim_dispositivo d ON du.dispositivo_id = d.dispositivo_id" +
            where +
            " GROUP BY d.dispositivo_nombre ORDER BY total_sesiones DESC";

        try {
            return ch.query(sql, (rs, rn) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("dispositivoNombre",      rs.getString("dispositivo_nombre"));
                row.put("totalEventos",           rs.getLong("total_eventos"));
                row.put("totalSesiones",          rs.getLong("total_sesiones"));
                row.put("totalUsuarios",          rs.getLong("total_usuarios"));
                row.put("totalConversiones",      rs.getLong("total_conversiones"));
                row.put("totalAbandonos",         rs.getLong("total_abandonos"));
                row.put("tasaConversion",         rs.getDouble("tasa_conversion"));
                row.put("tiempoPromedioSesion",   rs.getDouble("tiempo_promedio_sesion"));
                row.put("longitudPromedioSesion", rs.getDouble("longitud_promedio_sesion"));
                row.put("interaccionesPromedio",  rs.getDouble("interacciones_promedio"));
                return row;
            });
        } catch (Exception e) {
            logger.error("Error en getResumenDispositivo: {}", e.getMessage());
            return List.of();
        }
    }

    public List<Map<String, Object>> getTendencia(Integer semanaInicio, Integer semanaFin) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (semanaInicio != null) where.append(" AND fe.semana >= ").append(semanaInicio);
        if (semanaFin    != null) where.append(" AND fe.semana <= ").append(semanaFin);

        String sql =
            "SELECT fe.semana, d.dispositivo_nombre, " +
            "uniqExact(fe.session_id) AS sesiones, " +
            "sum(fe.is_conversion) AS conversiones " +
            "FROM retailmind.fact_eventos fe " +
            "JOIN retailmind.dim_usuario du ON fe.user_id = du.user_id " +
            "JOIN retailmind.dim_dispositivo d ON du.dispositivo_id = d.dispositivo_id" +
            where +
            " GROUP BY fe.semana, d.dispositivo_nombre ORDER BY fe.semana ASC";

        try {
            return ch.query(sql, (rs, rn) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("semana",             rs.getInt("semana"));
                row.put("dispositivoNombre",  rs.getString("dispositivo_nombre"));
                row.put("sesiones",           rs.getLong("sesiones"));
                row.put("conversiones",       rs.getLong("conversiones"));
                return row;
            });
        } catch (Exception e) {
            logger.error("Error en getTendenciaDispositivo: {}", e.getMessage());
            return List.of();
        }
    }
}
