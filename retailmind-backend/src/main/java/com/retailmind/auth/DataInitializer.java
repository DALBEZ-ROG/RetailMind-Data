package com.retailmind.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@Order(2)
public class DataInitializer implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(DataInitializer.class);

    private final ClickHouseUserRepository usuarioRepo;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(ClickHouseUserRepository usuarioRepo,
                           PasswordEncoder passwordEncoder) {
        this.usuarioRepo = usuarioRepo;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            if (!usuarioRepo.existsByUsername("admin")) {
                UsuarioSistema admin = new UsuarioSistema();
                admin.setUsername("admin");
                admin.setPassword(passwordEncoder.encode("admin123"));
                admin.setNombre("Administrador");
                admin.setRol(UsuarioSistema.Rol.ADMIN);
                admin.setActivo(true);
                usuarioRepo.save(admin);
                logger.info("[INIT] Usuario admin creado (admin / admin123)");
            } else {
                logger.info("[INIT] Usuario admin ya existe en ClickHouse");
            }
        } catch (Exception e) {
            logger.warn("[INIT] No se pudo verificar/crear usuario admin: {}", e.getMessage());
        }
    }
}
