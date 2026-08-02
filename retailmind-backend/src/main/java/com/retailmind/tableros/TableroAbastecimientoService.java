package com.retailmind.tableros;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * T-6 · TABLERO DE ABASTECIMIENTO — nivel estratégico, objetivo OE-11.
 *
 * Sirve tres decisiones de dirección (§3.6 del diseño):
 * <ul>
 *   <li><b>D-11.2</b> a qué proveedor se concentra la compra de cada familia,
 *       y a cuál se le retira;</li>
 *   <li><b>D-11.3</b> qué condiciones de pago se renegocian;</li>
 *   <li><b>D-11.4</b> qué reclamo de calidad se escala al proveedor.</li>
 * </ul>
 *
 * Roles: ADMIN, GERENTE, <b>COMPRAS</b>, ANALISTA. Lleva dinero —es el centro de
 * costo dominante del negocio, $22,47 M facturados— y BODEGA y DESPACHO quedan
 * fuera por ruta.
 *
 * <h2>Las dos correcciones que deciden si el tablero acierta o miente</h2>
 * <ol>
 *   <li><b>C5.1 · el mes del gasto es el de la FACTURA, no el de la orden.</b>
 *       360 de 839 facturas caen en un mes distinto al de su OC. Agrupando por
 *       el mes de la orden, <b>$4,6 M cambian de mes</b> y el total sigue
 *       cuadrando al centavo: no hay ninguna suma que falle, solo la FORMA de
 *       la curva es otra.</li>
 *   <li><b>C5.2 · «entrega incompleta» son tres cosas distintas.</b> De 259
 *       líneas cortas, solo 165 son incumplimiento del proveedor: 41 vienen de
 *       camino y 53 son de órdenes que canceló Compras. Con las 259, Comercial
 *       El Costeno pasa de <b>mejor proveedor (99,71 %) a PEOR (91,77 %)</b> —y
 *       la conclusión de D-11.2 sería exactamente la contraria a la correcta.
 *       Por eso el filtro {@code alcance} arranca en {@code entregadas}.</li>
 * </ol>
 */
@Service
public class TableroAbastecimientoService extends TableroServiceBase {

    private static final String CODIGO = "T-6";
    private static final String TITULO = "Tablero de Abastecimiento";
    private static final List<String> DECISIONES = List.of("D-11.2", "D-11.3", "D-11.4");

    private static final String ORDEN = DWH + ".fact_orden_compra";
    private static final String LINEA = DWH + ".fact_compra_linea";
    private static final String CAJA = DWH + ".fact_flujo_caja";
    private static final String DEFECTO = DWH + ".fact_devolucion_proveedor";

    /**
     * Alcance de la entrega. La MISMA lista blanca que OTD-COM-11, y a
     * propósito: las dos pantallas responden la misma pregunta con distinta
     * altura, y dos listas separadas acabarían divergiendo.
     *
     * Los estados que compone cada valor salen del CHECK del motor, no de un
     * documento: {@code entregadas} = recibida + recibida_parcial (2.855
     * líneas), {@code en_camino} = confirmada + enviada (41 líneas cortas),
     * {@code canceladas} = cancelada (53).
     */
    private static final Set<String> ALCANCES =
            Set.of("entregadas", "en_camino", "canceladas", "todas");

    public TableroAbastecimientoService(@Qualifier("pgJdbcTemplate") JdbcTemplate pg,
                                        @Qualifier("clickHouseJdbc") JdbcTemplate ch) {
        super(pg, ch);
    }

    /**
     * @param desde     fecha ISO; se compara contra la fecha de FACTURA en el
     *                  gasto y contra la de emisión de la orden en el resto
     * @param hasta     fecha ISO; idem
     * @param proveedor nombre exacto, o null = los once
     * @param categoria categoría de producto, o null = todas
     * @param alcance   entregadas (defecto) | en_camino | canceladas | todas
     */
    public Map<String, Object> tablero(String desde, String hasta, String proveedor,
                                       String categoria, String alcance) {
        String fDesde = fecha(desde, "desde");
        String fHasta = fecha(hasta, "hasta");
        exigirRangoValido(fDesde, fHasta);
        String fProveedor = texto(proveedor);
        String fCategoria = texto(categoria);
        String fAlcance = opcion(alcance, ALCANCES, "alcance");
        if (fAlcance == null) {
            fAlcance = "entregadas";
        }
        final String alc = fAlcance;

        return servir(CODIGO, TITULO, DECISIONES, () -> {
            Filtros orden = filtrosOrden(fDesde, fHasta, fProveedor);
            Filtros factura = filtrosFactura(fDesde, fHasta, fProveedor);
            Filtros linea = filtrosLinea(fDesde, fHasta, fProveedor, fCategoria);

            Map<String, Object> tot = totales(orden, factura, linea);

            List<Map<String, Object>> bloques = new ArrayList<>();
            bloques.add(gastoPorProveedorYMes(factura, tot));
            bloques.add(fichaDelProveedor(orden, linea, tot));
            bloques.add(entregasIncompletas(linea, alc));
            bloques.add(rechazoEnPuerta(linea, tot));
            bloques.add(evolucionDelCosto(linea));
            bloques.add(deudaYVencimientos(orden, tot));
            bloques.add(puntualidadDelPago(fDesde, fHasta, fProveedor));
            bloques.add(recuperacionDefectuosos(fDesde, fHasta, fProveedor, fCategoria));

            List<String> salvedades = new ArrayList<>();
            salvedades.add("El filtro de fechas NO significa lo mismo en todos los bloques, y "
                    + "es deliberado: el GASTO se filtra por la fecha de la FACTURA —360 de "
                    + "839 facturas caen en un mes distinto al de su orden— y el resto por la "
                    + "fecha de emisión de la ORDEN, porque hablan del ciclo de compra. Cada "
                    + "bloque declara cuál usa.");
            if (fCategoria != null) {
                salvedades.add("El filtro de categoría «" + fCategoria + "» alcanza a los "
                        + "bloques de LÍNEA (entregas incompletas, rechazo, evolución del "
                        + "costo y defectuosos). No alcanza al gasto, a la deuda ni a la "
                        + "puntualidad del pago: esos se miden a grano de orden y de pago, y "
                        + "una factura mezcla categorías.");
            }

            return sobreTableroConAlcance(CODIGO, TITULO, DECISIONES,
                    periodo(fDesde, fHasta, "fact_orden_compra"),
                    kpis(tot), bloques, salvedades, alc);
        });
    }

    private Map<String, Object> sobreTableroConAlcance(String codigo, String titulo,
                                                       List<String> decisiones,
                                                       Map<String, Object> periodo,
                                                       List<Map<String, Object>> kpis,
                                                       List<Map<String, Object>> bloques,
                                                       List<String> salvedades, String alcance) {
        Map<String, Object> t = sobreTablero(codigo, titulo, decisiones, periodo, kpis, bloques,
                salvedades, "fact_orden_compra", "fact_compra_linea", "fact_flujo_caja",
                "fact_devolucion_proveedor");
        t.put("alcance", alcance);
        return t;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Filtros
    // ═════════════════════════════════════════════════════════════════════

    /** Por fecha de EMISIÓN de la orden: el ciclo de compra. */
    private static Filtros filtrosOrden(String desde, String hasta, String proveedor) {
        Filtros f = new Filtros();
        f.y("mes >= toStartOfMonth(toDate(?))", desde);
        f.y("mes <= toStartOfMonth(toDate(?))", hasta);
        f.y("proveedor = ?", proveedor);
        return f;
    }

    /** Por fecha de FACTURA: el gasto (C5.1). */
    private static Filtros filtrosFactura(String desde, String hasta, String proveedor) {
        Filtros f = new Filtros();
        f.y("fecha_factura IS NOT NULL");
        f.y("toStartOfMonth(fecha_factura) >= toStartOfMonth(toDate(?))", desde);
        f.y("toStartOfMonth(fecha_factura) <= toStartOfMonth(toDate(?))", hasta);
        f.y("proveedor = ?", proveedor);
        return f;
    }

    private static Filtros filtrosLinea(String desde, String hasta, String proveedor,
                                        String categoria) {
        Filtros f = new Filtros();
        f.y("mes >= toStartOfMonth(toDate(?))", desde);
        f.y("mes <= toStartOfMonth(toDate(?))", hasta);
        f.y("proveedor = ?", proveedor);
        f.y("categoria = ?", categoria);
        return f;
    }

    /** Fragmento de estados que compone cada alcance. Constante del código. */
    private static String estadosDe(String alcance) {
        return switch (alcance) {
            case "en_camino"  -> "o.estado IN ('confirmada', 'enviada')";
            case "canceladas" -> "o.estado = 'cancelada'";
            case "todas"      -> "1";
            default           -> "o.estado IN ('recibida', 'recibida_parcial')";
        };
    }

    // ═════════════════════════════════════════════════════════════════════
    // Totales y KPI
    // ═════════════════════════════════════════════════════════════════════

    private Map<String, Object> totales(Filtros orden, Filtros factura, Filtros linea) {
        java.util.Map<String, Object> r = new java.util.LinkedHashMap<>();

        List<Map<String, Object>> a = ch.queryForList(
                "SELECT count() AS ordenes, "
                + "       countIf(fecha_recepcion IS NOT NULL) AS con_recepcion, "
                + "       countIf(cumplio_promesa = 1) AS cumplieron, "
                + "       countIf(cumplio_promesa IS NOT NULL) AS con_promesa, "
                + "       round(avg(dias_ciclo_real), 2) AS ciclo_medio, "
                + "       sum(cxp_saldo_pendiente) AS saldo "
                + "FROM " + ORDEN + " WHERE 1 " + orden.where(), orden.args());
        r.putAll(a.isEmpty() ? Map.of() : a.get(0));

        List<Map<String, Object>> b = ch.queryForList(
                "SELECT count() AS facturas, sum(factura_total) AS gasto "
                + "FROM " + ORDEN + " WHERE 1 " + factura.where(), factura.args());
        r.putAll(b.isEmpty() ? Map.of() : b.get(0));

        List<Map<String, Object>> c = ch.queryForList(
                "SELECT sum(cantidad_recibida) AS recibidas, "
                + "       sum(cantidad_rechazada) AS rechazadas, "
                + "       count() AS lineas "
                + "FROM " + LINEA + " WHERE 1 " + linea.where(), linea.args());
        r.putAll(c.isEmpty() ? Map.of() : c.get(0));
        return r;
    }

    private List<Map<String, Object>> kpis(Map<String, Object> tot) {
        long recibidas = num(tot.get("recibidas"));
        long rechazadas = num(tot.get("rechazadas"));

        List<Map<String, Object>> k = new ArrayList<>();
        k.add(kpi("Gasto de compra del período", dec(tot.get("gasto")), "moneda",
                fmt(num(tot.get("facturas"))) + " facturas de proveedor, fechadas por la "
                + "FACTURA y no por la orden: 360 de 839 caen en un mes distinto y agrupar "
                + "por la orden desplaza $4,6 M sin descuadrar el total."));
        k.add(kpi("Saldo abierto en cuentas por pagar", dec(tot.get("saldo")), "moneda",
                "Lo que queda por pagar de las órdenes del período. Es la financiación más "
                + "barata que tiene el negocio y también su vencimiento."));
        k.add(kpi("Órdenes que cumplieron el plazo",
                porcentaje(num(tot.get("cumplieron")), num(tot.get("con_promesa"))),
                "porcentaje",
                fmt(num(tot.get("cumplieron"))) + " de " + fmt(num(tot.get("con_promesa")))
                + " órdenes con fecha prometida Y llegada. Las que no tienen una de las dos "
                + "no cuentan como incumplidas: no son medibles."));
        k.add(kpi("Ciclo medio de compra", tot.get("ciclo_medio"), "dias",
                "De la emisión de la orden a la recepción, sobre las "
                + fmt(num(tot.get("con_recepcion"))) + " órdenes que llegaron."));
        k.add(kpi("Unidades rechazadas en puerta",
                porcentaje(rechazadas, recibidas + rechazadas), "porcentaje",
                fmt(rechazadas) + " de " + fmt(recibidas + rechazadas) + " unidades que "
                + "LLEGARON. El denominador es lo que llegó, no lo que se pidió: el rechazo "
                + "no siempre se descuenta de lo recibido y sobre lo pedido el porcentaje "
                + "sale inflado."));
        return k;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Elemento 1 — Gasto de compra por proveedor y mes
    // ═════════════════════════════════════════════════════════════════════

    /**
     * <h3>C5.1 · agrupado por {@code toStartOfMonth(fecha_factura)}</h3>
     * NO por la columna {@code mes}, que es el mes de la ORDEN. La diferencia no
     * es marginal: enero de 2025 pasa de $2,27 M a $3,46 M (+52,6 %) y julio de
     * 2026 cae un 46,8 %, es decir un arranque inflado y una caída final. El
     * total anual es idéntico en las dos versiones, y eso es justo lo que hace
     * peligroso el error: ningún control salta.
     */
    private Map<String, Object> gastoPorProveedorYMes(Filtros f, Map<String, Object> tot) {
        List<Map<String, Object>> items = ch.queryForList(
                "SELECT periodo, proveedor, facturas, t_gasto AS gasto, "
                + "       unidades, participacion_pct "
                + "FROM ( "
                + "  SELECT formatDateTime(toStartOfMonth(fecha_factura), '%Y-%m') AS periodo, "
                + "         toStartOfMonth(fecha_factura) AS mes_dato, "
                + "         proveedor, "
                + "         count() AS facturas, "
                + "         sum(factura_total) AS t_gasto, "
                + "         sum(unidades_recibidas) AS unidades, "
                + "         " + pct("sum(factura_total)",
                                    "sum(sum(factura_total)) OVER "
                                    + "(PARTITION BY toStartOfMonth(fecha_factura))")
                + "             AS participacion_pct "
                + "  FROM " + ORDEN + " WHERE 1 " + f.where() + " "
                + "  GROUP BY toStartOfMonth(fecha_factura), proveedor "
                + ") ORDER BY mes_dato, t_gasto DESC", f.args());
        return conSalvedad(bloque("gasto_proveedor_mes",
                "Gasto de compra por proveedor y mes",
                "barras_apiladas",
                fmt(num(tot.get("facturas"))) + " facturas de proveedor · "
                + money(dec(tot.get("gasto"))) + ". El mes es el de la FACTURA.",
                items),
                "El mes es el de la FACTURA, no el de la orden de compra. Son fechas "
                + "distintas en 360 de las 839 facturas, y agrupar por el mes de la orden "
                + "desplaza $4.628.932,62 entre meses SIN que el total deje de cuadrar al "
                + "centavo: no hay suma que falle, solo la curva cambia de forma. El filtro "
                + "de fechas de este bloque también es de la factura.");
    }

    // ═════════════════════════════════════════════════════════════════════
    // Elemento 2 — Ficha comparativa del proveedor
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Los cuatro ejes de D-11.2 en una sola vista: cuánto cuesta, cuánto tarda,
     * si cumple lo prometido y con qué calidad entrega. Es la decisión entera.
     *
     * El gasto sale de {@code factura_total} y no del total de la orden: el
     * proveedor factura lo que entregó, y las dos cifras difieren en 119
     * órdenes por $226.070,31 porque 72 quedaron en recepción parcial. Sumar la
     * orden inventaría un +2,4 % de gasto.
     */
    private Map<String, Object> fichaDelProveedor(Filtros orden, Filtros linea,
                                                  Map<String, Object> tot) {
        Object[] args = new Object[orden.args().length + linea.args().length];
        System.arraycopy(orden.args(), 0, args, 0, orden.args().length);
        System.arraycopy(linea.args(), 0, args, orden.args().length, linea.args().length);

        List<Map<String, Object>> items = ch.queryForList(
                "SELECT o.proveedor, o.ordenes, o.gasto, o.ciclo_medio, o.desvio_medio, "
                + "       o.cumplieron, o.con_promesa, o.cumplimiento_pct, "
                + "       l.unidades_recibidas, l.unidades_rechazadas, l.rechazo_pct, "
                + "       l.precio_medio "
                + "FROM ( "
                + "  SELECT proveedor, "
                + "         count() AS ordenes, "
                + "         sum(factura_total) AS gasto, "
                + "         round(avg(dias_ciclo_real), 2) AS ciclo_medio, "
                + "         round(avg(dias_desvio_promesa), 2) AS desvio_medio, "
                + "         countIf(cumplio_promesa = 1) AS cumplieron, "
                + "         countIf(cumplio_promesa IS NOT NULL) AS con_promesa, "
                + "         " + pct("countIf(cumplio_promesa = 1)",
                                    "countIf(cumplio_promesa IS NOT NULL)")
                + "             AS cumplimiento_pct "
                + "  FROM " + ORDEN + " WHERE 1 " + orden.where() + " GROUP BY proveedor "
                + ") o "
                + "LEFT JOIN ( "
                + "  SELECT proveedor, "
                + "         sum(cantidad_recibida) AS unidades_recibidas, "
                + "         sum(cantidad_rechazada) AS unidades_rechazadas, "
                + "         " + pct("sum(cantidad_rechazada)",
                                    "sum(cantidad_recibida) + sum(cantidad_rechazada)")
                + "             AS rechazo_pct, "
                + "         round(avg(precio_unitario), 2) AS precio_medio "
                + "  FROM " + LINEA + " WHERE 1 " + linea.where() + " GROUP BY proveedor "
                + ") l ON l.proveedor = o.proveedor "
                + "ORDER BY o.gasto DESC", args);
        return conSalvedad(bloque("ficha_proveedor",
                "Ficha comparativa del proveedor",
                "ranking",
                fmt(items.size()) + " proveedores con órdenes en el período. Cada eje declara "
                + "su propia base: el cumplimiento sobre las órdenes con promesa Y llegada, y "
                + "el rechazo sobre las unidades que LLEGARON.",
                items),
                "El gasto es lo FACTURADO, no el total de la orden: el proveedor factura lo "
                + "que entregó, y las dos cifras difieren en 119 órdenes por $226.070,31 "
                + "porque 72 quedaron en recepción parcial. Un desvío NEGATIVO en el plazo "
                + "significa que llegó antes de lo prometido.");
    }

    // ═════════════════════════════════════════════════════════════════════
    // Elemento 3 — Entrega incompleta por proveedor
    // ═════════════════════════════════════════════════════════════════════

    /**
     * <h3>C5.2 · el filtro que invierte el ranking</h3>
     * «Línea corta» son tres cosas distintas y solo una es culpa del proveedor:
     * 165 líneas entregadas de menos, 41 que vienen de camino y 53 de órdenes
     * que canceló Compras. Con {@code alcance = todas}, <b>Comercial El Costeno
     * pasa de 99,71 % a 91,77 %</b> —de mejor proveedor a peor— por cuatro
     * órdenes que canceló el propio negocio. Por eso el valor por defecto es
     * {@code entregadas}, y el bloque dice en qué alcance está.
     */
    private Map<String, Object> entregasIncompletas(Filtros linea, String alcance) {
        List<Map<String, Object>> items = ch.queryForList(
                "SELECT proveedor, "
                + "       count() AS lineas, "
                + "       countIf(cantidad_recibida < cantidad_pedida) AS lineas_cortas, "
                + "       sum(cantidad_pedida) AS unidades_pedidas, "
                + "       sum(cantidad_recibida) AS unidades_recibidas, "
                + "       sum(cantidad_pedida) - sum(cantidad_recibida) AS unidades_faltantes, "
                + "       " + pct("sum(cantidad_recibida)", "sum(cantidad_pedida)")
                + "           AS servido_pct, "
                + "       countDistinct(orden_compra_id) AS ordenes "
                + "FROM " + LINEA + " l "
                + "INNER JOIN (SELECT orden_compra_id, estado FROM " + ORDEN + ") o "
                + "        ON o.orden_compra_id = l.orden_compra_id "
                + "WHERE " + estadosDe(alcance) + " " + linea.where() + " "
                + "GROUP BY proveedor ORDER BY servido_pct ASC", linea.args());

        long cortas = items.stream().mapToLong(i -> num(i.get("lineas_cortas"))).sum();
        long lineas = items.stream().mapToLong(i -> num(i.get("lineas"))).sum();

        Map<String, Object> b = bloque("entregas_incompletas",
                "Entrega incompleta por proveedor",
                "barras",
                fmt(cortas) + " líneas servidas de menos sobre " + fmt(lineas)
                + " en el alcance «" + alcance + "». Ordenado de PEOR a mejor: la primera "
                + "fila es el proveedor que peor sirve lo que se le pide.",
                items);
        b.put("alcance", alcance);
        return conSalvedad(b,
                "alcance".equals(alcance) || "entregadas".equals(alcance)
                ? "Solo entran las órdenes ENTREGADAS —recibidas y recibidas en parte—, que "
                  + "son las únicas donde una línea corta es responsabilidad del proveedor. "
                  + "Las que vienen de camino todavía no han llegado y las canceladas las "
                  + "canceló Compras: contándolas, el mejor proveedor del catálogo pasa de "
                  + "99,71 % a 91,77 % y se convierte en el peor."
                : "ATENCIÓN: alcance «" + alcance + "». Fuera de «entregadas», una línea corta "
                  + "no es necesariamente un incumplimiento del proveedor: las órdenes en "
                  + "camino aún no han llegado y las canceladas las canceló Compras. El "
                  + "ranking de esta vista NO sirve para decidir con quién se corta.");
    }

    // ═════════════════════════════════════════════════════════════════════
    // Elemento 4 — Rechazo en puerta por proveedor y motivo
    // ═════════════════════════════════════════════════════════════════════

    /**
     * <h3>C3.2 · el denominador es lo que LLEGÓ</h3>
     * {@code recibida + rechazada}, no lo pedido. El rechazo no siempre se
     * descuenta de lo recibido —49 líneas lo descuentan, 37 lo suman aparte y 6
     * mezclan—, así que sobre lo pedido una línea real da 42,9 % donde la verdad
     * es 30,0 %. Y el sesgo no se reparte: cae sobre unos proveedores y no
     * sobre otros, que es lo peor que le puede pasar a un ranking.
     *
     * Los motivos salen del {@code GROUP BY}. El valor vacío —las 2.857 líneas
     * sin rechazo— se filtra explícitamente en vez de aparecer como una
     * categoría sin nombre.
     */
    private Map<String, Object> rechazoEnPuerta(Filtros f, Map<String, Object> tot) {
        List<Map<String, Object>> items = ch.queryForList(
                "SELECT proveedor, "
                + "       motivo_rechazo AS motivo, "
                + "       count() AS lineas, "
                + "       sum(cantidad_rechazada) AS unidades_rechazadas, "
                + "       sum(cantidad_recibida) AS unidades_recibidas, "
                + "       " + pct("sum(cantidad_rechazada)",
                                  "sum(cantidad_recibida) + sum(cantidad_rechazada)")
                + "           AS rechazo_pct, "
                + "       countDistinct(producto_variante_id) AS variantes "
                + "FROM " + LINEA + " "
                + "WHERE cantidad_rechazada > 0 AND motivo_rechazo != '' " + f.where() + " "
                + "GROUP BY proveedor, motivo_rechazo "
                + "ORDER BY unidades_rechazadas DESC", f.args());

        long rechazadas = num(tot.get("rechazadas"));
        long recibidas = num(tot.get("recibidas"));
        return conSalvedad(bloque("rechazo_puerta",
                "Rechazo en puerta por proveedor y motivo",
                "matriz",
                fmt(rechazadas) + " unidades rechazadas sobre " + fmt(recibidas + rechazadas)
                + " que LLEGARON (" + porcentaje(rechazadas, recibidas + rechazadas) + " %). "
                + "Las líneas sin rechazo no aparecen: no son un motivo en blanco.",
                items),
                "El porcentaje se mide contra lo que LLEGÓ —recibido más rechazado— y no "
                + "contra lo pedido. El rechazo no siempre se descuenta de lo recibido, así "
                + "que sobre lo pedido el porcentaje sale inflado, y no de forma pareja: el "
                + "sesgo cae sobre unos proveedores y no sobre otros.");
    }

    // ═════════════════════════════════════════════════════════════════════
    // Elemento 5 — Evolución del costo de compra por producto
    // ═════════════════════════════════════════════════════════════════════

    /**
     * <h3>La frontera se marca con {@code row_number() > 1}</h3>
     * <b>Jamás</b> con {@code precio_previo != 0}. {@code lagInFrame} rellena la
     * primera fila de cada partición con el DEFECTO del tipo —un
     * {@code Decimal} 0,00— y no con NULL: comprobando contra cero, el primer
     * precio de cada pareja producto-proveedor aparecería como una «subida del
     * 100 %», y son 1.041 parejas. El desempate del mismo día es la orden.
     */
    private Map<String, Object> evolucionDelCosto(Filtros f) {
        List<Map<String, Object>> items = ch.queryForList(
                "SELECT sku, producto_nombre AS producto, categoria, proveedor, "
                + "       compras, "
                + "       formatDateTime(primera, '%d/%m/%Y') AS primera_compra, "
                + "       formatDateTime(ultima, '%d/%m/%Y') AS ultima_compra, "
                + "       precio_inicial, precio_final, "
                + "       " + pct("precio_final - precio_inicial", "precio_inicial")
                + "           AS variacion_pct, "
                + "       subidas, bajadas "
                + "FROM ( "
                + "  SELECT sku, producto_nombre, categoria, proveedor, "
                + "         count() AS compras, "
                + "         min(fecha_emision) AS primera, "
                + "         max(fecha_emision) AS ultima, "
                + "         argMin(precio_unitario, (fecha_emision, orden_compra_id)) "
                + "             AS precio_inicial, "
                + "         argMax(precio_unitario, (fecha_emision, orden_compra_id)) "
                + "             AS precio_final, "
                // `n > 1` y no `previo != 0`: lagInFrame rellena con el DEFECTO
                // del tipo, no con NULL. Ver el javadoc.
                + "         countIf(n > 1 AND precio_unitario > previo) AS subidas, "
                + "         countIf(n > 1 AND precio_unitario < previo) AS bajadas "
                + "  FROM ( "
                + "    SELECT sku, producto_nombre, categoria, proveedor, "
                + "           producto_variante_id, proveedor_id, "
                + "           fecha_emision, orden_compra_id, precio_unitario, "
                + "           lagInFrame(precio_unitario) OVER ( "
                + "               PARTITION BY producto_variante_id, proveedor_id "
                + "               ORDER BY fecha_emision, orden_compra_id "
                + "               ROWS BETWEEN 1 PRECEDING AND 1 PRECEDING) AS previo, "
                + "           row_number() OVER ( "
                + "               PARTITION BY producto_variante_id, proveedor_id "
                + "               ORDER BY fecha_emision, orden_compra_id) AS n "
                + "    FROM " + LINEA + " WHERE 1 " + f.where() + " "
                + "  ) "
                + "  GROUP BY producto_variante_id, proveedor_id, "
                + "           sku, producto_nombre, categoria, proveedor "
                + ") WHERE compras > 1 "
                + "ORDER BY abs(variacion_pct) DESC", f.args());

        long subieron = items.stream()
                .filter(i -> i.get("variacion_pct") != null
                          && Double.parseDouble(String.valueOf(i.get("variacion_pct"))) > 0)
                .count();

        return conSalvedad(bloque("evolucion_costo",
                "Evolución del costo de compra por producto",
                "ranking",
                fmt(items.size()) + " parejas producto-proveedor con MÁS DE UNA compra en el "
                + "período —las de una sola no tienen evolución que medir— de las que "
                + fmt(subieron) + " subieron de precio. Ordenadas por magnitud del cambio, en "
                + "los dos sentidos.",
                items),
                "La variación del PRIMER precio de cada pareja no existe y va nula. Marcarla "
                + "comparando contra cero daría «subida del 100 %» en las 1.041 parejas, "
                + "porque la función de ventana rellena la primera fila con el valor por "
                + "defecto del tipo y no con un nulo. Cuando dos compras caen el mismo día, "
                + "desempata el número de orden.");
    }

    // ═════════════════════════════════════════════════════════════════════
    // Elemento 6 — Deuda y calendario de vencimientos
    // ═════════════════════════════════════════════════════════════════════

    /**
     * <h3>«Hoy» es el almacén, no el reloj</h3>
     * Lo vencido se decide contra la última fecha con dato del almacén y no
     * contra {@code today()}: el histórico termina el 2026-07 y con el reloj
     * del servidor toda la cartera aparecería vencida dentro de tres meses.
     */
    private Map<String, Object> deudaYVencimientos(Filtros f, Map<String, Object> tot) {
        List<Map<String, Object>> items = ch.queryForList(
                "SELECT proveedor, "
                + "       cxp_estado AS estado, "
                + "       count() AS documentos, "
                + "       sum(cxp_monto_original) AS monto_original, "
                + "       sum(cxp_saldo_pendiente) AS saldo, "
                + "       sum(cxp_monto_original) - sum(cxp_saldo_pendiente) AS pagado, "
                + "       formatDateTime(min(cxp_fecha_vencimiento), '%d/%m/%Y') "
                + "           AS vence_primero, "
                + "       countIf(cxp_fecha_vencimiento < "
                + "               (SELECT max(fecha_factura) FROM " + ORDEN + ")) AS vencidos "
                + "FROM " + ORDEN + " "
                + "WHERE cxp_saldo_pendiente > 0 " + f.where() + " "
                + "GROUP BY proveedor, cxp_estado ORDER BY saldo DESC", f.args());
        return conSalvedad(bloque("cxp_vencimientos",
                "Deuda abierta y calendario de vencimientos",
                "ranking",
                money(dec(tot.get("saldo"))) + " de saldo pendiente sobre "
                + fmt(num(tot.get("ordenes"))) + " órdenes del período. Solo aparecen las "
                + "que tienen saldo: una cuenta pagada no es deuda.",
                items),
                "Lo «vencido» se decide contra la última fecha de factura registrada en el "
                + "almacén, no contra la fecha de hoy. Con el reloj del servidor, un histórico "
                + "que termina hace meses haría aparecer toda la cartera como vencida sin que "
                + "nadie hubiera dejado de pagar.");
    }

    // ═════════════════════════════════════════════════════════════════════
    // Elemento 7 — Puntualidad de nuestro pago
    // ═════════════════════════════════════════════════════════════════════

    /**
     * El otro lado de D-11.3: no cuánto se debe, sino cómo se está pagando.
     * {@code dias_desvio_vencimiento} es negativo cuando se pagó ANTES del
     * vencimiento — y pagar antes de tiempo regala liquidez, así que un desvío
     * muy negativo tampoco es una buena noticia.
     */
    private Map<String, Object> puntualidadDelPago(String desde, String hasta,
                                                   String proveedor) {
        Filtros f = new Filtros();
        f.y("sentido = 'egreso'");
        f.y("mes >= toStartOfMonth(toDate(?))", desde);
        f.y("mes <= toStartOfMonth(toDate(?))", hasta);
        f.y("contraparte_nombre = ?", proveedor);

        // Alias `t_`: `countIf(a_tiempo = 1) AS a_tiempo` sustituiria el
        // `a_tiempo` del porcentaje por el propio agregado y lo anidaria.
        List<Map<String, Object>> items = ch.queryForList(
                "SELECT proveedor, pagos, pagado, t_a_tiempo AS a_tiempo, tarde, "
                + "       a_tiempo_pct, desvio_medio, peor_retraso "
                + "FROM ( "
                + "  SELECT contraparte_nombre AS proveedor, "
                + "       count() AS pagos, "
                + "       sum(monto) AS pagado, "
                + "       countIf(a_tiempo = 1) AS t_a_tiempo, "
                + "       countIf(a_tiempo = 0) AS tarde, "
                + "       " + pct("countIf(a_tiempo = 1)", "countIf(a_tiempo IS NOT NULL)")
                + "           AS a_tiempo_pct, "
                + "       round(avg(dias_desvio_vencimiento), 2) AS desvio_medio, "
                + "       max(dias_desvio_vencimiento) AS peor_retraso "
                + "FROM " + CAJA + " WHERE 1 " + f.where() + " "
                + "  GROUP BY contraparte_nombre) ORDER BY pagado DESC", f.args());

        List<Map<String, Object>> t = ch.queryForList(
                "SELECT count() AS pagos, sum(monto) AS pagado, countIf(a_tiempo = 1) AS ok, "
                + "       round(avg(dias_desvio_vencimiento), 2) AS desvio "
                + "FROM " + CAJA + " WHERE 1 " + f.where(), f.args());
        Map<String, Object> tt = t.isEmpty() ? Map.of() : t.get(0);

        return conSalvedad(bloque("puntualidad_pago",
                "Puntualidad de nuestro pago al proveedor",
                "barras",
                fmt(num(tt.get("pagos"))) + " pagos a proveedor · "
                + money(dec(tt.get("pagado"))) + " · " + fmt(num(tt.get("ok")))
                + " dentro del vencimiento. Desvío medio de la cartera: "
                + tt.get("desvio") + " días.",
                items),
                "El desvío es NEGATIVO cuando se pagó antes del vencimiento, y eso tampoco es "
                + "necesariamente bueno: adelantar el pago regala liquidez que el negocio "
                + "necesita, mientras el crédito comercial es la financiación más barata que "
                + "tiene. La lectura útil está en los dos extremos, no solo en el retraso.");
    }

    // ═════════════════════════════════════════════════════════════════════
    // Elemento 8 — Recuperación de defectuosos
    // ═════════════════════════════════════════════════════════════════════

    /**
     * <h3>Muestra declarada en pantalla: es un seguimiento de casos, no una
     * estadística</h3>
     * Hay <b>6 devoluciones resueltas</b> sobre 11 proveedores y 19 meses.
     * Con esa base no se puede juzgar la calidad de un proveedor, y el bloque lo
     * dice en su propio campo en vez de dejar que el lector lo suponga.
     *
     * {@code origen ∈ {rma, recepcion}} sale del CHECK del motor. El diseño los
     * nombraba {@code inspeccion_rma} y {@code recepcion_compra}: un filtro
     * escrito desde el documento vacía el bloque entero sin dar ningún error
     * (C4.7, que ya reincidió una vez).
     */
    private Map<String, Object> recuperacionDefectuosos(String desde, String hasta,
                                                        String proveedor, String categoria) {
        Filtros f = new Filtros();
        f.y("mes >= toStartOfMonth(toDate(?))", desde);
        f.y("mes <= toStartOfMonth(toDate(?))", hasta);
        f.y("proveedor = ?", proveedor);
        f.y("categoria = ?", categoria);

        List<Map<String, Object>> items = ch.queryForList(
                "SELECT proveedor, "
                + "       origen, "
                + "       tipo_resolucion AS resolucion, "
                + "       count() AS items, "
                + "       sum(cantidad) AS unidades, "
                + "       sum(costo_total) AS costo_en_juego, "
                + "       sum(valor_recuperado) AS recuperado, "
                + "       " + pct("sum(valor_recuperado)", "sum(costo_total)")
                + "           AS recuperado_pct, "
                + "       countDistinctIf(devolucion_proveedor_id, "
                + "                       devolucion_proveedor_id > 0) AS devoluciones, "
                + "       round(avgIf(toFloat64(dias_hasta_resolucion), resuelto = 1), 1) "
                + "           AS dias_resolucion "
                + "FROM " + DEFECTO + " WHERE 1 " + f.where() + " "
                + "GROUP BY proveedor, origen, tipo_resolucion "
                + "ORDER BY costo_en_juego DESC", f.args());

        List<Map<String, Object>> t = ch.queryForList(
                "SELECT count() AS items, sum(cantidad) AS unidades, "
                + "       sum(costo_total) AS costo, sum(valor_recuperado) AS recuperado, "
                + "       countDistinctIf(devolucion_proveedor_id, "
                + "                       devolucion_proveedor_id > 0) AS devoluciones, "
                + "       countDistinctIf(devolucion_proveedor_id, resuelto = 1) AS resueltas, "
                + "       countIf(resuelto = 0) AS pendientes "
                + "FROM " + DEFECTO + " WHERE 1 " + f.where(), f.args());
        Map<String, Object> tt = t.isEmpty() ? Map.of() : t.get(0);
        long resueltas = num(tt.get("resueltas"));

        Map<String, Object> b = bloque("defectuosos",
                "Recuperación del defectuoso ante el proveedor",
                "ranking",
                fmt(num(tt.get("items"))) + " ítems defectuosos · "
                + fmt(num(tt.get("unidades"))) + " unidades · "
                + money(dec(tt.get("costo"))) + " en juego, de los que se han recuperado "
                + money(dec(tt.get("recuperado"))) + ". Quedan "
                + fmt(num(tt.get("pendientes"))) + " ítems sin resolver.",
                items);
        return conMuestra(conSalvedad(b,
                "El origen distingue el defecto detectado en la inspección de una devolución "
                + "de cliente del rechazado al recibir la compra. Son los dos únicos valores "
                + "que admite el motor, y salen de ahí y no del diseño del pipeline, que los "
                + "nombraba de otra manera: con aquellos nombres el bloque saldría vacío sin "
                + "dar ningún error."),
                "MUESTRA DÉBIL: " + fmt(resueltas) + " devoluciones a proveedor resueltas "
                + "sobre 11 proveedores y 19 meses. Es un seguimiento de CASOS, no una base "
                + "para juzgar estadísticamente la calidad de un proveedor. Para eso está el "
                + "rechazo en puerta, que tiene 92 líneas y 185 unidades detrás.");
    }
}
