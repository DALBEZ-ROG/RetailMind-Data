package com.retailmind.resenas;

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
 * Reseñas y preguntas de producto (resena, resena_util, reporte_resena,
 * pregunta_producto, respuesta_pregunta).
 * SecurityConfig: el CLIENTE crea reseñas/preguntas, vota y reporta; el
 * listado público por producto es para ADMIN/GERENTE/CLIENTE; la moderación
 * (estados, respuestas, bandejas) es solo ADMIN/GERENTE. El autor sale del JWT.
 */
@RestController
@RequestMapping("/api/resenas")
public class ResenasController {

    public record ResenaReq(Long productoId, Integer calificacion, String titulo,
                            String comentario) {}
    public record EstadoReq(String estado) {}
    public record VotoReq(Boolean esUtil) {}
    public record ReporteReq(String motivo, String comentario) {}
    public record PreguntaReq(Long productoId, String pregunta) {}
    public record RespuestaReq(String respuesta) {}

    private final ResenasService servicio;

    public ResenasController(ResenasService servicio) {
        this.servicio = servicio;
    }

    // ── Reseñas ──────────────────────────────────────────────────────────

    /** Bandeja de moderación (personal), con filtro opcional por estado. */
    @GetMapping
    public List<Map<String, Object>> resenas(
            @RequestParam(required = false) String estado) {
        return servicio.listarResenas(estado);
    }

    /** Listado público: solo reseñas aprobadas del producto. */
    @GetMapping("/producto/{productoId}")
    public List<Map<String, Object>> resenasProducto(@PathVariable long productoId) {
        return servicio.listarResenasProducto(productoId);
    }

    /** Reseñas propias del cliente autenticado (cualquier estado). */
    @GetMapping("/mias")
    public List<Map<String, Object>> misResenas() {
        return servicio.listarMisResenas();
    }

    @PostMapping
    public ResponseEntity<?> crearResena(@RequestBody ResenaReq r) {
        long id = servicio.crearResena(r.productoId(), r.calificacion(), r.titulo(),
                r.comentario());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }

    @PatchMapping("/{id}/estado")
    public ResponseEntity<?> moderarResena(@PathVariable long id, @RequestBody EstadoReq r) {
        servicio.moderarResena(id, r.estado());
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ── Votos de utilidad ────────────────────────────────────────────────

    @PostMapping("/{id}/voto")
    public ResponseEntity<?> votar(@PathVariable long id, @RequestBody VotoReq r) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(servicio.votarResena(id, r.esUtil()));
    }

    // ── Reportes de reseña ───────────────────────────────────────────────

    @PostMapping("/{id}/reporte")
    public ResponseEntity<?> reportar(@PathVariable long id, @RequestBody ReporteReq r) {
        long reporteId = servicio.reportarResena(id, r.motivo(), r.comentario());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", reporteId));
    }

    /** Bandeja de reportes (personal), con filtro opcional por estado. */
    @GetMapping("/reportes")
    public List<Map<String, Object>> reportes(
            @RequestParam(required = false) String estado) {
        return servicio.listarReportes(estado);
    }

    @PatchMapping("/reportes/{id}/estado")
    public ResponseEntity<?> resolverReporte(@PathVariable long id, @RequestBody EstadoReq r) {
        servicio.resolverReporte(id, r.estado());
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ── Preguntas y respuestas ───────────────────────────────────────────

    /** Bandeja de preguntas (personal), con filtro opcional por estado. */
    @GetMapping("/preguntas")
    public List<Map<String, Object>> preguntas(
            @RequestParam(required = false) String estado) {
        return servicio.listarPreguntas(estado);
    }

    /** Preguntas del producto con sus respuestas (cliente: publicadas + propias). */
    @GetMapping("/preguntas/producto/{productoId}")
    public List<Map<String, Object>> preguntasProducto(@PathVariable long productoId) {
        return servicio.listarPreguntasProducto(productoId);
    }

    @PostMapping("/preguntas")
    public ResponseEntity<?> crearPregunta(@RequestBody PreguntaReq r) {
        long id = servicio.crearPregunta(r.productoId(), r.pregunta());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }

    @PostMapping("/preguntas/{id}/respuestas")
    public ResponseEntity<?> responder(@PathVariable long id, @RequestBody RespuestaReq r) {
        long respuestaId = servicio.responderPregunta(id, r.respuesta());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", respuestaId));
    }

    @PatchMapping("/preguntas/{id}/estado")
    public ResponseEntity<?> moderarPregunta(@PathVariable long id, @RequestBody EstadoReq r) {
        servicio.moderarPregunta(id, r.estado());
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ── Referencias ──────────────────────────────────────────────────────

    /** Productos activos para los selectores de reseña/pregunta. */
    @GetMapping("/productos-ref")
    public List<Map<String, Object>> productosRef() {
        return servicio.listarProductosRef();
    }
}
