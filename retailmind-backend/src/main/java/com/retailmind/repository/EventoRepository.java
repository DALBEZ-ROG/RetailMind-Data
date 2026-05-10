package com.retailmind.repository;

import com.retailmind.entity.Evento;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EventoRepository extends JpaRepository<Evento, Integer> {

    Page<Evento> findBySesion_SessionId(String sessionId, Pageable pageable);
}
