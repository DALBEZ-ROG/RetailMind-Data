package com.retailmind.controller;

import com.retailmind.entity.Conversion;
import com.retailmind.service.ConversionService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/conversiones")
@RequiredArgsConstructor
public class ConversionController {

    private final ConversionService conversionService;

    /** GET /api/conversiones?page=0&size=20 */
    @GetMapping
    public ResponseEntity<Page<Conversion>> findAll(
            @PageableDefault(size = 20, sort = "conversionId") Pageable pageable) {
        return ResponseEntity.ok(conversionService.findAll(pageable));
    }

    /** GET /api/conversiones/{id} */
    @GetMapping("/{id}")
    public ResponseEntity<Conversion> findById(@PathVariable Long id) {
        return conversionService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/conversiones/resumen
     * Devuelve: { "conversiones": N, "noConversiones": M, "total": T }
     */
    @GetMapping("/resumen")
    public ResponseEntity<Map<String, Long>> getResumen() {
        return ResponseEntity.ok(conversionService.getResumen());
    }
}
