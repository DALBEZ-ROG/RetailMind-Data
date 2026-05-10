package com.retailmind.controller;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.retailmind.dto.GrupoConteoDTO;
import com.retailmind.entity.Sesion;
import com.retailmind.service.SesionService;

@RestController
@RequestMapping("/api/sesiones")
public class SesionController {

    private final SesionService sesionService;

    public SesionController(SesionService sesionService) {
        this.sesionService = sesionService;
    }

    @GetMapping
    public ResponseEntity<Page<Sesion>> findAll(
            @PageableDefault(size = 20, sort = "sessionId") Pageable pageable) {
        return ResponseEntity.ok(sesionService.findAll(pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Sesion> findById(@PathVariable String id) {
        return sesionService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/usuario/{userId}")
    public ResponseEntity<Page<Sesion>> findByUsuario(
            @PathVariable String userId,
            @PageableDefault(size = 20, sort = "timestampUtc") Pageable pageable) {
        return ResponseEntity.ok(sesionService.findByUsuario(userId, pageable));
    }

    @GetMapping("/canal/{canalId}")
    public ResponseEntity<Page<Sesion>> findByCanal(
            @PathVariable Integer canalId,
            @PageableDefault(size = 20, sort = "timestampUtc") Pageable pageable) {
        return ResponseEntity.ok(sesionService.findByCanal(canalId, pageable));
    }

    /** GET /api/sesiones/por-canal */
    @GetMapping("/por-canal")
    public ResponseEntity<List<GrupoConteoDTO>> porCanal() {
        return ResponseEntity.ok(sesionService.countPorCanal());
    }

    /** GET /api/sesiones/por-region */
    @GetMapping("/por-region")
    public ResponseEntity<List<GrupoConteoDTO>> porRegion() {
        return ResponseEntity.ok(sesionService.countPorRegion());
    }

    /** GET /api/sesiones/por-dispositivo */
    @GetMapping("/por-dispositivo")
    public ResponseEntity<List<GrupoConteoDTO>> porDispositivo() {
        return ResponseEntity.ok(sesionService.countPorDispositivo());
    }
}
