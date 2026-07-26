package com.retailmind.informes;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * INFORMES TÁCTICOS — DEPARTAMENTO DE INVENTARIO / BODEGA.
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
 *   <li>{@code /valor-inventario} (OTD-INV-07, DINERO) → ADMIN, GERENTE, ANALISTA.
 *       Bodega y Despacho quedan fuera por segregación financiera.</li>
 *   <li>{@code /stock-bodega} (OTD-INV-02) → + COMPRAS y VENDEDOR, que necesitan
 *       saber qué hay disponible antes de comprar o de vender.</li>
 *   <li>{@code /bajo-minimo} y {@code /sobre-stock} (OTD-INV-01/08) → + COMPRAS:
 *       son las dos listas que disparan y frenan la reposición.</li>
 *   <li>Resto ({@code /kardex}, {@code /ajustes}, {@code /transferencias}) →
 *       ADMIN, GERENTE, BODEGA: operación interna del almacén.</li>
 * </ul>
 * Los roles salen de la columna «Dashboard y rol destinatario» del catálogo
 * ({@code docs/tactico/CATALOGO_OBJETIVOS_TACTICOS.md} §5).
 */
@RestController
@RequestMapping("/api/informes/inventario")
public class InformesInventarioController {

    private final InformesInventarioService servicio;

    public InformesInventarioController(InformesInventarioService servicio) {
        this.servicio = servicio;
    }

    /**
     * OTD-INV-01 — Productos bajo mínimo (lista de reposición).
     * GET /api/informes/inventario/bajo-minimo?bodega=&buscar=&page=&size=
     */
    @GetMapping("/bajo-minimo")
    public Map<String, Object> bajoMinimo(
            @RequestParam(required = false) String bodega,
            @RequestParam(required = false) String buscar,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return servicio.bajoMinimo(bodega, buscar, page, size);
    }

    /**
     * OTD-INV-02 — Stock actual por bodega (incluye lo apartado para pedidos).
     * GET /api/informes/inventario/stock-bodega?bodega=&buscar=&situacion=&page=&size=
     */
    @GetMapping("/stock-bodega")
    public Map<String, Object> stockPorBodega(
            @RequestParam(required = false) String bodega,
            @RequestParam(required = false) String buscar,
            @RequestParam(required = false) String situacion,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return servicio.stockPorBodega(bodega, buscar, situacion, page, size);
    }

    /**
     * OTD-INV-03 — Kardex: historial de entradas y salidas de un producto.
     * GET /api/informes/inventario/kardex?buscar=&bodega=&tipo=&naturaleza=&desde=&hasta=&page=&size=
     */
    @GetMapping("/kardex")
    public Map<String, Object> kardex(
            @RequestParam(required = false) String buscar,
            @RequestParam(required = false) String bodega,
            @RequestParam(required = false) String tipo,
            @RequestParam(required = false) String naturaleza,
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return servicio.kardex(buscar, bodega, tipo, naturaleza, desde, hasta, page, size);
    }

    /**
     * OTD-INV-05 — Ajustes de inventario (mermas y sobrantes) con su motivo.
     * GET /api/informes/inventario/ajustes?tipo=&estado=&motivo=&bodega=&desde=&hasta=&page=&size=
     */
    @GetMapping("/ajustes")
    public Map<String, Object> ajustes(
            @RequestParam(required = false) String tipo,
            @RequestParam(required = false) String estado,
            @RequestParam(required = false) String motivo,
            @RequestParam(required = false) String bodega,
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return servicio.ajustes(tipo, estado, motivo, bodega, desde, hasta, page, size);
    }

    /**
     * OTD-INV-06 — Transferencias entre bodegas y su estado.
     * GET /api/informes/inventario/transferencias?estado=&bodega=&desde=&hasta=&page=&size=
     */
    @GetMapping("/transferencias")
    public Map<String, Object> transferencias(
            @RequestParam(required = false) String estado,
            @RequestParam(required = false) String bodega,
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return servicio.transferencias(estado, bodega, desde, hasta, page, size);
    }

    /**
     * OTD-INV-07 — Valor del inventario por categoría y bodega. LLEVA DINERO:
     * SecurityConfig lo cierra a ADMIN/GERENTE/ANALISTA (Bodega y Despacho no).
     * GET /api/informes/inventario/valor-inventario?bodega=&categoria=
     */
    @GetMapping("/valor-inventario")
    public Map<String, Object> valorInventario(
            @RequestParam(required = false) String bodega,
            @RequestParam(required = false) String categoria) {
        return servicio.valorInventario(bodega, categoria);
    }

    /**
     * OTD-INV-08 — Sobre-stock: existencias por encima del tope máximo.
     * GET /api/informes/inventario/sobre-stock?bodega=&buscar=&page=&size=
     */
    @GetMapping("/sobre-stock")
    public Map<String, Object> sobreStock(
            @RequestParam(required = false) String bodega,
            @RequestParam(required = false) String buscar,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return servicio.sobreStock(bodega, buscar, page, size);
    }
}
