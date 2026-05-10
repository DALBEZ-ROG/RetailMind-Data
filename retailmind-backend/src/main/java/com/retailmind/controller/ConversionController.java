package com.retailmind.controller;

import com.retailmind.dto.TasaSemanaDTO;
import com.retailmind.entity.Conversion;
import com.retailmind.service.ConversionService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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
    public ResponseEntity<Conversion> findById(@PathVariable Integer id) {
        return conversionService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/resumen")
    public ResponseEntity<Map<String, Long>> getResumen() {
        return ResponseEntity.ok(conversionService.getResumen());
    }

    @GetMapping("/tasa-por-semana")
    public ResponseEntity<List<TasaSemanaDTO>> tasaPorSemana() {
        return ResponseEntity.ok(conversionService.getTasaPorSemana());
    }
}
