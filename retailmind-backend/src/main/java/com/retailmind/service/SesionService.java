package com.retailmind.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.retailmind.dto.GrupoConteoDTO;
import com.retailmind.entity.FactEvento;
import com.retailmind.repository.FactEventoRepository;

@Service
public class SesionService {

    private final FactEventoRepository factEventoRepository;

    public SesionService(FactEventoRepository factEventoRepository) {
        this.factEventoRepository = factEventoRepository;
    }

    /**
     * Retorna sesiones paginadas desde ClickHouse (fact_eventos agrupado por session_id).
     */
    public Map<String, Object> findAll(int page, int size) {
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
    }

    /**
     * Busca sesiones por usuario.
     */
    public Map<String, Object> findByUsuario(String userId, int page, int size) {
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
    }

    /**
     * Busca sesiones por canal.
     */
    public Map<String, Object> findByCanal(String channel, int page, int size) {
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
    }

    /**
     * Sesiones agrupadas por canal.
     */
    public List<GrupoConteoDTO> countPorCanal() {
        return factEventoRepository.countSesionesPorCanal();
    }

    /**
     * Sesiones agrupadas por region.
     */
    public List<GrupoConteoDTO> countPorRegion() {
        return factEventoRepository.countSesionesPorRegion();
    }

    /**
     * Sesiones agrupadas por dispositivo.
     */
    public List<GrupoConteoDTO> countPorDispositivo() {
        return factEventoRepository.countSesionesPorDispositivo();
    }
}
