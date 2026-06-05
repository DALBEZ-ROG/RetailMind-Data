package com.retailmind.analytics.trafico;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/analytics/trafico")
public class TraficoController {

    private final TraficoService traficoService;

    public TraficoController(TraficoService traficoService) {
        this.traficoService = traficoService;
    }

    @GetMapping("/resumen")
    public ResponseEntity<?> getResumen(
            @RequestParam(required = false) Integer semana) {
        try {
            return ResponseEntity.ok(traficoService.getResumen(semana));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/embudo-por-canal")
    public ResponseEntity<?> getEmbudoPorCanal(
            @RequestParam(required = false) Integer semana) {
        try {
            return ResponseEntity.ok(traficoService.getEmbudoPorCanal(semana));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }
}
