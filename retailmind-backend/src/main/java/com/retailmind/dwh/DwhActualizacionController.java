package com.retailmind.dwh;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ACTUALIZACIÓN DEL ALMACÉN desde la aplicación.
 *
 * Dos endpoints, ambos bajo {@code /api/dwh} y ambos reservados a ADMIN y
 * GERENTE por una línea propia de {@code SecurityConfig}:
 *
 * <ul>
 *   <li>{@code POST /api/dwh/actualizar} — dispara el orquestador y devuelve
 *       <b>202 Accepted</b> con el identificador de corrida. No espera: las 19
 *       cargas tardan ~20 s y bloquear el hilo de la petición durante ese
 *       tiempo convertiría un botón en un cuelgue.</li>
 *   <li>{@code GET /api/dwh/estado} — progreso o resultado de la última
 *       corrida, leído de la bitácora.</li>
 * </ul>
 *
 * <h2>Por qué NO cuelga de /api/informes ni de /api/etl</h2>
 *
 * De {@code /api/informes} no puede colgar porque esa rama termina en
 * {@code .requestMatchers("/api/informes/**").denyAll()} para todo lo que no
 * sea GET: los informes son solo consulta, y esto es una ACCIÓN. Y de
 * {@code /api/etl} tampoco, porque esa ruta es del ETL legado y está reservada
 * a ADMIN; aquí el catálogo pide también al GERENTE, y ampliar aquella línea
 * le abriría de paso la carga de CSV y el ETL antiguo.
 *
 * <h2>Segregación</h2>
 *
 * Actualizar el almacén reconstruye las 19 tablas, incluidas las que llevan
 * márgenes, costos y flujo de caja. Es una decisión de dirección, no una tarea
 * operativa: fuera quedan los seis roles restantes, incluido el ANALISTA, que
 * LEE los informes compuestos pero no decide cuándo se recalculan.
 */
@RestController
@RequestMapping("/api/dwh")
public class DwhActualizacionController {

    private final DwhActualizacionService servicio;

    public DwhActualizacionController(DwhActualizacionService servicio) {
        this.servicio = servicio;
    }

    /**
     * Dispara la actualización completa del almacén.
     * POST /api/dwh/actualizar
     *
     * @return 202 con {@code corridaId}; 409 si ya hay una corrida en curso
     *         (vía {@code GlobalExceptionHandler}, que traduce
     *         {@code IllegalStateException}).
     */
    @PostMapping("/actualizar")
    public ResponseEntity<Map<String, Object>> actualizar(Authentication auth) {
        String origen = auth != null ? auth.getName() : "desconocido";
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(servicio.disparar(origen));
    }

    /**
     * Progreso o resultado de la última actualización.
     * GET /api/dwh/estado
     *
     * Devuelve siempre 200: con ClickHouse apagado responde
     * {@code analiticaDisponible=false} con un mensaje legible, igual que los
     * informes compuestos. Una pantalla de estado no puede tumbarse porque el
     * servicio del que informa esté caído.
     */
    @GetMapping("/estado")
    public Map<String, Object> estado() {
        return servicio.estado();
    }
}
