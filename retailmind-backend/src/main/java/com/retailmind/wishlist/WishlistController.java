package com.retailmind.wishlist;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Wishlist del cliente autenticado (PostgreSQL, RLS por app.cliente_id).
 * Acceso: solo CLIENTE (SecurityConfig).
 */
@RestController
@RequestMapping("/api/wishlist")
public class WishlistController {

    public record ItemReq(Long varianteId) {}

    private final WishlistService service;

    public WishlistController(WishlistService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> getWishlist() {
        return service.getItems();
    }

    @PostMapping("/items")
    public ResponseEntity<?> agregar(@RequestBody ItemReq r) {
        if (r.varianteId() == null) {
            throw new IllegalArgumentException("varianteId es requerido");
        }
        service.agregar(r.varianteId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("success", true, "mensaje", "Agregado a wishlist"));
    }

    @DeleteMapping("/items/{varianteId}")
    public Map<String, Object> eliminar(@PathVariable long varianteId) {
        service.eliminar(varianteId);
        return Map.of("success", true, "mensaje", "Eliminado de wishlist");
    }
}
