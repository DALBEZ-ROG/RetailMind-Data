package com.retailmind.analytics.dashboard;

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

/**
 * Series y desgloses de `fact_eventos` para la pantalla de analítica web.
 * SOLO LECTURA. No modifica el contrato de `/api/dashboard/resumen`, que
 * siguen consumiendo otras pantallas.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * POR QUÉ AQUÍ SE CUENTAN EVENTOS Y NO SESIONES
 *
 * `session_id` NO identifica una sesión en esta tabla. El generador sintético
 * sortea `user_id` y `channel` en CADA FILA, así que un mismo `session_id`
 * acumula varios usuarios y varios canales. Medido sobre los 2,93 M de filas:
 *
 *   · sólo 24.446 de 474.637 sesiones (5,2 %) tienen UN usuario; la moda son 6;
 *   · 412.729 de 474.637 (87 %) tocan 2 o 3 canales distintos;
 *   · en consecuencia, `uniqExact(session_id)` agrupado por canal suma
 *     1.098.845 — el 231 % de las sesiones que existen.
 *
 * Un desglose así NO es parte-de-un-todo: cuenta la misma sesión una vez por
 * cada canal que tocó. Por eso los desgloses de esta clase miden EVENTOS
 * (`count()`), donde cada fila tiene exactamente un canal y su usuario
 * exactamente un dispositivo y una región: suman 100 % y se pueden comparar.
 *
 * Los KPIs de cabecera NO cambian —`totalSesiones` sigue siendo
 * `uniqExact(session_id)`, que como TOTAL sí es correcto—; lo que cambia es la
 * medida de los DESGLOSES.
 * ─────────────────────────────────────────────────────────────────────────────
 */
@Service
public class DashboardSeriesService {

    private static final Logger logger = LoggerFactory.getLogger(DashboardSeriesService.class);

    /**
     * Mínimo de sesiones para que la tasa de conversión de una semana se
     * considere medible. La semana 27 tiene 19 sesiones —son los eventos
     * reales de la tienda, no una carga— y su 21,05 % es ruido sobre n=19.
     * No se oculta la semana: se marca, y la pantalla lo dice.
     */
    private static final int MIN_SESIONES_TASA = 1000;

    private final JdbcTemplate ch;

    public DashboardSeriesService(@Lazy @Qualifier("clickHouseJdbc") JdbcTemplate ch) {
        this.ch = ch;
    }

    /**
     * Todo lo que necesitan los gráficos, en UNA petición.
     *
     * Son seis agregaciones y no seis endpoints a propósito: el navegador ya
     * hace una llamada para `/resumen` y otra para la tabla de sesiones;
     * añadir seis más habría costado seis viajes de red para 216 ms de
     * ClickHouse. Medido por consulta: semanal 37 ms · acciones 11 ms ·
     * duración 10 ms · canal 9 ms · dispositivo 83 ms · región 66 ms.
     */
    public Map<String, Object> getSeries() {
        Map<String, Object> r = new LinkedHashMap<>();
        try {
            r.put("semanal", serieSemanal());
            r.put("acciones", mezclaAcciones());
            r.put("duracion", distribucionDuracion());
            r.put("eventosPorCanal", eventosPorCanal());
            r.put("eventosPorDispositivo", eventosPorDispositivo());
            r.put("eventosPorRegion", eventosPorRegion());
            r.put("minSesionesTasa", MIN_SESIONES_TASA);
            r.put("disponible", true);
        } catch (Exception e) {
            // Mismo criterio que DashboardService: la analítica es degradable.
            // Con ClickHouse apagado la pantalla pinta los KPIs vacíos y los
            // gráficos se ocultan, en vez de romperse.
            logger.warn("No se pudieron leer las series del dashboard: {}", e.getMessage());
            r.clear();
            r.put("disponible", false);
            r.put("error", e.getMessage());
        }
        return r;
    }

    // ── La dimensión que faltaba por completo: el tiempo ─────────────────────

    /**
     * Una fila por semana cargada. Devuelve el VOLUMEN y la BASE de cada tasa
     * (`sesiones`), para que la pantalla pueda declarar el denominador en vez
     * de pintar un porcentaje suelto.
     *
     * Ordenada por `semana` ascendente: el eje se construye con lo que haya:
     * hoy 28 puntos, y 52 el día que se generen todas. No hay ningún número de
     * semanas escrito en el código.
     */
    private List<Map<String, Object>> serieSemanal() {
        return ch.query("""
                SELECT semana,
                       count()                                    AS eventos,
                       uniqExact(session_id)                      AS sesiones,
                       countIf(is_conversion = 1)                 AS conversiones,
                       round(countIf(is_conversion = 1) * 100.0
                             / uniqExact(session_id), 2)          AS tasa
                FROM retailmind.fact_eventos
                GROUP BY semana
                ORDER BY semana""",
                (rs, n) -> {
                    Map<String, Object> f = new LinkedHashMap<>();
                    long sesiones = rs.getLong("sesiones");
                    f.put("semana", rs.getInt("semana"));
                    f.put("eventos", rs.getLong("eventos"));
                    f.put("sesiones", sesiones);
                    f.put("conversiones", rs.getLong("conversiones"));
                    f.put("tasa", rs.getDouble("tasa"));
                    f.put("medible", sesiones >= MIN_SESIONES_TASA);
                    return f;
                });
    }

    // ── De qué están hechos los 2,93 M de eventos ────────────────────────────

    /** Las 6 acciones, de mayor a menor. Ninguna pantalla las mostraba. */
    private List<Map<String, Object>> mezclaAcciones() {
        return grupos("""
                SELECT user_action AS nombre, count() AS total
                FROM retailmind.fact_eventos
                GROUP BY nombre
                ORDER BY total DESC""");
    }

    /**
     * Duración del evento en tramos. Es la única dimensión ORDINAL de la
     * pantalla —los tramos tienen un orden que cambiaría el significado si se
     * barajara—, y por eso es la única que se pinta con una rampa de un solo
     * tono en vez de un color plano.
     *
     * El orden lo fija `orden`, no el volumen: se ordena por tramo y no por
     * tamaño, que es lo que distingue una escala ordinal de un ranking.
     */
    private List<Map<String, Object>> distribucionDuracion() {
        return ch.query("""
                SELECT multiIf(time_spent_sec <  60, 1,
                               time_spent_sec < 300, 2,
                               time_spent_sec < 900, 3,
                               time_spent_sec < 1800, 4, 5)       AS orden,
                       multiIf(time_spent_sec <  60, 'Menos de 1 min',
                               time_spent_sec < 300, '1 a 5 min',
                               time_spent_sec < 900, '5 a 15 min',
                               time_spent_sec < 1800, '15 a 30 min',
                               'Mas de 30 min')                   AS nombre,
                       count()                                    AS total
                FROM retailmind.fact_eventos
                GROUP BY orden, nombre
                ORDER BY orden""",
                (rs, n) -> {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("nombre", rs.getString("nombre"));
                    f.put("total", rs.getLong("total"));
                    return f;
                });
    }

    // ── De dónde vienen ──────────────────────────────────────────────────────

    private List<Map<String, Object>> eventosPorCanal() {
        return grupos("""
                SELECT channel AS nombre, count() AS total
                FROM retailmind.fact_eventos
                GROUP BY nombre
                ORDER BY total DESC""");
    }

    /**
     * El dispositivo y la región cuelgan del USUARIO, no del evento. El JOIN
     * cubre el 100 % de las filas salvo los 61 eventos de la tienda real
     * (`cliente1`, que no está en `dim_usuario`), así que se usa JOIN interno
     * y la diferencia es despreciable y conocida.
     */
    private List<Map<String, Object>> eventosPorDispositivo() {
        return grupos("""
                SELECT d.dispositivo_nombre AS nombre, count() AS total
                FROM retailmind.fact_eventos f
                JOIN retailmind.dim_usuario u ON f.user_id = u.user_id
                JOIN retailmind.dim_dispositivo d ON u.dispositivo_id = d.dispositivo_id
                GROUP BY nombre
                ORDER BY total DESC""");
    }

    private List<Map<String, Object>> eventosPorRegion() {
        return grupos("""
                SELECT r.region_nombre AS nombre, count() AS total
                FROM retailmind.fact_eventos f
                JOIN retailmind.dim_usuario u ON f.user_id = u.user_id
                JOIN retailmind.dim_region r ON u.region_id = r.region_id
                GROUP BY nombre
                ORDER BY total DESC""");
    }

    // ── Utilidad ─────────────────────────────────────────────────────────────

    /** Todas las consultas de desglose devuelven la misma forma {nombre,total}. */
    private List<Map<String, Object>> grupos(String sql) {
        List<Map<String, Object>> filas = new ArrayList<>();
        ch.query(sql, rs -> {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("nombre", rs.getString("nombre"));
            f.put("total", rs.getLong("total"));
            filas.add(f);
        });
        return filas;
    }
}
