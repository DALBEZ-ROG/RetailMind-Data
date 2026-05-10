package com.retailmind.config;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Crea tablas auxiliares que no forman parte del modelo JPA
 * (como carga_historial) si aun no existen.
 */
@Configuration
public class DatabaseInitConfig {

    @Bean
    public ApplicationRunner initAuxTables(JdbcTemplate jdbc) {
        return args -> {
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS carga_historial (
                    id                   SERIAL PRIMARY KEY,
                    semana               INT NOT NULL UNIQUE,
                    fecha_carga          TIMESTAMP NOT NULL DEFAULT NOW(),
                    registros_procesados INT NOT NULL DEFAULT 0,
                    registros_nuevos     INT NOT NULL DEFAULT 0
                )
                """);
        };
    }
}
