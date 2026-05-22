package com.retailmind.pedidos;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/pedidos")
public class PedidosController {

    private final PedidosService service;

    public PedidosController(PedidosService service) {
        this.service = service;
    }

    @GetMapping("/{userId}")
    public ResponseEntity<?> getPedidosUsuario(@PathVariable String userId) {
        try {
            return ResponseEntity.ok(service.getPedidosUsuario(userId));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/admin/todos")
    public ResponseEntity<?> getTodosPedidos() {
        try {
            return ResponseEntity.ok(service.getTodosLosPedidos());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
