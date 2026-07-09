package com.retailmind.marketing;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gestión de marketing (cupones, promociones, campañas, banners, newsletter).
 * SecurityConfig: GET para ADMIN/GERENTE (grp_gerente tiene SELECT en la BD);
 * escrituras solo ADMIN (solo grp_administrador tiene INSERT/UPDATE).
 */
@RestController
@RequestMapping("/api/marketing")
public class MarketingController {

    public record CuponReq(String codigo, String descripcion, String tipoDescuento,
                           BigDecimal valor, BigDecimal montoMinimoPedido, Integer usosMaximos,
                           Integer usosPorCliente, String fechaInicio, String fechaFin) {}
    public record PromocionReq(String nombre, String descripcion, String tipoDescuento,
                               BigDecimal valor, String fechaInicio, String fechaFin,
                               Integer prioridad, Boolean acumulable) {}
    public record CampanaReq(String nombre, String descripcion, String canal,
                             BigDecimal presupuesto, String fechaInicio, String fechaFin) {}
    public record BannerReq(String titulo, String imagenUrl, String urlDestino, String posicion,
                            Integer orden, Long campanaId, String fechaInicio, String fechaFin) {}
    public record SuscriptorReq(String email, Long clienteId) {}
    public record ActivoReq(boolean activo) {}
    public record EstadoReq(String estado) {}
    public record ProductoReq(long productoId) {}

    private final MarketingService servicio;

    public MarketingController(MarketingService servicio) {
        this.servicio = servicio;
    }

    // ── Cupones ──────────────────────────────────────────────────────────
    @GetMapping("/cupones")
    public List<Map<String, Object>> cupones() { return servicio.listarCupones(); }

    @GetMapping("/cupones/{id}/usos")
    public List<Map<String, Object>> usosCupon(@PathVariable long id) {
        return servicio.listarUsosCupon(id);
    }

    @PostMapping("/cupones")
    public ResponseEntity<?> crearCupon(@RequestBody CuponReq r) {
        long id = servicio.crearCupon(r.codigo(), r.descripcion(), r.tipoDescuento(), r.valor(),
                r.montoMinimoPedido(), r.usosMaximos(), r.usosPorCliente(),
                r.fechaInicio(), r.fechaFin());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }

    @PutMapping("/cupones/{id}")
    public ResponseEntity<?> editarCupon(@PathVariable long id, @RequestBody CuponReq r) {
        servicio.editarCupon(id, r.codigo(), r.descripcion(), r.tipoDescuento(), r.valor(),
                r.montoMinimoPedido(), r.usosMaximos(), r.usosPorCliente(),
                r.fechaInicio(), r.fechaFin());
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PatchMapping("/cupones/{id}/activo")
    public ResponseEntity<?> activarCupon(@PathVariable long id, @RequestBody ActivoReq r) {
        servicio.activarCupon(id, r.activo());
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ── Promociones ──────────────────────────────────────────────────────
    @GetMapping("/promociones")
    public List<Map<String, Object>> promociones() { return servicio.listarPromociones(); }

    @GetMapping("/promociones/{id}")
    public Map<String, Object> promocion(@PathVariable long id) {
        return servicio.obtenerPromocion(id);
    }

    @PostMapping("/promociones")
    public ResponseEntity<?> crearPromocion(@RequestBody PromocionReq r) {
        long id = servicio.crearPromocion(r.nombre(), r.descripcion(), r.tipoDescuento(),
                r.valor(), r.fechaInicio(), r.fechaFin(), r.prioridad(),
                Boolean.TRUE.equals(r.acumulable()));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }

    @PutMapping("/promociones/{id}")
    public ResponseEntity<?> editarPromocion(@PathVariable long id, @RequestBody PromocionReq r) {
        servicio.editarPromocion(id, r.nombre(), r.descripcion(), r.tipoDescuento(), r.valor(),
                r.fechaInicio(), r.fechaFin(), r.prioridad(), r.acumulable());
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PatchMapping("/promociones/{id}/activo")
    public ResponseEntity<?> activarPromocion(@PathVariable long id, @RequestBody ActivoReq r) {
        servicio.activarPromocion(id, r.activo());
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/promociones/{id}/productos")
    public ResponseEntity<?> asociarProducto(@PathVariable long id, @RequestBody ProductoReq r) {
        servicio.asociarProducto(id, r.productoId());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("success", true));
    }

    @DeleteMapping("/promociones/{id}/productos/{productoId}")
    public ResponseEntity<?> quitarProducto(@PathVariable long id, @PathVariable long productoId) {
        servicio.quitarProducto(id, productoId);
        return ResponseEntity.ok(Map.of("success", true));
    }

    /** Productos activos para el selector de asociación. */
    @GetMapping("/productos-ref")
    public List<Map<String, Object>> productosRef() { return servicio.listarProductosRef(); }

    // ── Campañas ─────────────────────────────────────────────────────────
    @GetMapping("/campanas")
    public List<Map<String, Object>> campanas() { return servicio.listarCampanas(); }

    @PostMapping("/campanas")
    public ResponseEntity<?> crearCampana(@RequestBody CampanaReq r) {
        long id = servicio.crearCampana(r.nombre(), r.descripcion(), r.canal(),
                r.presupuesto(), r.fechaInicio(), r.fechaFin());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }

    @PutMapping("/campanas/{id}")
    public ResponseEntity<?> editarCampana(@PathVariable long id, @RequestBody CampanaReq r) {
        servicio.editarCampana(id, r.nombre(), r.descripcion(), r.canal(),
                r.presupuesto(), r.fechaInicio(), r.fechaFin());
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PatchMapping("/campanas/{id}/estado")
    public ResponseEntity<?> estadoCampana(@PathVariable long id, @RequestBody EstadoReq r) {
        servicio.cambiarEstadoCampana(id, r.estado());
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ── Banners ──────────────────────────────────────────────────────────
    @GetMapping("/banners")
    public List<Map<String, Object>> banners() { return servicio.listarBanners(); }

    @PostMapping("/banners")
    public ResponseEntity<?> crearBanner(@RequestBody BannerReq r) {
        long id = servicio.crearBanner(r.titulo(), r.imagenUrl(), r.urlDestino(), r.posicion(),
                r.orden(), r.campanaId(), r.fechaInicio(), r.fechaFin());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }

    @PutMapping("/banners/{id}")
    public ResponseEntity<?> editarBanner(@PathVariable long id, @RequestBody BannerReq r) {
        servicio.editarBanner(id, r.titulo(), r.imagenUrl(), r.urlDestino(), r.posicion(),
                r.orden(), r.campanaId(), r.fechaInicio(), r.fechaFin());
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PatchMapping("/banners/{id}/activo")
    public ResponseEntity<?> activarBanner(@PathVariable long id, @RequestBody ActivoReq r) {
        servicio.activarBanner(id, r.activo());
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ── Newsletter ───────────────────────────────────────────────────────
    @GetMapping("/newsletter")
    public List<Map<String, Object>> suscriptores() { return servicio.listarSuscriptores(); }

    @PostMapping("/newsletter")
    public ResponseEntity<?> altaSuscriptor(@RequestBody SuscriptorReq r) {
        long id = servicio.altaSuscriptor(r.email(), r.clienteId());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }

    @PatchMapping("/newsletter/{id}/activo")
    public ResponseEntity<?> activarSuscriptor(@PathVariable long id, @RequestBody ActivoReq r) {
        servicio.activarSuscriptor(id, r.activo());
        return ResponseEntity.ok(Map.of("success", true));
    }
}
