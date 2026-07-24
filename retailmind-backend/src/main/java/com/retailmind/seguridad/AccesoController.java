package com.retailmind.seguridad;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Informe de intentos de acceso al sistema (log_acceso, OTD-GER-09, script 53).
 * SecurityConfig limita este endpoint a ADMIN/GERENTE y los GRANTs del script
 * 53 hacen lo propio en el motor. Consulta por pantalla: NO genera PDF.
 */
@RestController
@RequestMapping("/api/seguridad/accesos")
public class AccesoController {

    private final AccesoService servicio;

    public AccesoController(AccesoService servicio) {
        this.servicio = servicio;
    }

    /**
     * GET /api/seguridad/accesos — filtros por rango de fecha (desde/hasta,
     * YYYY-MM-DD), resultado (exitoso/fallido) y correo, con paginación.
     */
    @GetMapping
    public Map<String, Object> consultar(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(required = false) String resultado,
            @RequestParam(required = false) String email,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return servicio.consultar(desde, hasta, resultado, email, page, size);
    }
}
