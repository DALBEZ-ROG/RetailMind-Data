package com.retailmind.admin.horarios;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Configurar horarios de acceso por rol (tabla grupo_horario).
 * Bajo /api/admin/** SecurityConfig ya exige ADMIN; en BD solo
 * grp_administrador tiene INSERT/UPDATE sobre grupo_horario.
 */
@RestController
@RequestMapping("/api/admin/horarios")
public class HorariosAdminController {

    public record VentanaReq(String rolGrupo, Integer diaSemana, String horaInicio,
                             String horaFin, Boolean activo) {}

    private final HorariosAdminService servicio;

    public HorariosAdminController(HorariosAdminService servicio) {
        this.servicio = servicio;
    }

    @GetMapping
    public List<Map<String, Object>> listar() { return servicio.listar(); }

    @PostMapping
    public ResponseEntity<?> crear(@RequestBody VentanaReq r) {
        long id = servicio.crear(r.rolGrupo(), r.diaSemana(), r.horaInicio(), r.horaFin(),
                r.activo() == null || r.activo());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> editar(@PathVariable long id, @RequestBody VentanaReq r) {
        servicio.editar(id, r.horaInicio(), r.horaFin(), r.activo());
        return ResponseEntity.ok(Map.of("success", true));
    }
}
