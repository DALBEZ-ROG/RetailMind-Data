package com.retailmind.informes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * INFORMES TÁCTICOS COMPUESTOS — SOPORTE. Fuente: ClickHouse
 * ({@code retailmind_dwh}), no PostgreSQL.
 *
 * Cinco objetivos sobre UNA tabla de 248 filas —{@code fact_ticket}— más
 * {@code fact_devolucion_linea} para la mitad «devoluciones» de SOP-08:
 *
 * <pre>
 *   OTD-SOP-02  cumplimiento del plazo prometido   fact_ticket
 *   OTD-SOP-03  tiempo de resolución por categoría fact_ticket
 *   OTD-SOP-06  horas hasta la primera respuesta   fact_ticket
 *   OTD-SOP-07  tiempo de resolución por agente    fact_ticket
 *   OTD-SOP-08  productos que más problemas dan    fact_ticket + fact_devolucion_linea
 * </pre>
 *
 * Es la mejor relación informes/filas del modelo, y no por casualidad: los
 * cinco preguntan por el mismo hecho —un ticket— desde ejes distintos, así que
 * comparten tabla, filtros y definiciones.
 *
 * <h2>Ninguno lleva dinero</h2>
 * Los cinco van en tiempos, conteos y veredictos. No hay corte financiero que
 * hacer y por eso los cinco comparten el comodín del departamento en
 * {@code SecurityConfig} (ADMIN, GERENTE, SOPORTE), con la única salvedad de
 * SOP-08, que suma a COMPRAS porque el ranking de productos problemáticos
 * existe para que Compras revise al proveedor.
 *
 * <h2>Las dos decisiones que definen estos informes</h2>
 *
 * <ol>
 *   <li><b>Qué cuenta como «primera respuesta»</b> (SOP-06). Es una decisión,
 *       no un dato — ver {@link #PRIMERA_RESPUESTA}.</li>
 *   <li><b>Cómo se reparte la base del SLA</b> (SOP-02). En CUATRO categorías
 *       y no en un porcentaje — ver {@link #cumplimientoSla}.</li>
 * </ol>
 *
 * Ninguno de estos métodos lleva {@code @Transactional}: no tocan PostgreSQL.
 */
@Service
public class InformesSoporteCompuestosService extends InformeCompuestoServiceBase {

    private static final String TABLA = "fact_ticket";
    private static final String TABLA_DEV_LINEA = "fact_devolucion_linea";

    /**
     * La definición adoptada de «primera respuesta», literal y en el sobre.
     *
     * Se manda a la PANTALLA y no solo al código porque cualquier otra
     * definición da otro número igual de defendible, y un tiempo medio de
     * respuesta sin decir qué cuenta como respuesta no se puede reproducir:
     *
     * <pre>
     *   tickets con ALGÚN mensaje del equipo ............ 244
     *     cuya primera intervención es una NOTA INTERNA ..  32
     *     sin ninguna respuesta visible (solo notas) .....  51
     *   tickets con primera respuesta VISIBLE ........... 193   ← la adoptada
     * </pre>
     *
     * Con la definición laxa el informe mediría sobre 244 tickets y con un
     * tiempo sistemáticamente menor (los 32 responden al cliente 1,35 h más
     * tarde de lo que dice su primera nota interna).
     */
    private static final String PRIMERA_RESPUESTA =
            "«Primera respuesta» = el primer mensaje escrito por alguien del equipo "
            + "y VISIBLE para el cliente. Una nota interna entre agentes no cuenta, "
            + "aunque llegue antes. Con esa definición 193 de 248 tickets tienen "
            + "respuesta; contando también las notas internas serían 244, y el tiempo "
            + "medio saldría más bajo. Las dos cifras son ciertas: esta es la que mide "
            + "lo que el cliente recibió.";

    /** Los 5 estados del ciclo del ticket. */
    private static final Set<String> ESTADOS =
            Set.of("abierto", "en_proceso", "esperando_cliente", "resuelto", "cerrado");

    /** Las 4 prioridades, con su compromiso de 2 / 4 / 24 / 72 horas. */
    private static final Set<String> PRIORIDADES =
            Set.of("urgente", "alta", "media", "baja");

    public InformesSoporteCompuestosService(
            @Qualifier("pgJdbcTemplate") JdbcTemplate pg,
            @Qualifier("clickHouseJdbc") JdbcTemplate ch) {
        super(pg, ch);
    }

    /** Rango de creación + categoría + prioridad: los filtros de los cinco. */
    private Filtros filtrosTicket(String desde, String hasta, String categoria,
                                  String prioridad) {
        Filtros f = new Filtros();
        f.y("toDate(fecha_creacion) >= toDate(?)", fecha(desde, "desde"));
        f.y("toDate(fecha_creacion) <= toDate(?)", fecha(hasta, "hasta"));
        f.y("positionCaseInsensitive(categoria, ?) > 0", texto(categoria));
        f.y("prioridad = ?", opcion(prioridad, PRIORIDADES, "prioridad"));
        return f;
    }

    // ═════════════════════════════════════════════════════════════════════
    // OTD-SOP-02 — Cumplimiento del tiempo prometido al cliente
    // ═════════════════════════════════════════════════════════════════════

    /** Ejes del cumplimiento. La prioridad es el eje natural: define el plazo. */
    private static final Set<String> EJES_SLA =
            Set.of("prioridad", "categoria", "mes", "agente");

    /**
     * Cuántos reclamos se atienden dentro del plazo prometido — en CUATRO
     * categorías y no en un porcentaje.
     *
     * <h3>Por qué cuatro y no una tasa</h3>
     * Solo 76 de los 248 tickets están cerrados. El cumplimiento de los otros
     * 172 no es <i>incumplido</i>: es <b>desconocido</b>, porque todavía
     * pueden cerrarse a tiempo. Un porcentaje calculado sobre 248 como si todos
     * hubieran terminado daría 12/248 = 4,8 % — un número falso que mezcla lo
     * incumplido con lo que aún no ha ocurrido.
     *
     * La base se parte por tanto en cuatro:
     *
     * <pre>
     *   cerrado a tiempo ........  12   veredicto: cumplió
     *   cerrado tarde ...........  64   veredicto: incumplió
     *   abierto dentro de plazo .   0   todavía puede cumplir
     *   abierto y YA VENCIDO .... 172   ← la categoría accionable
     * </pre>
     *
     * Y el {@code pct_cumplimiento} de cada fila se calcula <b>solo sobre los
     * cerrados</b>, con la columna {@code cerrados} al lado para que se vea el
     * denominador. Los abiertos vencidos van en su propia columna porque son
     * lo que hay que ir a hacer hoy, no una estadística.
     *
     * <h3>Las dos categorías de abiertos se calculan AHORA, no en la carga</h3>
     * Dependen de {@code now()}: un ticket dentro de plazo a las 03:00 de la
     * carga del ETL puede estar vencido a las 09:00. El almacén guarda
     * {@code fecha_limite} y {@code fecha_cierre}; la partición la hace esta
     * consulta en el momento de mirarla. Congelarla habría dado un informe que
     * envejece mal justo en su única columna accionable.
     *
     * <h3>Que los «abiertos dentro de plazo» sean 0 no es un fallo</h3>
     * El seed llega al 2026-07 y los plazos son de 2 a 72 horas: toda la cola
     * viva está vencida. La fila se muestra igualmente con su cero — un
     * informe que oculta la categoría vacía hace creer que no existe.
     */
    public Map<String, Object> cumplimientoSla(String desde, String hasta,
                                               String categoria, String prioridad,
                                               String agrupar) {
        exigirRangoValido(fecha(desde, "desde"), fecha(hasta, "hasta"));
        String eje = agrupar == null || agrupar.isBlank()
                ? "prioridad" : opcion(agrupar, EJES_SLA, "agrupar");

        return ejecutar("OTD-SOP-02", () -> {
            Filtros f = filtrosTicket(desde, hasta, categoria, prioridad);
            String clave = switch (eje) {
                case "categoria" -> "categoria";
                case "mes"       -> "formatDateTime(mes, '%Y-%m')";
                case "agente"    -> "agente";
                default          -> "prioridad";
            };

            List<Map<String, Object>> items = ch.queryForList("""
                SELECT %s                                          AS etiqueta,
                       count()                                     AS tickets,
                       any(horas_sla_comprometidas)                AS horas_prometidas,
                       countIf(cumplio_sla IS NOT NULL)            AS cerrados,
                       countIf(cumplio_sla = 1)                    AS cerrados_a_tiempo,
                       countIf(cumplio_sla = 0)                    AS cerrados_tarde,
                       countIf(fecha_cierre IS NULL AND fecha_limite >= now())
                                                                   AS abiertos_en_plazo,
                       countIf(fecha_cierre IS NULL AND fecha_limite <  now())
                                                                   AS abiertos_vencidos,
                       round(countIf(cumplio_sla = 1) * 100.0
                             / nullIf(countIf(cumplio_sla IS NOT NULL), 0), 2)
                                                                   AS pct_cumplimiento,
                       round(avgIf(horas_resolucion,
                             horas_resolucion IS NOT NULL), 2)     AS horas_resolucion
                FROM %s.%s
                WHERE 1 %s
                GROUP BY etiqueta
                ORDER BY %s
                """.formatted(clave, DWH, TABLA, f.where(),
                        "mes".equals(eje) ? "etiqueta ASC" : "tickets DESC"), f.args());

            Map<String, Object> sobre = sobre(items);
            conResumen(sobre, kpisSla(f));
            sobre.put("salvedad",
                    "El porcentaje de cumplimiento se calcula SOLO sobre los tickets "
                    + "CERRADOS, que son 76 de 248. De los 172 abiertos no se sabe si "
                    + "cumplirán: no son incumplimientos, son casos sin desenlace. Por "
                    + "eso la base se parte en cuatro columnas y la última —abierto y ya "
                    + "vencido— es la accionable. Un porcentaje sobre el total daría "
                    + "4,8 % y sería falso. Las dos columnas de abiertos se calculan en "
                    + "el momento de la consulta, no en la carga del almacén.");
            return conMarcaDeAgua(sobre, TABLA);
        });
    }

    private List<Map<String, Object>> kpisSla(Filtros f) {
        Map<String, Object> t = ch.queryForMap("""
            SELECT count()                                    AS tickets,
                   countIf(cumplio_sla IS NOT NULL)           AS cerrados,
                   countIf(cumplio_sla = 1)                   AS a_tiempo,
                   countIf(cumplio_sla = 0)                   AS tarde,
                   countIf(fecha_cierre IS NULL AND fecha_limite >= now())
                                                              AS abiertos_en_plazo,
                   countIf(fecha_cierre IS NULL AND fecha_limite < now())
                                                              AS abiertos_vencidos,
                   round(countIf(cumplio_sla = 1) * 100.0
                         / nullIf(countIf(cumplio_sla IS NOT NULL), 0), 2) AS pct
            FROM %s.%s WHERE 1 %s
            """.formatted(DWH, TABLA, f.where()), f.args());

        List<Map<String, Object>> k = new ArrayList<>();
        k.add(kpi("Tickets del período", t.get("tickets"), "numero"));
        k.add(kpi("Cerrados (base del %)", t.get("cerrados"), "numero"));
        k.add(kpi("Cerrados a tiempo", t.get("a_tiempo"), "numero"));
        k.add(kpi("Cerrados tarde", t.get("tarde"), "numero"));
        k.add(kpi("Abiertos dentro de plazo", t.get("abiertos_en_plazo"), "numero"));
        k.add(kpi("Abiertos y YA VENCIDOS", t.get("abiertos_vencidos"), "numero"));
        k.add(kpi("Cumplimiento (solo cerrados)", cero(t.get("pct")), "porcentaje"));
        return k;
    }

    // ═════════════════════════════════════════════════════════════════════
    // OTD-SOP-03 — Cuánto tardamos en resolver cada tipo de problema
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Tiempo de resolución por categoría de reclamo.
     *
     * <h3>La base son 76 cierres y no los 120 «resueltos»</h3>
     * En el sistema, {@code resuelto} y {@code cerrado} no son el mismo hecho:
     * solo el cierre escribe {@code fecha_cierre}, porque un ticket resuelto
     * todavía puede reabrirlo el cliente respondiendo. Medido:
     *
     * <pre>
     *   estado 'cerrado' ..............  76   ← y los 76 tienen fecha
     *   estado 'resuelto' .............  44   ← ninguno la tiene
     *   con fecha_cierre ..............  76
     * </pre>
     *
     * No hay instante que restar en los 44, así que el tiempo se mide sobre
     * 76. Cada fila trae {@code cerrados} y {@code resueltos_por_estado} para
     * que la diferencia esté a la vista en vez de escondida en el promedio.
     *
     * <h3>Promedio, mediana y p90</h3>
     * Igual que en LOG-12: el promedio de un tiempo lo dispara un caso
     * atascado, la mediana dice lo que le pasa al ticket corriente y el p90
     * cuánto sufre el 10 % peor. Un informe de resolución con solo el promedio
     * invita a perseguir el caso raro.
     */
    public Map<String, Object> tiempoResolucion(String desde, String hasta,
                                                String categoria, String prioridad,
                                                String estado) {
        exigirRangoValido(fecha(desde, "desde"), fecha(hasta, "hasta"));
        String fEstado = opcion(estado, ESTADOS, "estado");

        return ejecutar("OTD-SOP-03", () -> {
            Filtros f = filtrosTicket(desde, hasta, categoria, prioridad);
            f.y("estado = ?", fEstado);

            List<Map<String, Object>> items = ch.queryForList("""
                SELECT categoria,
                       count()                                     AS tickets,
                       countIf(resuelto_por_estado = 1)            AS resueltos_por_estado,
                       countIf(horas_resolucion IS NOT NULL)       AS cerrados,
                       round(avgIf(horas_resolucion,
                             horas_resolucion IS NOT NULL), 2)     AS horas_promedio,
                       round(quantileExactIf(0.5)(horas_resolucion,
                             horas_resolucion IS NOT NULL), 2)     AS horas_mediana,
                       round(quantileExactIf(0.9)(horas_resolucion,
                             horas_resolucion IS NOT NULL), 2)     AS horas_p90,
                       round(maxIf(horas_resolucion,
                             horas_resolucion IS NOT NULL), 2)     AS horas_maximo,
                       round(avgIf(horas_resolucion,
                             horas_resolucion IS NOT NULL) / 24.0, 2) AS dias_promedio,
                       countIf(cumplio_sla = 1)                    AS a_tiempo,
                       countIf(cumplio_sla = 0)                    AS tarde
                FROM %s.%s
                WHERE 1 %s
                GROUP BY categoria
                ORDER BY tickets DESC, categoria
                """.formatted(DWH, TABLA, f.where()), f.args());

            for (Map<String, Object> fila : items) {
                fila.put("cobertura_pct",
                        pct(fila.get("cerrados"), fila.get("tickets")));
            }

            Map<String, Object> sobre = sobre(items);
            conResumen(sobre, kpisResolucion(f, items));
            sobre.put("salvedad",
                    "El tiempo se mide entre la apertura y el CIERRE, y solo 76 de los "
                    + "248 tickets tienen cierre. Los 44 en estado «resuelto» NO cuentan: "
                    + "en este sistema resolver no cierra —el cliente todavía puede "
                    + "responder y reabrir— y no hay instante que restar. Cada fila "
                    + "declara cuántos cerrados sostienen su promedio.");
            return conMarcaDeAgua(sobre, TABLA);
        });
    }

    private List<Map<String, Object>> kpisResolucion(Filtros f,
                                                     List<Map<String, Object>> items) {
        Map<String, Object> t = ch.queryForMap("""
            SELECT count()                                  AS tickets,
                   countIf(horas_resolucion IS NOT NULL)    AS cerrados,
                   countIf(resuelto_por_estado = 1)         AS resueltos,
                   round(avgIf(horas_resolucion,
                         horas_resolucion IS NOT NULL), 2)  AS promedio,
                   round(quantileExactIf(0.5)(horas_resolucion,
                         horas_resolucion IS NOT NULL), 2)  AS mediana,
                   round(quantileExactIf(0.9)(horas_resolucion,
                         horas_resolucion IS NOT NULL), 2)  AS p90,
                   countDistinct(categoria)                 AS categorias
            FROM %s.%s WHERE 1 %s
            """.formatted(DWH, TABLA, f.where()), f.args());

        List<Map<String, Object>> k = new ArrayList<>();
        k.add(kpi("Tickets del período", t.get("tickets"), "numero"));
        k.add(kpi("Con cierre (base)", t.get("cerrados"), "numero"));
        k.add(kpi("En estado resuelto o cerrado", t.get("resueltos"), "numero"));
        k.add(kpi("Horas promedio", cero(t.get("promedio")), "numero"));
        k.add(kpi("Mediana", cero(t.get("mediana")), "numero"));
        k.add(kpi("P90 (el 10 % peor)", cero(t.get("p90")), "numero"));
        k.add(kpi("Categorías", t.get("categorias"), "numero"));
        // La categoría más lenta se elige entre las que TIENEN cierres: una sin
        // ninguno no es «la más rápida con 0 horas», es una de la que no se sabe.
        Map<String, Object> peor = null;
        for (Map<String, Object> fila : items) {
            Object h = fila.get("horas_promedio");
            if (h == null || ((Number) fila.get("cerrados")).intValue() == 0) {
                continue;
            }
            if (peor == null || ((Number) h).doubleValue()
                    > ((Number) peor.get("horas_promedio")).doubleValue()) {
                peor = fila;
            }
        }
        if (peor != null) {
            k.add(kpi("Categoría más lenta", peor.get("categoria"), "texto"));
        }
        return k;
    }

    // ═════════════════════════════════════════════════════════════════════
    // OTD-SOP-06 — Horas hasta la primera respuesta al cliente
    // ═════════════════════════════════════════════════════════════════════

    /** Ejes de la primera respuesta. La urgencia es el que pide el catálogo. */
    private static final Set<String> EJES_RESPUESTA =
            Set.of("prioridad", "mes", "categoria", "agente");

    /**
     * Cuánto tarda el equipo en contestar por primera vez.
     *
     * <h3>La definición está en la pantalla, no solo en el código</h3>
     * Ver {@link #PRIMERA_RESPUESTA}: el sobre la lleva en {@code salvedad} y
     * el informe muestra <b>las dos poblaciones</b> —con respuesta visible y
     * con cualquier mensaje del equipo— para que la diferencia sea un dato y
     * no una nota de implementación. 193 contra 244 tickets, y 1,35 h de
     * distancia media en los 32 que empiezan por una nota interna.
     *
     * <h3>Los 55 sin respuesta también salen</h3>
     * {@code sin_respuesta} es una columna del informe. Un tiempo medio
     * calculado solo sobre los que sí contestaron es el sesgo clásico de mirar
     * lo que terminó — y aquí el 22 % de los tickets no ha recibido una sola
     * palabra visible.
     */
    public Map<String, Object> primeraRespuesta(String desde, String hasta,
                                                String categoria, String prioridad,
                                                String agrupar) {
        exigirRangoValido(fecha(desde, "desde"), fecha(hasta, "hasta"));
        String eje = agrupar == null || agrupar.isBlank()
                ? "prioridad" : opcion(agrupar, EJES_RESPUESTA, "agrupar");

        return ejecutar("OTD-SOP-06", () -> {
            Filtros f = filtrosTicket(desde, hasta, categoria, prioridad);
            String clave = switch (eje) {
                case "mes"       -> "formatDateTime(mes, '%Y-%m')";
                case "categoria" -> "categoria";
                case "agente"    -> "agente";
                default          -> "prioridad";
            };

            List<Map<String, Object>> items = ch.queryForList("""
                SELECT %s                                            AS etiqueta,
                       count()                                       AS tickets,
                       any(horas_sla_comprometidas)                  AS horas_prometidas,
                       countIf(fecha_primera_respuesta IS NOT NULL)  AS con_respuesta,
                       countIf(fecha_primera_respuesta IS NULL)      AS sin_respuesta,
                       round(avgIf(horas_primera_respuesta,
                             horas_primera_respuesta IS NOT NULL), 2) AS horas_promedio,
                       round(quantileExactIf(0.5)(horas_primera_respuesta,
                             horas_primera_respuesta IS NOT NULL), 2) AS horas_mediana,
                       round(quantileExactIf(0.9)(horas_primera_respuesta,
                             horas_primera_respuesta IS NOT NULL), 2) AS horas_p90,
                       -- La definición LAXA, al lado, para que la decisión se vea.
                       countIf(fecha_primer_mensaje_equipo IS NOT NULL)
                                                                     AS con_mensaje_equipo,
                       round(avgIf(horas_hasta_mensaje_equipo,
                             horas_hasta_mensaje_equipo IS NOT NULL), 2)
                                                                     AS horas_incl_internas,
                       countIf(fecha_primer_mensaje_equipo IS NOT NULL
                               AND fecha_primera_respuesta IS NULL)  AS solo_notas_internas,
                       sum(mensajes)                                 AS mensajes,
                       sum(mensajes_internos)                        AS mensajes_internos
                FROM %s.%s
                WHERE 1 %s
                GROUP BY etiqueta
                ORDER BY %s
                """.formatted(clave, DWH, TABLA, f.where(),
                        "mes".equals(eje) ? "etiqueta ASC" : "tickets DESC"), f.args());

            for (Map<String, Object> fila : items) {
                fila.put("cobertura_pct",
                        pct(fila.get("con_respuesta"), fila.get("tickets")));
            }

            Map<String, Object> sobre = sobre(items);
            conResumen(sobre, kpisRespuesta(f));
            sobre.put("salvedad", PRIMERA_RESPUESTA);
            return conMarcaDeAgua(sobre, TABLA);
        });
    }

    private List<Map<String, Object>> kpisRespuesta(Filtros f) {
        Map<String, Object> t = ch.queryForMap("""
            SELECT count()                                          AS tickets,
                   countIf(fecha_primera_respuesta IS NOT NULL)     AS con_respuesta,
                   countIf(fecha_primera_respuesta IS NULL)         AS sin_respuesta,
                   round(avgIf(horas_primera_respuesta,
                         horas_primera_respuesta IS NOT NULL), 2)   AS promedio,
                   round(quantileExactIf(0.5)(horas_primera_respuesta,
                         horas_primera_respuesta IS NOT NULL), 2)   AS mediana,
                   countIf(fecha_primer_mensaje_equipo IS NOT NULL) AS con_equipo,
                   round(avgIf(horas_hasta_mensaje_equipo,
                         horas_hasta_mensaje_equipo IS NOT NULL), 2) AS promedio_laxo,
                   sum(mensajes)                                    AS mensajes
            FROM %s.%s WHERE 1 %s
            """.formatted(DWH, TABLA, f.where()), f.args());

        List<Map<String, Object>> k = new ArrayList<>();
        k.add(kpi("Tickets del período", t.get("tickets"), "numero"));
        k.add(kpi("Con respuesta visible", t.get("con_respuesta"), "numero"));
        k.add(kpi("SIN respuesta al cliente", t.get("sin_respuesta"), "numero"));
        k.add(kpi("Horas hasta responder", cero(t.get("promedio")), "numero"));
        k.add(kpi("Mediana", cero(t.get("mediana")), "numero"));
        k.add(kpi("Base con la otra definición", t.get("con_equipo"), "numero"));
        k.add(kpi("Horas contando notas internas", cero(t.get("promedio_laxo")), "numero"));
        k.add(kpi("Mensajes intercambiados", t.get("mensajes"), "numero"));
        return k;
    }

    // ═════════════════════════════════════════════════════════════════════
    // OTD-SOP-07 — Cuánto tarda cada persona del equipo
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Carga y tiempos por agente.
     *
     * <h3>«(sin asignar)» es una fila del informe, y de las importantes</h3>
     * 33 de los 248 tickets no tienen agente. No es un hueco del dato: es la
     * cola que nadie ha tomado, y esconderla convertiría un informe de gestión
     * de equipo en un informe de los que sí trabajan. Aparece como una fila
     * más, con su etiqueta.
     *
     * <h3>Comparar agentes exige mirar el denominador</h3>
     * Cada fila trae {@code cerrados} junto al promedio. Un agente con dos
     * cierres rápidos no es mejor que otro con veinte y una media algo peor, y
     * un ranking por horas sin la columna de volumen invita exactamente a esa
     * conclusión.
     *
     * <h3>Sin dinero, pero es un informe sobre PERSONAS</h3>
     * El catálogo lo reserva a Soporte y Gerencia. No hay corte financiero que
     * hacer; el corte que importa aquí es de atribución y lo hace la ruta del
     * departamento.
     */
    public Map<String, Object> tiemposAgente(String desde, String hasta,
                                             String categoria, String prioridad,
                                             String agente) {
        exigirRangoValido(fecha(desde, "desde"), fecha(hasta, "hasta"));

        return ejecutar("OTD-SOP-07", () -> {
            Filtros f = filtrosTicket(desde, hasta, categoria, prioridad);
            f.y("positionCaseInsensitive(agente, ?) > 0", texto(agente));

            List<Map<String, Object>> items = ch.queryForList("""
                SELECT agente,
                       count()                                      AS tickets,
                       countIf(horas_resolucion IS NOT NULL)        AS cerrados,
                       countIf(estado NOT IN ('resuelto','cerrado')) AS vivos,
                       round(avgIf(horas_resolucion,
                             horas_resolucion IS NOT NULL), 2)      AS horas_promedio,
                       round(quantileExactIf(0.5)(horas_resolucion,
                             horas_resolucion IS NOT NULL), 2)      AS horas_mediana,
                       round(maxIf(horas_resolucion,
                             horas_resolucion IS NOT NULL), 2)      AS horas_maximo,
                       countIf(cumplio_sla = 1)                     AS a_tiempo,
                       countIf(cumplio_sla = 0)                     AS tarde,
                       round(countIf(cumplio_sla = 1) * 100.0
                             / nullIf(countIf(cumplio_sla IS NOT NULL), 0), 2)
                                                                    AS pct_cumplimiento,
                       countIf(fecha_cierre IS NULL AND fecha_limite < now())
                                                                    AS vencidos,
                       round(avgIf(horas_primera_respuesta,
                             horas_primera_respuesta IS NOT NULL), 2) AS horas_respuesta,
                       countDistinct(categoria)                     AS categorias
                FROM %s.%s
                WHERE 1 %s
                GROUP BY agente
                ORDER BY tickets DESC, agente
                """.formatted(DWH, TABLA, f.where()), f.args());

            for (Map<String, Object> fila : items) {
                fila.put("cobertura_pct", pct(fila.get("cerrados"), fila.get("tickets")));
            }

            Map<String, Object> sobre = sobre(items);
            conResumen(sobre, kpisAgente(f, items));
            sobre.put("salvedad",
                    "El promedio de cada agente se calcula sobre SUS tickets cerrados, "
                    + "que son pocos: 76 cierres repartidos entre el equipo. La columna "
                    + "«Cerrados» es el denominador y hay que leerla antes que las horas "
                    + "— con dos o tres casos, la media de un agente no distingue nada. "
                    + "La fila «(sin asignar)» son los tickets que nadie ha tomado, y se "
                    + "muestra a propósito.");
            return conMarcaDeAgua(sobre, TABLA);
        });
    }

    private List<Map<String, Object>> kpisAgente(Filtros f,
                                                 List<Map<String, Object>> items) {
        Map<String, Object> t = ch.queryForMap("""
            SELECT count()                                     AS tickets,
                   countDistinctIf(agente, agente != '(sin asignar)') AS agentes,
                   countIf(agente = '(sin asignar)')           AS sin_asignar,
                   countIf(horas_resolucion IS NOT NULL)       AS cerrados,
                   round(avgIf(horas_resolucion,
                         horas_resolucion IS NOT NULL), 2)     AS promedio,
                   countIf(fecha_cierre IS NULL AND fecha_limite < now()) AS vencidos
            FROM %s.%s WHERE 1 %s
            """.formatted(DWH, TABLA, f.where()), f.args());

        List<Map<String, Object>> k = new ArrayList<>();
        k.add(kpi("Tickets del período", t.get("tickets"), "numero"));
        k.add(kpi("Agentes con carga", t.get("agentes"), "numero"));
        k.add(kpi("Sin asignar", t.get("sin_asignar"), "numero"));
        k.add(kpi("Cierres del equipo", t.get("cerrados"), "numero"));
        k.add(kpi("Horas promedio del equipo", cero(t.get("promedio")), "numero"));
        k.add(kpi("Abiertos y vencidos", t.get("vencidos"), "numero"));

        // El «más rápido» exige un mínimo de cierres: con uno solo, cualquiera
        // gana el ranking sin haber demostrado nada.
        Map<String, Object> mejor = null;
        for (Map<String, Object> fila : items) {
            Object h = fila.get("horas_promedio");
            if (h == null || ((Number) fila.get("cerrados")).intValue() < 5) {
                continue;
            }
            if (mejor == null || ((Number) h).doubleValue()
                    < ((Number) mejor.get("horas_promedio")).doubleValue()) {
                mejor = fila;
            }
        }
        if (mejor != null) {
            k.add(kpi("Más rápido (≥5 cierres)", mejor.get("agente"), "texto"));
        }
        return k;
    }

    // ═════════════════════════════════════════════════════════════════════
    // OTD-SOP-08 — Productos que más reclamos y devoluciones generan
    // ═════════════════════════════════════════════════════════════════════

    /**
     * El ranking que Compras y Ventas necesitan: qué productos dan problemas.
     *
     * <h3>Dos tablas, dos hechos, un producto</h3>
     * Los reclamos salen de {@code fact_ticket} (142 de 248 tickets llevan
     * producto, script 50) y las devoluciones de
     * {@code fact_devolucion_linea} (274 líneas). Se agregan por SEPARADO y se
     * unen por variante con un {@code UNION ALL} + {@code GROUP BY}: un
     * producto que solo tiene reclamos —o solo devoluciones— tiene que
     * aparecer igualmente. Con un JOIN se perdería justo la mitad del ranking.
     *
     * <h3>Los 106 tickets sin producto NO se reparten</h3>
     * Quedan fuera del ranking y se declaran en el resumen. Repartirlos entre
     * los productos que sí tienen —o achacarlos al más reclamado— fabricaría
     * un culpable. Un ticket sobre «problema con mi factura» no es un problema
     * de ningún producto.
     *
     * <h3>Sin importes</h3>
     * El informe cuenta reclamos y unidades. COMPRAS es destinataria porque el
     * ranking existe para que revise al proveedor, y el corte financiero no le
     * afecta: no hay una sola columna de dinero en esta consulta.
     */
    public Map<String, Object> productosReclamados(String desde, String hasta,
                                                   String categoria, String buscar,
                                                   int page, int size) {
        exigirRangoValido(fecha(desde, "desde"), fecha(hasta, "hasta"));

        return ejecutar("OTD-SOP-08", () -> {
            Filtros tickets = new Filtros();
            tickets.y("tiene_producto = 1");
            tickets.y("toDate(fecha_creacion) >= toDate(?)", fecha(desde, "desde"));
            tickets.y("toDate(fecha_creacion) <= toDate(?)", fecha(hasta, "hasta"));
            tickets.y("positionCaseInsensitive(categoria_producto, ?) > 0",
                    texto(categoria));
            tickets.y("positionCaseInsensitive(producto_nombre, ?) > 0", texto(buscar));

            Filtros devs = new Filtros();
            devs.y("toDate(fecha_solicitud) >= toDate(?)", fecha(desde, "desde"));
            devs.y("toDate(fecha_solicitud) <= toDate(?)", fecha(hasta, "hasta"));
            devs.y("positionCaseInsensitive(categoria, ?) > 0", texto(categoria));
            devs.y("positionCaseInsensitive(producto_nombre, ?) > 0", texto(buscar));

            Object[] args = concat(tickets.args(), devs.args());

            String sqlItems = """
                SELECT producto_nombre, any(cat) AS categoria,
                       -- Los alias del UNION llevan prefijo `n_`/`u_` porque en
                       -- ClickHouse un agregado NO puede llamarse como la
                       -- columna que agrega (ILLEGAL_AGGREGATION, lección de la
                       -- Fase 1): `sum(reclamos) AS reclamos` revienta.
                       sum(n_reclamos)          AS reclamos,
                       sum(n_vencidos)          AS reclamos_vencidos,
                       sum(n_devoluciones)      AS devoluciones,
                       sum(u_devueltas)         AS uds_devueltas,
                       sum(u_defectuosas)       AS uds_defectuosas,
                       sum(n_reclamos) + sum(n_devoluciones) AS incidencias
                FROM (
                    SELECT producto_nombre, categoria_producto AS cat,
                           count() AS n_reclamos,
                           countIf(fecha_cierre IS NULL AND fecha_limite < now())
                               AS n_vencidos,
                           0 AS n_devoluciones, 0 AS u_devueltas, 0 AS u_defectuosas
                    FROM %s.%s WHERE 1 %s
                    GROUP BY producto_nombre, categoria_producto
                    UNION ALL
                    SELECT producto_nombre, categoria, 0, 0,
                           count() AS n_devoluciones,
                           sum(cantidad) AS u_devueltas,
                           sumIf(cantidad, resultado_inspeccion = 'defectuoso')
                               AS u_defectuosas
                    FROM %s.%s WHERE 1 %s
                    GROUP BY producto_nombre, categoria
                )
                GROUP BY producto_nombre
                ORDER BY incidencias DESC, reclamos DESC, producto_nombre
                """.formatted(DWH, TABLA, tickets.where(),
                        DWH, TABLA_DEV_LINEA, devs.where());

            Map<String, Object> sobre = paginarCh(sqlItems,
                    "SELECT count() FROM (" + sqlItems + ")", args, page, size);
            conResumen(sobre, kpisProductos(tickets, devs));
            sobre.put("salvedad",
                    "El ranking solo incluye lo que se puede atribuir a un producto: 142 "
                    + "de los 248 tickets llevan producto (el resto son consultas de "
                    + "facturación, envío o cuenta) y las 274 líneas de devolución. Los "
                    + "106 tickets sin producto NO se reparten entre los demás: hacerlo "
                    + "inventaría un culpable. Reclamos y devoluciones se cuentan por "
                    + "separado y se suman en «Incidencias», que es un recuento de "
                    + "hechos y no una medida de gravedad.");
            return conMarcaDeAgua(sobre, TABLA);
        });
    }

    private List<Map<String, Object>> kpisProductos(Filtros tickets, Filtros devs) {
        Map<String, Object> t = ch.queryForMap("""
            SELECT count()                       AS con_producto,
                   countDistinct(producto_nombre) AS productos
            FROM %s.%s WHERE 1 %s
            """.formatted(DWH, TABLA, tickets.where()), tickets.args());

        Map<String, Object> sin = ch.queryForMap("""
            SELECT countIf(tiene_producto = 0) AS sin_producto, count() AS total
            FROM %s.%s
            """.formatted(DWH, TABLA));

        Map<String, Object> d = ch.queryForMap("""
            SELECT count() AS lineas, sum(cantidad) AS unidades,
                   countDistinct(producto_nombre) AS productos,
                   sumIf(cantidad, resultado_inspeccion = 'defectuoso') AS defectuosas
            FROM %s.%s WHERE 1 %s
            """.formatted(DWH, TABLA_DEV_LINEA, devs.where()), devs.args());

        List<Map<String, Object>> k = new ArrayList<>();
        k.add(kpi("Reclamos con producto", t.get("con_producto"), "numero"));
        k.add(kpi("Tickets SIN producto (fuera)", sin.get("sin_producto"), "numero"));
        k.add(kpi("Productos reclamados", t.get("productos"), "numero"));
        k.add(kpi("Líneas devueltas", d.get("lineas"), "numero"));
        k.add(kpi("Unidades devueltas", d.get("unidades"), "numero"));
        k.add(kpi("Unidades defectuosas", d.get("defectuosas"), "numero"));
        k.add(kpi("Productos devueltos", d.get("productos"), "numero"));
        return k;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Utilidades
    // ═════════════════════════════════════════════════════════════════════

    /** Un conjunto vacío devuelve NULL en el agregado; la tarjeta muestra 0. */
    private static Object cero(Object v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** Porcentaje de parte sobre total, con dos decimales. Null si no hay base. */
    private static Double pct(Object parte, Object total) {
        if (parte == null || total == null) {
            return null;
        }
        double base = ((Number) total).doubleValue();
        return base == 0 ? null
                : Math.round(((Number) parte).doubleValue() * 10000.0 / base) / 100.0;
    }

    /** Los args de dos juegos de filtros, en el orden en que aparecen en el SQL. */
    private static Object[] concat(Object[] a, Object[] b) {
        Object[] todos = new Object[a.length + b.length];
        System.arraycopy(a, 0, todos, 0, a.length);
        System.arraycopy(b, 0, todos, a.length, b.length);
        return todos;
    }

    /** Etiquetas legibles de los ejes, por si algún informe las necesita. */
    static final Map<String, String> EJE_LEGIBLE = new LinkedHashMap<>(Map.of(
            "prioridad", "Por urgencia",
            "categoria", "Por categoría",
            "mes",       "Evolución mensual",
            "agente",    "Por agente"));
}
