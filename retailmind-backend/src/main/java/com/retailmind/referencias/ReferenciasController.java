package com.retailmind.referencias;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Catálogos de referencia (solo GET) para los selects de las pantallas
 * operativas. Requiere usuario autenticado (anyRequest().authenticated());
 * los privilegios finos los aplica la BD vía SET LOCAL ROLE.
 */
@RestController
@RequestMapping("/api/referencias")
public class ReferenciasController {

    private final ReferenciasService servicio;

    public ReferenciasController(ReferenciasService servicio) {
        this.servicio = servicio;
    }

    @GetMapping("/proveedores")
    public List<Map<String, Object>> proveedores() { return servicio.proveedores(); }

    @GetMapping("/bodegas")
    public List<Map<String, Object>> bodegas() { return servicio.bodegas(); }

    @GetMapping("/clientes")
    public List<Map<String, Object>> clientes() { return servicio.clientes(); }

    @GetMapping("/transportistas")
    public List<Map<String, Object>> transportistas() { return servicio.transportistas(); }

    @GetMapping("/metodos-envio")
    public List<Map<String, Object>> metodosEnvio() { return servicio.metodosEnvio(); }

    @GetMapping("/metodos-pago")
    public List<Map<String, Object>> metodosPago() { return servicio.metodosPago(); }

    @GetMapping("/motivos-devolucion")
    public List<Map<String, Object>> motivosDevolucion() { return servicio.motivosDevolucion(); }

    @GetMapping("/variantes")
    public List<Map<String, Object>> variantes() { return servicio.variantes(); }

    @GetMapping("/stock")
    public List<Map<String, Object>> stock(@RequestParam(required = false) Long varianteId,
                                           @RequestParam(required = false) Long bodegaId) {
        return servicio.stock(varianteId, bodegaId);
    }
}
