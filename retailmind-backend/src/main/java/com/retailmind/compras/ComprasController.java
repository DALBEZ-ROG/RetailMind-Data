package com.retailmind.compras;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ciclo de compra. Acceso: ADMIN / GERENTE / COMPRAS / BODEGA (SecurityConfig);
 * la BD aplica ademas privilegios + horario + RLS via SET LOCAL ROLE, asi que
 * cada rol solo puede ejecutar los pasos que su grupo de motor permite.
 */
@RestController
@RequestMapping("/api/compras")
public class ComprasController {

    public record OrdenReq(long proveedorId, long bodegaId, Long monedaId,
                           String fechaEntregaEsperada, String observacion,
                           List<ComprasService.ItemOrden> items) {}
    public record RecepcionReq(String observacion, List<ComprasService.ItemRecepcion> items) {}
    public record PagoReq(BigDecimal monto, long metodoPagoId, String referencia) {}

    private final ComprasService servicio;
    private final FacturaCompraPdfService pdfService;

    public ComprasController(ComprasService servicio, FacturaCompraPdfService pdfService) {
        this.servicio = servicio;
        this.pdfService = pdfService;
    }

    // a) Ordenes
    @PostMapping("/ordenes")
    public ResponseEntity<?> emitirOrden(@RequestBody OrdenReq r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                servicio.emitirOrden(r.proveedorId(), r.bodegaId(), r.monedaId(),
                        r.fechaEntregaEsperada(), r.observacion(), r.items()));
    }

    @GetMapping("/ordenes")
    public List<Map<String, Object>> ordenes() { return servicio.listarOrdenes(); }

    @GetMapping("/ordenes/{id}")
    public Map<String, Object> orden(@PathVariable long id) { return servicio.obtenerOrden(id); }

    // b) Recepciones
    @PostMapping("/ordenes/{id}/recepciones")
    public ResponseEntity<?> recibir(@PathVariable long id, @RequestBody RecepcionReq r) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(servicio.registrarRecepcion(id, r.observacion(), r.items()));
    }

    // c) Facturas (el numero lo genera el sistema, no el usuario)
    @PostMapping("/ordenes/{id}/facturas")
    public ResponseEntity<?> facturar(@PathVariable long id) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(servicio.registrarFactura(id));
    }

    @GetMapping("/facturas/{id}")
    public Map<String, Object> factura(@PathVariable long id) { return servicio.obtenerFactura(id); }

    /** PDF imprimible de la factura de compra. */
    @GetMapping("/facturas/{id}/pdf")
    public ResponseEntity<byte[]> facturaPdf(@PathVariable long id) {
        byte[] pdf = pdfService.generarPdf(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=factura-compra-" + id + ".pdf")
                .body(pdf);
    }

    // d) Cuentas por pagar y pagos
    @GetMapping("/cuentas-por-pagar")
    public List<Map<String, Object>> cuentas() { return servicio.listarCuentasPorPagar(); }

    @PostMapping("/cuentas-por-pagar/{id}/pagos")
    public ResponseEntity<?> pagar(@PathVariable long id, @RequestBody PagoReq r) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(servicio.registrarPago(id, r.monto(), r.metodoPagoId(), r.referencia()));
    }
}
