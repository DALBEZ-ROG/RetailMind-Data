package com.retailmind.gerencia;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Metas de venta por período (OTD-VEN-15, script 48).
 * SecurityConfig: lectura ADMIN/GERENTE/VENDEDOR/ANALISTA; escritura solo
 * ADMIN/GERENTE (espeja los GRANTs de meta_venta).
 */
@RestController
@RequestMapping("/api/gerencia/metas")
public class MetasVentaController {

    public record MetaReq(Integer anio, Integer mes, String departamento,
                          Double montoMeta, String notas) {}
    public record ActivoReq(boolean activo) {}

    private final MetasVentaService servicio;

    public MetasVentaController(MetasVentaService servicio) {
        this.servicio = servicio;
    }

    @GetMapping
    public List<Map<String, Object>> listar() { return servicio.listar(); }

    /** Meta vigente (activa) de un período; departamento por defecto 'general'. */
    @GetMapping("/vigente")
    public Map<String, Object> vigente(@RequestParam int anio, @RequestParam int mes,
                                       @RequestParam(required = false) String departamento) {
        return servicio.vigente(anio, mes, departamento);
    }

    @PostMapping
    public ResponseEntity<?> crear(@RequestBody MetaReq r) {
        long id = servicio.crear(r.anio(), r.mes(), r.departamento(), r.montoMeta(), r.notas());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> editar(@PathVariable long id, @RequestBody MetaReq r) {
        servicio.editar(id, r.anio(), r.mes(), r.departamento(), r.montoMeta(), r.notas());
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PatchMapping("/{id}/activo")
    public ResponseEntity<?> activar(@PathVariable long id, @RequestBody ActivoReq r) {
        servicio.activar(id, r.activo());
        return ResponseEntity.ok(Map.of("success", true));
    }
}
