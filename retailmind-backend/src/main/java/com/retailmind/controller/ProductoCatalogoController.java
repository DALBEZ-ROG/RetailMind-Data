package com.retailmind.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.retailmind.service.ProductoCatalogoService;

@RestController
@RequestMapping("/api/catalogo")
public class ProductoCatalogoController {

    private final ProductoCatalogoService service;

    public ProductoCatalogoController(ProductoCatalogoService service) {
        this.service = service;
    }

    @GetMapping("/productos")
    public ResponseEntity<?> getProductos(
            @RequestParam(required = false) Integer categoria_id,
            @RequestParam(required = false) String brand,
            @RequestParam(required = false) Float min_price,
            @RequestParam(required = false) Float max_price,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            return ResponseEntity.ok(service.getProductos(categoria_id, brand, min_price, max_price, page, size));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/productos/{productoId}")
    public ResponseEntity<?> getProductoById(@PathVariable String productoId) {
        try {
            Map<String, Object> producto = service.getProductoById(productoId);
            return producto != null ? ResponseEntity.ok(producto) : ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/categorias")
    public ResponseEntity<?> getCategorias() {
        try {
            return ResponseEntity.ok(service.getCategorias());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/marcas")
    public ResponseEntity<?> getMarcas() {
        try {
            return ResponseEntity.ok(service.getMarcas());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/eventos")
    public ResponseEntity<?> registrarEvento(@RequestBody Map<String, Object> body) {
        try {
            service.registrarEvento(
                    (String) body.get("user_id"),
                    (String) body.get("product_id"),
                    (String) body.get("user_action"),
                    (String) body.get("channel"),
                    body.get("price") != null ? ((Number) body.get("price")).floatValue() : null,
                    (String) body.get("session_id"));
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
