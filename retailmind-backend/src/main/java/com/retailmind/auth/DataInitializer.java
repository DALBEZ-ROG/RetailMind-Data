package com.retailmind.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Asegura el usuario administrador en PostgreSQL al arrancar.
 * (El seed principal vive en 23_seed_roles_admin.sql; esto es la red de
 * seguridad si la BD se recrea sin correr el seed.)
 */
@Component
@Order(2)
public class DataInitializer implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(DataInitializer.class);

    static final String ADMIN_EMAIL = "admin@retailmind.com";
    private static final String ADMIN_PASSWORD = "Admin2026!";

    private final PostgresUserRepository usuarioRepo;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(PostgresUserRepository usuarioRepo,
                           PasswordEncoder passwordEncoder) {
        this.usuarioRepo = usuarioRepo;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            if (!usuarioRepo.existsByEmail(ADMIN_EMAIL)) {
                usuarioRepo.crearUsuario(ADMIN_EMAIL,
                        passwordEncoder.encode(ADMIN_PASSWORD),
                        "Administrador", "Sistema", "ADMIN");
                logger.info("[INIT] Usuario admin creado en PostgreSQL ({})", ADMIN_EMAIL);
            } else {
                logger.info("[INIT] Usuario admin ya existe en PostgreSQL");
            }
        } catch (Exception e) {
            logger.warn("[INIT] No se pudo verificar/crear usuario admin: {}", e.getMessage());
        }
    }
}
