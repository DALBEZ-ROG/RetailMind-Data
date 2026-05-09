package com.retailmind.service;

import com.retailmind.entity.Evento;
import com.retailmind.repository.EventoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventoService {

    private final EventoRepository eventoRepository;

    public Page<Evento> findAll(Pageable pageable) {
        return eventoRepository.findAll(pageable);
    }

    public Optional<Evento> findById(Long id) {
        return eventoRepository.findById(id);
    }

    public Page<Evento> findBySession(String sessionId, Pageable pageable) {
        return eventoRepository.findBySesion_SessionId(sessionId, pageable);
    }
}
