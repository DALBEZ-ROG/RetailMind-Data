package com.retailmind.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.retailmind.entity.Conversion;

@Repository
public interface ConversionRepository extends JpaRepository<Conversion, Long> {

    Page<Conversion> findBySesion_SessionId(String sessionId, Pageable pageable);

    long countByIsConversion(Boolean isConversion);

    /**
     * Tasa de conversion agrupada por semana del anio.
     * Usa EXTRACT(WEEK FROM timestamp_utc) de la sesion asociada.
     */
    @Query("SELECT EXTRACT(WEEK FROM c.sesion.timestampUtc), " +
           "       COUNT(c), " +
           "       SUM(CASE WHEN c.isConversion = true THEN 1 ELSE 0 END) " +
           "FROM Conversion c " +
           "WHERE c.sesion IS NOT NULL AND c.sesion.timestampUtc IS NOT NULL " +
           "GROUP BY EXTRACT(WEEK FROM c.sesion.timestampUtc) " +
           "ORDER BY EXTRACT(WEEK FROM c.sesion.timestampUtc)")
    List<Object[]> tasaPorSemana();
}
