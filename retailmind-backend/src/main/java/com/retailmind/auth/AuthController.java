package com.retailmind.auth;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.retailmind.dto.LoginRequestDTO;
import com.retailmind.dto.LoginResponseDTO;
import com.retailmind.dto.RefreshTokenRequestDTO;

/**
 * Autenticación y gestión de usuarios contra PostgreSQL.
 * El identificador de login es el EMAIL (el campo "username" del DTO se
 * mantiene por compatibilidad con el frontend).
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final PostgresUserRepository usuarioRepo;
    private final PasswordEncoder passwordEncoder;

    public AuthController(AuthService authService,
                          PostgresUserRepository usuarioRepo,
                          PasswordEncoder passwordEncoder) {
        this.authService = authService;
        this.usuarioRepo = usuarioRepo;
        this.passwordEncoder = passwordEncoder;
    }

    /** POST /api/auth/login */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequestDTO request) {
        try {
            LoginResponseDTO response = authService.login(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Credenciales incorrectas"));
        }
    }

    /** POST /api/auth/refresh */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@RequestBody RefreshTokenRequestDTO request) {
        try {
            LoginResponseDTO response = authService.refreshToken(request.getRefreshToken());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Refresh token invalido o expirado"));
        }
    }

    /** GET /api/auth/me */
    @GetMapping("/me")
    public ResponseEntity<?> me() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(Map.of(
                "username", auth.getName(),
                "rol", auth.getAuthorities().iterator().next().getAuthority()
        ));
    }

    /** POST /api/auth/logout */
    @PostMapping("/logout")
    public ResponseEntity<?> logout() {
        return ResponseEntity.ok(Map.of("mensaje", "Sesion cerrada exitosamente"));
    }

    /** POST /api/auth/register - Solo ADMIN */
    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody Map<String, String> body) {
        try {
            String email = body.getOrDefault("email", body.get("username"));
            String password = body.get("password");
            String rol = body.get("rol");
            String nombre = body.getOrDefault("nombre", email);
            String apellido = body.get("apellido");

            if (email == null || password == null || rol == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "email (o username), password y rol son requeridos"));
            }

            String rolCodigo = rol.toUpperCase();
            if (!usuarioRepo.rolExiste(rolCodigo)) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Rol invalido: " + rol
                                + ". Use ADMIN, GERENTE, VENDEDOR, COMPRAS, BODEGA, DESPACHO, CLIENTE o ANALISTA"));
            }

            if (usuarioRepo.existsByEmail(email)) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "El usuario '" + email + "' ya existe"));
            }

            long id = usuarioRepo.crearUsuario(email, passwordEncoder.encode(password),
                    nombre, apellido, rolCodigo);

            return ResponseEntity.ok(Map.of("success", true, "id", id, "mensaje", "Usuario creado"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Error al crear usuario: " + e.getMessage()));
        }
    }

    /** GET /api/auth/usuarios - Solo ADMIN */
    @GetMapping("/usuarios")
    public ResponseEntity<?> listarUsuarios() {
        try {
            List<Map<String, Object>> usuarios = usuarioRepo.findAll().stream()
                    .map(u -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", u.id());
                        m.put("username", u.email());
                        m.put("nombre", u.apellido() != null ? u.nombre() + " " + u.apellido() : u.nombre());
                        m.put("rol", u.rolCodigo());
                        m.put("activo", u.activo());
                        m.put("clienteId", u.clienteId());
                        return m;
                    })
                    .collect(Collectors.toList());
            return ResponseEntity.ok(usuarios);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Error al listar usuarios: " + e.getMessage()));
        }
    }

    /** DELETE /api/auth/usuarios/{email} - Solo ADMIN */
    @DeleteMapping("/usuarios/{email}")
    public ResponseEntity<?> eliminarUsuario(@PathVariable String email) {
        try {
            if (DataInitializer.ADMIN_EMAIL.equalsIgnoreCase(email)) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "No se puede eliminar al usuario admin"));
            }

            if (!usuarioRepo.existsByEmail(email)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Usuario no encontrado: " + email));
            }

            usuarioRepo.eliminarPorEmail(email);
            return ResponseEntity.ok(Map.of("success", true, "mensaje", "Usuario eliminado"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Error al eliminar usuario: " + e.getMessage()));
        }
    }
}
