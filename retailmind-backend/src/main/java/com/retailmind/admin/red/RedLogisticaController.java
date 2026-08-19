package com.retailmind.admin.red;

import java.math.BigDecimal;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * Administración de la RED LOGÍSTICA (solo ADMIN, ver SecurityConfig).
 *
 * Cierra el defecto D-09: estas cinco tablas sostienen el ciclo de venta y no
 * tenían ninguna ruta de escritura, así que solo se poblaban por script.
 *
 * No hay DELETE en ninguna: se da de baja con el toggle `activo`. El porqué
 * está en {@link RedLogisticaService} — borrar una bodega o un transportista
 * referenciado por pedidos y kardex históricos rompería la trazabilidad.
 */
@RestController
@RequestMapping("/api/admin/red")
public class RedLogisticaController {

    public record BodegaReq(String codigo, String nombre, Long ciudadId,
                            String direccion, String telefono, Boolean esPrincipal) {}
    public record TransportistaReq(String nombre, String ruc, String telefono,
                                   String email, String sitioWeb, String urlSeguimiento) {}
    public record MetodoReq(String codigo, String nombre, String descripcion,
                            Long transportistaId, Integer diasEntregaMin,
                            Integer diasEntregaMax, Integer orden) {}
    public record ZonaReq(String nombre, Long paisId, Long provinciaId, Long ciudadId,
                          String descripcion) {}
    public record TarifaReq(Long zonaEnvioId, Long metodoEnvioId, BigDecimal costoBase,
                            BigDecimal costoPorKg, BigDecimal pesoMinKg,
                            BigDecimal pesoMaxKg, BigDecimal envioGratisDesde) {}
    public record ActivoReq(boolean activo) {}

    private final RedLogisticaService servicio;

    public RedLogisticaController(RedLogisticaService servicio) {
        this.servicio = servicio;
    }

    /** Listas para los desplegables (países, provincias, ciudades, zonas…). */
    @GetMapping("/referencias")
    public Map<String, Object> referencias() { return servicio.referencias(); }

    // ── Bodegas ──────────────────────────────────────────────────────────

    @GetMapping("/bodegas")
    public List<Map<String, Object>> bodegas() { return servicio.listarBodegas(); }

    @PostMapping("/bodegas")
    public ResponseEntity<?> crearBodega(@RequestBody BodegaReq r) {
        long id = servicio.crearBodega(r.codigo(), r.nombre(), r.ciudadId(),
                r.direccion(), r.telefono(), r.esPrincipal());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }

    @PutMapping("/bodegas/{id}")
    public ResponseEntity<?> editarBodega(@PathVariable long id, @RequestBody BodegaReq r) {
        servicio.editarBodega(id, r.codigo(), r.nombre(), r.ciudadId(),
                r.direccion(), r.telefono(), r.esPrincipal());
        return ResponseEntity.ok(Map.of("id", id));
    }

    @PatchMapping("/bodegas/{id}/activo")
    public ResponseEntity<?> activarBodega(@PathVariable long id, @RequestBody ActivoReq r) {
        servicio.activarBodega(id, r.activo());
        return ResponseEntity.ok(Map.of("id", id, "activo", r.activo()));
    }

    // ── Transportistas ───────────────────────────────────────────────────

    @GetMapping("/transportistas")
    public List<Map<String, Object>> transportistas() { return servicio.listarTransportistas(); }

    @PostMapping("/transportistas")
    public ResponseEntity<?> crearTransportista(@RequestBody TransportistaReq r) {
        long id = servicio.crearTransportista(r.nombre(), r.ruc(), r.telefono(),
                r.email(), r.sitioWeb(), r.urlSeguimiento());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }

    @PutMapping("/transportistas/{id}")
    public ResponseEntity<?> editarTransportista(@PathVariable long id,
                                                 @RequestBody TransportistaReq r) {
        servicio.editarTransportista(id, r.nombre(), r.ruc(), r.telefono(),
                r.email(), r.sitioWeb(), r.urlSeguimiento());
        return ResponseEntity.ok(Map.of("id", id));
    }

    @PatchMapping("/transportistas/{id}/activo")
    public ResponseEntity<?> activarTransportista(@PathVariable long id,
                                                  @RequestBody ActivoReq r) {
        servicio.activarTransportista(id, r.activo());
        return ResponseEntity.ok(Map.of("id", id, "activo", r.activo()));
    }

    // ── Métodos de envío ─────────────────────────────────────────────────

    @GetMapping("/metodos")
    public List<Map<String, Object>> metodos() { return servicio.listarMetodos(); }

    @PostMapping("/metodos")
    public ResponseEntity<?> crearMetodo(@RequestBody MetodoReq r) {
        long id = servicio.crearMetodo(r.codigo(), r.nombre(), r.descripcion(),
                r.transportistaId(), r.diasEntregaMin(), r.diasEntregaMax(), r.orden());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }

    @PutMapping("/metodos/{id}")
    public ResponseEntity<?> editarMetodo(@PathVariable long id, @RequestBody MetodoReq r) {
        servicio.editarMetodo(id, r.codigo(), r.nombre(), r.descripcion(),
                r.transportistaId(), r.diasEntregaMin(), r.diasEntregaMax(), r.orden());
        return ResponseEntity.ok(Map.of("id", id));
    }

    @PatchMapping("/metodos/{id}/activo")
    public ResponseEntity<?> activarMetodo(@PathVariable long id, @RequestBody ActivoReq r) {
        servicio.activarMetodo(id, r.activo());
        return ResponseEntity.ok(Map.of("id", id, "activo", r.activo()));
    }

    // ── Zonas ────────────────────────────────────────────────────────────

    @GetMapping("/zonas")
    public List<Map<String, Object>> zonas() { return servicio.listarZonas(); }

    @PostMapping("/zonas")
    public ResponseEntity<?> crearZona(@RequestBody ZonaReq r) {
        long id = servicio.crearZona(r.nombre(), r.paisId(), r.provinciaId(),
                r.ciudadId(), r.descripcion());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }

    @PutMapping("/zonas/{id}")
    public ResponseEntity<?> editarZona(@PathVariable long id, @RequestBody ZonaReq r) {
        servicio.editarZona(id, r.nombre(), r.paisId(), r.provinciaId(),
                r.ciudadId(), r.descripcion());
        return ResponseEntity.ok(Map.of("id", id));
    }

    @PatchMapping("/zonas/{id}/activo")
    public ResponseEntity<?> activarZona(@PathVariable long id, @RequestBody ActivoReq r) {
        servicio.activarZona(id, r.activo());
        return ResponseEntity.ok(Map.of("id", id, "activo", r.activo()));
    }

    // ── Tarifas ──────────────────────────────────────────────────────────

    @GetMapping("/tarifas")
    public List<Map<String, Object>> tarifas() { return servicio.listarTarifas(); }

    @PostMapping("/tarifas")
    public ResponseEntity<?> crearTarifa(@RequestBody TarifaReq r) {
        long id = servicio.crearTarifa(r.zonaEnvioId(), r.metodoEnvioId(), r.costoBase(),
                r.costoPorKg(), r.pesoMinKg(), r.pesoMaxKg(), r.envioGratisDesde());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }

    @PutMapping("/tarifas/{id}")
    public ResponseEntity<?> editarTarifa(@PathVariable long id, @RequestBody TarifaReq r) {
        servicio.editarTarifa(id, r.zonaEnvioId(), r.metodoEnvioId(), r.costoBase(),
                r.costoPorKg(), r.pesoMinKg(), r.pesoMaxKg(), r.envioGratisDesde());
        return ResponseEntity.ok(Map.of("id", id));
    }

    @PatchMapping("/tarifas/{id}/activo")
    public ResponseEntity<?> activarTarifa(@PathVariable long id, @RequestBody ActivoReq r) {
        servicio.activarTarifa(id, r.activo());
        return ResponseEntity.ok(Map.of("id", id, "activo", r.activo()));
    }
}
