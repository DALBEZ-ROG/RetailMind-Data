package com.retailmind.analytics.sesiones;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.retailmind.dto.FactEvento;
import com.retailmind.dto.FactEventoRepository;
import com.retailmind.dto.GrupoConteoDTO;

@Service
public class SesionService {

    private static final Logger logger = LoggerFactory.getLogger(SesionService.class);

    private final FactEventoRepository factEventoRepository;

    public SesionService(FactEventoRepository factEventoRepository) {
        this.factEventoRepository = factEventoRepository;
    }

    /**
     * Retorna sesiones paginadas desde ClickHouse (fact_eventos agrupado por session_id).
     */
    public Map<String, Object> findAll(int page, int size) {
        try {
            long total = factEventoRepository.countDistinctSessions();
            int offset = page * size;
            List<FactEvento> content = factEventoRepository.findSesionesPaginadas(offset, size);

            Map<String, Object> result = new HashMap<>();
            result.put("content", content);
            result.put("totalElements", total);
            result.put("totalPages", (int) Math.ceil((double) total / size));
            result.put("number", page);
            result.put("size", size);
            return result;
        } catch (Exception e) {
            logger.warn("Error al consultar sesiones en ClickHouse: {}", e.getMessage());
            return emptyPage(page, size);
        }
    }

    /**
     * Busca sesiones por usuario.
     */
    public Map<String, Object> findByUsuario(String userId, int page, int size) {
        try {
            long total = factEventoRepository.countSessionsByUsuario(userId);
            int offset = page * size;
            List<FactEvento> content = factEventoRepository.findSesionesByUsuario(userId, offset, size);

            Map<String, Object> result = new HashMap<>();
            result.put("content", content);
            result.put("totalElements", total);
            result.put("totalPages", (int) Math.ceil((double) total / size));
            result.put("number", page);
            result.put("size", size);
            return result;
        } catch (Exception e) {
            logger.warn("Error al consultar sesiones por usuario en ClickHouse: {}", e.getMessage());
            return emptyPage(page, size);
        }
    }

    /**
     * Busca sesiones por canal.
     */
    public Map<String, Object> findByCanal(String channel, int page, int size) {
        try {
            long total = factEventoRepository.countSessionsByCanal(channel);
            int offset = page * size;
            List<FactEvento> content = factEventoRepository.findSesionesByCanal(channel, offset, size);

            Map<String, Object> result = new HashMap<>();
            result.put("content", content);
            result.put("totalElements", total);
            result.put("totalPages", (int) Math.ceil((double) total / size));
            result.put("number", page);
            result.put("size", size);
            return result;
        } catch (Exception e) {
            logger.warn("Error al consultar sesiones por canal en ClickHouse: {}", e.getMessage());
            return emptyPage(page, size);
        }
    }

    /**
     * Sesiones agrupadas por canal.
     */
    public List<GrupoConteoDTO> countPorCanal() {
        try {
            return factEventoRepository.countSesionesPorCanal();
        } catch (Exception e) {
            logger.warn("Error al agrupar sesiones por canal: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Sesiones agrupadas por region.
     */
    public List<GrupoConteoDTO> countPorRegion() {
        try {
            return factEventoRepository.countSesionesPorRegion();
        } catch (Exception e) {
            logger.warn("Error al agrupar sesiones por region: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Sesiones agrupadas por dispositivo.
     */
    public List<GrupoConteoDTO> countPorDispositivo() {
        try {
            return factEventoRepository.countSesionesPorDispositivo();
        } catch (Exception e) {
            logger.warn("Error al agrupar sesiones por dispositivo: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private Map<String, Object> emptyPage(int page, int size) {
        Map<String, Object> result = new HashMap<>();
        result.put("content", List.of());
        result.put("totalElements", 0L);
        result.put("totalPages", 0);
        result.put("number", page);
        result.put("size", size);
        return result;
    }
}
