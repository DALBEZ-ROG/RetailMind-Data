package com.retailmind.controller;

import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.retailmind.dto.TasaSemanaDTO;
import com.retailmind.entity.Conversion;
import com.retailmind.service.ConversionService;

@RestController
@RequestMapping("/api/conversiones")
public class ConversionController {

    private final ConversionService conversionService;

    public ConversionController(ConversionService conversionService) {
        this.conversionService = conversionService;
    }

    @GetMapping
    public ResponseEntity<Page<Conversion>> findAll(
            @PageableDefault(size = 20, sort = "conversionId") Pageable pageable) {
        return ResponseEntity.ok(conversionService.findAll(pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Conversion> findById(@PathVariable Long id) {
        return conversionService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** GET /api/conversiones/resumen */
    @GetMapping("/resumen")
    public ResponseEntity<Map<String, Long>> getResumen() {
        return ResponseEntity.ok(conversionService.getResumen());
    }

    /** GET /api/conversiones/tasa-por-semana */
    @GetMapping("/tasa-por-semana")
    public ResponseEntity<List<TasaSemanaDTO>> tasaPorSemana() {
        return ResponseEntity.ok(conversionService.getTasaPorSemana());
    }
}
