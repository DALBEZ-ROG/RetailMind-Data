package com.retailmind.dto;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Repositorio para consultas sobre fact_eventos en ClickHouse.
 * Usa JdbcTemplate dedicado (no JPA, ya que ClickHouse no soporta Hibernate).
 */
@Repository
public class FactEventoRepository {

    private final JdbcTemplate clickHouseJdbc;

    public FactEventoRepository(@Lazy @Qualifier("clickHouseJdbc") JdbcTemplate clickHouseJdbc) {
        this.clickHouseJdbc = clickHouseJdbc;
    }

    // ── Conteos para Dashboard ───────────────────────────────────────────────

    public long countTotalEventos() {
        Long result = clickHouseJdbc.queryForObject("SELECT count() FROM fact_eventos", Long.class);
        return result != null ? result : 0L;
    }

    public long countDistinctSesiones() {
        Long result = clickHouseJdbc.queryForObject(
                "SELECT uniqExact(session_id) FROM fact_eventos", Long.class);
        return result != null ? result : 0L;
    }

    public long countDistinctUsuarios() {
        Long result = clickHouseJdbc.queryForObject(
                "SELECT uniqExact(user_id) FROM fact_eventos", Long.class);
        return result != null ? result : 0L;
    }

    public long countConversiones() {
        Long result = clickHouseJdbc.queryForObject(
                "SELECT count() FROM fact_eventos WHERE is_conversion = 1", Long.class);
        return result != null ? result : 0L;
    }

    public long countAbandonos() {
        Long result = clickHouseJdbc.queryForObject(
                "SELECT count() FROM fact_eventos WHERE drop_off_flag = 1", Long.class);
        return result != null ? result : 0L;
    }

    public int countDistinctSemanas() {
        Integer result = clickHouseJdbc.queryForObject(
                "SELECT uniqExact(semana) FROM fact_eventos", Integer.class);
        return result != null ? result : 0;
    }

    // ── Agrupaciones ─────────────────────────────────────────────────────────

    public List<GrupoConteoDTO> countSesionesPorCanal() {
        return clickHouseJdbc.query(
                "SELECT channel, uniqExact(session_id) AS total " +
                "FROM fact_eventos " +
                "WHERE channel != '' " +
                "GROUP BY channel " +
                "ORDER BY total DESC",
                (rs, rowNum) -> new GrupoConteoDTO(
                        rs.getString("channel"),
                        rs.getLong("total"))
        );
    }

    public List<GrupoConteoDTO> countSesionesPorRegion() {
        return clickHouseJdbc.query(
                "SELECT r.region_nombre AS region, uniqExact(f.session_id) AS total " +
                "FROM fact_eventos f " +
                "INNER JOIN dim_usuario u ON f.user_id = u.user_id " +
                "INNER JOIN dim_region r ON u.region_id = r.region_id " +
                "GROUP BY r.region_nombre " +
                "ORDER BY total DESC",
                (rs, rowNum) -> new GrupoConteoDTO(
                        rs.getString("region"),
                        rs.getLong("total"))
        );
    }

    public List<GrupoConteoDTO> countSesionesPorDispositivo() {
        return clickHouseJdbc.query(
                "SELECT d.dispositivo_nombre AS dispositivo, uniqExact(f.session_id) AS total " +
                "FROM fact_eventos f " +
                "INNER JOIN dim_usuario u ON f.user_id = u.user_id " +
                "INNER JOIN dim_dispositivo d ON u.dispositivo_id = d.dispositivo_id " +
                "GROUP BY d.dispositivo_nombre " +
                "ORDER BY total DESC",
                (rs, rowNum) -> new GrupoConteoDTO(
                        rs.getString("dispositivo"),
                        rs.getLong("total"))
        );
    }

    // ── Paginación manual para sesiones ──────────────────────────────────────

    public long countDistinctSessions() {
        Long result = clickHouseJdbc.queryForObject(
                "SELECT uniqExact(session_id) FROM fact_eventos", Long.class);
        return result != null ? result : 0L;
    }

    public List<FactEvento> findSesionesPaginadas(int offset, int limit) {
        return clickHouseJdbc.query(
                "SELECT session_id, user_id, timestamp_utc, " +
                "       max(session_length) AS session_length, " +
                "       max(interaction_count) AS interaction_count, " +
                "       any(channel) AS channel, " +
                "       max(is_conversion) AS is_conversion, " +
                "       max(drop_off_flag) AS drop_off_flag, " +
                "       sum(time_spent_sec) AS time_spent_sec, " +
                "       count() AS event_index, " +
                "       any(user_action) AS user_action, " +
                "       any(product_id) AS product_id, " +
                "       avg(price) AS price, " +
                "       any(semana) AS semana " +
                "FROM fact_eventos " +
                "GROUP BY session_id, user_id, timestamp_utc " +
                "ORDER BY session_id " +
                "LIMIT ? OFFSET ?",
                (rs, rowNum) -> {
                    FactEvento fe = new FactEvento();
                    fe.setSessionId(rs.getString("session_id"));
                    fe.setUserId(rs.getString("user_id"));
                    fe.setTimestampUtc(rs.getString("timestamp_utc"));
                    fe.setSessionLength(rs.getFloat("session_length"));
                    fe.setInteractionCount(rs.getInt("interaction_count"));
                    fe.setChannel(rs.getString("channel"));
                    fe.setIsConversion(rs.getInt("is_conversion"));
                    fe.setDropOffFlag(rs.getInt("drop_off_flag"));
                    fe.setTimeSpentSec(rs.getFloat("time_spent_sec"));
                    fe.setEventIndex(rs.getInt("event_index"));
                    fe.setUserAction(rs.getString("user_action"));
                    fe.setProductId(rs.getString("product_id"));
                    fe.setPrice(rs.getFloat("price"));
                    fe.setSemana(rs.getInt("semana"));
                    return fe;
                },
                limit, offset
        );
    }

    public List<FactEvento> findSesionesByUsuario(String userId, int offset, int limit) {
        return clickHouseJdbc.query(
                "SELECT session_id, user_id, timestamp_utc, " +
                "       max(session_length) AS session_length, " +
                "       max(interaction_count) AS interaction_count, " +
                "       any(channel) AS channel, " +
                "       max(is_conversion) AS is_conversion, " +
                "       max(drop_off_flag) AS drop_off_flag, " +
                "       sum(time_spent_sec) AS time_spent_sec, " +
                "       count() AS event_index, " +
                "       any(user_action) AS user_action, " +
                "       any(product_id) AS product_id, " +
                "       avg(price) AS price, " +
                "       any(semana) AS semana " +
                "FROM fact_eventos " +
                "WHERE user_id = ? " +
                "GROUP BY session_id, user_id, timestamp_utc " +
                "ORDER BY timestamp_utc DESC " +
                "LIMIT ? OFFSET ?",
                (rs, rowNum) -> {
                    FactEvento fe = new FactEvento();
                    fe.setSessionId(rs.getString("session_id"));
                    fe.setUserId(rs.getString("user_id"));
                    fe.setTimestampUtc(rs.getString("timestamp_utc"));
                    fe.setSessionLength(rs.getFloat("session_length"));
                    fe.setInteractionCount(rs.getInt("interaction_count"));
                    fe.setChannel(rs.getString("channel"));
                    fe.setIsConversion(rs.getInt("is_conversion"));
                    fe.setDropOffFlag(rs.getInt("drop_off_flag"));
                    fe.setTimeSpentSec(rs.getFloat("time_spent_sec"));
                    fe.setEventIndex(rs.getInt("event_index"));
                    fe.setUserAction(rs.getString("user_action"));
                    fe.setProductId(rs.getString("product_id"));
                    fe.setPrice(rs.getFloat("price"));
                    fe.setSemana(rs.getInt("semana"));
                    return fe;
                },
                userId, limit, offset
        );
    }

    public long countSessionsByUsuario(String userId) {
        Long result = clickHouseJdbc.queryForObject(
                "SELECT uniqExact(session_id) FROM fact_eventos WHERE user_id = ?",
                Long.class, userId);
        return result != null ? result : 0L;
    }

    public List<FactEvento> findSesionesByCanal(String channel, int offset, int limit) {
        return clickHouseJdbc.query(
                "SELECT session_id, user_id, timestamp_utc, " +
                "       max(session_length) AS session_length, " +
                "       max(interaction_count) AS interaction_count, " +
                "       any(channel) AS channel, " +
                "       max(is_conversion) AS is_conversion, " +
                "       max(drop_off_flag) AS drop_off_flag, " +
                "       sum(time_spent_sec) AS time_spent_sec, " +
                "       count() AS event_index, " +
                "       any(user_action) AS user_action, " +
                "       any(product_id) AS product_id, " +
                "       avg(price) AS price, " +
                "       any(semana) AS semana " +
                "FROM fact_eventos " +
                "WHERE channel = ? " +
                "GROUP BY session_id, user_id, timestamp_utc " +
                "ORDER BY timestamp_utc DESC " +
                "LIMIT ? OFFSET ?",
                (rs, rowNum) -> {
                    FactEvento fe = new FactEvento();
                    fe.setSessionId(rs.getString("session_id"));
                    fe.setUserId(rs.getString("user_id"));
                    fe.setTimestampUtc(rs.getString("timestamp_utc"));
                    fe.setSessionLength(rs.getFloat("session_length"));
                    fe.setInteractionCount(rs.getInt("interaction_count"));
                    fe.setChannel(rs.getString("channel"));
                    fe.setIsConversion(rs.getInt("is_conversion"));
                    fe.setDropOffFlag(rs.getInt("drop_off_flag"));
                    fe.setTimeSpentSec(rs.getFloat("time_spent_sec"));
                    fe.setEventIndex(rs.getInt("event_index"));
                    fe.setUserAction(rs.getString("user_action"));
                    fe.setProductId(rs.getString("product_id"));
                    fe.setPrice(rs.getFloat("price"));
                    fe.setSemana(rs.getInt("semana"));
                    return fe;
                },
                channel, limit, offset
        );
    }

    public long countSessionsByCanal(String channel) {
        Long result = clickHouseJdbc.queryForObject(
                "SELECT uniqExact(session_id) FROM fact_eventos WHERE channel = ?",
                Long.class, channel);
        return result != null ? result : 0L;
    }
}
