package com.retailmind.compras;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
                           String fechaEmision, String fechaEntregaEsperada,
                           String observacion, List<ComprasService.ItemOrden> items) {}
    public record RecepcionReq(String observacion, List<ComprasService.ItemRecepcion> items) {}
    public record PagoReq(BigDecimal monto, long metodoPagoId, String referencia) {}
    // Catálogo proveedor-producto (OTD-COM-10, script 51)
    public record ProductoProveedorReq(Long productoVarianteId, BigDecimal costo,
                                       Integer tiempoEntregaDias, Integer cantidadMinima,
                                       Boolean esPreferido, String codigoProveedor) {}
    public record ActivoReq(boolean activo) {}

    private final ComprasService servicio;
    private final FacturaCompraPdfService pdfService;
    private final ProductoProveedorService productoProveedor;

    public ComprasController(ComprasService servicio, FacturaCompraPdfService pdfService,
                             ProductoProveedorService productoProveedor) {
        this.servicio = servicio;
        this.pdfService = pdfService;
        this.productoProveedor = productoProveedor;
    }

    // a) Ordenes
    @PostMapping("/ordenes")
    public ResponseEntity<?> emitirOrden(@RequestBody OrdenReq r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                servicio.emitirOrden(r.proveedorId(), r.bodegaId(), r.monedaId(),
                        r.fechaEmision(), r.fechaEntregaEsperada(),
                        r.observacion(), r.items()));
    }

    @GetMapping("/ordenes")
    public List<Map<String, Object>> ordenes() { return servicio.listarOrdenes(); }

    @GetMapping("/ordenes/{id}")
    public Map<String, Object> orden(@PathVariable long id) { return servicio.obtenerOrden(id); }

    /** CU-O-12: aprobar orden emitida. Solo GERENTE/ADMIN (SecurityConfig). */
    @PostMapping("/ordenes/{id}/aprobar")
    public Map<String, Object> aprobar(@PathVariable long id) {
        return servicio.aprobarOrden(id);
    }

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

    // e) Catálogo proveedor-producto (OTD-COM-10, script 51). Lectura
    //    ADMIN/GERENTE/COMPRAS y escritura ADMIN/COMPRAS (SecurityConfig);
    //    BODEGA queda fuera: la tabla contiene costo (segregación financiera).
    @GetMapping("/proveedores")
    public List<Map<String, Object>> proveedores() {
        return productoProveedor.listarProveedores();
    }

    @GetMapping("/proveedores/{id}/productos")
    public List<Map<String, Object>> productosDeProveedor(@PathVariable long id) {
        return productoProveedor.listarProductosDe(id);
    }

    @PostMapping("/proveedores/{id}/productos")
    public ResponseEntity<?> asociarProducto(@PathVariable long id,
                                             @RequestBody ProductoProveedorReq r) {
        long ppId = productoProveedor.asociar(id, r.productoVarianteId(), r.costo(),
                r.tiempoEntregaDias(), r.cantidadMinima(), r.esPreferido(),
                r.codigoProveedor());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", ppId));
    }

    @PutMapping("/productos-proveedor/{id}")
    public ResponseEntity<?> editarProductoProveedor(@PathVariable long id,
                                                     @RequestBody ProductoProveedorReq r) {
        productoProveedor.editar(id, r.costo(), r.tiempoEntregaDias(),
                r.cantidadMinima(), r.esPreferido(), r.codigoProveedor());
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PatchMapping("/productos-proveedor/{id}/activo")
    public ResponseEntity<?> activarProductoProveedor(@PathVariable long id,
                                                      @RequestBody ActivoReq r) {
        productoProveedor.activar(id, r.activo());
        return ResponseEntity.ok(Map.of("success", true));
    }

    /** Buscador de producto para asociar (servidor; nunca la lista completa). */
    @GetMapping("/productos-ref")
    public List<Map<String, Object>> productosRef(@RequestParam(required = false) String q) {
        return productoProveedor.buscarProductosRef(q);
    }
}
