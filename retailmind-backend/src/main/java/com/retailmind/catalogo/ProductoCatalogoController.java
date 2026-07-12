package com.retailmind.catalogo;

import java.util.Map;
import java.util.NoSuchElementException;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Catálogo de la tienda del cliente (PostgreSQL). Acceso: CLIENTE (+ADMIN
 * para demo) vía SecurityConfig; la BD afina con SET LOCAL ROLE.
 * POST /eventos sigue alimentando ClickHouse (analítica) en modo best-effort.
 */
@RestController
@RequestMapping("/api/catalogo")
public class ProductoCatalogoController {

    private final ProductoCatalogoService service;
    private final EventoTiendaService eventos;

    public ProductoCatalogoController(ProductoCatalogoService service,
                                      EventoTiendaService eventos) {
        this.service = service;
        this.eventos = eventos;
    }

    @GetMapping("/productos")
    public Map<String, Object> getProductos(
            @RequestParam(required = false) Long categoria_id,
            @RequestParam(required = false) String brand,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Double min_price,
            @RequestParam(required = false) Double max_price,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.getProductos(categoria_id, brand, q, min_price, max_price, page, size);
    }

    @GetMapping("/productos/{varianteId}")
    public Map<String, Object> getProductoById(@PathVariable long varianteId) {
        Map<String, Object> producto = service.getProductoById(varianteId);
        if (producto == null) {
            throw new NoSuchElementException(
                    "El producto " + varianteId + " no existe o no está publicado");
        }
        return producto;
    }

    @GetMapping("/categorias")
    public Object getCategorias() {
        return service.getCategorias();
    }

    @GetMapping("/marcas")
    public Object getMarcas() {
        return service.getMarcas();
    }

    /** Evento de navegación hacia ClickHouse (analítica). Best-effort. */
    @PostMapping("/eventos")
    public Map<String, Object> registrarEvento(@RequestBody Map<String, Object> body) {
        eventos.registrar(
                (String) body.get("user_id"),
                body.get("product_id") != null ? String.valueOf(body.get("product_id")) : null,
                (String) body.get("user_action"),
                (String) body.get("channel"),
                body.get("price") != null ? ((Number) body.get("price")).doubleValue() : null,
                (String) body.get("session_id"));
        return Map.of("success", true);
    }
}
