package com.retailmind.controller;

import com.retailmind.entity.Sesion;
import com.retailmind.service.SesionService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/sesiones")
@RequiredArgsConstructor
public class SesionController {

    private final SesionService sesionService;

    /** GET /api/sesiones?page=0&size=20 */
    @GetMapping
    public ResponseEntity<Page<Sesion>> findAll(
            @PageableDefault(size = 20, sort = "sessionId") Pageable pageable) {
        return ResponseEntity.ok(sesionService.findAll(pageable));
    }

    /** GET /api/sesiones/{id} */
    @GetMapping("/{id}")
    public ResponseEntity<Sesion> findById(@PathVariable String id) {
        return sesionService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** GET /api/sesiones/usuario/{userId}?page=0&size=20 */
    @GetMapping("/usuario/{userId}")
    public ResponseEntity<Page<Sesion>> findByUsuario(
            @PathVariable String userId,
            @PageableDefault(size = 20, sort = "timestampUtc") Pageable pageable) {
        return ResponseEntity.ok(sesionService.findByUsuario(userId, pageable));
    }

    /** GET /api/sesiones/canal/{canalId}?page=0&size=20 */
    @GetMapping("/canal/{canalId}")
    public ResponseEntity<Page<Sesion>> findByCanal(
            @PathVariable Integer canalId,
            @PageableDefault(size = 20, sort = "timestampUtc") Pageable pageable) {
        return ResponseEntity.ok(sesionService.findByCanal(canalId, pageable));
    }
}
