package com.retailmind.recomendaciones;

import java.util.List;
import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Recomendaciones para el cliente autenticado. La señal viene de ClickHouse
 * (eventos) y los productos de PostgreSQL; si ClickHouse está apagado, se
 * degrada a destacados del catálogo sin romper la tienda.
 */
@RestController
@RequestMapping("/api/recomendaciones")
public class RecomendacionesController {

    private final RecomendacionesService service;

    public RecomendacionesController(RecomendacionesService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> getRecomendaciones() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return service.getRecomendaciones(auth != null ? auth.getName() : "anonymous");
    }

    @GetMapping("/similares/{varianteId}")
    public List<Map<String, Object>> getSimilares(@PathVariable long varianteId) {
        return service.getSimilares(varianteId);
    }
}
