package com.retailmind.analytics.funnel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class FunnelService {

    private final JdbcTemplate ch;

    public FunnelService(@Qualifier("clickHouseJdbc") JdbcTemplate ch) {
        this.ch = ch;
    }

    public Map<String, Object> getSesiones(Integer semana, String canal, int page, int size) {
        try {
            String whereClause = buildWhere(semana, canal);

            Long total = ch.queryForObject(
                    "SELECT uniqExact(session_id) FROM retailmind.fact_eventos" + whereClause, Long.class);

            if (total == null || total == 0) {
                return Map.of("content", List.of(), "totalElements", 0L, "totalPages", 0, "number", page, "size", size);
            }

            List<Map<String, Object>> rows = ch.query(
                    "SELECT session_id, user_id, " +
                    "any(channel) as canal, any(semana) as sem, " +
                    "maxIf(1, user_action='view') as hizo_view, " +
                    "maxIf(1, user_action='click') as hizo_click, " +
                    "maxIf(1, user_action='add_to_cart') as hizo_add_to_cart, " +
                    "maxIf(1, user_action='wishlist') as hizo_wishlist, " +
                    "maxIf(1, user_action='purchase') as hizo_purchase, " +
                    "maxIf(1, user_action='drop') as hizo_drop, " +
                    "count() as total_eventos, " +
                    "max(is_conversion) as convirtio " +
                    "FROM retailmind.fact_eventos" + whereClause +
                    " GROUP BY session_id, user_id ORDER BY session_id " +
                    "LIMIT " + size + " OFFSET " + (page * size),
                    (rs, rn) -> {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("sessionId", rs.getString("session_id"));
                        r.put("userId", rs.getString("user_id"));
                        r.put("canal", rs.getString("canal"));
                        r.put("semana", rs.getInt("sem"));
                        int view = rs.getInt("hizo_view");
                        int click = rs.getInt("hizo_click");
                        int cart = rs.getInt("hizo_add_to_cart");
                        int wish = rs.getInt("hizo_wishlist");
                        int purchase = rs.getInt("hizo_purchase");
                        int drop = rs.getInt("hizo_drop");
                        r.put("hizoView", view);
                        r.put("hizoClick", click);
                        r.put("hizoAddToCart", cart);
                        r.put("hizoWishlist", wish);
                        r.put("hizoPurchase", purchase);
                        r.put("hizoDrop", drop);
                        r.put("totalEventos", rs.getInt("total_eventos"));
                        r.put("convirtio", rs.getInt("convirtio"));

                        String etapa;
                        if (purchase == 1) etapa = "COMPRA";
                        else if (drop == 1) etapa = "ABANDONO";
                        else if (cart == 1) etapa = "CARRITO";
                        else if (wish == 1) etapa = "WISHLIST";
                        else if (click == 1) etapa = "CLICK";
                        else etapa = "SOLO VISTA";
                        r.put("etapaFinal", etapa);
                        return r;
                    });

            return Map.of("content", rows, "totalElements", total,
                    "totalPages", (int) Math.ceil((double) total / size), "number", page, "size", size);
        } catch (Exception e) {
            return Map.of("content", List.of(), "totalElements", 0L, "totalPages", 0, "number", page, "size", size);
        }
    }

    public Map<String, Object> getResumen(Integer semana, String canal) {
        try {
            String whereClause = buildWhere(semana, canal);

            List<Map<String, Object>> rows = ch.query(
                    "SELECT " +
                    "uniqExact(session_id) as total_sesiones, " +
                    "uniqExactIf(session_id, user_action='view') as llegaron_view, " +
                    "uniqExactIf(session_id, user_action='click') as llegaron_click, " +
                    "uniqExactIf(session_id, user_action='add_to_cart') as llegaron_add_to_cart, " +
                    "uniqExactIf(session_id, user_action='wishlist') as llegaron_wishlist, " +
                    "uniqExactIf(session_id, user_action='purchase') as llegaron_purchase, " +
                    "uniqExactIf(session_id, user_action='drop') as llegaron_drop " +
                    "FROM retailmind.fact_eventos" + whereClause,
                    (rs, rn) -> {
                        Map<String, Object> r = new LinkedHashMap<>();
                        long total = rs.getLong("total_sesiones");
                        r.put("totalSesiones", total);
                        r.put("llegaronView", rs.getLong("llegaron_view"));
                        r.put("llegaronClick", rs.getLong("llegaron_click"));
                        r.put("llegaronAddToCart", rs.getLong("llegaron_add_to_cart"));
                        r.put("llegaronWishlist", rs.getLong("llegaron_wishlist"));
                        r.put("llegaronPurchase", rs.getLong("llegaron_purchase"));
                        r.put("llegaronDrop", rs.getLong("llegaron_drop"));
                        r.put("tasaConversion", total > 0 ? Math.round(rs.getLong("llegaron_purchase") * 10000.0 / total) / 100.0 : 0);
                        r.put("tasaAbandono", total > 0 ? Math.round(rs.getLong("llegaron_drop") * 10000.0 / total) / 100.0 : 0);
                        return r;
                    });
            return rows.isEmpty() ? Map.of("totalSesiones", 0L) : rows.get(0);
        } catch (Exception e) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("totalSesiones", 0L);
            return empty;
        }
    }

    public List<Integer> getSemanasDisponibles() {
        try {
            return ch.query(
                    "SELECT DISTINCT semana FROM retailmind.fact_eventos ORDER BY semana ASC",
                    (rs, rn) -> rs.getInt("semana"));
        } catch (Exception e) {
            return List.of();
        }
    }

    public List<Map<String, Object>> getDetalleSesion(String sessionId) {
        return ch.query(
                "SELECT timestamp_utc, user_action, product_id, price, time_spent_sec, event_index " +
                "FROM retailmind.fact_eventos WHERE session_id = '" + sessionId + "' " +
                "ORDER BY event_index ASC",
                (rs, rn) -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("timestamp", rs.getString("timestamp_utc"));
                    r.put("userAction", rs.getString("user_action"));
                    r.put("productId", rs.getString("product_id"));
                    r.put("price", rs.getFloat("price"));
                    r.put("timeSpentSec", rs.getFloat("time_spent_sec"));
                    r.put("eventIndex", rs.getInt("event_index"));
                    return r;
                });
    }

    /**
     * Construye la cláusula WHERE con cast explícito para semana (UInt8).
     */
    private String buildWhere(Integer semana, String canal) {
        StringBuilder conditions = new StringBuilder();
        if (semana != null && semana > 0) {
            conditions.append(" AND semana = toUInt8(").append(semana).append(")");
        }
        if (canal != null && !canal.isEmpty() && !"null".equals(canal)) {
            conditions.append(" AND channel = '").append(canal).append("'");
        }
        if (conditions.length() > 0) {
            return " WHERE " + conditions.substring(5); // quita el primer " AND "
        }
        return "";
    }
}
