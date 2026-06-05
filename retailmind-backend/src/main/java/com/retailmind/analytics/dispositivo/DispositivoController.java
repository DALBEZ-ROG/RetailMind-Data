package com.retailmind.analytics.dispositivo;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/analytics/dispositivo")
public class DispositivoController {

    private final DispositivoService dispositivoService;

    public DispositivoController(DispositivoService dispositivoService) {
        this.dispositivoService = dispositivoService;
    }

    @GetMapping("/resumen")
    public ResponseEntity<?> getResumen(
            @RequestParam(required = false) Integer semana,
            @RequestParam(required = false) String canal) {
        try {
            return ResponseEntity.ok(dispositivoService.getResumen(semana, canal));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/tendencia")
    public ResponseEntity<?> getTendencia(
            @RequestParam(required = false) Integer semana_inicio,
            @RequestParam(required = false) Integer semana_fin) {
        try {
            return ResponseEntity.ok(dispositivoService.getTendencia(semana_inicio, semana_fin));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }
}
