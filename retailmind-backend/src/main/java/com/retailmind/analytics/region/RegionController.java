package com.retailmind.analytics.region;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/analytics/region")
public class RegionController {

    private final RegionService regionService;

    public RegionController(RegionService regionService) {
        this.regionService = regionService;
    }

    @GetMapping("/resumen")
    public ResponseEntity<?> getResumen(
            @RequestParam(required = false) Integer semana,
            @RequestParam(required = false) String canal) {
        try {
            return ResponseEntity.ok(regionService.getResumen(semana, canal));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/top-productos/{regionNombre}")
    public ResponseEntity<?> getTopProductos(@PathVariable String regionNombre) {
        try {
            return ResponseEntity.ok(regionService.getTopProductos(regionNombre));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }
}
