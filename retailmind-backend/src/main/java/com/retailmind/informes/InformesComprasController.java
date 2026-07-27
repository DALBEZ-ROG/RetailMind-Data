package com.retailmind.informes;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * INFORMES TÁCTICOS — DEPARTAMENTO DE COMPRAS.
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
 *   <li>{@code /defectuosos} (OTD-COM-08, SOLO CANTIDADES) → ADMIN, GERENTE,
 *       COMPRAS y BODEGA, que es quien marca e inspecciona la mercancía mala.</li>
 *   <li>Resto ({@code /ordenes}, {@code /cuentas-por-pagar},
 *       {@code /catalogo-proveedor}) → ADMIN, GERENTE, COMPRAS: llevan DINERO,
 *       así que Bodega y Despacho quedan fuera por segregación financiera.</li>
 * </ul>
 * Los roles salen de la columna «Dashboard y rol destinatario» del catálogo
 * ({@code docs/tactico/CATALOGO_OBJETIVOS_TACTICOS.md} §4).
 */
@RestController
@RequestMapping("/api/informes/compras")
public class InformesComprasController {

    private final InformesComprasService servicio;

    public InformesComprasController(InformesComprasService servicio) {
        this.servicio = servicio;
    }

    /**
     * OTD-COM-01 — Órdenes de compra por estado, con las pendientes de
     * aprobación de Gerencia destacadas.
     * GET /api/informes/compras/ordenes?estado=&proveedor=&desde=&hasta=&page=&size=
     */
    @GetMapping("/ordenes")
    public Map<String, Object> ordenes(
            @RequestParam(required = false) String estado,
            @RequestParam(required = false) String proveedor,
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return servicio.ordenes(estado, proveedor, desde, hasta, page, size);
    }

    /**
     * OTD-COM-02 — Cuentas por pagar: saldo, vencimiento y estado por proveedor.
     * GET /api/informes/compras/cuentas-por-pagar?estado=&situacion=&proveedor=&page=&size=
     */
    @GetMapping("/cuentas-por-pagar")
    public Map<String, Object> cuentasPorPagar(
            @RequestParam(required = false) String estado,
            @RequestParam(required = false) String situacion,
            @RequestParam(required = false) String proveedor,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return servicio.cuentasPorPagar(estado, situacion, proveedor, page, size);
    }

    /**
     * OTD-COM-08 — Pool de defectuosos y devoluciones a proveedor en curso.
     * SOLO CANTIDADES: BODEGA entra a este informe (ver javadoc del servicio).
     * GET /api/informes/compras/defectuosos?estado=&origen=&proveedor=&buscar=&page=&size=
     */
    @GetMapping("/defectuosos")
    public Map<String, Object> defectuosos(
            @RequestParam(required = false) String estado,
            @RequestParam(required = false) String origen,
            @RequestParam(required = false) String proveedor,
            @RequestParam(required = false) String buscar,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return servicio.defectuosos(estado, origen, proveedor, buscar, page, size);
    }

    /**
     * OTD-COM-10 — Catálogo proveedor–producto: costo, plazo y preferido.
     * LLEVA COSTO: SecurityConfig lo cierra a ADMIN/GERENTE/COMPRAS.
     * GET /api/informes/compras/catalogo-proveedor?proveedor=&buscar=&oferta=&page=&size=
     */
    @GetMapping("/catalogo-proveedor")
    public Map<String, Object> catalogoProveedor(
            @RequestParam(required = false) String proveedor,
            @RequestParam(required = false) String buscar,
            @RequestParam(required = false) String oferta,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return servicio.catalogoProveedor(proveedor, buscar, oferta, page, size);
    }
}
