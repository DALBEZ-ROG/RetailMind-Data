package com.retailmind.config;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Configuración única de datasource: ClickHouse.
 * PostgreSQL ha sido eliminado del sistema.
 */
@Configuration
public class ClickHouseConfig {

    private static final Logger logger = LoggerFactory.getLogger(ClickHouseConfig.class);

    @Primary
    @Bean(name = {"jdbcTemplate", "clickHouseJdbc"})
    @SuppressWarnings("null")
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

    /**
     * Crea las tablas del sistema en ClickHouse al arrancar.
     */
    @Bean
    public ApplicationRunner initClickHouseTables(JdbcTemplate jdbc) {
        return args -> {
            logger.info("Inicializando tablas del sistema en ClickHouse...");

            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS retailmind.usuarios_sistema (
                    id String,
                    username String,
                    password String,
                    nombre String,
                    rol String,
                    activo UInt8,
                    fecha_creacion String
                ) ENGINE = MergeTree()
                ORDER BY username
                """);

            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS retailmind.wishlist_items (
                    wishlist_id String,
                    user_id String,
                    producto_id String,
                    fecha_agregado String
                ) ENGINE = MergeTree()
                ORDER BY (user_id, producto_id)
                """);

            logger.info("Tablas del sistema verificadas en ClickHouse");
        };
    }
}
