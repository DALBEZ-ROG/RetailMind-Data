package com.retailmind.informes;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * INFORMES TÁCTICOS COMPUESTOS — GERENCIA / DIRECCIÓN (fuente: ClickHouse).
 *
 * Comparte la ruta base {@code /api/informes/gerencia} con
 * {@link InformesGerenciaController}, del que se separa porque la fuente es el
 * data warehouse y no PostgreSQL.
 *
 * AUTORIZACIÓN: línea propia en {@code SecurityConfig} y ANTES del comodín de
 * Gerencia. No es una formalidad: el comodín del departamento es
 * ADMIN + GERENTE, y el catálogo suma el ANALISTA a estos dos objetivos. Van en
 * su propia línea para que ese añadido no arrastre por descuido a OTD-GER-08 y
 * OTD-GER-09, que son los datos sensibles de seguridad del sistema.
 */
@RestController
@RequestMapping("/api/informes/gerencia")
public class InformesGerenciaCompuestosController {

    private final InformesGerenciaCompuestosService servicio;
    private final InformesPrevisionService prevision;

    public InformesGerenciaCompuestosController(InformesGerenciaCompuestosService servicio,
                                                InformesPrevisionService prevision) {
        this.servicio = servicio;
        this.prevision = prevision;
    }

    /**
     * OTD-GER-13 — PREVISIÓN DE DEMANDA a tres meses (fase E2 del nivel
     * estratégico, §5.1).
     * GET /api/informes/gerencia/prevision-demanda?nivel=&categoria=&horizonte=
     *     &buscar=&page=&size=
     *
     * Destinatarios (§5.1.8): Administrador, Gerente y Analista. Sirve a
     * D-10.1, la previsión con la que se fijan las metas del próximo período.
     *
     * El MISMO informe se sirve en {@code /api/informes/compras/prevision-demanda}
     * para Compras: cambia el destinatario, no el dato. Los dos delegan en
     * {@link InformesPrevisionService} para que no puedan divergir.
     *
     * El sobre trae, además de la tabla: {@code serie} (los meses observados y
     * los previstos en una sola lista, para pintar los dos en el MISMO gráfico)
     * y {@code salvedad} con las cinco limitaciones que §5.1.10 exige declarar
     * en pantalla — empezando por la que puede costar dinero: <b>se prevé la
     * VENTA y no la demanda</b>, así que un quiebre de stock pasado se lee como
     * demanda baja y se perpetúa comprando de menos.
     */
    @GetMapping("/prevision-demanda")
    public Map<String, Object> previsionDemanda(
            @RequestParam(required = false) String nivel,
            @RequestParam(required = false) String categoria,
            @RequestParam(required = false) Integer horizonte,
            @RequestParam(required = false) String buscar,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return prevision.previsionDemanda(nivel, categoria, horizonte, buscar, page, size);
    }

    /**
     * OTD-GER-02 — Balanza mensual: entra por ventas vs sale a proveedores.
     * GET /api/informes/gerencia/balanza?desde=&hasta=&page=&size=
     *
     * Destinatarios (catálogo §8): Gerente, Administrador y Analista.
     * Base CAJA (cobros y pagos efectivos); la lectura devengada del lado de
     * compras llega con {@code fact_orden_compra} en la Fase 3.
     */
    @GetMapping("/balanza")
    public Map<String, Object> balanza(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return servicio.balanza(desde, hasta, page, size);
    }

    /**
     * OTD-GER-05 — Descuento otorgado por cupón y período.
     * GET /api/informes/gerencia/descuento-cupones?desde=&hasta=&cupon=&page=&size=
     *
     * Destinatarios: Gerente, Analista, Administrador. Grano (mes, cupón): es
     * el recorrido histórico del canje, no la foto de vigencia — ésa es
     * OTD-GER-04, que sigue sirviéndose de PostgreSQL en {@code /cupones}.
     */
    @GetMapping("/descuento-cupones")
    public Map<String, Object> descuentoCupones(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(required = false) String cupon,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return servicio.descuentoCupones(desde, hasta, cupon, page, size);
    }

    // ── FASE 4 · margen, descuento y efecto de las promociones ────────────

    /**
     * OTD-GER-03 — Qué categorías dejan más ganancia.
     * GET /api/informes/gerencia/margen-categoria?desde=&hasta=&canal=
     *     &categoria=&agrupar=
     *
     * Destinatarios (catálogo §9): Gerente, Analista y Administrador. DINERO
     * de principio a fin: Bodega y Despacho fuera, por RUTA.
     *
     * {@code agrupar} ∈ {categoria (defecto), mes, marca}. El sobre viaja con
     * {@code salvedad}: el margen se calcula contra el costo VIGENTE, porque
     * no hay histórico de costos (misma salvedad que OTD-INV-09).
     *
     * Sin paginación: 11 categorías, 19 meses o las marcas del catálogo.
     */
    @GetMapping("/margen-categoria")
    public Map<String, Object> margenCategoria(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(required = false) String canal,
            @RequestParam(required = false) String categoria,
            @RequestParam(required = false) String agrupar) {
        return servicio.margenCategoria(desde, hasta, canal, categoria, agrupar);
    }

    /**
     * OTD-GER-10 — Ganancia real producto por producto, con buscador.
     * GET /api/informes/gerencia/margen-producto?desde=&hasta=&canal=
     *     &categoria=&buscar=&page=&size=
     *
     * Destinatarios (catálogo §9): Gerente, Analista y Administrador. Es la
     * vista fina de GER-03 y lleva el mismo dinero y la misma salvedad de
     * costo vigente.
     *
     * Solo aparecen los productos CON venta en el período: un producto sin
     * ventas no tiene margen realizado que mostrar.
     */
    @GetMapping("/margen-producto")
    public Map<String, Object> margenProducto(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(required = false) String canal,
            @RequestParam(required = false) String categoria,
            @RequestParam(required = false) String buscar,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return servicio.margenProducto(desde, hasta, canal, categoria, buscar, page, size);
    }

    /**
     * OTD-GER-11 — Descuento total entregado, por mes y por producto.
     * GET /api/informes/gerencia/descuento-total?desde=&hasta=&canal=
     *     &categoria=&agrupar=&page=&size=
     *
     * Destinatarios (catálogo §9): Gerente, Analista y Administrador.
     *
     * Suma las DOS capas —promoción por línea y cupón prorrateado— y las
     * muestra separadas, porque se deciden en sitios distintos. No confundir
     * con OTD-GER-05, que es solo el cupón y con grano (mes, cupón): éste es
     * el descuento COMPLETO y con grano de producto.
     *
     * {@code agrupar} ∈ {mes (defecto), producto, categoria}.
     */
    @GetMapping("/descuento-total")
    public Map<String, Object> descuentoTotal(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(required = false) String canal,
            @RequestParam(required = false) String categoria,
            @RequestParam(required = false) String agrupar,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return servicio.descuentoTotal(desde, hasta, canal, categoria, agrupar, page, size);
    }

    /**
     * OTD-GER-07 — ¿Las promociones hacen vender más? Antes vs durante.
     * GET /api/informes/gerencia/efecto-promociones?buscar=&categoria=
     *     &page=&size=
     *
     * Destinatarios (catálogo §9): Gerente y Analista.
     *
     * <b>MUESTRA DÉBIL DECLARADA.</b> El catálogo lo clasifica <i>REQUIERE
     * VOLUMEN</i>: ~195 líneas dentro de ventana (~123 con descuento aplicado)
     * frente a ~4.133 de línea base. El sobre lleva {@code salvedad} con esas
     * cifras, cada fila trae {@code lineas_durante} y {@code lineas_antes}, y
     * la tabla se ordena por VOLUMEN y no por la variación — ordenar por la
     * variación pondría arriba justo los casos que no se sostienen.
     *
     * No lleva filtro de fechas: el período de comparación lo define la ventana
     * de CADA promoción, no el usuario. Un rango externo recortaría el «antes»
     * de unas promociones y no el de otras, y las filas dejarían de ser
     * comparables entre sí.
     */
    @GetMapping("/efecto-promociones")
    public Map<String, Object> efectoPromociones(
            @RequestParam(required = false) String buscar,
            @RequestParam(required = false) String categoria,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return servicio.efectoPromociones(buscar, categoria, page, size);
    }
}
