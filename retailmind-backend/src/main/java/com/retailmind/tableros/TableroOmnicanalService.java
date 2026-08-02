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
 * T-1 · TABLERO OMNICANAL — nivel estratégico, objetivo OE-06.
 *
 * Sirve tres decisiones de dirección (§3.1 del diseño):
 * <ul>
 *   <li><b>D-06.1</b> dónde se refuerza y de dónde se retira capacidad por canal;</li>
 *   <li><b>D-06.2</b> cuál de los puntos de caída del recorrido se ataca este trimestre;</li>
 *   <li><b>D-06.3</b> qué medios de cobro se ofrecen, se retiran o se renegocian.</li>
 * </ul>
 *
 * Roles: ADMIN, GERENTE, ANALISTA. <b>Lleva dinero</b>, así que BODEGA y
 * DESPACHO quedan fuera por RUTA —ClickHouse no tiene GRANT por columna y el
 * único corte posible es la de {@code SecurityConfig}, enumerada por nombre
 * (R-5).
 *
 * <h2>Los siete elementos y de dónde salen</h2>
 * Seis se sirven aquí desde {@code fact_pedido} y {@code fact_flujo_caja}. El
 * séptimo —el carrito abandonado— <b>no existe en el almacén</b>: no hay grano
 * de carrito y el diseño decidió no crear la tabla (R-7). Lo pide la PANTALLA
 * con una segunda llamada al informe simple OTD-VEN-08, que ya lleva meses
 * construido sobre PostgreSQL. Efecto colateral bueno: ese bloque sobrevive a
 * ClickHouse apagado.
 */
@Service
public class TableroOmnicanalService extends TableroServiceBase {

    private static final String CODIGO = "T-1";
    private static final String TITULO = "Tablero Omnicanal";
    private static final List<String> DECISIONES = List.of("D-06.1", "D-06.2", "D-06.3");

    private static final String PEDIDO = DWH + ".fact_pedido";
    private static final String CAJA = DWH + ".fact_flujo_caja";
    private static final String LINEA = DWH + ".fact_venta_linea";

    public TableroOmnicanalService(@Qualifier("pgJdbcTemplate") JdbcTemplate pg,
                                   @Qualifier("clickHouseJdbc") JdbcTemplate ch) {
        super(pg, ch);
    }

    /**
     * @param desde     fecha ISO; el rango se ajusta a MESES COMPLETOS
     * @param hasta     fecha ISO; idem
     * @param canal     web | tienda | telefono, o null = los tres
     * @param categoria nombre exacto de la categoría de producto, o null
     */
    public Map<String, Object> tablero(String desde, String hasta, String canal, String categoria) {
        // La validación corre FUERA de `servir`: un filtro inválido es un 400
        // del usuario y lo sigue siendo aunque ClickHouse esté caído.
        String fDesde = fecha(desde, "desde");
        String fHasta = fecha(hasta, "hasta");
        exigirRangoValido(fDesde, fHasta);
        String fCanal = opcion(canal, CANALES, "canal");
        String fCategoria = texto(categoria);

        return servir(CODIGO, TITULO, DECISIONES, () -> {
            // ── Dos juegos de filtros sobre el MISMO hecho ────────────────
            // `vivos` excluye los cancelados: son los 159 que separan la venta
            // de la intención de compra. `todos` los conserva porque el embudo
            // empieza justo ahí — un embudo que arranca en «no cancelado» no
            // puede enseñar la cancelación como fuga.
            Filtros vivos = filtrosPedido(fDesde, fHasta, fCanal, fCategoria, true);
            Filtros todos = filtrosPedido(fDesde, fHasta, fCanal, fCategoria, false);

            Map<String, Object> tot = totales(vivos);
            BigDecimal venta = dec(tot.get("venta"));
            long pedidos = num(tot.get("pedidos"));

            List<Map<String, Object>> bloques = new ArrayList<>();
            bloques.add(participacionPorCanal(vivos, pedidos, venta));
            bloques.add(ticketPorCanal(vivos, pedidos));
            Map<String, Object> omni = omnicanalidad(vivos);
            bloques.add(bloqueOmnicanal(omni));
            bloques.add(embudo(todos));
            bloques.add(cobrosFallidos(fDesde, fHasta));
            bloques.add(mezclaDePago(fDesde, fHasta, fCanal));

            List<String> salvedades = new ArrayList<>();
            if (fCategoria != null) {
                // Filtrar un hecho de CABECERA por un atributo de LÍNEA. El
                // pedido entra entero o no entra: no hay forma de que la mitad
                // de un pedido esté en el embudo.
                salvedades.add("Con el filtro de categoría «" + fCategoria + "» activo, un "
                        + "pedido entra ENTERO en cuanto contiene al menos una línea de esa "
                        + "categoría, y sus importes incluyen las demás categorías del mismo "
                        + "pedido. La categoría es un atributo de la línea y estos elementos "
                        + "se miden a grano de pedido: para el reparto exacto de la venta por "
                        + "categoría, el tablero de Rentabilidad (T-2) mide a grano de línea.");
            }

            return sobreTablero(CODIGO, TITULO, DECISIONES,
                    periodo(fDesde, fHasta, "fact_pedido"),
                    kpis(tot, omni, fDesde, fHasta),
                    bloques, salvedades,
                    "fact_pedido", "fact_flujo_caja");
        });
    }

    // ═════════════════════════════════════════════════════════════════════
    // Filtros
    // ═════════════════════════════════════════════════════════════════════

    private static Filtros filtrosPedido(String desde, String hasta, String canal,
                                         String categoria, boolean soloVivos) {
        Filtros f = new Filtros();
        if (soloVivos) {
            f.y("es_cancelado = 0");
        }
        // Se compara contra `mes`, que el ETL ya resolvió en la zona horaria del
        // negocio, y no contra `fecha_pedido`: un mes recortado por la mitad se
        // dibujaría como una caída de la venta que no ocurrió.
        f.y("mes >= toStartOfMonth(toDate(?))", desde);
        f.y("mes <= toStartOfMonth(toDate(?))", hasta);
        f.y("canal = ?", canal);
        // El filtro de categoría es un SEMI-JOIN contra el hecho de línea: la
        // cabecera no guarda categoría y no puede guardarla (un pedido mezcla
        // varias). La salvedad del sobre dice qué significa.
        f.y("pedido_id IN (SELECT pedido_id FROM " + LINEA + " WHERE categoria = ?)", categoria);
        return f;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Totales y KPI de cabecera
    // ═════════════════════════════════════════════════════════════════════

    private Map<String, Object> totales(Filtros vivos) {
        List<Map<String, Object>> r = ch.queryForList(
                "SELECT count() AS pedidos, "
                + "       sum(total) AS venta, "
                + "       sumIf(total, canal = 'web') AS venta_web, "
                + "       countIf(canal = 'web') AS pedidos_web, "
                + "       countDistinct(cliente_id) AS clientes "
                + "FROM " + PEDIDO + " WHERE 1 " + vivos.where(), vivos.args());
        return r.isEmpty() ? Map.of() : r.get(0);
    }

    /**
     * Las cinco tarjetas de cabecera del diseño, cada una con su nota.
     *
     * <h3>La tasa de rechazo NO es «del cobro en línea»</h3>
     * El diseño pedía «tasa de rechazo del cobro en línea». No es computable:
     * los <b>176 intentos fallidos no tienen canal</b> —la columna llega vacía—
     * porque el intento se registra ANTES de que exista el pedido, que es el
     * mismo motivo por el que C2.1 ya documentó que tampoco tienen
     * {@code pedido_id} ni {@code fecha_pago}. Partirlos por canal daría cero
     * fallos en los tres canales y una tasa de rechazo del 0 % en la tarjeta,
     * sin un solo error. Se publica la tasa GLOBAL y la nota dice por qué.
     */
    private List<Map<String, Object>> kpis(Map<String, Object> tot, Map<String, Object> omni,
                                           String desde, String hasta) {
        long pedidos = num(tot.get("pedidos"));
        BigDecimal venta = dec(tot.get("venta"));
        BigDecimal ventaWeb = dec(tot.get("venta_web"));

        Filtros caja = filtrosCaja(desde, hasta);
        List<Map<String, Object>> c = ch.queryForList(
                "SELECT countIf(estado = 'fallido') AS fallidos, "
                + "       countIf(estado = 'completado') AS completados, "
                + "       count() AS intentos "
                + "FROM " + CAJA + " WHERE sentido = 'ingreso' " + caja.where(), caja.args());
        long fallidos = c.isEmpty() ? 0 : num(c.get(0).get("fallidos"));
        long intentos = c.isEmpty() ? 0 : num(c.get(0).get("intentos"));

        long omnicanales = num(omni.get("omnicanal"));
        long conPedido = num(omni.get("clientes"));

        List<Map<String, Object>> k = new ArrayList<>();
        k.add(kpi("Venta del período", venta, "moneda",
                fmt(pedidos) + " pedidos NO cancelados. Los cancelados quedan fuera: "
                + "miden intención, no venta."));
        k.add(kpi("Ticket medio", div(venta, pedidos), "moneda",
                "Venta del período entre los " + fmt(pedidos) + " pedidos no cancelados."));
        k.add(kpi("Venta por la tienda en línea", porcentaje(ventaWeb, venta), "porcentaje",
                money(ventaWeb) + " de " + money(venta) + " del período."));
        k.add(kpi("Rechazo del cobro", porcentaje(fallidos, intentos), "porcentaje",
                fmt(fallidos) + " intentos fallidos sobre " + fmt(intentos) + " registrados. "
                + "NO es separable por canal: el intento fallido se registra antes de que "
                + "exista el pedido y no guarda canal (misma causa que C2.1)."));
        k.add(kpi("Clientes omnicanales", omnicanales, "numero",
                "Compraron por la web Y por un canal interno. Denominador: " + fmt(conPedido)
                + " clientes con al menos un pedido no cancelado en el período."));
        return k;
    }

    private static Filtros filtrosCaja(String desde, String hasta) {
        Filtros f = new Filtros();
        f.y("mes >= toStartOfMonth(toDate(?))", desde);
        f.y("mes <= toStartOfMonth(toDate(?))", hasta);
        return f;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Elemento 1 — Participación de la venta por canal, mes a mes
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Serie apilada al 100 %: qué parte de la venta de cada mes puso cada canal.
     *
     * La participación se calcula con una ventana sobre el conjunto YA agregado
     * ({@code sum(sum(total)) OVER (PARTITION BY mes)}), no sobre la página:
     * un porcentaje que cambia según cuántas filas estés mirando no es un
     * porcentaje.
     */
    private Map<String, Object> participacionPorCanal(Filtros f, long pedidos, BigDecimal venta) {
        List<Map<String, Object>> items = ch.queryForList(
                "SELECT formatDateTime(mes, '%Y-%m') AS periodo, "
                + "       canal, "
                + "       count() AS pedidos, "
                + "       sum(total) AS venta, "
                + "       sum(unidades) AS unidades, "
                + "       " + pct("sum(total)", "sum(sum(total)) OVER (PARTITION BY mes)")
                + "           AS participacion_pct "
                + "FROM " + PEDIDO + " WHERE 1 " + f.where() + " "
                + "GROUP BY mes, canal "
                + "ORDER BY mes, canal", f.args());
        return bloque("participacion_canal",
                "Participación de la venta por canal, mes a mes",
                "serie_apilada",
                fmt(pedidos) + " pedidos no cancelados · " + money(venta)
                + ". El 100 % de cada mes es la venta de ESE mes, no del período.",
                items);
    }

    // ═════════════════════════════════════════════════════════════════════
    // Elemento 2 — Ticket promedio por canal
    // ═════════════════════════════════════════════════════════════════════

    private Map<String, Object> ticketPorCanal(Filtros f, long pedidos) {
        List<Map<String, Object>> items = ch.queryForList(
                "SELECT formatDateTime(mes, '%Y-%m') AS periodo, "
                + "       canal, "
                + "       count() AS pedidos, "
                + "       round(toFloat64(sum(total)) / count(), 2) AS ticket_promedio, "
                + "       round(toFloat64(sum(unidades)) / count(), 2) AS unidades_por_pedido, "
                + "       round(toFloat64(sum(lineas)) / count(), 2) AS lineas_por_pedido "
                + "FROM " + PEDIDO + " WHERE 1 " + f.where() + " "
                + "GROUP BY mes, canal "
                + "ORDER BY mes, canal", f.args());
        return bloque("ticket_canal",
                "Ticket promedio por canal",
                "serie",
                "Cada punto es la venta del canal en ese mes dividida entre SUS pedidos "
                + "no cancelados; los meses sin pedidos de un canal no producen punto. "
                + "Base del período: " + fmt(pedidos) + " pedidos.",
                items);
    }

    // ═════════════════════════════════════════════════════════════════════
    // Elemento 3 — Cliente omnicanal
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Reparte los clientes del período en tres cestas EXCLUYENTES —los dos
     * canales, solo web, solo interno— en vez de publicar un porcentaje suelto.
     * «El 93 % es omnicanal» sin las otras dos cestas no dice si el 7 % restante
     * es web pura o mostrador puro, que es justo lo que D-06.1 necesita saber
     * para mover capacidad.
     *
     * «Canal interno» agrupa tienda y teléfono: el eje de la decisión es
     * digital contra atendido, y son los dos que comparten personal.
     */
    private Map<String, Object> omnicanalidad(Filtros f) {
        List<Map<String, Object>> r = ch.queryForList(
                "SELECT count() AS clientes, "
                + "       countIf(w = 1 AND i = 1) AS omnicanal, "
                + "       countIf(w = 1 AND i = 0) AS solo_web, "
                + "       countIf(w = 0 AND i = 1) AS solo_interno, "
                + "       sumIf(venta, w = 1 AND i = 1) AS venta_omnicanal, "
                + "       sumIf(venta, w = 1 AND i = 0) AS venta_solo_web, "
                + "       sumIf(venta, w = 0 AND i = 1) AS venta_solo_interno, "
                + "       sumIf(pedidos, w = 1 AND i = 1) AS pedidos_omnicanal, "
                + "       sumIf(pedidos, w = 1 AND i = 0) AS pedidos_solo_web, "
                + "       sumIf(pedidos, w = 0 AND i = 1) AS pedidos_solo_interno "
                + "FROM ( "
                + "  SELECT cliente_id, "
                + "         maxIf(1, canal = 'web')  AS w, "
                + "         maxIf(1, canal != 'web') AS i, "
                + "         count()   AS pedidos, "
                + "         sum(total) AS venta "
                + "  FROM " + PEDIDO + " WHERE 1 " + f.where() + " "
                + "  GROUP BY cliente_id "
                + ")", f.args());
        return r.isEmpty() ? Map.of() : r.get(0);
    }

    private Map<String, Object> bloqueOmnicanal(Map<String, Object> o) {
        long clientes = num(o.get("clientes"));
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(cesta("Omnicanal (web y canal interno)", o, "omnicanal", clientes));
        items.add(cesta("Solo tienda en línea", o, "solo_web", clientes));
        items.add(cesta("Solo canal interno (tienda o teléfono)", o, "solo_interno", clientes));
        return bloque("cliente_omnicanal",
                "Cliente omnicanal",
                "semaforo",
                fmt(clientes) + " clientes con al menos un pedido no cancelado en el período. "
                + "Las tres cestas son excluyentes y suman ese total.",
                items);
    }

    private static Map<String, Object> cesta(String etiqueta, Map<String, Object> o,
                                             String clave, long total) {
        long n = num(o.get(clave));
        Map<String, Object> fila = new LinkedHashMap<>();
        fila.put("segmento", etiqueta);
        fila.put("clientes", n);
        fila.put("clientes_pct", porcentaje(n, total));
        fila.put("pedidos", num(o.get("pedidos_" + clave)));
        fila.put("venta", dec(o.get("venta_" + clave)));
        return fila;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Elemento 4 — Embudo del recorrido
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Cinco pasos, cada uno con su denominador (C2.7).
     *
     * <h3>El paso se mide como «llegó AQUÍ o más lejos», y no por su marca</h3>
     * Ésta es la corrección que este elemento obligó a hacer. Contando el hito
     * a secas —{@code countIf(fecha_despachado IS NOT NULL)}— el embudo NO es
     * monótono: salen 2.868 despachados y 3.696 entregados, o sea 828 pedidos
     * que se entregaron sin registro de despacho (la brecha que la Fase 2 ya
     * había medido para OTD-LOG-12). Dibujado tal cual, el embudo enseña una
     * fuga del 26 % en el despacho que se «recupera» sola en la entrega: una
     * pantalla que invita a reforzar la etapa que no tiene el problema, que es
     * literalmente el error contra el que D-06.2 existe.
     *
     * Un pedido entregado pasó por el despacho aunque nadie apuntara la hora.
     * Por eso cada paso cuenta a quien alcanzó ESE hito <b>o cualquiera
     * posterior</b>, y el resultado sí es monótono: 4.083 → 3.907 → 3.884 →
     * 3.837 → 3.696. Lo que el registro incompleto sí impide es medir el TIEMPO
     * de esa etapa, y eso vive en T-4 con su propio {@code pedidos_medidos}.
     *
     * <h3>Qué es «perdidos»</h3>
     * La diferencia contra el paso anterior. En el primer escalón la mayor
     * parte son los pedidos CANCELADOS, y la fila lo dice: sin eso, el embudo
     * atribuiría al cobro una caída que es una cancelación.
     */
    private Map<String, Object> embudo(Filtros f) {
        List<Map<String, Object>> r = ch.queryForList(
                "SELECT count() AS creados, "
                + "       countIf(es_cancelado = 1) AS cancelados, "
                + "       countIf(fecha_pagado IS NOT NULL "
                + "            OR fecha_facturado IS NOT NULL "
                + "            OR fecha_despachado IS NOT NULL "
                + "            OR fecha_entregado IS NOT NULL) AS cobrados, "
                + "       countIf(fecha_facturado IS NOT NULL "
                + "            OR fecha_despachado IS NOT NULL "
                + "            OR fecha_entregado IS NOT NULL) AS facturados, "
                + "       countIf(fecha_despachado IS NOT NULL "
                + "            OR fecha_entregado IS NOT NULL) AS despachados, "
                + "       countIf(fecha_entregado IS NOT NULL) AS entregados, "
                + "       countIf(fecha_despachado IS NULL "
                + "           AND fecha_entregado IS NOT NULL) AS despacho_sin_marca "
                + "FROM " + PEDIDO + " WHERE 1 " + f.where(), f.args());
        Map<String, Object> e = r.isEmpty() ? Map.of() : r.get(0);

        long creados = num(e.get("creados"));
        long cancelados = num(e.get("cancelados"));
        long cobrados = num(e.get("cobrados"));
        long facturados = num(e.get("facturados"));
        long despachados = num(e.get("despachados"));
        long entregados = num(e.get("entregados"));
        long sinMarca = num(e.get("despacho_sin_marca"));

        List<Map<String, Object>> items = new ArrayList<>();
        items.add(paso(1, "Pedido creado", creados, creados, creados,
                "Todos los pedidos del período, cancelados incluidos: el embudo tiene que "
                + "poder enseñar la cancelación como fuga."));
        items.add(paso(2, "Cobrado", cobrados, creados, creados,
                fmt(cancelados) + " de los " + fmt(creados - cobrados) + " que no llegan son "
                + "pedidos CANCELADOS; el resto se quedó confirmado sin pagar."));
        items.add(paso(3, "Facturado", facturados, cobrados, creados,
                "Denominador: los " + fmt(cobrados) + " cobrados."));
        items.add(paso(4, "Despachado", despachados, facturados, creados,
                "Denominador: los " + fmt(facturados) + " facturados. Incluye "
                + fmt(sinMarca) + " pedidos que llegaron a entregarse sin que se registrara "
                + "la hora de despacho: pasaron por la etapa aunque nadie la apuntara."));
        items.add(paso(5, "Entregado", entregados, despachados, creados,
                "Denominador: los " + fmt(despachados) + " despachados. Los que faltan no "
                + "llegaron tarde: no llegaron."));

        return conSalvedad(bloque("embudo",
                "Embudo del recorrido del pedido",
                "embudo",
                "Base " + fmt(creados) + " pedidos creados en el período. CADA paso declara "
                + "su propio denominador: la tasa de un escalón se mide contra el escalón "
                + "anterior, nunca contra el total.",
                items),
                "Cada paso cuenta a los pedidos que alcanzaron ESE hito o cualquiera "
                + "POSTERIOR, no a los que tienen esa marca de tiempo. Contando la marca a "
                + "secas el embudo no sería monótono —" + fmt(sinMarca) + " pedidos se "
                + "entregaron sin registro de despacho— y aparecería una fuga en el despacho "
                + "que se recupera sola en la entrega. Medir el TIEMPO de esa etapa sí exige "
                + "la marca, y por eso se hace aparte declarando cuántos pedidos se midieron.");
    }

    private static Map<String, Object> paso(int orden, String nombre, long pedidos,
                                            long denominador, long origen, String nota) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("orden", orden);
        p.put("paso", nombre);
        p.put("pedidos", pedidos);
        p.put("denominador", denominador);
        p.put("perdidos", denominador - pedidos);
        p.put("tasa_paso_pct", porcentaje(pedidos, denominador));
        p.put("tasa_origen_pct", porcentaje(pedidos, origen));
        p.put("nota", nota);
        return p;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Elemento 5 — Cobros fallidos por motivo y mes
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Los cinco motivos de rechazo, mes a mes.
     *
     * <h3>La fecha de estas filas es la del INTENTO (C2.1)</h3>
     * Un cobro rechazado nunca se liquida, así que no tiene {@code fecha_pago};
     * el ETL usa {@code fecha_creacion} y lo marca con {@code fecha_es_intento
     * = 1}. La salvedad lo dice en pantalla porque, mezclado con la mezcla de
     * pago del elemento siguiente —donde la fecha SÍ es la del cobro—, un mes
     * significa dos cosas distintas en dos gráficos que se leen seguidos.
     *
     * <h3>El filtro de canal no se aplica aquí, y se declara</h3>
     * Los 176 fallidos llegan con el canal VACÍO por la misma razón: el intento
     * se registra antes de que exista el pedido. Aplicarles {@code canal = ?}
     * los borraría a todos y el bloque saldría vacío sin un solo error — con la
     * lectura «no hay rechazos en este canal», que es lo contrario de la
     * verdad.
     *
     * La lista de motivos NO se enumera en el código: sale del propio dato
     * ({@code GROUP BY}), que es la regla que C3C.3 y C4.7 dejaron escrita con
     * sangre.
     */
    private Map<String, Object> cobrosFallidos(String desde, String hasta) {
        Filtros f = filtrosCaja(desde, hasta);
        List<Map<String, Object>> items = ch.queryForList(
                "SELECT formatDateTime(mes, '%Y-%m') AS periodo, "
                + "       motivo_fallo AS motivo, "
                + "       count() AS intentos, "
                + "       sum(monto) AS monto, "
                + "       any(intentos_mes) AS intentos_del_mes, "
                + "       " + pct("count()", "any(intentos_mes)") + " AS pct_del_mes "
                + "FROM ( "
                + "  SELECT mes, motivo_fallo, monto, estado, "
                + "         count() OVER (PARTITION BY mes) AS intentos_mes "
                + "  FROM " + CAJA + " WHERE sentido = 'ingreso' " + f.where() + " "
                + ") WHERE estado = 'fallido' "
                + "GROUP BY mes, motivo_fallo "
                + "ORDER BY mes, intentos DESC", f.args());

        List<Map<String, Object>> tot = ch.queryForList(
                "SELECT countIf(estado = 'fallido') AS fallidos, count() AS intentos, "
                + "       sumIf(monto, estado = 'fallido') AS monto "
                + "FROM " + CAJA + " WHERE sentido = 'ingreso' " + f.where(), f.args());
        long fallidos = tot.isEmpty() ? 0 : num(tot.get(0).get("fallidos"));
        long intentos = tot.isEmpty() ? 0 : num(tot.get(0).get("intentos"));

        return conSalvedad(bloque("cobros_fallidos",
                "Cobros rechazados por motivo y mes",
                "barras_apiladas",
                fmt(fallidos) + " intentos rechazados sobre " + fmt(intentos) + " intentos de "
                + "cobro del período (" + porcentaje(fallidos, intentos) + " %). El "
                + "porcentaje de cada barra es sobre los intentos de SU mes.",
                items),
                "La fecha de un cobro rechazado es la del INTENTO, no la de un pago: un "
                + "cobro que se rechaza nunca llega a liquidarse y no tiene fecha de pago "
                + "(C2.1). Por la misma razón NO guarda canal, así que el filtro de canal "
                + "del tablero no se aplica a este bloque — aplicarlo lo dejaría vacío y "
                + "se leería como «en este canal no hay rechazos».");
    }

    // ═════════════════════════════════════════════════════════════════════
    // Elemento 6 — Mezcla de forma de pago
    // ═════════════════════════════════════════════════════════════════════

    private Map<String, Object> mezclaDePago(String desde, String hasta, String canal) {
        Filtros f = filtrosCaja(desde, hasta);
        f.y("canal = ?", canal);
        // OJO con el alias: `sum(monto) AS monto` hace que ClickHouse sustituya
        // el `monto` de la ventana por su propia definición y produzca
        // `sum(sum(monto))` → ILLEGAL_AGGREGATION. El agregado usa nombres con
        // prefijo `t_` y el contrato de la API se repone en el SELECT exterior,
        // donde ya no queda ningún alias que resolver.
        List<Map<String, Object>> items = ch.queryForList(
                "SELECT periodo, forma_pago, tipo, cobros, "
                + "       t_monto AS monto, participacion_pct "
                + "FROM ( "
                + "  SELECT formatDateTime(mes, '%Y-%m') AS periodo, "
                + "         mes AS mes_dato, "
                + "         metodo_pago AS forma_pago, "
                + "         any(metodo_pago_tipo) AS tipo, "
                + "         count() AS cobros, "
                + "         sum(monto) AS t_monto, "
                + "         " + pct("sum(monto)", "sum(sum(monto)) OVER (PARTITION BY mes)")
                + "             AS participacion_pct "
                + "  FROM " + CAJA + " "
                + "  WHERE sentido = 'ingreso' AND estado = 'completado' " + f.where() + " "
                + "  GROUP BY mes, metodo_pago "
                + ") ORDER BY mes_dato, t_monto DESC", f.args());

        List<Map<String, Object>> tot = ch.queryForList(
                "SELECT count() AS cobros, sum(monto) AS monto FROM " + CAJA + " "
                + "WHERE sentido = 'ingreso' AND estado = 'completado' " + f.where(), f.args());
        long cobros = tot.isEmpty() ? 0 : num(tot.get(0).get("cobros"));
        BigDecimal monto = tot.isEmpty() ? BigDecimal.ZERO : dec(tot.get(0).get("monto"));

        return bloque("mezcla_pago",
                "Mezcla de la forma de pago",
                "areas_apiladas",
                fmt(cobros) + " cobros COMPLETADOS · " + money(monto) + ". Los rechazados "
                + "quedan fuera y se miran en el bloque anterior: un cobro que no entró no "
                + "es una forma de pago que el negocio recibió.",
                items);
    }

}
