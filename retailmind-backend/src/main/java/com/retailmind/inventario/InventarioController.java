package com.retailmind.inventario;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inventario: transferencias (ADMIN/GERENTE/BODEGA), ajustes (ADMIN/BODEGA)
 * y kardex de solo lectura (ADMIN/GERENTE/BODEGA/ANALISTA). Los roles por
 * ruta los aplica SecurityConfig; la BD afina via SET LOCAL ROLE.
 */
@RestController
@RequestMapping("/api/inventario")
public class InventarioController {

    public record TransferenciaReq(long varianteId, long bodegaOrigenId,
                                   long bodegaDestinoId, int cantidad, String observacion) {}
    public record AjusteReq(long varianteId, long bodegaId, String tipo,
                            int cantidad, String motivo) {}
    public record AnularAjusteReq(String motivo) {}
    public record NivelesReq(long varianteId, long bodegaId,
                             int stockMinimo, Integer stockMaximo) {}

    private final InventarioService servicio;

    public InventarioController(InventarioService servicio) {
        this.servicio = servicio;
    }

    @PostMapping("/transferencias")
    public ResponseEntity<?> transferir(@RequestBody TransferenciaReq r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                servicio.transferir(r.varianteId(), r.bodegaOrigenId(),
                        r.bodegaDestinoId(), r.cantidad(), r.observacion()));
    }

    @GetMapping("/transferencias")
    public List<Map<String, Object>> transferencias() {
        return servicio.listarTransferencias();
    }

    // CU-O-16: ajuste manual de stock (solo ADMIN/BODEGA)
    @PostMapping("/ajustes")
    public ResponseEntity<?> ajustar(@RequestBody AjusteReq r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                servicio.registrarAjuste(r.varianteId(), r.bodegaId(),
                        r.tipo(), r.cantidad(), r.motivo()));
    }

    @GetMapping("/ajustes")
    public List<Map<String, Object>> ajustes() { return servicio.listarAjustes(); }

    /** Anula un ajuste aplicado revirtiendo su movimiento de kardex. */
    @PostMapping("/ajustes/{id}/anular")
    public ResponseEntity<?> anularAjuste(@PathVariable long id,
                                          @RequestBody AnularAjusteReq r) {
        return ResponseEntity.ok(servicio.anularAjuste(id, r.motivo()));
    }

    // Cierre de brechas OTD-INV-08: niveles mín/máx por variante y bodega
    @PutMapping("/niveles")
    public Map<String, Object> actualizarNiveles(@RequestBody NivelesReq r) {
        return servicio.actualizarNiveles(r.varianteId(), r.bodegaId(),
                r.stockMinimo(), r.stockMaximo());
    }

    // CU-O-17: kardex de solo lectura, filtrable por variante y/o bodega
    @GetMapping("/kardex")
    public List<Map<String, Object>> kardex(@RequestParam(required = false) Long varianteId,
                                            @RequestParam(required = false) Long bodegaId) {
        return servicio.kardex(varianteId, bodegaId);
    }
}
