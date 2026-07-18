package com.retailmind.carrito;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Carrito del cliente autenticado (PostgreSQL, RLS por app.cliente_id).
 * Acceso: solo CLIENTE (SecurityConfig). El usuario sale del JWT: no se
 * aceptan ids de usuario por URL.
 */
@RestController
@RequestMapping("/api/carrito")
public class CarritoController {

    public record ItemReq(Long varianteId, Integer cantidad) {}

    private final CarritoService service;

    public CarritoController(CarritoService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> getCarrito() {
        return service.getItems();
    }

    @PostMapping("/items")
    public ResponseEntity<?> agregar(@RequestBody ItemReq r) {
        if (r.varianteId() == null) {
            throw new IllegalArgumentException("varianteId es requerido");
        }
        service.agregarItem(r.varianteId(), r.cantidad() != null ? r.cantidad() : 1);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("success", true, "mensaje", "Producto agregado al carrito"));
    }

    @PatchMapping("/items/{varianteId}")
    public Map<String, Object> cambiarCantidad(@PathVariable long varianteId,
                                               @RequestBody ItemReq r) {
        if (r.cantidad() == null) {
            throw new IllegalArgumentException("cantidad es requerida");
        }
        service.cambiarCantidad(varianteId, r.cantidad());
        return Map.of("success", true);
    }

    @DeleteMapping("/items/{varianteId}")
    public Map<String, Object> eliminar(@PathVariable long varianteId) {
        service.eliminarItem(varianteId);
        return Map.of("success", true, "mensaje", "Producto eliminado del carrito");
    }

    /** Métodos de pago que ofrece el checkout online (pago simulado). */
    @GetMapping("/checkout/metodos")
    public List<Map<String, Object>> metodosCheckout() {
        return service.metodosCheckout();
    }

    /**
     * Valida un código de cupón contra el carrito actual (script 40): responde
     * {valido, motivo?, descuento?} sin aplicar nada. La aplicación real
     * ocurre al confirmar el checkout, recalculada en backend.
     */
    @PostMapping("/cupon/validar")
    public Map<String, Object> validarCupon(@RequestBody Map<String, String> body) {
        return service.validarCupon(body != null ? body.get("codigo") : null);
    }

    /**
     * Checkout online completo: dirección + método de pago (tarjeta simulada
     * o transferencia) + cupón (validado y aplicado en backend, con su uso
     * registrado en uso_cupon). El pedido nace PAGADO.
     */
    @PostMapping("/checkout")
    public ResponseEntity<?> checkout(@RequestBody CarritoService.CheckoutReq req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.checkout(req));
    }
}
