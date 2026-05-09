package com.retailmind.repository;

import com.retailmind.entity.Conversion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Map;

@Repository
public interface ConversionRepository extends JpaRepository<Conversion, Long> {

    Page<Conversion> findBySesion_SessionId(String sessionId, Pageable pageable);

    long countByIsConversion(Boolean isConversion);

    @Query("""
            SELECT new map(
                SUM(CASE WHEN c.isConversion = true  THEN 1 ELSE 0 END) AS conversiones,
                SUM(CASE WHEN c.isConversion = false THEN 1 ELSE 0 END) AS noConversiones,
                COUNT(c) AS total
            )
            FROM Conversion c
            """)
    Map<String, Long> getResumenConversiones();
}
