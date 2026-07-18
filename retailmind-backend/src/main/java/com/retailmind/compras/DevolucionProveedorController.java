package com.retailmind.compras;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Devolución a proveedor (script 45). Bajo /api/compras (ADMIN/GERENTE/
 * COMPRAS/BODEGA); SecurityConfig afina: marcar defectuoso = BODEGA,
 * gestionar la devolución = COMPRAS. La BD refuerza con GRANTs + RLS.
 */
@RestController
@RequestMapping("/api/compras")
public class DevolucionProveedorController {

    public record MarcarDefectuosoReq(int cantidad, String nota) {}
    public record AsignarProveedorReq(long proveedorId) {}
    public record CrearReq(long proveedorId, List<Long> itemIds, String observacion) {}
    public record NotaReq(String nota) {}
    public record ResolverReq(String tipoResolucion, String nota) {}

    private final DevolucionProveedorService servicio;

    public DevolucionProveedorController(DevolucionProveedorService servicio) {
        this.servicio = servicio;
    }

    // Pool de ítems defectuosos (BODEGA los identifica, COMPRAS los agrupa)
    @GetMapping("/items-defectuosos")
    public List<Map<String, Object>> items(@RequestParam(required = false) String estado) {
        return servicio.listarItems(estado);
    }

    /** Líneas de recepciones confirmadas (referencia del marcado posterior). */
    @GetMapping("/recepciones/detalles-ref")
    public List<Map<String, Object>> detallesRecepcion() {
        return servicio.listarDetallesRecepcion();
    }

    /** BODEGA: unidades ya recibidas que resultaron defectuosas (salen de stock). */
    @PostMapping("/recepciones/detalles/{id}/defectuoso")
    public ResponseEntity<?> marcarDefectuoso(@PathVariable long id,
                                              @RequestBody MarcarDefectuosoReq r) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(servicio.marcarDefectuosoRecepcion(id, r.cantidad(), r.nota()));
    }

    /** COMPRAS: fija el proveedor de un ítem RMA no rastreable. */
    @PatchMapping("/items-defectuosos/{id}/proveedor")
    public Map<String, Object> asignarProveedor(@PathVariable long id,
                                                @RequestBody AsignarProveedorReq r) {
        return servicio.asignarProveedor(id, r.proveedorId());
    }

    // Devoluciones a proveedor
    @PostMapping("/devoluciones-proveedor")
    public ResponseEntity<?> crear(@RequestBody CrearReq r) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(servicio.crear(r.proveedorId(), r.itemIds(), r.observacion()));
    }

    @GetMapping("/devoluciones-proveedor")
    public List<Map<String, Object>> listar(@RequestParam(required = false) String estado) {
        return servicio.listar(estado);
    }

    @GetMapping("/devoluciones-proveedor/{id}")
    public Map<String, Object> obtener(@PathVariable long id) {
        return servicio.obtener(id);
    }

    @PostMapping("/devoluciones-proveedor/{id}/enviar")
    public Map<String, Object> enviar(@PathVariable long id, @RequestBody(required = false) NotaReq r) {
        return servicio.enviar(id, r != null ? r.nota() : null);
    }

    @PostMapping("/devoluciones-proveedor/{id}/resolver")
    public Map<String, Object> resolver(@PathVariable long id, @RequestBody ResolverReq r) {
        return servicio.resolver(id, r.tipoResolucion(), r.nota());
    }

    @PostMapping("/devoluciones-proveedor/{id}/cerrar")
    public Map<String, Object> cerrar(@PathVariable long id, @RequestBody(required = false) NotaReq r) {
        return servicio.cerrar(id, r != null ? r.nota() : null);
    }
}
