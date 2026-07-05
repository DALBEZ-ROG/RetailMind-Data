package com.retailmind.inventario;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Transferencias entre bodegas. Acceso: ADMIN / GERENTE / BODEGA. */
@RestController
@RequestMapping("/api/inventario")
public class InventarioController {

    public record TransferenciaReq(long varianteId, long bodegaOrigenId,
                                   long bodegaDestinoId, int cantidad, String observacion) {}

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
}
