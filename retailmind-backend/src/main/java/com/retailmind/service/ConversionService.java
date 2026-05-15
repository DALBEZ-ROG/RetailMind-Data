package com.retailmind.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.retailmind.dto.TasaSemanaDTO;

@Service
public class ConversionService {

    private static final Logger logger = LoggerFactory.getLogger(ConversionService.class);

    private final JdbcTemplate clickHouseJdbc;

    public ConversionService(@Lazy @Qualifier("clickHouseJdbc") JdbcTemplate clickHouseJdbc) {
        this.clickHouseJdbc = clickHouseJdbc;
    }

    /**
     * Retorna conversiones paginadas desde fact_eventos en ClickHouse.
     * Cada fila con is_conversion=1 o drop_off_flag=1 se considera un registro de conversión.
     */
    public Map<String, Object> findAll(int page, int size) {
        try {
            long total = countTotal();
            int offset = page * size;

            List<Map<String, Object>> content = clickHouseJdbc.query(
                    "SELECT " +
                    "  rowNumberInAllBlocks() AS conversion_id, " +
                    "  session_id, " +
                    "  is_conversion, " +
                    "  drop_off_flag, " +
                    "  timestamp_utc " +
                    "FROM retailmind.fact_eventos " +
                    "WHERE is_conversion = 1 OR drop_off_flag = 1 " +
                    "ORDER BY session_id " +
                    "LIMIT ? OFFSET ?",
                    (rs, rowNum) -> {
                        Map<String, Object> row = new HashMap<>();
                        row.put("conversionId", rs.getLong("conversion_id"));
                        Map<String, String> sesion = new HashMap<>();
                        sesion.put("sessionId", rs.getString("session_id"));
                        row.put("sesion", sesion);
                        row.put("isConversion", rs.getInt("is_conversion") == 1);
                        row.put("dropOffFlag", rs.getInt("drop_off_flag") == 1);
                        row.put("conversionTime", rs.getString("timestamp_utc"));
                        return row;
                    },
                    size, offset
            );

            Map<String, Object> result = new HashMap<>();
            result.put("content", content);
            result.put("totalElements", total);
            result.put("totalPages", (int) Math.ceil((double) total / size));
            result.put("number", page);
            result.put("size", size);
            return result;
        } catch (Exception e) {
            logger.error("Error al consultar conversiones en ClickHouse: {}", e.getMessage());
            Map<String, Object> empty = new HashMap<>();
            empty.put("content", List.of());
            empty.put("totalElements", 0L);
            empty.put("totalPages", 0);
            empty.put("number", page);
            empty.put("size", size);
            return empty;
        }
    }

    /**
     * Retorna resumen de conversiones.
     */
    public Map<String, Long> getResumen() {
        try {
            Long conversiones = clickHouseJdbc.queryForObject(
                    "SELECT count() FROM retailmind.fact_eventos WHERE is_conversion = 1",
                    Long.class);
            Long noConversiones = clickHouseJdbc.queryForObject(
                    "SELECT count() FROM retailmind.fact_eventos WHERE is_conversion = 0",
                    Long.class);

            long conv = conversiones != null ? conversiones : 0L;
            long noConv = noConversiones != null ? noConversiones : 0L;

            Map<String, Long> resumen = new HashMap<>();
            resumen.put("conversiones", conv);
            resumen.put("noConversiones", noConv);
            resumen.put("total", conv + noConv);
            return resumen;
        } catch (Exception e) {
            logger.error("Error al obtener resumen de conversiones: {}", e.getMessage());
            Map<String, Long> resumen = new HashMap<>();
            resumen.put("conversiones", 0L);
            resumen.put("noConversiones", 0L);
            resumen.put("total", 0L);
            return resumen;
        }
    }

    /**
     * Retorna tasa de conversión agrupada por semana.
     */
    public List<TasaSemanaDTO> getTasaPorSemana() {
        try {
            return clickHouseJdbc.query(
                    "SELECT " +
                    "  semana, " +
                    "  count() AS total_eventos, " +
                    "  sum(is_conversion) AS total_conversiones " +
                    "FROM retailmind.fact_eventos " +
                    "GROUP BY semana " +
                    "ORDER BY semana",
                    (rs, rowNum) -> new TasaSemanaDTO(
                            rs.getInt("semana"),
                            rs.getLong("total_eventos"),
                            rs.getLong("total_conversiones"))
            );
        } catch (Exception e) {
            logger.error("Error al obtener tasa por semana: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private long countTotal() {
        Long count = clickHouseJdbc.queryForObject(
                "SELECT count() FROM retailmind.fact_eventos WHERE is_conversion = 1 OR drop_off_flag = 1",
                Long.class);
        return count != null ? count : 0L;
    }
}
