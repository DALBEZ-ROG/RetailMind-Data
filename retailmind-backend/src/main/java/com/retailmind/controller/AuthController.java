package com.retailmind.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import com.retailmind.dto.LoginRequestDTO;
import com.retailmind.dto.LoginResponseDTO;
import com.retailmind.dto.RefreshTokenRequestDTO;
import com.retailmind.entity.UsuarioSistema;
import com.retailmind.repository.ClickHouseUserRepository;
import com.retailmind.service.AuthService;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final ClickHouseUserRepository usuarioRepo;
    private final PasswordEncoder passwordEncoder;

    public AuthController(AuthService authService,
                          ClickHouseUserRepository usuarioRepo,
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
            String username = body.get("username");
            String password = body.get("password");
            String email = body.get("email");
            String rol = body.get("rol");

            if (username == null || password == null || rol == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "username, password y rol son requeridos"));
            }

            if (usuarioRepo.existsByUsername(username)) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "El usuario '" + username + "' ya existe"));
            }

            UsuarioSistema usuario = new UsuarioSistema();
            usuario.setUsername(username);
            usuario.setPassword(passwordEncoder.encode(password));
            usuario.setNombre(email != null ? email : "");
            usuario.setRol(UsuarioSistema.Rol.valueOf(rol.toUpperCase()));
            usuario.setActivo(true);
            usuarioRepo.save(usuario);

            return ResponseEntity.ok(Map.of("success", true, "mensaje", "Usuario creado"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Rol invalido. Use ADMIN o CLIENTE"));
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
                        m.put("id", u.getId());
                        m.put("username", u.getUsername());
                        m.put("nombre", u.getNombre());
                        m.put("rol", u.getRol().name());
                        m.put("activo", u.getActivo());
                        m.put("fechaCreacion", u.getFechaCreacion());
                        return m;
                    })
                    .collect(Collectors.toList());
            return ResponseEntity.ok(usuarios);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Error al listar usuarios: " + e.getMessage()));
        }
    }

    /** DELETE /api/auth/usuarios/{username} - Solo ADMIN */
    @DeleteMapping("/usuarios/{username}")
    public ResponseEntity<?> eliminarUsuario(@PathVariable String username) {
        try {
            if ("admin".equals(username)) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "No se puede eliminar al usuario admin"));
            }

            if (!usuarioRepo.existsByUsername(username)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Usuario no encontrado: " + username));
            }

            usuarioRepo.deleteByUsername(username);
            return ResponseEntity.ok(Map.of("success", true, "mensaje", "Usuario eliminado"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Error al eliminar usuario: " + e.getMessage()));
        }
    }
}
