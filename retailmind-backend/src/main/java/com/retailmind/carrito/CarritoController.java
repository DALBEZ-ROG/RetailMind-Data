package com.retailmind.carrito;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/carrito")
public class CarritoController {

    private final CarritoService service;

    public CarritoController(CarritoService service) {
        this.service = service;
    }

    @PostMapping("/agregar")
    public ResponseEntity<?> agregar(@RequestBody Map<String, Object> body) {
        try {
            service.agregarItem(
                    (String) body.get("user_id"),
                    (String) body.get("producto_id"),
                    body.get("cantidad") != null ? ((Number) body.get("cantidad")).intValue() : 1);
            return ResponseEntity.ok(Map.of("success", true, "mensaje", "Producto agregado al carrito"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{userId}")
    public ResponseEntity<?> getCarrito(@PathVariable String userId) {
        try {
            return ResponseEntity.ok(service.getCarrito(userId));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{userId}/{productoId}")
    public ResponseEntity<?> eliminarItem(@PathVariable String userId, @PathVariable String productoId) {
        try {
            service.eliminarItem(userId, productoId);
            return ResponseEntity.ok(Map.of("success", true, "mensaje", "Producto eliminado del carrito"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{userId}/checkout")
    public ResponseEntity<?> checkout(@PathVariable String userId) {
        try {
            return ResponseEntity.ok(service.checkout(userId));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
