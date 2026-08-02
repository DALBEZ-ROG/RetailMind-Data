package com.retailmind.informes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * PREVISIÓN DE DEMANDA — fase E2 del nivel estratégico (§5.1 de
 * {@code docs/estrategico/DISENO_NIVEL_ESTRATEGICO.md}). Fuente: ClickHouse
 * ({@code retailmind_dwh.fact_prevision_demanda}), no PostgreSQL.
 *
 * Alimenta tres decisiones de dirección: D-10.1 (las metas del período),
 * D-11.1 (el plan de compra) y D-07.5 (el nivel objetivo de stock).
 *
 * <h2>Por qué esto es una clase y no un método más de Gerencia</h2>
 * §5.1.8 estima «0 clases Java nuevas» porque los dos servicios de departamento
 * ya existen. Pero el informe es **el mismo** para Gerencia y para Compras —el
 * mismo SQL, los mismos KPI, la misma salvedad— y solo cambia quién puede
 * verlo. Repartirlo entre {@code InformesGerenciaCompuestosService} y
 * {@code InformesComprasCompuestosService} obligaría a duplicarlo, y dos copias
 * de una consulta de previsión divergen en la primera corrección: una pantalla
 * enseñaría una banda y la otra otra distinta para el mismo mes. Se paga UNA
 * clase para no pagar esa divergencia; los dos controladores existentes la
 * inyectan y no se creó ninguno. Corrección CE3.6.
 *
 * <h2>Las cinco reglas de presentación de §5.1.9, y quién cumple cada una</h2>
 * <ol>
 *   <li><b>Histórico y previsión en el MISMO gráfico</b> — el sobre viaja con
 *       {@code serie}: los 19 meses observados y los 3 previstos en una sola
 *       lista, cada punto con lo que le corresponde. La pantalla no une nada.</li>
 *   <li><b>Ningún número sin banda</b> — la tabla lo garantiza antes de
 *       publicarse; aquí la banda viaja SIEMPRE junto a la cifra, y las series
 *       sin previsión llegan con {@code metodo = sin_prevision} para que la
 *       pantalla escriba «sin previsión individual» en vez de un cero.</li>
 *   <li><b>La muestra en la fila</b> — {@code meses_historia} es una columna,
 *       no una nota al pie.</li>
 *   <li><b>El error por fila</b> — {@code mape_backtest} va acompañado de
 *       {@code mape_linea_base}: un 24 % no se puede leer sin saber que el
 *       ingenuo sacaba 24,2 % en ESA serie y 10,7 % en la de al lado.</li>
 *   <li><b>La banda se ensancha con el horizonte</b> — lo produce el modelo y
 *       lo verifica su validación antes de publicar.</li>
 * </ol>
 *
 * Ningún método lleva {@code @Transactional}: no se toca PostgreSQL.
 */
@Service
public class InformesPrevisionService extends InformeCompuestoServiceBase {

    private static final String TABLA = "fact_prevision_demanda";
    private static final String TABLA_VENTA = "fact_venta_linea";

    /** Niveles de agregación publicados. Lista blanca. */
    private static final Set<String> NIVELES = Set.of("total", "categoria", "variante");

    /** Centinela del nivel total en la columna {@code categoria}. */
    private static final String CATEGORIA_TODAS = "(todas)";

    private static final String SIN_PREVISION = "sin_prevision";

    public InformesPrevisionService(
            @Qualifier("pgJdbcTemplate") JdbcTemplate pg,
            @Qualifier("clickHouseJdbc") JdbcTemplate ch) {
        super(pg, ch);
    }

    /**
     * OTD-GER-13 · previsión de demanda a tres meses, en el nivel pedido.
     *
     * @param nivel     total | categoria | variante (defecto: categoria)
     * @param categoria filtro exacto de categoría; en nivel variante, la acota
     * @param horizonte 1-3 meses; sin valor, los tres
     * @param buscar    nombre o SKU, útil solo en el nivel variante
     */
    public Map<String, Object> previsionDemanda(String nivel, String categoria,
                                                Integer horizonte, String buscar,
                                                int page, int size) {
        String nivelSel = opcion(nivel, NIVELES, "nivel");
        if (nivelSel == null) {
            nivelSel = "categoria";
        }
        final String nivelFinal = nivelSel;
        final int h = entero(horizonte, 1, 3, 0, "horizonte");

        return ejecutar("OTD-GER-13", () -> {
            Filtros f = new Filtros();
            f.y("nivel = ?", nivelFinal);
            if (!"total".equals(nivelFinal)) {
                f.y("categoria = ?", texto(categoria));
            }
            if (h > 0) {
                f.y("horizonte = ?", h);
            }
            if ("variante".equals(nivelFinal)) {
                // El fragmento lleva DOS interrogantes y `Filtros` acumula un
                // valor por llamada: el segundo se añade al final, en `argsCon`.
                // Va el ÚLTIMO de la cláusula a propósito, que es la única
                // posición en la que completarlo por detrás no descoloca al resto.
                f.y("(positionCaseInsensitive(producto_nombre, ?) > 0 "
                        + "OR positionCaseInsensitive(sku, ?) > 0)", texto(buscar));
            }

            String sql = sqlItems(f.where());
            Map<String, Object> sobre = paginarCh(
                    sql, "SELECT count() FROM (" + sql + ")",
                    argsCon(f, "variante".equals(nivelFinal) ? texto(buscar) : null),
                    page, size);

            Anclas anclas = anclas();
            String categoriaSerie = "total".equals(nivelFinal) ? null : texto(categoria);
            sobre.put("serie", serie(categoriaSerie, anclas));
            conResumen(sobre, kpis(nivelFinal, categoriaSerie, anclas));
            sobre.put("salvedad", salvedad(anclas));
            sobre.put("previsionDe", categoriaSerie == null
                    ? "Total de la tienda" : categoriaSerie);
            return conMarcaDeAgua(sobre, TABLA);
        });
    }

    /**
     * Los args del filtro, con el {@code buscar} duplicado para el OR.
     *
     * {@code Filtros} añade un parámetro por fragmento y el fragmento del
     * buscador lleva DOS interrogantes (nombre y SKU). Se completa aquí en vez
     * de retocar el acumulador, que sirve a 39 informes ya verificados.
     */
    private static Object[] argsCon(Filtros f, String buscar) {
        return buscar == null ? f.args() : f.argsCon(buscar);
    }

    /**
     * OJO: esta consulta se arma por CONCATENACIÓN y no con
     * {@code String.formatted()}, aunque el resto del proyecto use lo segundo.
     *
     * {@code formatted()} interpreta el bloque ENTERO como plantilla, y aquí hay
     * un {@code '%Y-%m'} dentro del SQL: el formateador lo lee como un
     * especificador suyo y revienta con {@code Conversion = 'Y'} — un 400 en el
     * endpoint por un error que no tiene nada que ver con la petición. Es la
     * misma trampa que ya documentó §18 del patrón de informes.
     */
    private static String sqlItems(String where) {
        return "SELECT\n"
            + """
                formatDateTime(mes, '%Y-%m')                    AS periodo,
                multiIf(nivel = 'total', 'Total de la tienda',
                        nivel = 'categoria', categoria,
                        producto_nombre)                        AS serie,
                categoria,
                sku,
                horizonte,
                horizonte_efectivo,
                unidades_previstas,
                limite_inferior,
                limite_superior,
                meses_historia,
                observaciones_mes,
                metodo,
                mape_backtest,
                mape_linea_base,
                mae_backtest,
                es_linea_base,
                demanda_censurada,
                unidades_mismo_mes_anio_previo
            """
            + " FROM " + DWH + "." + TABLA
            + " WHERE 1 = 1 " + where
            + " ORDER BY horizonte, unidades_previstas DESC";
    }

    // ── Anclas temporales ────────────────────────────────────────────────

    /**
     * Los dos meses que enmarcan la pantalla y si el último observado quedó
     * fuera del ajuste.
     *
     * <h3>El mes truncado se LEE de la tabla, no se vuelve a detectar</h3>
     * El ETL decide si el último mes está incompleto (§5.1.2) y lo deja escrito
     * en {@code horizonte_efectivo}: cuando ese mes se excluye del
     * entrenamiento, la previsión del primer mes está a DOS meses del último
     * dato usado aunque sea el horizonte 1. Volver a mirar aquí los días del mes
     * crearía una segunda fuente de verdad que se desincronizaría con el modelo
     * en cuanto cambiara el criterio — y con ella, el gráfico marcaría como
     * incompleto un mes que sí entró, o al revés.
     *
     * <b>No se puede deducir del calendario.</b> La tabla publica SIEMPRE los
     * tres meses siguientes al último observado, se haya excluido o no: por
     * fuera, los dos casos son idénticos. Ésa es exactamente la razón de que la
     * columna exista.
     */
    private record Anclas(String ultimoObservado, String primerPrevisto,
                          int desfase, boolean truncado) {
    }

    private Anclas anclas() {
        String observado = ch.queryForObject(
                "SELECT formatDateTime(max(mes), '%Y-%m') FROM " + DWH + "." + TABLA_VENTA
                + " WHERE es_cancelado = 0", String.class);
        String previsto = ch.queryForObject(
                "SELECT formatDateTime(min(mes), '%Y-%m') FROM " + DWH + "." + TABLA,
                String.class);
        Integer desfase = ch.queryForObject(
                "SELECT max(toInt16(horizonte_efectivo) - toInt16(horizonte)) + 1 FROM "
                + DWH + "." + TABLA, Integer.class);
        int d = desfase == null ? 1 : desfase;
        return new Anclas(observado, previsto, d, d > 1);
    }

    // ── Regla 1: la serie histórica y la previsión en el MISMO gráfico ───

    /**
     * Los meses observados y los previstos, en una sola lista ordenada.
     *
     * Un punto histórico trae {@code real} y nada más; uno previsto trae
     * {@code previsto} con su banda y {@code real} nulo. Es lo que permite a la
     * pantalla dibujar la continuidad sin decidir nada por su cuenta: «un número
     * solo, sin la serie que lo produjo, no es interpretable».
     *
     * El último punto observado va marcado con {@code truncado} cuando el mes
     * está incompleto, porque la regla del diseño es mostrarlo APARTE y no
     * disimularlo dentro de la línea: una caída de fin de serie que es un
     * artefacto del corte se lee como una caída del negocio.
     */
    private List<Map<String, Object>> serie(String categoria, Anclas anclas) {
        String filtro = categoria == null ? "" : " AND categoria = ?";
        Object[] args = categoria == null ? new Object[0] : new Object[]{categoria};

        List<Map<String, Object>> historico = ch.queryForList(
                "SELECT formatDateTime(mes, '%Y-%m') AS periodo, sum(cantidad) AS real "
                + "FROM " + DWH + "." + TABLA_VENTA + " WHERE es_cancelado = 0" + filtro
                + " GROUP BY mes ORDER BY mes", args);

        String nivel = categoria == null ? "total" : "categoria";
        Object[] argsPrev = categoria == null
                ? new Object[]{nivel} : new Object[]{nivel, categoria};
        List<Map<String, Object>> previsto = ch.queryForList(
                "SELECT formatDateTime(mes, '%Y-%m') AS periodo, unidades_previstas, "
                + "limite_inferior, limite_superior, metodo "
                + "FROM " + DWH + "." + TABLA + " WHERE nivel = ?"
                + (categoria == null ? "" : " AND categoria = ?")
                + " ORDER BY mes", argsPrev);

        List<Map<String, Object>> puntos = new ArrayList<>();
        for (int i = 0; i < historico.size(); i++) {
            Map<String, Object> fila = historico.get(i);
            Map<String, Object> punto = new LinkedHashMap<>();
            punto.put("periodo", fila.get("periodo"));
            punto.put("real", fila.get("real"));
            punto.put("previsto", null);
            punto.put("limiteInferior", null);
            punto.put("limiteSuperior", null);
            punto.put("truncado", anclas.truncado() && i == historico.size() - 1);
            puntos.add(punto);
        }
        for (Map<String, Object> fila : previsto) {
            boolean sinPrevision = SIN_PREVISION.equals(String.valueOf(fila.get("metodo")));
            Map<String, Object> punto = new LinkedHashMap<>();
            punto.put("periodo", fila.get("periodo"));
            punto.put("real", null);
            punto.put("previsto", sinPrevision ? null : fila.get("unidades_previstas"));
            punto.put("limiteInferior", sinPrevision ? null : fila.get("limite_inferior"));
            punto.put("limiteSuperior", sinPrevision ? null : fila.get("limite_superior"));
            punto.put("truncado", false);
            puntos.add(punto);
        }
        return puntos;
    }

    // ── Resumen ──────────────────────────────────────────────────────────

    /**
     * Las tarjetas del encabezado, tomadas de la serie de REFERENCIA (el total,
     * o la categoría filtrada) y de su primer mes previsto.
     *
     * Aunque la tabla esté mostrando variantes, el encabezado describe la serie
     * agregada: es la cifra con la que se fija una meta o se dimensiona una
     * compra, y es la única cuyo error de backtest tiene sentido leer como
     * número global. El MAPE de cada variante va en SU fila (regla 4).
     */
    private List<Map<String, Object>> kpis(String nivel, String categoria, Anclas anclas) {
        String sqlNivel = categoria == null ? "total" : "categoria";
        Object[] args = categoria == null
                ? new Object[]{sqlNivel} : new Object[]{sqlNivel, categoria};
        List<Map<String, Object>> filas = ch.queryForList(
                "SELECT unidades_previstas, limite_inferior, limite_superior, "
                + "meses_historia, observaciones_mes, mape_backtest, mape_linea_base, "
                + "mae_backtest, metodo, es_linea_base "
                + "FROM " + DWH + "." + TABLA + " WHERE nivel = ?"
                + (categoria == null ? "" : " AND categoria = ?")
                + " AND horizonte = 1", args);

        List<Map<String, Object>> kpis = new ArrayList<>();
        if (filas.isEmpty()) {
            return kpis;
        }
        Map<String, Object> fila = filas.get(0);
        boolean sinPrevision = SIN_PREVISION.equals(String.valueOf(fila.get("metodo")));

        if (sinPrevision) {
            kpis.add(kpi("Previsión " + anclas.primerPrevisto(), "sin previsión", "texto"));
            kpis.add(kpi("Meses de historia", fila.get("meses_historia"), "numero"));
            kpis.add(kpi("Por qué", "menos de 12 meses con venta: no hay un ciclo "
                    + "que estimar", "texto"));
            return kpis;
        }

        BigDecimal valor = decimal(fila.get("unidades_previstas"));
        BigDecimal inf = decimal(fila.get("limite_inferior"));
        BigDecimal sup = decimal(fila.get("limite_superior"));
        BigDecimal semi = sup.subtract(inf).divide(BigDecimal.valueOf(2), 0, RoundingMode.HALF_UP);

        kpis.add(kpi("Previsión " + anclas.primerPrevisto(), valor, "numero"));
        kpis.add(kpi("Banda 80 %", "± " + semi.toPlainString() + " uds", "texto"));
        kpis.add(kpi("MAPE del backtest", fila.get("mape_backtest"), "porcentaje"));
        kpis.add(kpi("La vara: ingenuo estacional", fila.get("mape_linea_base"), "porcentaje"));
        kpis.add(kpi("MAE del backtest", fila.get("mae_backtest"), "numero"));
        kpis.add(kpi("Meses de historia", fila.get("meses_historia"), "numero"));
        kpis.add(kpi("Observaciones de ese mes", fila.get("observaciones_mes"), "numero"));
        kpis.add(kpi("Método", metodoLegible(String.valueOf(fila.get("metodo"))), "texto"));

        if ("variante".equals(nivel)) {
            Integer conPrevision = ch.queryForObject(
                    "SELECT countDistinct(producto_variante_id) FROM " + DWH + "." + TABLA
                    + " WHERE nivel = 'variante'", Integer.class);
            Integer catalogo = ch.queryForObject(
                    "SELECT count() FROM " + dimension("dim_producto"), Integer.class);
            if (conPrevision != null && catalogo != null) {
                kpis.add(kpi("Variantes sin previsión propia",
                        catalogo - conPrevision, "numero"));
            }
        }
        return kpis;
    }

    private static String metodoLegible(String metodo) {
        return switch (metodo) {
            case "descomposicion" -> "descomposición estacional encogida";
            case "linea_base_estacional" -> "línea base estacional (el modelo no la superó)";
            case "top_down_categoria" -> "cuota sobre la previsión de su categoría";
            case SIN_PREVISION -> "sin previsión individual";
            default -> metodo;
        };
    }

    private static BigDecimal decimal(Object valor) {
        if (valor instanceof BigDecimal b) {
            return b;
        }
        return valor == null ? BigDecimal.ZERO : new BigDecimal(valor.toString());
    }

    // ── Las cinco limitaciones de §5.1.10, en pantalla ───────────────────

    /**
     * La salvedad que se lee ANTES que la tabla.
     *
     * No es documentación trasladada: quien mira esta pantalla toma decisiones
     * de compra y de meta con ella, y las cinco limitaciones cambian cómo hay
     * que leer cada cifra. La segunda —**se prevé la venta, no la demanda**— es
     * la que puede costar dinero: un modelo entrenado sobre venta aprende el
     * quiebre de stock como demanda baja y lo perpetúa comprando de menos.
     *
     * Dos partes son DINÁMICAS y se calculan en cada petición, porque escribir
     * a mano un mes o una cifra que el ETL puede cambiar es fabricar una
     * salvedad que caduca sin avisar: el mes truncado y el desajuste entre la
     * suma de las categorías y el total.
     */
    private String salvedad(Anclas anclas) {
        StringBuilder s = new StringBuilder();
        s.append("1) La historia son 19 meses: cada mes del calendario tiene como mucho "
                + "DOS observaciones, y los de agosto a diciembre solo UNA. El factor "
                + "estacional de esos cinco meses es un dato, no una estimación, y la "
                + "banda lo refleja siendo más ancha. ");
        s.append("2) Se prevé la VENTA, no la demanda: cuando un producto estuvo sin "
                + "stock la venta fue baja porque no había qué vender, y el sistema NO "
                + "registra la venta perdida, así que un quiebre se lee como demanda "
                + "baja. Las series afectadas van marcadas en la columna «censurada». ");
        if (anclas.truncado()) {
            s.append("3) El último mes observado (").append(anclas.ultimoObservado())
             .append(") está INCOMPLETO y se excluyó del entrenamiento; en el gráfico "
                     + "va marcado aparte. Por eso la primera previsión (")
             .append(anclas.primerPrevisto())
             .append(") está a dos meses del último dato usado, y su banda es la que "
                     + "corresponde a esa distancia. ");
        } else {
            s.append("3) El último mes observado está completo y entró al "
                    + "entrenamiento. ");
        }
        s.append("4) La historia disponible es un SEED SIMULADO con una curva mensual "
                + "conocida y un escalón de crecimiento fijo: el error medido describe "
                + "la calidad del método sobre estos datos y NO es evidencia de "
                + "conocimiento del mercado real de Quevedo. ");
        s.append(sinPrevisionIndividual());
        s.append(descuadreAgregacion());
        return s.toString().trim();
    }

    private String sinPrevisionIndividual() {
        Integer conPrevision = ch.queryForObject(
                "SELECT countDistinct(producto_variante_id) FROM " + DWH + "." + TABLA
                + " WHERE nivel = 'variante'", Integer.class);
        Integer catalogo = ch.queryForObject(
                "SELECT count() FROM " + dimension("dim_producto"), Integer.class);
        if (conPrevision == null || catalogo == null) {
            return "";
        }
        return "5) " + (catalogo - conPrevision) + " de " + catalogo + " variantes no "
                + "tienen previsión individual —les falta historia—: se les aplica la "
                + "de su categoría, y la fila lo dice. ";
    }

    /**
     * Cada nivel se ajusta por SEPARADO, así que la suma de las categorías no
     * es idéntica al total. Se dice con su cifra, porque quien lea la tabla por
     * categoría va a sumarla y va a encontrar la diferencia.
     */
    private String descuadreAgregacion() {
        List<Map<String, Object>> filas = ch.queryForList(
                "SELECT sumIf(unidades_previstas, nivel = 'categoria') AS suma, "
                + "sumIf(unidades_previstas, nivel = 'total') AS total "
                + "FROM " + DWH + "." + TABLA + " WHERE horizonte = 1", new Object[0]);
        if (filas.isEmpty()) {
            return "";
        }
        BigDecimal suma = decimal(filas.get(0).get("suma"));
        BigDecimal total = decimal(filas.get(0).get("total"));
        if (total.signum() == 0) {
            return "";
        }
        BigDecimal desvio = suma.subtract(total)
                .divide(total, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(1, RoundingMode.HALF_UP);
        return "6) El total y las categorías se ajustan por SEPARADO: la suma de las "
                + "categorías difiere del total en " + desvio.toPlainString() + " %. "
                + "No están reconciliadas, y para dimensionar una compra hay que usar "
                + "un nivel u otro, no mezclarlos.";
    }
}
