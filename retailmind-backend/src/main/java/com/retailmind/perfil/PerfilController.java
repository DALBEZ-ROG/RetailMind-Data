package com.retailmind.perfil;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Perfil del usuario autenticado (PostgreSQL). El usuario sale del JWT.
 * GET /api/perfil es para cualquier rol; los datos de cliente y las
 * direcciones son solo CLIENTE (SecurityConfig + RLS).
 */
@RestController
@RequestMapping("/api/perfil")
public class PerfilController {

    private final PerfilService service;

    public PerfilController(PerfilService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> getPerfil() {
        return service.getPerfil();
    }

    @PutMapping
    public Map<String, Object> actualizarDatos(@RequestBody Map<String, Object> body) {
        service.actualizarDatos(body);
        return Map.of("success", true, "mensaje", "Datos actualizados correctamente");
    }

    // ── Direcciones del cliente ──────────────────────────────────────────

    @GetMapping("/direcciones")
    public List<Map<String, Object>> direcciones() {
        return service.listarDirecciones();
    }

    @PostMapping("/direcciones")
    public ResponseEntity<?> crearDireccion(@RequestBody Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.crearDireccion(body));
    }

    @PutMapping("/direcciones/{id}")
    public Map<String, Object> actualizarDireccion(@PathVariable long id,
                                                   @RequestBody Map<String, Object> body) {
        service.actualizarDireccion(id, body);
        return Map.of("success", true);
    }

    @DeleteMapping("/direcciones/{id}")
    public Map<String, Object> eliminarDireccion(@PathVariable long id) {
        service.eliminarDireccion(id);
        return Map.of("success", true);
    }

    @GetMapping("/ciudades")
    public List<Map<String, Object>> ciudades() {
        return service.ciudades();
    }
}
