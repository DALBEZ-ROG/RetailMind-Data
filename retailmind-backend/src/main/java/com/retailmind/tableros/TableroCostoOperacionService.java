package com.retailmind.tableros;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * T-5 · TABLERO DE COSTO DE LA OPERACIÓN — nivel estratégico, objetivo OE-09.
 *
 * Sirve <b>D-09.3</b> (§3.4 del diseño): si se revisa la tarifa de envío que se
 * cobra al cliente, y por qué zonas.
 *
 * <h2>Por qué existe separado de T-4</h2>
 * Sirven al mismo objetivo y leen en buena parte la misma tabla. Están
 * separados por el <b>corte financiero</b>: T-4 no selecciona un solo importe y
 * por eso DESPACHO y BODEGA pueden abrirlo; éste sí los selecciona —es
 * literalmente el tablero del costo— y por eso los deja fuera. Con un único
 * tablero habría que elegir entre negarle a Despacho la vista de su propia
 * operación o enseñarle los márgenes del transporte; separar era la única
 * salida, y lo único que la sostiene es que cada uno tiene su línea propia en
 * {@code SecurityConfig}.
 *
 * <h2>La trampa de la que vive este tablero</h2>
 * <b>24 envíos no tienen tarifa</b> ({@code sin_tarifa = 1}) y su {@code costo}
 * es 0. No son envíos gratis: son envíos anteriores a que existiera la tarifa,
 * y caen todos en el último mes de la serie. Promediándolos, julio de 2026 pasa
 * de $9,74 a $7,59 por envío —un <b>22 % más barato</b>— y el último punto de la
 * curva parece una bajada de tarifas que nunca ocurrió. Se excluyen, y el
 * bloque <b>DICE cuántos excluyó</b>.
 */
@Service
public class TableroCostoOperacionService extends TableroServiceBase {

    private static final String CODIGO = "T-5";
    private static final String TITULO = "Tablero de Costo de la Operación";
    private static final List<String> DECISIONES = List.of("D-09.3");

    private static final String ENVIO = DWH + ".fact_envio";
    private static final String PEDIDO = DWH + ".fact_pedido";
    private static final String DEVOLUCION = DWH + ".fact_devolucion";

    public TableroCostoOperacionService(@Qualifier("pgJdbcTemplate") JdbcTemplate pg,
                                        @Qualifier("clickHouseJdbc") JdbcTemplate ch) {
        super(pg, ch);
    }

    /**
     * @param desde         fecha ISO; el rango se ajusta a MESES COMPLETOS
     * @param hasta         fecha ISO; idem
     * @param zona          nombre exacto de la zona, o null = todas
     * @param transportista nombre exacto, o null = los cinco
     */
    public Map<String, Object> tablero(String desde, String hasta, String zona,
                                       String transportista) {
        String fDesde = fecha(desde, "desde");
        String fHasta = fecha(hasta, "hasta");
        exigirRangoValido(fDesde, fHasta);
        String fZona = texto(zona);
        String fTransportista = texto(transportista);

        return servir(CODIGO, TITULO, DECISIONES, () -> {
            // DOS juegos de filtros sobre el mismo hecho: `tarifados` excluye
            // los 24 sin tarifa —es la base de todo lo que se promedia— y
            // `todos` los conserva solo para poder DECIR cuántos se dejaron
            // fuera. Un bloque que excluye sin decirlo miente por omisión.
            Filtros tarifados = filtrosEnvio(fDesde, fHasta, fZona, fTransportista, true);
            Filtros todos = filtrosEnvio(fDesde, fHasta, fZona, fTransportista, false);

            Map<String, Object> tot = totales(tarifados, todos);

            List<Map<String, Object>> bloques = new ArrayList<>();
            bloques.add(costoPorZonaYMes(tarifados, tot));
            bloques.add(costoPorKilo(todos, tot));
            bloques.add(costoDelRetorno(fDesde, fHasta));
            bloques.add(costoSobreVenta(tarifados, fDesde, fHasta));

            List<String> salvedades = new ArrayList<>();
            salvedades.add("De los importes de este tablero solo el flete está medido. Los "
                    + "demás costos internos de la operación —personal de bodega, empaque, "
                    + "combustible propio— NO existen en el sistema, así que «costo "
                    + "logístico» aquí significa exactamente «lo que se pagó al "
                    + "transportista más lo reembolsado por devoluciones», y nada más.");

            return sobreTablero(CODIGO, TITULO, DECISIONES,
                    periodo(fDesde, fHasta, "fact_envio"),
                    kpis(tot),
                    bloques, salvedades,
                    "fact_envio", "fact_pedido", "fact_devolucion");
        });
    }

    // ═════════════════════════════════════════════════════════════════════
    // Filtros
    // ═════════════════════════════════════════════════════════════════════

    private static Filtros filtrosEnvio(String desde, String hasta, String zona,
                                        String transportista, boolean soloTarifados) {
        Filtros f = new Filtros();
        if (soloTarifados) {
            f.y("sin_tarifa = 0");
        }
        f.y("mes >= toStartOfMonth(toDate(?))", desde);
        f.y("mes <= toStartOfMonth(toDate(?))", hasta);
        f.y("zona = ?", zona);
        f.y("transportista = ?", transportista);
        return f;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Totales y KPI
    // ═════════════════════════════════════════════════════════════════════

    private Map<String, Object> totales(Filtros tarifados, Filtros todos) {
        // `sum(costo) AS costo` haria que ClickHouse sustituyera el `costo`
        // del promedio por su propia definicion y anidara los agregados
        // (ILLEGAL_AGGREGATION). Alias `t_` dentro, contrato fuera.
        List<Map<String, Object>> a = ch.queryForList(
                "SELECT envios, t_costo AS costo, costo_medio, costo_kg, peso FROM ( "
                + "  SELECT count() AS envios, sum(costo) AS t_costo, "
                + "         round(toFloat64(sum(costo)) / count(), 2) AS costo_medio, "
                + "         round(avg(costo_por_kg), 4) AS costo_kg, "
                + "         round(toFloat64(sum(peso_total_kg)), 2) AS peso "
                + "  FROM " + ENVIO + " WHERE 1 " + tarifados.where() + ")",
                tarifados.args());
        List<Map<String, Object>> b = ch.queryForList(
                "SELECT count() AS envios_todos, countIf(sin_tarifa = 1) AS t_sin_tarifa "
                + "FROM " + ENVIO + " WHERE 1 " + todos.where(), todos.args());
        java.util.Map<String, Object> r = new java.util.LinkedHashMap<>(
                a.isEmpty() ? Map.of() : a.get(0));
        r.putAll(b.isEmpty() ? Map.of() : b.get(0));
        return r;
    }

    private List<Map<String, Object>> kpis(Map<String, Object> tot) {
        BigDecimal costo = dec(tot.get("costo"));
        long envios = num(tot.get("envios"));
        long sinTarifa = num(tot.get("sin_tarifa"));

        List<Map<String, Object>> reemb = ch.queryForList(
                "SELECT count() AS devoluciones, sum(monto_reembolsado) AS reembolsado, "
                + "       countIf(reembolso_registrado = 0) AS sin_asiento "
                + "FROM " + DEVOLUCION + " WHERE monto_reembolsado > 0");
        Map<String, Object> rb = reemb.isEmpty() ? Map.of() : reemb.get(0);
        BigDecimal reembolsado = dec(rb.get("reembolsado"));

        List<Map<String, Object>> v = ch.queryForList(
                "SELECT sum(total) AS venta FROM " + PEDIDO + " WHERE es_cancelado = 0");
        BigDecimal venta = v.isEmpty() ? BigDecimal.ZERO : dec(v.get(0).get("venta"));

        List<Map<String, Object>> k = new ArrayList<>();
        k.add(kpi("Costo de envío del período", costo, "moneda",
                fmt(envios) + " envíos TARIFADOS. Quedan fuera " + fmt(sinTarifa)
                + " envíos sin tarifa, que no son envíos gratis: son anteriores a que "
                + "existiera la tarifa y promediarlos abarataría el período."));
        k.add(kpi("Costo medio por envío", tot.get("costo_medio"), "moneda",
                money(costo) + " entre " + fmt(envios) + " envíos tarifados."));
        k.add(kpi("Costo medio por kilo", tot.get("costo_kg"), "moneda",
                "Sobre " + fmt(envios) + " envíos con peso y tarifa. En los no tarifados el "
                + "costo por kilo viaja NULO, nunca cero."));
        k.add(kpi("Reembolsos pagados", reembolsado, "moneda",
                fmt(num(rb.get("devoluciones"))) + " devoluciones con reembolso, de las que "
                + fmt(num(rb.get("sin_asiento"))) + " no tienen asiento contable registrado."));
        k.add(kpi("Costo logístico sobre la venta",
                porcentaje(costo.add(reembolsado), venta), "porcentaje",
                "Flete " + money(costo) + " + reembolsos " + money(reembolsado) + " sobre "
                + money(venta) + " de venta no cancelada. Solo el flete está medido: los "
                + "demás costos internos no existen en el sistema."));
        return k;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Elemento 1 — Serie mensual del costo de envío por zona
    // ═════════════════════════════════════════════════════════════════════

    private Map<String, Object> costoPorZonaYMes(Filtros f, Map<String, Object> tot) {
        // Alias con prefijo `t_`: `sum(costo) AS costo` haría que ClickHouse
        // resolviera hacia atrás el `costo` de la ventana y anidara el agregado
        // (ILLEGAL_AGGREGATION). El nombre del contrato se repone fuera.
        List<Map<String, Object>> items = ch.queryForList(
                "SELECT periodo, zona, envios, t_costo AS costo, costo_medio, "
                + "       participacion_pct, kilos "
                + "FROM ( "
                + "  SELECT formatDateTime(mes, '%Y-%m') AS periodo, "
                + "         mes AS mes_dato, "
                + "         zona, "
                + "         count() AS envios, "
                + "         sum(costo) AS t_costo, "
                + "         round(toFloat64(sum(costo)) / count(), 2) AS costo_medio, "
                + "         round(toFloat64(sum(peso_total_kg)), 2) AS kilos, "
                + "         " + pct("sum(costo)", "sum(sum(costo)) OVER (PARTITION BY mes)")
                + "             AS participacion_pct "
                + "  FROM " + ENVIO + " WHERE 1 " + f.where() + " "
                + "  GROUP BY mes, zona "
                + ") ORDER BY mes_dato, t_costo DESC", f.args());

        long sinTarifa = num(tot.get("sin_tarifa"));
        return conSalvedad(bloque("costo_zona_mes",
                "Costo del envío por zona, mes a mes",
                "serie",
                fmt(num(tot.get("envios"))) + " envíos tarifados · " + money(dec(tot.get("costo")))
                + ". El costo medio de cada punto es sobre los envíos de ESE mes y ESA zona.",
                items),
                sinTarifa == 0
                ? "Todos los envíos del período tienen tarifa."
                : "Se han EXCLUIDO " + fmt(sinTarifa) + " envíos sin tarifar, cuyo costo "
                  + "registrado es cero. No son envíos gratis: son anteriores a que la tarifa "
                  + "existiera, y se concentran en el último tramo de la serie. Incluyéndolos, "
                  + "el mes afectado cae de $9,74 a $7,59 por envío —un 22 % menos— y la curva "
                  + "termina en una bajada de tarifas que no ocurrió.");
    }

    // ═════════════════════════════════════════════════════════════════════
    // Elemento 2 — Costo por kilo por transportista y zona
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Mapa de calor. Aquí se usa el juego de filtros SIN excluir: interesa ver
     * cuántos envíos de cada casilla no están tarifados, y ese conteo
     * desaparecería si se filtraran antes. El promedio, en cambio, solo cuenta
     * los tarifados — porque {@code costo_por_kg} viaja <b>NULO</b> en los
     * demás y {@code avg} ignora los nulos, que es justo lo que debe hacer.
     */
    private Map<String, Object> costoPorKilo(Filtros todos, Map<String, Object> tot) {
        List<Map<String, Object>> items = ch.queryForList(
                "SELECT transportista, zona, envios, tarifados, "
                + "       t_sin_tarifa AS sin_tarifa, "
                + "       t_costo_kg AS costo_por_kg, mediana_por_kg, "
                + "       t_costo AS costo, kilos "
                + "FROM ( "
                + "  SELECT transportista, zona, "
                + "       count() AS envios, "
                + "       countIf(costo_por_kg IS NOT NULL) AS tarifados, "
                + "       countIf(sin_tarifa = 1) AS t_sin_tarifa, "
                + "       round(avg(costo_por_kg), 4) AS t_costo_kg, "
                + "       round(quantileExact(0.5)(costo_por_kg), 4) AS mediana_por_kg, "
                + "       round(toFloat64(sumIf(costo, sin_tarifa = 0)), 2) AS t_costo, "
                + "       round(toFloat64(sumIf(peso_total_kg, sin_tarifa = 0)), 2) AS kilos "
                + "  FROM " + ENVIO + " WHERE 1 " + todos.where() + " "
                + "  GROUP BY transportista, zona) ORDER BY envios DESC", todos.args());
        return conSalvedad(bloque("costo_por_kg",
                "Costo por kilo por transportista y zona",
                "matriz",
                fmt(num(tot.get("envios"))) + " envíos tarifados de "
                + fmt(num(tot.get("envios_todos"))) + " del período. Cada casilla promedia "
                + "SOLO sus envíos tarifados, y la columna de al lado dice cuántos son.",
                items),
                "En los envíos sin tarifar el costo por kilo viaja NULO, nunca cero, y el "
                + "promedio los ignora. Con un cero, una casilla con pocos envíos y alguno sin "
                + "tarifar aparecería como el transportista más barato de la matriz.");
    }

    // ═════════════════════════════════════════════════════════════════════
    // Elemento 3 — Costo del retorno: reembolsos por vía y motivo
    // ═════════════════════════════════════════════════════════════════════

    /**
     * <h3>C4.1 · el reembolso tiene DOS registros y no coinciden</h3>
     * La cabecera de la devolución guarda {@code monto_reembolsado} y la vía
     * ({@code metodo_reembolso}); la tabla de asientos guarda el movimiento
     * contable. Son <b>86 devoluciones contra 85 asientos</b>, con $169,70 de
     * diferencia: una devolución se reembolsó sin dejar asiento.
     *
     * Este bloque usa la <b>cabecera</b>, que es el único de los dos que trae la
     * VÍA —y la vía es media pregunta de D-09.3—, y publica la columna «sin
     * asiento» en vez de reconciliar por su cuenta. Reconciliar sería inventar
     * un asiento que nadie escribió; enseñarlo es lo que permite ir a buscarlo.
     */
    private Map<String, Object> costoDelRetorno(String desde, String hasta) {
        Filtros f = new Filtros();
        f.y("monto_reembolsado > 0");
        f.y("mes >= toStartOfMonth(toDate(?))", desde);
        f.y("mes <= toStartOfMonth(toDate(?))", hasta);

        List<Map<String, Object>> items = ch.queryForList(
                "SELECT metodo_reembolso AS via, "
                + "       motivo, "
                + "       count() AS devoluciones, "
                + "       sum(monto_reembolsado) AS reembolsado, "
                + "       countIf(reembolso_registrado = 0) AS sin_asiento, "
                + "       sum(monto_reembolsado) - sum(monto_reembolso_asiento) AS diferencia, "
                + "       sum(unidades) AS unidades "
                + "FROM " + DEVOLUCION + " WHERE 1 " + f.where() + " "
                + "GROUP BY metodo_reembolso, motivo ORDER BY reembolsado DESC", f.args());

        List<Map<String, Object>> t = ch.queryForList(
                "SELECT count() AS devoluciones, sum(monto_reembolsado) AS reembolsado, "
                + "       countIf(reembolso_registrado = 0) AS sin_asiento, "
                + "       sum(monto_reembolsado) - sum(monto_reembolso_asiento) AS diferencia "
                + "FROM " + DEVOLUCION + " WHERE 1 " + f.where(), f.args());
        Map<String, Object> tt = t.isEmpty() ? Map.of() : t.get(0);
        long sinAsiento = num(tt.get("sin_asiento"));

        return conSalvedad(bloque("reembolsos",
                "Costo del retorno: reembolsos por vía y motivo",
                "barras_apiladas",
                fmt(num(tt.get("devoluciones"))) + " devoluciones con reembolso pagado · "
                + money(dec(tt.get("reembolsado"))) + ". Solo entran las que tienen importe "
                + "reembolsado: una devolución aprobada sin pagar todavía no es un costo.",
                items),
                "La cifra sale de la CABECERA de la devolución, no del asiento contable, "
                + "porque es la única de las dos que guarda la VÍA del reembolso —y la vía es "
                + "media pregunta de esta decisión—. Los dos registros no coinciden: hay "
                + fmt(sinAsiento) + " devolución(es) reembolsada(s) sin asiento, "
                + money(dec(tt.get("diferencia"))) + " de diferencia. Se publica la columna en "
                + "vez de cuadrarla por dentro: el descuadre es el dato.");
    }

    // ═════════════════════════════════════════════════════════════════════
    // Elemento 4 — Costo logístico total sobre la venta
    // ═════════════════════════════════════════════════════════════════════

    private Map<String, Object> costoSobreVenta(Filtros envio, String desde, String hasta) {
        Filtros p = new Filtros();
        p.y("es_cancelado = 0");
        p.y("mes >= toStartOfMonth(toDate(?))", desde);
        p.y("mes <= toStartOfMonth(toDate(?))", hasta);

        Object[] args = new Object[envio.args().length + p.args().length];
        System.arraycopy(envio.args(), 0, args, 0, envio.args().length);
        System.arraycopy(p.args(), 0, args, envio.args().length, p.args().length);

        // El JOIN es LEFT desde el ENVÍO y no al revés a propósito: un mes con
        // envíos y sin venta es una anomalía que hay que ver; un mes con venta
        // y sin envíos es lo normal (venta de mostrador que el cliente se
        // lleva), y colgarlo aquí llenaría la serie de ratios infinitos.
        List<Map<String, Object>> items = ch.queryForList(
                "SELECT e.periodo, e.envios, e.t_costo AS costo, e.costo_medio, "
                + "       v.venta, "
                + "       " + pct("e.t_costo", "v.venta") + " AS costo_sobre_venta_pct "
                + "FROM ( "
                + "  SELECT formatDateTime(mes, '%Y-%m') AS periodo, mes AS m, "
                + "         count() AS envios, sum(costo) AS t_costo, "
                + "         round(toFloat64(sum(costo)) / count(), 2) AS costo_medio "
                + "  FROM " + ENVIO + " WHERE 1 " + envio.where() + " GROUP BY mes "
                + ") e "
                + "LEFT JOIN ( "
                + "  SELECT mes AS m, sum(total) AS venta "
                + "  FROM " + PEDIDO + " WHERE 1 " + p.where() + " GROUP BY mes "
                + ") v ON v.m = e.m "
                + "ORDER BY e.m", args);

        return conSalvedad(bloque("costo_sobre_venta",
                "Costo logístico sobre la venta, mes a mes",
                "doble_eje",
                "Un punto por mes con envíos tarifados. El denominador es la venta NO "
                + "cancelada del mismo mes, con IVA y flete incluidos: es lo que factura el "
                + "negocio, que es contra lo que se decide si una tarifa es cara.",
                items),
                "El ratio incluye SOLO el flete pagado al transportista. No incluye personal "
                + "de bodega, empaque ni combustible propio: esos costos no existen en el "
                + "sistema, así que este porcentaje es un suelo, no el costo logístico total.");
    }
}
