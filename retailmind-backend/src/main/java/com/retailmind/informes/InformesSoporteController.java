package com.retailmind.informes;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * INFORMES TÁCTICOS — DEPARTAMENTO DE SOPORTE.
 *
 * Misma convención que el resto del nivel táctico:
 * {@code /api/informes/{departamento}/{informe}}. Todos son GET de solo lectura
 * y todos devuelven el MISMO sobre {@code {items, total, page, size, resumen[]}},
 * que la pantalla genérica del frontend sabe pintar sin conocer el informe.
 *
 * Consulta POR PANTALLA con filtros: estos endpoints NO generan PDF.
 *
 * Autorización en SecurityConfig — los roles salen de la columna «Dashboard y
 * rol destinatario» del catálogo ({@code docs/tactico/CATALOGO_OBJETIVOS_TACTICOS.md} §8):
 * <ul>
 *   <li>{@code /bandeja} (OTD-SOP-01) y {@code /por-agente} (OTD-SOP-05) →
 *       SOPORTE, GERENTE, ADMIN.</li>
 *   <li>{@code /por-categoria} (OTD-SOP-04) → SOPORTE, GERENTE (y ADMIN, que
 *       entra en todo el nivel táctico).</li>
 * </ul>
 * Ningún informe de Soporte lleva dinero: no hay corte financiero que declarar.
 */
@RestController
@RequestMapping("/api/informes/soporte")
public class InformesSoporteController {

    private final InformesSoporteService servicio;

    public InformesSoporteController(InformesSoporteService servicio) {
        this.servicio = servicio;
    }

    /**
     * OTD-SOP-01 — Bandeja de tickets por estado, prioridad, categoría y agente.
     * GET /api/informes/soporte/bandeja?estado=&prioridad=&categoria=&agente=&buscar=&page=&size=
     */
    @GetMapping("/bandeja")
    public Map<String, Object> bandeja(
            @RequestParam(required = false) String estado,
            @RequestParam(required = false) String prioridad,
            @RequestParam(required = false) String categoria,
            @RequestParam(required = false) String agente,
            @RequestParam(required = false) String buscar,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return servicio.bandeja(estado, prioridad, categoria, agente, buscar, page, size);
    }

    /**
     * OTD-SOP-04 — Distribución de tickets por categoría. Sin paginar (8 filas).
     * GET /api/informes/soporte/por-categoria?desde=&hasta=
     */
    @GetMapping("/por-categoria")
    public Map<String, Object> porCategoria(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta) {
        return servicio.porCategoria(desde, hasta);
    }

    /**
     * OTD-SOP-05 — Carga y cierre por agente. Sin paginar (pocas filas).
     * GET /api/informes/soporte/por-agente?desde=&hasta=
     */
    @GetMapping("/por-agente")
    public Map<String, Object> porAgente(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta) {
        return servicio.porAgente(desde, hasta);
    }
}
