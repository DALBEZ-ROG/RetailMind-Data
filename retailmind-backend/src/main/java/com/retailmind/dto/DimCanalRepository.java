package com.retailmind.dto;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Repositorio para consultas sobre dim_canal en ClickHouse.
 */
@Repository
public class DimCanalRepository {

    private final JdbcTemplate clickHouseJdbc;

    public DimCanalRepository(@Lazy @Qualifier("clickHouseJdbc") JdbcTemplate clickHouseJdbc) {
        this.clickHouseJdbc = clickHouseJdbc;
    }

    public List<DimCanal> findAll() {
        return clickHouseJdbc.query(
                "SELECT canal_id, canal_nombre FROM dim_canal ORDER BY canal_id",
                (rs, rowNum) -> new DimCanal(
                        rs.getLong("canal_id"),
                        rs.getString("canal_nombre"))
        );
    }

    public long count() {
        Long result = clickHouseJdbc.queryForObject("SELECT count() FROM dim_canal", Long.class);
        return result != null ? result : 0L;
    }
}
