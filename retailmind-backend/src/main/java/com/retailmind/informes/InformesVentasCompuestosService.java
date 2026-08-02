package com.retailmind.informes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * INFORMES TÁCTICOS COMPUESTOS — VENTAS. Fuente: ClickHouse
 * ({@code retailmind_dwh}), no PostgreSQL.
 *
 * Primer módulo del nivel COMPUESTO y piloto del pipeline ETL (§9.2 del diseño).
 * Implementa OTD-VEN-06; los otros nueve compuestos de Ventas llegan con sus
 * tablas en las fases 2 a 4.
 *
 * Ninguno de estos métodos lleva {@code @Transactional}: no tocan PostgreSQL.
 * Ver {@link InformeCompuestoServiceBase} para las tres diferencias de esta
 * capa (fuente, corte por ruta, marca de agua) y para la degradación.
 */
@Service
public class InformesVentasCompuestosService extends InformeCompuestoServiceBase {

    /** Tabla que sirve el informe; también de la que sale la marca de agua. */
    private static final String TABLA = "fact_venta_linea";

    public InformesVentasCompuestosService(
            @Qualifier("pgJdbcTemplate") JdbcTemplate pg,
            @Qualifier("clickHouseJdbc") JdbcTemplate ch) {
        super(pg, ch);
    }

    // ═════════════════════════════════════════════════════════════════════
    // OTD-VEN-06 — Evolución de la venta mes a mes y por categoría
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Serie mensual de la venta con desglose por categoría de producto.
     *
     * <h3>Grano y por qué esta tabla</h3>
     * Una fila por (mes, categoría). El grano de origen es la LÍNEA de pedido
     * —{@code fact_venta_linea}, 10.384 filas— porque la categoría es un
     * atributo del producto y no del pedido: un pedido con artículos de tres
     * categorías reparte su venta entre las tres. Agregar desde la cabecera
     * daría el total correcto y el desglose imposible.
     *
     * <h3>Qué se excluye, y se dice</h3>
     * Los pedidos CANCELADOS quedan fuera ({@code es_cancelado = 0}): son los
     * 159 que separan $5.716.436,55 de $5.498.570,35 en el sistema. Un informe
     * de evolución del ingreso que los contara mediría intención, no venta.
     *
     * <h3>Los meses sin venta salen en la serie</h3>
     * Es la razón de ser de {@code dim_fecha} (§4.1) y la corrección de una
     * debilidad ya conocida del catálogo: OTD-GER-01 tuvo que emitir a mano una
     * fila «Día sin movimiento» porque un {@code GROUP BY} sobre los hechos
     * solo puede devolver los períodos que existen en los hechos. Aquí, si el
     * rango pedido incluye un mes sin una sola venta, la serie emite una fila
     * explícita con categoría «(sin ventas)» y ceros, en vez de saltárselo. Un
     * hueco invisible en una serie temporal se lee como continuidad, y eso es
     * un error de lectura que el informe puede evitar.
     *
     * <h3>Variación mes a mes</h3>
     * {@code variacion_pct} compara cada categoría contra SU propio mes
     * anterior dentro del rango filtrado ({@code lagInFrame} particionado por
     * categoría). En el primer mes de la serie no hay contra qué comparar y la
     * columna va nula — no cero, que se leería como «no creció».
     *
     * <h3>Costo y margen</h3>
     * El margen usa el costo VIGENTE de la variante, congelado en la corrida
     * del ETL (§8.3: no existe costo histórico en el sistema). La marca de agua
     * del sobre dice a qué fecha corresponde ese costo.
     *
     * @param desde     fecha ISO; el rango se ajusta a MESES COMPLETOS
     * @param hasta     fecha ISO; idem
     * @param categoria nombre exacto de la categoría, o null = todas
     * @param canal     web | tienda | telefono, o null = todos
     */
    public Map<String, Object> evolucionMensual(String desde, String hasta, String categoria,
                                                String canal, int page, int size) {
        // La validación corre FUERA de `ejecutar`: un filtro inválido es un 400
        // del usuario y debe seguir siéndolo aunque ClickHouse esté caído.
        String fDesde = fecha(desde, "desde");
        String fHasta = fecha(hasta, "hasta");
        exigirRangoValido(fDesde, fHasta);
        String fCanal = opcion(canal, CANALES, "canal");
        String fCategoria = texto(categoria);

        return ejecutar("OTD-VEN-06", () -> {
            Filtros f = new Filtros();
            f.y("es_cancelado = 0");
            // El rango se ajusta a meses COMPLETOS: en una serie mensual, un mes
            // recortado por la mitad se dibujaría como una caída de la venta que
            // no ocurrió. Se compara contra `mes`, que el ETL ya calculó en la
            // zona del negocio (§8.6) — no contra `fecha_pedido`.
            f.y("mes >= toStartOfMonth(toDate(?))", fDesde);
            f.y("mes <= toStartOfMonth(toDate(?))", fHasta);
            f.y("categoria = ?", fCategoria);
            f.y("canal = ?", fCanal);

            Map<String, Object> sobre = paginarCh(
                    sqlItems(f.where()), sqlCount(f.where()), f.args(), page, size);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> pagina =
                    (List<Map<String, Object>>) sobre.get("items");
            List<Map<String, Object>> vacios = mesesSinVenta(f, fDesde, fHasta, page);
            if (!vacios.isEmpty()) {
                List<Map<String, Object>> items = new ArrayList<>(pagina);
                items.addAll(vacios);
                sobre.put("items", items);
                // Los meses vacíos no salen del agregado, así que tampoco están
                // en el `count()`: se suman al total o el paginador mostraría
                // menos filas de las que se están pintando.
                sobre.put("total", ((Number) sobre.get("total")).intValue() + vacios.size());
            }

            conResumen(sobre, kpis(f));
            return conMarcaDeAgua(sobre, TABLA);
        });
    }

    // ── Consultas ────────────────────────────────────────────────────────

    /**
     * El agregado por (mes, categoría) más dos medidas de contexto que se
     * calculan con ventana sobre el conjunto YA agregado:
     *
     * <ul>
     *   <li>{@code participacion_pct} — el peso de la categoría dentro de la
     *       venta de SU mes. Responde «qué se vendió», que es la mitad de la
     *       pregunta del objetivo.</li>
     *   <li>{@code variacion_pct} — contra el mismo mes de la categoría
     *       anterior. Responde «cómo evoluciona», la otra mitad.</li>
     * </ul>
     *
     * Las ventanas van en una subconsulta y ANTES del {@code LIMIT}, porque
     * calcularlas sobre la página visible daría porcentajes que cambian al
     * pasar de página — un número distinto según dónde estés mirando.
     *
     * <h4>Por qué los alias internos llevan prefijo {@code t_}</h4>
     * ClickHouse resuelve los alias hacia atrás: si el agregado escribe
     * {@code sum(v.margen) AS margen} y una capa exterior vuelve a nombrar
     * {@code margen}, el motor sustituye el alias por su expresión y falla con
     * {@code ILLEGAL_AGGREGATION: Aggregate function sum(margen) is found
     * inside another aggregate function}. No es un capricho de estilo: dar a
     * un agregado el mismo nombre que su columna de origen rompe la consulta en
     * cuanto se apila una ventana encima. Los nombres del contrato de la API
     * ({@code venta_neta}, {@code margen}…) se reponen en el SELECT más
     * externo, donde ya no hay nada que resolver.
     */
    private static String sqlItems(String where) {
        return """
            SELECT
                etiqueta_mes    AS mes,
                categoria_dato  AS categoria,
                n_pedidos       AS pedidos,
                n_unidades      AS unidades,
                t_venta_bruta   AS venta_bruta,
                t_descuentos    AS descuentos,
                t_venta_neta    AS venta_neta,
                t_costo         AS costo,
                t_margen        AS margen,
                margen_pct,
                participacion_pct,
                variacion_pct
            FROM (
                SELECT
                    etiqueta_mes, mes_dato, categoria_dato,
                    n_pedidos, n_unidades, t_venta_bruta, t_descuentos,
                    t_venta_neta, t_costo, t_margen,
                    -- Los PORCENTAJES se calculan en Float64, y hay que forzarlo.
                    -- Dividir dos Decimal(14,2) en ClickHouse devuelve un
                    -- Decimal con la escala del operando IZQUIERDO: 0,1508 se
                    -- trunca a 0,15 y el margen acaba mostrándose como 15,00 en
                    -- vez de 15,08. El dinero sigue siendo Decimal —esa regla no
                    -- se toca—; un porcentaje derivado no es dinero y no entra
                    -- en ninguna suma que deba cuadrar al centavo, así que en
                    -- Float64 gana precisión sin arriesgar nada.
                    round(toFloat64(t_margen)
                          / nullIf(toFloat64(t_venta_neta), 0) * 100, 2)  AS margen_pct,
                    round(toFloat64(t_venta_neta)
                          / nullIf(toFloat64(sum(t_venta_neta) OVER (PARTITION BY mes_dato)), 0)
                          * 100, 2)                                       AS participacion_pct,
                    round((toFloat64(t_venta_neta) - toFloat64(t_anterior))
                          / nullIf(toFloat64(t_anterior), 0) * 100, 2)    AS variacion_pct
                FROM (
                    SELECT
                        etiqueta_mes, mes_dato, categoria_dato,
                        n_pedidos, n_unidades, t_venta_bruta, t_descuentos,
                        t_venta_neta, t_costo, t_margen,
                        lagInFrame(t_venta_neta) OVER (
                            PARTITION BY categoria_dato ORDER BY mes_dato
                            ROWS BETWEEN 1 PRECEDING AND 1 PRECEDING
                        )                                              AS t_anterior
                    FROM (%s)
                )
            )
            ORDER BY etiqueta_mes DESC, t_venta_neta DESC
            """.formatted(agregado(where));
    }

    private static String sqlCount(String where) {
        return "SELECT count() FROM (" + agregado(where) + ")";
    }

    /**
     * El agregado base. {@code mes_etiqueta} viene de {@code dim_fecha} y no de
     * un {@code formatDateTime} sobre el hecho: es exactamente para lo que la
     * dimensión existe, y de paso el texto «AAAA-MM» viaja ya formateado, que
     * es la regla vigente contra el desfase de un día del formateador
     * ({@code PATRON_INFORMES.md} §11).
     *
     * El join es LEFT y lleva respaldo, a propósito. Con INNER, un mes de venta
     * que cayera fuera del calendario cargado (hoy 2025-2026) desaparecería del
     * informe SIN error: la dimensión estaría decidiendo qué hechos existen, que
     * es justo al revés. Con LEFT la fila sobrevive y el respaldo le pone su
     * etiqueta, de modo que un mes fuera de rango se VEA en el informe en vez de
     * faltar en silencio.
     */
    private static String agregado(String where) {
        return """
            SELECT
                any(if(empty(d.mes_etiqueta),
                       formatDateTime(v.mes, '%%Y-%%m'),
                       d.mes_etiqueta))                      AS etiqueta_mes,
                v.mes                                        AS mes_dato,
                v.categoria                                  AS categoria_dato,
                countDistinct(v.pedido_id)                   AS n_pedidos,
                sum(v.cantidad)                              AS n_unidades,
                sum(v.subtotal_bruto)                        AS t_venta_bruta,
                sum(v.descuento_total)                       AS t_descuentos,
                sum(v.venta_neta)                            AS t_venta_neta,
                sum(v.costo_total)                           AS t_costo,
                sum(v.margen)                                AS t_margen
            FROM %s.%s v
            LEFT JOIN %s.dim_fecha d ON d.fecha = v.mes
            WHERE 1 %s
            GROUP BY v.mes, v.categoria
            """.formatted(DWH, TABLA, DWH, where);
    }

    /**
     * Filas explícitas para los meses del rango que NO tuvieron ninguna venta.
     *
     * Se resuelven contra {@code dim_fecha} —el calendario completo— con un
     * anti-join sobre los meses que sí aparecen en el hecho. Sin la dimensión
     * esta pregunta no tiene respuesta: los hechos no saben qué meses faltan.
     *
     * Solo se emiten en la PRIMERA página y solo cuando el usuario acotó el
     * rango: sin rango, el informe abarcaría hasta diciembre de 2026 y añadiría
     * cinco meses vacíos de relleno a una serie que termina en julio. Un mes sin
     * venta es información cuando alguien preguntó por él; es ruido cuando
     * nadie lo pidió.
     *
     * El anti-join reutiliza los MISMOS filtros del informe (`f`), no solo el
     * rango. Así «sin ventas» significa «sin ventas de lo que preguntaste»: con
     * el filtro de categoría en Belleza, un mes en que Belleza no vendió nada
     * aparece como vacío aunque el resto del catálogo sí vendiera. Comprobar
     * contra el total sería responder otra pregunta.
     */
    private List<Map<String, Object>> mesesSinVenta(Filtros f, String desde, String hasta,
                                                    int page) {
        if (page > 0 || (desde == null && hasta == null)) {
            return List.of();
        }

        Filtros rango = new Filtros();
        rango.y("mes_inicio >= toStartOfMonth(toDate(?))", desde);
        rango.y("mes_inicio <= toStartOfMonth(toDate(?))", hasta);

        // `etiqueta_mes` y no `mes`: ningún alias de estas consultas puede
        // llamarse igual que una columna de las tablas implicadas (ver kpis()).
        String sql = """
            SELECT DISTINCT d.mes_etiqueta AS etiqueta_mes
            FROM %s.dim_fecha d
            WHERE 1 %s
              AND d.mes_inicio NOT IN (
                    SELECT DISTINCT mes FROM %s.%s WHERE 1 %s
              )
            ORDER BY etiqueta_mes DESC
            """.formatted(DWH, rango.where(), DWH, TABLA, f.where());

        // Orden de los parámetros = orden de aparición en el SQL: primero los
        // del rango del calendario, después los del informe dentro del anti-join.
        Object[] args = rango.argsCon(f.args());

        List<Map<String, Object>> vacios = new ArrayList<>();
        for (Map<String, Object> fila : ch.queryForList(sql, args)) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("mes", fila.get("etiqueta_mes"));
            m.put("categoria", "(sin ventas)");
            for (String medida : new String[]{"pedidos", "unidades", "venta_bruta",
                    "descuentos", "venta_neta", "costo", "margen"}) {
                m.put(medida, 0);
            }
            m.put("margen_pct", null);
            m.put("participacion_pct", null);
            m.put("variacion_pct", null);
            vacios.add(m);
        }
        return vacios;
    }

    // ── Resumen de cabecera ──────────────────────────────────────────────

    /**
     * Los KPI se calculan sobre TODO el conjunto filtrado, no sobre la página.
     * Un total que cambia al pasar de página no es un total.
     */
    private List<Map<String, Object>> kpis(Filtros f) {
        // Alias con prefijo, por el mismo motivo que en sqlItems: nombrar un
        // agregado igual que su columna de origen y volver a usar ese nombre
        // dentro de otro agregado hace que ClickHouse sustituya el alias y
        // falle con ILLEGAL_AGGREGATION.
        String sql = """
            SELECT
                countDistinct(mes)                                  AS n_meses,
                countDistinct(categoria)                            AS n_categorias,
                countDistinct(pedido_id)                            AS n_pedidos,
                sum(cantidad)                                       AS n_unidades,
                sum(venta_neta)                                     AS t_venta_neta,
                sum(descuento_total)                                AS t_descuentos,
                sum(margen)                                         AS t_margen,
                round(toFloat64(sum(margen))
                      / nullIf(toFloat64(sum(venta_neta)), 0) * 100, 2)  AS t_margen_pct
            FROM %s.%s
            WHERE 1 %s
            """.formatted(DWH, TABLA, f.where());

        Map<String, Object> t = ch.queryForMap(sql, f.args());

        // Las dos lecturas que un gerente busca primero en una serie: quién
        // manda y cuándo se vendió más.
        //
        // Ningún alias puede llamarse como una columna de la tabla. Aquí costó
        // una segunda corrida: `any(d.mes_etiqueta) AS mes` chocaba con la
        // columna `mes` que el WHERE usa para filtrar el rango, y ClickHouse
        // sustituía el alias dentro del WHERE — «Aggregate function any(...) is
        // found in WHERE». Con `etiqueta_mes` no hay nada que resolver.
        String liderSql = """
            SELECT categoria, sum(venta_neta) AS total_venta
            FROM %s.%s WHERE 1 %s
            GROUP BY categoria ORDER BY total_venta DESC LIMIT 1
            """.formatted(DWH, TABLA, f.where());
        String mejorMesSql = """
            SELECT any(if(empty(d.mes_etiqueta),
                          formatDateTime(v.mes, '%%Y-%%m'),
                          d.mes_etiqueta))       AS etiqueta_mes,
                   sum(v.venta_neta)             AS total_venta
            FROM %s.%s v
            LEFT JOIN %s.dim_fecha d ON d.fecha = v.mes
            WHERE 1 %s
            GROUP BY v.mes ORDER BY total_venta DESC LIMIT 1
            """.formatted(DWH, TABLA, DWH, f.where());

        List<Map<String, Object>> lider = ch.queryForList(liderSql, f.args());
        List<Map<String, Object>> mejor = ch.queryForList(mejorMesSql, f.args());

        List<Map<String, Object>> k = new ArrayList<>();
        k.add(kpi("Meses en la serie", t.get("n_meses"), "numero"));
        k.add(kpi("Categorías", t.get("n_categorias"), "numero"));
        k.add(kpi("Pedidos", t.get("n_pedidos"), "numero"));
        k.add(kpi("Unidades", t.get("n_unidades"), "numero"));
        k.add(kpi("Venta neta", t.get("t_venta_neta"), "moneda"));
        k.add(kpi("Descuentos", t.get("t_descuentos"), "moneda"));
        k.add(kpi("Margen", t.get("t_margen"), "moneda"));
        k.add(kpi("Margen sobre venta", valorOCero(t.get("t_margen_pct")), "porcentaje"));
        if (!lider.isEmpty()) {
            k.add(kpi("Categoría líder", lider.get(0).get("categoria"), "texto"));
        }
        if (!mejor.isEmpty()) {
            k.add(kpi("Mejor mes", mejor.get(0).get("etiqueta_mes"), "texto"));
        }
        return k;
    }

    /** Un conjunto vacío devuelve NULL en el porcentaje; la tarjeta muestra 0. */
    private static Object valorOCero(Object v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    // ═════════════════════════════════════════════════════════════════════
    // OTD-VEN-05 — Cuánto compra cada cliente
    // ═════════════════════════════════════════════════════════════════════

    /**
     * El negocio visto desde el CLIENTE: total gastado, número de pedidos y
     * fecha de la última compra.
     *
     * <h3>Grano y fuentes</h3>
     * Una fila por cliente que compró en el período. Sale de
     * {@code fact_pedido} (cabecera: el gasto es del pedido, no de la línea)
     * unida a {@code dim_cliente} para el nombre, el correo y la geografía.
     *
     * <h3>Los tres clientes que no aparecen, y por qué se dicen</h3>
     * El padrón tiene 72 clientes y solo 69 han comprado alguna vez. Este
     * informe se maneja desde el HECHO, así que un cliente sin pedidos no
     * produce fila — es correcto: la pregunta es cuánto compra cada cliente, no
     * quién existe. Para que la ausencia no pase inadvertida, el resumen lleva
     * las dos cifras («Clientes en el padrón» y «Con compra en el período») y
     * su diferencia queda a la vista en la cabecera.
     *
     * <h3>Cancelados</h3>
     * El dinero excluye los pedidos cancelados, igual que en el resto de los
     * informes de ingreso, pero el recuento de cancelados viaja en su propia
     * columna: un cliente que cancela mucho es información comercial, no ruido
     * que convenga esconder.
     *
     * <h3>`segmento` viaja aunque sea constante</h3>
     * La columna muestra 'sin_segmentar' en los 72 clientes y eso es el dato:
     * la segmentación B2B/B2C no existe ni es derivable
     * ({@code DIAGNOSTICO_SEGMENTO_CLIENTE.md}, veredicto de población
     * homogénea). Verla en la tabla evita que alguien la dé por hecha.
     */
    public Map<String, Object> clientes(String desde, String hasta, String canal,
                                        String buscar, int page, int size) {
        String fDesde = fecha(desde, "desde");
        String fHasta = fecha(hasta, "hasta");
        exigirRangoValido(fDesde, fHasta);
        String fCanal = opcion(canal, CANALES, "canal");
        String fBuscar = texto(buscar);

        return ejecutar("OTD-VEN-05", () -> {
            Filtros f = new Filtros();
            // El rango se compara sobre `toDate(fecha_pedido)` y no sobre el
            // DateTime crudo, por dos motivos: el día del negocio queda COMPLETO
            // en los dos extremos (comparar contra un DateTime dejaría fuera todo
            // lo ocurrido tras la medianoche del último día), y `toDate` de una
            // columna `DateTime('America/Guayaquil')` resuelve la fecha en la
            // zona del negocio, no en la del servidor — que es el error de §8.6
            // por el que este proyecto ya pagó una vez.
            f.y("toDate(p.fecha_pedido) >= toDate(?)", fDesde);
            f.y("toDate(p.fecha_pedido) <= toDate(?)", fHasta);
            f.y("p.canal = ?", fCanal);
            f.y("positionCaseInsensitive(concat(c.nombre_completo, ' ', c.email), ?) > 0",
                    fBuscar);

            Map<String, Object> sobre = paginarCh(
                    sqlClientes(f.where()), contarSobre(agregadoCliente(f.where())),
                    f.args(), page, size);
            conResumen(sobre, kpisClientes(f));
            return conMarcaDeAgua(sobre, TABLA_PEDIDO);
        });
    }

    private static String agregadoCliente(String where) {
        return """
            SELECT
                p.cliente_id                                   AS id_cliente,
                c.nombre_completo                              AS nombre_cliente,
                c.email                                        AS correo,
                c.ciudad                                       AS ciudad_cliente,
                c.provincia                                    AS provincia_cliente,
                c.segmento                                     AS segmento_cliente,
                countIf(p.es_cancelado = 0)                    AS n_pedidos,
                countIf(p.es_cancelado = 1)                    AS n_cancelados,
                sumIf(p.unidades, p.es_cancelado = 0)          AS n_unidades,
                sumIf(p.lineas, p.es_cancelado = 0)            AS n_lineas,
                sumIf(p.total, p.es_cancelado = 0)             AS t_monto,
                sumIf(p.monto_cupon, p.es_cancelado = 0)       AS t_cupon,
                maxIf(p.fecha_pedido, p.es_cancelado = 0)      AS f_ultima,
                minIf(p.fecha_pedido, p.es_cancelado = 0)      AS f_primera
            FROM %s.%s p
            LEFT JOIN %s c ON c.cliente_id = p.cliente_id
            WHERE 1 %s
            GROUP BY p.cliente_id, c.nombre_completo, c.email,
                     c.ciudad, c.provincia, c.segmento
            """.formatted(DWH, TABLA_PEDIDO, dimension(TABLA_CLIENTE), where);
    }

    private static String sqlClientes(String where) {
        // Las fechas viajan ya FORMATEADAS como texto. Es la regla vigente desde
        // PATRON_INFORMES.md §11: el formateador del frontend interpreta una
        // fecha serializada como UTC y puede mostrarla corrida un día. Un
        // informe cuya columna «última compra» miente por un día es peor que
        // uno que no la trae.
        return """
            SELECT
                nombre_cliente    AS cliente,
                correo            AS email,
                ciudad_cliente    AS ciudad,
                provincia_cliente AS provincia,
                segmento_cliente  AS segmento,
                n_pedidos         AS pedidos,
                n_cancelados      AS cancelados,
                n_unidades        AS unidades,
                n_lineas          AS lineas,
                t_monto           AS monto_total,
                t_cupon           AS descuento_cupon,
                round(toFloat64(t_monto) / nullIf(n_pedidos, 0), 2)  AS ticket_promedio,
                round(toFloat64(t_monto)
                      / nullIf(toFloat64(sum(t_monto) OVER ()), 0) * 100, 2)
                                                                     AS participacion_pct,
                if(n_pedidos = 0, '—', formatDateTime(f_primera, '%%d/%%m/%%Y'))
                                                                     AS primera_compra,
                if(n_pedidos = 0, '—', formatDateTime(f_ultima, '%%d/%%m/%%Y'))
                                                                     AS ultima_compra,
                if(n_pedidos = 0, NULL,
                   dateDiff('day', f_ultima, now('America/Guayaquil')))
                                                                     AS dias_sin_comprar
            FROM (%s)
            ORDER BY t_monto DESC, n_pedidos DESC
            """.formatted(agregadoCliente(where));
    }

    private List<Map<String, Object>> kpisClientes(Filtros f) {
        Map<String, Object> t = ch.queryForMap("""
            SELECT countDistinct(p.cliente_id)               AS n_clientes,
                   countIf(p.es_cancelado = 0)               AS n_pedidos,
                   sumIf(p.total, p.es_cancelado = 0)        AS t_monto,
                   round(toFloat64(sumIf(p.total, p.es_cancelado = 0))
                         / nullIf(countIf(p.es_cancelado = 0), 0), 2) AS t_ticket
            FROM %s.%s p
            LEFT JOIN %s c ON c.cliente_id = p.cliente_id
            WHERE 1 %s
            """.formatted(DWH, TABLA_PEDIDO, dimension(TABLA_CLIENTE), f.where()),
                f.args());

        // El padrón completo NO lleva los filtros del informe: es el universo
        // contra el que se lee la cobertura. Filtrarlo daría siempre 100 %.
        Integer padron = ch.queryForObject(
                "SELECT count() FROM " + dimension(TABLA_CLIENTE), Integer.class);

        List<Map<String, Object>> lider = ch.queryForList("""
            SELECT c.nombre_completo AS nombre_cliente,
                   sumIf(p.total, p.es_cancelado = 0) AS t_monto
            FROM %s.%s p
            LEFT JOIN %s c ON c.cliente_id = p.cliente_id
            WHERE 1 %s
            GROUP BY c.nombre_completo ORDER BY t_monto DESC LIMIT 1
            """.formatted(DWH, TABLA_PEDIDO, dimension(TABLA_CLIENTE), f.where()),
                f.args());

        List<Map<String, Object>> k = new ArrayList<>();
        k.add(kpi("Clientes en el padrón", padron, "numero"));
        k.add(kpi("Con compra en el período", t.get("n_clientes"), "numero"));
        k.add(kpi("Pedidos", t.get("n_pedidos"), "numero"));
        k.add(kpi("Monto comprado", t.get("t_monto"), "moneda"));
        k.add(kpi("Ticket promedio", valorOCero(t.get("t_ticket")), "moneda"));
        if (!lider.isEmpty() && lider.get(0).get("nombre_cliente") != null) {
            k.add(kpi("Mejor cliente", lider.get(0).get("nombre_cliente"), "texto"));
        }
        return k;
    }

    // ═════════════════════════════════════════════════════════════════════
    // OTD-VEN-07 — Valor promedio del pedido, por período y por canal
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Ticket promedio mes a mes y por canal.
     *
     * <h3>Por qué la mediana va al lado del promedio</h3>
     * El promedio de un ticket lo mueve un pedido grande; la mediana no. Con
     * las dos en la misma fila se ve de un vistazo si el mes subió porque se
     * vendió más caro en general o porque entró un pedido excepcional. Un
     * informe de «valor promedio» que solo trae el promedio invita justo a la
     * lectura equivocada.
     *
     * <h3>Qué se excluye</h3>
     * Los pedidos cancelados: un pedido que no llegó a ser venta no tiene
     * ticket.
     *
     * <h3>Grano de la medida</h3>
     * `total` es una medida de CABECERA y por eso este informe se sirve de
     * `fact_pedido` y no de `fact_venta_linea`: promediar el total del pedido
     * sobre las líneas lo contaría 2,5 veces (§3 del diseño).
     */
    public Map<String, Object> ticketPromedio(String desde, String hasta, String canal,
                                              int page, int size) {
        String fDesde = fecha(desde, "desde");
        String fHasta = fecha(hasta, "hasta");
        exigirRangoValido(fDesde, fHasta);
        String fCanal = opcion(canal, CANALES, "canal");

        return ejecutar("OTD-VEN-07", () -> {
            Filtros f = filtrosMensuales(fDesde, fHasta, fCanal);
            Map<String, Object> sobre = paginarCh(
                    sqlTicket(f.where()), contarSobre(agregadoTicket(f.where())),
                    f.args(), page, size);
            conResumen(sobre, kpisTicket(f));
            return conMarcaDeAgua(sobre, TABLA_PEDIDO);
        });
    }

    /**
     * Filtros comunes de los informes mensuales sobre {@code fact_pedido}:
     * cancelados fuera y rango ajustado a MESES COMPLETOS.
     *
     * El rango se compara contra `mes` —que el ETL ya calculó en la zona del
     * negocio— y no contra `fecha_pedido`: en una serie mensual, medio mes se
     * dibuja como una caída de la venta que no ocurrió.
     */
    private static Filtros filtrosMensuales(String desde, String hasta, String canal) {
        Filtros f = new Filtros();
        f.y("es_cancelado = 0");
        f.y("mes >= toStartOfMonth(toDate(?))", desde);
        f.y("mes <= toStartOfMonth(toDate(?))", hasta);
        f.y("canal = ?", canal);
        return f;
    }

    private static String agregadoTicket(String where) {
        return """
            SELECT
                mes                                            AS mes_dato,
                canal                                          AS canal_dato,
                count()                                        AS n_pedidos,
                sum(unidades)                                  AS n_unidades,
                sum(lineas)                                    AS n_lineas,
                sum(total)                                     AS t_monto,
                min(total)                                     AS t_minimo,
                max(total)                                     AS t_maximo,
                quantileExact(0.5)(total)                      AS t_mediana
            FROM %s.%s
            WHERE 1 %s
            GROUP BY mes, canal
            """.formatted(DWH, TABLA_PEDIDO, where);
    }

    private static String sqlTicket(String where) {
        return """
            SELECT
                formatDateTime(mes_dato, '%%Y-%%m')            AS mes,
                canal_dato                                     AS canal,
                n_pedidos                                      AS pedidos,
                n_unidades                                     AS unidades,
                t_monto                                        AS monto_total,
                round(toFloat64(t_monto) / nullIf(n_pedidos, 0), 2)   AS ticket_promedio,
                t_mediana                                      AS ticket_mediana,
                t_minimo                                       AS ticket_minimo,
                t_maximo                                       AS ticket_maximo,
                round(toFloat64(n_unidades) / nullIf(n_pedidos, 0), 2) AS unidades_por_pedido,
                round(toFloat64(n_lineas) / nullIf(n_pedidos, 0), 2)   AS lineas_por_pedido,
                round((toFloat64(t_monto) / nullIf(n_pedidos, 0)
                       - toFloat64(t_anterior))
                      / nullIf(toFloat64(t_anterior), 0) * 100, 2)     AS variacion_pct
            FROM (
                SELECT *,
                    -- El ticket del mismo canal en SU mes anterior. Comparar
                    -- contra la fila previa sin particionar por canal mezclaría
                    -- el ticket de mostrador con el de la web.
                    lagInFrame(toFloat64(t_monto) / nullIf(n_pedidos, 0)) OVER (
                        PARTITION BY canal_dato ORDER BY mes_dato
                        ROWS BETWEEN 1 PRECEDING AND 1 PRECEDING
                    ) AS t_anterior
                FROM (%s)
            )
            ORDER BY mes_dato DESC, t_monto DESC
            """.formatted(agregadoTicket(where));
    }

    private List<Map<String, Object>> kpisTicket(Filtros f) {
        Map<String, Object> t = ch.queryForMap("""
            SELECT count()                                     AS n_pedidos,
                   countDistinct(mes)                          AS n_meses,
                   sum(total)                                  AS t_monto,
                   round(toFloat64(sum(total)) / nullIf(count(), 0), 2) AS t_ticket,
                   quantileExact(0.5)(total)                   AS t_mediana,
                   max(total)                                  AS t_maximo,
                   round(toFloat64(sum(unidades)) / nullIf(count(), 0), 2) AS t_unidades
            FROM %s.%s WHERE 1 %s
            """.formatted(DWH, TABLA_PEDIDO, f.where()), f.args());

        List<Map<String, Object>> lider = ch.queryForList("""
            SELECT canal AS canal_dato,
                   round(toFloat64(sum(total)) / nullIf(count(), 0), 2) AS t_ticket
            FROM %s.%s WHERE 1 %s
            GROUP BY canal ORDER BY t_ticket DESC LIMIT 1
            """.formatted(DWH, TABLA_PEDIDO, f.where()), f.args());

        List<Map<String, Object>> k = new ArrayList<>();
        k.add(kpi("Meses en la serie", t.get("n_meses"), "numero"));
        k.add(kpi("Pedidos", t.get("n_pedidos"), "numero"));
        k.add(kpi("Monto vendido", t.get("t_monto"), "moneda"));
        k.add(kpi("Ticket promedio", valorOCero(t.get("t_ticket")), "moneda"));
        k.add(kpi("Ticket mediano", valorOCero(t.get("t_mediana")), "moneda"));
        k.add(kpi("Pedido mayor", valorOCero(t.get("t_maximo")), "moneda"));
        k.add(kpi("Unidades por pedido", valorOCero(t.get("t_unidades")), "numero"));
        if (!lider.isEmpty()) {
            k.add(kpi("Canal de mayor ticket",
                    CANAL_LEGIBLE.getOrDefault(String.valueOf(lider.get(0).get("canal_dato")),
                            String.valueOf(lider.get(0).get("canal_dato"))), "texto"));
        }
        return k;
    }

    /** Etiqueta de negocio del canal, para las tarjetas de resumen. */
    private static final Map<String, String> CANAL_LEGIBLE = Map.of(
            "web", "Tienda en línea", "tienda", "Mostrador", "telefono", "Teléfono");

    // ═════════════════════════════════════════════════════════════════════
    // OTD-VEN-13 — Evolución mensual de la participación de cada canal
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Cómo cambia mes a mes el peso de cada canal en la venta.
     *
     * <h3>Par temporal de OTD-VEN-16</h3>
     * VEN-16 (SIMPLE, PostgreSQL) es la FOTO del período; éste es la PELÍCULA.
     * Misma pregunta de negocio, distinto recorrido: es el barrido de 19 meses
     * lo que lo hace compuesto y lo que lo saca de una consulta directa.
     *
     * <h3>Dos participaciones, no una</h3>
     * La cuota se mide sobre PEDIDOS y sobre MONTO, y casi nunca coinciden: un
     * canal puede poner el 54 % de los pedidos y el 53 % del dinero porque su
     * ticket es menor. Publicar solo una de las dos deja la mitad de la
     * respuesta fuera.
     *
     * <h3>Lo que este informe NO mide</h3>
     * `canal` es el MEDIO por el que entró el pedido, jamás el tipo de cliente.
     * La segmentación B2B/B2C no existe en el sistema y no se deduce de aquí
     * ({@code DIAGNOSTICO_SEGMENTO_CLIENTE.md}).
     */
    public Map<String, Object> evolucionCanal(String desde, String hasta, String canal,
                                              int page, int size) {
        String fDesde = fecha(desde, "desde");
        String fHasta = fecha(hasta, "hasta");
        exigirRangoValido(fDesde, fHasta);
        String fCanal = opcion(canal, CANALES, "canal");

        return ejecutar("OTD-VEN-13", () -> {
            // El filtro de canal NO entra en el agregado: la participación se
            // calcula contra el total del mes, y filtrando un canal el
            // porcentaje saldría siempre 100 %. Se aplica DESPUÉS, sobre el
            // resultado ya repartido.
            Filtros base = filtrosMensuales(fDesde, fHasta, null);
            Filtros vista = new Filtros();
            vista.y("canal_dato = ?", fCanal);

            Object[] args = base.argsCon(vista.args());
            Map<String, Object> sobre = paginarCh(
                    sqlCanal(base.where(), vista.where()),
                    contarSobre(sqlCanal(base.where(), vista.where())),
                    args, page, size);
            conResumen(sobre, kpisCanal(base, fCanal));
            return conMarcaDeAgua(sobre, TABLA_PEDIDO);
        });
    }

    private static String agregadoCanal(String where) {
        return """
            SELECT
                mes                    AS mes_dato,
                canal                  AS canal_dato,
                count()                AS n_pedidos,
                sum(unidades)          AS n_unidades,
                sum(total)             AS t_monto,
                countDistinct(cliente_id) AS n_clientes
            FROM %s.%s
            WHERE 1 %s
            GROUP BY mes, canal
            """.formatted(DWH, TABLA_PEDIDO, where);
    }

    private static String sqlCanal(String where, String whereVista) {
        return """
            SELECT
                formatDateTime(mes_dato, '%%Y-%%m')  AS mes,
                canal_dato                           AS canal,
                n_pedidos                            AS pedidos,
                n_unidades                           AS unidades,
                n_clientes                           AS clientes,
                t_monto                              AS monto,
                round(toFloat64(t_monto) / nullIf(n_pedidos, 0), 2) AS ticket_promedio,
                round(toFloat64(n_pedidos)
                      / nullIf(toFloat64(sum(n_pedidos) OVER (PARTITION BY mes_dato)), 0)
                      * 100, 2)                      AS participacion_pedidos_pct,
                round(toFloat64(t_monto)
                      / nullIf(toFloat64(sum(t_monto) OVER (PARTITION BY mes_dato)), 0)
                      * 100, 2)                      AS participacion_monto_pct,
                round((toFloat64(t_monto) - toFloat64(t_anterior))
                      / nullIf(toFloat64(t_anterior), 0) * 100, 2) AS variacion_pct
            FROM (
                SELECT *,
                    lagInFrame(t_monto) OVER (
                        PARTITION BY canal_dato ORDER BY mes_dato
                        ROWS BETWEEN 1 PRECEDING AND 1 PRECEDING
                    ) AS t_anterior
                FROM (%s)
            )
            WHERE 1 %s
            ORDER BY mes_dato DESC, t_monto DESC
            """.formatted(agregadoCanal(where), whereVista);
    }

    private List<Map<String, Object>> kpisCanal(Filtros base, String canal) {
        List<Map<String, Object>> reparto = ch.queryForList("""
            SELECT canal AS canal_dato, count() AS n_pedidos, sum(total) AS t_monto,
                   round(toFloat64(sum(total))
                         / nullIf(toFloat64(sum(sum(total)) OVER ()), 0) * 100, 2)
                                                    AS cuota_pct
            FROM %s.%s WHERE 1 %s
            GROUP BY canal ORDER BY t_monto DESC
            """.formatted(DWH, TABLA_PEDIDO, base.where()), base.args());

        Map<String, Object> t = ch.queryForMap("""
            SELECT countDistinct(mes) AS n_meses, count() AS n_pedidos,
                   sum(total) AS t_monto
            FROM %s.%s WHERE 1 %s
            """.formatted(DWH, TABLA_PEDIDO, base.where()), base.args());

        List<Map<String, Object>> k = new ArrayList<>();
        k.add(kpi("Meses en la serie", t.get("n_meses"), "numero"));
        k.add(kpi("Pedidos", t.get("n_pedidos"), "numero"));
        k.add(kpi("Monto vendido", t.get("t_monto"), "moneda"));
        // La cuota de CADA canal en el período completo: es el marco contra el
        // que se leen los altibajos mensuales de la tabla.
        for (Map<String, Object> fila : reparto) {
            String codigo = String.valueOf(fila.get("canal_dato"));
            k.add(kpi(CANAL_LEGIBLE.getOrDefault(codigo, codigo),
                    fila.get("cuota_pct"), "porcentaje"));
        }
        if (canal != null) {
            k.add(kpi("Filtrado por canal", CANAL_LEGIBLE.getOrDefault(canal, canal), "texto"));
        }
        return k;
    }

    // ═════════════════════════════════════════════════════════════════════
    // OTD-VEN-09 — Mezcla de formas de cobro y su evolución
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Con qué se cobra —efectivo, tarjeta, transferencia— y cómo cambia esa
     * mezcla mes a mes.
     *
     * <h3>Grano: el COBRO, no el pedido</h3>
     * Se sirve de {@code fact_flujo_caja} con {@code sentido='ingreso'} porque
     * la forma de pago es un atributo del COBRO y no del pedido: hay pedidos
     * con dos cobros (abonos parciales), y contarlos por pedido perdería uno de
     * los dos métodos.
     *
     * <h3>Solo lo efectivamente cobrado</h3>
     * {@code estado='completado'}. Los 176 intentos fallidos NO son una forma
     * de cobro —son dinero que no entró— y tienen su propio informe
     * (OTD-VEN-12). Mezclarlos aquí inflaría la mezcla con cobros inexistentes.
     */
    public Map<String, Object> formasCobro(String desde, String hasta, String tipo,
                                           int page, int size) {
        String fDesde = fecha(desde, "desde");
        String fHasta = fecha(hasta, "hasta");
        exigirRangoValido(fDesde, fHasta);
        String fTipo = opcion(tipo, TIPOS_COBRO, "tipo");

        return ejecutar("OTD-VEN-09", () -> {
            Filtros base = new Filtros();
            base.y("sentido = 'ingreso'");
            base.y("estado = 'completado'");
            base.y("mes >= toStartOfMonth(toDate(?))", fDesde);
            base.y("mes <= toStartOfMonth(toDate(?))", fHasta);

            // Igual que en VEN-13: el filtro de forma de pago se aplica DESPUÉS
            // del reparto, o la participación de la única forma visible saldría
            // siempre 100 %.
            Filtros vista = new Filtros();
            vista.y("tipo_dato = ?", fTipo);

            Object[] args = base.argsCon(vista.args());
            String sql = sqlFormasCobro(base.where(), vista.where());
            Map<String, Object> sobre = paginarCh(sql, contarSobre(sql), args, page, size);
            conResumen(sobre, kpisFormasCobro(base, fTipo));
            return conMarcaDeAgua(sobre, TABLA_CAJA);
        });
    }

    /** Tipos de forma de pago del catálogo (`metodo_pago.tipo`). */
    private static final java.util.Set<String> TIPOS_COBRO =
            java.util.Set.of("efectivo", "tarjeta", "transferencia");

    private static String sqlFormasCobro(String where, String whereVista) {
        return """
            SELECT
                formatDateTime(mes_dato, '%%Y-%%m')   AS mes,
                tipo_dato                             AS forma_cobro,
                metodo_dato                           AS metodo,
                n_cobros                              AS cobros,
                t_monto                               AS monto,
                round(toFloat64(t_monto) / nullIf(n_cobros, 0), 2) AS cobro_promedio,
                round(toFloat64(t_monto)
                      / nullIf(toFloat64(sum(t_monto) OVER (PARTITION BY mes_dato)), 0)
                      * 100, 2)                       AS participacion_pct,
                round((toFloat64(t_monto) - toFloat64(t_anterior))
                      / nullIf(toFloat64(t_anterior), 0) * 100, 2) AS variacion_pct
            FROM (
                SELECT *,
                    lagInFrame(t_monto) OVER (
                        PARTITION BY tipo_dato ORDER BY mes_dato
                        ROWS BETWEEN 1 PRECEDING AND 1 PRECEDING
                    ) AS t_anterior
                FROM (
                    SELECT
                        mes              AS mes_dato,
                        metodo_pago_tipo AS tipo_dato,
                        metodo_pago      AS metodo_dato,
                        count()          AS n_cobros,
                        sum(monto)       AS t_monto
                    FROM %s.%s
                    WHERE 1 %s
                    GROUP BY mes, metodo_pago_tipo, metodo_pago
                )
            )
            WHERE 1 %s
            ORDER BY mes_dato DESC, t_monto DESC
            """.formatted(DWH, TABLA_CAJA, where, whereVista);
    }

    private List<Map<String, Object>> kpisFormasCobro(Filtros base, String tipo) {
        Map<String, Object> t = ch.queryForMap("""
            SELECT count() AS n_cobros, countDistinct(mes) AS n_meses,
                   sum(monto) AS t_monto,
                   round(toFloat64(sum(monto)) / nullIf(count(), 0), 2) AS t_promedio
            FROM %s.%s WHERE 1 %s
            """.formatted(DWH, TABLA_CAJA, base.where()), base.args());

        List<Map<String, Object>> reparto = ch.queryForList("""
            SELECT metodo_pago_tipo AS tipo_dato, sum(monto) AS t_monto,
                   round(toFloat64(sum(monto))
                         / nullIf(toFloat64(sum(sum(monto)) OVER ()), 0) * 100, 2)
                                                  AS cuota_pct
            FROM %s.%s WHERE 1 %s
            GROUP BY metodo_pago_tipo ORDER BY t_monto DESC
            """.formatted(DWH, TABLA_CAJA, base.where()), base.args());

        List<Map<String, Object>> k = new ArrayList<>();
        k.add(kpi("Meses en la serie", t.get("n_meses"), "numero"));
        k.add(kpi("Cobros", t.get("n_cobros"), "numero"));
        k.add(kpi("Total cobrado", t.get("t_monto"), "moneda"));
        k.add(kpi("Cobro promedio", valorOCero(t.get("t_promedio")), "moneda"));
        for (Map<String, Object> fila : reparto) {
            String nombre = String.valueOf(fila.get("tipo_dato"));
            k.add(kpi(nombre.substring(0, 1).toUpperCase() + nombre.substring(1),
                    fila.get("cuota_pct"), "porcentaje"));
        }
        if (tipo != null) {
            k.add(kpi("Filtrado por forma", tipo, "texto"));
        }
        return k;
    }

    // ═════════════════════════════════════════════════════════════════════
    // OTD-VEN-12 — Cobros en línea fallidos y su motivo
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Los intentos de cobro rechazados, por motivo y por período: cuánta venta
     * se está perdiendo en el paso del pago.
     *
     * <h3>Los motivos llegan ya NORMALIZADOS — y por qué importa</h3>
     * En PostgreSQL conviven SEIS valores distintos donde el negocio tiene
     * CINCO: el código {@code tarjeta_rechazada} y su texto libre «Tarjeta
     * rechazada por el emisor» son el mismo rechazo escrito de dos maneras. El
     * ETL aplica el mapa (§5.3 del diseño) antes de cargar, así que aquí
     * `tarjeta_rechazada` ya suma sus 39 intentos en UNA fila. Sin esa
     * normalización este informe mostraría dos filas del mismo motivo y el
     * segundo motivo más frecuente parecería el cuarto.
     *
     * <h3>La fecha de un cobro fallido es la del INTENTO</h3>
     * Un cobro rechazado no tiene fecha de liquidación —`pago.fecha_pago` va
     * NULL en los 176—, así que el período de la fila es el del intento
     * (`fecha_creacion`), marcado en el almacén con `fecha_es_intento = 1`. Es
     * la única fecha que existe y es además la que el informe quiere: cuándo se
     * perdió la venta.
     *
     * <h3>El monto NO es dinero perdido definitivamente</h3>
     * Es el importe que se intentó cobrar. Parte pudo reintentarse con éxito
     * después; el sistema no encadena intento y reintento, y por eso la columna
     * se rotula «monto no cobrado en el intento» y no «venta perdida».
     */
    public Map<String, Object> cobrosFallidos(String desde, String hasta, String motivo,
                                              int page, int size) {
        String fDesde = fecha(desde, "desde");
        String fHasta = fecha(hasta, "hasta");
        exigirRangoValido(fDesde, fHasta);
        String fMotivo = opcion(motivo, MOTIVOS_FALLO, "motivo");

        return ejecutar("OTD-VEN-12", () -> {
            Filtros f = new Filtros();
            f.y("sentido = 'ingreso'");
            f.y("estado = 'fallido'");
            f.y("mes >= toStartOfMonth(toDate(?))", fDesde);
            f.y("mes <= toStartOfMonth(toDate(?))", fHasta);
            f.y("motivo_fallo = ?", fMotivo);

            String sql = sqlFallidos(f.where());
            Map<String, Object> sobre = paginarCh(sql, contarSobre(sql), f.args(), page, size);
            conResumen(sobre, kpisFallidos(f, fDesde, fHasta));
            return conMarcaDeAgua(sobre, TABLA_CAJA);
        });
    }

    /**
     * Lista blanca de motivos: los cinco del negocio más el {@code 'otro'} de
     * la regla de escape del ETL. `otro` se admite como filtro a propósito — si
     * algún día aparece un motivo no previsto, hay que poder aislarlo desde la
     * pantalla en vez de tener que abrir el log del pipeline.
     */
    private static final java.util.Set<String> MOTIVOS_FALLO = java.util.Set.of(
            "fondos_insuficientes", "datos_incorrectos", "tarjeta_rechazada",
            "error_pasarela", "limite_excedido", "otro");

    private static String sqlFallidos(String where) {
        return """
            SELECT
                formatDateTime(mes_dato, '%%Y-%%m')  AS mes,
                motivo_dato                          AS motivo,
                n_intentos                           AS intentos,
                t_monto                              AS monto_intentado,
                round(toFloat64(t_monto) / nullIf(n_intentos, 0), 2) AS intento_promedio,
                metodo_dato                          AS metodo_predominante,
                round(toFloat64(n_intentos)
                      / nullIf(toFloat64(sum(n_intentos) OVER (PARTITION BY mes_dato)), 0)
                      * 100, 2)                      AS participacion_mes_pct
            FROM (
                SELECT
                    mes             AS mes_dato,
                    motivo_fallo    AS motivo_dato,
                    count()         AS n_intentos,
                    sum(monto)      AS t_monto,
                    -- El método más repetido del grupo. `topK(1)` devuelve un
                    -- array; se toma su primer elemento con respaldo vacío para
                    -- que un grupo raro no rompa la fila.
                    arrayElement(arrayConcat(topK(1)(toString(metodo_pago)), ['—']), 1)
                                    AS metodo_dato
                FROM %s.%s
                WHERE 1 %s
                GROUP BY mes, motivo_fallo
            )
            ORDER BY mes_dato DESC, n_intentos DESC
            """.formatted(DWH, TABLA_CAJA, where);
    }

    private List<Map<String, Object>> kpisFallidos(Filtros f, String desde, String hasta) {
        Map<String, Object> t = ch.queryForMap("""
            SELECT count() AS n_intentos, sum(monto) AS t_monto,
                   countDistinct(motivo_fallo) AS n_motivos,
                   countDistinct(mes) AS n_meses
            FROM %s.%s WHERE 1 %s
            """.formatted(DWH, TABLA_CAJA, f.where()), f.args());

        List<Map<String, Object>> lider = ch.queryForList("""
            SELECT motivo_fallo AS motivo_dato, count() AS n_intentos
            FROM %s.%s WHERE 1 %s
            GROUP BY motivo_fallo ORDER BY n_intentos DESC LIMIT 1
            """.formatted(DWH, TABLA_CAJA, f.where()), f.args());

        // La TASA de fallo necesita el denominador —los cobros que sí entraron—
        // y por eso no puede salir del mismo conjunto filtrado: se recalcula
        // sobre el mismo rango de meses, sin el filtro de estado ni el de
        // motivo. Un número de intentos fallidos sin su tasa no dice si el
        // problema es grande o marginal.
        Filtros universo = new Filtros();
        universo.y("sentido = 'ingreso'");
        universo.y("mes >= toStartOfMonth(toDate(?))", desde);
        universo.y("mes <= toStartOfMonth(toDate(?))", hasta);
        Map<String, Object> u = ch.queryForMap("""
            SELECT count() AS n_todos, countIf(estado = 'fallido') AS n_fallidos,
                   round(toFloat64(countIf(estado = 'fallido'))
                         / nullIf(toFloat64(count()), 0) * 100, 2) AS t_tasa
            FROM %s.%s WHERE 1 %s
            """.formatted(DWH, TABLA_CAJA, universo.where()), universo.args());

        List<Map<String, Object>> k = new ArrayList<>();
        k.add(kpi("Intentos fallidos", t.get("n_intentos"), "numero"));
        k.add(kpi("Monto no cobrado", t.get("t_monto"), "moneda"));
        k.add(kpi("Motivos distintos", t.get("n_motivos"), "numero"));
        k.add(kpi("Meses con rechazos", t.get("n_meses"), "numero"));
        k.add(kpi("Intentos de cobro totales", u.get("n_todos"), "numero"));
        k.add(kpi("Tasa de rechazo", valorOCero(u.get("t_tasa")), "porcentaje"));
        if (!lider.isEmpty()) {
            String codigo = String.valueOf(lider.get(0).get("motivo_dato"));
            k.add(kpi("Motivo más frecuente",
                    MOTIVO_LEGIBLE.getOrDefault(codigo, codigo), "texto"));
        }
        return k;
    }

    /**
     * Etiqueta de negocio de cada motivo, para las tarjetas de resumen.
     *
     * La tabla la resuelve el archivo de definiciones del frontend, pero un KPI
     * es texto plano y llegaría con el código crudo: «fondos_insuficientes» en
     * una tarjeta de dirección se lee como un identificador de sistema, no como
     * una respuesta.
     */
    private static final Map<String, String> MOTIVO_LEGIBLE = Map.of(
            "fondos_insuficientes", "Fondos insuficientes",
            "datos_incorrectos",    "Datos de la tarjeta incorrectos",
            "tarjeta_rechazada",    "Tarjeta rechazada por el emisor",
            "error_pasarela",       "Error de la pasarela",
            "limite_excedido",      "Límite de la tarjeta excedido",
            "otro",                 "Otro motivo (no previsto)");

    // ── Utilidades comunes de este servicio ──────────────────────────────

    /** Tablas del DWH que sirven estos informes. */
    private static final String TABLA_PEDIDO  = "fact_pedido";
    private static final String TABLA_CAJA    = "fact_flujo_caja";
    private static final String TABLA_CLIENTE = "dim_cliente";
    private static final String TABLA_RESENA  = "fact_resena";
    private static final String TABLA_DEV     = "fact_devolucion";

    // ═════════════════════════════════════════════════════════════════════
    // OTD-VEN-11 — Calificación de cada producto y su evolución (Fase 4)
    // ═════════════════════════════════════════════════════════════════════

    /** Los 3 estados de moderación de una reseña. */
    private static final java.util.Set<String> ESTADOS_RESENA =
            java.util.Set.of("pendiente", "aprobada", "rechazada");

    /** Ejes del informe de reseñas. `producto` es la pregunta principal. */
    private static final java.util.Set<String> EJES_RESENA =
            java.util.Set.of("producto", "mes", "categoria", "marca");

    /**
     * Lo que los clientes puntúan, y cómo se mueve en el tiempo.
     *
     * <h3>El grano es el PRODUCTO PADRE, y eso condiciona todo el informe</h3>
     * {@code fact_resena} es la ÚNICA tabla del modelo cuyo grano de producto
     * es el padre y no la variante: {@code resena.producto_id} apunta al
     * producto. Por eso este SQL <b>no une a {@code dim_producto}</b> — esa
     * dimensión tiene grano de variante y unir por {@code producto_id}
     * devolvería una fila por variante, inflando en silencio el peso de los 7
     * productos multivariante (corrección C4.4). Los atributos del producto
     * viajan denormalizados en la propia tabla de hechos.
     *
     * <h3>«Cómo evoluciona» es un eje, no un informe aparte</h3>
     * {@code agrupar} ∈ {producto (defecto), mes, categoria, marca}. El
     * objetivo pide las dos cosas —el ranking y la evolución— y son el mismo
     * agregado con distinta clave; separarlas en dos endpoints obligaría a
     * mantener dos veces la misma definición de «calificación media».
     *
     * <h3>Qué reseñas cuentan</h3>
     * TODAS por defecto, y el filtro {@code estado} deja verlas por separado.
     * Restringir de oficio a las {@code aprobada} sería tentador —son las que
     * el cliente ve en la tienda— pero convertiría el informe en una medida de
     * la moderación y no de la opinión: las 21 rechazadas y las 53 pendientes
     * también son lo que la gente escribió. El resumen declara la partición.
     *
     * @param agrupar eje del agregado; fuera de la lista blanca → 400
     */
    public Map<String, Object> resenasProducto(String desde, String hasta,
                                               String categoria, String estado,
                                               String buscar, String agrupar,
                                               int page, int size) {
        String fDesde  = fecha(desde, "desde");
        String fHasta  = fecha(hasta, "hasta");
        exigirRangoValido(fDesde, fHasta);
        String fEstado = opcion(estado, ESTADOS_RESENA, "estado");
        String eje     = agrupar == null || agrupar.isBlank()
                ? "producto" : opcion(agrupar, EJES_RESENA, "agrupar");

        return ejecutar("OTD-VEN-11", () -> {
            Filtros f = new Filtros();
            f.y("toDate(fecha_creacion) >= toDate(?)", fDesde);
            f.y("toDate(fecha_creacion) <= toDate(?)", fHasta);
            f.y("positionCaseInsensitive(categoria, ?) > 0", texto(categoria));
            f.y("estado = ?", fEstado);
            f.y("positionCaseInsensitive(producto_nombre, ?) > 0", texto(buscar));

            String clave = switch (eje) {
                case "mes"       -> "formatDateTime(mes, '%Y-%m')";
                case "categoria" -> "categoria";
                case "marca"     -> "marca";
                default          -> "producto_nombre";
            };
            // Ordenar por calificación con pocas reseñas pondría arriba al
            // producto con UN cinco. Por eso el orden por defecto es el volumen
            // y cada fila declara sobre cuántas reseñas se calculó su media.
            String orden = "mes".equals(eje) ? "etiqueta ASC" : "resenas DESC, media DESC";

            String sqlItems = """
                SELECT %s                                        AS etiqueta,
                       any(categoria)                            AS categoria,
                       any(marca)                                AS marca,
                       count()                                   AS resenas,
                       round(avg(calificacion), 2)               AS media,
                       countIf(calificacion >= 4)                AS positivas,
                       countIf(calificacion = 3)                 AS neutras,
                       countIf(calificacion <= 2)                AS negativas,
                       round(countIf(calificacion >= 4) * 100.0 / count(), 2) AS pct_positivas,
                       countIf(compra_verificada = 1)            AS verificadas,
                       countIf(estado = 'aprobada')              AS aprobadas,
                       countIf(estado = 'pendiente')             AS pendientes,
                       countIf(estado = 'rechazada')             AS rechazadas,
                       countDistinct(producto_id)                AS productos,
                       countDistinct(cliente_id)                 AS clientes,
                       max(fecha_creacion)                       AS ultima_resena
                FROM %s.%s
                WHERE 1 %s
                GROUP BY etiqueta
                ORDER BY %s
                """.formatted(clave, DWH, TABLA_RESENA, f.where(), orden);

            Map<String, Object> sobre =
                    paginarCh(sqlItems, contarSobre(sqlItems), f.args(), page, size);
            conResumen(sobre, kpisResenas(f, eje));
            return conMarcaDeAgua(sobre, TABLA_RESENA);
        });
    }

    private List<Map<String, Object>> kpisResenas(Filtros f, String eje) {
        Map<String, Object> t = ch.queryForMap("""
            SELECT count()                                     AS n,
                   countDistinct(producto_id)                  AS productos,
                   round(avg(calificacion), 2)                 AS media,
                   round(countIf(calificacion >= 4) * 100.0 / nullIf(count(), 0), 2)
                                                               AS pct_positivas,
                   countIf(calificacion <= 2)                  AS negativas,
                   countIf(compra_verificada = 1)              AS verificadas,
                   countIf(estado = 'pendiente')               AS pendientes,
                   round(avgIf(dias_hasta_moderacion, dias_hasta_moderacion IS NOT NULL), 2)
                                                               AS dias_moderacion
            FROM %s.%s WHERE 1 %s
            """.formatted(DWH, TABLA_RESENA, f.where()), f.args());

        List<Map<String, Object>> k = new ArrayList<>();
        k.add(kpi("Reseñas", t.get("n"), "numero"));
        k.add(kpi("Productos reseñados", t.get("productos"), "numero"));
        k.add(kpi("Calificación media", valorOCero(t.get("media")), "numero"));
        k.add(kpi("Positivas (4-5)", valorOCero(t.get("pct_positivas")), "porcentaje"));
        k.add(kpi("Negativas (1-2)", t.get("negativas"), "numero"));
        k.add(kpi("Con compra verificada", t.get("verificadas"), "numero"));
        k.add(kpi("Pendientes de moderar", t.get("pendientes"), "numero"));
        k.add(kpi("Días hasta moderar", valorOCero(t.get("dias_moderacion")), "numero"));
        k.add(kpi("Eje", ETIQUETA_EJE_RESENA.getOrDefault(eje, eje), "texto"));
        return k;
    }

    private static final Map<String, String> ETIQUETA_EJE_RESENA = Map.of(
            "producto",  "Por producto",
            "mes",       "Evolución mensual",
            "categoria", "Por categoría",
            "marca",     "Por marca");

    // ═════════════════════════════════════════════════════════════════════
    // OTD-VEN-14 — Cuánto devuelven los clientes y qué % de la venta es
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Bases de fecha del porcentaje. NO son intercambiables — ver más abajo.
     */
    private static final java.util.Set<String> BASES_VEN14 =
            java.util.Set.of("devolucion", "pedido");

    /**
     * El valor devuelto mes a mes y su peso sobre la venta.
     *
     * <h3>La decisión que define el informe: contra QUÉ mes se divide</h3>
     * El numerador (lo devuelto) se fecha por la devolución; el denominador
     * (la venta) por el pedido. Una devolución de julio puede corresponder a un
     * pedido de mayo, así que <b>el porcentaje cambia según el mes que se
     * elija</b> y las dos respuestas son defendibles:
     *
     * <ul>
     *   <li>{@code base=devolucion} (defecto) — «cuánto me devolvieron ESTE
     *       mes, sobre lo que vendí ESTE mes». Es la pregunta de control del
     *       gerente y la que recomienda §5.10 del diseño.</li>
     *   <li>{@code base=pedido} — «de lo que vendí en mayo, cuánto acabó
     *       volviendo». Es la pregunta de CALIDAD de una cohorte, y su último
     *       mes siempre parece bueno porque las devoluciones aún no han
     *       ocurrido.</li>
     * </ul>
     *
     * El sobre declara cuál se usó en un KPI y en {@code salvedad}: un
     * porcentaje de devolución sin decir contra qué mes se divide es un número
     * que no se puede reproducir.
     *
     * <h3>El denominador no incluye pedidos cancelados</h3>
     * {@code es_cancelado = 0}, igual que en VEN-06 y GER-02: un pedido
     * cancelado no es venta y meterlo abajo bajaría artificialmente la tasa.
     *
     * <h3>Los dos montos del reembolso viajan separados</h3>
     * {@code monto_total} es lo que el cliente devolvió en mercancía;
     * {@code monto_reembolsado} es el dinero que se le devolvió. No son la
     * misma cifra —$95.693,89 contra $44.695,33— porque una devolución puede
     * estar en curso, rechazada o con parte de la mercancía no apta. Las dos
     * columnas están en la tabla y ninguna se presenta como «la» devolución.
     */
    public Map<String, Object> devolucionesMes(String desde, String hasta,
                                               String estado, String motivo,
                                               String base) {
        String fDesde  = fecha(desde, "desde");
        String fHasta  = fecha(hasta, "hasta");
        exigirRangoValido(fDesde, fHasta);
        String fEstado = opcion(estado, ESTADOS_DEVOLUCION, "estado");
        String fBase   = base == null || base.isBlank()
                ? "devolucion" : opcion(base, BASES_VEN14, "base");
        // La columna de mes del NUMERADOR. Constante del código, nunca texto
        // del usuario: `base` ya pasó por la lista blanca.
        String mesDev = "pedido".equals(fBase) ? "mes_pedido" : "mes";

        return ejecutar("OTD-VEN-14", () -> {
            Filtros f = new Filtros();
            f.y("toDate(fecha_solicitud) >= toDate(?)", fDesde);
            f.y("toDate(fecha_solicitud) <= toDate(?)", fHasta);
            f.y("estado = ?", fEstado);
            f.y("positionCaseInsensitive(motivo, ?) > 0", texto(motivo));

            // Las dos mitades se agregan por separado y se unen por mes con un
            // FULL JOIN: un mes con devoluciones y sin ventas —o al revés— tiene
            // que aparecer igualmente. Con un INNER, el mes desaparecería y la
            // serie tendría un hueco que nadie sabría explicar.
            List<Map<String, Object>> items = ch.queryForList("""
                SELECT formatDateTime(mes, '%%Y-%%m')            AS periodo,
                       devoluciones, unidades, monto_devuelto, monto_reembolsado,
                       reembolsos, pedidos_vendidos, venta,
                       round(monto_devuelto * 100.0 / nullIf(venta, 0), 2)
                                                                 AS pct_sobre_venta,
                       round(monto_reembolsado * 100.0 / nullIf(venta, 0), 2)
                                                                 AS pct_reembolsado,
                       round(devoluciones * 100.0 / nullIf(pedidos_vendidos, 0), 2)
                                                                 AS pct_pedidos
                FROM (
                    -- OJO con los alias: en ClickHouse un agregado NO puede
                    -- llamarse como la columna que agrega (ILLEGAL_AGGREGATION,
                    -- lección de la Fase 1). Por eso las columnas del UNION
                    -- llevan prefijo `n_`/`m_` y solo el nivel de fuera usa el
                    -- nombre que ve la pantalla.
                    SELECT mes,
                           sum(n_dev)        AS devoluciones,
                           sum(n_uds)        AS unidades,
                           sum(m_devuelto)   AS monto_devuelto,
                           sum(m_reembolso)  AS monto_reembolsado,
                           sum(n_reembolsos) AS reembolsos,
                           sum(n_pedidos)    AS pedidos_vendidos,
                           sum(m_venta)      AS venta
                    FROM (
                        SELECT %s AS mes, count() AS n_dev, sum(unidades) AS n_uds,
                               sum(monto_total) AS m_devuelto,
                               sum(monto_reembolsado) AS m_reembolso,
                               countIf(monto_reembolsado > 0) AS n_reembolsos,
                               0 AS n_pedidos, toDecimal64(0, 2) AS m_venta
                        FROM %s.%s WHERE 1 %s
                        GROUP BY mes
                        UNION ALL
                        SELECT mes, 0, 0, toDecimal64(0, 2), toDecimal64(0, 2), 0,
                               count() AS n_pedidos, sum(total) AS m_venta
                        FROM %s.%s WHERE es_cancelado = 0
                        GROUP BY mes
                    )
                    GROUP BY mes
                )
                ORDER BY periodo
                """.formatted(mesDev, DWH, TABLA_DEV, f.where(), DWH, TABLA_PEDIDO),
                    f.args());

            Map<String, Object> sobre = sobre(items);
            conResumen(sobre, kpisDevoluciones(f, fBase));
            sobre.put("salvedad", "devolucion".equals(fBase)
                    ? "El porcentaje divide lo devuelto EN el mes entre lo vendido EN "
                      + "ese mismo mes. Una devolución de julio puede corresponder a un "
                      + "pedido de mayo, así que numerador y denominador NO son la misma "
                      + "población: es la tasa de control del mes, no la calidad de la "
                      + "venta de ese mes. Para eso, cambia la base a «mes del pedido»."
                    : "El porcentaje se fecha por el MES DEL PEDIDO: de lo vendido en "
                      + "cada mes, cuánto acabó volviendo. Los meses más recientes "
                      + "aparecen siempre mejor porque sus devoluciones todavía no han "
                      + "ocurrido — el plazo del RMA es de 30 días desde la entrega.");
            return conMarcaDeAgua(sobre, TABLA_DEV);
        });
    }

    /** Los 9 estados del ciclo RMA (CHECK de {@code devolucion}). */
    private static final java.util.Set<String> ESTADOS_DEVOLUCION =
            java.util.Set.of("solicitada", "en_revision", "aprobada", "rechazada",
                    "en_transito", "recibida", "inspeccionada", "reembolsada", "cerrada");

    private List<Map<String, Object>> kpisDevoluciones(Filtros f, String base) {
        Map<String, Object> d = ch.queryForMap("""
            SELECT count()                        AS devoluciones,
                   sum(monto_total)               AS devuelto,
                   sum(monto_reembolsado)         AS reembolsado,
                   countIf(monto_reembolsado > 0) AS reembolsos,
                   sum(unidades)                  AS unidades,
                   countDistinct(cliente_id)      AS clientes,
                   countIf(es_terminal = 1)       AS terminales
            FROM %s.%s WHERE 1 %s
            """.formatted(DWH, TABLA_DEV, f.where()), f.args());

        Map<String, Object> v = ch.queryForMap("""
            SELECT sum(total) AS venta, count() AS pedidos
            FROM %s.%s WHERE es_cancelado = 0
            """.formatted(DWH, TABLA_PEDIDO));

        BigDecimal devuelto = (BigDecimal) d.get("devuelto");
        BigDecimal venta = (BigDecimal) v.get("venta");
        Object tasa = (venta == null || venta.signum() == 0 || devuelto == null)
                ? BigDecimal.ZERO
                : devuelto.multiply(BigDecimal.valueOf(100))
                          .divide(venta, 2, java.math.RoundingMode.HALF_UP);

        List<Map<String, Object>> k = new ArrayList<>();
        k.add(kpi("Devoluciones", d.get("devoluciones"), "numero"));
        k.add(kpi("Mercancía devuelta", valorOCero(devuelto), "moneda"));
        k.add(kpi("Dinero reembolsado", valorOCero(d.get("reembolsado")), "moneda"));
        k.add(kpi("Reembolsos pagados", d.get("reembolsos"), "numero"));
        k.add(kpi("Unidades devueltas", d.get("unidades"), "numero"));
        k.add(kpi("Clientes que devolvieron", d.get("clientes"), "numero"));
        k.add(kpi("Venta del período", valorOCero(venta), "moneda"));
        k.add(kpi("Tasa de devolución", tasa, "porcentaje"));
        k.add(kpi("Base del porcentaje", "devolucion".equals(base)
                ? "Mes de la devolución" : "Mes del pedido", "texto"));
        return k;
    }

    /**
     * {@code count()} sobre un agregado ya construido.
     *
     * Envolver la MISMA consulta que produce los ítems garantiza que el total
     * del paginador y las filas pintadas no puedan divergir: un `count` escrito
     * aparte se desincroniza en cuanto alguien toca un `GROUP BY`.
     */
    private static String contarSobre(String sql) {
        return "SELECT count() FROM (" + sql + ")";
    }

    // ═════════════════════════════════════════════════════════════════════
    // OTD-VEN-19 — Clientes en riesgo (fase E3 del nivel estratégico, §5.2)
    // ═════════════════════════════════════════════════════════════════════

    private static final String TABLA_ALERTA = "fact_alerta_cliente";

    /** Niveles publicados por el modelo. Lista blanca. */
    private static final java.util.Set<String> NIVELES_ALERTA =
            java.util.Set.of("critica", "atencion", "normal", "sin_muestra");

    /**
     * Estado sintético: los dos niveles que piden una llamada. Se traduce aquí y
     * NUNCA se concatena texto del usuario — mismo criterio que el
     * {@code pendientes} de OTD-SOP-01.
     */
    private static final String NIVEL_EN_ALERTA = "alerta";

    /**
     * Alerta de abandono: qué clientes llevan un silencio inusual **según su
     * propio ritmo de compra**, ordenados por valor en riesgo.
     *
     * <h3>Esto NO es una predicción de abandono, y la pantalla lo dice</h3>
     * §5.2.1 verificó contra los datos que no hay modelo entrenable aquí: no
     * existe etiqueta de abandono, el generador del seed sortea al cliente con
     * peso constante —**nadie abandona nunca**— y la correlación entre el mejor
     * predictor disponible y el resultado real es <b>0,039</b> sobre 5 casos
     * positivos. Lo que se publica es un modelo del PROCESO: supervivencia
     * exponencial con la tasa propia de cada cliente, que no necesita etiquetas
     * y cuya tasa de falsa alarma se conoce de antemano (≈ α = 0,05).
     *
     * <h3>Las cinco reglas de presentación de §5.2.9, y quién cumple cada una</h3>
     * <ol>
     *   <li><b>La medida principal es «veces su intervalo propio»</b> — viaja en
     *       {@code silencio_en_intervalos} y la definición la pone junto al
     *       ritmo. «67 días» no dice nada sin saber si el cliente compra cada
     *       semana o cada trimestre.</li>
     *   <li><b>El <i>sparkline</i> de compras por mes va EN LA FILA</b> —
     *       {@code compras_por_mes} es un array de 12 enteros que la pantalla
     *       dibuja en la celda. Es la defensa contra el artefacto de la rampa:
     *       deja ver de un golpe si la caída es un hueco o una pendiente.</li>
     *   <li><b>La lista se ordena por VALOR EN RIESGO</b>, no por probabilidad.
     *       Un cliente de $500 con probabilidad 0,1 % no es la primera
     *       llamada.</li>
     *   <li><b>El lift y su muestra van en la CABECERA</b> — son los TRES
     *       primeros KPI del resumen, antes que ninguna cifra de negocio. Si el
     *       lift no discrimina, el usuario tiene que verlo antes que la lista.
     *       Ver {@link #kpisRiesgo}.</li>
     *   <li><b>La fecha ancla va en el título</b> — {@code sufijoTitulo}. La
     *       recencia se mide contra {@code max(fecha_pedido)} del almacén y no
     *       contra el reloj; sin la fecha, la pantalla se lee como si fuera de
     *       hoy.</li>
     * </ol>
     *
     * <h3>El recorte del VENDEDOR</h3>
     * §5.2.8 lo pide «con el mismo mecanismo de OTD-VEN-02», que en PostgreSQL
     * es {@code pedido.vendedor_id = <id del JWT>}. En el almacén <b>no existe
     * {@code vendedor_id}</b>: {@code fact_pedido} guarda el NOMBRE. El recorte
     * casa por tanto contra {@code vendedores}, el conjunto de nombres que
     * atendieron al cliente en la ventana, usando el nombre del principal — que
     * se compone igual que en el ETL ({@code nombre + ' ' + apellido}). El ETL
     * valida que esos nombres son únicos, o dos homónimos compartirían cartera.
     */
    public Map<String, Object> clientesEnRiesgo(String nivel, String buscar,
                                                int page, int size) {
        String fNivel = opcionAlerta(nivel);
        String fBuscar = texto(buscar);
        boolean soloPropio = "VENDEDOR".equals(rolActual());
        String cartera = soloPropio ? nombreActual() : null;

        return ejecutar("OTD-VEN-19", () -> {
            Filtros f = new Filtros();
            if (NIVEL_EN_ALERTA.equals(fNivel)) {
                f.y("nivel_alerta IN ('critica', 'atencion')");
            } else if (fNivel != null) {
                f.y("nivel_alerta = ?", fNivel);
            }
            f.y("positionCaseInsensitive(concat(cliente_nombre, ' ', email), ?) > 0",
                    fBuscar);
            // El recorte del vendedor va DESPUÉS de los filtros del usuario y
            // antes de la paginación: es una restricción de visibilidad, no un
            // filtro, y tiene que aplicarse pase lo que pase.
            f.y("has(vendedores, ?)", cartera);

            String sql = sqlRiesgo(f.where());
            Map<String, Object> sobre = paginarCh(
                    sql, contarSobre(sql), f.args(), page, size);

            Map<String, Object> cabecera = ch.queryForMap(
                    "SELECT formatDateTime(max(fecha_ancla), '%d/%m/%Y')   AS ancla, "
                    + "     formatDateTime(max(ventana_inicio), '%d/%m/%Y') AS ventana, "
                    + "     max(meses_ventana) AS meses "
                    + "FROM " + DWH + "." + TABLA_ALERTA);

            conResumen(sobre, kpisRiesgo(f, soloPropio, cartera));
            sobre.put("alcance", soloPropio ? "propio" : "equipo");
            if (soloPropio) {
                sobre.put("avisoAlcance",
                        "Estás viendo únicamente los clientes de TU cartera —aquellos a "
                        + "los que has atendido en la ventana del cálculo—. El resto es "
                        + "atribución de Gerencia. Ojo: el pedido del canal en línea no "
                        + "tiene vendedor y no forma cartera de nadie.");
            }
            sobre.put("sufijoTitulo", "datos al " + cabecera.get("ancla")
                    + " (última compra registrada)");
            sobre.put("salvedad", salvedadRiesgo(cabecera));
            return conMarcaDeAgua(sobre, TABLA_ALERTA);
        });
    }

    /**
     * Lista blanca del filtro, con el estado sintético {@code alerta}.
     *
     * El valor por DEFECTO es diseño y no comodidad: la pantalla arranca en los
     * clientes que piden una llamada, no en los 69. Un informe de alerta que
     * abre mostrando a todo el mundo obliga a buscar la alerta dentro de la
     * lista, que es exactamente lo que la alerta venía a evitar.
     */
    private static String opcionAlerta(String nivel) {
        if (nivel == null || nivel.isBlank()) {
            return NIVEL_EN_ALERTA;
        }
        if ("todos".equals(nivel)) {
            return null;
        }
        if (NIVEL_EN_ALERTA.equals(nivel)) {
            return NIVEL_EN_ALERTA;
        }
        return opcion(nivel, NIVELES_ALERTA, "nivel");
    }

    /**
     * Nombre del usuario autenticado, compuesto EXACTAMENTE como lo compone el
     * ETL al etiquetar {@code fact_pedido.vendedor}
     * ({@code trim(nombre || ' ' || apellido)}). {@code AppUserPrincipal} ya lo
     * trae resuelto desde el login, así que no hace falta tocar PostgreSQL —
     * que además obligaría a abrir una transacción en un servicio que por
     * contrato no la tiene.
     */
    private static String nombreActual() {
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder
                        .getContext().getAuthentication();
        if (auth != null && auth.getPrincipal()
                instanceof com.retailmind.auth.AppUserPrincipal p
                && p.getNombre() != null) {
            return p.getNombre().trim();
        }
        // Sin nombre no se puede recortar, y un recorte que falla ABIERTO
        // enseñaría la cartera entera a un vendedor. Se recorta a nada.
        return " sin-cartera";
    }

    private static String sqlRiesgo(String where) {
        // Concatenación y no `String.formatted()`: el SQL lleva patrones de
        // fecha con por-ciento ('%d/%m/%Y') y el formateador los interpretaría
        // como especificadores suyos, reventando con «Conversion = 'd'». Misma
        // trampa que ya documentó §18 del patrón de informes.
        return "SELECT\n"
            + """
                cliente_nombre                                 AS cliente,
                email,
                ciudad,
                nivel_alerta,
                facturacion_12m,
                valor_en_riesgo,
                percentil_valor,
                intervalo_medio_dias,
                dias_silencio,
                silencio_en_intervalos,
                round(toFloat64(prob_silencio) * 100, 2)       AS prob_pct,
                pedidos_ventana,
                dias_observados,
                compras_por_mes,
                reclamos_abiertos,
                devoluciones_12m,
                activo,
                vendedores
            """
            + ", formatDateTime(fecha_ultima_compra, '%d/%m/%Y') AS ultima_compra"
            + " FROM " + DWH + "." + TABLA_ALERTA
            + " WHERE 1 = 1 " + where
            // Regla 3 de §5.2.9. El desempate por probabilidad es lo que ordena
            // a los `sin_muestra`, cuyo valor en riesgo es 0 por construcción:
            // el modelo no emite juicio sobre ellos y no puede fingir uno.
            + " ORDER BY valor_en_riesgo DESC, prob_silencio ASC, cliente_nombre";
    }

    /**
     * Las tarjetas del encabezado. <b>El veredicto del modelo va primero.</b>
     *
     * Regla 4 de §5.2.9: «el lift y su muestra van en la cabecera, no en una
     * nota. Si el lift es 1,0 el usuario tiene que verlo antes que la lista».
     * Por eso las tres primeras tarjetas son el lift, su muestra y el dictamen
     * de si supera al azar — y solo después vienen las cifras de negocio.
     *
     * La tercera tarjeta es la que el diseño no pidió y sin la cual el lift
     * engaña igual: un lift de 1,99 medido sobre 14 casos positivos tiene un
     * valor p de 0,10, o sea que **no es distinguible del azar**. Publicar el
     * 1,99 solo lo convertiría en un titular.
     */
    private List<Map<String, Object>> kpisRiesgo(Filtros f, boolean soloPropio,
                                                 String cartera) {
        Map<String, Object> v = ch.queryForMap(
                "SELECT max(lift_backtest) AS lift, "
                + "     max(casos_positivos_backtest) AS positivos, "
                + "     max(evaluados_backtest) AS evaluados, "
                + "     max(precision_backtest) AS precision_top, "
                + "     max(tasa_base_backtest) AS tasa_base, "
                + "     max(p_valor_backtest) AS p_valor, "
                + "     max(alerta_alpha) AS alfa "
                + "FROM " + DWH + "." + TABLA_ALERTA);

        // El universo de la cabecera respeta el recorte del vendedor, pero NO el
        // filtro de nivel: «9 en alerta de 69» es la cifra que da sentido al 9, y
        // con el filtro aplicado diría «9 de 9».
        Filtros u = new Filtros();
        u.y("has(vendedores, ?)", cartera);
        Map<String, Object> t = ch.queryForMap(
                "SELECT count() AS clientes, "
                + "     countIf(nivel_alerta IN ('critica', 'atencion')) AS en_alerta, "
                + "     countIf(nivel_alerta = 'critica')      AS criticas, "
                + "     countIf(nivel_alerta = 'sin_muestra')  AS sin_muestra, "
                + "     sumIf(facturacion_12m, nivel_alerta IN ('critica','atencion')) "
                + "                                            AS facturacion_alerta, "
                + "     sumIf(valor_en_riesgo, nivel_alerta IN ('critica','atencion')) "
                + "                                            AS riesgo, "
                + "     maxIf(dias_silencio, nivel_alerta = 'sin_muestra') AS silencio_ciego "
                + "FROM " + DWH + "." + TABLA_ALERTA + " WHERE 1 = 1 " + u.where(),
                u.args());

        BigDecimal lift = decimalDe(v.get("lift"));
        BigDecimal pValor = decimalDe(v.get("p_valor"));
        boolean supera = lift.compareTo(BigDecimal.ONE) > 0
                && pValor.compareTo(new BigDecimal("0.05")) < 0;

        List<Map<String, Object>> k = new ArrayList<>();
        // ── El veredicto, primero ────────────────────────────────────────
        k.add(kpi("Lift sobre el azar",
                lift.toPlainString().replace('.', ',') + "×", "texto"));
        k.add(kpi("Medido sobre",
                v.get("positivos") + " casos positivos de " + v.get("evaluados")
                + " evaluaciones", "texto"));
        k.add(kpi("¿Supera al azar?", (supera ? "Sí" : "NO")
                + " · p = " + pValor.toPlainString().replace('.', ','), "texto"));
        k.add(kpi("Aciertos en el top 10", v.get("precision_top"), "porcentaje"));
        k.add(kpi("Tasa base (dejaron de comprar)", v.get("tasa_base"), "porcentaje"));
        // ── Y después, el negocio ────────────────────────────────────────
        k.add(kpi(soloPropio ? "En alerta en mi cartera" : "Clientes en alerta",
                t.get("en_alerta") + " de " + t.get("clientes"), "texto"));
        k.add(kpi("De ellos, críticos", t.get("criticas"), "numero"));
        k.add(kpi("Facturación 12m en alerta", valorOCero(t.get("facturacion_alerta")),
                "moneda"));
        k.add(kpi("Valor en riesgo", valorOCero(t.get("riesgo")), "moneda"));
        k.add(kpi("Sin muestra para opinar",
                t.get("sin_muestra") + " clientes · el mayor silencio entre ellos, "
                + t.get("silencio_ciego") + " días", "texto"));
        k.add(kpi("Umbral de alerta (α)", decimalDe(v.get("alfa")).toPlainString()
                .replace('.', ','), "texto"));
        return k;
    }

    private static BigDecimal decimalDe(Object valor) {
        if (valor instanceof BigDecimal b) {
            return b;
        }
        return valor == null ? BigDecimal.ZERO : new BigDecimal(valor.toString());
    }

    /**
     * Las CINCO limitaciones de §5.2.10, en pantalla y encima de la tabla.
     *
     * No es documentación trasladada: quien mira esta lista va a llamar a un
     * cliente por lo que ponga aquí. La tercera es la que impide leerla mal —
     * en la validación, la alerta no superó al azar— y la quinta nombra el hueco
     * que el propio método abre: los clientes con más silencio son, por eso
     * mismo, los que se quedan sin muestra.
     */
    private String salvedadRiesgo(Map<String, Object> cabecera) {
        Map<String, Object> v = ch.queryForMap(
                "SELECT max(lift_backtest) AS lift, "
                + "     max(casos_positivos_backtest) AS positivos, "
                + "     max(p_valor_backtest) AS p_valor, "
                + "     countIf(nivel_alerta = 'sin_muestra') AS sin_muestra, "
                + "     count() AS clientes, "
                + "     max(concentracion_maxima) AS concentracion "
                + "FROM " + DWH + "." + TABLA_ALERTA);

        return "1) Esto es una ALERTA DE SILENCIO ESTADÍSTICAMENTE INUSUAL, no una "
            + "predicción de abandono. El sistema no tiene ningún registro de un "
            + "cliente que se despide: no hay baja, no hay contrato, no hay campo. Un "
            + "silencio largo puede ser un viaje. "
            + "2) Se calcula solo sobre los últimos " + cabecera.get("meses")
            + " meses (desde el " + cabecera.get("ventana") + "). Antes, la cartera "
            + "estaba creciendo desde UN SOLO comprador —en enero y febrero de 2025 un "
            + "cliente hizo el 100 % de los pedidos—, y un ritmo calculado con esa "
            + "historia señalaría a los clientes más grandes como los más perdidos. En "
            + "la ventana usada, el mayor comprador de un mes no pasa del "
            + decimalDe(v.get("concentracion")).toPlainString().replace('.', ',')
            + " % de los pedidos. "
            + "3) EN LA VALIDACIÓN, la alerta no supera al azar de forma "
            + "significativa: lift "
            + decimalDe(v.get("lift")).toPlainString().replace('.', ',')
            + " sobre " + v.get("positivos") + " casos positivos, con un valor p de "
            + decimalDe(v.get("p_valor")).toPlainString().replace('.', ',')
            + ". Es una propiedad de LOS DATOS y no del método: en el histórico "
            + "disponible las compras de cada cliente ocurren a ritmo constante y "
            + "nadie abandona nunca (la correlación medida entre la señal y el "
            + "resultado es 0,039). Úsela para priorizar una llamada, no para dar por "
            + "perdido a un cliente. "
            + "4) La recencia se mide contra la ÚLTIMA COMPRA REGISTRADA EN EL ALMACÉN "
            + "(" + cabecera.get("ancla") + "), no contra la fecha de hoy. Si el "
            + "pipeline se detiene, la pantalla no se llena de falsas alarmas — pero "
            + "tampoco se actualiza, y la fecha del título lo dice. "
            + "5) La muestra por cliente es pequeña: la columna «pedidos» sostiene el "
            + "ritmo de cada fila, y con menos de 3 pedidos en la ventana no hay ritmo "
            + "que calcular. " + v.get("sin_muestra") + " de " + v.get("clientes")
            + " clientes están en ese caso y salen con nivel «sin muestra» y su "
            + "silencio real: son precisamente los candidatos más fuertes al abandono "
            + "—su silencio es lo que los dejó sin pedidos— y el modelo NO puede "
            + "ordenarlos.";
    }
}
