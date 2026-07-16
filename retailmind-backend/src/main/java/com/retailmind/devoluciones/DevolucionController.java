package com.retailmind.devoluciones;

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
 * RMA / logística inversa. Un endpoint por transición, cada uno con SU rol
 * (SecurityConfig; la BD refuerza con SET LOCAL ROLE + GRANTs + RLS):
 *   solicitar CLIENTE · revision/aprobar/rechazar/cerrar SOPORTE ·
 *   transito DESPACHO · recepcion DESPACHO/BODEGA · inspeccion BODEGA ·
 *   reembolso GERENTE. ADMIN acompaña todas.
 */
@RestController
@RequestMapping("/api/devoluciones")
public class DevolucionController {

    public record SolicitudReq(long pedidoId, String motivoCodigo, String descripcion,
                               List<DevolucionService.ItemSolicitud> items) {}
    public record AprobarReq(Long transportistaId, Long bodegaId) {}
    public record RechazoReq(String motivo) {}
    public record ObservacionReq(String observacion) {}
    public record InspeccionReq(Long bodegaId, List<DevolucionService.ItemInspeccion> items) {}
    public record ReembolsoReq(String metodo, String referencia) {}

    private final DevolucionService servicio;
    private final GuiaRetornoPdfService guiaPdf;

    public DevolucionController(DevolucionService servicio, GuiaRetornoPdfService guiaPdf) {
        this.servicio = servicio;
        this.guiaPdf = guiaPdf;
    }

    // ── Consultas ────────────────────────────────────────────────────────

    @GetMapping
    public List<Map<String, Object>> listar(@RequestParam(required = false) String estado) {
        return servicio.listar(estado);
    }

    @GetMapping("/motivos-ref")
    public List<Map<String, Object>> motivos() { return servicio.motivosRef(); }

    @GetMapping("/transportistas-ref")
    public List<Map<String, Object>> transportistas() { return servicio.transportistasRef(); }

    @GetMapping("/pedido/{pedidoId}/elegibilidad")
    public Map<String, Object> elegibilidad(@PathVariable long pedidoId) {
        return servicio.elegibilidad(pedidoId);
    }

    @GetMapping("/{id}")
    public Map<String, Object> detalle(@PathVariable long id) { return servicio.obtener(id); }

    @GetMapping("/{id}/guia-pdf")
    public ResponseEntity<byte[]> guiaPdf(@PathVariable long id) {
        byte[] pdf = guiaPdf.generarPdf(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=guia-retorno-" + id + ".pdf")
                .body(pdf);
    }

    // ── Transiciones (una por rol) ───────────────────────────────────────

    /** CLIENTE solicita desde Mis Pedidos; crea/engancha ticket 'Devolución'. */
    @PostMapping
    public ResponseEntity<?> solicitar(@RequestBody SolicitudReq r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                servicio.solicitar(r.pedidoId(), r.motivoCodigo(), r.descripcion(), r.items()));
    }

    /** SOPORTE toma el caso (solicitada → en_revision). */
    @PostMapping("/{id}/revision")
    public Map<String, Object> revision(@PathVariable long id) {
        return servicio.iniciarRevision(id);
    }

    /** SOPORTE aprueba y genera la guía de retorno. */
    @PostMapping("/{id}/aprobar")
    public Map<String, Object> aprobar(@PathVariable long id,
                                       @RequestBody(required = false) AprobarReq r) {
        return servicio.aprobar(id, r != null ? r.transportistaId() : null,
                r != null ? r.bodegaId() : null);
    }

    /** SOPORTE rechaza con motivo (terminal; ticket resuelto). */
    @PostMapping("/{id}/rechazar")
    public Map<String, Object> rechazar(@PathVariable long id, @RequestBody RechazoReq r) {
        return servicio.rechazar(id, r.motivo());
    }

    /** DESPACHO: paquete en camino al almacén (logística inversa). */
    @PostMapping("/{id}/transito")
    public Map<String, Object> transito(@PathVariable long id,
                                        @RequestBody(required = false) ObservacionReq r) {
        return servicio.marcarTransito(id, r != null ? r.observacion() : null);
    }

    /** DESPACHO/BODEGA: paquete recibido en el almacén. */
    @PostMapping("/{id}/recepcion")
    public Map<String, Object> recepcion(@PathVariable long id,
                                         @RequestBody(required = false) ObservacionReq r) {
        return servicio.marcarRecibida(id, r != null ? r.observacion() : null);
    }

    /** BODEGA: inspección por ítem — SOLO aquí reingresa stock (lo apto). */
    @PostMapping("/{id}/inspeccion")
    public Map<String, Object> inspeccion(@PathVariable long id, @RequestBody InspeccionReq r) {
        return servicio.inspeccionar(id, r.bodegaId(), r.items());
    }

    /** GERENTE/ADMIN: reembolso simulado según inspección. */
    @PostMapping("/{id}/reembolso")
    public Map<String, Object> reembolso(@PathVariable long id, @RequestBody ReembolsoReq r) {
        return servicio.reembolsar(id, r.metodo(), r.referencia());
    }

    /** SOPORTE/ADMIN: cierra el caso y resuelve el ticket. */
    @PostMapping("/{id}/cerrar")
    public Map<String, Object> cerrar(@PathVariable long id) {
        return servicio.cerrar(id);
    }
}
