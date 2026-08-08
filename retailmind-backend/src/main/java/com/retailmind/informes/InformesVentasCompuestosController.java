package com.retailmind.informes;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * INFORMES TÁCTICOS COMPUESTOS — VENTAS (fuente: ClickHouse).
 *
 * Comparte la ruta base {@code /api/informes/ventas} con
 * {@link InformesVentasController} y respeta la misma convención
 * ({@code /api/informes/{departamento}/{informe}}, GET de solo lectura, sobre
 * {@code {items, total, page, size, resumen[]}}, sin PDF). Va en un controlador
 * APARTE porque la fuente es distinta: estos endpoints leen el data warehouse
 * y no PostgreSQL, y separarlos deja a la vista dónde termina la consulta
 * directa y dónde empieza la analítica — incluida su degradación.
 *
 * AUTORIZACIÓN: en {@code SecurityConfig}, línea propia y ANTES del comodín
 * {@code /api/informes/ventas/**}. Este informe lleva DINERO (venta, costo,
 * margen) y ClickHouse no respalda la segregación financiera por motor (§8.2
 * del diseño del pipeline): aquí el corte lo hace ÍNTEGRAMENTE la ruta, como ya
 * ocurre con OTD-INV-07, OTD-LOG-11 y OTD-GER-08.
 */
@RestController
@RequestMapping("/api/informes/ventas")
public class InformesVentasCompuestosController {

    private final InformesVentasCompuestosService servicio;

    public InformesVentasCompuestosController(InformesVentasCompuestosService servicio) {
        this.servicio = servicio;
    }

    /**
     * OTD-VEN-06 — Evolución de la venta mes a mes y por categoría.
     * GET /api/informes/ventas/evolucion-mensual?desde=&hasta=&categoria=&canal=&page=&size=
     *
     * Destinatarios (catálogo §3): Gerente, Analista, Administrador.
     * El rango de fechas se ajusta a meses COMPLETOS y excluye los pedidos
     * cancelados. El sobre viaja con la marca de agua {@code datosAl}.
     */
    @GetMapping("/evolucion-mensual")
    public Map<String, Object> evolucionMensual(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(required = false) String categoria,
            @RequestParam(required = false) String canal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return servicio.evolucionMensual(desde, hasta, categoria, canal, page, size);
    }

    /**
     * OTD-VEN-05 — Cuánto compra cada cliente.
     * GET /api/informes/ventas/clientes?desde=&hasta=&canal=&buscar=&page=&size=
     *
     * Destinatarios (catálogo §3): Gerente, Vendedor, Analista, Administrador.
     * Lleva MONTO: Bodega y Despacho fuera. El VENDEDOR sí entra —la cartera de
     * clientes es su herramienta— y a diferencia de OTD-VEN-02 NO se recorta a
     * lo suyo: el pedido del canal web no tiene vendedor, así que un recorte por
     * autor dejaría fuera más de la mitad de la compra de cada cliente y daría
     * una ficha falsa.
     */
    @GetMapping("/clientes")
    public Map<String, Object> clientes(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(required = false) String canal,
            @RequestParam(required = false) String buscar,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return servicio.clientes(desde, hasta, canal, buscar, page, size);
    }

    /**
     * OTD-VEN-07 — Valor promedio del pedido, por período y por canal.
     * GET /api/informes/ventas/ticket-promedio?desde=&hasta=&canal=&page=&size=
     *
     * Destinatarios: Gerente, Analista, Administrador. El rango se ajusta a
     * meses COMPLETOS y excluye los pedidos cancelados.
     */
    @GetMapping("/ticket-promedio")
    public Map<String, Object> ticketPromedio(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(required = false) String canal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return servicio.ticketPromedio(desde, hasta, canal, page, size);
    }

    /**
     * OTD-VEN-13 — Evolución mensual de la participación de cada canal.
     * GET /api/informes/ventas/evolucion-canal?desde=&hasta=&canal=&page=&size=
     *
     * Destinatarios: Gerente, Vendedor, Analista, Administrador. Par temporal
     * de OTD-VEN-16 (SIMPLE), que da la foto del período.
     */
    @GetMapping("/evolucion-canal")
    public Map<String, Object> evolucionCanal(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(required = false) String canal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return servicio.evolucionCanal(desde, hasta, canal, page, size);
    }

    /**
     * OTD-VEN-09 — Mezcla de formas de cobro y su evolución.
     * GET /api/informes/ventas/formas-cobro?desde=&hasta=&tipo=&page=&size=
     *
     * Destinatarios: Gerente, Analista, Administrador. Solo cobros
     * COMPLETADOS: los intentos fallidos son OTD-VEN-12.
     */
    @GetMapping("/formas-cobro")
    public Map<String, Object> formasCobro(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(required = false) String tipo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return servicio.formasCobro(desde, hasta, tipo, page, size);
    }

    /**
     * OTD-VEN-12 — Cobros en línea fallidos y su motivo.
     * GET /api/informes/ventas/cobros-fallidos?desde=&hasta=&motivo=&page=&size=
     *
     * Destinatarios (catálogo §3): Gerente y Administrador — el corte más
     * estrecho de Ventas. Los motivos llegan NORMALIZADOS por el ETL: cinco, no
     * los seis crudos de PostgreSQL.
     */
    @GetMapping("/cobros-fallidos")
    public Map<String, Object> cobrosFallidos(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(required = false) String motivo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return servicio.cobrosFallidos(desde, hasta, motivo, page, size);
    }

    /**
     * OTD-VEN-11 — Calificación de cada producto y cómo evoluciona.
     * GET /api/informes/ventas/resenas?desde=&hasta=&categoria=&estado=&buscar=
     *     &agrupar=&page=&size=
     *
     * Destinatarios (catálogo §3): Gerente, Vendedor y Analista. NO lleva
     * dinero —es una escala de 1 a 5— pero tampoco entran Bodega ni Despacho:
     * la opinión del cliente sobre el catálogo no es su atribución.
     *
     * {@code agrupar} ∈ {producto (defecto), mes, categoria, marca}: el
     * ranking y la evolución son el mismo agregado con distinta clave.
     */
    @GetMapping("/resenas")
    public Map<String, Object> resenas(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(required = false) String categoria,
            @RequestParam(required = false) String estado,
            @RequestParam(required = false) String buscar,
            @RequestParam(required = false) String agrupar,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return servicio.resenasProducto(desde, hasta, categoria, estado, buscar,
                agrupar, page, size);
    }

    /**
     * OTD-VEN-14 — Dinero devuelto al mes y su peso sobre la venta.
     * GET /api/informes/ventas/devoluciones?desde=&hasta=&estado=&motivo=&base=
     *
     * Destinatarios (catálogo §3): Gerente, Administrador y Analista —
     * <b>Bodega y Despacho NO: es dinero</b>, y el corte lo hace la ruta
     * porque ClickHouse no tiene GRANT por columna.
     *
     * {@code base} ∈ {devolucion (defecto), pedido} decide contra QUÉ mes se
     * divide el porcentaje. No es un detalle de presentación: son dos
     * preguntas distintas y el sobre declara cuál respondió, en un KPI y en
     * {@code salvedad}.
     *
     * Sin paginación: una fila por mes de la serie.
     */
    @GetMapping("/devoluciones")
    public Map<String, Object> devoluciones(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(required = false) String estado,
            @RequestParam(required = false) String motivo,
            @RequestParam(required = false) String base) {
        return servicio.devolucionesMes(desde, hasta, estado, motivo, base);
    }

    /**
     * OTD-VEN-19 — Clientes en riesgo (fase E3 del nivel estratégico, §5.2).
     * GET /api/informes/ventas/clientes-en-riesgo?nivel=&buscar=&page=&size=
     *
     * Destinatarios (§5.2.8): <b>ADMIN, GERENTE y VENDEDOR</b>. Lleva MONTO
     * —facturación 12m y valor en riesgo—, así que Bodega y Despacho quedan
     * fuera por RUTA. El VENDEDOR entra porque es quien ejecuta el gesto
     * comercial, y se recorta a SU cartera con {@code alcance: "propio"}.
     *
     * {@code nivel} ∈ {alerta (defecto), critica, atencion, normal,
     * sin_muestra, todos}. Arranca en {@code alerta} a propósito: un informe de
     * alerta que abre mostrando a los 69 clientes obliga a buscar la alerta
     * dentro de la lista.
     *
     * <b>El resultado del modelo viaja en la cabecera</b>: los tres primeros KPI
     * son el lift medido, su número de casos positivos y si supera al azar. Es
     * la regla 4 de §5.2.9 y no es negociable — un modelo que oculta su lift es
     * indistinguible de uno que funciona.
     */
    @GetMapping("/clientes-en-riesgo")
    public Map<String, Object> clientesEnRiesgo(
            @RequestParam(required = false) String nivel,
            @RequestParam(required = false) String buscar,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return servicio.clientesEnRiesgo(nivel, buscar, page, size);
    }

    /**
     * OTD-VEN-03 — Los productos que más se venden («producto estrella»).
     * GET /api/informes/ventas/top-productos?desde=&hasta=&canal=&categoria=
     *     &page=&size=
     *
     * Destinatarios (catálogo §3): <b>Gerente, Vendedor, Compras, Analista y
     * Administrador</b> — el reparto más ancho de Ventas, porque la pregunta
     * («qué reponer») es operativa y no de dirección.
     *
     * {@code size} arranca en <b>10</b>: el objetivo pide «los 10 primeros» y
     * ese es el informe por defecto. La paginación sigue disponible, así que
     * quien quiera el ranking completo solo tiene que pedir más.
     *
     * NO devuelve margen ni costo: eso es OTD-GER-10 y el catálogo lo reserva a
     * la dirección. Ver el javadoc del servicio.
     */
    @GetMapping("/top-productos")
    public Map<String, Object> topProductos(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(required = false) String canal,
            @RequestParam(required = false) String categoria,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return servicio.topProductos(desde, hasta, canal, categoria, page, size);
    }

    /**
     * OTD-VEN-04 — Los productos que no se venden («producto hueso»).
     * GET /api/informes/ventas/productos-hueso?desde=&hasta=&canal=&categoria=
     *     &marca=&alcance=&page=&size=
     *
     * Destinatarios (catálogo §3): <b>Gerente, Compras, Analista y
     * Administrador</b>. Sin VENDEDOR: la decisión que sostiene —liquidar o
     * dejar de comprar— es de compras y de dirección.
     *
     * {@code alcance} ∈ {nunca (defecto), periodo}. Son DOS listas distintas y
     * dos decisiones distintas; el sobre declara en pantalla cuál se está
     * viendo. {@code size} arranca en <b>10</b>, como el objetivo pide.
     *
     * Este informe no selecciona ni una columna de dinero, así que Compras
     * entra sin abrir ninguna lectura financiera nueva.
     */
    @GetMapping("/productos-hueso")
    public Map<String, Object> productosHueso(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(required = false) String canal,
            @RequestParam(required = false) String categoria,
            @RequestParam(required = false) String marca,
            @RequestParam(required = false) String alcance,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return servicio.productosHueso(desde, hasta, canal, categoria, marca,
                alcance, page, size);
    }
}
