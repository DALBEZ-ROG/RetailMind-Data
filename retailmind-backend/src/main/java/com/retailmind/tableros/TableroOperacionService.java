package com.retailmind.tableros;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * T-4 · TABLERO DE OPERACIÓN Y ÚLTIMA MILLA — nivel estratégico, objetivo OE-09.
 *
 * Sirve tres decisiones de dirección (§3.4 del diseño):
 * <ul>
 *   <li><b>D-09.1</b> con qué transportistas se renueva contrato y a cuál se le retira volumen;</li>
 *   <li><b>D-09.2</b> dónde se refuerza la operación interna;</li>
 *   <li><b>D-09.4</b> dónde se ataca la pérdida física.</li>
 * </ul>
 *
 * <h2>ES EL ÚNICO TABLERO SIN DINERO, y eso es su razón de existir</h2>
 *
 * T-4 y T-5 sirven al mismo objetivo y están separados por una sola razón:
 * <b>éste es el único que DESPACHO y BODEGA pueden abrir</b>. En ClickHouse no
 * hay GRANT por columna ni RLS, así que lo único que garantiza la segregación
 * son dos cosas, y las dos tienen que cumplirse a la vez:
 *
 * <ol>
 *   <li>su ruta va enumerada por nombre en {@code SecurityConfig}, y</li>
 *   <li><b>su consulta no selecciona un solo importe</b>.</li>
 * </ol>
 *
 * Lo segundo es lo que este archivo tiene que sostener línea a línea. Las tres
 * tablas que lee TIENEN columnas de dinero —{@code fact_envio.costo},
 * {@code fact_pedido.total}, {@code fact_movimiento_inventario.valor_movimiento}—
 * y ninguna aparece en un SELECT de aquí. Todo se mide en <b>envíos, días,
 * horas y unidades</b>. El costo del envío vive en T-5, que empieza donde éste
 * termina.
 *
 * Hay una prueba automática de esto: {@code validar_tableros.py} recorre la
 * respuesta entera —KPI y todas las filas de todos los bloques— buscando
 * nombres de columna con aspecto monetario, y falla si encuentra uno. Una regla
 * que solo vive en un comentario se rompe en el primer bloque nuevo.
 */
@Service
public class TableroOperacionService extends TableroServiceBase {

    private static final String CODIGO = "T-4";
    private static final String TITULO = "Tablero de Operación y Última Milla";
    private static final List<String> DECISIONES = List.of("D-09.1", "D-09.2", "D-09.4");

    private static final String ENVIO = DWH + ".fact_envio";
    private static final String PEDIDO = DWH + ".fact_pedido";
    private static final String NOVEDAD = DWH + ".fact_novedad_envio";
    private static final String KARDEX = DWH + ".fact_movimiento_inventario";
    private static final String DEVOLUCION = DWH + ".fact_devolucion";

    public TableroOperacionService(@Qualifier("pgJdbcTemplate") JdbcTemplate pg,
                                   @Qualifier("clickHouseJdbc") JdbcTemplate ch) {
        super(pg, ch);
    }

    /**
     * @param desde         fecha ISO; el rango se ajusta a MESES COMPLETOS
     * @param hasta         fecha ISO; idem
     * @param transportista nombre exacto, o null = los cinco
     * @param zona          nombre exacto de la zona de envío, o null = todas
     * @param bodega        nombre exacto; solo alcanza a la merma y al origen
     *                      del envío, y se declara
     */
    public Map<String, Object> tablero(String desde, String hasta, String transportista,
                                       String zona, String bodega) {
        String fDesde = fecha(desde, "desde");
        String fHasta = fecha(hasta, "hasta");
        exigirRangoValido(fDesde, fHasta);
        String fTransportista = texto(transportista);
        String fZona = texto(zona);
        String fBodega = texto(bodega);

        return servir(CODIGO, TITULO, DECISIONES, () -> {
            Filtros envio = filtrosEnvio(fDesde, fHasta, fTransportista, fZona, fBodega);
            Filtros novedad = filtrosNovedad(fDesde, fHasta, fTransportista, fZona);
            Filtros kardex = filtrosKardex(fDesde, fHasta, fBodega);

            Map<String, Object> tot = totalesEnvio(envio);
            Map<String, Object> merma = totalesMerma(kardex);

            List<Map<String, Object>> bloques = new ArrayList<>();
            bloques.add(cumplimientoPromesa(envio, tot));
            bloques.add(diasTransito(envio, tot));
            bloques.add(tiempoPorEtapa(fDesde, fHasta));
            bloques.add(incidencias(novedad));
            bloques.add(mermaPorMotivo(kardex, merma));
            bloques.add(retornoAlAlmacen(envio, novedad));

            List<String> salvedades = new ArrayList<>();
            salvedades.add("Este tablero NO lleva ni una columna de dinero: se mide en envíos, "
                    + "días, horas y unidades. Es lo que permite que Despacho y Bodega lo "
                    + "abran, porque en el almacén analítico no hay privilegio por columna "
                    + "que respalde el corte. El costo del transporte vive en el tablero de "
                    + "Costo de la Operación (T-5), reservado a dirección.");
            if (fBodega != null) {
                salvedades.add("El filtro de bodega «" + fBodega + "» alcanza a la merma y al "
                        + "ORIGEN del envío. No alcanza al tiempo por etapa del ciclo: el "
                        + "pedido no guarda de qué bodega salió cada línea.");
            }

            return sobreTablero(CODIGO, TITULO, DECISIONES,
                    periodo(fDesde, fHasta, "fact_envio"),
                    kpis(tot, merma, novedad, fDesde, fHasta),
                    bloques, salvedades,
                    "fact_envio", "fact_pedido", "fact_novedad_envio",
                    "fact_movimiento_inventario");
        });
    }

    // ═════════════════════════════════════════════════════════════════════
    // Filtros
    // ═════════════════════════════════════════════════════════════════════

    private static Filtros filtrosEnvio(String desde, String hasta, String transportista,
                                        String zona, String bodega) {
        Filtros f = new Filtros();
        f.y("mes >= toStartOfMonth(toDate(?))", desde);
        f.y("mes <= toStartOfMonth(toDate(?))", hasta);
        f.y("transportista = ?", transportista);
        f.y("zona = ?", zona);
        f.y("bodega_origen = ?", bodega);
        return f;
    }

    private static Filtros filtrosNovedad(String desde, String hasta, String transportista,
                                          String zona) {
        Filtros f = new Filtros();
        f.y("mes >= toStartOfMonth(toDate(?))", desde);
        f.y("mes <= toStartOfMonth(toDate(?))", hasta);
        f.y("transportista = ?", transportista);
        f.y("zona = ?", zona);
        return f;
    }

    private static Filtros filtrosKardex(String desde, String hasta, String bodega) {
        Filtros f = new Filtros();
        // C3B.1, la corrección más cara del proyecto: `es_ajuste_real` y JAMÁS
        // `naturaleza = 'ajuste'`. La apertura del almacén se registró como
        // `entrada_ajuste` —343 movimientos, 34.210 unidades— y colarla aquí
        // multiplica el sobrante por 380 sin que ninguna suma deje de cuadrar.
        f.y("es_ajuste_real = 1");
        f.y("mes >= toStartOfMonth(toDate(?))", desde);
        f.y("mes <= toStartOfMonth(toDate(?))", hasta);
        f.y("bodega = ?", bodega);
        return f;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Totales y KPI — SIN una sola cifra de dinero
    // ═════════════════════════════════════════════════════════════════════

    private Map<String, Object> totalesEnvio(Filtros f) {
        List<Map<String, Object>> r = ch.queryForList(
                "SELECT count() AS envios, "
                + "       countIf(entregado_a_tiempo IS NOT NULL) AS con_promesa, "
                + "       countIf(entregado_a_tiempo = 1) AS a_tiempo, "
                + "       countIf(estado = 'entregado') AS entregados, "
                + "       countIf(dias_transito IS NOT NULL) AS con_transito, "
                + "       round(avg(dias_transito), 2) AS transito_medio "
                + "FROM " + ENVIO + " WHERE 1 " + f.where(), f.args());
        return r.isEmpty() ? Map.of() : r.get(0);
    }

    private Map<String, Object> totalesMerma(Filtros f) {
        List<Map<String, Object>> r = ch.queryForList(
                "SELECT count() AS movimientos, "
                + "       sum(if(factor = -1, cantidad, 0)) AS unidades_perdidas, "
                + "       sum(if(factor =  1, cantidad, 0)) AS unidades_sobrantes "
                + "FROM " + KARDEX + " WHERE 1 " + f.where(), f.args());
        return r.isEmpty() ? Map.of() : r.get(0);
    }

    private List<Map<String, Object>> kpis(Map<String, Object> tot, Map<String, Object> merma,
                                           Filtros novedad, String desde, String hasta) {
        long envios = num(tot.get("envios"));
        long conPromesa = num(tot.get("con_promesa"));
        long aTiempo = num(tot.get("a_tiempo"));

        List<Map<String, Object>> inc = ch.queryForList(
                "SELECT count() AS novedades, countIf(resuelta = 0) AS abiertas "
                + "FROM " + NOVEDAD + " WHERE 1 " + novedad.where(), novedad.args());
        long novedades = inc.isEmpty() ? 0 : num(inc.get(0).get("novedades"));
        long abiertas = inc.isEmpty() ? 0 : num(inc.get(0).get("abiertas"));

        Object cicloMedio = cicloMedioHoras(desde, hasta);

        List<Map<String, Object>> k = new ArrayList<>();
        k.add(kpi("Entregas dentro de la fecha prometida", porcentaje(aTiempo, conPromesa),
                "porcentaje",
                fmt(aTiempo) + " de " + fmt(conPromesa) + " envíos con promesa MEDIBLE. Los "
                + fmt(envios - conPromesa) + " restantes no llegaron tarde: no llegaron, o no "
                + "tienen fecha prometida — y por eso no entran en el denominador."));
        k.add(kpi("Tránsito medio", tot.get("transito_medio"), "dias",
                "Sobre los " + fmt(num(tot.get("con_transito"))) + " envíos con despacho y "
                + "entrega registrados. Las restas ya vienen convertidas a la zona horaria "
                + "del negocio en el ETL: en UTC, uno de cada cinco cambia de día."));
        k.add(kpi("Ciclo total medio del pedido", cicloMedio, "dias",
                "Del pago a la entrega. Cada etapa se mide sobre una población distinta y el "
                + "bloque de etapas lo declara fila por fila."));
        k.add(kpi("Incidencias de entrega abiertas", abiertas, "numero",
                fmt(abiertas) + " sin resolver de " + fmt(novedades) + " registradas en el "
                + "período. Una incidencia abierta bloquea la entrega del pedido."));
        k.add(kpi("Unidades perdidas por merma", num(merma.get("unidades_perdidas")), "numero",
                "En " + fmt(num(merma.get("movimientos"))) + " ajustes REALES de inventario, "
                + "con " + fmt(num(merma.get("unidades_sobrantes"))) + " unidades de sobrante "
                + "en el otro sentido. La apertura del almacén NO cuenta como ajuste."));
        return k;
    }

    /** Media del ciclo completo, en DÍAS, sobre los pedidos que lo tienen medido. */
    private Object cicloMedioHoras(String desde, String hasta) {
        Filtros f = new Filtros();
        f.y("mes >= toStartOfMonth(toDate(?))", desde);
        f.y("mes <= toStartOfMonth(toDate(?))", hasta);
        List<Map<String, Object>> r = ch.queryForList(
                "SELECT round(avg(toFloat64(horas_ciclo_total)) / 24, 2) AS dias "
                + "FROM " + PEDIDO + " WHERE horas_ciclo_total IS NOT NULL " + f.where(),
                f.args());
        return r.isEmpty() ? null : r.get(0).get("dias");
    }

    // ═════════════════════════════════════════════════════════════════════
    // Elemento 1 — Cumplimiento de la fecha prometida por transportista
    // ═════════════════════════════════════════════════════════════════════

    /**
     * <h3>{@code entregado_a_tiempo} viaja NULL, nunca 0</h3>
     * Un envío sin fecha de entrega real o sin promesa no es un incumplimiento:
     * es un caso NO MEDIBLE. Contarlo como 0 hunde el porcentaje de cualquier
     * transportista que tenga envíos en tránsito, y castigaría más al que más
     * volumen tiene abierto. La columna {@code sin_promesa} los publica aparte
     * en vez de esconderlos.
     */
    private Map<String, Object> cumplimientoPromesa(Filtros f, Map<String, Object> tot) {
        List<Map<String, Object>> items = ch.queryForList(
                "SELECT transportista, "
                + "       count() AS envios, "
                + "       countIf(entregado_a_tiempo IS NOT NULL) AS con_promesa, "
                + "       countIf(entregado_a_tiempo IS NULL) AS sin_promesa, "
                + "       countIf(entregado_a_tiempo = 1) AS a_tiempo, "
                + "       countIf(entregado_a_tiempo = 0) AS tarde, "
                + "       " + pct("countIf(entregado_a_tiempo = 1)",
                                  "countIf(entregado_a_tiempo IS NOT NULL)") + " AS a_tiempo_pct, "
                + "       round(avgIf(toFloat64(dias_desvio_promesa), "
                + "                   entregado_a_tiempo = 0), 2) AS retraso_medio "
                + "FROM " + ENVIO + " WHERE 1 " + f.where() + " "
                + "GROUP BY transportista ORDER BY envios DESC", f.args());
        return conSalvedad(bloque("cumplimiento_promesa",
                "Cumplimiento de la fecha prometida por transportista",
                "barras",
                fmt(num(tot.get("con_promesa"))) + " envíos con promesa MEDIBLE de "
                + fmt(num(tot.get("envios"))) + " despachados. El porcentaje de cada "
                + "transportista es sobre SUS envíos medibles, no sobre sus envíos totales.",
                items),
                "Un envío sin fecha prometida o sin entrega registrada llega como «no "
                + "medible», nunca como incumplido: contarlo como fallo hundiría al "
                + "transportista que más envíos tenga en tránsito, que es lo contrario de lo "
                + "que mide este bloque. Van aparte en la columna «sin promesa».");
    }

    // ═════════════════════════════════════════════════════════════════════
    // Elemento 2 — Días de tránsito por transportista y zona
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Caja y bigotes: mínimo, primer cuartil, mediana, tercer cuartil y máximo.
     *
     * La media va también, pero la CAJA es lo que decide: un transportista con
     * media de 4 días y bigote hasta 9 no es el mismo que uno con media de 4 y
     * bigote hasta 5, y renovar contrato con el primero es comprar variabilidad.
     */
    private Map<String, Object> diasTransito(Filtros f, Map<String, Object> tot) {
        List<Map<String, Object>> items = ch.queryForList(
                "SELECT transportista, zona, "
                + "       count() AS medidos, "
                + "       round(avg(dias_transito), 2) AS media, "
                + "       min(dias_transito) AS minimo, "
                + "       quantileExact(0.25)(dias_transito) AS q1, "
                + "       quantileExact(0.50)(dias_transito) AS mediana, "
                + "       quantileExact(0.75)(dias_transito) AS q3, "
                + "       max(dias_transito) AS maximo "
                + "FROM " + ENVIO + " WHERE dias_transito IS NOT NULL " + f.where() + " "
                + "GROUP BY transportista, zona ORDER BY medidos DESC", f.args());
        return conSalvedad(bloque("dias_transito",
                "Días de tránsito por transportista y zona",
                "caja_bigotes",
                fmt(num(tot.get("con_transito"))) + " envíos con despacho y entrega "
                + "registrados. Cada caja es una pareja (transportista, zona) y solo cuenta "
                + "SUS envíos medidos, que van en la primera columna.",
                items),
                "Las tres restas de fecha vienen convertidas a la zona horaria del negocio "
                + "desde el ETL. Restadas en UTC, 569 de 2.727 envíos (20,9 %) cambian de día "
                + "y el tránsito medio baja de 3,98 a 3,77: el error es asimétrico —se "
                + "despacha por la tarde y se entrega por la mañana— y acorta el tránsito "
                + "sistemáticamente.");
    }

    // ═════════════════════════════════════════════════════════════════════
    // Elemento 3 — Tiempo por etapa del ciclo
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Las CUATRO etapas del ciclo, cada una con su {@code pedidos_medidos}.
     *
     * <h3>Es el bloque que C2.7 documenta con nombre y cifra</h3>
     * Las cuatro se miden sobre poblaciones distintas —2.868 / 2.856 / 2.727 /
     * 3.696 pedidos— porque hay pedidos con unos hitos registrados y otros no.
     * Presentadas juntas sin su denominador, un promedio sube o baja sin que
     * nada haya cambiado en la operación, y la etapa que parece el cuello es la
     * que peor registro tiene, no la que más tarda.
     *
     * <h3>Y aquí NO se aplica el truco de CE1.1</h3>
     * En el embudo de T-1 un paso cuenta a quien llegó a ese hito «o a uno
     * posterior», porque el paso es una POSICIÓN y un pedido entregado pasó por
     * el despacho aunque nadie apuntara la hora. Un TIEMPO no admite eso: sin
     * las dos marcas no hay resta que hacer. Por eso el embudo es monótono y
     * estas cuatro poblaciones no lo son —2.727 es menor que 3.696—, y ninguna
     * de las dos cosas es un error. Lo que sí sería un error es publicar la
     * suma de las cuatro etapas como «el ciclo»: son cuatro medias sobre cuatro
     * conjuntos, y su suma no es el ciclo de nadie. El ciclo total va como una
     * quinta fila con su propia población.
     */
    private Map<String, Object> tiempoPorEtapa(String desde, String hasta) {
        Filtros f = new Filtros();
        f.y("mes >= toStartOfMonth(toDate(?))", desde);
        f.y("mes <= toStartOfMonth(toDate(?))", hasta);

        String[][] etapas = {
            {"Del cobro al picking terminado", "horas_pago_a_preparacion"},
            {"Del picking terminado al despacho", "horas_preparacion_a_despacho"},
            {"Del despacho a la entrega", "horas_despacho_a_entrega"},
            {"Ciclo completo (cobro → entrega)", "horas_ciclo_total"},
        };

        List<Map<String, Object>> items = new ArrayList<>();
        for (String[] etapa : etapas) {
            // El nombre de la columna es una CONSTANTE del código, nunca del
            // usuario: se interpola porque ClickHouse no admite un parámetro en
            // la posición de un identificador.
            String col = etapa[1];
            List<Map<String, Object>> r = ch.queryForList(
                    "SELECT count() AS pedidos_medidos, "
                    + "       round(avg(toFloat64(" + col + ")) / 24, 2) AS dias_media, "
                    + "       round(quantileExact(0.5)(toFloat64(" + col + ")) / 24, 2) "
                    + "           AS dias_mediana, "
                    + "       round(quantileExact(0.9)(toFloat64(" + col + ")) / 24, 2) "
                    + "           AS dias_p90, "
                    + "       round(avg(toFloat64(" + col + ")), 2) AS horas_media "
                    + "FROM " + PEDIDO + " WHERE " + col + " IS NOT NULL " + f.where(),
                    f.args());
            Map<String, Object> fila = new LinkedHashMap<>(r.isEmpty() ? Map.of() : r.get(0));
            fila.put("etapa", etapa[0]);
            fila.put("es_total", col.equals("horas_ciclo_total") ? 1 : 0);
            items.add(fila);
        }

        long maximo = items.stream().mapToLong(i -> num(i.get("pedidos_medidos"))).max().orElse(0);
        long minimo = items.stream().mapToLong(i -> num(i.get("pedidos_medidos"))).min().orElse(0);

        return conSalvedad(bloque("tiempo_etapa",
                "Tiempo por etapa del ciclo del pedido",
                "barras",
                "CADA etapa se mide sobre una población DISTINTA —entre " + fmt(minimo)
                + " y " + fmt(maximo) + " pedidos— y su tamaño va en la columna «pedidos "
                + "medidos». Sin ese número, comparar dos etapas no significa nada.",
                items),
                "Las cuatro filas NO se suman: son cuatro medias sobre cuatro conjuntos "
                + "distintos, y su suma no es el ciclo de ningún pedido. El ciclo completo va "
                + "como una fila propia, medida solo sobre los pedidos que tienen cobro y "
                + "entrega. Y a diferencia del embudo del tablero Omnicanal, aquí no se puede "
                + "suponer el hito que falta: sin las dos marcas de tiempo no hay resta. "
                + "OJO con el nombre de las etapas: «picking terminado» es el hito "
                + "'preparado' y NO 'en_preparacion' —son dos hitos distintos, 2.868 pedidos "
                + "frente a 2.883— y se eligió el primero para que cada tramo empiece justo "
                + "donde acaba el anterior y los tres cubran el ciclo sin huecos.");
    }

    // ═════════════════════════════════════════════════════════════════════
    // Elemento 4 — Incidencias de entrega por tipo y desenlace
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Matriz tipo × desenlace.
     *
     * <h3>La lista blanca sale de los DATOS (C3C.3)</h3>
     * El diseño del ETL declaraba {@code accion ∈ {reprogramar,
     * devolver_almacen}} — los verbos del API. Lo que la base guarda es el
     * PARTICIPIO: {@code reprogramada}, {@code devuelto_almacen} y
     * {@code sin_resolver} para la que sigue abierta. Un filtro escrito desde el
     * documento casa con CERO filas y oculta el 68 % de las novedades sin dar un
     * error. Por eso este bloque no enumera nada: agrupa y deja que los valores
     * aparezcan solos, que es la única forma de que un valor nuevo se vea en vez
     * de desaparecer.
     */
    private Map<String, Object> incidencias(Filtros f) {
        List<Map<String, Object>> items = ch.queryForList(
                "SELECT tipo, "
                + "       accion AS desenlace, "
                + "       count() AS novedades, "
                + "       countDistinct(envio_id) AS envios, "
                + "       countIf(resuelta = 1) AS resueltas, "
                + "       round(avgIf(toFloat64(horas_hasta_resolucion), resuelta = 1), 2) "
                + "           AS horas_resolucion, "
                + "       max(intento_numero) AS intento_maximo "
                + "FROM " + NOVEDAD + " WHERE 1 " + f.where() + " "
                + "GROUP BY tipo, accion ORDER BY novedades DESC", f.args());

        List<Map<String, Object>> tot = ch.queryForList(
                "SELECT count() AS novedades, countDistinct(envio_id) AS envios, "
                + "       countIf(resuelta = 1) AS resueltas, "
                + "       countDistinct(accion) AS desenlaces, countDistinct(tipo) AS tipos "
                + "FROM " + NOVEDAD + " WHERE 1 " + f.where(), f.args());
        Map<String, Object> t = tot.isEmpty() ? Map.of() : tot.get(0);

        return conSalvedad(bloque("incidencias",
                "Incidencias de entrega por tipo y desenlace",
                "matriz",
                fmt(num(t.get("novedades"))) + " incidencias sobre " + fmt(num(t.get("envios")))
                + " envíos distintos —un envío puede acumular hasta tres intentos— con "
                + fmt(num(t.get("resueltas"))) + " ya resueltas. La matriz es "
                + fmt(num(t.get("tipos"))) + " tipos × " + fmt(num(t.get("desenlaces")))
                + " desenlaces.",
                items),
                "Los desenlaces salen del propio dato y no de una lista escrita a mano. El "
                + "diseño los nombraba con los verbos del API («reprogramar», "
                + "«devolver_almacen») y lo que la base guarda es el participio: un filtro "
                + "tomado del documento habría casado con cero filas y vaciado el bloque sin "
                + "dar ningún error.");
    }

    // ═════════════════════════════════════════════════════════════════════
    // Elemento 5 — Merma y sobrante por motivo, en unidades
    // ═════════════════════════════════════════════════════════════════════

    /**
     * <h3>C3B.1 · el aviso más caro del proyecto</h3>
     * El filtro es {@code es_ajuste_real = 1}, <b>jamás</b>
     * {@code naturaleza = 'ajuste'}. La apertura del almacén se cargó como
     * {@code entrada_ajuste} —343 movimientos, 34.210 unidades— y con el filtro
     * «obvio» el sobrante pasa de 90 unidades a 34.300: un factor <b>380×</b>
     * que pinta un almacén fuera de control sin que falle ninguna suma. El ETL
     * precalcula la columna para que el error no esté al alcance de un descuido.
     */
    private Map<String, Object> mermaPorMotivo(Filtros f, Map<String, Object> tot) {
        List<Map<String, Object>> items = ch.queryForList(
                "SELECT ajuste_motivo AS motivo, "
                + "       ajuste_tipo AS tipo, "
                + "       count() AS movimientos, "
                + "       sum(if(factor = -1, cantidad, 0)) AS unidades_perdidas, "
                + "       sum(if(factor =  1, cantidad, 0)) AS unidades_sobrantes, "
                + "       sum(cantidad_con_signo) AS neto, "
                + "       countDistinct(producto_variante_id) AS variantes "
                + "FROM " + KARDEX + " WHERE 1 " + f.where() + " "
                + "GROUP BY ajuste_motivo, ajuste_tipo "
                + "ORDER BY unidades_perdidas DESC, unidades_sobrantes DESC", f.args());
        return conSalvedad(bloque("merma_motivo",
                "Merma y sobrante por motivo, en unidades",
                "barras",
                fmt(num(tot.get("movimientos"))) + " ajustes REALES de inventario: "
                + fmt(num(tot.get("unidades_perdidas"))) + " unidades perdidas y "
                + fmt(num(tot.get("unidades_sobrantes"))) + " de sobrante. Todo en unidades: "
                + "este tablero no valoriza.",
                items),
                "Solo entran los ajustes REALES de inventario. La apertura del almacén está "
                + "registrada con el mismo tipo de movimiento —343 asientos por 34.210 "
                + "unidades— y filtrar por la naturaleza del movimiento en vez de por esta "
                + "marca multiplicaría el sobrante por 380 sin que ninguna suma dejara de "
                + "cuadrar.");
    }

    // ═════════════════════════════════════════════════════════════════════
    // Elemento 6 — Devoluciones al almacén por incidencia
    // ═════════════════════════════════════════════════════════════════════

    /**
     * El embudo de la mercancía que vuelve por una incidencia de entrega.
     *
     * <h3>El último paso vale CERO, y no es un fallo: es el hallazgo</h3>
     * El diseño monta este embudo sobre {@code fact_novedad_envio +
     * fact_devolucion}, dando por hecho que la mercancía devuelta al almacén
     * acaba en una devolución registrada. <b>No hay ni una.</b> Es coherente con
     * cómo está construido el sistema —devolver al almacén no reingresa stock,
     * porque el kardex solo se mueve tras la inspección de bodega, y el
     * reembolso queda pendiente de soporte— pero significa que la mercancía
     * volvió físicamente y <b>no existe en ningún registro posterior</b>: ni
     * vuelve a estar disponible para vender, ni el cliente ha sido reembolsado.
     *
     * Un cero sin explicar se lee como «no pasa nada aquí». Por eso el paso se
     * publica igualmente, con su nota, en vez de omitirse: la brecha es
     * exactamente lo que D-09.4 tiene que ver.
     */
    private Map<String, Object> retornoAlAlmacen(Filtros envio, Filtros novedad) {
        List<Map<String, Object>> e = ch.queryForList(
                "SELECT count() AS despachados FROM " + ENVIO + " WHERE 1 " + envio.where(),
                envio.args());
        long despachados = e.isEmpty() ? 0 : num(e.get(0).get("despachados"));

        List<Map<String, Object>> n = ch.queryForList(
                "SELECT countDistinct(envio_id) AS con_novedad, "
                + "       countDistinctIf(envio_id, accion = 'devuelto_almacen') AS devueltos, "
                + "       countDistinctIf(pedido_id, accion = 'devuelto_almacen') AS pedidos "
                + "FROM " + NOVEDAD + " WHERE 1 " + novedad.where(), novedad.args());
        Map<String, Object> nv = n.isEmpty() ? Map.of() : n.get(0);
        long conNovedad = num(nv.get("con_novedad"));
        long devueltos = num(nv.get("devueltos"));
        long pedidos = num(nv.get("pedidos"));

        List<Map<String, Object>> r = ch.queryForList(
                "SELECT countDistinct(nv.pedido_id) AS con_devolucion "
                + "FROM " + NOVEDAD + " nv "
                + "INNER JOIN (SELECT DISTINCT pedido_id FROM " + DEVOLUCION + ") d "
                + "        ON d.pedido_id = nv.pedido_id "
                + "WHERE nv.accion = 'devuelto_almacen' " + novedad.where(), novedad.args());
        long conDevolucion = r.isEmpty() ? 0 : num(r.get(0).get("con_devolucion"));

        List<Map<String, Object>> items = new ArrayList<>();
        items.add(paso(1, "Envíos despachados", despachados, despachados,
                "Todos los envíos del período."));
        items.add(paso(2, "Con alguna incidencia de entrega", conNovedad, despachados,
                "Un envío puede acumular hasta tres intentos antes de resolverse."));
        items.add(paso(3, "Devueltos al almacén", devueltos, conNovedad,
                "El resto se reprogramó y llegó a entregarse, o sigue abierto."));
        items.add(paso(4, "Pedidos que quedaron sin entregar", pedidos, devueltos,
                "Estado terminal: la venta no se completó."));
        items.add(paso(5, "Con una devolución registrada después", conDevolucion, pedidos,
                conDevolucion == 0
                ? "NINGUNO. La mercancía volvió físicamente y no hay registro posterior: no "
                  + "reingresó al stock vendible ni consta reembolso al cliente. No es un "
                  + "fallo del dato — es la brecha del proceso, y es lo que este bloque existe "
                  + "para enseñar."
                : "Con trazabilidad posterior del retorno."));

        return conSalvedad(bloque("retorno_almacen",
                "Retorno al almacén por incidencia de entrega",
                "embudo",
                "Base " + fmt(despachados) + " envíos despachados en el período. Cada paso "
                + "declara su propio denominador: la tasa de un escalón se mide contra el "
                + "escalón anterior.",
                items),
                conDevolucion == 0
                ? "El último escalón es CERO y está publicado a propósito. Los " + fmt(pedidos)
                  + " pedidos devueltos al almacén no tienen ninguna devolución registrada "
                  + "después: el sistema no reingresa el stock al devolver —el kardex solo se "
                  + "mueve tras la inspección de bodega— y el reembolso queda pendiente. La "
                  + "mercancía está físicamente de vuelta y no existe en ningún registro "
                  + "posterior. Omitir el paso por estar a cero escondería justo el problema."
                : "Los pasos se miden sobre envíos distintos, no sobre incidencias: un envío "
                  + "con tres intentos cuenta una vez.");
    }

    private static Map<String, Object> paso(int orden, String nombre, long valor,
                                            long denominador, String nota) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("orden", orden);
        p.put("paso", nombre);
        p.put("pedidos", valor);
        p.put("denominador", denominador);
        p.put("perdidos", denominador - valor);
        p.put("tasa_paso_pct", porcentaje(valor, denominador));
        p.put("nota", nota);
        return p;
    }
}
