package com.retailmind.config;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Configuración de JdbcTemplates:
 * - PostgreSQL (primary): auth, ETL, tablas auxiliares
 * - ClickHouse (lazy): consultas analíticas del dashboard
 */
@Configuration
public class ClickHouseConfig {

    @Primary
    @Bean(name = "jdbcTemplate")
    public JdbcTemplate postgresJdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Lazy
    @Bean(name = "clickHouseJdbc")
    public JdbcTemplate clickHouseJdbcTemplate(
            @Value("${clickhouse.datasource.url}") String url,
            @Value("${clickhouse.datasource.username}") String username,
            @Value("${clickhouse.datasource.password}") String password,
            @Value("${clickhouse.datasource.driver-class-name}") String driverClassName) {

        DataSource ds = DataSourceBuilder.create()
                .url(url)
                .username(username)
                .password(password)
                .driverClassName(driverClassName)
                .build();

        return new JdbcTemplate(ds);
    }
}
