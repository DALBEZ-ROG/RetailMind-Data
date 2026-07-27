package com.retailmind.informes;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * INFORMES TÁCTICOS — DEPARTAMENTO DE LOGÍSTICA / DESPACHO.
 *
 * Misma convención que el resto del nivel táctico:
 * {@code /api/informes/{departamento}/{informe}}. Todos son GET de solo lectura
 * y todos devuelven el MISMO sobre {@code {items, total, page, size, resumen[]}},
 * que la pantalla genérica del frontend sabe pintar sin conocer el informe.
 *
 * Consulta POR PANTALLA con filtros: estos endpoints NO generan PDF (los PDF
 * quedan para documentos operativos: facturas, guías, comprobantes).
 *
 * Autorización en SecurityConfig, de lo más específico a lo más general:
 * <ul>
 *   <li>{@code /costo-envio} (OTD-LOG-11, DINERO) → ADMIN, GERENTE. DESPACHO
 *       queda fuera por segregación financiera, y aquí el corte lo hace ESTA
 *       RUTA y no el motor (grp_despacho lee {@code envio.costo} porque lo
 *       escribe al despachar) — ver el javadoc del servicio.</li>
 *   <li>{@code /devoluciones} (OTD-LOG-06) → + SOPORTE, que es quien valida el
 *       RMA, y BODEGA, que ve su tramo de inspección en cantidades.</li>
 *   <li>{@code /cola-despacho} y {@code /envios} (OTD-LOG-01/02) → ADMIN,
 *       GERENTE, DESPACHO: la operación diaria de salida.</li>
 * </ul>
 * Los roles salen de la columna «Dashboard y rol destinatario» del catálogo
 * ({@code docs/tactico/CATALOGO_OBJETIVOS_TACTICOS.md} §7).
 */
@RestController
@RequestMapping("/api/informes/logistica")
public class InformesLogisticaController {

    private final InformesLogisticaService servicio;

    public InformesLogisticaController(InformesLogisticaService servicio) {
        this.servicio = servicio;
    }

    /**
     * OTD-LOG-01 — Cola de despacho: pedidos del tramo de salida en espera.
     * GET /api/informes/logistica/cola-despacho?estado=&canal=&transportista=&buscar=&page=&size=
     */
    @GetMapping("/cola-despacho")
    public Map<String, Object> colaDespacho(
            @RequestParam(required = false) String estado,
            @RequestParam(required = false) String canal,
            @RequestParam(required = false) String transportista,
            @RequestParam(required = false) String buscar,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return servicio.colaDespacho(estado, canal, transportista, buscar, page, size);
    }

    /**
     * OTD-LOG-02 — Envíos por estado, transportista y guía.
     * GET /api/informes/logistica/envios?estado=&transportista=&desde=&hasta=&buscar=&page=&size=
     */
    @GetMapping("/envios")
    public Map<String, Object> envios(
            @RequestParam(required = false) String estado,
            @RequestParam(required = false) String transportista,
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(required = false) String buscar,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return servicio.envios(estado, transportista, desde, hasta, buscar, page, size);
    }

    /**
     * OTD-LOG-06 — Devoluciones de cliente en curso por paso del ciclo RMA.
     * GET /api/informes/logistica/devoluciones?estado=&motivo=&desde=&hasta=&buscar=&page=&size=
     */
    @GetMapping("/devoluciones")
    public Map<String, Object> devoluciones(
            @RequestParam(required = false) String estado,
            @RequestParam(required = false) String motivo,
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(required = false) String buscar,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return servicio.devoluciones(estado, motivo, desde, hasta, buscar, page, size);
    }

    /**
     * OTD-LOG-11 — Costo real del envío por zona y transportista contra lo
     * cobrado. LLEVA DINERO: SecurityConfig lo cierra a ADMIN/GERENTE
     * (DESPACHO NO). Sin paginar: pocas filas por naturaleza.
     * GET /api/informes/logistica/costo-envio?zona=&transportista=&desde=&hasta=
     */
    @GetMapping("/costo-envio")
    public Map<String, Object> costoEnvio(
            @RequestParam(required = false) String zona,
            @RequestParam(required = false) String transportista,
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta) {
        return servicio.costoEnvio(zona, transportista, desde, hasta);
    }
}
