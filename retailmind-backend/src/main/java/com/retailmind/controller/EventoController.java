package com.retailmind.controller;

import com.retailmind.entity.Evento;
import com.retailmind.service.EventoService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/eventos")
public class EventoController {

    private final EventoService eventoService;

    public EventoController(EventoService eventoService) {
        this.eventoService = eventoService;
    }

    @GetMapping
    public ResponseEntity<Page<Evento>> findAll(
            @PageableDefault(size = 20, sort = "eventoId") Pageable pageable) {
        return ResponseEntity.ok(eventoService.findAll(pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Evento> findById(@PathVariable Integer id) {
        return eventoService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/sesion/{sessionId}")
    public ResponseEntity<Page<Evento>> findBySession(
            @PathVariable String sessionId,
            @PageableDefault(size = 20, sort = "eventIndex") Pageable pageable) {
        return ResponseEntity.ok(eventoService.findBySession(sessionId, pageable));
    }
}
