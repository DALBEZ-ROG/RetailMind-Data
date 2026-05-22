package com.retailmind.analytics.conversiones;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.retailmind.dto.TasaSemanaDTO;

@RestController
@RequestMapping("/api/conversiones")
public class ConversionController {

    private final ConversionService conversionService;

    public ConversionController(ConversionService conversionService) {
        this.conversionService = conversionService;
    }

    /**
     * GET /api/conversiones?page=0&size=20
     * Retorna conversiones paginadas desde ClickHouse (fact_eventos con is_conversion=1 o drop_off_flag=1).
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> findAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(conversionService.findAll(page, size));
    }

    /**
     * GET /api/conversiones/resumen
     * Retorna conteo de conversiones vs no-conversiones.
     */
    @GetMapping("/resumen")
    public ResponseEntity<Map<String, Long>> getResumen() {
        return ResponseEntity.ok(conversionService.getResumen());
    }

    /**
     * GET /api/conversiones/tasa-por-semana
     * Retorna tasa de conversión agrupada por semana.
     */
    @GetMapping("/tasa-por-semana")
    public ResponseEntity<List<TasaSemanaDTO>> tasaPorSemana() {
        return ResponseEntity.ok(conversionService.getTasaPorSemana());
    }
}
