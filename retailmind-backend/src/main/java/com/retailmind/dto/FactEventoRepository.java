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

    /**
     * Los SEIS escalares de la portada del dashboard, en UNA sola pasada.
     *
     * Estaban como seis consultas sueltas ({@code countTotalEventos},
     * {@code countDistinctSesiones}, …), cada una con su viaje a ClickHouse y su
     * recorrido de `fact_eventos` (2.931.837 filas). Medido servidor adentro:
     * 0,002 + 0,036 + 0,026 + 0,011 + 0,007 + 0,005 = <b>87 ms</b> en seis
     * viajes; la misma información en una pasada, <b>55 ms</b> en uno.
     *
     * Los seis valores son idénticos —verificado uno a uno—: `count()` es el
     * mismo, los tres `uniqExact` también, y los dos conteos con WHERE se
     * convierten en `countIf`, que es la misma cuenta sin recorrer aparte.
     */
    public record ResumenEscalares(long eventos, long sesiones, long usuarios,
                                   long conversiones, long abandonos, int semanas) {}

    public ResumenEscalares resumenEscalares() {
        return clickHouseJdbc.queryForObject("""
                SELECT count()                        AS eventos,
                       uniqExact(session_id)          AS sesiones,
                       uniqExact(user_id)             AS usuarios,
                       countIf(is_conversion  = 1)    AS conversiones,
                       countIf(drop_off_flag  = 1)    AS abandonos,
                       uniqExact(semana)              AS semanas
                FROM fact_eventos""",
                (rs, n) -> new ResumenEscalares(
                        rs.getLong("eventos"), rs.getLong("sesiones"), rs.getLong("usuarios"),
                        rs.getLong("conversiones"), rs.getLong("abandonos"),
                        rs.getInt("semanas")));
    }

    /*
     * Los SEIS métodos de aquí abajo siguen existiendo por si alguien necesita
     * un escalar suelto, pero el dashboard YA NO los usa: pedirlos los seis
     * costaba seis viajes a ClickHouse y seis recorridos de la tabla. Si hacen
     * falta varios a la vez, {@link #resumenEscalares()} los da en una pasada.
     */

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

    /**
     * `session_id` que cierra la ventana pedida, leído por el ORDEN DE LA TABLA.
     *
     * `fact_eventos` es una MergeTree con {@code ORDER BY (session_id,
     * event_index)}, así que esto es una lectura por la clave de orden, sin
     * agregación: 3 ms para la primera página y 17 ms para la última.
     * Devuelve null si el desplazamiento cae más allá del final.
     */
    private String cotaSessionId(int offset, int limit) {
        List<String> fila = clickHouseJdbc.query(
                "SELECT session_id FROM fact_eventos ORDER BY session_id LIMIT 1 OFFSET ?",
                (rs, n) -> rs.getString(1), Math.max(offset + limit - 1, 0));
        return fila.isEmpty() ? null : fila.get(0);
    }

    /**
     * Página de la tabla de sesiones del dashboard.
     *
     * <h3>Dos arreglos, y el primero es de CORRECCIÓN</h3>
     *
     * <b>1. El orden no era determinista.</b> Ordenaba solo por `session_id`, y
     * cada sesión aporta varias filas —el GROUP BY incluye `timestamp_utc`, que
     * es distinto por evento—, así que los empates los rompía el orden en que
     * ClickHouse devolviera los bloques. Comprobado: la MISMA consulta con el
     * MISMO desplazamiento daba tres resultados distintos en tres ejecuciones
     * seguidas. Con LIMIT/OFFSET eso significa que al pasar de página se podían
     * repetir filas y perder otras. Ahora desempata por
     * {@code (session_id, timestamp_utc, user_id)}, que es la clave completa del
     * GROUP BY y por tanto un orden total: tres ejecuciones, el mismo resultado.
     *
     * <b>2. Se acota el recorrido.</b> Agregaba los 2.931.837 eventos para
     * quedarse con 20 filas: 544 ms. Como las filas y los grupos van AMBOS
     * ordenados por `session_id` y los grupos nunca son más que las filas, el
     * `session_id` de la fila que ocupa la posición {@code offset+limit-1}
     * acota por arriba a todos los grupos de la ventana pedida; filtrar por él
     * no puede dejar fuera ninguna fila del resultado. Verificado comparando
     * las dos formas en las páginas 0, 20, 500, 100.000 y la última: idénticas.
     *
     * Medido: 544 → <b>10 ms</b> en la primera página, 708 → 141 ms en la
     * página 100.000 y sin cambio en la última (ahí la cota es la tabla
     * entera). Nunca es peor.
     *
     * <h3>Lo que este método NO arregla, y queda dicho</h3>
     * El listado se llama «sesiones» pero devuelve un EVENTO por fila: el
     * GROUP BY incluye `timestamp_utc` y produce 2.931.837 grupos para 474.645
     * sesiones, de modo que `count() AS event_index` vale 1 siempre y
     * `totalElements` (474.645) no cuadra con las filas que se pueden paginar.
     * Es anterior a este cambio y decidir si la tabla lista sesiones o eventos
     * es una decisión de producto, no de rendimiento: no se toca aquí.
     */
    public List<FactEvento> findSesionesPaginadas(int offset, int limit) {
        String cota = cotaSessionId(offset, limit);
        if (cota == null) {
            return List.of();
        }
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
                "WHERE session_id <= ? " +
                "GROUP BY session_id, user_id, timestamp_utc " +
                "ORDER BY session_id, timestamp_utc, user_id " +
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
                cota, limit, offset
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
