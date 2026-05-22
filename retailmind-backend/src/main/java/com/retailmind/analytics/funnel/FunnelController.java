package com.retailmind.analytics.funnel;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/funnel")
public class FunnelController {

    private final FunnelService service;

    public FunnelController(FunnelService service) {
        this.service = service;
    }

    @GetMapping("/sesiones")
    public ResponseEntity<?> getSesiones(
            @RequestParam(required = false) Integer semana,
            @RequestParam(required = false) String canal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        try {
            return ResponseEntity.ok(service.getSesiones(semana, canal, page, size));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/resumen")
    public ResponseEntity<?> getResumen(
            @RequestParam(required = false) Integer semana,
            @RequestParam(required = false) String canal) {
        try {
            return ResponseEntity.ok(service.getResumen(semana, canal));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/semanas-disponibles")
    public ResponseEntity<?> getSemanasDisponibles() {
        try {
            return ResponseEntity.ok(service.getSemanasDisponibles());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/sesion/{sessionId}")
    public ResponseEntity<?> getDetalleSesion(@PathVariable String sessionId) {
        try {
            return ResponseEntity.ok(service.getDetalleSesion(sessionId));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
