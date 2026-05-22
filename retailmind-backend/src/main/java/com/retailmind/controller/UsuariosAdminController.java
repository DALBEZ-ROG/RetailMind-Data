package com.retailmind.controller;

import java.util.Map;
import java.util.NoSuchElementException;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.retailmind.service.UsuariosAdminService;

@RestController
@RequestMapping("/api/admin/usuarios")
public class UsuariosAdminController {

    private final UsuariosAdminService service;

    public UsuariosAdminController(UsuariosAdminService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<?> listar() {
        try {
            return ResponseEntity.ok(service.listarUsuarios());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping
    public ResponseEntity<?> crear(@RequestBody Map<String, String> body) {
        try {
            service.crearUsuario(body.get("username"), body.get("password"),
                    body.get("email"), body.get("rol"));
            return ResponseEntity.ok(Map.of("success", true, "mensaje", "Usuario creado"));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{username}")
    public ResponseEntity<?> eliminar(@PathVariable String username) {
        try {
            service.eliminarUsuario(username);
            return ResponseEntity.ok(Map.of("success", true, "mensaje", "Usuario eliminado"));
        } catch (IllegalStateException | NoSuchElementException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{username}/toggle-activo")
    public ResponseEntity<?> toggleActivo(@PathVariable String username) {
        try {
            service.toggleActivo(username);
            return ResponseEntity.ok(Map.of("success", true, "mensaje", "Estado actualizado"));
        } catch (NoSuchElementException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
