package com.retailmind.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.retailmind.dto.GrupoConteoDTO;
import com.retailmind.service.SesionService;

@RestController
@RequestMapping("/api/sesiones")
public class SesionController {

    private final SesionService sesionService;

    public SesionController(SesionService sesionService) {
        this.sesionService = sesionService;
    }

    /**
     * GET /api/sesiones?page=0&size=20
     * Retorna sesiones paginadas desde ClickHouse (fact_eventos agrupado por session_id).
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> findAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(sesionService.findAll(page, size));
    }

    /**
     * GET /api/sesiones/usuario/{userId}?page=0&size=20
     */
    @GetMapping("/usuario/{userId}")
    public ResponseEntity<Map<String, Object>> findByUsuario(
            @PathVariable String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(sesionService.findByUsuario(userId, page, size));
    }

    /**
     * GET /api/sesiones/canal/{channel}?page=0&size=20
     */
    @GetMapping("/canal/{channel}")
    public ResponseEntity<Map<String, Object>> findByCanal(
            @PathVariable String channel,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(sesionService.findByCanal(channel, page, size));
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
