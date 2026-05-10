package com.retailmind.service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.retailmind.dto.GrupoConteoDTO;
import com.retailmind.entity.Sesion;
import com.retailmind.repository.SesionRepository;

@Service
@Transactional(readOnly = true)
public class SesionService {

    private final SesionRepository sesionRepository;

    public SesionService(SesionRepository sesionRepository) {
        this.sesionRepository = sesionRepository;
    }

    public Page<Sesion> findAll(Pageable pageable) {
        return sesionRepository.findAll(pageable);
    }

    public Optional<Sesion> findById(String sessionId) {
        return sesionRepository.findById(sessionId);
    }

    public Page<Sesion> findByUsuario(String userId, Pageable pageable) {
        return sesionRepository.findByUsuario_UserId(userId, pageable);
    }

    public Page<Sesion> findByCanal(Integer channelId, Pageable pageable) {
        return sesionRepository.findByCanal_ChannelId(channelId, pageable);
    }

    public List<GrupoConteoDTO> countPorCanal() {
        return sesionRepository.countPorCanal().stream()
                .map(row -> new GrupoConteoDTO(
                        row[0] != null ? row[0].toString() : "Desconocido",
                        ((Number) row[1]).longValue()))
                .collect(Collectors.toList());
    }

    public List<GrupoConteoDTO> countPorRegion() {
        return sesionRepository.countPorRegion().stream()
                .map(row -> new GrupoConteoDTO(
                        row[0] != null ? row[0].toString() : "Desconocido",
                        ((Number) row[1]).longValue()))
                .collect(Collectors.toList());
    }

    public List<GrupoConteoDTO> countPorDispositivo() {
        return sesionRepository.countPorDispositivo().stream()
                .map(row -> new GrupoConteoDTO(
                        row[0] != null ? row[0].toString() : "Desconocido",
                        ((Number) row[1]).longValue()))
                .collect(Collectors.toList());
    }
}
