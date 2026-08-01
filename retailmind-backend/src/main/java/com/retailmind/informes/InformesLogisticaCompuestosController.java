package com.retailmind.informes;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * INFORMES TÁCTICOS COMPUESTOS — LOGÍSTICA (fuente: ClickHouse).
 *
 * Comparte la ruta base {@code /api/informes/logistica} con
 * {@link InformesLogisticaController} y respeta la misma convención. Va en un
 * controlador APARTE porque la fuente es distinta: lee el data warehouse y no
 * PostgreSQL, con su propia degradación.
 *
 * AUTORIZACIÓN: línea propia en {@code SecurityConfig}, ANTES del comodín
 * {@code /api/informes/logistica/**}, porque este informe suma al ANALISTA, que
 * no participa en el resto del departamento.
 */
@RestController
@RequestMapping("/api/informes/logistica")
public class InformesLogisticaCompuestosController {

    private final InformesLogisticaCompuestosService servicio;

    public InformesLogisticaCompuestosController(InformesLogisticaCompuestosService servicio) {
        this.servicio = servicio;
    }

    /**
     * OTD-LOG-12 — Tiempo por etapa del ciclo del pedido.
     * GET /api/informes/logistica/tiempos-ciclo?desde=&hasta=&canal=
     *
     * Destinatarios (catálogo §6): Despacho, Gerente, Analista y Administrador.
     * DESPACHO entra porque el informe es de TIEMPOS y no selecciona ninguna
     * columna de monto — la barrera del corte financiero es aquí la CONSULTA,
     * igual que en OTD-COM-08, porque ClickHouse no tiene GRANT por columna que
     * lo respalde.
     *
     * Sin paginación: son siempre CUATRO filas, una por etapa.
     */
    @GetMapping("/tiempos-ciclo")
    public Map<String, Object> tiemposCiclo(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(required = false) String canal) {
        return servicio.tiemposCiclo(desde, hasta, canal);
    }

    /**
     * OTD-LOG-03 — Cumplimiento de la fecha prometida, por transportista.
     * GET /api/informes/logistica/cumplimiento-promesa?desde=&hasta=&zona=
     *     &transportista=&estado=
     *
     * Destinatarios (catálogo §7): Despacho, Gerente, Analista y Administrador.
     * DESPACHO entra: el informe va en fechas y veredictos, sin un solo importe.
     *
     * Sin paginación: una fila por transportista, cinco en total.
     */
    @GetMapping("/cumplimiento-promesa")
    public Map<String, Object> cumplimientoPromesa(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(required = false) String zona,
            @RequestParam(required = false) String transportista,
            @RequestParam(required = false) String estado) {
        return servicio.cumplimientoPromesa(desde, hasta, zona, transportista, estado);
    }

    /**
     * OTD-LOG-04 — Días reales de tránsito.
     * GET /api/informes/logistica/dias-transito?desde=&hasta=&zona=
     *     &transportista=&agrupar=
     *
     * Destinatarios: los mismos que LOG-03. Tampoco lleva dinero.
     *
     * {@code agrupar} ∈ {transportista (defecto), mes, zona} — el «y período»
     * que pide el catálogo se resuelve con este corte y no con un informe aparte.
     * Un valor fuera de la lista blanca sale como 400.
     */
    @GetMapping("/dias-transito")
    public Map<String, Object> diasTransito(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(required = false) String zona,
            @RequestParam(required = false) String transportista,
            @RequestParam(required = false) String agrupar) {
        return servicio.diasTransito(desde, hasta, zona, transportista, agrupar);
    }

    /**
     * OTD-LOG-05 — Problemas de entrega: tipo, intentos y desenlace.
     * GET /api/informes/logistica/novedades?desde=&hasta=&tipo=&accion=
     *     &transportista=
     *
     * Destinatarios (catálogo §7): Despacho, Gerente, Soporte y Administrador.
     * SOPORTE entra —la incidencia de entrega acaba a menudo en su bandeja— y no
     * hay dinero que segregar.
     *
     * OJO con {@code accion}: los valores son {@code reprogramada} /
     * {@code devuelto_almacen} / {@code sin_resolver}, NO los verbos del API que
     * declara el diseño (corrección C3C.3).
     */
    @GetMapping("/novedades")
    public Map<String, Object> novedades(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(required = false) String tipo,
            @RequestParam(required = false) String accion,
            @RequestParam(required = false) String transportista) {
        return servicio.novedadesEntrega(desde, hasta, tipo, accion, transportista);
    }

    /**
     * SERIE TEMPORAL DEL COSTO DE ENVÍO — la evolución mensual que OTD-LOG-11
     * dejó pendiente para ClickHouse al reclasificarse a SIMPLE.
     * GET /api/informes/logistica/costo-envio-mensual?desde=&hasta=&zona=
     *     &transportista=
     *
     * Destinatarios: Gerente y Administrador. <b>DESPACHO NO</b>, al revés que
     * los otros tres de esta fase: este informe SÍ selecciona importes. El corte
     * lo hace la RUTA, igual que en el LOG-11 simple.
     *
     * La ruta es {@code /costo-envio-mensual} y no una variante de
     * {@code /costo-envio} a propósito: el informe simple sigue vivo y sirve otra
     * pregunta (la foto por zona, no la serie por mes).
     */
    @GetMapping("/costo-envio-mensual")
    public Map<String, Object> costoEnvioMensual(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(required = false) String zona,
            @RequestParam(required = false) String transportista) {
        return servicio.costoEnvioMensual(desde, hasta, zona, transportista);
    }

    // ── FASE 4 · la posventa ──────────────────────────────────────────────

    /**
     * OTD-LOG-07 — Días que tarda una devolución, tramo por tramo.
     * GET /api/informes/logistica/ciclo-devolucion?desde=&hasta=&estado=
     *     &motivo=&agrupar=
     *
     * Destinatarios (catálogo §7): Gerente, Soporte y Analista. Sin dinero:
     * son fechas y días.
     *
     * {@code agrupar} ∈ {mes (defecto), motivo, estado}. Cada columna de días
     * declara su propio {@code n_}: el ciclo hasta el cierre solo existe en 35
     * de 196 devoluciones y el ciclo hasta el desenlace en 53.
     *
     * Sin paginación: son 19 meses, 4 motivos o 9 estados.
     */
    @GetMapping("/ciclo-devolucion")
    public Map<String, Object> cicloDevolucion(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(required = false) String estado,
            @RequestParam(required = false) String motivo,
            @RequestParam(required = false) String agrupar) {
        return servicio.cicloDevolucion(desde, hasta, estado, motivo, agrupar);
    }

    /**
     * OTD-LOG-08 — Por qué devuelven y qué pasa con esa mercancía.
     * GET /api/informes/logistica/motivos-devolucion?desde=&hasta=&motivo=
     *     &resultado=&categoria=
     *
     * Destinatarios (catálogo §7): Gerente, Soporte, Analista y <b>BODEGA en
     * cantidades</b>. Bodega entra porque es quien inspecciona: la consulta no
     * selecciona ni un importe, y esa —no el motor— es la barrera del corte
     * financiero, igual que en OTD-COM-08.
     *
     * OJO con {@code resultado}: además de los tres del CHECK
     * ({@code apto_reventa}, {@code defectuoso}, {@code rechazado}) existe
     * {@code sin_inspeccionar}, que pone el ETL sobre las 112 líneas cuya
     * devolución aún no llegó a bodega. No es un hueco: es un desenlace
     * pendiente y tiene que poder filtrarse.
     */
    @GetMapping("/motivos-devolucion")
    public Map<String, Object> motivosDevolucion(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(required = false) String motivo,
            @RequestParam(required = false) String resultado,
            @RequestParam(required = false) String categoria) {
        return servicio.motivosDevolucion(desde, hasta, motivo, resultado, categoria);
    }

    /**
     * OTD-LOG-09 — De cada 100 envíos, cuántos acaban en devolución.
     * GET /api/informes/logistica/tasa-devolucion?desde=&hasta=&canal=
     *
     * Destinatarios (catálogo §7): Gerente, Analista y <b>Despacho en
     * conteos</b>. El informe cruza {@code fact_envio} (Fase 3C) con
     * {@code fact_devolucion} (Fase 4) mes a mes y no selecciona ningún
     * importe.
     *
     * Sin paginación: una fila por mes.
     */
    @GetMapping("/tasa-devolucion")
    public Map<String, Object> tasaDevolucion(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(required = false) String canal) {
        return servicio.tasaDevolucion(desde, hasta, canal);
    }

    /**
     * OTD-LOG-10 — Dinero reembolsado: cuánto, por qué vía y por qué motivo.
     * GET /api/informes/logistica/reembolsos?desde=&hasta=&metodo=&motivo=
     *     &agrupar=
     *
     * Destinatarios (catálogo §7): Gerente, Administrador y Soporte.
     * <b>DESPACHO NO</b>, al revés que los otros tres de esta fase: aquí SÍ
     * hay importes. El corte lo hace la RUTA, en su propia línea de
     * {@code SecurityConfig}, igual que la serie del costo de envío.
     *
     * El período es el del PAGO del reembolso, no el de la solicitud de la
     * devolución. {@code agrupar} ∈ {mes (defecto), metodo, motivo}.
     */
    @GetMapping("/reembolsos")
    public Map<String, Object> reembolsos(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(required = false) String metodo,
            @RequestParam(required = false) String motivo,
            @RequestParam(required = false) String agrupar) {
        return servicio.reembolsos(desde, hasta, metodo, motivo, agrupar);
    }
}
