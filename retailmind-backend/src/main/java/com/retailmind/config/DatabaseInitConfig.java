package com.retailmind.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Crea tablas auxiliares que no forman parte del modelo JPA principal.
 */
@Configuration
public class DatabaseInitConfig {

    @Bean
    public ApplicationRunner initAuxTables(@Qualifier("jdbcTemplate") JdbcTemplate jdbc) {
        return args -> {
            // Tabla de historial de cargas ETL
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS carga_historial (
                    id                   SERIAL PRIMARY KEY,
                    semana               INT NOT NULL UNIQUE,
                    fecha_carga          TIMESTAMP NOT NULL DEFAULT NOW(),
                    registros_procesados INT NOT NULL DEFAULT 0,
                    registros_nuevos     INT NOT NULL DEFAULT 0
                )
                """);

            // Tabla de usuarios del sistema (autenticacion JWT)
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS usuarios_sistema (
                    id         SERIAL PRIMARY KEY,
                    username   VARCHAR(50) UNIQUE NOT NULL,
                    password   VARCHAR(255) NOT NULL,
                    nombre     VARCHAR(100),
                    rol        VARCHAR(20) NOT NULL DEFAULT 'VIEWER',
                    activo     BOOLEAN NOT NULL DEFAULT true,
                    created_at TIMESTAMP DEFAULT NOW()
                )
                """);
        };
    }
}
