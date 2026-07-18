package com.retailmind.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import com.zaxxer.hikari.HikariDataSource;

/**
 * Datasource de ClickHouse (SOLO analytics; lo transaccional vive en
 * PostgreSQL, ver PostgresConfig).
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

        HikariDataSource ds = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(url)
                .username(username)
                .password(password)
                .driverClassName(driverClassName)
                .build();

        // ClickHouse es analítica OPCIONAL: con el servidor apagado hay que fallar
        // rápido (invariante del sistema: solo PostgreSQL manda en /api/health y en
        // los healthchecks de contenedores). Sin esto, Hikari espera 30s por defecto
        // y el driver no tiene timeout de conexión: /api/health se colgaba.
        ds.setConnectionTimeout(3000);
        ds.setValidationTimeout(1500);
        ds.setInitializationFailTimeout(-1);
        ds.addDataSourceProperty("connect_timeout", "2500");
        ds.addDataSourceProperty("socket_timeout", "30000");

        return new JdbcTemplate(ds);
    }

    /**
     * Crea las tablas del sistema en ClickHouse al arrancar.
     * TOLERANTE A FALLOS: si ClickHouse no responde, se registra un WARN y la
     * app levanta igual (lo operativo vive en PostgreSQL y no depende de esto;
     * el health reportara clickhouse=DOWN).
     */
    @Bean
    public ApplicationRunner initClickHouseTables(JdbcTemplate jdbc) {
        return args -> {
            try {
                inicializarTablasSistema(jdbc);
            } catch (Exception e) {
                logger.warn("ClickHouse no disponible; analytics deshabilitado temporalmente ({})",
                        e.getMessage());
            }
        };
    }

    private void inicializarTablasSistema(JdbcTemplate jdbc) {
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
    }
}
