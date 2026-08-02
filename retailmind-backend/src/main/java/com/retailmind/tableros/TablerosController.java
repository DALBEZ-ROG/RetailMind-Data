package com.retailmind.tableros;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * TABLEROS DE DIRECCIÓN — nivel ESTRATÉGICO, fase E1-A.
 *
 * Convención de ruta: {@code /api/tableros/{tablero}}, todos GET de solo
 * lectura, todos servidos desde el data warehouse ({@code retailmind_dwh}).
 *
 * <h2>Un endpoint por tablero, y no uno por elemento</h2>
 * La justificación entera vive en {@link TableroServiceBase}. En corto: los
 * elementos de un tablero comparten filtros, se leen juntos para tomar UNA
 * decisión, deben llevar la MISMA marca de agua y deben degradar a la vez. Seis
 * peticiones darían seis oportunidades de mostrar medio tablero coherente y
 * medio no.
 *
 * <h2>Ninguno de estos métodos lleva {@code @Transactional}</h2>
 * No tocan PostgreSQL. La regla de oro del proyecto —«todo acceso a Postgres va
 * dentro de una transacción o corre sin {@code SET LOCAL ROLE}»— no aplica
 * aquí; anotarlos abriría una transacción inútil y haría que
 * {@code PgSessionRoleAspect} asumiera un rol de grupo para nada.
 *
 * <h2>Autorización</h2>
 * Los tres tableros de esta fase LLEVAN DINERO, así que BODEGA y DESPACHO
 * quedan fuera. Como ClickHouse no tiene GRANT por columna, el corte lo hace la
 * RUTA y va enumerada <b>por nombre</b> en {@code SecurityConfig} —nunca con
 * comodín— para que un tablero futuro no herede el permiso (R-5 del diseño).
 * SOPORTE entra solo a {@code /cliente-posventa}, y allí el servicio recorta el
 * tablero a los bloques de tickets y devoluciones.
 *
 * <h2>Lo que NO sirve este controlador</h2>
 * Dos elementos de esta fase no salen del almacén y el diseño decidió no crear
 * tabla para ellos (R-7): el carrito abandonado de T-1 y el sobre-stock del
 * presente de T-2. Los pide la pantalla llamando a los informes SIMPLES ya
 * construidos sobre PostgreSQL —{@code /api/informes/ventas/carritos-abandonados}
 * y {@code /api/informes/inventario/sobre-stock}—, que siguen funcionando con
 * el almacén apagado.
 */
@RestController
@RequestMapping("/api/tableros")
public class TablerosController {

    private final TableroOmnicanalService omnicanal;
    private final TableroRentabilidadService rentabilidad;
    private final TableroClientePosventaService clientePosventa;
    private final TableroOperacionService operacion;
    private final TableroCostoOperacionService costoOperacion;
    private final TableroAbastecimientoService abastecimiento;
    private final TableroGobiernoDatoService gobiernoDato;

    public TablerosController(TableroOmnicanalService omnicanal,
                              TableroRentabilidadService rentabilidad,
                              TableroClientePosventaService clientePosventa,
                              TableroOperacionService operacion,
                              TableroCostoOperacionService costoOperacion,
                              TableroAbastecimientoService abastecimiento,
                              TableroGobiernoDatoService gobiernoDato) {
        this.omnicanal = omnicanal;
        this.rentabilidad = rentabilidad;
        this.clientePosventa = clientePosventa;
        this.operacion = operacion;
        this.costoOperacion = costoOperacion;
        this.abastecimiento = abastecimiento;
        this.gobiernoDato = gobiernoDato;
    }

    /**
     * T-1 — Tablero Omnicanal (OE-06; decisiones D-06.1, D-06.2, D-06.3).
     * GET /api/tableros/omnicanal?desde=&hasta=&canal=&categoria=
     *
     * Destinatarios: ADMIN, GERENTE, ANALISTA.
     */
    @GetMapping("/omnicanal")
    public Map<String, Object> omnicanal(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(required = false) String canal,
            @RequestParam(required = false) String categoria) {
        return omnicanal.tablero(desde, hasta, canal, categoria);
    }

    /**
     * T-2 — Tablero de Rentabilidad y Rotación (OE-07; D-07.1 a D-07.4).
     * GET /api/tableros/rentabilidad?desde=&hasta=&categoria=&marca=&bodega=
     *
     * Destinatarios: ADMIN, GERENTE, ANALISTA. El filtro de bodega solo alcanza
     * a los bloques de stock, y el sobre lo declara.
     */
    @GetMapping("/rentabilidad")
    public Map<String, Object> rentabilidad(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(required = false) String categoria,
            @RequestParam(required = false) String marca,
            @RequestParam(required = false) String bodega) {
        return rentabilidad.tablero(desde, hasta, categoria, marca, bodega);
    }

    /**
     * T-3 — Tablero de Cliente y Posventa (OE-08; D-08.2, D-08.3, D-08.4).
     * GET /api/tableros/cliente-posventa?desde=&hasta=&categoria=&categoriaTicket=
     *
     * Destinatarios: ADMIN, GERENTE, ANALISTA con el tablero completo; SOPORTE
     * con el alcance de posventa. El recorte de SOPORTE lo hace la CONSULTA —los
     * bloques de valor del cliente y de reseñas no se ejecutan— y el sobre
     * declara cuáles omitió, porque ClickHouse no tiene una barrera de motor
     * que respalde el corte.
     */
    @GetMapping("/cliente-posventa")
    public Map<String, Object> clientePosventa(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(required = false) String categoria,
            @RequestParam(required = false) String categoriaTicket) {
        return clientePosventa.tablero(desde, hasta, categoria, categoriaTicket);
    }

    /**
     * T-4 — Tablero de Operación y Última Milla (OE-09; D-09.1, D-09.2, D-09.4).
     * GET /api/tableros/operacion?desde=&hasta=&transportista=&zona=&bodega=
     *
     * Destinatarios: ADMIN, GERENTE, ANALISTA, <b>DESPACHO</b> y <b>BODEGA</b>.
     *
     * Es el ÚNICO tablero que Despacho y Bodega pueden abrir, y lo que lo
     * permite es que su consulta <b>no selecciona un solo importe</b>: todo se
     * mide en envíos, días, horas y unidades. La regla no vive solo en este
     * comentario — {@code validar_tableros.py} recorre la respuesta entera
     * buscando columnas con aspecto monetario y falla si aparece una.
     */
    @GetMapping("/operacion")
    public Map<String, Object> operacion(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(required = false) String transportista,
            @RequestParam(required = false) String zona,
            @RequestParam(required = false) String bodega) {
        return operacion.tablero(desde, hasta, transportista, zona, bodega);
    }

    /**
     * T-5 — Tablero de Costo de la Operación (OE-09; D-09.3).
     * GET /api/tableros/costo-operacion?desde=&hasta=&zona=&transportista=
     *
     * Destinatarios: ADMIN, GERENTE, ANALISTA. DESPACHO y BODEGA quedan fuera
     * POR RUTA: es el gemelo con dinero de T-4 y existe separado justamente
     * para que ellos puedan tener el suyo.
     */
    @GetMapping("/costo-operacion")
    public Map<String, Object> costoOperacion(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(required = false) String zona,
            @RequestParam(required = false) String transportista) {
        return costoOperacion.tablero(desde, hasta, zona, transportista);
    }

    /**
     * T-6 — Tablero de Abastecimiento (OE-11; D-11.2, D-11.3, D-11.4).
     * GET /api/tableros/abastecimiento?desde=&hasta=&proveedor=&categoria=&alcance=
     *
     * Destinatarios: ADMIN, GERENTE, <b>COMPRAS</b>, ANALISTA.
     *
     * {@code alcance} ∈ {entregadas (defecto), en_camino, canceladas, todas}, y
     * el defecto no es una comodidad: con «todas», el mejor proveedor del
     * catálogo pasa a ser el peor por órdenes que canceló el propio negocio.
     */
    @GetMapping("/abastecimiento")
    public Map<String, Object> abastecimiento(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(required = false) String proveedor,
            @RequestParam(required = false) String categoria,
            @RequestParam(required = false) String alcance) {
        return abastecimiento.tablero(desde, hasta, proveedor, categoria, alcance);
    }

    /**
     * T-7 — Tablero de Gobierno del Dato (OE-10; D-10.2, D-10.3).
     * GET /api/tableros/gobierno-dato?corridas=
     *
     * <b>DATO SENSIBLE: ADMIN y GERENTE únicamente</b>, el corte más estricto
     * del sistema. Y en la auditoría el corte lo hace ESTA RUTA y no el motor:
     * {@code grp_analista} sí tiene SELECT sobre {@code log_auditoria}.
     *
     * Los dos elementos sensibles —auditoría y accesos— NO se sirven desde
     * aquí: los pide la pantalla a los informes OTD-GER-08 y OTD-GER-09, que ya
     * existen sobre PostgreSQL y siguen respondiendo con el almacén caído.
     */
    @GetMapping("/gobierno-dato")
    public Map<String, Object> gobiernoDato(
            @RequestParam(required = false) Integer corridas) {
        return gobiernoDato.tablero(corridas);
    }
}
