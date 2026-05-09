package com.retailmind.repository;

import com.retailmind.entity.Sesion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SesionRepository extends JpaRepository<Sesion, String> {

    Page<Sesion> findByUsuario_UserId(String userId, Pageable pageable);

    Page<Sesion> findByCanal_ChannelId(Integer channelId, Pageable pageable);
}
