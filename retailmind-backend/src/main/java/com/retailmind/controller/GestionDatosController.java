package com.retailmind.controller;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.retailmind.service.GestionDatosService;

@RestController
@RequestMapping("/api/gestion")
public class GestionDatosController {

    private static final Logger logger = LoggerFactory.getLogger(GestionDatosController.class);
    private final GestionDatosService service;

    public GestionDatosController(GestionDatosService service) {
        this.service = service;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // FACT_EVENTOS
    // ══════════════════════════════════════════════════════════════════════════

    @GetMapping("/fact-eventos")
    public ResponseEntity<?> getFactEventos(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Integer semana) {
        try {
            return ResponseEntity.ok(service.getFactEventos(page, size, semana));
        } catch (Exception e) {
            logger.error("Error al obtener fact_eventos: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/fact-eventos/{eventPk}")
    public ResponseEntity<?> getFactEventoById(@PathVariable long eventPk) {
        try {
            Map<String, Object> row = service.getFactEventoById(eventPk);
            return row != null ? ResponseEntity.ok(row) : ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/fact-eventos/{eventPk}")
    public ResponseEntity<?> updateFactEvento(@PathVariable long eventPk, @RequestBody Map<String, Object> body) {
        try {
            service.updateFactEvento(eventPk, body);
            return ResponseEntity.ok(Map.of("success", true, "mensaje", "Evento actualizado"));
        } catch (Exception e) {
            logger.error("Error al actualizar evento {}: {}", eventPk, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/fact-eventos/{eventPk}")
    public ResponseEntity<?> deleteFactEvento(@PathVariable long eventPk) {
        try {
            service.deleteFactEvento(eventPk);
            return ResponseEntity.ok(Map.of("success", true, "mensaje", "Evento eliminado"));
        } catch (Exception e) {
            logger.error("Error al eliminar evento {}: {}", eventPk, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DIM_CANAL
    // ══════════════════════════════════════════════════════════════════════════

    @GetMapping("/dim-canal")
    public ResponseEntity<?> getDimCanal() {
        try { return ResponseEntity.ok(service.getDimension("dim_canal", "canal_id", "canal_nombre")); }
        catch (Exception e) { return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage())); }
    }

    @PostMapping("/dim-canal")
    public ResponseEntity<?> createDimCanal(@RequestBody Map<String, Object> body) {
        try {
            service.insertDimension("dim_canal", "canal_id", "canal_nombre",
                    ((Number) body.get("id")).longValue(), (String) body.get("nombre"));
            return ResponseEntity.ok(Map.of("success", true, "mensaje", "Canal creado"));
        } catch (Exception e) { return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage())); }
    }

    @PutMapping("/dim-canal/{id}")
    public ResponseEntity<?> updateDimCanal(@PathVariable long id, @RequestBody Map<String, Object> body) {
        try {
            service.updateDimension("dim_canal", "canal_id", "canal_nombre", id, (String) body.get("nombre"));
            return ResponseEntity.ok(Map.of("success", true, "mensaje", "Canal actualizado"));
        } catch (Exception e) { return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage())); }
    }

    @DeleteMapping("/dim-canal/{id}")
    public ResponseEntity<?> deleteDimCanal(@PathVariable long id) {
        try {
            service.deleteDimension("dim_canal", "canal_id", id);
            return ResponseEntity.ok(Map.of("success", true, "mensaje", "Canal eliminado"));
        } catch (Exception e) { return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage())); }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DIM_REGION
    // ══════════════════════════════════════════════════════════════════════════

    @GetMapping("/dim-region")
    public ResponseEntity<?> getDimRegion() {
        try { return ResponseEntity.ok(service.getDimension("dim_region", "region_id", "region_nombre")); }
        catch (Exception e) { return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage())); }
    }

    @PostMapping("/dim-region")
    public ResponseEntity<?> createDimRegion(@RequestBody Map<String, Object> body) {
        try {
            service.insertDimension("dim_region", "region_id", "region_nombre",
                    ((Number) body.get("id")).longValue(), (String) body.get("nombre"));
            return ResponseEntity.ok(Map.of("success", true, "mensaje", "Region creada"));
        } catch (Exception e) { return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage())); }
    }

    @PutMapping("/dim-region/{id}")
    public ResponseEntity<?> updateDimRegion(@PathVariable long id, @RequestBody Map<String, Object> body) {
        try {
            service.updateDimension("dim_region", "region_id", "region_nombre", id, (String) body.get("nombre"));
            return ResponseEntity.ok(Map.of("success", true, "mensaje", "Region actualizada"));
        } catch (Exception e) { return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage())); }
    }

    @DeleteMapping("/dim-region/{id}")
    public ResponseEntity<?> deleteDimRegion(@PathVariable long id) {
        try {
            service.deleteDimension("dim_region", "region_id", id);
            return ResponseEntity.ok(Map.of("success", true, "mensaje", "Region eliminada"));
        } catch (Exception e) { return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage())); }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DIM_DISPOSITIVO
    // ══════════════════════════════════════════════════════════════════════════

    @GetMapping("/dim-dispositivo")
    public ResponseEntity<?> getDimDispositivo() {
        try { return ResponseEntity.ok(service.getDimension("dim_dispositivo", "dispositivo_id", "dispositivo_nombre")); }
        catch (Exception e) { return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage())); }
    }

    @PostMapping("/dim-dispositivo")
    public ResponseEntity<?> createDimDispositivo(@RequestBody Map<String, Object> body) {
        try {
            service.insertDimension("dim_dispositivo", "dispositivo_id", "dispositivo_nombre",
                    ((Number) body.get("id")).longValue(), (String) body.get("nombre"));
            return ResponseEntity.ok(Map.of("success", true, "mensaje", "Dispositivo creado"));
        } catch (Exception e) { return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage())); }
    }

    @PutMapping("/dim-dispositivo/{id}")
    public ResponseEntity<?> updateDimDispositivo(@PathVariable long id, @RequestBody Map<String, Object> body) {
        try {
            service.updateDimension("dim_dispositivo", "dispositivo_id", "dispositivo_nombre", id, (String) body.get("nombre"));
            return ResponseEntity.ok(Map.of("success", true, "mensaje", "Dispositivo actualizado"));
        } catch (Exception e) { return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage())); }
    }

    @DeleteMapping("/dim-dispositivo/{id}")
    public ResponseEntity<?> deleteDimDispositivo(@PathVariable long id) {
        try {
            service.deleteDimension("dim_dispositivo", "dispositivo_id", id);
            return ResponseEntity.ok(Map.of("success", true, "mensaje", "Dispositivo eliminado"));
        } catch (Exception e) { return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage())); }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DIM_CATEGORIA
    // ══════════════════════════════════════════════════════════════════════════

    @GetMapping("/dim-categoria")
    public ResponseEntity<?> getDimCategoria() {
        try { return ResponseEntity.ok(service.getDimension("dim_categoria", "categoria_id", "categoria_nombre")); }
        catch (Exception e) { return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage())); }
    }

    @PostMapping("/dim-categoria")
    public ResponseEntity<?> createDimCategoria(@RequestBody Map<String, Object> body) {
        try {
            service.insertDimension("dim_categoria", "categoria_id", "categoria_nombre",
                    ((Number) body.get("id")).longValue(), (String) body.get("nombre"));
            return ResponseEntity.ok(Map.of("success", true, "mensaje", "Categoria creada"));
        } catch (Exception e) { return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage())); }
    }

    @PutMapping("/dim-categoria/{id}")
    public ResponseEntity<?> updateDimCategoria(@PathVariable long id, @RequestBody Map<String, Object> body) {
        try {
            service.updateDimension("dim_categoria", "categoria_id", "categoria_nombre", id, (String) body.get("nombre"));
            return ResponseEntity.ok(Map.of("success", true, "mensaje", "Categoria actualizada"));
        } catch (Exception e) { return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage())); }
    }

    @DeleteMapping("/dim-categoria/{id}")
    public ResponseEntity<?> deleteDimCategoria(@PathVariable long id) {
        try {
            service.deleteDimension("dim_categoria", "categoria_id", id);
            return ResponseEntity.ok(Map.of("success", true, "mensaje", "Categoria eliminada"));
        } catch (Exception e) { return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage())); }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DIM_FUENTE_TRAFICO
    // ══════════════════════════════════════════════════════════════════════════

    @GetMapping("/dim-fuente-trafico")
    public ResponseEntity<?> getDimFuenteTrafico() {
        try { return ResponseEntity.ok(service.getDimension("dim_fuente_trafico", "fuente_id", "fuente_nombre")); }
        catch (Exception e) { return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage())); }
    }

    @PostMapping("/dim-fuente-trafico")
    public ResponseEntity<?> createDimFuenteTrafico(@RequestBody Map<String, Object> body) {
        try {
            service.insertDimension("dim_fuente_trafico", "fuente_id", "fuente_nombre",
                    ((Number) body.get("id")).longValue(), (String) body.get("nombre"));
            return ResponseEntity.ok(Map.of("success", true, "mensaje", "Fuente de trafico creada"));
        } catch (Exception e) { return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage())); }
    }

    @PutMapping("/dim-fuente-trafico/{id}")
    public ResponseEntity<?> updateDimFuenteTrafico(@PathVariable long id, @RequestBody Map<String, Object> body) {
        try {
            service.updateDimension("dim_fuente_trafico", "fuente_id", "fuente_nombre", id, (String) body.get("nombre"));
            return ResponseEntity.ok(Map.of("success", true, "mensaje", "Fuente de trafico actualizada"));
        } catch (Exception e) { return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage())); }
    }

    @DeleteMapping("/dim-fuente-trafico/{id}")
    public ResponseEntity<?> deleteDimFuenteTrafico(@PathVariable long id) {
        try {
            service.deleteDimension("dim_fuente_trafico", "fuente_id", id);
            return ResponseEntity.ok(Map.of("success", true, "mensaje", "Fuente de trafico eliminada"));
        } catch (Exception e) { return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage())); }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DIM_PRODUCTO
    // ══════════════════════════════════════════════════════════════════════════

    @GetMapping("/dim-producto")
    public ResponseEntity<?> getDimProducto(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        try { return ResponseEntity.ok(service.getProductos(page, size)); }
        catch (Exception e) { return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage())); }
    }

    @PostMapping("/dim-producto")
    public ResponseEntity<?> createDimProducto(@RequestBody Map<String, Object> body) {
        try {
            service.insertProducto((String) body.get("productoId"),
                    ((Number) body.get("categoriaId")).intValue(),
                    (String) body.get("brand"),
                    ((Number) body.get("price")).floatValue());
            return ResponseEntity.ok(Map.of("success", true, "mensaje", "Producto creado"));
        } catch (Exception e) { return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage())); }
    }

    @PutMapping("/dim-producto/{id}")
    public ResponseEntity<?> updateDimProducto(@PathVariable String id, @RequestBody Map<String, Object> body) {
        try {
            service.updateProducto(id, (String) body.get("brand"), ((Number) body.get("price")).floatValue());
            return ResponseEntity.ok(Map.of("success", true, "mensaje", "Producto actualizado"));
        } catch (Exception e) { return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage())); }
    }

    @DeleteMapping("/dim-producto/{id}")
    public ResponseEntity<?> deleteDimProducto(@PathVariable String id) {
        try {
            service.deleteProducto(id);
            return ResponseEntity.ok(Map.of("success", true, "mensaje", "Producto eliminado"));
        } catch (Exception e) { return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage())); }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DIM_USUARIO
    // ══════════════════════════════════════════════════════════════════════════

    @GetMapping("/dim-usuario")
    public ResponseEntity<?> getDimUsuario(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        try { return ResponseEntity.ok(service.getUsuarios(page, size)); }
        catch (Exception e) { return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage())); }
    }

    @DeleteMapping("/dim-usuario/{id}")
    public ResponseEntity<?> deleteDimUsuario(@PathVariable String id) {
        try {
            service.deleteUsuario(id);
            return ResponseEntity.ok(Map.of("success", true, "mensaje", "Usuario eliminado"));
        } catch (Exception e) { return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage())); }
    }
}
