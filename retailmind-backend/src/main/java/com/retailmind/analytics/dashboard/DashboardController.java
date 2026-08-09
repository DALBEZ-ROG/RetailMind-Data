package com.retailmind.analytics.dashboard;

import java.time.Instant;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.retailmind.dto.DashboardResumenDTO;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;
    private final DashboardSeriesService seriesService;

    public DashboardController(DashboardService dashboardService,
                               DashboardSeriesService seriesService) {
        this.dashboardService = dashboardService;
        this.seriesService = seriesService;
    }

    /** GET /api/dashboard/resumen */
    @GetMapping("/resumen")
    public ResponseEntity<DashboardResumenDTO> getResumen() {
        return ResponseEntity.ok(dashboardService.getResumen());
    }

    /**
     * GET /api/dashboard/series
     *
     * Series y desgloses para los gráficos, en UNA petición y de SOLO LECTURA.
     * Va aparte de `/resumen` a propósito: aquel contrato lo consumen otras
     * pantallas y no se toca. Lo que aquí se añade es la dimensión TEMPORAL
     * —28 semanas hoy— que la pantalla no tenía, más los desgloses medidos en
     * EVENTOS (ver el javadoc de {@link DashboardSeriesService} para por qué
     * no en sesiones).
     */
    @GetMapping("/series")
    public ResponseEntity<Map<String, Object>> getSeries() {
        return ResponseEntity.ok(seriesService.getSeries());
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
