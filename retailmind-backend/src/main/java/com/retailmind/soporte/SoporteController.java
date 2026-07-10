package com.retailmind.soporte;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
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
 * Soporte / atención al cliente (categoría de ticket, ticket con hilo de
 * mensajes, FAQ).
 * SecurityConfig: tickets para ADMIN/GERENTE (todos) y CLIENTE (solo los
 * suyos, propiedad en el servicio); estado/asignación solo ADMIN/GERENTE;
 * gestión de categorías y FAQ solo ADMIN; lecturas de referencia
 * (categorias-ref, faqs-activas) para los roles con SELECT en la BD.
 */
@RestController
@RequestMapping("/api/soporte")
public class SoporteController {

    public record CategoriaReq(String nombre, String descripcion) {}
    public record TicketReq(Long clienteId, Long categoriaId, Long pedidoId,
                            String asunto, String descripcion, String prioridad) {}
    public record MensajeReq(String mensaje, Boolean esInterno) {}
    public record EstadoReq(String estado) {}
    public record AsignarReq(Long usuarioId) {}
    public record FaqReq(Long categoriaId, String pregunta, String respuesta, Integer orden) {}
    public record ActivoReq(boolean activo) {}

    private final SoporteService servicio;

    public SoporteController(SoporteService servicio) {
        this.servicio = servicio;
    }

    // ── Categorías de ticket ─────────────────────────────────────────────
    @GetMapping("/categorias")
    public List<Map<String, Object>> categorias() { return servicio.listarCategorias(); }

    /** Categorías activas para el selector al crear un ticket. */
    @GetMapping("/categorias-ref")
    public List<Map<String, Object>> categoriasRef() { return servicio.listarCategoriasRef(); }

    @PostMapping("/categorias")
    public ResponseEntity<?> crearCategoria(@RequestBody CategoriaReq r) {
        long id = servicio.crearCategoria(r.nombre(), r.descripcion());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }

    @PutMapping("/categorias/{id}")
    public ResponseEntity<?> editarCategoria(@PathVariable long id, @RequestBody CategoriaReq r) {
        servicio.editarCategoria(id, r.nombre(), r.descripcion());
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PatchMapping("/categorias/{id}/activo")
    public ResponseEntity<?> activarCategoria(@PathVariable long id, @RequestBody ActivoReq r) {
        servicio.activarCategoria(id, r.activo());
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ── Tickets ──────────────────────────────────────────────────────────
    @GetMapping("/tickets")
    public List<Map<String, Object>> tickets() { return servicio.listarTickets(); }

    @GetMapping("/tickets/{id}")
    public Map<String, Object> ticket(@PathVariable long id) {
        return servicio.obtenerTicket(id);
    }

    @PostMapping("/tickets")
    public ResponseEntity<?> crearTicket(@RequestBody TicketReq r) {
        Map<String, Object> creado = servicio.crearTicket(r.clienteId(), r.categoriaId(),
                r.pedidoId(), r.asunto(), r.descripcion(), r.prioridad());
        return ResponseEntity.status(HttpStatus.CREATED).body(creado);
    }

    @PostMapping("/tickets/{id}/mensajes")
    public ResponseEntity<?> responder(@PathVariable long id, @RequestBody MensajeReq r) {
        long mensajeId = servicio.agregarMensaje(id, r.mensaje(),
                Boolean.TRUE.equals(r.esInterno()));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", mensajeId));
    }

    @PatchMapping("/tickets/{id}/estado")
    public ResponseEntity<?> cambiarEstado(@PathVariable long id, @RequestBody EstadoReq r) {
        servicio.cambiarEstado(id, r.estado());
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PatchMapping("/tickets/{id}/asignar")
    public ResponseEntity<?> asignar(@PathVariable long id, @RequestBody AsignarReq r) {
        servicio.asignarAgente(id, r.usuarioId());
        return ResponseEntity.ok(Map.of("success", true));
    }

    /** Personal interno activo para el selector de asignación. */
    @GetMapping("/usuarios-ref")
    public List<Map<String, Object>> usuariosRef() { return servicio.listarUsuariosRef(); }

    /** Pedidos del cliente para el selector "pedido relacionado" del ticket. */
    @GetMapping("/pedidos-ref")
    public List<Map<String, Object>> pedidosRef(
            @RequestParam(required = false) Long clienteId) {
        return servicio.listarPedidosRef(clienteId);
    }

    // ── FAQ ──────────────────────────────────────────────────────────────
    @GetMapping("/faqs")
    public List<Map<String, Object>> faqs() { return servicio.listarFaqs(); }

    /** Centro de ayuda: solo FAQ activas, en orden. */
    @GetMapping("/faqs-activas")
    public List<Map<String, Object>> faqsActivas() { return servicio.listarFaqsActivas(); }

    @PostMapping("/faqs")
    public ResponseEntity<?> crearFaq(@RequestBody FaqReq r) {
        long id = servicio.crearFaq(r.categoriaId(), r.pregunta(), r.respuesta(), r.orden());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }

    @PutMapping("/faqs/{id}")
    public ResponseEntity<?> editarFaq(@PathVariable long id, @RequestBody FaqReq r) {
        servicio.editarFaq(id, r.categoriaId(), r.pregunta(), r.respuesta(), r.orden());
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PatchMapping("/faqs/{id}/activo")
    public ResponseEntity<?> activarFaq(@PathVariable long id, @RequestBody ActivoReq r) {
        servicio.activarFaq(id, r.activo());
        return ResponseEntity.ok(Map.of("success", true));
    }
}
