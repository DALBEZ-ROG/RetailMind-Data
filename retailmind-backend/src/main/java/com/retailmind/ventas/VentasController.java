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
import org.springframework.web.bind.annotation.RequestParam;
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
    public record NotaReq(String nota, Boolean esVisibleCliente) {}
    public record PagoClienteReq(long metodoPagoId, java.math.BigDecimal monto, String referencia) {}
    public record EntregaReq(String observacion) {}

    private final VentasService servicio;
    private final FacturaVentaPdfService pdfService;

    public VentasController(VentasService servicio, FacturaVentaPdfService pdfService) {
        this.servicio = servicio;
        this.pdfService = pdfService;
    }

    // Caso 7: pedidos INTERNOS (venta en tienda/telefónica). El canal 'web'
    // queda reservado al checkout de la tienda online (/api/carrito/checkout),
    // que crea el pedido ya pagado.
    @PostMapping("/pedidos")
    public ResponseEntity<?> crearPedido(@RequestBody PedidoReq r) {
        String canal = r.canal() == null || r.canal().isBlank() ? "tienda" : r.canal();
        if (!List.of("tienda", "telefono").contains(canal)) {
            throw new IllegalArgumentException("Canal invalido para un pedido interno: "
                    + "usa 'tienda' o 'telefono' (el canal 'web' es exclusivo del "
                    + "checkout de la tienda online)");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(
                servicio.crearPedido(r.clienteId(), r.bodegaId(), canal, r.items()));
    }

    @GetMapping("/pedidos")
    public List<Map<String, Object>> pedidos() { return servicio.listarPedidos(); }

    @GetMapping("/pedidos/{id}")
    public Map<String, Object> pedido(@PathVariable long id) { return servicio.obtenerPedido(id); }

    // Notas / observaciones del pedido (bitácora del personal)
    @GetMapping("/pedidos/{id}/notas")
    public List<Map<String, Object>> notas(@PathVariable long id) {
        return servicio.listarNotas(id);
    }

    @PostMapping("/pedidos/{id}/notas")
    public ResponseEntity<?> crearNota(@PathVariable long id, @RequestBody NotaReq r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                servicio.crearNota(id, r.nota(), Boolean.TRUE.equals(r.esVisibleCliente())));
    }

    // Pago del cliente: compuerta previa a facturar/despachar (ADMIN/VENDEDOR)
    @PostMapping("/pedidos/{id}/pagos")
    public ResponseEntity<?> cobrar(@PathVariable long id, @RequestBody PagoClienteReq r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                servicio.registrarPago(id, r.metodoPagoId(), r.monto(), r.referencia()));
    }

    // Caso 8: factura de venta + PDF
    @PostMapping("/pedidos/{id}/factura")
    public ResponseEntity<?> facturar(@PathVariable long id) {
        return ResponseEntity.status(HttpStatus.CREATED).body(servicio.emitirFactura(id));
    }

    /** Listado de facturas de venta emitidas (búsqueda + paginación). */
    @GetMapping("/facturas")
    public Map<String, Object> facturas(@RequestParam(defaultValue = "") String q,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "25") int size) {
        return servicio.listarFacturas(q, page, size);
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

    // Entrega: cierra la logística (ADMIN/DESPACHO)
    @PostMapping("/pedidos/{id}/entrega")
    public Map<String, Object> entregar(@PathVariable long id,
                                        @RequestBody(required = false) EntregaReq r) {
        return servicio.entregar(id, r != null ? r.observacion() : null);
    }

    @GetMapping("/envios/{id}")
    public Map<String, Object> envio(@PathVariable long id) { return servicio.obtenerEnvio(id); }

    @GetMapping("/envios/{id}/seguimiento")
    public List<Map<String, Object>> seguimiento(@PathVariable long id) {
        return servicio.seguimiento(id);
    }

    // Caso 10 (devolución RMA): movido a /api/devoluciones (DevolucionController)
}
