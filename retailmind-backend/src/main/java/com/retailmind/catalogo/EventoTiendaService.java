package com.retailmind.catalogo;

import java.time.LocalDateTime;
import java.time.temporal.WeekFields;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Registro de eventos de la tienda hacia ClickHouse (fact_eventos), SOLO para
 * analítica/recomendaciones. Es best-effort: si ClickHouse está apagado se
 * registra un WARN y la tienda sigue funcionando (nunca propaga el error).
 */
@Service
public class EventoTiendaService {

    private static final Logger logger = LoggerFactory.getLogger(EventoTiendaService.class);

    private final JdbcTemplate ch;

    public EventoTiendaService(@Qualifier("clickHouseJdbc") JdbcTemplate ch) {
        this.ch = ch;
    }

    public void registrar(String userId, String productId, String userAction,
                          String channel, Double price, String sessionId) {
        try {
            String sid = (sessionId == null || sessionId.isEmpty())
                    ? "sess_shop_" + UUID.randomUUID().toString().substring(0, 8)
                    : sessionId;
            int semana = LocalDateTime.now().get(WeekFields.ISO.weekOfYear());
            int isConversion = "purchase".equals(userAction) ? 1 : 0;
            int dropOff = "drop".equals(userAction) ? 1 : 0;

            ch.update("""
                    INSERT INTO retailmind.fact_eventos
                        (session_id, user_id, timestamp_utc, event_index, user_action, product_id,
                         time_spent_sec, session_length, interaction_count, is_conversion,
                         drop_off_flag, price, channel, semana)
                    VALUES (?, ?, ?, 1, ?, ?, 0, 0, 1, ?, ?, ?, ?, ?)""",
                    sid,
                    userId != null ? userId : "anonymous",
                    LocalDateTime.now().toString(),
                    userAction,
                    productId != null ? productId : "",
                    isConversion, dropOff,
                    price != null ? price : 0d,
                    channel != null ? channel : "web",
                    semana);
        } catch (RuntimeException e) {
            logger.warn("Evento de tienda no registrado (ClickHouse no disponible): {}", e.getMessage());
        }
    }
}
