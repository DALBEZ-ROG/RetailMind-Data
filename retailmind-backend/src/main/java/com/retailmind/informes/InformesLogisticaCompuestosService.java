package com.retailmind.informes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * INFORMES TÁCTICOS COMPUESTOS — LOGÍSTICA. Fuente: ClickHouse
 * ({@code retailmind_dwh}), no PostgreSQL.
 *
 * Sirve CINCO objetivos, de tres tablas de hechos distintas:
 *
 * <pre>
 *   OTD-LOG-12  tiempos por etapa      fact_pedido          (Fase 2)
 *   OTD-LOG-03  cumplimiento de fecha  fact_envio           (Fase 3C)
 *   OTD-LOG-04  días de tránsito       fact_envio           (Fase 3C)
 *   OTD-LOG-05  problemas de entrega   fact_novedad_envio   (Fase 3C)
 *   (serie)     costo de envío mensual fact_envio           (Fase 3C)
 * </pre>
 *
 * La SERIE del costo de envío no tiene código propio en el catálogo: es la
 * evolución temporal que OTD-LOG-11 dejó pendiente al reclasificarse a SIMPLE
 * (§6 del catálogo). El informe simple da la FOTO —costo por zona y
 * transportista, agregado del período—; éste da la SERIE, mes a mes. El diseño
 * (§5.8) anticipó que {@code fact_envio} la sirve sin tabla adicional, y así es:
 * un {@code GROUP BY mes} sobre columnas que la tabla ya lleva.
 *
 * <h2>Los tres informes de tiempos NO llevan dinero — y eso es deliberado</h2>
 * DESPACHO es destinatario de LOG-03, LOG-04 y LOG-05 (catálogo §7), y el corte
 * financiero del sistema lo deja fuera de todo importe. Como en LOG-12, la
 * barrera aquí NO puede ser el motor: {@code fact_envio} tiene {@code costo} y
 * ClickHouse no tiene GRANT por columna. La hace la CONSULTA, que no selecciona
 * ningún importe en esos tres. La serie del costo sí lo selecciona, y por eso va
 * cerrada a ADMIN/GERENTE en su propia línea de {@code SecurityConfig}.
 *
 * <h2>Cada informe declara sobre cuántos envíos midió</h2>
 * Lección heredada de LOG-12 y confirmada por los datos de esta fase: de los
 * 2.872 envíos, solo 2.727 tienen entrega real y 2.723 tienen además fecha
 * prometida. Los 145 restantes son los `devuelto` y los `en_transito`: nunca
 * llegaron. Un tránsito promediado sobre 2.872 estaría diluido por envíos que no
 * ocurrieron, así que cada fila trae {@code medidos} y {@code cobertura_pct}.
 *
 * Ninguno de estos métodos lleva {@code @Transactional}: no tocan PostgreSQL.
 */
@Service
public class InformesLogisticaCompuestosService extends InformeCompuestoServiceBase {

    private static final String TABLA = "fact_pedido";
    private static final String TABLA_ENVIO = "fact_envio";
    private static final String TABLA_NOVEDAD = "fact_novedad_envio";

    public InformesLogisticaCompuestosService(
            @Qualifier("pgJdbcTemplate") JdbcTemplate pg,
            @Qualifier("clickHouseJdbc") JdbcTemplate ch) {
        super(pg, ch);
    }

    // ═════════════════════════════════════════════════════════════════════
    // OTD-LOG-12 — Tiempo por etapa del ciclo del pedido
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Las cuatro etapas del camino de un pedido, con su tiempo real.
     *
     * <h3>El grano de la pregunta es el PEDIDO, no la transición</h3>
     * El historial guarda 24.608 transiciones. El objetivo pregunta «cuántas
     * horas tarda un pedido del pago a la preparación», que es un atributo del
     * pedido: por eso los hitos viajan pivotados en {@code fact_pedido} y este
     * informe es un agregado directo, sin unir ninguna tabla.
     *
     * <h3>Cada etapa declara SU denominador — y ese es el punto del informe</h3>
     * No todos los pedidos recorren todos los tramos. Medido sobre los 4.083:
     *
     * <pre>
     *   pago → preparado ........ 2.868 pedidos
     *   preparado → despachado .. 2.856
     *   despachado → entregado .. 2.727
     *   ciclo completo .......... 3.696
     * </pre>
     *
     * Los 3.696 pedidos entregados superan a los 2.868 con hito de despacho:
     * 828 llegaron a destino sin que el sistema registrara su salida. Por eso
     * cada fila trae {@code pedidos_medidos} y {@code cobertura_pct}: un
     * promedio de 88 horas calculado sobre 2.727 pedidos y otro de 13 horas
     * calculado sobre 2.856 NO son comparables sin decir sobre cuántos se
     * calcularon, y presentarlos juntos sin esa columna es la manera silenciosa
     * de que el cuello de botella parezca estar donde no está.
     *
     * <h3>Promedio, mediana y p90 en la misma fila</h3>
     * El promedio de un tiempo lo dispara un pedido atascado; la mediana dice
     * lo que le pasa al pedido corriente y el p90 dice cuánto sufre el 10 % peor.
     * Un informe de cuellos de botella con solo el promedio invita a perseguir
     * el caso raro.
     *
     * <h3>Ni una columna de dinero</h3>
     * DESPACHO es destinatario de este informe (catálogo §6) y el corte
     * financiero del sistema lo deja fuera de todo lo que lleve monto. Aquí la
     * barrera NO puede ser el motor —ClickHouse no tiene GRANT por columna y
     * {@code fact_pedido} sí tiene `total`— así que la hace la CONSULTA: este
     * SQL no selecciona ningún importe. Es el mismo mecanismo declarado en
     * OTD-COM-08.
     *
     * @param desde fecha ISO del pedido; el rango se toma sobre `fecha_pedido`
     * @param hasta idem
     * @param canal web | tienda | telefono, o null = todos
     */
    public Map<String, Object> tiemposCiclo(String desde, String hasta, String canal) {
        String fDesde = fecha(desde, "desde");
        String fHasta = fecha(hasta, "hasta");
        exigirRangoValido(fDesde, fHasta);
        String fCanal = opcion(canal, CANALES, "canal");

        return ejecutar("OTD-LOG-12", () -> {
            Filtros f = new Filtros();
            f.y("es_cancelado = 0");
            // `toDate` sobre una columna DateTime('America/Guayaquil') resuelve
            // el día en la zona del negocio y cubre los dos extremos completos.
            f.y("toDate(fecha_pedido) >= toDate(?)", fDesde);
            f.y("toDate(fecha_pedido) <= toDate(?)", fHasta);
            f.y("canal = ?", fCanal);

            Map<String, Object> t = ch.queryForMap(sqlEtapas(f.where()), f.args());

            // El resultado llega ANCHO —una columna por medida y etapa— y se
            // transpone aquí a las cuatro filas que el informe muestra. Es más
            // barato que cuatro consultas con los mismos filtros repetidos, y
            // sobre todo garantiza que las cuatro etapas se midan sobre
            // exactamente el mismo conjunto de pedidos.
            List<Map<String, Object>> items = new ArrayList<>();
            int universo = ((Number) t.get("n_pedidos")).intValue();
            for (Etapa e : ETAPAS) {
                items.add(fila(e, t, universo));
            }

            Map<String, Object> sobre = sobre(items);
            conResumen(sobre, kpis(t, universo, items));
            return conMarcaDeAgua(sobre, TABLA);
        });
    }

    // ── Las cuatro etapas ────────────────────────────────────────────────

    /**
     * Una etapa del ciclo. {@code columna} es la columna de horas que la mide en
     * {@code fact_pedido}; el ETL la calculó como la diferencia entre dos hitos.
     *
     * La lista está FIJADA aquí a propósito, igual que el pivote del ETL: un
     * estado nuevo en {@code estado_pedido} obliga a tocar las dos piezas. Es el
     * precio declarado de no tener una tabla de transiciones (§5.1), y es
     * asumible porque los 11 estados están cerrados por CHECK en el motor.
     */
    private record Etapa(String clave, String etiqueta, String columna, String descripcion) {}

    private static final List<Etapa> ETAPAS = List.of(
            new Etapa("pago_preparacion", "Del pago a la preparación",
                    "horas_pago_a_preparacion",
                    "Desde que el cliente paga hasta que bodega termina el picking"),
            new Etapa("preparacion_despacho", "De la preparación al despacho",
                    "horas_preparacion_a_despacho",
                    "Desde que el pedido está preparado hasta que sale con el transportista"),
            new Etapa("despacho_entrega", "Del despacho a la entrega",
                    "horas_despacho_a_entrega",
                    "Tiempo en manos del transportista hasta la entrega al cliente"),
            new Etapa("ciclo_total", "Ciclo completo (pago → entrega)",
                    "horas_ciclo_total",
                    "El recorrido entero, para comparar contra la suma de las etapas"));

    /**
     * Un solo escaneo produce las seis medidas de las cuatro etapas.
     *
     * `quantileExactIf` y no `quantileIf`: el aproximado es más rápido y aquí no
     * hace falta — son 4.083 filas— y una mediana aproximada en un informe de
     * cuellos de botella es exactamente el tipo de número que nadie vuelve a
     * comprobar.
     */
    private static String sqlEtapas(String where) {
        StringBuilder medidas = new StringBuilder();
        for (Etapa e : ETAPAS) {
            String c = e.columna();
            medidas.append("""
                ,   countIf(%1$s IS NOT NULL)                        AS n_%2$s
                ,   round(avgIf(%1$s, %1$s IS NOT NULL), 2)          AS prom_%2$s
                ,   round(quantileExactIf(0.5)(%1$s, %1$s IS NOT NULL), 2)  AS med_%2$s
                ,   round(quantileExactIf(0.9)(%1$s, %1$s IS NOT NULL), 2)  AS p90_%2$s
                ,   round(minIf(%1$s, %1$s IS NOT NULL), 2)          AS min_%2$s
                ,   round(maxIf(%1$s, %1$s IS NOT NULL), 2)          AS max_%2$s
                """.formatted(c, e.clave()));
        }
        return """
            SELECT count() AS n_pedidos
                %s
            FROM %s.%s
            WHERE 1 %s
            """.formatted(medidas, DWH, TABLA, where);
    }

    /** Traduce las seis medidas anchas de una etapa a la fila que se pinta. */
    private static Map<String, Object> fila(Etapa e, Map<String, Object> t, int universo) {
        Map<String, Object> m = new LinkedHashMap<>();
        Number medidos = (Number) t.get("n_" + e.clave());
        int n = medidos == null ? 0 : medidos.intValue();

        m.put("etapa", e.etiqueta());
        m.put("descripcion", e.descripcion());
        m.put("pedidos_medidos", n);
        // La cobertura es la columna que impide comparar dos promedios calculados
        // sobre poblaciones distintas sin darse cuenta.
        m.put("cobertura_pct", universo == 0 ? null
                : Math.round(n * 10000.0 / universo) / 100.0);
        m.put("horas_promedio", n == 0 ? null : t.get("prom_" + e.clave()));
        m.put("horas_mediana", n == 0 ? null : t.get("med_" + e.clave()));
        m.put("horas_p90", n == 0 ? null : t.get("p90_" + e.clave()));
        m.put("horas_minimo", n == 0 ? null : t.get("min_" + e.clave()));
        m.put("horas_maximo", n == 0 ? null : t.get("max_" + e.clave()));
        m.put("dias_promedio", n == 0 ? null : redondearDias(t.get("prom_" + e.clave())));
        return m;
    }

    private static Double redondearDias(Object horas) {
        if (horas == null) {
            return null;
        }
        return Math.round(((Number) horas).doubleValue() / 24.0 * 100.0) / 100.0;
    }

    private static List<Map<String, Object>> kpis(Map<String, Object> t, int universo,
                                                  List<Map<String, Object>> items) {
        List<Map<String, Object>> k = new ArrayList<>();
        k.add(kpi("Pedidos en el período", universo, "numero"));
        for (Etapa e : ETAPAS) {
            Object prom = t.get("prom_" + e.clave());
            k.add(kpi(e.etiqueta(), prom == null ? 0 : prom, "numero"));
        }

        // El cuello de botella se declara EXCLUYENDO el ciclo completo, que por
        // definición es el más largo de los cuatro y ganaría siempre.
        Map<String, Object> peor = null;
        for (int i = 0; i < ETAPAS.size() - 1; i++) {
            Map<String, Object> fila = items.get(i);
            Object horas = fila.get("horas_promedio");
            if (horas == null) {
                continue;
            }
            if (peor == null || ((Number) horas).doubleValue()
                    > ((Number) peor.get("horas_promedio")).doubleValue()) {
                peor = fila;
            }
        }
        if (peor != null) {
            k.add(kpi("Cuello de botella", peor.get("etapa"), "texto"));
        }
        return k;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Filtros compartidos por los informes de envío
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Estados del envío — CHECK del motor en PostgreSQL.
     *
     * Se declaran completos aunque los tres informes de tiempos solo tengan
     * sentido sobre `entregado`: el filtro existe para poder mirar los `devuelto`
     * y los `en_transito` y ver que, efectivamente, no tienen medida. Un informe
     * que oculta las filas sin dato hace creer que no existen.
     */
    private static final java.util.Set<String> ESTADOS_ENVIO =
            java.util.Set.of("preparando", "listo", "en_transito", "entregado",
                    "fallido", "devuelto");

    /**
     * Los 5 tipos de incidencia — CHECK de {@code novedad_envio}, todos en uso.
     */
    private static final java.util.Set<String> TIPOS_NOVEDAD =
            java.util.Set.of("cliente_ausente", "direccion_incorrecta", "cliente_rechazo",
                    "zona_dificil_acceso", "dano_en_transito");

    /**
     * Desenlaces REALES de una novedad.
     *
     * OJO: el diseño del pipeline (§5.9) dice {@code reprogramar} /
     * {@code devolver_almacen} — esos son los verbos del API, no lo que la base
     * guarda. Lo guardado es el participio, y un filtro escrito desde el diseño
     * casa con CERO filas sin dar ningún error. Corrección C3C.3 de
     * {@code docs/estrategico/CORRECCIONES_DISENO_ETL.md}: esta lista sale de los
     * datos, no del documento.
     *
     * {@code sin_resolver} no está en el origen: lo pone el ETL sobre el NULL de
     * las 7 novedades todavía abiertas, para que se puedan filtrar y contar.
     */
    private static final java.util.Set<String> ACCIONES_NOVEDAD =
            java.util.Set.of("reprogramada", "devuelto_almacen", "sin_resolver");

    /**
     * Rango de despacho + zona + transportista: los filtros que comparten
     * LOG-03, LOG-04 y la serie de costo.
     *
     * La zona y el transportista se buscan por texto contenido y no por lista
     * cerrada, igual que en el OTD-LOG-11 simple: las tres zonas configuradas y
     * los cinco transportistas cambian con los contratos, y una lista blanca de
     * nombres propios envejece mal. El texto del usuario viaja SIEMPRE como
     * parámetro.
     */
    private Filtros filtrosEnvio(String desde, String hasta, String zona, String transportista) {
        Filtros f = new Filtros();
        f.y("toDate(fecha_despacho) >= toDate(?)", fecha(desde, "desde"));
        f.y("toDate(fecha_despacho) <= toDate(?)", fecha(hasta, "hasta"));
        f.y("positionCaseInsensitive(zona, ?) > 0", texto(zona));
        f.y("positionCaseInsensitive(transportista, ?) > 0", texto(transportista));
        return f;
    }

    // ═════════════════════════════════════════════════════════════════════
    // OTD-LOG-03 — Cumplimiento de la fecha prometida al cliente
    // ═════════════════════════════════════════════════════════════════════

    /**
     * De los envíos ya entregados, cuántos llegaron a más tardar el día
     * prometido y cuántos llegaron tarde, por transportista.
     *
     * <h3>El denominador es 2.723 y no 2.872 — y la diferencia no es redondeo</h3>
     * Solo se puede juzgar el cumplimiento de una promesa cuando existen las DOS
     * fechas: la prometida y la real. Medido sobre el almacén completo:
     *
     * <pre>
     *   envíos totales ....................... 2.872
     *   con entrega real ..................... 2.727   ← los `entregado`
     *   con entrega real Y fecha prometida ... 2.723   ← sobre estos se juzga
     * </pre>
     *
     * Los 145 que faltan son los 120 `devuelto` y los 25 `en_transito`: no es que
     * llegaran tarde, es que no llegaron. Contarlos como incumplimiento sería
     * mentir en una dirección; ignorarlos en silencio, en la otra. Por eso el
     * veredicto {@code entregado_a_tiempo} viaja NULL cuando falta cualquiera de
     * las dos fechas —un 0 diría «llegó tarde», que no es «no se sabe»— y cada
     * fila declara su {@code cobertura_pct}.
     *
     * <h3>El día se resolvió en la zona del negocio</h3>
     * Corrección C3C.1: el veredicto compara un {@code timestamptz} contra un
     * {@code date}, y el día del primero depende de la zona horaria. En UTC, dos
     * envíos cambian de lado. El ETL ya dejó la comparación resuelta en
     * {@code entregado_a_tiempo}, así que este informe no vuelve a restar fechas:
     * lee el veredicto. Es justamente para eso que la transformación cara vive en
     * el ETL y no en la consulta.
     *
     * <h3>Sin una sola columna de monto</h3>
     * DESPACHO es destinatario. La barrera es esta consulta, no el motor.
     */
    public Map<String, Object> cumplimientoPromesa(String desde, String hasta, String zona,
                                                   String transportista, String estado) {
        exigirRangoValido(fecha(desde, "desde"), fecha(hasta, "hasta"));
        String fEstado = opcion(estado, ESTADOS_ENVIO, "estado");

        return ejecutar("OTD-LOG-03", () -> {
            Filtros f = filtrosEnvio(desde, hasta, zona, transportista);
            f.y("estado = ?", fEstado);

            List<Map<String, Object>> items = ch.queryForList("""
                SELECT transportista,
                       count()                                 AS envios,
                       countIf(entregado_a_tiempo IS NOT NULL)  AS medidos,
                       countIf(entregado_a_tiempo = 1)          AS a_tiempo,
                       countIf(entregado_a_tiempo = 0)          AS tarde,
                       round(countIf(entregado_a_tiempo = 1) * 100.0
                             / nullIf(countIf(entregado_a_tiempo IS NOT NULL), 0), 2)
                                                                AS pct_a_tiempo,
                       round(avgIf(dias_desvio_promesa, dias_desvio_promesa IS NOT NULL), 2)
                                                                AS desvio_medio,
                       quantileExactIf(0.5)(dias_desvio_promesa,
                                            dias_desvio_promesa IS NOT NULL)
                                                                AS desvio_mediana,
                       maxIf(dias_desvio_promesa, dias_desvio_promesa IS NOT NULL)
                                                                AS peor_retraso,
                       countIf(dias_desvio_promesa < 0)         AS adelantados
                FROM %s.%s
                WHERE 1 %s
                GROUP BY transportista
                ORDER BY envios DESC
                """.formatted(DWH, TABLA_ENVIO, f.where()), f.args());

            // La cobertura se calcula aquí y no en SQL para dejarla en la misma
            // forma que LOG-12: medidos sobre el universo de SU fila.
            for (Map<String, Object> fila : items) {
                fila.remove("ratio_interno");
                fila.put("cobertura_pct", porcentaje(fila.get("medidos"), fila.get("envios")));
            }

            Map<String, Object> sobre = sobre(items);
            conResumen(sobre, kpisPromesa(f, items));
            return conMarcaDeAgua(sobre, TABLA_ENVIO);
        });
    }

    private List<Map<String, Object>> kpisPromesa(Filtros f, List<Map<String, Object>> items) {
        Map<String, Object> t = ch.queryForMap("""
            SELECT count()                                AS n_envios,
                   countIf(entregado_a_tiempo IS NOT NULL) AS n_medidos,
                   countIf(entregado_a_tiempo = 1)         AS n_a_tiempo,
                   countIf(entregado_a_tiempo = 0)         AS n_tarde,
                   round(countIf(entregado_a_tiempo = 1) * 100.0
                         / nullIf(countIf(entregado_a_tiempo IS NOT NULL), 0), 2) AS t_pct,
                   round(avgIf(dias_desvio_promesa, dias_desvio_promesa > 0), 2)  AS t_retraso
            FROM %s.%s WHERE 1 %s
            """.formatted(DWH, TABLA_ENVIO, f.where()), f.args());

        List<Map<String, Object>> k = new ArrayList<>();
        k.add(kpi("Envíos en el período", t.get("n_envios"), "numero"));
        k.add(kpi("Con promesa medible", t.get("n_medidos"), "numero"));
        k.add(kpi("Llegaron a tiempo", t.get("n_a_tiempo"), "numero"));
        k.add(kpi("Llegaron tarde", t.get("n_tarde"), "numero"));
        k.add(kpi("Cumplimiento", t.get("t_pct"), "porcentaje"));
        k.add(kpi("Retraso medio del que llega tarde", t.get("t_retraso"), "numero"));

        mejorPeor(items, "pct_a_tiempo", "transportista", k,
                "Transportista más puntual", "Transportista menos puntual");
        return k;
    }

    // ═════════════════════════════════════════════════════════════════════
    // OTD-LOG-04 — Días reales de tránsito
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Los días que pasan desde que el paquete sale de bodega hasta la puerta del
     * cliente, sin importar qué fecha se prometió.
     *
     * <h3>Por qué un filtro de agrupación y no tres informes</h3>
     * El catálogo pide el tránsito «por transportista <b>y período</b>», que son
     * dos cortes de la misma medida. En vez de duplicar el informe se expone un
     * filtro {@code agrupar} con lista blanca de TRES cortes —transportista, mes
     * y zona—, y la primera columna se llama {@code grupo} en los tres casos para
     * que la pantalla genérica no necesite saber cuál está activo.
     *
     * La lista blanca no es un adorno: la expresión de agrupación entra en el SQL
     * por concatenación, así que es obligatorio que provenga del código y nunca
     * del texto del usuario. Un valor no previsto sale como 400.
     *
     * <h3>Promedio, mediana y p90 juntos</h3>
     * Mismo criterio que LOG-12. El promedio de Servientrega es 4,10 días y su
     * p90 es 7: la diferencia entre esas dos cifras es la conversación real sobre
     * un transportista, y solo con el promedio no existe.
     *
     * <h3>El día se resolvió en America/Guayaquil — C3C.1</h3>
     * Es la corrección más cara de esta fase y cae justo en este informe: 569 de
     * los 2.727 envíos (20,9 %) tienen un tránsito distinto si el día se resuelve
     * en UTC, y el promedio se movería de 3,98 a 3,77 días. El ETL ya restó con
     * la zona explícita; aquí solo se agrega.
     */
    public Map<String, Object> diasTransito(String desde, String hasta, String zona,
                                            String transportista, String agrupar) {
        exigirRangoValido(fecha(desde, "desde"), fecha(hasta, "hasta"));
        String corte = opcion(agrupar, AGRUPACIONES.keySet(), "agrupar");
        String claveCorte = corte == null ? "transportista" : corte;

        return ejecutar("OTD-LOG-04", () -> {
            Filtros f = filtrosEnvio(desde, hasta, zona, transportista);
            Agrupacion a = AGRUPACIONES.get(claveCorte);

            List<Map<String, Object>> items = ch.queryForList("""
                SELECT %1$s                                  AS grupo,
                       count()                               AS envios,
                       countIf(dias_transito IS NOT NULL)     AS medidos,
                       round(avgIf(dias_transito, dias_transito IS NOT NULL), 2)
                                                             AS dias_promedio,
                       quantileExactIf(0.5)(dias_transito, dias_transito IS NOT NULL)
                                                             AS dias_mediana,
                       quantileExactIf(0.9)(dias_transito, dias_transito IS NOT NULL)
                                                             AS dias_p90,
                       minIf(dias_transito, dias_transito IS NOT NULL) AS dias_minimo,
                       maxIf(dias_transito, dias_transito IS NOT NULL) AS dias_maximo,
                       countIf(fecha_entrega_real IS NULL)   AS sin_llegar
                FROM %2$s.%3$s
                WHERE 1 %4$s
                GROUP BY grupo
                ORDER BY %5$s
                """.formatted(a.expresion(), DWH, TABLA_ENVIO, f.where(), a.orden()), f.args());

            for (Map<String, Object> fila : items) {
                fila.put("cobertura_pct", porcentaje(fila.get("medidos"), fila.get("envios")));
            }

            Map<String, Object> sobre = sobre(items);
            sobre.put("agrupacion", a.etiqueta());
            conResumen(sobre, kpisTransito(f, items, a));
            return conMarcaDeAgua(sobre, TABLA_ENVIO);
        });
    }

    /**
     * Un corte de la serie de tránsito. {@code expresion} y {@code orden} son
     * CONSTANTES del código: entran en el SQL concatenadas y jamás pueden venir
     * del usuario, que solo elige la CLAVE del mapa.
     */
    private record Agrupacion(String expresion, String orden, String etiqueta) {}

    /**
     * El mes se formatea a texto en el propio SQL. Es la lección de
     * {@code PATRON_INFORMES.md} §11: un {@code Date} puro se serializa
     * «AAAA-MM-DD» y el formateador de la pantalla lo interpreta como UTC,
     * mostrando un día menos. En una columna de agrupación eso rotularía la serie
     * con el mes equivocado en la frontera.
     */
    private static final java.util.Map<String, Agrupacion> AGRUPACIONES =
            java.util.Map.of(
                    "transportista", new Agrupacion("transportista", "envios DESC",
                            "Transportista"),
                    "mes", new Agrupacion("formatDateTime(mes, '%Y-%m')", "grupo ASC", "Mes"),
                    "zona", new Agrupacion("zona", "envios DESC", "Zona de envío"));

    private List<Map<String, Object>> kpisTransito(Filtros f, List<Map<String, Object>> items,
                                                   Agrupacion a) {
        Map<String, Object> t = ch.queryForMap("""
            SELECT count()                            AS n_envios,
                   countIf(dias_transito IS NOT NULL)  AS n_medidos,
                   round(avgIf(dias_transito, dias_transito IS NOT NULL), 2) AS t_prom,
                   quantileExactIf(0.5)(dias_transito, dias_transito IS NOT NULL) AS t_med,
                   quantileExactIf(0.9)(dias_transito, dias_transito IS NOT NULL) AS t_p90,
                   countIf(fecha_entrega_real IS NULL) AS n_sin_llegar
            FROM %s.%s WHERE 1 %s
            """.formatted(DWH, TABLA_ENVIO, f.where()), f.args());

        List<Map<String, Object>> k = new ArrayList<>();
        k.add(kpi("Agrupado por", a.etiqueta(), "texto"));
        k.add(kpi("Envíos en el período", t.get("n_envios"), "numero"));
        k.add(kpi("Con tránsito medible", t.get("n_medidos"), "numero"));
        k.add(kpi("Días de tránsito (media)", t.get("t_prom"), "numero"));
        k.add(kpi("Días (mediana)", t.get("t_med"), "numero"));
        k.add(kpi("Días (p90)", t.get("t_p90"), "numero"));
        k.add(kpi("Nunca llegaron", t.get("n_sin_llegar"), "numero"));

        // Aquí el MEJOR es el MENOR: menos días de tránsito es mejor servicio.
        mejorPeor(items, "dias_promedio", "grupo", k, "Más lento", "Más rápido");
        return k;
    }

    // ═════════════════════════════════════════════════════════════════════
    // OTD-LOG-05 — Problemas de entrega: tipo, intentos y desenlace
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Las incidencias de la última milla, por tipo y por cómo terminaron.
     *
     * <h3>Los desenlaces son los que guarda la base, no los que dice el diseño</h3>
     * Corrección C3C.3. §5.9 del diseño especifica los valores
     * {@code reprogramar} / {@code devolver_almacen}, que son los verbos de las
     * operaciones del API. Lo guardado es el participio:
     *
     * <pre>
     *   devuelto_almacen ..... 120     reprogramada ..... 49     (NULL) ..... 7
     * </pre>
     *
     * Filtrar por {@code devolver_almacen} devuelve CERO filas sin dar error, y
     * el informe diría que las incidencias se resuelven reprogramando siempre —
     * ocultando justo la categoría que acaba en venta perdida, que es el 68 %.
     *
     * <h3>Las 7 abiertas se muestran, no se descartan</h3>
     * Son las que no tienen {@code horas_hasta_resolucion}. Descartarlas daría un
     * tiempo medio de resolución calculado solo sobre lo que sí se cerró — el
     * sesgo de mirar únicamente lo que terminó. Van con el desenlace
     * {@code sin_resolver}, que pone el ETL, y su columna de horas queda vacía en
     * vez de en cero.
     *
     * <h3>Los intentos son la otra mitad de la pregunta</h3>
     * {@code intento_numero} va de 1 a 3, coherente con el tope que impone
     * {@code VentasService} (intentos = 1 + reprogramaciones). Las que acaban en
     * devolución al almacén llegan al intento 3; las reprogramadas se quedan en 2.
     */
    public Map<String, Object> novedadesEntrega(String desde, String hasta, String tipo,
                                                String accion, String transportista) {
        exigirRangoValido(fecha(desde, "desde"), fecha(hasta, "hasta"));
        String fTipo = opcion(tipo, TIPOS_NOVEDAD, "tipo");
        String fAccion = opcion(accion, ACCIONES_NOVEDAD, "accion");

        return ejecutar("OTD-LOG-05", () -> {
            Filtros f = new Filtros();
            f.y("toDate(fecha_registro) >= toDate(?)", fecha(desde, "desde"));
            f.y("toDate(fecha_registro) <= toDate(?)", fecha(hasta, "hasta"));
            f.y("tipo = ?", fTipo);
            f.y("accion = ?", fAccion);
            f.y("positionCaseInsensitive(transportista, ?) > 0", texto(transportista));

            List<Map<String, Object>> items = ch.queryForList("""
                SELECT tipo,
                       accion,
                       count()                    AS novedades,
                       countIf(resuelta = 1)      AS resueltas,
                       countIf(resuelta = 0)      AS abiertas,
                       countDistinct(envio_id)    AS envios,
                       round(avg(intento_numero), 2) AS intento_medio,
                       max(intento_numero)        AS intento_max,
                       countIf(intento_numero >= 3) AS en_tercer_intento,
                       round(avgIf(horas_hasta_resolucion, resuelta = 1), 2) AS horas_medias,
                       round(maxIf(horas_hasta_resolucion, resuelta = 1), 2) AS horas_maximo
                FROM %s.%s
                WHERE 1 %s
                GROUP BY tipo, accion
                ORDER BY novedades DESC
                """.formatted(DWH, TABLA_NOVEDAD, f.where()), f.args());

            Map<String, Object> sobre = sobre(items);
            conResumen(sobre, kpisNovedades(f));
            return conMarcaDeAgua(sobre, TABLA_NOVEDAD);
        });
    }

    private List<Map<String, Object>> kpisNovedades(Filtros f) {
        Map<String, Object> t = ch.queryForMap("""
            SELECT count()                                  AS n_novedades,
                   countDistinct(envio_id)                   AS n_envios,
                   countIf(resuelta = 1)                     AS n_resueltas,
                   countIf(resuelta = 0)                     AS n_abiertas,
                   countIf(accion = 'devuelto_almacen')      AS n_devueltas,
                   round(countIf(accion = 'devuelto_almacen') * 100.0
                         / nullIf(count(), 0), 2)            AS t_pct_devuelta,
                   round(avgIf(horas_hasta_resolucion, resuelta = 1), 2) AS t_horas,
                   round(avg(intento_numero), 2)             AS t_intentos
            FROM %s.%s WHERE 1 %s
            """.formatted(DWH, TABLA_NOVEDAD, f.where()), f.args());

        List<Map<String, Object>> k = new ArrayList<>();
        k.add(kpi("Novedades registradas", t.get("n_novedades"), "numero"));
        k.add(kpi("Envíos afectados", t.get("n_envios"), "numero"));
        k.add(kpi("Resueltas", t.get("n_resueltas"), "numero"));
        k.add(kpi("Todavía abiertas", t.get("n_abiertas"), "numero"));
        k.add(kpi("Acabaron devueltas al almacén", t.get("n_devueltas"), "numero"));
        k.add(kpi("Terminan en devolución", t.get("t_pct_devuelta"), "porcentaje"));
        k.add(kpi("Horas hasta resolver (media)", t.get("t_horas"), "numero"));
        k.add(kpi("Intentos por novedad (media)", t.get("t_intentos"), "numero"));
        return k;
    }

    // ═════════════════════════════════════════════════════════════════════
    // SERIE TEMPORAL DEL COSTO DE ENVÍO — la evolución que LOG-11 no da
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Cuánto cuesta el transporte MES A MES, con su costo por kilo.
     *
     * <h3>Qué añade sobre OTD-LOG-11, que ya existe</h3>
     * LOG-11 (simple, PostgreSQL) da la FOTO: costo por zona y transportista
     * agregado del período elegido. Responde «¿dónde nos cuesta más caro?». Éste
     * da la SERIE: responde «¿se está encareciendo?», que es una pregunta
     * distinta y la que el catálogo dejó anotada como pendiente para ClickHouse
     * al reclasificar LOG-11 a simple. El diseño (§5.8) previó que
     * {@code fact_envio} la sirve sin tabla adicional — y así es: un
     * {@code GROUP BY mes}, sin un solo JOIN.
     *
     * <h3>Los 24 envíos sin tarifar se EXCLUYEN del promedio y se declaran — C3C.2</h3>
     * Hay 24 envíos con {@code costo = 0} y {@code peso_total_kg} nulo: los
     * primeros de la tabla, creados a mano durante el desarrollo antes de que el
     * cálculo de tarifa existiera. <b>No son envíos gratuitos, son envíos sin
     * tarifar</b>, y los 24 caen dentro de julio de 2026:
     *
     * <pre>
     *   costo medio de julio 2026 con los 24 ceros dentro .... $7,5930
     *   costo medio de julio 2026 sin ellos .................. $9,7369   (−22,0 %)
     * </pre>
     *
     * El promedio los excluye vía la marca {@code sin_tarifa} que puso el ETL, y
     * la columna {@code sin_tarifa} de cada fila dice cuántos se excluyeron. El
     * total acumulado SÍ los incluye —sumar cero no distorsiona una suma— para
     * que el {@code costo_total} siga cuadrando con los $32.723,25 del sistema.
     *
     * <h3>Este SÍ lleva dinero: DESPACHO queda fuera</h3>
     * Al revés que los otros tres de esta fase. El corte lo hace la RUTA, como en
     * el LOG-11 simple: {@code grp_despacho} conserva SELECT sobre
     * {@code envio.costo} —lo escribe al despachar— así que el motor no lo
     * impediría, y en ClickHouse directamente no hay GRANT por columna.
     */
    public Map<String, Object> costoEnvioMensual(String desde, String hasta, String zona,
                                                 String transportista) {
        exigirRangoValido(fecha(desde, "desde"), fecha(hasta, "hasta"));

        return ejecutar("OTD-LOG-11-serie", () -> {
            Filtros f = filtrosEnvio(desde, hasta, zona, transportista);

            List<Map<String, Object>> items = ch.queryForList("""
                SELECT formatDateTime(mes, '%%Y-%%m')     AS periodo,
                       count()                            AS envios,
                       countIf(sin_tarifa = 1)            AS sin_tarifar,
                       sum(costo)                         AS costo_total,
                       round(avgIf(costo, sin_tarifa = 0), 2) AS costo_medio,
                       sum(peso_total_kg)                 AS peso_total,
                       round(avgIf(costo_por_kg, costo_por_kg IS NOT NULL), 4) AS costo_por_kg,
                       countDistinct(transportista)       AS transportistas,
                       countDistinct(zona)                AS zonas,
                       countIf(estado = 'entregado')      AS entregados
                FROM %s.%s
                WHERE 1 %s
                GROUP BY mes
                ORDER BY mes ASC
                """.formatted(DWH, TABLA_ENVIO, f.where()), f.args());

            Map<String, Object> sobre = sobre(items);
            // La salvedad es el mismo mecanismo que OTD-INV-09: una limitación
            // del dato se DICE en la pantalla, no se deja para el que audite.
            sobre.put("salvedad",
                    "El costo medio excluye los envíos sin tarifar (columna «Sin tarifa»): "
                    + "son envíos creados antes de que el cálculo de tarifa por zona y peso "
                    + "existiera, y su costo de 0 no significa que el envío fuera gratis. "
                    + "El costo total sí los incluye, para que cuadre con el sistema.");
            conResumen(sobre, kpisCosto(f, items));
            return conMarcaDeAgua(sobre, TABLA_ENVIO);
        });
    }

    private List<Map<String, Object>> kpisCosto(Filtros f, List<Map<String, Object>> items) {
        Map<String, Object> t = ch.queryForMap("""
            SELECT count()                    AS n_envios,
                   countIf(sin_tarifa = 1)     AS n_sin_tarifa,
                   countDistinct(mes)          AS n_meses,
                   sum(costo)                  AS t_costo,
                   round(avgIf(costo, sin_tarifa = 0), 2) AS t_medio,
                   sum(peso_total_kg)          AS t_peso,
                   round(avgIf(costo_por_kg, costo_por_kg IS NOT NULL), 4) AS t_por_kg
            FROM %s.%s WHERE 1 %s
            """.formatted(DWH, TABLA_ENVIO, f.where()), f.args());

        List<Map<String, Object>> k = new ArrayList<>();
        k.add(kpi("Meses en la serie", t.get("n_meses"), "numero"));
        k.add(kpi("Envíos", t.get("n_envios"), "numero"));
        k.add(kpi("Costo total del transporte", t.get("t_costo"), "moneda"));
        k.add(kpi("Costo medio por envío", t.get("t_medio"), "moneda"));
        k.add(kpi("Costo medio por kilo", t.get("t_por_kg"), "moneda"));
        k.add(kpi("Kilos transportados", t.get("t_peso"), "numero"));
        k.add(kpi("Envíos sin tarifar (excluidos del promedio)", t.get("n_sin_tarifa"), "numero"));

        mejorPeor(items, "costo_medio", "periodo", k, "Mes más caro", "Mes más barato");
        return k;
    }

    // ═════════════════════════════════════════════════════════════════════
    // FASE 4 — la posventa: LOG-07, LOG-08, LOG-09 y LOG-10
    // ═════════════════════════════════════════════════════════════════════

    private static final String TABLA_DEV       = "fact_devolucion";
    private static final String TABLA_DEV_LINEA = "fact_devolucion_linea";

    /** Los 9 estados del ciclo RMA (CHECK de {@code devolucion}). */
    private static final java.util.Set<String> ESTADOS_DEVOLUCION =
            java.util.Set.of("solicitada", "en_revision", "aprobada", "rechazada",
                    "en_transito", "recibida", "inspeccionada", "reembolsada", "cerrada");

    /** Filtros que comparten los cuatro informes de devolución. */
    private Filtros filtrosDevolucion(String desde, String hasta, String estado,
                                      String motivo) {
        Filtros f = new Filtros();
        f.y("toDate(fecha_solicitud) >= toDate(?)", fecha(desde, "desde"));
        f.y("toDate(fecha_solicitud) <= toDate(?)", fecha(hasta, "hasta"));
        f.y("estado = ?", opcion(estado, ESTADOS_DEVOLUCION, "estado"));
        f.y("positionCaseInsensitive(motivo, ?) > 0", texto(motivo));
        return f;
    }

    // ── OTD-LOG-07 · Días de ciclo de la devolución ──────────────────────

    /** Ejes por los que se puede cortar el ciclo del RMA. */
    private static final java.util.Set<String> EJES_CICLO =
            java.util.Set.of("mes", "motivo", "estado");

    /**
     * Cuánto tarda una devolución, tramo por tramo.
     *
     * <h3>El ciclo COMPLETO solo es medible en 35 de 196 — y se dice</h3>
     * §5.10 define el ciclo total como «cierre − solicitud». Medido contra la
     * base, ese cierre existe en muy pocas:
     *
     * <pre>
     *   devoluciones ................... 196
     *   con hito 'cerrada' .............  35   ← 17,9 %
     *   con desenlace TERMINAL .........  53   (35 cerradas + 18 rechazadas)
     * </pre>
     *
     * Una devolución <b>rechazada</b> también terminó: el estado es terminal
     * por diseño del RMA. Medir el ciclo solo sobre las cerradas descarta 18
     * desenlaces reales y, peor, descarta los más RÁPIDOS —rechazar no exige
     * recibir la mercancía—, así que el promedio saldría inflado.
     *
     * Por eso el informe muestra <b>las dos</b> medidas con su base: el ciclo
     * hasta el cierre (la del diseño) y el ciclo hasta el desenlace
     * (corrección C4.2). Cada fila declara sobre cuántas devoluciones se
     * calculó cada una.
     *
     * <h3>Cada tramo tiene su propio denominador</h3>
     * Misma lección que LOG-12 (C2.7): aprobación 143, tránsito de retorno
     * 121, inspección 107, reembolso 86. Cuatro medias puestas en fila sobre
     * poblaciones distintas hacen que el cuello de botella parezca estar donde
     * no está, así que cada una viaja con su {@code n_}.
     */
    public Map<String, Object> cicloDevolucion(String desde, String hasta, String estado,
                                               String motivo, String agrupar) {
        exigirRangoValido(fecha(desde, "desde"), fecha(hasta, "hasta"));
        String eje = agrupar == null || agrupar.isBlank()
                ? "mes" : opcion(agrupar, EJES_CICLO, "agrupar");

        return ejecutar("OTD-LOG-07", () -> {
            Filtros f = filtrosDevolucion(desde, hasta, estado, motivo);
            String clave = switch (eje) {
                case "motivo" -> "motivo";
                case "estado" -> "estado";
                default       -> "formatDateTime(mes, '%Y-%m')";
            };

            List<Map<String, Object>> items = ch.queryForList("""
                SELECT %s                                          AS etiqueta,
                       count()                                     AS devoluciones,
                       countIf(es_terminal = 1)                    AS terminadas,
                       -- OJO con los alias: en ClickHouse un agregado NO puede
                       -- llamarse igual que la columna que agrega
                       -- (ILLEGAL_AGGREGATION, lección de la Fase 1). De ahí
                       -- que la media de `dias_hasta_desenlace` se llame
                       -- `dias_desenlace` y no como su columna.
                       countIf(dias_ciclo_total IS NOT NULL)       AS n_cierre,
                       round(avgIf(dias_ciclo_total,
                             dias_ciclo_total IS NOT NULL), 2)     AS dias_cierre,
                       countIf(dias_hasta_desenlace IS NOT NULL)   AS n_desenlace,
                       round(avgIf(dias_hasta_desenlace,
                             dias_hasta_desenlace IS NOT NULL), 2) AS dias_desenlace,
                       quantileExactIf(0.5)(dias_hasta_desenlace,
                             dias_hasta_desenlace IS NOT NULL)     AS mediana_desenlace,
                       countIf(dias_hasta_aprobacion IS NOT NULL)  AS n_aprobacion,
                       round(avgIf(dias_hasta_aprobacion,
                             dias_hasta_aprobacion IS NOT NULL), 2) AS dias_aprobacion,
                       countIf(dias_transito_retorno IS NOT NULL)  AS n_transito,
                       round(avgIf(dias_transito_retorno,
                             dias_transito_retorno IS NOT NULL), 2) AS dias_transito,
                       countIf(dias_hasta_inspeccion IS NOT NULL)  AS n_inspeccion,
                       round(avgIf(dias_hasta_inspeccion,
                             dias_hasta_inspeccion IS NOT NULL), 2) AS dias_inspeccion,
                       countIf(dias_hasta_reembolso IS NOT NULL)   AS n_reembolso,
                       round(avgIf(dias_hasta_reembolso,
                             dias_hasta_reembolso IS NOT NULL), 2) AS dias_reembolso,
                       maxIf(dias_hasta_desenlace,
                             dias_hasta_desenlace IS NOT NULL)     AS peor_caso
                FROM %s.%s
                WHERE 1 %s
                GROUP BY etiqueta
                ORDER BY etiqueta
                """.formatted(clave, DWH, TABLA_DEV, f.where()), f.args());

            for (Map<String, Object> fila : items) {
                fila.put("cobertura_pct",
                        porcentaje(fila.get("n_desenlace"), fila.get("devoluciones")));
            }

            Map<String, Object> sobre = sobre(items);
            conResumen(sobre, kpisCiclo(f));
            sobre.put("salvedad",
                    "El «ciclo hasta el cierre» es la medida del catálogo y solo existe "
                    + "en las devoluciones que llegaron a 'cerrada' — 35 de 196 en el "
                    + "conjunto completo. El «ciclo hasta el desenlace» añade las "
                    + "RECHAZADAS, que también terminaron, y mide 53. Cada columna trae "
                    + "su propio n: son promedios sobre poblaciones distintas y no se "
                    + "pueden comparar entre sí sin mirarlo.");
            return conMarcaDeAgua(sobre, TABLA_DEV);
        });
    }

    private List<Map<String, Object>> kpisCiclo(Filtros f) {
        Map<String, Object> t = ch.queryForMap("""
            SELECT count()                                       AS devoluciones,
                   countIf(es_terminal = 1)                      AS terminadas,
                   countIf(dias_ciclo_total IS NOT NULL)         AS n_cierre,
                   round(avgIf(dias_ciclo_total,
                         dias_ciclo_total IS NOT NULL), 2)       AS dias_cierre,
                   countIf(dias_hasta_desenlace IS NOT NULL)     AS n_desenlace,
                   round(avgIf(dias_hasta_desenlace,
                         dias_hasta_desenlace IS NOT NULL), 2)   AS dias_desenlace,
                   round(avgIf(dias_hasta_aprobacion,
                         dias_hasta_aprobacion IS NOT NULL), 2)  AS dias_aprobacion,
                   round(avgIf(dias_transito_retorno,
                         dias_transito_retorno IS NOT NULL), 2)  AS dias_transito,
                   round(avgIf(dias_hasta_reembolso,
                         dias_hasta_reembolso IS NOT NULL), 2)   AS dias_reembolso
            FROM %s.%s WHERE 1 %s
            """.formatted(DWH, TABLA_DEV, f.where()), f.args());

        List<Map<String, Object>> k = new ArrayList<>();
        k.add(kpi("Devoluciones", t.get("devoluciones"), "numero"));
        k.add(kpi("Con desenlace", t.get("terminadas"), "numero"));
        k.add(kpi("Ciclo hasta el cierre", t.get("dias_cierre"), "dias"));
        k.add(kpi("Medidas para el cierre", t.get("n_cierre"), "numero"));
        k.add(kpi("Ciclo hasta el desenlace", t.get("dias_desenlace"), "dias"));
        k.add(kpi("Medidas para el desenlace", t.get("n_desenlace"), "numero"));
        k.add(kpi("Hasta aprobar", t.get("dias_aprobacion"), "dias"));
        k.add(kpi("Tránsito de retorno", t.get("dias_transito"), "dias"));
        k.add(kpi("Hasta reembolsar", t.get("dias_reembolso"), "dias"));
        return k;
    }

    // ── OTD-LOG-08 · Por qué devuelven y qué pasa con la mercancía ───────

    /**
     * Motivo del cliente contra destino de la mercancía.
     *
     * <h3>«Sin inspeccionar» es una categoría, no un hueco</h3>
     * 112 de las 274 líneas no tienen resultado de inspección porque su
     * devolución todavía no llegó a bodega. Ocultarlas haría creer que toda la
     * mercancía devuelta ya se revisó y que el 100 % tiene destino conocido.
     *
     * <h3>Solo lo apto reingresa — y el ETL ya lo decidió</h3>
     * {@code reingresa_stock} viene precalculado en la tabla porque es LA
     * regla del RMA, no un filtro de conveniencia: lo {@code defectuoso} va al
     * pool de devolución al proveedor y lo {@code rechazado} no genera ni
     * reembolso ni stock. Escribir aquí «todo lo que no fue rechazado vuelve»
     * daría 156 líneas donde son 119 (corrección análoga a C3B.1).
     *
     * <h3>BODEGA entra en CANTIDADES</h3>
     * El catálogo la nombra destinataria «en cantidades». Este SQL no
     * selecciona ni un importe, así que la barrera del corte financiero es la
     * CONSULTA — mismo mecanismo que OTD-COM-08 y OTD-LOG-12: la tabla de
     * origen sí tiene {@code monto_linea} y ClickHouse no tiene GRANT por
     * columna.
     */
    public Map<String, Object> motivosDevolucion(String desde, String hasta, String motivo,
                                                 String resultado, String categoria) {
        exigirRangoValido(fecha(desde, "desde"), fecha(hasta, "hasta"));
        String fResultado = opcion(resultado, RESULTADOS_INSPECCION, "resultado");

        return ejecutar("OTD-LOG-08", () -> {
            Filtros f = new Filtros();
            f.y("toDate(fecha_solicitud) >= toDate(?)", fecha(desde, "desde"));
            f.y("toDate(fecha_solicitud) <= toDate(?)", fecha(hasta, "hasta"));
            f.y("positionCaseInsensitive(motivo, ?) > 0", texto(motivo));
            f.y("resultado_inspeccion = ?", fResultado);
            f.y("positionCaseInsensitive(categoria, ?) > 0", texto(categoria));

            List<Map<String, Object>> items = ch.queryForList("""
                SELECT motivo,
                       resultado_inspeccion                        AS resultado,
                       count()                                     AS lineas,
                       sum(cantidad)                               AS unidades,
                       countDistinct(devolucion_id)                AS devoluciones,
                       countDistinct(producto_variante_id)         AS productos,
                       sum(unidades_reingresadas)                  AS uds_reingresadas,
                       countIf(reingresa_stock = 1)                AS lineas_reingresadas,
                       round(sum(unidades_reingresadas) * 100.0
                             / nullIf(sum(cantidad), 0), 2)        AS pct_reingreso,
                       countIf(inspeccionada = 0)                  AS sin_inspeccionar
                FROM %s.%s
                WHERE 1 %s
                GROUP BY motivo, resultado
                ORDER BY unidades DESC, motivo, resultado
                """.formatted(DWH, TABLA_DEV_LINEA, f.where()), f.args());

            Map<String, Object> sobre = sobre(items);
            conResumen(sobre, kpisMotivos(f));
            return conMarcaDeAgua(sobre, TABLA_DEV_LINEA);
        });
    }

    /**
     * Los 3 resultados del CHECK + la etiqueta que pone el ETL sobre el NULL.
     * {@code sin_inspeccionar} NO está en el origen: es la línea que aún no
     * pasó por bodega, y tiene que poder filtrarse.
     */
    private static final java.util.Set<String> RESULTADOS_INSPECCION =
            java.util.Set.of("apto_reventa", "defectuoso", "rechazado", "sin_inspeccionar");

    private List<Map<String, Object>> kpisMotivos(Filtros f) {
        Map<String, Object> t = ch.queryForMap("""
            SELECT count()                              AS lineas,
                   sum(cantidad)                        AS unidades,
                   countDistinct(devolucion_id)         AS devoluciones,
                   countIf(inspeccionada = 1)           AS inspeccionadas,
                   sum(unidades_reingresadas)           AS uds_reingresadas,
                   sumIf(cantidad, resultado_inspeccion = 'defectuoso')  AS uds_defectuosas,
                   sumIf(cantidad, resultado_inspeccion = 'rechazado')   AS uds_rechazadas,
                   sumIf(cantidad, inspeccionada = 0)   AS uds_pendientes,
                   countDistinct(motivo)                AS motivos
            FROM %s.%s WHERE 1 %s
            """.formatted(DWH, TABLA_DEV_LINEA, f.where()), f.args());

        List<Map<String, Object>> k = new ArrayList<>();
        k.add(kpi("Líneas devueltas", t.get("lineas"), "numero"));
        k.add(kpi("Unidades", t.get("unidades"), "numero"));
        k.add(kpi("Devoluciones", t.get("devoluciones"), "numero"));
        k.add(kpi("Motivos distintos", t.get("motivos"), "numero"));
        k.add(kpi("Vuelven al stock", t.get("uds_reingresadas"), "numero"));
        k.add(kpi("Defectuosas (al proveedor)", t.get("uds_defectuosas"), "numero"));
        k.add(kpi("Rechazadas (sin reembolso)", t.get("uds_rechazadas"), "numero"));
        k.add(kpi("Pendientes de inspección", t.get("uds_pendientes"), "numero"));
        k.add(kpi("Recuperación de stock",
                porcentaje(t.get("uds_reingresadas"), t.get("unidades")), "porcentaje"));
        return k;
    }

    // ── OTD-LOG-09 · De cada 100 envíos, cuántos acaban en devolución ────

    /**
     * La tasa mensual de devolución sobre los envíos despachados.
     *
     * <h3>Es el único informe que cruza DOS tablas de hechos de fases
     * distintas</h3>
     * {@code fact_envio} (Fase 3C, 2.872 filas) en el denominador y
     * {@code fact_devolucion} (Fase 4, 196) en el numerador. Se agregan por
     * separado y se unen POR MES con un {@code UNION ALL} + {@code GROUP BY}:
     * un mes con devoluciones y sin envíos —o al revés— tiene que aparecer
     * igualmente. Con un JOIN interno el mes desaparecería y la serie tendría
     * un hueco inexplicable; con un LEFT desde el lado equivocado, la tasa de
     * ese mes saldría como si fuera cero.
     *
     * <h3>Numerador y denominador NO son la misma población</h3>
     * Se cuentan las devoluciones registradas EN el mes contra los envíos
     * despachados EN el mes, y una devolución de julio puede corresponder a un
     * envío de mayo. Es la tasa de control operativa —«cuánto me está
     * volviendo ahora»— y el informe lo declara. La versión por cohorte (de lo
     * enviado en mayo, cuánto volvió) es la que sirve OTD-VEN-14 con
     * {@code base=pedido}.
     *
     * <h3>Sin una sola columna de monto</h3>
     * DESPACHO es destinatario «en conteos» según el catálogo, y este SQL solo
     * cuenta: la barrera es la consulta.
     */
    public Map<String, Object> tasaDevolucion(String desde, String hasta, String canal) {
        exigirRangoValido(fecha(desde, "desde"), fecha(hasta, "hasta"));
        String fCanal = opcion(canal, CANALES, "canal");

        return ejecutar("OTD-LOG-09", () -> {
            // Los filtros se aplican a las DOS mitades y por eso los args van
            // duplicados: cada subconsulta lleva su propio juego.
            Filtros envios = new Filtros();
            envios.y("toDate(fecha_despacho) >= toDate(?)", fecha(desde, "desde"));
            envios.y("toDate(fecha_despacho) <= toDate(?)", fecha(hasta, "hasta"));
            envios.y("canal = ?", fCanal);

            Filtros devs = new Filtros();
            devs.y("toDate(fecha_solicitud) >= toDate(?)", fecha(desde, "desde"));
            devs.y("toDate(fecha_solicitud) <= toDate(?)", fecha(hasta, "hasta"));
            devs.y("canal_pedido = ?", fCanal);

            Object[] args = new Object[envios.args().length + devs.args().length];
            System.arraycopy(envios.args(), 0, args, 0, envios.args().length);
            System.arraycopy(devs.args(), 0, args, envios.args().length,
                    devs.args().length);

            List<Map<String, Object>> items = ch.queryForList("""
                SELECT formatDateTime(mes, '%%Y-%%m')                AS periodo,
                       envios, entregados, devoluciones, unidades_devueltas,
                       round(devoluciones * 100.0 / nullIf(envios, 0), 2)
                                                                     AS pct_sobre_envios,
                       round(devoluciones * 100.0 / nullIf(entregados, 0), 2)
                                                                     AS pct_sobre_entregados
                FROM (
                    SELECT mes,
                           sum(envios) AS envios, sum(entregados) AS entregados,
                           sum(devoluciones) AS devoluciones,
                           sum(unidades_devueltas) AS unidades_devueltas
                    FROM (
                        SELECT mes, count() AS envios,
                               countIf(estado = 'entregado') AS entregados,
                               0 AS devoluciones, 0 AS unidades_devueltas
                        FROM %s.%s WHERE 1 %s GROUP BY mes
                        UNION ALL
                        SELECT mes, 0, 0, count() AS devoluciones,
                               sum(unidades) AS unidades_devueltas
                        FROM %s.%s WHERE 1 %s GROUP BY mes
                    )
                    GROUP BY mes
                )
                ORDER BY periodo
                """.formatted(DWH, TABLA_ENVIO, envios.where(), DWH, TABLA_DEV,
                        devs.where()), args);

            Map<String, Object> sobre = sobre(items);
            conResumen(sobre, kpisTasa(items));
            sobre.put("salvedad",
                    "La tasa divide las devoluciones REGISTRADAS en el mes entre los "
                    + "envíos DESPACHADOS en ese mismo mes. No son la misma población: "
                    + "una devolución de julio puede venir de un envío de mayo. Es la "
                    + "medida de control del mes, no la calidad de lo enviado ese mes.");
            return conMarcaDeAgua(sobre, TABLA_DEV);
        });
    }

    private List<Map<String, Object>> kpisTasa(List<Map<String, Object>> items) {
        long envios = 0;
        long entregados = 0;
        long devoluciones = 0;
        Map<String, Object> peor = null;
        for (Map<String, Object> f : items) {
            envios += ((Number) f.get("envios")).longValue();
            entregados += ((Number) f.get("entregados")).longValue();
            devoluciones += ((Number) f.get("devoluciones")).longValue();
            Object pct = f.get("pct_sobre_envios");
            // Un mes con 2 envíos y 1 devolución daría 50 % y ganaría el KPI
            // sin significar nada: se exige un mínimo de volumen para el «peor».
            if (pct != null && ((Number) f.get("envios")).longValue() >= 30
                    && (peor == null || ((Number) pct).doubleValue()
                            > ((Number) peor.get("pct_sobre_envios")).doubleValue())) {
                peor = f;
            }
        }
        List<Map<String, Object>> k = new ArrayList<>();
        k.add(kpi("Meses en la serie", items.size(), "numero"));
        k.add(kpi("Envíos despachados", envios, "numero"));
        k.add(kpi("Entregados", entregados, "numero"));
        k.add(kpi("Devoluciones", devoluciones, "numero"));
        k.add(kpi("Tasa sobre envíos", porcentaje(devoluciones, envios), "porcentaje"));
        k.add(kpi("Tasa sobre entregados",
                porcentaje(devoluciones, entregados), "porcentaje"));
        if (peor != null) {
            k.add(kpi("Peor mes (≥30 envíos)", peor.get("periodo"), "texto"));
        }
        return k;
    }

    // ── OTD-LOG-10 · El dinero que se devuelve al cliente ────────────────

    /** Ejes del informe de reembolsos. */
    private static final java.util.Set<String> EJES_REEMBOLSO =
            java.util.Set.of("mes", "metodo", "motivo");

    /**
     * Reembolsos pagados: cuánto, por qué vía y por qué motivo.
     *
     * <h3>Hay DOS registros del reembolso y no dicen lo mismo — C4.1</h3>
     * <pre>
     *   devolucion.monto_reembolsado ....  86 devoluciones / $44.695,33
     *   tabla `reembolso` (tesorería) ...  85 devoluciones / $44.525,63
     * </pre>
     * La que sobra es la devolución 8 (`DV-20260716-53942`), del día en que se
     * construyó el RMA: tiene su monto en la cabecera y ningún asiento. Donde
     * existen los dos, coinciden siempre.
     *
     * <b>Este informe usa el monto de la DEVOLUCIÓN</b> (86 / $44.695,33) por
     * una razón concreta: la <i>vía</i> del reembolso —que es media pregunta
     * del objetivo— solo existe ahí. La tabla {@code reembolso} guarda el
     * asiento, no el medio. El sobre declara la elección y la columna
     * {@code con_asiento} deja ver cuántos tienen además su registro contable,
     * para que la diferencia sea consultable y no una nota al pie.
     *
     * <h3>DESPACHO fuera: esto es dinero</h3>
     * El catálogo reserva LOG-10 a Gerente, Administrador y Soporte. El corte
     * lo hace la RUTA, no el motor.
     */
    public Map<String, Object> reembolsos(String desde, String hasta, String metodo,
                                          String motivo, String agrupar) {
        exigirRangoValido(fecha(desde, "desde"), fecha(hasta, "hasta"));
        String fMetodo = opcion(metodo, METODOS_REEMBOLSO, "metodo");
        String eje = agrupar == null || agrupar.isBlank()
                ? "mes" : opcion(agrupar, EJES_REEMBOLSO, "agrupar");

        return ejecutar("OTD-LOG-10", () -> {
            Filtros f = new Filtros();
            // El período de un reembolso es el día en que se PAGÓ, no el día en
            // que se pidió la devolución: son hechos distintos y este informe
            // es de tesorería.
            f.y("monto_reembolsado > 0");
            f.y("toDate(fecha_reembolso) >= toDate(?)", fecha(desde, "desde"));
            f.y("toDate(fecha_reembolso) <= toDate(?)", fecha(hasta, "hasta"));
            f.y("metodo_reembolso = ?", fMetodo);
            f.y("positionCaseInsensitive(motivo, ?) > 0", texto(motivo));

            String clave = switch (eje) {
                case "metodo" -> "metodo_reembolso";
                case "motivo" -> "motivo";
                // El mes del PAGO, no el `mes` de la tabla (que es el de la
                // solicitud): un reembolso de agosto sobre una devolución de
                // julio es tesorería de agosto.
                default -> "formatDateTime(fecha_reembolso, '%Y-%m')";
            };

            List<Map<String, Object>> items = ch.queryForList("""
                SELECT %s                                        AS etiqueta,
                       count()                                   AS reembolsos,
                       sum(monto_reembolsado)                    AS monto,
                       round(avg(monto_reembolsado), 2)          AS monto_medio,
                       max(monto_reembolsado)                    AS mayor,
                       sum(monto_total)                          AS mercancia_devuelta,
                       round(sum(monto_reembolsado) * 100.0
                             / nullIf(sum(monto_total), 0), 2)   AS pct_sobre_devuelto,
                       countIf(reembolso_registrado = 1)         AS con_asiento,
                       countIf(reembolso_registrado = 0)         AS sin_asiento,
                       countDistinct(cliente_id)                 AS clientes,
                       round(avgIf(dias_hasta_reembolso,
                             dias_hasta_reembolso IS NOT NULL), 2) AS dias_hasta_pagar
                FROM %s.%s
                WHERE 1 %s
                GROUP BY etiqueta
                ORDER BY %s
                """.formatted(clave, DWH, TABLA_DEV, f.where(),
                        "mes".equals(eje) ? "etiqueta ASC" : "monto DESC"), f.args());

            Map<String, Object> sobre = sobre(items);
            conResumen(sobre, kpisReembolsos(f));
            sobre.put("salvedad",
                    "El importe es `devolucion.monto_reembolsado` — 86 devoluciones por "
                    + "$44.695,33 en el conjunto completo—, y no la tabla contable de "
                    + "reembolsos, que registra 85 por $44.525,63. La diferencia es UNA "
                    + "devolución legacy con monto y sin asiento. Se usa el de la "
                    + "devolución porque la VÍA del reembolso, que es media pregunta de "
                    + "este informe, solo existe ahí; la columna «Con asiento» deja ver "
                    + "cuáles tienen además su registro de tesorería.");
            return conMarcaDeAgua(sobre, TABLA_DEV);
        });
    }

    /** Las 3 vías de reembolso realmente usadas. */
    private static final java.util.Set<String> METODOS_REEMBOLSO =
            java.util.Set.of("efectivo", "tarjeta", "transferencia");

    private List<Map<String, Object>> kpisReembolsos(Filtros f) {
        Map<String, Object> t = ch.queryForMap("""
            SELECT count()                            AS reembolsos,
                   sum(monto_reembolsado)             AS monto,
                   round(avg(monto_reembolsado), 2)   AS medio,
                   sum(monto_total)                   AS devuelto,
                   countIf(reembolso_registrado = 1)  AS con_asiento,
                   countIf(reembolso_registrado = 0)  AS sin_asiento,
                   countDistinct(cliente_id)          AS clientes,
                   countDistinct(metodo_reembolso)    AS vias,
                   round(avgIf(dias_hasta_reembolso,
                         dias_hasta_reembolso IS NOT NULL), 2) AS dias
            FROM %s.%s WHERE 1 %s
            """.formatted(DWH, TABLA_DEV, f.where()), f.args());

        List<Map<String, Object>> k = new ArrayList<>();
        k.add(kpi("Reembolsos pagados", t.get("reembolsos"), "numero"));
        k.add(kpi("Total reembolsado", t.get("monto"), "moneda"));
        k.add(kpi("Reembolso medio", t.get("medio"), "moneda"));
        k.add(kpi("Mercancía devuelta", t.get("devuelto"), "moneda"));
        k.add(kpi("Con asiento contable", t.get("con_asiento"), "numero"));
        k.add(kpi("Sin asiento contable", t.get("sin_asiento"), "numero"));
        k.add(kpi("Clientes reembolsados", t.get("clientes"), "numero"));
        k.add(kpi("Vías usadas", t.get("vias"), "numero"));
        k.add(kpi("Días hasta pagar", t.get("dias"), "dias"));
        return k;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Utilidades comunes
    // ═════════════════════════════════════════════════════════════════════

    /** Porcentaje de parte sobre total, con dos decimales. Null si no hay base. */
    private static Double porcentaje(Object parte, Object total) {
        if (parte == null || total == null) {
            return null;
        }
        double base = ((Number) total).doubleValue();
        if (base == 0) {
            return null;
        }
        return Math.round(((Number) parte).doubleValue() * 10000.0 / base) / 100.0;
    }

    /**
     * Añade dos KPI con los extremos de una medida.
     *
     * Se salta las filas cuya medida es NULL en vez de tratarlas como cero: un
     * transportista sin ningún envío medible no es «el más rápido con 0 días»,
     * es un transportista del que no se sabe nada. Ese es exactamente el tipo de
     * fila que ganaría un ranking por descuido.
     */
    private static void mejorPeor(List<Map<String, Object>> items, String medida, String clave,
                                  List<Map<String, Object>> kpis,
                                  String etiquetaMayor, String etiquetaMenor) {
        Map<String, Object> mayor = null;
        Map<String, Object> menor = null;
        for (Map<String, Object> fila : items) {
            Object v = fila.get(medida);
            if (v == null) {
                continue;
            }
            double d = ((Number) v).doubleValue();
            if (mayor == null || d > ((Number) mayor.get(medida)).doubleValue()) {
                mayor = fila;
            }
            if (menor == null || d < ((Number) menor.get(medida)).doubleValue()) {
                menor = fila;
            }
        }
        // Con una sola fila, «el mejor» y «el peor» serían la misma y decir las
        // dos cosas es ruido: se declara solo una.
        if (mayor != null) {
            kpis.add(kpi(etiquetaMayor, mayor.get(clave), "texto"));
        }
        if (menor != null && menor != mayor) {
            kpis.add(kpi(etiquetaMenor, menor.get(clave), "texto"));
        }
    }
}
