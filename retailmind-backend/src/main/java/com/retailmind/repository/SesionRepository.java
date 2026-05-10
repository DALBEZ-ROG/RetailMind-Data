package com.retailmind.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.retailmind.entity.Sesion;

@Repository
public interface SesionRepository extends JpaRepository<Sesion, String> {

    Page<Sesion> findByUsuario_UserId(String userId, Pageable pageable);

    Page<Sesion> findByCanal_ChannelId(Integer channelId, Pageable pageable);

    /** Sesiones agrupadas por nombre de canal */
    @Query("SELECT s.canal.channelName, COUNT(s) " +
           "FROM Sesion s " +
           "WHERE s.canal IS NOT NULL " +
           "GROUP BY s.canal.channelName " +
           "ORDER BY COUNT(s) DESC")
    List<Object[]> countPorCanal();

    /** Sesiones agrupadas por nombre de region del usuario */
    @Query("SELECT s.usuario.region.regionName, COUNT(s) " +
           "FROM Sesion s " +
           "WHERE s.usuario IS NOT NULL AND s.usuario.region IS NOT NULL " +
           "GROUP BY s.usuario.region.regionName " +
           "ORDER BY COUNT(s) DESC")
    List<Object[]> countPorRegion();

    /** Sesiones agrupadas por tipo de dispositivo del usuario */
    @Query("SELECT s.usuario.dispositivo.deviceTypeName, COUNT(s) " +
           "FROM Sesion s " +
           "WHERE s.usuario IS NOT NULL AND s.usuario.dispositivo IS NOT NULL " +
           "GROUP BY s.usuario.dispositivo.deviceTypeName " +
           "ORDER BY COUNT(s) DESC")
    List<Object[]> countPorDispositivo();
}
