package com.retailmind.ventas;

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
 * Ciclo de venta. Acceso: ADMIN / GERENTE / VENDEDOR / DESPACHO / CLIENTE
 * (SecurityConfig); la BD afina con SET LOCAL ROLE + horario + RLS (el
 * cliente solo ve/opera SUS pedidos por app.cliente_id).
 */
@RestController
@RequestMapping("/api/ventas")
public class VentasController {

    public record PedidoReq(long clienteId, long bodegaId, String canal,
                            List<VentasService.ItemPedido> items) {}
    public record DespachoReq(long transportistaId, long metodoEnvioId,
                              Long bodegaId, String observacion) {}
    public record DevolucionReq(String motivoCodigo, long bodegaId, String descripcion,
                                List<VentasService.ItemDevolucion> items) {}

    private final VentasService servicio;
    private final FacturaVentaPdfService pdfService;

    public VentasController(VentasService servicio, FacturaVentaPdfService pdfService) {
        this.servicio = servicio;
        this.pdfService = pdfService;
    }

    // Caso 7: pedidos
    @PostMapping("/pedidos")
    public ResponseEntity<?> crearPedido(@RequestBody PedidoReq r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                servicio.crearPedido(r.clienteId(), r.bodegaId(), r.canal(), r.items()));
    }

    @GetMapping("/pedidos")
    public List<Map<String, Object>> pedidos() { return servicio.listarPedidos(); }

    @GetMapping("/pedidos/{id}")
    public Map<String, Object> pedido(@PathVariable long id) { return servicio.obtenerPedido(id); }

    // Caso 8: factura de venta + PDF
    @PostMapping("/pedidos/{id}/factura")
    public ResponseEntity<?> facturar(@PathVariable long id) {
        return ResponseEntity.status(HttpStatus.CREATED).body(servicio.emitirFactura(id));
    }

    @GetMapping("/facturas/{id}")
    public Map<String, Object> factura(@PathVariable long id) { return servicio.obtenerFactura(id); }

    @GetMapping("/facturas/{id}/pdf")
    public ResponseEntity<byte[]> facturaPdf(@PathVariable long id) {
        byte[] pdf = pdfService.generarPdf(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=factura-venta-" + id + ".pdf")
                .body(pdf);
    }

    // Caso 9: despacho
    @PostMapping("/pedidos/{id}/despacho")
    public ResponseEntity<?> despachar(@PathVariable long id, @RequestBody DespachoReq r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                servicio.despachar(id, r.transportistaId(), r.metodoEnvioId(),
                        r.bodegaId(), r.observacion()));
    }

    @GetMapping("/envios/{id}")
    public Map<String, Object> envio(@PathVariable long id) { return servicio.obtenerEnvio(id); }

    @GetMapping("/envios/{id}/seguimiento")
    public List<Map<String, Object>> seguimiento(@PathVariable long id) {
        return servicio.seguimiento(id);
    }

    // Caso 10: devolución
    @PostMapping("/pedidos/{id}/devolucion")
    public ResponseEntity<?> devolver(@PathVariable long id, @RequestBody DevolucionReq r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                servicio.procesarDevolucion(id, r.motivoCodigo(), r.bodegaId(),
                        r.descripcion(), r.items()));
    }

    @GetMapping("/devoluciones/{id}")
    public Map<String, Object> devolucion(@PathVariable long id) {
        return servicio.obtenerDevolucion(id);
    }
}
