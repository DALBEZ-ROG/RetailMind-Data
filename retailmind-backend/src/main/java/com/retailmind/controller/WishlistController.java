package com.retailmind.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.retailmind.service.WishlistService;

@RestController
@RequestMapping("/api/wishlist")
public class WishlistController {

    private final WishlistService service;

    public WishlistController(WishlistService service) {
        this.service = service;
    }

    @PostMapping("/agregar")
    public ResponseEntity<?> agregar(@RequestBody Map<String, String> body) {
        try {
            service.agregar(body.get("user_id"), body.get("producto_id"));
            return ResponseEntity.ok(Map.of("success", true, "mensaje", "Agregado a wishlist"));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{userId}")
    public ResponseEntity<?> getWishlist(@PathVariable String userId) {
        try {
            return ResponseEntity.ok(service.getWishlist(userId));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{userId}/{productoId}")
    public ResponseEntity<?> eliminar(@PathVariable String userId, @PathVariable String productoId) {
        try {
            service.eliminar(userId, productoId);
            return ResponseEntity.ok(Map.of("success", true, "mensaje", "Eliminado de wishlist"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
