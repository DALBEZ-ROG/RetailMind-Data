package com.retailmind.informes;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * INFORMES TÁCTICOS COMPUESTOS — SOPORTE (fuente: ClickHouse).
 *
 * Comparte la ruta base {@code /api/informes/soporte} con
 * {@link InformesSoporteController} y respeta la misma convención. Va en un
 * controlador APARTE porque la fuente es distinta: lee el data warehouse y no
 * PostgreSQL, con su propia degradación.
 *
 * AUTORIZACIÓN: ninguno de los cinco lleva dinero, así que cuatro se apoyan en
 * el comodín del departamento ({@code /api/informes/soporte/**} →
 * ADMIN/GERENTE/SOPORTE). El único con línea propia es OTD-SOP-08, que suma a
 * COMPRAS: el ranking de productos problemáticos existe precisamente para que
 * Compras vaya a hablar con el proveedor.
 */
@RestController
@RequestMapping("/api/informes/soporte")
public class InformesSoporteCompuestosController {

    private final InformesSoporteCompuestosService servicio;

    public InformesSoporteCompuestosController(InformesSoporteCompuestosService servicio) {
        this.servicio = servicio;
    }

    /**
     * OTD-SOP-02 — Cumplimiento del tiempo prometido al cliente.
     * GET /api/informes/soporte/cumplimiento-sla?desde=&hasta=&categoria=
     *     &prioridad=&agrupar=
     *
     * Destinatarios (catálogo §8): Soporte y Gerente.
     *
     * El informe NO publica una tasa sobre el total: parte la base en CUATRO
     * —cerrado a tiempo, cerrado tarde, abierto dentro de plazo, abierto y ya
     * vencido— porque el cumplimiento de un ticket abierto es desconocido y no
     * incumplido. La última categoría es la accionable.
     *
     * {@code agrupar} ∈ {prioridad (defecto), categoria, mes, agente}.
     * Sin paginación: 4 prioridades, 8 categorías o 19 meses.
     */
    @GetMapping("/cumplimiento-sla")
    public Map<String, Object> cumplimientoSla(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(required = false) String categoria,
            @RequestParam(required = false) String prioridad,
            @RequestParam(required = false) String agrupar) {
        return servicio.cumplimientoSla(desde, hasta, categoria, prioridad, agrupar);
    }

    /**
     * OTD-SOP-03 — Horas de resolución por tipo de problema.
     * GET /api/informes/soporte/tiempo-resolucion?desde=&hasta=&categoria=
     *     &prioridad=&estado=
     *
     * Destinatarios (catálogo §8): Soporte, Gerente y Analista.
     *
     * La base son los 76 tickets con CIERRE, no los 120 en estado «resuelto o
     * cerrado»: resolver no cierra en este sistema y no hay instante que
     * restar. Cada fila declara su denominador.
     *
     * Sin paginación: una fila por categoría, ocho en total.
     */
    @GetMapping("/tiempo-resolucion")
    public Map<String, Object> tiempoResolucion(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(required = false) String categoria,
            @RequestParam(required = false) String prioridad,
            @RequestParam(required = false) String estado) {
        return servicio.tiempoResolucion(desde, hasta, categoria, prioridad, estado);
    }

    /**
     * OTD-SOP-06 — Horas hasta la primera respuesta al cliente.
     * GET /api/informes/soporte/primera-respuesta?desde=&hasta=&categoria=
     *     &prioridad=&agrupar=
     *
     * Destinatarios (catálogo §8): Soporte y Gerente.
     *
     * La definición de «primera respuesta» —primer mensaje del equipo VISIBLE
     * para el cliente— viaja en {@code salvedad} y se pinta encima de la tabla:
     * es una decisión, y con la definición laxa (contando notas internas) la
     * base pasa de 193 a 244 tickets y el tiempo baja.
     *
     * {@code agrupar} ∈ {prioridad (defecto), mes, categoria, agente}.
     */
    @GetMapping("/primera-respuesta")
    public Map<String, Object> primeraRespuesta(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(required = false) String categoria,
            @RequestParam(required = false) String prioridad,
            @RequestParam(required = false) String agrupar) {
        return servicio.primeraRespuesta(desde, hasta, categoria, prioridad, agrupar);
    }

    /**
     * OTD-SOP-07 — Tiempo de resolución por persona del equipo.
     * GET /api/informes/soporte/tiempos-agente?desde=&hasta=&categoria=
     *     &prioridad=&agente=
     *
     * Destinatarios (catálogo §8): Soporte y Gerente.
     *
     * La ruta es {@code /tiempos-agente} y no {@code /por-agente} a propósito:
     * el informe SIMPLE con ese nombre sigue vivo y responde otra pregunta —la
     * carga viva de la bandeja—, mientras este mide TIEMPOS sobre los cierres.
     *
     * La fila «(sin asignar)» aparece a propósito: son los 33 tickets que
     * nadie ha tomado.
     */
    @GetMapping("/tiempos-agente")
    public Map<String, Object> tiemposAgente(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(required = false) String categoria,
            @RequestParam(required = false) String prioridad,
            @RequestParam(required = false) String agente) {
        return servicio.tiemposAgente(desde, hasta, categoria, prioridad, agente);
    }

    /**
     * OTD-SOP-08 — Productos que más reclamos y devoluciones generan.
     * GET /api/informes/soporte/productos-reclamados?desde=&hasta=&categoria=
     *     &buscar=&page=&size=
     *
     * Destinatarios (catálogo §8): Soporte, Gerente y <b>COMPRAS</b> — de ahí
     * la línea propia en {@code SecurityConfig}. Sin una sola columna de
     * dinero: son conteos de reclamos y unidades devueltas.
     *
     * Cruza {@code fact_ticket} con {@code fact_devolucion_linea}; los 106
     * tickets sin producto quedan FUERA del ranking y se declaran en el
     * resumen en vez de repartirse.
     */
    @GetMapping("/productos-reclamados")
    public Map<String, Object> productosReclamados(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(required = false) String categoria,
            @RequestParam(required = false) String buscar,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return servicio.productosReclamados(desde, hasta, categoria, buscar, page, size);
    }
}
