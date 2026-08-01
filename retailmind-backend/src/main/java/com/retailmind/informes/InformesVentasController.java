package com.retailmind.informes;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * INFORMES TÁCTICOS — DEPARTAMENTO DE VENTAS.
 *
 * Convención de rutas del nivel táctico (misma para los seis departamentos):
 * {@code /api/informes/{departamento}/{informe}}. Todos son GET de solo
 * lectura y todos devuelven el MISMO sobre: {@code {items, total, page, size,
 * resumen[]}}, que la pantalla genérica del frontend sabe pintar sin conocer
 * el informe.
 *
 * Decisión del alcance: consulta POR PANTALLA con filtros y registros
 * visibles. Estos endpoints NO generan PDF (los PDF quedan para documentos
 * operativos: facturas, guías, comprobantes).
 *
 * Autorización en SecurityConfig: {@code /api/informes/ventas/**} para
 * ADMIN/GERENTE/VENDEDOR y {@code /moderacion} solo ADMIN/GERENTE. Bodega y
 * Despacho quedan fuera por segregación financiera, y el motor lo respalda
 * (sin SELECT sobre pedido.total, carrito ni meta_venta).
 */
@RestController
@RequestMapping("/api/informes/ventas")
public class InformesVentasController {

    private final InformesVentasService servicio;

    public InformesVentasController(InformesVentasService servicio) {
        this.servicio = servicio;
    }

    /**
     * OTD-VEN-01 — Cartera de pedidos por estado.
     * GET /api/informes/ventas/cartera-pedidos?estado=&canal=&desde=&hasta=&buscar=&page=&size=
     */
    @GetMapping("/cartera-pedidos")
    public Map<String, Object> carteraPedidos(
            @RequestParam(required = false) String estado,
            @RequestParam(required = false) String canal,
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(required = false) String buscar,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return servicio.carteraPedidos(estado, canal, desde, hasta, buscar, page, size);
    }

    /**
     * OTD-VEN-02 — Ventas por vendedor (cumplimiento individual).
     * GET /api/informes/ventas/por-vendedor?desde=&hasta=
     * El VENDEDOR autenticado recibe SOLO su propia fila.
     */
    @GetMapping("/por-vendedor")
    public Map<String, Object> ventasPorVendedor(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta) {
        return servicio.ventasPorVendedor(desde, hasta);
    }

    /**
     * OTD-VEN-08 — Carritos abandonados.
     * GET /api/informes/ventas/carritos-abandonados?estado=&diasMinimos=&page=&size=
     */
    @GetMapping("/carritos-abandonados")
    public Map<String, Object> carritosAbandonados(
            @RequestParam(required = false) String estado,
            @RequestParam(required = false) Integer diasMinimos,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return servicio.carritosAbandonados(estado, diasMinimos, page, size);
    }

    /**
     * OTD-VEN-10 — Cola de moderación (reseñas y preguntas en espera).
     * GET /api/informes/ventas/moderacion?tipo=&diasMinimos=&page=&size=
     */
    @GetMapping("/moderacion")
    public Map<String, Object> colaModeracion(
            @RequestParam(required = false) String tipo,
            @RequestParam(required = false) Integer diasMinimos,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return servicio.colaModeracion(tipo, diasMinimos, page, size);
    }

    /**
     * OTD-VEN-15 — Venta acumulada contra la meta del período.
     * GET /api/informes/ventas/avance-meta?anio=&mes= (por defecto, el mes en curso).
     */
    @GetMapping("/avance-meta")
    public Map<String, Object> avanceMeta(
            @RequestParam(required = false) Integer anio,
            @RequestParam(required = false) Integer mes) {
        return servicio.avanceMeta(anio, mes);
    }

    /**
     * OTD-VEN-16 — Participación de la venta por canal (foto del período).
     * GET /api/informes/ventas/participacion-canal?canal=&desde=&hasta=
     *
     * Sostiene OE-06 (Consolidación de la Experiencia Omnicanal). Agrupa por el
     * MEDIO de entrada del pedido, nunca por tipo de cliente: la segmentación
     * B2B/B2C no existe en los datos ni es derivable (ver el detalle en el
     * servicio y el diagnóstico que lo descarta).
     */
    @GetMapping("/participacion-canal")
    public Map<String, Object> participacionCanal(
            @RequestParam(required = false) String canal,
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta) {
        return servicio.participacionCanal(canal, desde, hasta);
    }
}
