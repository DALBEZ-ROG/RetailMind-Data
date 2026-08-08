package com.retailmind.tableros;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * T-3 · TABLERO DE CLIENTE Y POSVENTA — nivel estratégico, objetivo OE-08.
 *
 * Sirve tres decisiones de dirección (§3.3 del diseño):
 * <ul>
 *   <li><b>D-08.2</b> a quién se le reconoce la condición de cliente preferente;</li>
 *   <li><b>D-08.3</b> qué causa de reclamo se ataca y con qué recurso;</li>
 *   <li><b>D-08.4</b> qué productos se retiran o se renegocian por devolución
 *       y calificación.</li>
 * </ul>
 *
 * <h2>Roles: el único tablero de esta fase con acceso PARCIAL</h2>
 * ADMIN, GERENTE y ANALISTA lo ven entero. <b>SOPORTE entra solo al bloque de
 * tickets y devoluciones</b> (§4 del diseño). El corte no lo puede hacer el
 * motor —ClickHouse no tiene GRANT por columna ni RLS— así que lo hace la
 * <b>CONSULTA</b>: para SOPORTE los bloques de valor del cliente y de reseñas
 * <b>no se ejecutan</b>, y el sobre declara cuáles omitió y por qué. Es la
 * misma disciplina que COM-08 y LOG-12 ya aplicaron, un escalón más fino: allí
 * el corte era por endpoint, aquí es por bloque dentro del mismo endpoint.
 *
 * BODEGA y DESPACHO quedan fuera por RUTA: el tablero lleva dinero.
 *
 * <h2>Las tres trampas heredadas que este tablero respeta</h2>
 * <ol>
 *   <li><b>C4.4 — JAMÁS unir {@code fact_resena} a {@code dim_producto}.</b> La
 *       reseña es del producto PADRE y la dimensión es por VARIANTE: el join
 *       multiplica las 344 reseñas a 348 sin dar un error. El bloque de
 *       calificación se sirve solo de {@code fact_resena}, que ya denormaliza
 *       nombre, categoría y marca justamente para esto.</li>
 *   <li><b>C4.3 — {@code 'sin_categoria'}</b> es un valor real del dato (hay 1
 *       ticket sin clasificar, porque la FK es NULLABLE). Sale del
 *       {@code GROUP BY} y no se filtra: un JOIN interno lo tiraría en
 *       silencio.</li>
 *   <li><b>C4.5 — «resuelto» NO es «cerrado».</b> Resolver no escribe
 *       {@code fecha_cierre} en este sistema, así que los TIEMPOS se miden
 *       sobre los 76 cerrados y no sobre los 120 «atendidos». Cada fila declara
 *       su base.</li>
 * </ol>
 */
@Service
public class TableroClientePosventaService extends TableroServiceBase {

    private static final String CODIGO = "T-3";
    private static final String TITULO = "Tablero de Cliente y Posventa";
    private static final List<String> DECISIONES = List.of("D-08.2", "D-08.3", "D-08.4");

    private static final String PEDIDO = DWH + ".fact_pedido";
    private static final String TICKET = DWH + ".fact_ticket";
    private static final String DEVOLUCION = DWH + ".fact_devolucion";
    private static final String DEV_LINEA = DWH + ".fact_devolucion_linea";
    private static final String RESENA = DWH + ".fact_resena";

    /** Roles que ven el tablero COMPLETO. SOPORTE ve solo posventa. */
    private static final String SOPORTE = "SOPORTE";

    public TableroClientePosventaService(@Qualifier("pgJdbcTemplate") JdbcTemplate pg,
                                         @Qualifier("clickHouseJdbc") JdbcTemplate ch) {
        super(pg, ch);
    }

    /**
     * @param desde           fecha ISO; el rango se ajusta a MESES COMPLETOS
     * @param hasta           fecha ISO; idem
     * @param categoria       categoría de PRODUCTO, o null = todas
     * @param categoriaTicket categoría del TICKET, o null = todas
     */
    public Map<String, Object> tablero(String desde, String hasta, String categoria,
                                       String categoriaTicket) {
        String fDesde = fecha(desde, "desde");
        String fHasta = fecha(hasta, "hasta");
        exigirRangoValido(fDesde, fHasta);
        String fCategoria = texto(categoria);
        String fCatTicket = texto(categoriaTicket);

        // El rol se lee FUERA de `servir`: quién pregunta no depende de que
        // ClickHouse esté vivo, y el sobre degradado también debe declarar el
        // alcance con el que se habría servido.
        boolean soloPosventa = SOPORTE.equals(rolActual());

        return servir(CODIGO, TITULO, DECISIONES, () -> {
            Filtros pedido = filtrosMes(fDesde, fHasta);
            Filtros ticket = filtrosTicket(fDesde, fHasta, fCatTicket);
            Filtros devol = filtrosMes(fDesde, fHasta);
            Filtros resena = filtrosResena(fDesde, fHasta, fCategoria);

            List<Map<String, Object>> bloques = new ArrayList<>();
            List<Map<String, Object>> omitidos = new ArrayList<>();

            Map<String, Object> ventaTot = totalesVenta(pedido);
            Map<String, Object> ticketTot = totalesTicket(ticket);
            Map<String, Object> devolTot = totalesDevolucion(devol);

            if (soloPosventa) {
                omitidos.add(omitido("pareto_clientes", "Curva de valor del cliente"));
                omitidos.add(omitido("nuevo_recurrente", "Cliente nuevo contra recurrente"));
                omitidos.add(omitido("calificacion_producto", "Calificación por producto"));
            } else {
                bloques.add(paretoClientes(pedido, ventaTot));
                bloques.add(nuevoContraRecurrente(fDesde, fHasta));
            }

            bloques.add(reclamosPorCategoria(ticket, ticketTot));
            bloques.add(devolucionPorProducto(fDesde, fHasta, fCategoria, devolTot));

            if (!soloPosventa) {
                bloques.add(calificacionPorProducto(resena));
            }

            bloques.add(reclamaYDevuelve(fDesde, fHasta, fCatTicket, fCategoria));

            List<String> salvedades = new ArrayList<>();
            if (soloPosventa) {
                salvedades.add("Estás viendo el tablero con el alcance de SOPORTE: los "
                        + "bloques de valor del cliente y de calificación no se consultan. "
                        + "En el almacén analítico la segregación no la respalda el motor "
                        + "—no hay privilegio por columna—, así que el corte lo hace la "
                        + "consulta: esos bloques no se ejecutan, no es que se oculten.");
            }
            if (fCategoria != null) {
                salvedades.add("El filtro de categoría de producto «" + fCategoria + "» "
                        + "alcanza a las devoluciones, a las reseñas y al cruce de reclamo y "
                        + "devolución. NO alcanza a los tickets ni a los bloques de cliente: "
                        + "un ticket puede no referirse a ningún producto (hay tickets sin "
                        + "producto asociado) y un pedido mezcla categorías.");
            }

            return sobreTableroConAlcance(fDesde, fHasta,
                    kpis(ventaTot, ticketTot, devolTot, pedido, soloPosventa),
                    bloques, salvedades, omitidos, soloPosventa);
        });
    }

    private Map<String, Object> sobreTableroConAlcance(String desde, String hasta,
                                                       List<Map<String, Object>> kpis,
                                                       List<Map<String, Object>> bloques,
                                                       List<String> salvedades,
                                                       List<Map<String, Object>> omitidos,
                                                       boolean soloPosventa) {
        Map<String, Object> t = sobreTablero(CODIGO, TITULO, DECISIONES,
                periodo(desde, hasta, "fact_pedido"), kpis, bloques, salvedades,
                "fact_pedido", "fact_ticket", "fact_devolucion", "fact_devolucion_linea",
                "fact_resena");
        t.put("alcance", soloPosventa ? "posventa" : "completo");
        t.put("bloquesOmitidos", omitidos);
        return t;
    }

    private static Map<String, Object> omitido(String id, String titulo) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("id", id);
        o.put("titulo", titulo);
        o.put("motivo", "Fuera del alcance del rol SOPORTE, que entra a este tablero solo "
                + "por el bloque de tickets y devoluciones.");
        return o;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Filtros
    // ═════════════════════════════════════════════════════════════════════

    private static Filtros filtrosMes(String desde, String hasta) {
        Filtros f = new Filtros();
        f.y("mes >= toStartOfMonth(toDate(?))", desde);
        f.y("mes <= toStartOfMonth(toDate(?))", hasta);
        return f;
    }

    private static Filtros filtrosTicket(String desde, String hasta, String categoria) {
        Filtros f = filtrosMes(desde, hasta);
        f.y("categoria = ?", categoria);
        return f;
    }

    private static Filtros filtrosResena(String desde, String hasta, String categoria) {
        Filtros f = filtrosMes(desde, hasta);
        f.y("categoria = ?", categoria);
        return f;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Totales y KPI
    // ═════════════════════════════════════════════════════════════════════

    private Map<String, Object> totalesVenta(Filtros f) {
        List<Map<String, Object>> r = ch.queryForList(
                "SELECT count() AS pedidos, sum(total) AS venta, "
                + "       countDistinct(cliente_id) AS clientes "
                + "FROM " + PEDIDO + " WHERE es_cancelado = 0 " + f.where(), f.args());
        return r.isEmpty() ? Map.of() : r.get(0);
    }

    /**
     * Los tickets se cuentan en CUATRO cestas y no en dos, por C4.5: «resuelto»
     * y «cerrado» no son el mismo estado y solo el segundo escribe la fecha de
     * cierre. Una tasa de cumplimiento sobre los 248 daría 4,8 % y sería falsa;
     * la base honesta de los TIEMPOS son los cerrados.
     */
    private Map<String, Object> totalesTicket(Filtros f) {
        List<Map<String, Object>> r = ch.queryForList(
                "SELECT count() AS tickets, "
                + "       countIf(estado = 'cerrado') AS cerrados, "
                + "       countIf(estado = 'resuelto') AS resueltos, "
                + "       countIf(estado NOT IN ('resuelto', 'cerrado')) AS vivos, "
                + "       countIf(fecha_cierre IS NOT NULL) AS con_cierre, "
                + "       countIf(cumplio_sla = 1) AS sla_cumplido, "
                + "       countIf(cumplio_sla = 0) AS sla_incumplido, "
                + "       countIf(cumplio_sla IS NULL) AS sla_desconocido "
                + "FROM " + TICKET + " WHERE 1 " + f.where(), f.args());
        return r.isEmpty() ? Map.of() : r.get(0);
    }

    private Map<String, Object> totalesDevolucion(Filtros f) {
        List<Map<String, Object>> r = ch.queryForList(
                "SELECT count() AS devoluciones, "
                + "       sum(monto_total) AS monto, "
                + "       sum(unidades) AS unidades, "
                + "       countIf(es_terminal = 0) AS abiertas, "
                + "       sum(monto_reembolsado) AS reembolsado "
                + "FROM " + DEVOLUCION + " WHERE 1 " + f.where(), f.args());
        return r.isEmpty() ? Map.of() : r.get(0);
    }

    private List<Map<String, Object>> kpis(Map<String, Object> venta, Map<String, Object> tk,
                                           Map<String, Object> dv, Filtros pedido,
                                           boolean soloPosventa) {
        List<Map<String, Object>> k = new ArrayList<>();

        long conCierre = num(tk.get("con_cierre"));
        long slaOk = num(tk.get("sla_cumplido"));
        BigDecimal ventaTotal = dec(venta.get("venta"));
        BigDecimal devuelto = dec(dv.get("monto"));

        if (!soloPosventa) {
            long clientes = num(venta.get("clientes"));
            Map<String, Object> top = topVeinte(pedido);
            k.add(kpi("Clientes activos del período", clientes, "numero",
                    "Con al menos un pedido no cancelado entre las fechas del filtro."));
            k.add(kpi("Ingreso del top 20 % de clientes",
                    porcentaje(dec(top.get("venta_top")), ventaTotal), "porcentaje",
                    money(dec(top.get("venta_top"))) + " de " + money(ventaTotal) + ", puestos "
                    + "por " + fmt(num(top.get("clientes_top"))) + " clientes de "
                    + fmt(clientes) + "."));
            k.add(kpi("Clientes nuevos en el último mes del rango",
                    num(nuevosUltimoMes(pedido)), "numero",
                    "Primera compra de su historia en ese mes. La primera compra se calcula "
                    + "sobre TODA la historia del cliente, no sobre el rango filtrado."));
        } else {
            k.add(kpi("Tickets vivos", num(tk.get("vivos")), "numero",
                    "Ni resueltos ni cerrados, de " + fmt(num(tk.get("tickets")))
                    + " tickets del período."));
            k.add(kpi("Devoluciones abiertas", num(dv.get("abiertas")), "numero",
                    "Sin estado terminal, de " + fmt(num(dv.get("devoluciones")))
                    + " del período."));
        }

        k.add(kpi("Tiempo prometido cumplido", porcentaje(slaOk, conCierre), "porcentaje",
                fmt(slaOk) + " de " + fmt(conCierre) + " tickets CERRADOS. El denominador NO "
                + "son los " + fmt(num(tk.get("tickets"))) + " del período ni los "
                + fmt(num(tk.get("resueltos")) + num(tk.get("cerrados")))
                + " «atendidos»: resolver no cierra en este sistema y sin cierre no hay "
                + "instante que restar."));
        k.add(kpi("Venta devuelta", porcentaje(devuelto, ventaTotal), "porcentaje",
                money(devuelto) + " en " + fmt(num(dv.get("devoluciones"))) + " devoluciones "
                + "sobre " + money(ventaTotal) + " de venta no cancelada del período. La "
                + "devolución se fecha por su SOLICITUD, no por el pedido de origen: parte "
                + "de lo devuelto corresponde a ventas anteriores al rango."));
        return k;
    }

    private Map<String, Object> topVeinte(Filtros f) {
        // El corte del top 20 % se calcula sobre los clientes CON pedido en el
        // período, no sobre los 72 de la dimensión: incluir a los que no
        // compraron ensancharía el denominador y bajaría el corte sin que
        // hubiera cambiado nada del negocio.
        List<Map<String, Object>> r = ch.queryForList(
                "SELECT countIf(ranking <= corte) AS clientes_top, "
                + "       sumIf(venta, ranking <= corte) AS venta_top, "
                + "       count() AS clientes, "
                // `any(corte) AS corte` volvería a caer en la resolución hacia
                // atrás de los alias: ClickHouse sustituiría el `corte` de los
                // dos countIf por `any(corte)` y anidaría agregados.
                + "       any(corte) AS corte_top "
                + "FROM ( "
                + "  SELECT venta, "
                + "         row_number() OVER (ORDER BY venta DESC, cliente_id) AS ranking, "
                + "         greatest(1, toUInt32(ceil(count() OVER () * 0.2))) AS corte "
                + "  FROM (SELECT cliente_id, sum(total) AS venta FROM " + PEDIDO + " "
                + "        WHERE es_cancelado = 0 " + f.where() + " GROUP BY cliente_id) "
                + ")", f.args());
        return r.isEmpty() ? Map.of() : r.get(0);
    }

    private long nuevosUltimoMes(Filtros f) {
        List<Map<String, Object>> r = ch.queryForList(
                "SELECT countDistinctIf(cliente_id, mes = primera AND mes = ultimo) AS nuevos "
                + "FROM ( "
                + "  SELECT cliente_id, mes, "
                + "         min(mes) OVER (PARTITION BY cliente_id) AS primera "
                + "  FROM " + PEDIDO + " WHERE es_cancelado = 0 "
                // Alias obligatorio: ClickHouse exige nombrar las subconsultas
                // que participan en un JOIN (`joined_subquery_requires_alias`).
                + ") p "
                + "CROSS JOIN (SELECT max(mes) AS ultimo FROM " + PEDIDO + " "
                + "            WHERE es_cancelado = 0 " + f.where() + ") u", f.args());
        return r.isEmpty() ? 0 : num(r.get(0).get("nuevos"));
    }

    // ═════════════════════════════════════════════════════════════════════
    // Elemento 1 — Curva de valor del cliente (Pareto)
    // ═════════════════════════════════════════════════════════════════════

    /**
     * El acumulado ordenado por valor: el corte que el usuario mueve sobre esta
     * curva ES la decisión D-08.2.
     *
     * <h3>Salvedad de alcance, y no es menor</h3>
     * El tablero <b>informa</b> el corte; no lo ejecuta. Reconocer la condición
     * de cliente preferente exige un programa de lealtad que hoy no existe como
     * capacidad del sistema —tabla, backend y pantallas—, y decir lo contrario
     * convertiría una vista en una promesa.
     */
    private Map<String, Object> paretoClientes(Filtros f, Map<String, Object> tot) {
        List<Map<String, Object>> items = ch.queryForList(
                "SELECT p.ranking, "
                + "       c.nombre_completo AS cliente, "
                + "       c.ciudad, c.segmento, "
                + "       p.pedidos, p.venta, p.ticket_medio, "
                + "       p.venta_pct, p.acumulado_pct, p.clientes_pct "
                + "FROM ( "
                + "  SELECT cliente_id, pedidos, venta, "
                + "         round(toFloat64(venta) / pedidos, 2) AS ticket_medio, "
                + "         row_number() OVER (ORDER BY venta DESC, cliente_id) AS ranking, "
                + "         " + pct("venta", "sum(venta) OVER ()") + " AS venta_pct, "
                + "         " + pct("sum(venta) OVER (ORDER BY venta DESC, cliente_id "
                                    + "ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)",
                                    "sum(venta) OVER ()") + " AS acumulado_pct, "
                + "         " + pct("row_number() OVER (ORDER BY venta DESC, cliente_id)",
                                    "count() OVER ()") + " AS clientes_pct "
                + "  FROM (SELECT cliente_id, count() AS pedidos, sum(total) AS venta "
                + "        FROM " + PEDIDO + " WHERE es_cancelado = 0 " + f.where()
                + "        GROUP BY cliente_id) "
                + ") p "
                + "LEFT JOIN " + dimension("dim_cliente") + " c "
                + "       ON c.cliente_id = p.cliente_id "
                + "ORDER BY p.ranking", f.args());

        return conSalvedad(bloque("pareto_clientes",
                "Curva de valor del cliente",
                "curva_acumulada",
                fmt(items.size()) + " clientes con al menos un pedido no cancelado en el "
                + "período · " + money(dec(tot.get("venta"))) + ". El acumulado es sobre la "
                + "venta de ESTOS clientes, no sobre los 72 de la cartera: quien no compró en "
                + "el rango no está en la curva ni en su denominador.",
                items),
                "El tablero INFORMA el corte de cliente preferente; no lo ejecuta. Reconocer "
                + "la condición exige un programa de lealtad que hoy no existe como capacidad "
                + "del sistema —tabla, backend y pantallas—, así que lo que sale de aquí es "
                + "el criterio, no la ejecución.");
    }

    // ═════════════════════════════════════════════════════════════════════
    // Elemento 2 — Cliente nuevo contra recurrente
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Clasifica cada mes por la PRIMERA compra del cliente.
     *
     * <h3>La primera compra se calcula sobre TODA la historia</h3>
     * Es la trampa entera de este elemento. Calculando
     * {@code min(mes) OVER (PARTITION BY cliente_id)} dentro del rango
     * filtrado, todo cliente que aparece en el primer mes del rango es «nuevo»
     * por construcción: un filtro de los últimos tres meses daría 48 clientes
     * nuevos donde hay 1, y la conclusión —«la captación va disparada»— sería
     * exactamente lo contrario de la verdad. La ventana corre sobre el hecho
     * completo y el rango se aplica DESPUÉS, sobre el resultado ya clasificado.
     *
     * Consecuencia declarada: un cliente cuya primera compra es anterior al
     * almacén se clasificaría como nuevo en su primer mes cargado. Con el
     * histórico arrancando en el primer mes del seed, ese caso no existe hoy;
     * el día que se cargue un histórico parcial, existirá.
     */
    private Map<String, Object> nuevoContraRecurrente(String desde, String hasta) {
        Filtros f = filtrosMes(desde, hasta);
        List<Map<String, Object>> items = ch.queryForList(
                "SELECT formatDateTime(mes, '%Y-%m') AS periodo, "
                + "       clientes_nuevos, clientes_recurrentes, "
                + "       pedidos_nuevos, pedidos_recurrentes, "
                + "       venta_nuevos, venta_recurrentes, "
                + "       " + pct("venta_nuevos", "venta_nuevos + venta_recurrentes")
                + "           AS venta_nuevos_pct, "
                + "       " + pct("clientes_nuevos", "clientes_nuevos + clientes_recurrentes")
                + "           AS clientes_nuevos_pct "
                + "FROM ( "
                + "  SELECT mes, "
                + "         countDistinctIf(cliente_id, mes = primera) AS clientes_nuevos, "
                + "         countDistinctIf(cliente_id, mes > primera) AS clientes_recurrentes, "
                + "         countIf(mes = primera) AS pedidos_nuevos, "
                + "         countIf(mes > primera) AS pedidos_recurrentes, "
                + "         sumIf(total, mes = primera) AS venta_nuevos, "
                + "         sumIf(total, mes > primera) AS venta_recurrentes "
                + "  FROM ( "
                + "    SELECT cliente_id, mes, total, "
                + "           min(mes) OVER (PARTITION BY cliente_id) AS primera "
                + "    FROM " + PEDIDO + " WHERE es_cancelado = 0 "
                + "  ) "
                + "  GROUP BY mes "
                + ") WHERE 1 " + f.where() + " ORDER BY mes", f.args());

        return conSalvedad(bloque("nuevo_recurrente",
                "Cliente nuevo contra recurrente, mes a mes",
                "barras_apiladas",
                "Un cliente es NUEVO en el mes de su primera compra y recurrente en todos "
                + "los demás. Denominador de cada mes: los clientes distintos con pedido no "
                + "cancelado en ESE mes.",
                items),
                "La primera compra de cada cliente se calcula sobre TODA su historia y el "
                + "rango de fechas se aplica DESPUÉS. Calculándola dentro del rango, todo "
                + "cliente que apareciera en el primer mes filtrado sería «nuevo» por "
                + "construcción y la captación parecería dispararse cada vez que se acorta "
                + "el filtro.");
    }

    // ═════════════════════════════════════════════════════════════════════
    // Elemento 3 — Reclamos por categoría y su tiempo de resolución
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Volumen y tiempo por categoría de ticket, con las dos salvedades de C4.3
     * y C4.5 aplicadas en el propio SQL:
     *
     * <ul>
     *   <li>{@code 'sin_categoria'} sale del {@code GROUP BY} como una fila más
     *       —el ETL ya lo escribió así porque la FK es nullable— y no se filtra
     *       ni se reparte;</li>
     *   <li>los tiempos se miden <b>solo sobre los cerrados</b>, y la columna
     *       {@code base_tiempos} viaja al lado de cada media para que el número
     *       no se pueda leer sin su denominador. La mediana y el p90 van además
     *       de la media porque un ticket olvidado durante semanas mueve la
     *       media de una categoría entera.</li>
     * </ul>
     */
    private Map<String, Object> reclamosPorCategoria(Filtros f, Map<String, Object> tot) {
        List<Map<String, Object>> items = ch.queryForList(
                "SELECT categoria, "
                + "       count() AS tickets, "
                + "       countIf(estado NOT IN ('resuelto', 'cerrado')) AS vivos, "
                + "       countIf(estado = 'resuelto') AS resueltos, "
                + "       countIf(estado = 'cerrado') AS cerrados, "
                + "       countIf(fecha_cierre IS NOT NULL) AS base_tiempos, "
                + "       round(avgIf(toFloat64(horas_resolucion), "
                + "                   fecha_cierre IS NOT NULL), 2) AS horas_media, "
                + "       round(quantileExactIf(0.5)(toFloat64(horas_resolucion), "
                + "                   fecha_cierre IS NOT NULL), 2) AS horas_mediana, "
                + "       round(quantileExactIf(0.9)(toFloat64(horas_resolucion), "
                + "                   fecha_cierre IS NOT NULL), 2) AS horas_p90, "
                + "       countIf(cumplio_sla = 1) AS sla_cumplido, "
                + "       countIf(cumplio_sla = 0) AS sla_incumplido, "
                + "       " + pct("countIf(cumplio_sla = 1)", "countIf(fecha_cierre IS NOT NULL)")
                + "           AS sla_pct "
                + "FROM " + TICKET + " WHERE 1 " + f.where() + " "
                + "GROUP BY categoria ORDER BY tickets DESC", f.args());

        long tickets = num(tot.get("tickets"));
        long conCierre = num(tot.get("con_cierre"));
        return conSalvedad(bloque("tickets_categoria",
                "Reclamos por categoría y tiempo de resolución",
                "barras",
                fmt(tickets) + " tickets del período · " + fmt(num(tot.get("vivos")))
                + " vivos · " + fmt(conCierre) + " cerrados. El VOLUMEN se cuenta sobre los "
                + fmt(tickets) + "; los TIEMPOS solo sobre los " + fmt(conCierre)
                + " cerrados, y cada fila lleva su propia base.",
                items),
                "«Resuelto» no es «cerrado»: resolver no escribe fecha de cierre en este "
                + "sistema, así que hay " + fmt(num(tot.get("resueltos"))) + " tickets "
                + "atendidos sin instante final que restar y quedan fuera de los tiempos. La "
                + "categoría «sin_categoria» es un valor real del dato —la clasificación del "
                + "ticket es opcional— y se muestra como una fila más: filtrarla la borraría "
                + "sin avisar.");
    }

    // ═════════════════════════════════════════════════════════════════════
    // Elemento 4 — Devolución por producto y motivo
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Ranking de producto con la composición de motivos dentro de cada uno.
     *
     * {@code reingresa_stock} separa lo que vuelve a venderse de lo que se
     * pierde: no es lo mismo un producto que se devuelve por talla —vuelve
     * íntegro al stock— que uno que se devuelve roto, y la decisión D-08.4 es
     * distinta en cada caso.
     */
    private Map<String, Object> devolucionPorProducto(String desde, String hasta,
                                                      String categoria,
                                                      Map<String, Object> tot) {
        // El rango de meses es el común del tablero; la categoría de PRODUCTO
        // solo aplica aquí, así que el filtro se arma en el bloque y no en el
        // compartido (a un ticket no se le puede aplicar: puede no referirse a
        // ningún producto).
        Filtros f = filtrosMes(desde, hasta);
        f.y("categoria = ?", categoria);
        String where = f.where();
        Object[] args = f.args();

        List<Map<String, Object>> items = ch.queryForList(
                "SELECT producto_variante_id AS variante_id, sku, producto, "
                + "       t_categoria AS categoria, t_marca AS marca, "
                + "       motivo, lineas, unidades, monto, "
                + "       t_reingresadas AS unidades_reingresadas, "
                + "       reingreso_pct, unidades_producto "
                + "FROM ( "
                + "  SELECT producto_variante_id, "
                + "         any(sku) AS sku, "
                + "         any(producto_nombre) AS producto, "
                // Mismo motivo que `t_reingresadas` de tres líneas abajo, pero
                // por el WHERE y no por el pct: el filtro de categoría del
                // tablero entra en ESTE WHERE, y con el alias homónimo
                // ClickHouse lo resuelve contra el agregado (ILLEGAL_AGGREGATION).
                + "         any(categoria) AS t_categoria, "
                + "         any(marca) AS t_marca, "
                + "         motivo, "
                + "         count() AS lineas, "
                + "         sum(cantidad) AS unidades, "
                + "         sum(monto_linea) AS monto, "
                // El alias NO puede llamarse como su columna: ClickHouse lo
                // resolvería hacia atrás dentro del pct y produciría
                // `sum(sum(unidades_reingresadas))` → ILLEGAL_AGGREGATION.
                + "         sum(unidades_reingresadas) AS t_reingresadas, "
                + "         " + pct("sum(unidades_reingresadas)", "sum(cantidad)")
                + "             AS reingreso_pct, "
                + "         sum(sum(cantidad)) OVER (PARTITION BY producto_variante_id) "
                + "             AS unidades_producto "
                + "  FROM " + DEV_LINEA + " WHERE 1 " + where + " "
                + "  GROUP BY producto_variante_id, motivo "
                + ") "
                + "ORDER BY unidades_producto DESC, variante_id, unidades DESC", args);

        return bloque("devolucion_producto",
                "Devolución por producto y motivo",
                "ranking",
                fmt(num(tot.get("devoluciones"))) + " devoluciones del período · "
                + fmt(num(tot.get("unidades"))) + " unidades · "
                + money(dec(tot.get("monto"))) + ". Las filas son (producto × motivo) y se "
                + "ordenan por el total de unidades del PRODUCTO, para que su composición "
                + "quede junta. La fecha es la de la SOLICITUD de la devolución, no la del "
                + "pedido devuelto.",
                items);
    }

    // ═════════════════════════════════════════════════════════════════════
    // Elemento 5 — Calificación por producto
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Nota media por producto, servida <b>solo</b> desde {@code fact_resena}.
     *
     * <h3>C4.4 · Por qué NO se une a {@code dim_producto}</h3>
     * La reseña es del producto PADRE; la dimensión es por VARIANTE. Un
     * producto con dos variantes duplica cada una de sus reseñas en el join, y
     * en este almacén hay 5 productos con dos variantes y 2 con tres: las 344
     * reseñas se convierten en <b>348</b> filas —la nota media cambia, el
     * conteo cambia— y ClickHouse no da el menor aviso. El ETL denormalizó
     * nombre, categoría y marca dentro del hecho <b>precisamente</b> para que
     * este join no haga falta nunca.
     */
    private Map<String, Object> calificacionPorProducto(Filtros f) {
        List<Map<String, Object>> items = ch.queryForList(
                // El SELECT exterior repone los nombres del contrato: dentro, la
                // categoría y la marca agregadas van con `t_` porque el filtro
                // del tablero entra en el WHERE de la consulta interna y un
                // alias homónimo lo resolvería contra el agregado
                // (ILLEGAL_AGGREGATION, Code 184).
                "SELECT producto_id, producto, "
                + "       t_categoria AS categoria, t_marca AS marca, "
                + "       resenas, nota_media, negativas, positivas, verificadas, "
                + "       aprobadas, pendientes "
                + "FROM ( "
                + "  SELECT producto_id, "
                + "         any(producto_nombre) AS producto, "
                + "         any(categoria) AS t_categoria, "
                + "         any(marca) AS t_marca, "
                + "         count() AS resenas, "
                + "         round(avg(calificacion), 2) AS nota_media, "
                + "         countIf(calificacion <= 2) AS negativas, "
                + "         countIf(calificacion >= 4) AS positivas, "
                + "         countIf(compra_verificada = 1) AS verificadas, "
                + "         countIf(estado = 'aprobada') AS aprobadas, "
                + "         countIf(estado = 'pendiente') AS pendientes "
                + "  FROM " + RESENA + " WHERE 1 " + f.where() + " "
                + "  GROUP BY producto_id "
                + ") "
                + "ORDER BY negativas DESC, resenas DESC, nota_media ASC", f.args());

        long resenas = items.stream().mapToLong(i -> num(i.get("resenas"))).sum();
        return conSalvedad(bloque("calificacion_producto",
                "Calificación por producto",
                "ranking",
                fmt(resenas) + " reseñas del período repartidas en " + fmt(items.size())
                + " productos. Se ordenan por reseñas NEGATIVAS y no por nota media: un "
                + "producto con una sola reseña de 1 estrella tiene la peor nota del catálogo "
                + "y no es el problema.",
                items),
                "El grano es el PRODUCTO, no la variante: la reseña se escribe sobre el "
                + "producto padre. Este bloque no se cruza con la dimensión de producto a "
                + "propósito —la dimensión es por variante y el cruce multiplicaría las "
                + "reseñas de los productos con más de una variante sin dar ningún error—, "
                + "así que nombre, categoría y marca salen del propio hecho.");
    }

    // ═════════════════════════════════════════════════════════════════════
    // Elemento 6 — Producto que reclama y devuelve a la vez
    // ═════════════════════════════════════════════════════════════════════

    /**
     * La dispersión que es D-08.4 en una sola vista: en el eje de los reclamos,
     * el ticket; en el de la pérdida, la unidad devuelta. Lo que está arriba a
     * la derecha paga dos veces —el reembolso y la reputación—.
     *
     * <h3>El cruce es INNER y se declara</h3>
     * Solo entran los productos que tienen las dos cosas. Un producto que solo
     * genera tickets, o que solo se devuelve, no responde la pregunta de este
     * elemento y tiene su propio bloque; meterlo aquí con un cero en un eje
     * llenaría los márgenes de la dispersión de puntos que no significan nada.
     * El denominador declara cuántos productos quedaron fuera por cada lado.
     */
    private Map<String, Object> reclamaYDevuelve(String desde, String hasta,
                                                 String catTicket, String catProducto) {
        Filtros t = filtrosMes(desde, hasta);
        t.y("categoria = ?", catTicket);
        Filtros d = filtrosMes(desde, hasta);
        d.y("categoria = ?", catProducto);

        Object[] args = new Object[t.args().length + d.args().length];
        System.arraycopy(t.args(), 0, args, 0, t.args().length);
        System.arraycopy(d.args(), 0, args, t.args().length, d.args().length);

        List<Map<String, Object>> items = ch.queryForList(
                "SELECT t.producto_variante_id AS variante_id, "
                + "       d.sku, d.producto, d.t_categoria AS categoria, d.t_marca AS marca, "
                + "       t.tickets, t.tickets_cerrados, "
                + "       d.devoluciones, d.unidades, d.monto, d.unidades_reingresadas "
                + "FROM ( "
                + "  SELECT producto_variante_id, count() AS tickets, "
                + "         countIf(estado = 'cerrado') AS tickets_cerrados "
                + "  FROM " + TICKET + " WHERE tiene_producto = 1 " + t.where() + " "
                + "  GROUP BY producto_variante_id "
                + ") t "
                + "INNER JOIN ( "
                + "  SELECT producto_variante_id, any(sku) AS sku, "
                // `t_` por lo mismo: el filtro de categoría del tablero entra
                // en el WHERE de ESTE lado del JOIN (ILLEGAL_AGGREGATION).
                + "         any(producto_nombre) AS producto, any(categoria) AS t_categoria, "
                + "         any(marca) AS t_marca, "
                + "         countDistinct(devolucion_id) AS devoluciones, "
                + "         sum(cantidad) AS unidades, sum(monto_linea) AS monto, "
                + "         sum(unidades_reingresadas) AS unidades_reingresadas "
                + "  FROM " + DEV_LINEA + " WHERE 1 " + d.where() + " "
                + "  GROUP BY producto_variante_id "
                + ") d ON d.producto_variante_id = t.producto_variante_id "
                + "ORDER BY t.tickets DESC, d.unidades DESC", args);

        List<Map<String, Object>> bases = ch.queryForList(
                "SELECT (SELECT countDistinct(producto_variante_id) FROM " + TICKET + " "
                + "        WHERE tiene_producto = 1 " + t.where() + ") AS con_ticket, "
                + "       (SELECT countDistinct(producto_variante_id) FROM " + DEV_LINEA + " "
                + "        WHERE 1 " + d.where() + ") AS con_devolucion", args);
        long conTicket = bases.isEmpty() ? 0 : num(bases.get(0).get("con_ticket"));
        long conDev = bases.isEmpty() ? 0 : num(bases.get(0).get("con_devolucion"));

        return bloque("reclama_y_devuelve",
                "Producto que reclama y devuelve a la vez",
                "dispersion",
                fmt(items.size()) + " productos con AMBAS cosas en el período, de "
                + fmt(conTicket) + " con reclamo y " + fmt(conDev) + " con devolución. El "
                + "cruce es exclusivo a propósito: un producto que solo genera tickets, o "
                + "que solo se devuelve, no responde esta pregunta y tiene su propio bloque.",
                items);
    }
}
