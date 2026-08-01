package com.retailmind.informes;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * INFORMES TÁCTICOS COMPUESTOS — INVENTARIO / BODEGA (fuente: ClickHouse).
 *
 * Comparte la ruta base {@code /api/informes/inventario} con
 * {@link InformesInventarioController} y respeta la misma convención. Va en un
 * controlador APARTE porque la fuente es distinta: lee el data warehouse
 * {@code retailmind_dwh} y no PostgreSQL, con su propia degradación.
 *
 * AUTORIZACIÓN: los tres endpoints llevan línea propia en {@code SecurityConfig},
 * ANTES del comodín {@code /api/informes/inventario/**}, porque el reparto de
 * roles NO coincide con el del departamento:
 *
 * <ul>
 *   <li>{@code /rotacion} y {@code /mermas} suman al ANALISTA, que no participa
 *       en los informes simples de Inventario, y mantienen a BODEGA porque no
 *       exponen importes.</li>
 *   <li>{@code /capital-inmovilizado} deja fuera a BODEGA: es dinero de
 *       principio a fin. Es el segundo informe del departamento con ese corte,
 *       junto con OTD-INV-07.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/informes/inventario")
public class InformesInventarioCompuestosController {

    private final InformesInventarioCompuestosService servicio;

    public InformesInventarioCompuestosController(
            InformesInventarioCompuestosService servicio) {
        this.servicio = servicio;
    }

    /**
     * OTD-INV-04 — Rotación por categoría y período.
     * GET /api/informes/inventario/rotacion?desde=&hasta=&bodega=&categoria=
     *
     * Destinatarios (catálogo §5): Gerente, Analista, Administrador y BODEGA en
     * cantidades. Bodega entra porque la consulta no selecciona ni un importe —
     * la barrera del corte financiero es aquí la CONSULTA, igual que en
     * OTD-COM-08 y OTD-LOG-12, porque ClickHouse no tiene GRANT por columna.
     *
     * Sin paginación: una fila por categoría, once como mucho.
     */
    @GetMapping("/rotacion")
    public Map<String, Object> rotacion(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(required = false) String bodega,
            @RequestParam(required = false) String categoria) {
        return servicio.rotacion(desde, hasta, bodega, categoria);
    }

    /**
     * OTD-INV-09 — Evolución mensual del capital inmovilizado.
     * GET /api/informes/inventario/capital-inmovilizado?desde=&hasta=&bodega=&categoria=
     *
     * Destinatarios (catálogo §5): Gerente, Administrador y Analista. BODEGA NO:
     * el informe es dinero de principio a fin.
     *
     * El sobre viaja SIEMPRE con {@code salvedad}: la valorización es a costo
     * VIGENTE y no histórico (§8.3 del diseño), y la pantalla la muestra encima
     * de la tabla. Sin esa declaración el informe se leería como contabilidad.
     *
     * Sin paginación: una fila por mes, 19 en el período sembrado.
     */
    @GetMapping("/capital-inmovilizado")
    public Map<String, Object> capitalInmovilizado(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(required = false) String bodega,
            @RequestParam(required = false) String categoria) {
        return servicio.capitalInmovilizado(desde, hasta, bodega, categoria);
    }

    /**
     * OTD-INV-10 — Mermas y sobrantes acumulados por período y motivo.
     * GET /api/informes/inventario/mermas?desde=&hasta=&bodega=&tipo=&estado=
     *
     * Destinatarios (catálogo §5): Bodega en cantidades; valorizado solo Gerente
     * y Administrador. El informe es MIXTO y el reparto lo decide el servicio
     * sobre el rol del JWT: las columnas de valor ni siquiera se seleccionan
     * cuando quien pregunta no puede verlas.
     *
     * Sin paginación: una fila por (tipo, motivo, estado), once motivos reales.
     */
    @GetMapping("/mermas")
    public Map<String, Object> mermas(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(required = false) String bodega,
            @RequestParam(required = false) String tipo,
            @RequestParam(required = false) String estado) {
        return servicio.mermas(desde, hasta, bodega, tipo, estado);
    }
}
