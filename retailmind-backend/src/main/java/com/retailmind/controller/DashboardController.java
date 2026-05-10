package com.retailmind.controller;

import java.time.Instant;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.retailmind.dto.DashboardResumenDTO;
import com.retailmind.service.DashboardService;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    /** GET /api/dashboard/resumen */
    @GetMapping("/resumen")
    public ResponseEntity<DashboardResumenDTO> getResumen() {
        return ResponseEntity.ok(dashboardService.getResumen());
    }

    /** POST /api/dashboard/refrescar-vistas */
    @PostMapping("/refrescar-vistas")
    public ResponseEntity<Map<String, Object>> refrescarVistas() {
        long inicio = Instant.now().toEpochMilli();
        String resultado = dashboardService.refrescarVistas();
        long duracion = Instant.now().toEpochMilli() - inicio;

        boolean ok = !resultado.startsWith("Error");
        return ResponseEntity.ok(Map.of(
                "success", ok,
                "mensaje", resultado,
                "duracionMs", duracion
        ));
    }
}
