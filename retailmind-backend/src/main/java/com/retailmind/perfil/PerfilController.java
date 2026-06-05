package com.retailmind.perfil;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/perfil")
public class PerfilController {

    private final PerfilService perfilService;

    public PerfilController(PerfilService perfilService) {
        this.perfilService = perfilService;
    }

    /** GET /api/perfil/{username} */
    @GetMapping("/{username}")
    public ResponseEntity<?> getPerfil(@PathVariable String username) {
        try {
            return ResponseEntity.ok(perfilService.getPerfil(username));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Error al obtener perfil: " + e.getMessage()));
        }
    }

    /** PUT /api/perfil/{username}/email */
    @PutMapping("/{username}/email")
    public ResponseEntity<?> actualizarEmail(@PathVariable String username,
                                              @RequestBody Map<String, String> body) {
        try {
            String email = body.get("email");
            if (email == null || email.isBlank()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "El email es requerido"));
            }
            perfilService.actualizarEmail(username, email);
            return ResponseEntity.ok(Map.of("success", true, "mensaje", "Email actualizado correctamente"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Error al actualizar email: " + e.getMessage()));
        }
    }

    /** PUT /api/perfil/{username}/password */
    @PutMapping("/{username}/password")
    public ResponseEntity<?> cambiarPassword(@PathVariable String username,
                                              @RequestBody Map<String, String> body) {
        try {
            String passwordActual = body.get("passwordActual");
            String passwordNuevo = body.get("passwordNuevo");

            if (passwordActual == null || passwordNuevo == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "passwordActual y passwordNuevo son requeridos"));
            }
            if (passwordNuevo.equals(passwordActual)) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "La nueva contraseña debe ser diferente a la actual"));
            }
            perfilService.cambiarPassword(username, passwordActual, passwordNuevo);
            return ResponseEntity.ok(Map.of("success", true, "mensaje", "Contraseña actualizada correctamente"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Error al cambiar contraseña: " + e.getMessage()));
        }
    }
}
