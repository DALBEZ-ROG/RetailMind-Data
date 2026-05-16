package com.retailmind.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class GestionDatosService {

    private static final Logger logger = LoggerFactory.getLogger(GestionDatosService.class);
    private final JdbcTemplate ch;

    public GestionDatosService(@Lazy @Qualifier("clickHouseJdbc") JdbcTemplate ch) {
        this.ch = ch;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // FACT_EVENTOS
    // ══════════════════════════════════════════════════════════════════════════

    public Map<String, Object> getFactEventos(int page, int size, Integer semana) {
        String where = (semana != null) ? " WHERE semana = " + semana : "";
        Long total = ch.queryForObject("SELECT count() FROM retailmind.fact_eventos" + where, Long.class);
        List<Map<String, Object>> rows = ch.query(
                "SELECT event_pk, session_id, user_id, timestamp_utc, event_index, " +
                "user_action, product_id, time_spent_sec, session_length, interaction_count, " +
                "is_conversion, drop_off_flag, price, channel, semana " +
                "FROM retailmind.fact_eventos" + where +
                " ORDER BY event_pk LIMIT " + size + " OFFSET " + (page * size),
                (rs, rn) -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("eventPk", rs.getLong("event_pk"));
                    r.put("sessionId", rs.getString("session_id"));
                    r.put("userId", rs.getString("user_id"));
                    r.put("timestampUtc", rs.getString("timestamp_utc"));
                    r.put("eventIndex", rs.getInt("event_index"));
                    r.put("userAction", rs.getString("user_action"));
                    r.put("productId", rs.getString("product_id"));
                    r.put("timeSpentSec", rs.getFloat("time_spent_sec"));
                    r.put("sessionLength", rs.getFloat("session_length"));
                    r.put("interactionCount", rs.getInt("interaction_count"));
                    r.put("isConversion", rs.getInt("is_conversion"));
                    r.put("dropOffFlag", rs.getInt("drop_off_flag"));
                    r.put("price", rs.getFloat("price"));
                    r.put("channel", rs.getString("channel"));
                    r.put("semana", rs.getInt("semana"));
                    return r;
                });
        long t = total != null ? total : 0L;
        return Map.of("content", rows, "totalElements", t,
                "totalPages", (int) Math.ceil((double) t / size), "number", page, "size", size);
    }

    public Map<String, Object> getFactEventoById(long eventPk) {
        List<Map<String, Object>> rows = ch.query(
                "SELECT * FROM retailmind.fact_eventos WHERE event_pk = " + eventPk,
                (rs, rn) -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("eventPk", rs.getLong("event_pk"));
                    r.put("sessionId", rs.getString("session_id"));
                    r.put("userId", rs.getString("user_id"));
                    r.put("timestampUtc", rs.getString("timestamp_utc"));
                    r.put("eventIndex", rs.getInt("event_index"));
                    r.put("userAction", rs.getString("user_action"));
                    r.put("productId", rs.getString("product_id"));
                    r.put("timeSpentSec", rs.getFloat("time_spent_sec"));
                    r.put("sessionLength", rs.getFloat("session_length"));
                    r.put("interactionCount", rs.getInt("interaction_count"));
                    r.put("isConversion", rs.getInt("is_conversion"));
                    r.put("dropOffFlag", rs.getInt("drop_off_flag"));
                    r.put("price", rs.getFloat("price"));
                    r.put("channel", rs.getString("channel"));
                    r.put("semana", rs.getInt("semana"));
                    return r;
                });
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void updateFactEvento(long eventPk, Map<String, Object> fields) {
        StringBuilder sb = new StringBuilder("ALTER TABLE retailmind.fact_eventos UPDATE ");
        List<String> sets = new ArrayList<>();
        fields.forEach((k, v) -> {
            if (v instanceof String) sets.add(k + " = '" + v + "'");
            else sets.add(k + " = " + v);
        });
        sb.append(String.join(", ", sets));
        sb.append(" WHERE event_pk = ").append(eventPk);
        sb.append(" SETTINGS mutations_sync = 1");
        ch.execute(sb.toString());
    }

    public void deleteFactEvento(long eventPk) {
        ch.execute("ALTER TABLE retailmind.fact_eventos DELETE WHERE event_pk = " + eventPk +
                " SETTINGS mutations_sync = 1");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DIMENSIONES GENÉRICAS
    // ══════════════════════════════════════════════════════════════════════════

    public List<Map<String, Object>> getDimension(String table, String idCol, String nameCol) {
        return ch.query("SELECT " + idCol + ", " + nameCol + " FROM retailmind." + table + " ORDER BY " + idCol,
                (rs, rn) -> Map.of("id", rs.getLong(idCol), "nombre", rs.getString(nameCol)));
    }

    public void insertDimension(String table, String idCol, String nameCol, long id, String nombre) {
        ch.execute("INSERT INTO retailmind." + table + " (" + idCol + ", " + nameCol + ") VALUES (" +
                id + ", '" + nombre + "')");
    }

    public void updateDimension(String table, String idCol, String nameCol, long id, String nombre) {
        ch.execute("ALTER TABLE retailmind." + table + " UPDATE " + nameCol + " = '" + nombre +
                "' WHERE " + idCol + " = " + id + " SETTINGS mutations_sync = 1");
    }

    public void deleteDimension(String table, String idCol, long id) {
        ch.execute("ALTER TABLE retailmind." + table + " DELETE WHERE " + idCol + " = " + id +
                " SETTINGS mutations_sync = 1");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DIM_PRODUCTO
    // ══════════════════════════════════════════════════════════════════════════

    public Map<String, Object> getProductos(int page, int size) {
        Long total = ch.queryForObject("SELECT count() FROM retailmind.dim_producto", Long.class);
        List<Map<String, Object>> rows = ch.query(
                "SELECT producto_id, categoria_id, brand, price FROM retailmind.dim_producto " +
                "ORDER BY producto_id LIMIT " + size + " OFFSET " + (page * size),
                (rs, rn) -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("productoId", rs.getString("producto_id"));
                    r.put("categoriaId", rs.getInt("categoria_id"));
                    r.put("brand", rs.getString("brand"));
                    r.put("price", rs.getFloat("price"));
                    return r;
                });
        long t = total != null ? total : 0L;
        return Map.of("content", rows, "totalElements", t,
                "totalPages", (int) Math.ceil((double) t / size), "number", page, "size", size);
    }

    public void insertProducto(String productoId, int categoriaId, String brand, float price) {
        ch.execute("INSERT INTO retailmind.dim_producto (producto_id, categoria_id, brand, price) VALUES ('" +
                productoId + "', " + categoriaId + ", '" + brand + "', " + price + ")");
    }

    public void updateProducto(String productoId, String brand, float price) {
        ch.execute("ALTER TABLE retailmind.dim_producto UPDATE brand = '" + brand + "', price = " + price +
                " WHERE producto_id = '" + productoId + "' SETTINGS mutations_sync = 1");
    }

    public void deleteProducto(String productoId) {
        ch.execute("ALTER TABLE retailmind.dim_producto DELETE WHERE producto_id = '" + productoId +
                "' SETTINGS mutations_sync = 1");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DIM_USUARIO
    // ══════════════════════════════════════════════════════════════════════════

    public Map<String, Object> getUsuarios(int page, int size) {
        Long total = ch.queryForObject("SELECT count() FROM retailmind.dim_usuario", Long.class);
        List<Map<String, Object>> rows = ch.query(
                "SELECT user_id, region_id, dispositivo_id FROM retailmind.dim_usuario " +
                "ORDER BY user_id LIMIT " + size + " OFFSET " + (page * size),
                (rs, rn) -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("userId", rs.getString("user_id"));
                    r.put("regionId", rs.getInt("region_id"));
                    r.put("dispositivoId", rs.getInt("dispositivo_id"));
                    return r;
                });
        long t = total != null ? total : 0L;
        return Map.of("content", rows, "totalElements", t,
                "totalPages", (int) Math.ceil((double) t / size), "number", page, "size", size);
    }

    public void deleteUsuario(String userId) {
        ch.execute("ALTER TABLE retailmind.dim_usuario DELETE WHERE user_id = '" + userId +
                "' SETTINGS mutations_sync = 1");
    }
}
