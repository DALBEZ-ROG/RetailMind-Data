package com.retailmind.recomendaciones;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/recomendaciones")
public class RecomendacionesController {

    private final RecomendacionesService recomendacionesService;

    public RecomendacionesController(RecomendacionesService recomendacionesService) {
        this.recomendacionesService = recomendacionesService;
    }

    /** GET /api/recomendaciones/{username} */
    @GetMapping("/{username}")
    public ResponseEntity<?> getRecomendaciones(@PathVariable String username) {
        try {
            return ResponseEntity.ok(recomendacionesService.getRecomendaciones(username));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Error al obtener recomendaciones: " + e.getMessage()));
        }
    }

    /** GET /api/recomendaciones/{username}/similares/{productoId} */
    @GetMapping("/{username}/similares/{productoId}")
    public ResponseEntity<?> getSimilares(@PathVariable String username,
                                           @PathVariable String productoId) {
        try {
            return ResponseEntity.ok(recomendacionesService.getSimilares(username, productoId));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Error al obtener similares: " + e.getMessage()));
        }
    }
}
