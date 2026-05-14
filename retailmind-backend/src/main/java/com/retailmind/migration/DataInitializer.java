package com.retailmind.migration;

import com.retailmind.entity.UsuarioSistema;
import com.retailmind.repository.UsuarioSistemaRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@Order(1)
public class DataInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbc;
    private final UsuarioSistemaRepository usuarioRepo;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(@Qualifier("jdbcTemplate") JdbcTemplate jdbc,
                           UsuarioSistemaRepository usuarioRepo,
                           PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.usuarioRepo = usuarioRepo;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Crear tablas auxiliares (antes de cualquier consulta JPA)
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

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS carga_historial (
                id                   SERIAL PRIMARY KEY,
                semana               INT NOT NULL UNIQUE,
                fecha_carga          TIMESTAMP NOT NULL DEFAULT NOW(),
                registros_procesados INT NOT NULL DEFAULT 0,
                registros_nuevos     INT NOT NULL DEFAULT 0
            )
            """);

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS error_log (
                id          SERIAL PRIMARY KEY,
                timestamp   TIMESTAMP NOT NULL DEFAULT NOW(),
                tipo_error  VARCHAR(100),
                mensaje     TEXT,
                stack_trace TEXT,
                resuelto    BOOLEAN NOT NULL DEFAULT false
            )
            """);

        // Ahora si consultar con JPA
        if (!usuarioRepo.existsByUsername("admin")) {
            UsuarioSistema admin = new UsuarioSistema();
            admin.setUsername("admin");
            admin.setPassword(passwordEncoder.encode("admin123"));
            admin.setNombre("Administrador");
            admin.setRol(UsuarioSistema.Rol.ADMIN);
            admin.setActivo(true);
            usuarioRepo.save(admin);
            System.out.println("[INIT] Usuario admin creado (admin / admin123)");
        }
    }
}
