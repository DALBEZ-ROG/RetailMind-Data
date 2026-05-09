package com.retailmind.service;

import com.retailmind.entity.Sesion;
import com.retailmind.repository.SesionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SesionService {

    private final SesionRepository sesionRepository;

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
}
