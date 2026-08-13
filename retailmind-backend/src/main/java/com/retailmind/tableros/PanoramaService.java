package com.retailmind.tableros;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * PANORAMA DEL NEGOCIO — la foto de conjunto del comercio.
 *
 * <h2>Por qué existe, si ya hay siete tableros</h2>
 *
 * Los siete tableros de dirección responden cada uno por SU ámbito («¿qué hago
 * con el abastecimiento?», «¿qué descontinúo?») y los 73 informes son de
 * detalle. Ninguna pantalla contestaba la primera pregunta que hace cualquiera
 * que se sienta delante del sistema: <b>¿de qué tamaño es este comercio y está
 * sano?</b> Esta lo hace, y por eso NO lleva filtros: la foto de conjunto es la
 * década entera o no es una foto de conjunto. Quien quiera acotar tiene los
 * siete tableros, que sí filtran.
 *
 * <h3>Lo que deliberadamente NO repite de los tableros</h3>
 * <ul>
 *   <li>El estado del almacén va como <b>franja de resumen</b> (cuatro cifras),
 *       no como las tres tablas de T-7 «Gobierno del Dato». Aquí interesa
 *       «¿está fresco y cuadra?»; el detalle tarea por tarea es de T-7.</li>
 *   <li>No hay embudo ni mezcla de canal: son de T-1, y con filtros.</li>
 *   <li>Los seis gráficos son series de la DÉCADA o rankings globales — el
 *       eje que ningún tablero toma, porque todos acotan período.</li>
 * </ul>
 *
 * <h2>Coste medido (mediana de 3, tiempo de servidor)</h2>
 * Las nueve consultas suman <b>~300 ms</b> de ClickHouse sobre 27 M de filas.
 * La más cara es el kardex (8,0 M filas, 27-158 ms). Se sirven en UNA petición
 * por la misma razón que un tablero (véase {@link TableroServiceBase}): una
 * sola marca de agua y una sola decisión de degradación.
 *
 * <h2>La trampa de ClickHouse que aparece aquí seis veces</h2>
 * Ningún alias de agregado puede llamarse como una columna de la tabla que
 * agrega, o el motor responde {@code ILLEGAL_AGGREGATION}. En
 * {@code fact_venta_linea} colisionan {@code venta_neta}, {@code margen} y
 * {@code margen_pct}; en {@code fact_pedido}, {@code unidades}; en
 * {@code fact_envio}, {@code costo}. Por eso los alias de este archivo son
 * {@code venta}, {@code margen_bruto}, {@code pct_margen}, {@code uds} y
 * {@code monto} — elegidos para no chocar, no por gusto.
 *
 * <h2>Cualificación obligatoria</h2>
 * El bean de ClickHouse apunta por defecto a la base LEGADA de analítica web.
 * Toda tabla va cualificada con {@link #DWH}; una consulta sin cualificar leería
 * la base equivocada <b>sin dar error</b> ({@code dim_producto} existe en las
 * dos con conteos distintos).
 */
@Service
public class PanoramaService extends TableroServiceBase {

    private static final String CODIGO = "negocio";
    private static final String TITULO = "Panorama del Negocio";

    /** Las tablas que sostienen la pantalla; la marca de agua es la MÁS REZAGADA. */
    private static final String[] FUENTES = {
        "fact_pedido", "fact_venta_linea", "fact_envio",
        "fact_orden_compra", "fact_devolucion", "fact_movimiento_inventario"
    };

    public PanoramaService(@Qualifier("pgJdbcTemplate") JdbcTemplate pg,
                           @Qualifier("clickHouseJdbc") JdbcTemplate ch) {
        super(pg, ch);
    }

    public Map<String, Object> panorama() {
        return servir(CODIGO, TITULO, List.of(), () -> {
            List<Map<String, Object>> kpis = kpis();
            List<Map<String, Object>> bloques = new ArrayList<>();
            bloques.add(ventaMensual());
            bloques.add(flujosDinero());
            bloques.add(categorias());
            bloques.add(margenMensual());
            bloques.add(puntualidadMensual());
            bloques.add(kardexMensual());

            Map<String, Object> sobre = sobreTablero(
                    CODIGO, TITULO, List.of(), Map.of(),
                    kpis, bloques,
                    List.of("La ventana es la DÉCADA COMPLETA (2025-2034) y la pantalla no "
                            + "ofrece filtros a propósito: es la foto de conjunto. Para acotar "
                            + "por período, canal o categoría están los siete tableros de dirección.",
                            "El margen se computa contra el costo VIGENTE de la variante — el "
                            + "sistema no almacena COGS histórico —, así que es volumen a moneda "
                            + "constante y no el margen contable de cada mes."),
                    FUENTES);
            // El estado del almacén va DESPUÉS del sobre estándar: no es un bloque
            // que se dibuje como los demás, es la franja que dice si lo de arriba
            // se puede creer.
            sobre.put("almacen", almacen());
            return sobre;
        });
    }

    // ── KPIs ─────────────────────────────────────────────────────────────

    /**
     * Seis cifras, elegidas para que cada una responda algo distinto: TAMAÑO
     * (venta, pedidos, clientes), ECONOMÍA UNITARIA (ticket, margen) y CALIDAD
     * DE LA OPERACIÓN (entregas a tiempo). Cada una lleva su denominador, que en
     * este molde es obligatorio.
     */
    private List<Map<String, Object>> kpis() {
        Map<String, Object> v = ch.queryForMap(
                "SELECT count() AS pedidos, sum(total) AS venta, sum(unidades) AS uds, "
              + "       uniqExact(cliente_id) AS clientes, "
              + "       round(sum(total) / count(), 2) AS ticket "
              + "FROM " + DWH + ".fact_pedido WHERE es_cancelado = 0");

        Map<String, Object> m = ch.queryForMap(
                "SELECT sum(venta_neta) AS venta, sum(margen) AS margen_bruto, "
              + "       round(100 * sum(margen) / nullIf(sum(venta_neta), 0), 2) AS pct_margen "
              + "FROM " + DWH + ".fact_venta_linea WHERE es_cancelado = 0");

        Map<String, Object> e = ch.queryForMap(
                "SELECT count() AS medibles, countIf(entregado_a_tiempo = 1) AS a_tiempo, "
              + "       round(100 * countIf(entregado_a_tiempo = 1) / count(), 2) AS pct "
              + "FROM " + DWH + ".fact_envio WHERE entregado_a_tiempo IS NOT NULL");

        List<Map<String, Object>> kpis = new ArrayList<>();
        kpis.add(kpi("Venta de la década", v.get("venta"), "moneda",
                "Suma de los pedidos NO cancelados de 2025 a 2034"));
        kpis.add(kpi("Pedidos", v.get("pedidos"), "numero",
                "No cancelados; el total incluido el cancelado es mayor"));
        kpis.add(kpi("Ticket medio", v.get("ticket"), "moneda",
                "Venta entre pedidos no cancelados"));
        kpis.add(kpi("Margen bruto", m.get("pct_margen"), "porcentaje",
                "Sobre venta neta de línea, a costo vigente"));
        kpis.add(kpi("Clientes con compra", v.get("clientes"), "numero",
                "Clientes distintos con al menos un pedido no cancelado"));
        kpis.add(kpi("Entregas a tiempo", e.get("pct"), "porcentaje",
                "Sobre los envíos con promesa y entrega registradas"));
        return kpis;
    }

    // ── Bloques ──────────────────────────────────────────────────────────

    /** ¿Cómo ha crecido la venta en diez años? — 120 puntos. */
    private Map<String, Object> ventaMensual() {
        List<Map<String, Object>> items = ch.queryForList(
                "SELECT formatDateTime(mes, '%Y-%m') AS periodo, "
              + "       sum(total) AS venta, count() AS pedidos "
              + "FROM " + DWH + ".fact_pedido WHERE es_cancelado = 0 "
              + "GROUP BY mes ORDER BY mes");
        return bloque("venta_mensual", "Venta mensual de la década", "serie",
                "Todos los pedidos no cancelados, agrupados por mes de pedido", items);
    }

    /**
     * ¿Cuál es la escala de cada flujo de dinero? Es el único bloque que cruza
     * CUATRO tablas de hechos, y es lo que ningún tablero pone junto.
     */
    private Map<String, Object> flujosDinero() {
        // El UNION va ENVUELTO en una subconsulta y el ORDER BY fuera. En
        // ClickHouse un `ORDER BY` escrito detrás de un `UNION ALL` se liga al
        // ÚLTIMO SELECT, que no declara los alias —solo el primero lo hace—, y
        // el motor responde `UNKNOWN_IDENTIFIER: monto`.
        List<Map<String, Object>> items = ch.queryForList(
                "SELECT concepto, monto, origen FROM ("
              + "  SELECT 'Venta al cliente' AS concepto, sum(total) AS monto, "
              + "         'fact_pedido · pedidos no cancelados' AS origen "
              + "  FROM " + DWH + ".fact_pedido WHERE es_cancelado = 0 "
              + "  UNION ALL SELECT 'Compra a proveedor', sum(factura_total), "
              + "         'fact_orden_compra · lo facturado por el proveedor' "
              + "  FROM " + DWH + ".fact_orden_compra "
              + "  UNION ALL SELECT 'Flete de la última milla', sum(costo), "
              + "         'fact_envio · excluye los envíos sin tarifar' "
              + "  FROM " + DWH + ".fact_envio WHERE sin_tarifa = 0 "
              + "  UNION ALL SELECT 'Devolución de cliente', sum(monto_total), "
              + "         'fact_devolucion · monto solicitado' "
              + "  FROM " + DWH + ".fact_devolucion"
              + ") ORDER BY monto DESC");
        return bloque("flujos_dinero", "La escala de cada flujo de dinero", "barras",
                "Década completa; cada barra declara de qué tabla sale", items);
    }

    /** ¿Qué categorías sostienen la venta? */
    private Map<String, Object> categorias() {
        List<Map<String, Object>> items = ch.queryForList(
                "SELECT categoria, sum(venta_neta) AS venta, sum(cantidad) AS unidades, "
              + "       round(100 * sum(margen) / nullIf(sum(venta_neta), 0), 2) AS pct_margen "
              + "FROM " + DWH + ".fact_venta_linea WHERE es_cancelado = 0 "
              + "GROUP BY categoria ORDER BY venta DESC LIMIT 10");
        return bloque("categorias", "Las diez categorías que más venden", "ranking",
                "Venta neta de línea de la década; 10 de las categorías del catálogo", items);
    }

    /** ¿El margen aguanta el crecimiento? */
    private Map<String, Object> margenMensual() {
        List<Map<String, Object>> items = ch.queryForList(
                "SELECT formatDateTime(mes, '%Y-%m') AS periodo, "
              + "       round(100 * sum(margen) / nullIf(sum(venta_neta), 0), 2) AS pct_margen, "
              + "       sum(venta_neta) AS venta "
              + "FROM " + DWH + ".fact_venta_linea WHERE es_cancelado = 0 "
              + "GROUP BY mes ORDER BY mes");
        return conSalvedad(
                bloque("margen_mensual", "Margen bruto mes a mes", "serie",
                        "Margen sobre venta neta de línea, de los pedidos no cancelados", items),
                "A costo VIGENTE: el sistema no guarda costo histórico, así que la serie mide "
                + "la mezcla de producto, no la inflación del costo.");
    }

    /** ¿La operación cumple la promesa mientras crece? */
    private Map<String, Object> puntualidadMensual() {
        List<Map<String, Object>> items = ch.queryForList(
                "SELECT formatDateTime(mes, '%Y-%m') AS periodo, "
              + "       round(100 * countIf(entregado_a_tiempo = 1) / count(), 2) AS pct, "
              + "       count() AS medibles "
              + "FROM " + DWH + ".fact_envio WHERE entregado_a_tiempo IS NOT NULL "
              + "GROUP BY mes ORDER BY mes");
        return conSalvedad(
                bloque("puntualidad_mensual", "Entregas a tiempo mes a mes", "serie",
                        "Solo los envíos con fecha prometida y fecha real; los no entregados "
                        + "no cuentan ni a favor ni en contra", items),
                "El denominador NO son todos los envíos: un envío sin entrega registrada no "
                + "llegó tarde, no llegó, y mezclarlo hundiría la serie sin explicar por qué.");
    }

    /** ¿Cuánto músculo físico mueve el almacén? */
    private Map<String, Object> kardexMensual() {
        List<Map<String, Object>> items = ch.queryForList(
                "SELECT formatDateTime(mes, '%Y-%m') AS periodo, "
              + "       sum(cantidad) AS unidades, count() AS movimientos "
              + "FROM " + DWH + ".fact_movimiento_inventario "
              + "GROUP BY mes ORDER BY mes");
        return bloque("kardex_mensual", "Unidades movidas en el almacén", "barras",
                "Todos los movimientos de kardex, entradas y salidas, en valor absoluto", items);
    }

    // ── Estado del almacén ───────────────────────────────────────────────

    /**
     * La franja que dice si lo de arriba se puede creer: cuándo corrió el ETL,
     * si los 49 controles cuadraron, cuántas tablas publicó y cuántas filas hay.
     *
     * En {@code etl_ejecucion}, {@code corrida} y {@code validar_dwh} NO son
     * tablas del modelo: la primera escribe dos filas por proceso (una al
     * empezar y otra al acabar) y su {@code filas_escritas} repite el total de
     * todas las tablas. Sumar sin excluirlas da el DOBLE. Por eso se excluyen de
     * todo conteo y cada tarea se colapsa con {@code argMax(..., inicio)}.
     */
    private Map<String, Object> almacen() {
        Map<String, Object> a = new LinkedHashMap<>();

        String ultima = ch.queryForObject(
                "SELECT if(max(inicio) IS NULL, '', formatDateTime(max(inicio), '%d/%m/%Y %H:%i')) "
              + "FROM " + DWH + ".etl_ejecucion WHERE tarea = 'corrida'", String.class);
        a.put("ultimaCorrida", ultima);

        Map<String, Object> val = ch.queryForMap(
                "SELECT argMax(resultado, inicio) AS resultado, "
              + "       argMax(mensaje, inicio) AS mensaje "
              + "FROM " + DWH + ".etl_ejecucion WHERE tarea = 'validar_dwh'");
        a.put("validacion", val.get("resultado"));
        a.put("mensajeValidacion", val.get("mensaje"));

        Map<String, Object> t = ch.queryForMap(
                "SELECT count() AS tablas, sum(filas) AS filas, "
              + "       countIf(resultado != 'exito') AS con_fallo FROM ("
              + "  SELECT tarea, argMax(filas_escritas, inicio) AS filas, "
              + "         argMax(resultado, inicio) AS resultado "
              + "  FROM " + DWH + ".etl_ejecucion "
              + "  WHERE tarea NOT IN ('corrida', 'validar_dwh') GROUP BY tarea)");
        a.put("tablasPublicadas", t.get("tablas"));
        a.put("filasPublicadas", t.get("filas"));
        a.put("tablasConFallo", t.get("con_fallo"));
        return a;
    }
}
