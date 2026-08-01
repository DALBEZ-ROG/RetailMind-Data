package com.retailmind.informes;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * INFORMES TÁCTICOS COMPUESTOS — COMPRAS (fuente: ClickHouse).
 *
 * Comparte la ruta base {@code /api/informes/compras} con
 * {@link InformesComprasController}. Va en un controlador APARTE porque la
 * fuente es distinta: lee el data warehouse y no PostgreSQL, con su propia
 * degradación.
 *
 * <h2>Autorización (SecurityConfig, de lo específico a lo general)</h2>
 * <ul>
 *   <li>{@code /rechazos} (OTD-COM-07) — MIXTO: BODEGA entra porque el catálogo
 *       se lo da «en cantidades, sin montos», y la consulta no le selecciona el
 *       valor rechazado.</li>
 *   <li>{@code /puntualidad-pago}, {@code /gasto-mensual}, {@code /ciclo-compra}
 *       y {@code /evolucion-costo} — el ANALISTA entra, como pide el catálogo.
 *       Bodega y Despacho fuera: llevan dinero o precios.</li>
 *   <li>{@code /cumplimiento-plazo} y {@code /recuperacion-proveedor} — se apoyan
 *       en el comodín del departamento (ADMIN/GERENTE/COMPRAS). COM-05 no lleva
 *       importes y aun así el catálogo lo reserva a Compras y Gerencia: es
 *       material de negociación con el proveedor.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/informes/compras")
public class InformesComprasCompuestosController {

    private final InformesComprasCompuestosService servicio;

    public InformesComprasCompuestosController(InformesComprasCompuestosService servicio) {
        this.servicio = servicio;
    }

    /**
     * OTD-COM-03 — ¿Le pagamos a tiempo al proveedor?
     * GET /api/informes/compras/puntualidad-pago?desde=&hasta=&proveedor=
     *     &puntualidad=&metodo=&agrupar=
     *
     * Destinatarios (catálogo §4): Compras, Gerente, Administrador y Analista.
     *
     * {@code puntualidad} ∈ {@code a_tiempo | tarde | anticipado} — ojo, los
     * anticipados son un SUBCONJUNTO de los puntuales (506 de 564), no una
     * tercera categoría excluyente. {@code metodo} ∈ {@code transferencia |
     * efectivo}, los dos únicos con que se paga a proveedor.
     *
     * Sin paginación: 11 proveedores, 19 meses o 2 métodos.
     */
    @GetMapping("/puntualidad-pago")
    public Map<String, Object> puntualidadPago(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(required = false) String proveedor,
            @RequestParam(required = false) String puntualidad,
            @RequestParam(required = false) String metodo,
            @RequestParam(required = false) String agrupar) {
        return servicio.puntualidadPago(desde, hasta, proveedor, puntualidad, metodo, agrupar);
    }

    /**
     * OTD-COM-04 — Gasto de compras por proveedor y por mes.
     * GET /api/informes/compras/gasto-mensual?desde=&hasta=&proveedor=&agrupar=
     *
     * Destinatarios (catálogo §4): Compras, Gerente, Administrador y Analista.
     *
     * OJO con las fechas: {@code desde}/{@code hasta} filtran por la fecha de la
     * FACTURA del proveedor, no por la de la orden — el gasto se devenga cuando
     * el proveedor factura. El sobre lo declara en {@code salvedad}.
     *
     * Sin paginación: 19 meses, 11 proveedores o 2 bodegas.
     */
    @GetMapping("/gasto-mensual")
    public Map<String, Object> gastoMensual(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(required = false) String proveedor,
            @RequestParam(required = false) String agrupar) {
        return servicio.gastoMensual(desde, hasta, proveedor, agrupar);
    }

    /**
     * OTD-COM-05 — ¿Cumple el proveedor el plazo que prometió?
     * GET /api/informes/compras/cumplimiento-plazo?desde=&hasta=&proveedor=
     *     &resultado=&agrupar=
     *
     * Destinatarios (catálogo §4): Compras y Gerente (más Administrador, que
     * entra en todos). Es el único compuesto de Compras del que TAMBIÉN sale el
     * Analista, y no por dinero —no lleva ni un importe— sino porque el catálogo
     * lo trata como material de negociación con el proveedor.
     *
     * Cada fila declara sobre cuántas órdenes se calcula («Medidas»): de 865
     * órdenes solo 825 tienen a la vez fecha prometida y recepción.
     *
     * Sin paginación: 11 proveedores o 19 meses.
     */
    @GetMapping("/cumplimiento-plazo")
    public Map<String, Object> cumplimientoPlazo(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(required = false) String proveedor,
            @RequestParam(required = false) String resultado,
            @RequestParam(required = false) String agrupar) {
        return servicio.cumplimientoPlazo(desde, hasta, proveedor, resultado, agrupar);
    }

    /**
     * OTD-COM-06 — Días reales del ciclo de compra (emisión → recepción).
     * GET /api/informes/compras/ciclo-compra?desde=&hasta=&proveedor=&agrupar=
     *
     * Destinatarios (catálogo §4): Compras, Gerente y Analista.
     *
     * No es COM-05 con otro nombre: aquí la base son las 839 órdenes CON
     * recepción —exista o no promesa—, mientras COM-05 mide sobre los 825 pares
     * comparables. Poblaciones distintas y declaradas.
     *
     * Sin paginación: 11 proveedores, 19 meses o 2 bodegas.
     */
    @GetMapping("/ciclo-compra")
    public Map<String, Object> cicloCompra(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(required = false) String proveedor,
            @RequestParam(required = false) String agrupar) {
        return servicio.cicloCompra(desde, hasta, proveedor, agrupar);
    }

    /**
     * OTD-COM-07 — Mercancía rechazada en puerta, por proveedor y motivo.
     * GET /api/informes/compras/rechazos?desde=&hasta=&proveedor=&motivo=
     *     &soloConRechazo=&agrupar=
     *
     * Destinatarios (catálogo §4): Compras y Gerente; BODEGA entra «en
     * cantidades, sin montos» — su consulta no selecciona {@code valor_rechazado}
     * y el sobre viaja con {@code conValorizacion: false}.
     *
     * OJO con {@code motivo}: el filtro habla en SLUGS
     * ({@code empaque_danado}, {@code defecto_fabrica}, {@code caducidad_proxima},
     * {@code no_coincide}, {@code unidades_incompletas}) porque el valor guardado
     * es una frase con espacios. Son CINCO y no seis: el sexto valor del origen
     * lo funde el ETL (corrección C3.3).
     *
     * Sin paginación: 11 proveedores, 5 motivos, 19 meses o 10 categorías.
     */
    @GetMapping("/rechazos")
    public Map<String, Object> rechazos(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(required = false) String proveedor,
            @RequestParam(required = false) String motivo,
            @RequestParam(required = false) String soloConRechazo,
            @RequestParam(required = false) String agrupar) {
        return servicio.rechazos(desde, hasta, proveedor, motivo, soloConRechazo, agrupar);
    }

    /**
     * OTD-COM-12 — ¿Está subiendo el precio de lo que compramos?
     * GET /api/informes/compras/evolucion-costo?desde=&hasta=&proveedor=&buscar=
     *     &tendencia=&minimoCompras=&page=&size=
     *
     * Destinatarios (catálogo §4): Compras, Gerente y Analista.
     *
     * {@code tendencia} ∈ {@code subio | bajo | estable | sin_serie}; el último
     * son los pares con UNA sola compra, que no tienen evolución que mostrar y
     * conviene poder aislar. {@code minimoCompras} exige un mínimo de compras en
     * la serie (1–50) para quedarse con los productos que de verdad se repiten.
     *
     * PAGINADO: 1.041 pares (producto, proveedor).
     */
    @GetMapping("/evolucion-costo")
    public Map<String, Object> evolucionCosto(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(required = false) String proveedor,
            @RequestParam(required = false) String buscar,
            @RequestParam(required = false) String tendencia,
            @RequestParam(required = false) Integer minimoCompras,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return servicio.evolucionCosto(desde, hasta, proveedor, buscar, tendencia,
                minimoCompras, page, size);
    }

    /**
     * OTD-COM-09 — Cuánto recuperamos del proveedor por mercancía defectuosa.
     * GET /api/informes/compras/recuperacion-proveedor?desde=&hasta=&proveedor=
     *     &origen=&estado=&resolucion=&agrupar=
     *
     * Destinatarios (catálogo §5): Compras, Gerente y Administrador.
     *
     * <b>MUESTRA DÉBIL DECLARADA.</b> El catálogo lo clasifica <i>REQUIERE
     * VOLUMEN</i>: 8 devoluciones al proveedor, 6 con resolución, en 6 meses
     * distintos. El sobre lleva {@code salvedad} con esas cifras y cada fila
     * trae la columna «Resoluciones», que es el denominador de todo lo demás.
     *
     * OJO con {@code origen}: los valores son {@code rma} y {@code recepcion},
     * NO los {@code inspeccion_rma} / {@code recepcion_compra} que declara el
     * diseño del pipeline — con esos, el filtro casa con cero filas sin dar
     * ningún error (corrección C4.7).
     *
     * Sin paginación: 11 proveedores, 19 meses o unas pocas categorías.
     */
    @GetMapping("/recuperacion-proveedor")
    public Map<String, Object> recuperacionProveedor(
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(required = false) String proveedor,
            @RequestParam(required = false) String origen,
            @RequestParam(required = false) String estado,
            @RequestParam(required = false) String resolucion,
            @RequestParam(required = false) String agrupar) {
        return servicio.recuperacionProveedor(desde, hasta, proveedor, origen, estado,
                resolucion, agrupar);
    }
}
