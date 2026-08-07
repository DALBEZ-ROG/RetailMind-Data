package com.retailmind.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Asegura el usuario administrador en PostgreSQL al arrancar.
 * (El seed principal vive en 23_seed_roles_admin.sql; esto es la red de
 * seguridad si la BD se recrea sin correr el seed.)
 *
 * <h3>Por que la contrasena ya no esta en el codigo</h3>
 * Hasta el 2026-08-06 estaba aqui como constante `ADMIN_PASSWORD`, o sea
 * versionada en git y ademas COMPILADA en el jar, donde un `strings` la
 * encuentra. (El valor NO se reproduce en este comentario: repetirlo aqui
 * dejaria la fuga exactamente donde estaba.) Ahora llega por configuracion
 * (`admin.seed.password` ← variable de entorno `ADMIN_PASSWORD`, que vive en
 * el `.env`). El valor NO cambio: solo cambio de sitio.
 *
 * <h3>Que pasa si la variable falta: NI abortar NI un valor por defecto</h3>
 * <ul>
 *   <li><b>Abortar el arranque</b> seria desproporcionado. Esto es una red de
 *       seguridad para una base recien creada; en una instalacion en marcha el
 *       admin YA existe y esta contrasena no se usa jamas. Tumbar la
 *       aplicacion entera por un dato que casi nunca hace falta convertiria
 *       una omision de configuracion en una caida.</li>
 *   <li><b>Un valor por defecto</b> seria PEOR que el literal que habia: una
 *       contrasena conocida, escrita en el repositorio y aplicada a una cuenta
 *       ADMIN. Exactamente el fallo que este cambio viene a cerrar.</li>
 *   <li>Por eso: si falta y el admin ya existe, no pasa nada —ni se menciona—;
 *       si falta y HAY que sembrarlo, se salta la siembra y se registra en
 *       ERROR que la cuenta no se creo y como arreglarlo. El sistema arranca,
 *       y el que despliega se entera del hueco por el log en vez de heredar
 *       una cuenta con contrasena publica.</li>
 * </ul>
 */
@Component
@Order(2)
public class DataInitializer implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(DataInitializer.class);

    static final String ADMIN_EMAIL = "admin@retailmind.com";

    private final PostgresUserRepository usuarioRepo;
    private final PasswordEncoder passwordEncoder;
    /** Vacia cuando nadie la configuro: no hay valor por defecto a proposito. */
    private final String adminPassword;

    public DataInitializer(PostgresUserRepository usuarioRepo,
                           PasswordEncoder passwordEncoder,
                           @Value("${admin.seed.password:}") String adminPassword) {
        this.usuarioRepo = usuarioRepo;
        this.passwordEncoder = passwordEncoder;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            if (usuarioRepo.existsByEmail(ADMIN_EMAIL)) {
                logger.info("[INIT] Usuario admin ya existe en PostgreSQL");
                return;
            }
            // Solo aqui hace falta la contrasena: se comprueba DESPUES de saber
            // que el usuario no esta, para que su ausencia sea irrelevante en el
            // caso normal.
            if (adminPassword == null || adminPassword.isBlank()) {
                logger.error("[INIT] No existe el usuario admin ({}) y no hay contrasena de "
                        + "siembra configurada: la cuenta NO se crea. Define ADMIN_PASSWORD en "
                        + "el entorno (.env) y reinicia, o siembra el admin con "
                        + "23_seed_roles_admin.sql.", ADMIN_EMAIL);
                return;
            }
            usuarioRepo.crearUsuario(ADMIN_EMAIL,
                    passwordEncoder.encode(adminPassword),
                    "Administrador", "Sistema", "ADMIN");
            logger.info("[INIT] Usuario admin creado en PostgreSQL ({})", ADMIN_EMAIL);
        } catch (Exception e) {
            logger.warn("[INIT] No se pudo verificar/crear usuario admin: {}", e.getMessage());
        }
    }
}
