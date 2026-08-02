package com.retailmind.tableros;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * T-2 · TABLERO DE RENTABILIDAD Y ROTACIÓN — nivel estratégico, objetivo OE-07.
 *
 * Sirve cuatro decisiones de dirección (§3.2 del diseño):
 * <ul>
 *   <li><b>D-07.1</b> qué productos se descontinúan;</li>
 *   <li><b>D-07.2</b> política de precio por categoría;</li>
 *   <li><b>D-07.3</b> cuánto descuento se autoriza y sobre qué categorías;</li>
 *   <li><b>D-07.4</b> qué capital inmovilizado se libera.</li>
 * </ul>
 *
 * Roles: ADMIN, GERENTE, ANALISTA. Es el tablero con MÁS dinero del sistema
 * —margen, costo y capital— y BODEGA y DESPACHO quedan fuera por RUTA.
 *
 * <h2>Las dos salvedades que este tablero no puede omitir</h2>
 * <ol>
 *   <li><b>El margen se computa contra el costo VIGENTE</b>, no contra el costo
 *       al que se compró aquella unidad: el sistema no guarda costo histórico
 *       (§8.3 del diseño del ETL). Un margen de hace un año está valorado a
 *       precio de hoy.</li>
 *   <li><b>El capital inmovilizado es volumen a MONEDA CONSTANTE</b>, por la
 *       misma razón: es «las unidades de aquel mes al costo de hoy», no lo que
 *       valía la bodega aquel mes. La serie mide movimiento de volumen, no
 *       revalorización.</li>
 * </ol>
 * Las dos viajan en el bloque que afectan y la pantalla las pinta ENCIMA de la
 * cifra. Una advertencia sobre cómo leer un número llega tarde si se lee
 * después del número.
 *
 * <h2>El elemento que no sale del almacén</h2>
 * El sobre-stock del PRESENTE. {@code fact_stock_mensual} guarda el cierre de
 * cada mes pero <b>no lleva mínimo ni máximo</b>: los topes son del presente y
 * viven en {@code inventario} (PostgreSQL). Lo pide la pantalla con una segunda
 * llamada al informe simple OTD-INV-08, ya construido (R-7).
 */
@Service
public class TableroRentabilidadService extends TableroServiceBase {

    private static final String CODIGO = "T-2";
    private static final String TITULO = "Tablero de Rentabilidad y Rotación";
    private static final List<String> DECISIONES =
            List.of("D-07.1", "D-07.2", "D-07.3", "D-07.4");

    private static final String LINEA = DWH + ".fact_venta_linea";
    private static final String STOCK = DWH + ".fact_stock_mensual";
    private static final String KARDEX = DWH + ".fact_movimiento_inventario";

    private static final String SALVEDAD_COSTO =
            "El margen se computa contra el costo VIGENTE de la variante, no contra el costo "
            + "al que se compró aquella unidad: el sistema no guarda costo histórico. Un "
            + "margen de hace un año está valorado a precio de hoy, así que la serie es "
            + "comparable entre categorías pero NO mide el efecto de un cambio de costo.";

    private static final String SALVEDAD_CAPITAL =
            "Es volumen valorizado a MONEDA CONSTANTE: las unidades de cada mes al costo "
            + "vigente de hoy, y no lo que valía la bodega aquel mes. La serie mide cómo se "
            + "movió el VOLUMEN inmovilizado; una subida no puede atribuirse a que el "
            + "producto se encareciera.";

    public TableroRentabilidadService(@Qualifier("pgJdbcTemplate") JdbcTemplate pg,
                                      @Qualifier("clickHouseJdbc") JdbcTemplate ch) {
        super(pg, ch);
    }

    /**
     * @param desde     fecha ISO; el rango se ajusta a MESES COMPLETOS
     * @param hasta     fecha ISO; idem
     * @param categoria nombre exacto de la categoría, o null = todas
     * @param marca     nombre exacto de la marca, o null = todas
     * @param bodega    nombre exacto de la bodega, o null = todas. Solo afecta a
     *                  los bloques de STOCK; se declara en el sobre
     */
    public Map<String, Object> tablero(String desde, String hasta, String categoria,
                                       String marca, String bodega) {
        String fDesde = fecha(desde, "desde");
        String fHasta = fecha(hasta, "hasta");
        exigirRangoValido(fDesde, fHasta);
        String fCategoria = texto(categoria);
        String fMarca = texto(marca);
        String fBodega = texto(bodega);

        return servir(CODIGO, TITULO, DECISIONES, () -> {
            Filtros venta = filtrosVenta(fDesde, fHasta, fCategoria, fMarca);
            Filtros stock = filtrosStock(fDesde, fHasta, fCategoria, fMarca, fBodega);

            Map<String, Object> tot = totalesVenta(venta);
            Map<String, Object> cap = capitalAlCierre(stock);

            List<Map<String, Object>> bloques = new ArrayList<>();
            bloques.add(margenPorCategoria(venta, tot));
            bloques.add(matrizMargenRotacion(venta, stock));
            bloques.add(productoHueso(venta, stock, fCategoria, fMarca));
            bloques.add(descuentoEntregado(venta, tot));
            bloques.add(descuentoContraMargen(venta, tot));
            bloques.add(capitalInmovilizado(stock, cap));

            List<String> salvedades = new ArrayList<>();
            if (fBodega != null) {
                salvedades.add("El filtro de bodega «" + fBodega + "» solo alcanza a los "
                        + "bloques de STOCK (matriz de rotación, producto hueso y capital "
                        + "inmovilizado). La venta no se registra por bodega: el pedido no "
                        + "guarda de qué bodega salió cada línea, así que los bloques de "
                        + "margen y descuento siguen mostrando TODA la venta del período.");
            }

            return sobreTablero(CODIGO, TITULO, DECISIONES,
                    periodo(fDesde, fHasta, "fact_venta_linea"),
                    kpis(tot, cap, stock),
                    bloques, salvedades,
                    "fact_venta_linea", "fact_stock_mensual", "fact_movimiento_inventario");
        });
    }

    // ═════════════════════════════════════════════════════════════════════
    // Filtros
    // ═════════════════════════════════════════════════════════════════════

    private static Filtros filtrosVenta(String desde, String hasta, String categoria,
                                        String marca) {
        Filtros f = new Filtros();
        // Los pedidos cancelados no son venta: son intención. Quedan fuera de
        // TODO este tablero, y el denominador de cada bloque lo dice.
        f.y("es_cancelado = 0");
        f.y("mes >= toStartOfMonth(toDate(?))", desde);
        f.y("mes <= toStartOfMonth(toDate(?))", hasta);
        f.y("categoria = ?", categoria);
        f.y("marca = ?", marca);
        return f;
    }

    private static Filtros filtrosStock(String desde, String hasta, String categoria,
                                        String marca, String bodega) {
        Filtros f = new Filtros();
        f.y("mes >= toStartOfMonth(toDate(?))", desde);
        f.y("mes <= toStartOfMonth(toDate(?))", hasta);
        f.y("categoria = ?", categoria);
        f.y("marca = ?", marca);
        f.y("bodega = ?", bodega);
        return f;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Totales y KPI de cabecera
    // ═════════════════════════════════════════════════════════════════════

    private Map<String, Object> totalesVenta(Filtros f) {
        List<Map<String, Object>> r = ch.queryForList(
                "SELECT count() AS lineas, "
                + "       countDistinct(pedido_id) AS pedidos, "
                + "       sum(cantidad) AS unidades, "
                + "       sum(subtotal_bruto) AS bruto, "
                + "       sum(descuento_promocion) AS descuento_promocion, "
                + "       sum(descuento_cupon_prorrateado) AS descuento_cupon, "
                + "       sum(descuento_total) AS descuento_total, "
                + "       sum(venta_neta) AS venta_neta, "
                + "       sum(costo_total) AS costo, "
                + "       sum(margen) AS margen, "
                + "       countIf(excepcion_descuento = 1) AS lineas_excepcion, "
                + "       countDistinctIf(pedido_id, excepcion_descuento = 1) AS pedidos_excepcion "
                + "FROM " + LINEA + " WHERE 1 " + f.where(), f.args());
        return r.isEmpty() ? Map.of() : r.get(0);
    }

    /**
     * Capital al cierre del ÚLTIMO mes del rango consultado, más el del primero
     * para poder dar la variación.
     *
     * El «cierre» no es el mes en curso del reloj sino el último mes con dato
     * dentro del filtro: R-4 del diseño —el seed termina el 2026-07-22 y el
     * calendario avanza— obliga a anclar el presente al almacén y a decirlo.
     */
    private Map<String, Object> capitalAlCierre(Filtros f) {
        List<Map<String, Object>> r = ch.queryForList(
                "SELECT formatDateTime(max(mes), '%Y-%m') AS mes_cierre, "
                + "       sumIf(valor_cierre, mes = m.ultimo) AS capital_cierre, "
                + "       sumIf(stock_cierre, mes = m.ultimo) AS unidades_cierre, "
                + "       countIf(mes = m.ultimo) AS posiciones_cierre, "
                + "       sumIf(valor_cierre, mes = m.primero) AS capital_inicio, "
                + "       formatDateTime(min(mes), '%Y-%m') AS mes_inicio "
                + "FROM " + STOCK + " "
                + "CROSS JOIN (SELECT max(mes) AS ultimo, min(mes) AS primero "
                + "            FROM " + STOCK + " WHERE 1 " + f.where() + ") m "
                + "WHERE 1 " + f.where(), f.argsCon(f.args()));
        return r.isEmpty() ? Map.of() : r.get(0);
    }

    private List<Map<String, Object>> kpis(Map<String, Object> tot, Map<String, Object> cap,
                                           Filtros stock) {
        BigDecimal ventaNeta = dec(tot.get("venta_neta"));
        BigDecimal margen = dec(tot.get("margen"));
        BigDecimal descuento = dec(tot.get("descuento_total"));
        BigDecimal bruto = dec(tot.get("bruto"));
        long lineas = num(tot.get("lineas"));
        long pedidos = num(tot.get("pedidos"));
        long lineasExc = num(tot.get("lineas_excepcion"));
        long pedidosExc = num(tot.get("pedidos_excepcion"));

        BigDecimal capCierre = dec(cap.get("capital_cierre"));
        BigDecimal capInicio = dec(cap.get("capital_inicio"));

        List<Map<String, Object>> k = new ArrayList<>();
        k.add(kpi("Venta neta", ventaNeta, "moneda",
                fmt(lineas) + " líneas de " + fmt(pedidos) + " pedidos no cancelados. Es "
                + "venta de LÍNEA (sin IVA ni flete), no el total del pedido."));
        k.add(kpi("Margen", margen, "moneda",
                "Venta neta menos costo. " + SALVEDAD_COSTO));
        k.add(kpi("Margen sobre la venta", porcentaje(margen, ventaNeta), "porcentaje",
                money(margen) + " sobre " + money(ventaNeta) + " de venta neta."));
        k.add(kpi("Descuento entregado", descuento, "moneda",
                porcentaje(descuento, bruto) + " % de la venta bruta (" + money(bruto) + "). "
                + (lineasExc > 0
                   ? fmt(lineasExc) + " líneas de " + fmt(pedidosExc) + " pedidos van marcadas "
                     + "como excepción y su cupón NO está aquí (ver el bloque de descuento)."
                   : "Sin excepciones de prorrateo en el período.")));
        k.add(kpi("Peso del descuento sobre el margen", porcentaje(descuento, margen),
                "porcentaje",
                "Cuánto pesa el descuento entregado frente al margen que quedó. Por encima "
                + "del 100 % el descuento supera al margen."));
        k.add(kpi("Capital inmovilizado al cierre", capCierre, "moneda",
                "Cierre de " + txt(cap.get("mes_cierre")) + " · "
                + fmt(num(cap.get("posiciones_cierre"))) + " posiciones · "
                + fmt(num(cap.get("unidades_cierre"))) + " unidades. " + SALVEDAD_CAPITAL));
        k.add(kpi("Variación del capital en el período", porcentaje(
                        capCierre.subtract(capInicio), capInicio), "porcentaje",
                "De " + money(capInicio) + " (" + txt(cap.get("mes_inicio")) + ") a "
                + money(capCierre) + " (" + txt(cap.get("mes_cierre")) + ")."));
        return k;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Elemento 1 — Margen por categoría y mes
    // ═════════════════════════════════════════════════════════════════════

    private Map<String, Object> margenPorCategoria(Filtros f, Map<String, Object> tot) {
        // Alias con prefijo `t_` en el agregado: `sum(venta_neta) AS venta_neta`
        // hace que ClickHouse resuelva hacia atrás el `venta_neta` de la ventana
        // y acabe con `sum(sum(venta_neta))` → ILLEGAL_AGGREGATION. Los nombres
        // del contrato de la API se reponen en el SELECT exterior.
        List<Map<String, Object>> items = ch.queryForList(
                "SELECT periodo, categoria, unidades, "
                + "       t_venta_neta AS venta_neta, t_costo AS costo, t_margen AS margen, "
                + "       margen_pct, participacion_pct "
                + "FROM ( "
                + "  SELECT formatDateTime(mes, '%Y-%m') AS periodo, "
                + "         mes AS mes_dato, "
                + "         categoria, "
                + "         sum(cantidad) AS unidades, "
                + "         sum(venta_neta) AS t_venta_neta, "
                + "         sum(costo_total) AS t_costo, "
                + "         sum(margen) AS t_margen, "
                + "         " + pct("sum(margen)", "sum(venta_neta)") + " AS margen_pct, "
                + "         " + pct("sum(venta_neta)",
                                    "sum(sum(venta_neta)) OVER (PARTITION BY mes)")
                + "             AS participacion_pct "
                + "  FROM " + LINEA + " WHERE 1 " + f.where() + " "
                + "  GROUP BY mes, categoria "
                + ") ORDER BY mes_dato, t_venta_neta DESC", f.args());
        return conSalvedad(bloque("margen_categoria",
                "Margen por categoría y mes",
                "serie",
                fmt(num(tot.get("lineas"))) + " líneas · " + money(dec(tot.get("venta_neta")))
                + " de venta neta · " + money(dec(tot.get("margen"))) + " de margen. El "
                + "margen de cada celda es sobre la venta neta de ESA celda, no del período.",
                items),
                SALVEDAD_COSTO);
    }

    // ═════════════════════════════════════════════════════════════════════
    // Elemento 2 — Matriz margen × rotación
    // ═════════════════════════════════════════════════════════════════════

    /**
     * La dispersión de cuatro cuadrantes que ES la decisión D-07.1.
     *
     * <h3>Rotación: unidades vendidas sobre el stock MEDIO del período</h3>
     * No sobre el stock de hoy. Un producto que se liquidó a cero en marzo
     * tiene stock actual 0 y su rotación saldría infinita —o se dividiría por
     * cero y desaparecería—, cuando lo que hizo fue rotar bien y agotarse.
     * El stock medio es el promedio de los cierres mensuales del rango.
     *
     * <h3>El corte de los cuadrantes es la MEDIANA, y viaja en el bloque</h3>
     * Se calcula con {@code quantileExact(0.5)(x) OVER ()} sobre el conjunto ya
     * agregado, no sobre una constante escrita a mano: un corte fijo del 25 %
     * de margen clasificaría todo el catálogo en el mismo cuadrante en cuanto
     * se filtre por una categoría cara. Los dos cortes viajan en
     * {@code corteRotacion} y {@code corteMargen} para que la pantalla dibuje
     * la cruz exactamente donde se clasificó, y no en un sitio aproximado.
     *
     * <h3>Las variantes sin posición de stock no se descartan en silencio</h3>
     * Se les deja {@code rotacion} NULA y se cuentan aparte. Ponerles 0
     * las mandaría al cuadrante «no rota», que es la recomendación contraria a
     * la correcta para un producto que vendió sin llegar a inventariarse.
     */
    private Map<String, Object> matrizMargenRotacion(Filtros venta, Filtros stock) {
        String agregado =
                "SELECT v.producto_variante_id AS variante_id, "
                + "       any(v.sku) AS sku, "
                + "       any(v.producto_nombre) AS producto, "
                + "       any(v.categoria) AS categoria, "
                + "       any(v.marca) AS marca, "
                + "       sum(v.cantidad) AS unidades, "
                + "       sum(v.venta_neta) AS t_venta_neta, "
                + "       sum(v.margen) AS t_margen, "
                + "       " + pct("sum(v.margen)", "sum(v.venta_neta)") + " AS margen_pct, "
                + "       round(any(s.stock_medio), 2) AS stock_medio, "
                + "       if(any(s.stock_medio) > 0, "
                + "          round(sum(v.cantidad) / any(s.stock_medio), 3), NULL) AS rotacion "
                + "FROM " + LINEA + " v "
                + "LEFT JOIN (SELECT producto_variante_id, avg(stock_cierre) AS stock_medio "
                + "           FROM " + STOCK + " WHERE 1 " + stock.where()
                + "           GROUP BY producto_variante_id) s "
                + "       ON s.producto_variante_id = v.producto_variante_id "
                + "WHERE 1 " + venta.where() + " "
                + "GROUP BY v.producto_variante_id";

        Object[] args = concat(stock.args(), venta.args());

        List<Map<String, Object>> items = ch.queryForList(
                "SELECT variante_id, sku, producto, categoria, marca, unidades, "
                + "       t_venta_neta AS venta_neta, t_margen AS margen, "
                + "       margen_pct, stock_medio, rotacion, "
                + "       corte_rotacion, corte_margen, "
                + "       if(rotacion IS NULL, 'sin_stock', "
                + "          if(rotacion >= corte_rotacion, "
                + "             if(margen_pct >= corte_margen, 'estrella', 'volumen'), "
                + "             if(margen_pct >= corte_margen, 'nicho', 'hueso'))) AS cuadrante "
                + "FROM ( "
                + "  SELECT *, "
                + "         round(quantileExact(0.5)(rotacion) OVER (), 3) AS corte_rotacion, "
                + "         round(quantileExact(0.5)(margen_pct) OVER (), 2) AS corte_margen "
                + "  FROM (" + agregado + ") "
                + ") "
                + "ORDER BY t_venta_neta DESC", args);

        long sinStock = items.stream().filter(i -> i.get("rotacion") == null).count();
        Object corteRot = items.isEmpty() ? null : items.get(0).get("corte_rotacion");
        Object corteMrg = items.isEmpty() ? null : items.get(0).get("corte_margen");

        Map<String, Object> b = bloque("matriz_margen_rotacion",
                "Matriz margen × rotación",
                "dispersion",
                fmt(items.size()) + " variantes CON venta en el período. Las que no vendieron "
                + "nada no están aquí: viven en el bloque de producto hueso, que es su sitio. "
                + (sinStock > 0
                   ? fmt(sinStock) + " vendieron sin posición de stock en el rango y van con "
                     + "rotación nula (cuadrante «sin_stock»), no con cero."
                   : "Todas tienen posición de stock en el rango."),
                items);
        b.put("corteRotacion", corteRot);
        b.put("corteMargen", corteMrg);
        return conSalvedad(b,
                "Rotación = unidades vendidas en el período entre el stock MEDIO de los "
                + "cierres mensuales del rango, no entre el stock de hoy: un producto que se "
                + "agotó tendría stock actual cero y una rotación infinita. Los dos cortes de "
                + "los cuadrantes son las MEDIANAS del conjunto filtrado —viajan en el bloque— "
                + "y se mueven con el filtro: son un corte relativo, no un umbral del negocio. "
                + SALVEDAD_COSTO);
    }

    // ═════════════════════════════════════════════════════════════════════
    // Elemento 3 — Producto hueso
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Las variantes SIN una sola venta en el período, con el capital que
     * retienen y el tiempo que llevan quietas.
     *
     * <h3>Dos poblaciones distintas, y la tabla las distingue</h3>
     * «Sin venta en el período filtrado» y «sin venta NUNCA» no son lo mismo, y
     * la decisión tampoco: liquidar una referencia estacional fuera de su
     * temporada destruye margen ya comprado. La columna {@code ultima_venta}
     * sale del kardex ({@code salida_venta}) sobre TODA la historia, no sobre
     * el rango, y viene vacía justamente en las que no vendieron jamás.
     *
     * <h3>«Hoy» es el almacén, no el reloj</h3>
     * Los días sin venta se cuentan contra la última salida registrada en el
     * kardex, no contra {@code now()}. R-4: el seed termina y el calendario
     * avanza; anclado al reloj, dentro de tres meses todo el catálogo parecería
     * llevar un trimestre sin venderse.
     */
    private Map<String, Object> productoHueso(Filtros venta, Filtros stock,
                                              String categoria, String marca) {
        Filtros dim = new Filtros();
        dim.y("categoria = ?", categoria);
        dim.y("marca = ?", marca);

        // El «stock actual» es el del ÚLTIMO mes DENTRO del rango filtrado, no
        // el del último mes del almacén: con un rango que termine en 2025, un
        // `mes = (SELECT max(mes) FROM stock)` global cruzado con el filtro de
        // rango no casaría con ninguna fila y la tabla saldría con todo el
        // capital a cero — sin error y con la lectura «no hay nada parado».
        // De ahí que los parámetros del filtro de stock viajen DOS veces.
        Object[] args = concat(venta.args(),
                concat(stock.args(), concat(stock.args(), dim.args())));

        List<Map<String, Object>> items = ch.queryForList(
                "SELECT d.producto_variante_id AS variante_id, "
                + "       d.sku, d.producto_nombre AS producto, d.categoria, d.marca, "
                + "       d.costo, "
                + "       s.unidades AS stock_actual, "
                + "       round(d.costo * s.unidades, 2) AS capital_retenido, "
                + "       k.ultima_venta, "
                + "       k.dias_sin_venta "
                + "FROM " + dimension("dim_producto") + " d "
                + "LEFT ANTI JOIN (SELECT DISTINCT producto_variante_id FROM " + LINEA
                + "                WHERE 1 " + venta.where() + ") v "
                + "     ON d.producto_variante_id = v.producto_variante_id "
                + "LEFT JOIN (SELECT producto_variante_id, sum(stock_cierre) AS unidades "
                + "           FROM " + STOCK + " "
                + "           WHERE 1 " + stock.where() + " "
                + "             AND mes = (SELECT max(mes) FROM " + STOCK
                + "                        WHERE 1 " + stock.where() + ") "
                + "           GROUP BY producto_variante_id) s "
                + "     ON s.producto_variante_id = d.producto_variante_id "
                + "LEFT JOIN (SELECT producto_variante_id, "
                + "                  formatDateTime(max(fecha), '%d/%m/%Y') AS ultima_venta, "
                + "                  dateDiff('day', max(fecha), "
                + "                           (SELECT max(fecha) FROM " + KARDEX
                + "                            WHERE tipo_movimiento = 'salida_venta')) "
                + "                      AS dias_sin_venta "
                + "           FROM " + KARDEX + " WHERE tipo_movimiento = 'salida_venta' "
                + "           GROUP BY producto_variante_id) k "
                + "     ON k.producto_variante_id = d.producto_variante_id "
                + "WHERE 1 " + dim.where() + " "
                + "ORDER BY capital_retenido DESC NULLS LAST, d.sku", args);

        long nunca = items.stream().filter(i -> i.get("ultima_venta") == null
                || String.valueOf(i.get("ultima_venta")).isEmpty()).count();
        BigDecimal capital = items.stream()
                .map(i -> dec(i.get("capital_retenido")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return conSalvedad(bloque("producto_hueso",
                "Producto hueso: sin venta en el período",
                "ranking",
                fmt(items.size()) + " variantes del catálogo sin una sola línea vendida en el "
                + "rango, de las cuales " + fmt(nunca) + " no han vendido NUNCA. Retienen "
                + money(capital) + " de capital al último cierre disponible.",
                items),
                "«Sin venta en el período» y «sin venta nunca» no son la misma decisión: una "
                + "referencia estacional fuera de temporada aparece aquí y liquidarla destruye "
                + "margen ya comprado. La columna de última venta sale del kardex sobre TODA "
                + "la historia y distingue las dos. Los días sin venta se cuentan contra la "
                + "última salida registrada en el almacén, no contra la fecha de hoy: el "
                + "histórico termina y el calendario sigue.");
    }

    // ═════════════════════════════════════════════════════════════════════
    // Elemento 4 — Descuento entregado por mes y categoría
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Las DOS capas de descuento, separadas: la promoción se aplica a la línea
     * y el cupón se prorratea desde la cabecera. Sumarlas en una sola barra
     * ocultaría cuál de las dos palancas hay que mover, que es exactamente lo
     * que D-07.3 decide.
     *
     * <h3>Las líneas marcadas como excepción se cuentan aparte (C1.2)</h3>
     * Hay pedidos con cupón que se quedaron en «pagado» sin llegar a
     * «facturado», o sea sin factura de la que prorratear: el ETL los cargó con
     * {@code descuento_cupon_prorrateado = 0} y los MARCÓ con
     * {@code excepcion_descuento = 1}. Sus líneas siguen en la serie —su
     * descuento de promoción sí es real— pero la columna
     * {@code lineas_excepcion} las declara, porque en esos pedidos el cupón
     * entregado NO está representado y la serie los infravalora.
     */
    private Map<String, Object> descuentoEntregado(Filtros f, Map<String, Object> tot) {
        List<Map<String, Object>> items = ch.queryForList(
                "SELECT periodo, categoria, venta_bruta, "
                + "       t_promocion AS descuento_promocion, "
                + "       t_cupon     AS descuento_cupon, "
                + "       t_descuento AS descuento_total, "
                + "       descuento_pct, lineas_excepcion "
                + "FROM ( "
                + "  SELECT formatDateTime(mes, '%Y-%m') AS periodo, "
                + "         mes AS mes_dato, "
                + "         categoria, "
                + "         sum(subtotal_bruto) AS venta_bruta, "
                + "         sum(descuento_promocion) AS t_promocion, "
                + "         sum(descuento_cupon_prorrateado) AS t_cupon, "
                + "         sum(descuento_total) AS t_descuento, "
                + "         " + pct("sum(descuento_total)", "sum(subtotal_bruto)")
                + "             AS descuento_pct, "
                + "         countIf(excepcion_descuento = 1) AS lineas_excepcion "
                + "  FROM " + LINEA + " WHERE 1 " + f.where() + " "
                + "  GROUP BY mes, categoria "
                + ") ORDER BY mes_dato, t_descuento DESC", f.args());

        long lineasExc = num(tot.get("lineas_excepcion"));
        long pedidosExc = num(tot.get("pedidos_excepcion"));

        Map<String, Object> b = bloque("descuento_mes",
                "Descuento entregado por mes y categoría",
                "barras_apiladas",
                money(dec(tot.get("descuento_total"))) + " entregados sobre "
                + money(dec(tot.get("bruto"))) + " de venta bruta ("
                + porcentaje(dec(tot.get("descuento_total")), dec(tot.get("bruto")))
                + " %). Dos capas separadas: promoción a la línea, cupón prorrateado desde "
                + "la cabecera.",
                items);
        b.put("excepciones", lineasExc);
        b.put("pedidosExcepcion", pedidosExc);
        return conSalvedad(b,
                lineasExc == 0
                ? "Sin excepciones de prorrateo en este período."
                : fmt(lineasExc) + " líneas de " + fmt(pedidosExc) + " pedidos van marcadas "
                  + "como EXCEPCIÓN: son pedidos con cupón que se quedaron en «pagado» sin "
                  + "llegar a facturarse, así que no hay factura de la que prorratear el "
                  + "cupón y su capa de cupón entra como cero. Sus líneas siguen en la serie "
                  + "—la promoción sí es real— pero en esos pedidos el descuento entregado "
                  + "está infravalorado, y por eso se cuentan aparte y no se ocultan.");
    }

    // ═════════════════════════════════════════════════════════════════════
    // Elemento 5 — Descuento contra margen
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Doble eje: cuánto se descontó y cuánto margen quedó, mes a mes. Es la
     * vista que responde si el descuento está trayendo volumen o comiéndose la
     * rentabilidad — con el aviso de que el descuento arrastra su IVA y el
     * total cae 1,15× lo descontado.
     */
    private Map<String, Object> descuentoContraMargen(Filtros f, Map<String, Object> tot) {
        List<Map<String, Object>> items = ch.queryForList(
                "SELECT periodo, unidades, venta_bruta, t_descuento AS descuento, "
                + "       descuento_pct, t_venta_neta AS venta_neta, t_margen AS margen, "
                + "       margen_pct, pedidos "
                + "FROM ( "
                + "  SELECT formatDateTime(mes, '%Y-%m') AS periodo, "
                + "         mes AS mes_dato, "
                + "         sum(cantidad) AS unidades, "
                + "         sum(subtotal_bruto) AS venta_bruta, "
                + "         sum(descuento_total) AS t_descuento, "
                + "         " + pct("sum(descuento_total)", "sum(subtotal_bruto)")
                + "             AS descuento_pct, "
                + "         sum(venta_neta) AS t_venta_neta, "
                + "         sum(margen) AS t_margen, "
                + "         " + pct("sum(margen)", "sum(venta_neta)") + " AS margen_pct, "
                + "         countDistinct(pedido_id) AS pedidos "
                + "  FROM " + LINEA + " WHERE 1 " + f.where() + " "
                + "  GROUP BY mes "
                + ") ORDER BY mes_dato", f.args());
        return conSalvedad(bloque("descuento_vs_margen",
                "Descuento contra margen, mes a mes",
                "doble_eje",
                "Un punto por mes sobre " + fmt(num(tot.get("lineas"))) + " líneas. El % de "
                + "descuento es sobre la venta BRUTA del mes y el % de margen sobre la venta "
                + "NETA del mismo mes: son dos bases distintas a propósito, porque el "
                + "descuento se autoriza sobre el precio de lista y el margen se mide sobre "
                + "lo que se cobró.",
                items),
                "El descuento arrastra su IVA: cada dólar descontado baja el total facturado "
                + "en 1,15. Un techo de descuento mal puesto se ve en esta serie un trimestre "
                + "tarde. " + SALVEDAD_COSTO);
    }

    // ═════════════════════════════════════════════════════════════════════
    // Elemento 6 — Capital inmovilizado por mes
    // ═════════════════════════════════════════════════════════════════════

    private Map<String, Object> capitalInmovilizado(Filtros f, Map<String, Object> cap) {
        // La ventana va SOBRE EL AGREGADO, nunca sobre las filas crudas: con
        // `1 PRECEDING` a grano de posición, «el mes anterior» sería la fila
        // anterior de la misma tabla y la variación no significaría nada.
        //
        // Y la frontera de la serie se marca con `row_number() > 1`, jamás
        // comprobando `anterior != 0`: `lagInFrame` rellena la primera fila con
        // el DEFECTO del tipo y no con NULL, así que el primer mes mostraría su
        // capital entero como «variación del +100 %». Es exactamente el error
        // que INV-09 cometió con `any(x) OVER (… 1 PRECEDING)`.
        List<Map<String, Object>> items = ch.queryForList(
                "SELECT formatDateTime(mes, '%Y-%m') AS periodo, "
                + "       posiciones, unidades, capital, entradas, salidas, "
                + "       if(n > 1, "
                + "          " + pct("capital - anterior", "anterior") + ", NULL) "
                + "           AS variacion_pct "
                + "FROM ( "
                + "  SELECT mes, posiciones, unidades, capital, entradas, salidas, "
                + "         lagInFrame(capital) OVER (ORDER BY mes "
                + "             ROWS BETWEEN 1 PRECEDING AND 1 PRECEDING) AS anterior, "
                + "         row_number() OVER (ORDER BY mes) AS n "
                + "  FROM ( "
                + "    SELECT mes, "
                + "           count() AS posiciones, "
                + "           sum(stock_cierre) AS unidades, "
                + "           sum(valor_cierre) AS capital, "
                + "           sum(entradas_mes) AS entradas, "
                + "           sum(salidas_mes) AS salidas "
                + "    FROM " + STOCK + " WHERE 1 " + f.where() + " "
                + "    GROUP BY mes "
                + "  ) "
                + ") ORDER BY mes", f.args());
        return conSalvedad(bloque("capital_mensual",
                "Capital inmovilizado, mes a mes",
                "serie",
                "Cierre de cada mes sobre las posiciones (variante × bodega) con movimiento "
                + "registrado. Al cierre de " + txt(cap.get("mes_cierre")) + ": "
                + money(dec(cap.get("capital_cierre"))) + " en "
                + fmt(num(cap.get("posiciones_cierre"))) + " posiciones.",
                items),
                SALVEDAD_CAPITAL);
    }

    // ═════════════════════════════════════════════════════════════════════

    /** Concatena dos juegos de parámetros respetando su orden en el SQL. */
    private static Object[] concat(Object[] a, Object[] b) {
        Object[] r = new Object[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
