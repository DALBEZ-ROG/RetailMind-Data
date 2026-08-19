package com.retailmind.auth;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.retailmind.dto.LoginRequestDTO;
import com.retailmind.dto.LoginResponseDTO;
import com.retailmind.dto.RefreshTokenRequestDTO;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Autenticación y gestión de usuarios contra PostgreSQL.
 * El identificador de login es el EMAIL (el campo "username" del DTO se
 * mantiene por compatibilidad con el frontend).
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;
    private final PostgresUserRepository usuarioRepo;
    private final UsuarioAdminService usuarioAdmin;
    private final PasswordEncoder passwordEncoder;

    public AuthController(AuthService authService,
                          PostgresUserRepository usuarioRepo,
                          UsuarioAdminService usuarioAdmin,
                          PasswordEncoder passwordEncoder) {
        this.authService = authService;
        this.usuarioRepo = usuarioRepo;
        this.usuarioAdmin = usuarioAdmin;
        this.passwordEncoder = passwordEncoder;
    }

    /** POST /api/auth/login */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequestDTO request,
                                   HttpServletRequest http) {
        try {
            LoginResponseDTO response = authService.login(request, ipCliente(http),
                    http.getHeader("User-Agent"));
            return ResponseEntity.ok(response);
        } catch (LoginFallidoException e) {
            // Rechazo ESPERADO (correo inexistente, contraseña incorrecta,
            // usuario inactivo, fuera de horario). El motivo detallado ya fue a
            // log_acceso; al cliente va la respuesta genérica de siempre.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Credenciales incorrectas"));
        } catch (Exception e) {
            // Fallo INESPERADO: la base no responde, falta una tabla, el secreto
            // del JWT es inválido…
            //
            // Antes caía en el mismo `catch` que el rechazo esperado y salía
            // como «Credenciales incorrectas» SIN ESCRIBIR NADA en ningún log.
            // Un sistema recién instalado con un problema de configuración era
            // por tanto indistinguible de una contraseña mal tecleada, y no
            // dejaba una sola línea con la que empezar a mirar. Se descubrió
            // justo así: probando el arranque contra una base vacía.
            //
            // La RESPUESTA al cliente no cambia —sigue siendo genérica, que es
            // lo correcto para no filtrar si el correo existe—; lo que cambia es
            // que el operador tiene ahora el motivo en el log del servidor.
            logger.error("Fallo INESPERADO al autenticar a '{}': {}",
                    request.getUsername(), e.toString(), e);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Credenciales incorrectas"));
        }
    }

    /** IP de origen del intento (respeta un proxy si lo hubiera; en dev = localhost). */
    private static String ipCliente(HttpServletRequest http) {
        String xff = http.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return http.getRemoteAddr();
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
                    nombre, apellido, body.get("telefono"), rolCodigo);

            return ResponseEntity.ok(Map.of("success", true, "id", id, "mensaje", "Usuario creado"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Error al crear usuario: " + e.getMessage()));
        }
    }

    /**
     * GET /api/auth/usuarios - Solo ADMIN.
     * NUNCA devuelve password_hash: el hash se queda en el repositorio.
     */
    @GetMapping("/usuarios")
    public ResponseEntity<?> listarUsuarios() {
        try {
            List<Map<String, Object>> usuarios = usuarioRepo.findAll().stream()
                    .map(u -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", u.id());
                        m.put("username", u.email());
                        m.put("nombre", u.apellido() != null ? u.nombre() + " " + u.apellido() : u.nombre());
                        // Nombre y apellido por separado: la pantalla los edita como campos
                        m.put("soloNombre", u.nombre());
                        m.put("apellido", u.apellido());
                        m.put("telefono", u.telefono());
                        m.put("rol", u.rolCodigo());
                        m.put("activo", u.activo());
                        m.put("clienteId", u.clienteId());
                        m.put("fechaCreacion", u.fechaCreacion());
                        m.put("ultimoAcceso", u.ultimoAcceso());
                        return m;
                    })
                    .collect(Collectors.toList());
            return ResponseEntity.ok(usuarios);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Error al listar usuarios: " + e.getMessage()));
        }
    }

    /** GET /api/auth/roles - Solo ADMIN. Lista blanca de roles desde la BD. */
    @GetMapping("/roles")
    public ResponseEntity<?> listarRoles() {
        return ResponseEntity.ok(usuarioRepo.rolesActivos());
    }

    /**
     * PUT /api/auth/usuarios/{id} - Solo ADMIN. Modifica datos y ROL.
     * El email es inmutable y la contraseña NO viaja por aquí.
     */
    @PutMapping("/usuarios/{id}")
    public ResponseEntity<?> modificarUsuario(@PathVariable long id,
                                              @RequestBody Map<String, String> body) {
        usuarioAdmin.modificar(id, body.get("nombre"), body.get("apellido"),
                body.get("telefono"), body.get("rol"));
        return ResponseEntity.ok(Map.of("success", true, "mensaje", "Usuario actualizado"));
    }

    /**
     * PATCH /api/auth/usuarios/{id}/activo - Solo ADMIN. Baja/alta LÓGICA:
     * es lo que la pantalla llama «Eliminar». El usuario deja de poder entrar
     * y su historial se conserva intacto.
     */
    @PatchMapping("/usuarios/{id}/activo")
    public ResponseEntity<?> cambiarActivo(@PathVariable long id,
                                           @RequestBody Map<String, Boolean> body) {
        Boolean activo = body.get("activo");
        if (activo == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "El campo 'activo' es requerido"));
        }
        usuarioAdmin.cambiarActivo(id, activo);
        return ResponseEntity.ok(Map.of("success", true,
                "mensaje", activo ? "Usuario reactivado" : "Usuario eliminado (baja lógica)"));
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
