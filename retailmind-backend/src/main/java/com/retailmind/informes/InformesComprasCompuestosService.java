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
 * INFORMES TÁCTICOS COMPUESTOS — COMPRAS. Fuente: ClickHouse
 * ({@code retailmind_dwh}), no PostgreSQL.
 *
 * Sirve los SIETE objetivos compuestos del departamento:
 *
 * <ul>
 *   <li><b>OTD-COM-03</b> {@link #puntualidadPago} — ¿pagamos a tiempo?
 *       ({@code fact_flujo_caja}, lado del egreso).</li>
 *   <li><b>OTD-COM-04</b> {@link #gastoMensual} — cuánto gastamos, por proveedor
 *       y por mes ({@code fact_orden_compra}).</li>
 *   <li><b>OTD-COM-05</b> {@link #cumplimientoPlazo} — promesa contra llegada.</li>
 *   <li><b>OTD-COM-06</b> {@link #cicloCompra} — días reales del ciclo.</li>
 *   <li><b>OTD-COM-07</b> {@link #rechazos} — mercancía rechazada en puerta
 *       ({@code fact_compra_linea}).</li>
 *   <li><b>OTD-COM-09</b> {@link #recuperacionProveedor} — recuperación al
 *       proveedor ({@code fact_devolucion_proveedor}, Fase 4).</li>
 *   <li><b>OTD-COM-12</b> {@link #evolucionCosto} — cómo cambia el precio de
 *       compra entre una compra y la siguiente.</li>
 * </ul>
 *
 * OTD-COM-11 (entregas incompletas) NO está aquí: el catálogo lo clasifica
 * SIMPLE —agrega sobre la foto presente, no compara períodos— y vive en
 * {@link InformesComprasService} contra PostgreSQL.
 *
 * Ninguno de estos métodos lleva {@code @Transactional}: no tocan PostgreSQL.
 *
 * <h2>Dos trampas de ClickHouse que este archivo tiene que respetar</h2>
 * <ol>
 *   <li><b>Ningún alias de agregado puede llamarse como una columna</b> que se
 *       use dentro de otro agregado: {@code sum(monto) AS monto} seguido de
 *       {@code sumIf(monto, …)} revienta con {@code ILLEGAL_AGGREGATION}. Por eso
 *       aquí el total del dinero se llama {@code monto_pagado} y no
 *       {@code monto}. Ocurrió al escribir COM-03 y no es un detalle de estilo:
 *       el error solo aparece en tiempo de ejecución.</li>
 *   <li><b>{@code lagInFrame} devuelve el DEFECTO del tipo, no NULL</b>, en la
 *       primera fila de cada partición. En COM-12 eso significa que el precio
 *       anterior de la primera compra llega como <b>0,00</b>, y compararlo daría
 *       «subida del 100 %» en las 1.041 primeras compras. La frontera se marca
 *       con {@code row_number() > 1} y nunca con {@code precio_previo != 0}.</li>
 * </ol>
 */
@Service
public class InformesComprasCompuestosService extends InformeCompuestoServiceBase {

    private static final String TABLA = "fact_devolucion_proveedor";
    private static final String TABLA_OC = "fact_orden_compra";
    private static final String TABLA_LINEA = "fact_compra_linea";
    private static final String TABLA_CAJA = "fact_flujo_caja";

    /** Los 2 tipos del CHECK + la etiqueta del ETL para lo aún sin resolver. */
    private static final Set<String> RESOLUCIONES =
            Set.of("nota_credito", "reposicion", "sin_resolver");

    /**
     * Los orígenes REALES del pool de defectuosos.
     *
     * OJO: el diseño del pipeline (§5.14) declara {@code inspeccion_rma} /
     * {@code recepcion_compra}. Esos valores NO existen en la base — el CHECK
     * de {@code item_defectuoso} solo admite {@code rma} y {@code recepcion}, y
     * un filtro escrito desde el diseño casa con CERO filas sin dar error.
     * Corrección C4.7 de {@code CORRECCIONES_DISENO_ETL.md}: esta lista sale de
     * los datos, no del documento. Es el mismo hallazgo que C3C.3 con la acción
     * de las novedades de envío, una fase más tarde.
     */
    private static final Set<String> ORIGENES = Set.of("rma", "recepcion");

    /** Los 3 estados del pool. */
    private static final Set<String> ESTADOS = Set.of("pendiente", "en_devolucion", "resuelto");

    /** Ejes del informe. El proveedor es la pregunta; el mes, su evolución. */
    private static final Set<String> EJES =
            Set.of("proveedor", "mes", "categoria", "resolucion");

    // ── Listas blancas de los informes nuevos ────────────────────────────

    /** COM-03: cómo se quiere ver la puntualidad. */
    private static final Set<String> EJES_PAGO = Set.of("proveedor", "mes", "metodo");

    /** COM-03: los DOS tipos de método con que se paga a proveedor, medidos. */
    private static final Set<String> METODOS_PAGO = Set.of("transferencia", "efectivo");

    /** COM-03: el veredicto sobre un pago. `anticipado` es un subconjunto de a_tiempo. */
    private static final Set<String> PUNTUALIDADES = Set.of("a_tiempo", "tarde", "anticipado");

    /** COM-04. El mes por defecto es el de la FACTURA — ver el javadoc del método. */
    private static final Set<String> EJES_GASTO = Set.of("mes", "proveedor", "bodega");

    /** COM-05. */
    private static final Set<String> EJES_PLAZO = Set.of("proveedor", "mes");
    private static final Set<String> RESULTADOS_PLAZO = Set.of("cumplio", "incumplio");

    /** COM-06. */
    private static final Set<String> EJES_CICLO = Set.of("proveedor", "mes", "bodega");

    /** COM-07. */
    private static final Set<String> EJES_RECHAZO =
            Set.of("proveedor", "motivo", "mes", "categoria");

    /**
     * COM-07 — los CINCO motivos reales, con un slug estable para la API.
     *
     * El valor guardado es la FRASE, no un código: la corrección C3.3 decidió
     * normalizar el vocabulario sin cambiar el idioma, porque el origen ya
     * escribe frases legibles y convertirlas a slug obligaría a cada informe a
     * traducirlas de vuelta. Lo que NO puede viajar en una URL es una frase con
     * espacios, así que el filtro habla en slugs y este mapa es el único sitio
     * donde se cruzan las dos formas.
     *
     * Son cinco y no seis: {@code cajas mojadas en el transporte} —tecleado a
     * mano en la aplicación durante el desarrollo del script 45— lo funde el ETL
     * con {@code Empaque danado en transito}. Verificado contra el almacén: 5
     * motivos, 92 líneas, 185 unidades.
     */
    private static final Map<String, String> MOTIVOS_RECHAZO = new LinkedHashMap<>();
    static {
        MOTIVOS_RECHAZO.put("empaque_danado",       "Empaque danado en transito");
        MOTIVOS_RECHAZO.put("defecto_fabrica",      "Producto con defecto de fabrica");
        MOTIVOS_RECHAZO.put("caducidad_proxima",    "Fecha de caducidad proxima");
        MOTIVOS_RECHAZO.put("no_coincide",          "No coincide con especificacion");
        MOTIVOS_RECHAZO.put("unidades_incompletas", "Unidades incompletas en caja");
    }

    /** COM-12: qué le pasó al precio entre la primera compra y la última. */
    private static final Set<String> TENDENCIAS =
            Set.of("subio", "bajo", "estable", "sin_serie");

    /**
     * La muestra, tal cual, en la pantalla y junto al número.
     *
     * El catálogo clasifica COM-09 como <i>REQUIERE VOLUMEN</i>, y el diseño
     * insiste: «su informe debe mostrar la muestra junto al número, o inducirá
     * a decisiones sobre ruido». El pipeline no puede arreglarlo — el ciclo
     * completo opera desde el script 45 y escribe todo correctamente;
     * sencillamente ha ocurrido seis veces.
     */
    private static final String SALVEDAD_MUESTRA =
            "MUESTRA DÉBIL — léela antes que los promedios. El pool tiene 38 ítems "
            + "defectuosos y solo 8 devoluciones al proveedor, de las cuales 6 tienen "
            + "resolución, repartidas en 6 meses distintos y entre unos pocos de los 11 "
            + "proveedores. Un «monto medio recuperado por proveedor» calculado sobre UNA "
            + "devolución no es un promedio: la columna «Resoluciones» de cada fila es el "
            + "denominador y hay que mirarla primero. El ciclo funciona y escribe todo "
            + "desde el script 45; lo que falta es volumen, y eso no lo arregla el "
            + "informe.";

    /** COM-04: por qué el mes de este informe no es el mes de la orden. */
    private static final String SALVEDAD_MES_FACTURA =
            "El período de este informe es el mes de la FACTURA del proveedor, no el de "
            + "la orden de compra: el gasto se devenga cuando el proveedor factura lo que "
            + "entregó. No son lo mismo — 360 de las 839 facturas caen en un mes distinto "
            + "al de su orden, y agrupar por la orden movería $4.628.932,62 de mes "
            + "(enero de 2025 saldría un 52,6 % más alto y julio de 2026 un 46,8 % más "
            + "bajo). Quedan fuera 26 órdenes por $314.275,10 que nunca llegaron a "
            + "facturarse: son compromiso emitido, no gasto.";

    /** COM-05: sobre cuántas órdenes se puede realmente opinar. */
    private static final String SALVEDAD_PARES =
            "El cumplimiento solo se puede medir donde existen LAS DOS fechas. De 865 "
            + "órdenes, 849 tienen fecha prometida y 839 tienen recepción, pero solo 825 "
            + "tienen ambas: ése es el denominador de cada porcentaje y cada fila lo "
            + "declara en «Medidas». Las órdenes sin promesa (16) y las que aún no han "
            + "llegado o se cancelaron (40) se cuentan aparte y NO se reparten como "
            + "incumplimientos.";

    /** COM-06: el ciclo se mide donde hubo llegada, y ese conjunto se declara. */
    private static final String SALVEDAD_CICLO =
            "El ciclo se mide sobre las 839 órdenes que SÍ tienen recepción; las 26 "
            + "restantes (13 canceladas, 7 confirmadas y 6 enviadas) nunca llegaron y no "
            + "tienen ciclo que medir — se cuentan en «Sin recepción» y no arrastran el "
            + "promedio hacia arriba. La fecha de recepción se convierte a la zona del "
            + "negocio antes de restar (America/Guayaquil): sin esa conversión, 5 órdenes "
            + "ganan un día. La mediana es la ALTA (el valor en la posición n/2 de la "
            + "serie ordenada) cuando el número de órdenes es par.";

    /** COM-07: el porcentaje no se calcula sobre lo pedido. */
    private static final String SALVEDAD_RECHAZO =
            "El porcentaje de rechazo va sobre lo que FÍSICAMENTE LLEGÓ (aceptado + "
            + "rechazado), no sobre lo que se pidió. No es un matiz: en 37 de las 92 "
            + "líneas con rechazo el almacén registró la devolución POR ENCIMA de lo "
            + "recibido (pidió 7, llegaron 10, aceptó 7 y rechazó 3), así que sobre lo "
            + "pedido esa línea daría 42,9 % donde la verdad es 30,0 %. El sesgo es "
            + "siempre al alza y cae solo sobre algunos proveedores — justo el eje que "
            + "este informe existe para comparar.";

    /** COM-12: dos compras del mismo día y una fecha sin hora. */
    private static final String SALVEDAD_COSTO =
            "La serie de cada producto se ordena por fecha de emisión de la orden y, a "
            + "igualdad de fecha, por número de orden: la fecha de emisión es un día sin "
            + "hora y hay 16 pares (producto, proveedor) con dos compras el MISMO día, "
            + "que sin ese desempate quedarían en un orden arbitrario y darían una "
            + "variación distinta en cada consulta. La variación se mide entre compras "
            + "SUCESIVAS del mismo proveedor: un producto que se compra a dos proveedores "
            + "aparece en dos filas y sus precios no se comparan entre sí. Si filtras por "
            + "fechas, la serie se recorta a ese rango y «precio inicial» pasa a ser el "
            + "primero DENTRO del rango.";

    public InformesComprasCompuestosService(
            @Qualifier("pgJdbcTemplate") JdbcTemplate pg,
            @Qualifier("clickHouseJdbc") JdbcTemplate ch) {
        super(pg, ch);
    }

    /**
     * ¿Este rol recibe las columnas de dinero?
     *
     * Solo lo consultan OTD-COM-07 y OTD-COM-11, los dos informes MIXTOS del
     * departamento: el catálogo se los da a BODEGA «en cantidades, sin montos».
     * Todos los demás resuelven el corte en la RUTA, que es más simple y no deja
     * la decisión repartida en dos sitios.
     */
    private static boolean puedeVerDinero() {
        return !"BODEGA".equals(rolActual());
    }

    // ═════════════════════════════════════════════════════════════════════
    // OTD-COM-03 — ¿Le pagamos a tiempo al proveedor?
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Pagos hechos antes o después del vencimiento, por proveedor y por mes.
     *
     * <h3>Anticipo y retraso NO se promedian juntos</h3>
     * {@code dias_desvio_vencimiento} es negativo cuando se pagó ANTES. Un
     * promedio único los cancela, y el resultado es una cifra que suena
     * tranquilizadora sobre un proveedor al que se le paga tarde: Distribuidora
     * Deportiva Andina tiene un desvío medio de <b>−9,2 días</b> («pagamos con
     * nueve días de adelanto») y sin embargo <b>4 de sus 18 facturas se pagaron
     * tarde</b>, con 44 días de retraso acumulado. Por eso cada fila trae las
     * tres medidas separadas —desvío medio, anticipo medio y RETRASO medio, este
     * último solo sobre los pagos tardíos— y el conteo de cada grupo al lado.
     *
     * <h3>Pagar el día exacto es pagar a tiempo</h3>
     * {@code a_tiempo = 1} incluye el vencimiento mismo ({@code fecha_pago <=
     * fecha_vencimiento}), que es el criterio del ETL y el del negocio. Los 58
     * pagos hechos justo el día del vencimiento viajan además en su propia
     * columna, porque «puntual con margen» y «puntual por los pelos» no son la
     * misma señal para tesorería.
     *
     * <h3>Es un informe de DINERO</h3>
     * Bodega y Despacho quedan fuera por RUTA; el ANALISTA entra, como pide el
     * catálogo. En ClickHouse no hay GRANT por columna que respalde el corte.
     *
     * Verificado contra PostgreSQL: 902 pagos, $16.084.462,74, 564 a tiempo,
     * 11 proveedores y 19 meses, 0 diferencias fila a fila en los dos ejes.
     */
    public Map<String, Object> puntualidadPago(String desde, String hasta, String proveedor,
                                               String puntualidad, String metodo,
                                               String agrupar) {
        exigirRangoValido(fecha(desde, "desde"), fecha(hasta, "hasta"));
        String fPunt = opcion(puntualidad, PUNTUALIDADES, "puntualidad");
        String fMetodo = opcion(metodo, METODOS_PAGO, "metodo");
        String eje = agrupar == null || agrupar.isBlank()
                ? "proveedor" : opcion(agrupar, EJES_PAGO, "agrupar");

        return ejecutar("OTD-COM-03", () -> {
            Filtros f = filtrosPago(desde, hasta, proveedor, fPunt, fMetodo);

            String clave = switch (eje) {
                case "mes"    -> "formatDateTime(mes, '%Y-%m')";
                case "metodo" -> "metodo_pago";
                default       -> "contraparte_nombre";
            };

            List<Map<String, Object>> items = ch.queryForList("""
                SELECT %s                                            AS etiqueta,
                       count()                                       AS pagos,
                       sum(monto)                                    AS monto_pagado,
                       countIf(a_tiempo = 1)                         AS pagos_a_tiempo,
                       countIf(a_tiempo = 0)                         AS pagos_tarde,
                       toFloat64(round(countIf(a_tiempo = 1) * 100.0
                             / nullIf(count(), 0), 2))               AS pct_a_tiempo,
                       countIf(dias_desvio_vencimiento < 0)          AS pagos_anticipados,
                       countIf(dias_desvio_vencimiento = 0)          AS pagos_en_fecha,
                       sumIf(monto, a_tiempo = 0)                    AS monto_tarde,
                       -- Las TRES medidas por separado: promediarlas juntas
                       -- esconde al proveedor al que se paga tarde.
                       round(avg(dias_desvio_vencimiento), 2)        AS dias_desvio_medio,
                       round(avgIf(-dias_desvio_vencimiento,
                                   dias_desvio_vencimiento < 0), 2)  AS dias_anticipo_medio,
                       round(avgIf(dias_desvio_vencimiento,
                                   dias_desvio_vencimiento > 0), 2)  AS dias_retraso_medio,
                       max(dias_desvio_vencimiento)                  AS max_retraso,
                       countDistinct(contraparte_id)                 AS proveedores
                FROM %s.%s
                WHERE 1 %s
                GROUP BY etiqueta
                ORDER BY %s
                """.formatted(clave, DWH, TABLA_CAJA, f.where(),
                        "mes".equals(eje) ? "etiqueta ASC" : "monto_pagado DESC"), f.args());

            Map<String, Object> sobre = sobre(items);
            conResumen(sobre, kpisPuntualidad(f));
            return conMarcaDeAgua(sobre, TABLA_CAJA);
        });
    }

    /**
     * El lado del EGRESO y solo ése.
     *
     * {@code sentido = 'egreso'} es un fragmento CONSTANTE y no un filtro del
     * usuario: {@code fact_flujo_caja} lleva en la misma tabla los 4.079 cobros
     * de cliente, y sin este AND el informe sumaría los ingresos como si fueran
     * pagos a proveedor. Los cobros no tienen vencimiento, así que
     * {@code a_tiempo} llegaría NULL en el 82 % de las filas y los porcentajes
     * saldrían divididos por un denominador cinco veces mayor.
     */
    private Filtros filtrosPago(String desde, String hasta, String proveedor,
                                String puntualidad, String metodo) {
        Filtros f = new Filtros();
        f.y("sentido = 'egreso'");
        f.y("toDate(fecha) >= toDate(?)", fecha(desde, "desde"));
        f.y("toDate(fecha) <= toDate(?)", fecha(hasta, "hasta"));
        f.y("positionCaseInsensitive(contraparte_nombre, ?) > 0", texto(proveedor));
        f.y("metodo_pago_tipo = ?", metodo);
        if (puntualidad != null) {
            switch (puntualidad) {
                case "tarde"      -> f.y("a_tiempo = 0");
                case "anticipado" -> f.y("dias_desvio_vencimiento < 0");
                default           -> f.y("a_tiempo = 1");
            }
        }
        return f;
    }

    private List<Map<String, Object>> kpisPuntualidad(Filtros f) {
        // OJO con los alias: `AS a_tiempo` sobre una columna que se llama
        // `a_tiempo` y que más abajo entra en `sumIf(monto, a_tiempo = 0)` es un
        // ILLEGAL_AGGREGATION en ClickHouse, no un aviso de estilo. Por eso los
        // conteos van prefijados con `n_`.
        Map<String, Object> t = ch.queryForMap("""
            SELECT count()                                        AS pagos,
                   sum(monto)                                     AS monto_pagado,
                   countIf(a_tiempo = 1)                          AS n_a_tiempo,
                   countIf(a_tiempo = 0)                          AS n_tarde,
                   countIf(dias_desvio_vencimiento < 0)           AS anticipados,
                   countIf(dias_desvio_vencimiento = 0)           AS en_fecha,
                   sumIf(monto, a_tiempo = 0)                     AS monto_tarde,
                   toFloat64(round(countIf(a_tiempo = 1) * 100.0
                         / nullIf(count(), 0), 2))                AS pct,
                   round(avg(dias_desvio_vencimiento), 2)         AS desvio,
                   round(avgIf(-dias_desvio_vencimiento,
                               dias_desvio_vencimiento < 0), 2)   AS anticipo,
                   round(avgIf(dias_desvio_vencimiento,
                               dias_desvio_vencimiento > 0), 2)   AS retraso,
                   max(dias_desvio_vencimiento)                   AS peor,
                   countDistinct(contraparte_id)                  AS proveedores,
                   countDistinct(mes)                             AS meses
            FROM %s.%s WHERE 1 %s
            """.formatted(DWH, TABLA_CAJA, f.where()), f.args());

        List<Map<String, Object>> k = new ArrayList<>();
        k.add(kpi("Pagos a proveedor", t.get("pagos"), "numero"));
        k.add(kpi("Importe pagado", cero(t.get("monto_pagado")), "moneda"));
        k.add(kpi("Pagados a tiempo", t.get("n_a_tiempo"), "numero"));
        k.add(kpi("Pagados tarde", t.get("n_tarde"), "numero"));
        k.add(kpi("Puntualidad", cero(t.get("pct")), "porcentaje"));
        k.add(kpi("Importe pagado tarde", cero(t.get("monto_tarde")), "moneda"));
        k.add(kpi("Anticipados", t.get("anticipados"), "numero"));
        k.add(kpi("Justo el día del vencimiento", t.get("en_fecha"), "numero"));
        k.add(kpi("Anticipo medio", cero(t.get("anticipo")), "dias"));
        k.add(kpi("Retraso medio (solo tardíos)", cero(t.get("retraso")), "dias"));
        k.add(kpi("Peor retraso", cero(t.get("peor")), "dias"));
        k.add(kpi("Proveedores", t.get("proveedores"), "numero"));
        k.add(kpi("Meses con pagos", t.get("meses"), "numero"));
        return k;
    }

    // ═════════════════════════════════════════════════════════════════════
    // OTD-COM-04 — Cuánto gastamos en compras, por proveedor y por mes
    // ═════════════════════════════════════════════════════════════════════

    /**
     * El gasto de compras devengado, con su compromiso al lado.
     *
     * <h3>El gasto es la FACTURA, nunca el total de la orden</h3>
     * Corrección C3.6: difieren en 119 de las 838 órdenes con ambos documentos,
     * por −$226.070,31, porque 72 órdenes quedaron en {@code recibida_parcial} y
     * el proveedor factura <b>lo que entregó</b>. Sumar la orden inventaría un
     * +2,4 % de gasto ($22,47 M → $23,01 M) y dejaría la balanza de GER-02 sin
     * cuadrar nunca contra contabilidad. Las dos cifras viajan juntas y su
     * diferencia es una columna del informe, {@code brecha}: es la pregunta «¿qué
     * parte de lo que pedí no me llegó a facturar?», no un descuadre.
     *
     * <h3>El mes es el de la FACTURA, y eso cambia la serie</h3>
     * El almacén trae {@code mes} = mes de emisión de la ORDEN, que es el eje
     * natural de {@code fact_orden_compra} y el correcto para COM-05/06. Para el
     * GASTO no sirve: <b>360 de las 839 facturas caen en un mes distinto al de su
     * orden</b>, y agrupar por el de la orden desplaza <b>$4.628.932,62</b> —el
     * 20,6 % del gasto total— entre meses, inflando enero de 2025 un 52,6 % y
     * hundiendo julio de 2026 un 46,8 %. Ninguna suma se rompe: el total anual
     * es idéntico y solo la forma de la curva miente. Por eso este informe
     * agrupa por {@code toStartOfMonth(fecha_factura)} y lo dice en la salvedad.
     *
     * <h3>Sin factura no hay gasto</h3>
     * Las 26 órdenes sin factura ($314.275,10 de compromiso) quedan FUERA del
     * informe por construcción: no son gasto todavía. Se cuentan en un KPI para
     * que la ausencia sea visible en vez de simplemente no estar.
     *
     * Verificado contra PostgreSQL: 839 facturas, $22.467.387,27, 19 meses, 11
     * proveedores, 0 diferencias fila a fila.
     */
    public Map<String, Object> gastoMensual(String desde, String hasta, String proveedor,
                                            String agrupar) {
        exigirRangoValido(fecha(desde, "desde"), fecha(hasta, "hasta"));
        String eje = agrupar == null || agrupar.isBlank()
                ? "mes" : opcion(agrupar, EJES_GASTO, "agrupar");

        return ejecutar("OTD-COM-04", () -> {
            Filtros f = new Filtros();
            // Sin factura no hay gasto devengado: es el filtro que define el informe.
            f.y("fecha_factura IS NOT NULL");
            f.y("fecha_factura >= toDate(?)", fecha(desde, "desde"));
            f.y("fecha_factura <= toDate(?)", fecha(hasta, "hasta"));
            f.y("positionCaseInsensitive(proveedor, ?) > 0", texto(proveedor));

            String clave = switch (eje) {
                case "proveedor" -> "proveedor";
                case "bodega"    -> "bodega";
                default          -> "formatDateTime(toStartOfMonth(fecha_factura), '%Y-%m')";
            };

            List<Map<String, Object>> items = ch.queryForList("""
                SELECT %s                                        AS etiqueta,
                       count()                                   AS facturas,
                       sum(factura_total)                        AS gasto,
                       sum(total)                                AS comprometido_oc,
                       sum(factura_total) - sum(total)           AS brecha,
                       toFloat64(round((sum(factura_total) - sum(total)) * 100.0
                             / nullIf(sum(total), 0), 2))        AS brecha_pct,
                       countIf(estado = 'recibida_parcial')      AS ordenes_parciales,
                       sum(unidades_pedidas)                     AS uds_pedidas,
                       sum(unidades_recibidas)                   AS uds_recibidas,
                       sum(cxp_monto_original) - sum(cxp_saldo_pendiente) AS pagado,
                       sum(cxp_saldo_pendiente)                  AS saldo_cxp,
                       countDistinct(proveedor_id)               AS proveedores,
                       round(sum(factura_total)
                             / nullIf(count(), 0), 2)            AS factura_media
                FROM %s.%s
                WHERE 1 %s
                GROUP BY etiqueta
                ORDER BY %s
                """.formatted(clave, DWH, TABLA_OC, f.where(),
                        "mes".equals(eje) ? "etiqueta ASC" : "gasto DESC"), f.args());

            Map<String, Object> sobre = sobre(items);
            conResumen(sobre, kpisGasto(f));
            sobre.put("salvedad", SALVEDAD_MES_FACTURA);
            return conMarcaDeAgua(sobre, TABLA_OC);
        });
    }

    private List<Map<String, Object>> kpisGasto(Filtros f) {
        Map<String, Object> t = ch.queryForMap("""
            SELECT count()                                   AS facturas,
                   sum(factura_total)                        AS gasto,
                   sum(total)                                AS comprometido,
                   sum(factura_total) - sum(total)           AS brecha,
                   sum(cxp_monto_original) - sum(cxp_saldo_pendiente) AS pagado,
                   sum(cxp_saldo_pendiente)                  AS saldo,
                   countDistinct(proveedor_id)               AS proveedores,
                   countDistinct(toStartOfMonth(fecha_factura)) AS meses,
                   sum(unidades_recibidas)                   AS unidades,
                   countIf(estado = 'recibida_parcial')      AS parciales
            FROM %s.%s WHERE 1 %s
            """.formatted(DWH, TABLA_OC, f.where()), f.args());

        // Lo que NO entra en el informe, contado aparte: son compromiso, no gasto.
        Map<String, Object> sin = ch.queryForMap("""
            SELECT count()    AS ordenes,
                   sum(total) AS comprometido
            FROM %s.%s WHERE fecha_factura IS NULL
            """.formatted(DWH, TABLA_OC));

        List<Map<String, Object>> k = new ArrayList<>();
        k.add(kpi("Gasto facturado", cero(t.get("gasto")), "moneda"));
        k.add(kpi("Facturas de compra", t.get("facturas"), "numero"));
        k.add(kpi("Comprometido en órdenes", cero(t.get("comprometido")), "moneda"));
        k.add(kpi("Brecha orden-factura", cero(t.get("brecha")), "moneda"));
        k.add(kpi("Órdenes recibidas a medias", t.get("parciales"), "numero"));
        k.add(kpi("Pagado al proveedor", cero(t.get("pagado")), "moneda"));
        k.add(kpi("Saldo por pagar", cero(t.get("saldo")), "moneda"));
        k.add(kpi("Unidades recibidas", cero(t.get("unidades")), "numero"));
        k.add(kpi("Proveedores", t.get("proveedores"), "numero"));
        k.add(kpi("Meses con factura", t.get("meses"), "numero"));
        k.add(kpi("Órdenes sin facturar (fuera)", sin.get("ordenes"), "numero"));
        k.add(kpi("Compromiso sin facturar (fuera)", cero(sin.get("comprometido")), "moneda"));
        return k;
    }

    // ═════════════════════════════════════════════════════════════════════
    // OTD-COM-05 — ¿Cumple el proveedor el plazo que prometió?
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Promesa contra llegada real, con el denominador declarado en cada fila.
     *
     * <h3>825 de 865: la cobertura es parte del resultado</h3>
     * El veredicto solo existe donde hay promesa Y recepción. Las 16 órdenes sin
     * fecha prometida y las 40 sin recepción NO son incumplimientos: son
     * preguntas sin respuesta, y repartirlas en «cumplió/no cumplió» es
     * exactamente el error que C2.7 documentó en LOG-12. Cada fila trae
     * {@code medidas}, {@code sin_promesa} y {@code sin_recepcion}, y el
     * porcentaje se calcula sobre {@code medidas}.
     *
     * <h3>El corte está en cero, y por eso la zona horaria importa</h3>
     * {@code cumplio_promesa} lo precalcula el ETL como
     * {@code (fecha_recepcion AT TIME ZONE 'America/Guayaquil')::date <=
     * fecha_entrega_esperada} (corrección C3.4). Sin esa conversión, 5 de las 839
     * recepciones caen al día siguiente y cambian DE LADO en un informe cuyo
     * veredicto es binario: una orden puntual pasaría a contarse como
     * incumplimiento del proveedor.
     *
     * <h3>Sin dinero, y aun así cerrado</h3>
     * Este informe no selecciona ni un importe, pero el catálogo lo asigna solo a
     * Compras y Gerencia — es material de negociación con el proveedor, no un
     * indicador de operación. Se respeta el catálogo: Bodega, Despacho, Soporte,
     * Vendedor y Analista quedan fuera por RUTA. Es el único compuesto de
     * Compras del que también sale el ANALISTA.
     *
     * Verificado contra PostgreSQL: 825 pares medidos, 449 cumplidas, 0
     * diferencias fila a fila en los 11 proveedores.
     */
    public Map<String, Object> cumplimientoPlazo(String desde, String hasta, String proveedor,
                                                 String resultado, String agrupar) {
        exigirRangoValido(fecha(desde, "desde"), fecha(hasta, "hasta"));
        String fRes = opcion(resultado, RESULTADOS_PLAZO, "resultado");
        String eje = agrupar == null || agrupar.isBlank()
                ? "proveedor" : opcion(agrupar, EJES_PLAZO, "agrupar");

        return ejecutar("OTD-COM-05", () -> {
            Filtros f = new Filtros();
            f.y("fecha_emision >= toDate(?)", fecha(desde, "desde"));
            f.y("fecha_emision <= toDate(?)", fecha(hasta, "hasta"));
            f.y("positionCaseInsensitive(proveedor, ?) > 0", texto(proveedor));
            if (fRes != null) {
                f.y("cumplio".equals(fRes) ? "cumplio_promesa = 1" : "cumplio_promesa = 0");
            }

            String clave = "mes".equals(eje)
                    ? "formatDateTime(mes, '%Y-%m')" : "proveedor";

            List<Map<String, Object>> items = ch.queryForList("""
                SELECT %s                                          AS etiqueta,
                       count()                                     AS ordenes,
                       -- El denominador, en la propia fila. Sin él, los tres
                       -- porcentajes de abajo no se pueden leer.
                       countIf(cumplio_promesa IS NOT NULL)        AS medidas,
                       countIf(cumplio_promesa = 1)                AS cumplidas,
                       countIf(cumplio_promesa = 0)                AS incumplidas,
                       toFloat64(round(countIf(cumplio_promesa = 1) * 100.0
                             / nullIf(countIf(cumplio_promesa IS NOT NULL), 0), 2))
                                                                   AS pct_cumplimiento,
                       countIf(fecha_entrega_esperada IS NULL)     AS sin_promesa,
                       countIf(fecha_recepcion IS NULL)            AS sin_recepcion,
                       round(avgIf(dias_desvio_promesa,
                                   cumplio_promesa IS NOT NULL), 2) AS dias_desvio_medio,
                       round(avgIf(dias_desvio_promesa,
                                   cumplio_promesa = 0), 2)        AS dias_retraso_medio,
                       round(avgIf(-dias_desvio_promesa,
                                   cumplio_promesa = 1), 2)        AS dias_adelanto_medio,
                       maxIf(dias_desvio_promesa,
                             cumplio_promesa IS NOT NULL)          AS peor_retraso,
                       sumIf(dias_desvio_promesa, cumplio_promesa = 0) AS dias_retraso_total
                FROM %s.%s
                WHERE 1 %s
                GROUP BY etiqueta
                ORDER BY %s
                """.formatted(clave, DWH, TABLA_OC, f.where(),
                        "mes".equals(eje) ? "etiqueta ASC" : "pct_cumplimiento ASC"), f.args());

            Map<String, Object> sobre = sobre(items);
            conResumen(sobre, kpisPlazo(f));
            sobre.put("salvedad", SALVEDAD_PARES);
            return conMarcaDeAgua(sobre, TABLA_OC);
        });
    }

    private List<Map<String, Object>> kpisPlazo(Filtros f) {
        Map<String, Object> t = ch.queryForMap("""
            SELECT count()                                    AS ordenes,
                   countIf(cumplio_promesa IS NOT NULL)       AS medidas,
                   countIf(cumplio_promesa = 1)               AS cumplidas,
                   countIf(cumplio_promesa = 0)               AS incumplidas,
                   toFloat64(round(countIf(cumplio_promesa = 1) * 100.0
                         / nullIf(countIf(cumplio_promesa IS NOT NULL), 0), 2)) AS pct,
                   countIf(fecha_entrega_esperada IS NULL)    AS sin_promesa,
                   countIf(fecha_recepcion IS NULL)           AS sin_recepcion,
                   round(avgIf(dias_desvio_promesa,
                               cumplio_promesa IS NOT NULL), 2) AS desvio,
                   round(avgIf(dias_desvio_promesa, cumplio_promesa = 0), 2) AS retraso,
                   maxIf(dias_desvio_promesa, cumplio_promesa IS NOT NULL)   AS peor,
                   countDistinct(proveedor_id)                AS proveedores
            FROM %s.%s WHERE 1 %s
            """.formatted(DWH, TABLA_OC, f.where()), f.args());

        List<Map<String, Object>> k = new ArrayList<>();
        k.add(kpi("Órdenes medidas (la base)", t.get("medidas"), "numero"));
        k.add(kpi("Órdenes en el filtro", t.get("ordenes"), "numero"));
        k.add(kpi("Llegaron en plazo", t.get("cumplidas"), "numero"));
        k.add(kpi("Llegaron tarde", t.get("incumplidas"), "numero"));
        k.add(kpi("Cumplimiento", cero(t.get("pct")), "porcentaje"));
        k.add(kpi("Desvío medio", cero(t.get("desvio")), "dias"));
        k.add(kpi("Retraso medio (solo tardías)", cero(t.get("retraso")), "dias"));
        k.add(kpi("Peor retraso", cero(t.get("peor")), "dias"));
        k.add(kpi("Sin fecha prometida", t.get("sin_promesa"), "numero"));
        k.add(kpi("Sin recepción todavía", t.get("sin_recepcion"), "numero"));
        k.add(kpi("Proveedores", t.get("proveedores"), "numero"));
        return k;
    }

    // ═════════════════════════════════════════════════════════════════════
    // OTD-COM-06 — Cuántos días tarda de verdad una compra en llegar
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Días reales de emisión → recepción, exista o no promesa de por medio.
     *
     * <h3>Qué lo separa de COM-05</h3>
     * COM-05 juzga al proveedor contra lo que dijo; éste mide lo que TARDA, sin
     * juicio. Por eso su base no son los 825 pares con promesa sino las <b>839
     * órdenes con recepción</b>: entran también las 14 que llegaron sin fecha
     * prometida, que para COM-05 no existen. Son dos poblaciones distintas a
     * propósito y cada informe declara la suya.
     *
     * <h3>Promedio y mediana, juntos</h3>
     * El promedio del ciclo es 10,81 días, pero la distribución no es simétrica:
     * el mínimo es 0 y el máximo 25. Se publican los dos, más los tramos «hasta
     * 7 días» y «más de 14», que es lo que de verdad se negocia con un proveedor.
     * La mediana es la ALTA —el elemento en la posición n/2 con la serie
     * ordenada, contando desde cero— porque es la definición de
     * {@code quantileExact(0.5)} de ClickHouse; con un número par de órdenes NO
     * coincide con {@code percentile_disc} de PostgreSQL, y el control de
     * validación reproduce la definición del motor en vez de suponerlas iguales.
     *
     * Verificado contra PostgreSQL: 839 órdenes medidas, 9.073 días acumulados,
     * 0 diferencias fila a fila (incluida la mediana) en los 11 proveedores.
     */
    public Map<String, Object> cicloCompra(String desde, String hasta, String proveedor,
                                           String agrupar) {
        exigirRangoValido(fecha(desde, "desde"), fecha(hasta, "hasta"));
        String eje = agrupar == null || agrupar.isBlank()
                ? "proveedor" : opcion(agrupar, EJES_CICLO, "agrupar");

        return ejecutar("OTD-COM-06", () -> {
            Filtros f = new Filtros();
            f.y("fecha_emision >= toDate(?)", fecha(desde, "desde"));
            f.y("fecha_emision <= toDate(?)", fecha(hasta, "hasta"));
            f.y("positionCaseInsensitive(proveedor, ?) > 0", texto(proveedor));

            String clave = switch (eje) {
                case "mes"    -> "formatDateTime(mes, '%Y-%m')";
                case "bodega" -> "bodega";
                default       -> "proveedor";
            };

            List<Map<String, Object>> items = ch.queryForList("""
                SELECT %s                                       AS etiqueta,
                       count()                                  AS ordenes,
                       countIf(dias_ciclo_real IS NOT NULL)     AS medidas,
                       countIf(fecha_recepcion IS NULL)         AS sin_recepcion,
                       round(avg(dias_ciclo_real), 2)           AS dias_promedio,
                       quantileExact(0.5)(dias_ciclo_real)      AS dias_mediana,
                       min(dias_ciclo_real)                     AS dias_min,
                       max(dias_ciclo_real)                     AS dias_max,
                       countIf(dias_ciclo_real <= 7)            AS hasta_7_dias,
                       countIf(dias_ciclo_real > 14)            AS mas_de_14_dias,
                       toFloat64(round(countIf(dias_ciclo_real <= 7) * 100.0
                             / nullIf(countIf(dias_ciclo_real IS NOT NULL), 0), 2))
                                                                AS pct_hasta_7,
                       sum(unidades_recibidas)                  AS uds_recibidas,
                       countDistinct(proveedor_id)              AS proveedores
                FROM %s.%s
                WHERE 1 %s
                GROUP BY etiqueta
                ORDER BY %s
                """.formatted(clave, DWH, TABLA_OC, f.where(),
                        "mes".equals(eje) ? "etiqueta ASC" : "dias_promedio DESC"), f.args());

            Map<String, Object> sobre = sobre(items);
            conResumen(sobre, kpisCiclo(f));
            sobre.put("salvedad", SALVEDAD_CICLO);
            return conMarcaDeAgua(sobre, TABLA_OC);
        });
    }

    private List<Map<String, Object>> kpisCiclo(Filtros f) {
        Map<String, Object> t = ch.queryForMap("""
            SELECT count()                                AS ordenes,
                   countIf(dias_ciclo_real IS NOT NULL)   AS medidas,
                   countIf(fecha_recepcion IS NULL)       AS sin_recepcion,
                   round(avg(dias_ciclo_real), 2)         AS promedio,
                   quantileExact(0.5)(dias_ciclo_real)    AS mediana,
                   min(dias_ciclo_real)                   AS minimo,
                   max(dias_ciclo_real)                   AS maximo,
                   countIf(dias_ciclo_real <= 7)          AS rapidas,
                   countIf(dias_ciclo_real > 14)          AS lentas,
                   countDistinct(proveedor_id)            AS proveedores,
                   countDistinct(mes)                     AS meses
            FROM %s.%s WHERE 1 %s
            """.formatted(DWH, TABLA_OC, f.where()), f.args());

        List<Map<String, Object>> k = new ArrayList<>();
        k.add(kpi("Órdenes con llegada (la base)", t.get("medidas"), "numero"));
        k.add(kpi("Órdenes en el filtro", t.get("ordenes"), "numero"));
        k.add(kpi("Ciclo medio", cero(t.get("promedio")), "dias"));
        k.add(kpi("Ciclo mediano", cero(t.get("mediana")), "dias"));
        k.add(kpi("La más rápida", cero(t.get("minimo")), "dias"));
        k.add(kpi("La más lenta", cero(t.get("maximo")), "dias"));
        k.add(kpi("Llegaron en 7 días o menos", t.get("rapidas"), "numero"));
        k.add(kpi("Tardaron más de 14 días", t.get("lentas"), "numero"));
        k.add(kpi("Sin recepción todavía", t.get("sin_recepcion"), "numero"));
        k.add(kpi("Proveedores", t.get("proveedores"), "numero"));
        k.add(kpi("Meses con órdenes", t.get("meses"), "numero"));
        return k;
    }

    // ═════════════════════════════════════════════════════════════════════
    // OTD-COM-07 — Mercancía rechazada en puerta, por proveedor y motivo
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Cuánto llega mal y por qué, con el porcentaje sobre lo que llegó.
     *
     * <h3>El denominador es lo que LLEGÓ, no lo que se pidió</h3>
     * Corrección C3.2, y es el corazón del informe. {@code cantidad_recibida} y
     * {@code cantidad_rechazada} no guardan relación constante con lo pedido: en
     * 49 líneas el rechazo se descontó de lo aceptado, en <b>37 va por encima</b>
     * (pidió 7, llegaron 10, aceptó 7, rechazó 3) y en 6 hay rechazo y entrega
     * parcial a la vez. Sobre «lo pedido», esa línea real da 42,9 % donde la
     * verdad es 30,0 %. En el agregado la diferencia parece nada (0,1559 % contra
     * 0,1542 %), pero este informe <b>no se lee en el agregado</b>: se lee por
     * proveedor, y el sesgo cae solo sobre aquellos cuyo almacén registró el
     * rechazo de la manera aditiva. El eje que se corrompe es justo el que el
     * informe existe para comparar.
     *
     * <h3>Cinco motivos, no seis</h3>
     * El motivo es texto libre y el ETL lo normaliza con lista blanca y regla de
     * escape (corrección C3.3): {@code cajas mojadas en el transporte} —tecleado
     * a mano— se funde con {@code Empaque danado en transito}. Contra PostgreSQL
     * en crudo aparecen 6 valores; en el almacén hay 5, y esa diferencia de −1 en
     * un proveedor es la normalización funcionando, no un descuadre.
     *
     * <h3>MIXTO: Bodega entra, y sin importes</h3>
     * El catálogo se lo da a Bodega «en cantidades, sin montos». Aquí no basta
     * con la ruta —hace falta que Bodega ENTRE— ni basta el motor: ClickHouse no
     * tiene GRANT por columna, y en PostgreSQL grp_bodega conserva SELECT sobre
     * {@code orden_compra_detalle.precio_unitario} (excepción del script 41, la
     * necesita para valorizar el kardex). El corte lo hace la CONSULTA: cuando
     * pregunta Bodega, la columna {@code valor_rechazado} no se selecciona.
     *
     * Verificado contra PostgreSQL: 2.949 líneas, 92 con rechazo, 185 unidades,
     * $27.557,63 rechazados, 5 motivos; 0 diferencias por proveedor y por motivo.
     */
    public Map<String, Object> rechazos(String desde, String hasta, String proveedor,
                                        String motivo, String soloConRechazo, String agrupar) {
        exigirRangoValido(fecha(desde, "desde"), fecha(hasta, "hasta"));
        String slug = opcion(motivo, MOTIVOS_RECHAZO.keySet(), "motivo");
        String eje = agrupar == null || agrupar.isBlank()
                ? "proveedor" : opcion(agrupar, EJES_RECHAZO, "agrupar");
        boolean soloRechazo = "true".equalsIgnoreCase(texto(soloConRechazo))
                || "motivo".equals(eje);   // agrupar por motivo implica tener uno
        boolean conDinero = puedeVerDinero();

        return ejecutar("OTD-COM-07", () -> {
            Filtros f = new Filtros();
            f.y("fecha_emision >= toDate(?)", fecha(desde, "desde"));
            f.y("fecha_emision <= toDate(?)", fecha(hasta, "hasta"));
            f.y("positionCaseInsensitive(proveedor, ?) > 0", texto(proveedor));
            f.y("motivo_rechazo = ?", slug == null ? null : MOTIVOS_RECHAZO.get(slug));
            if (soloRechazo) {
                f.y("cantidad_rechazada > 0");
            }

            String clave = switch (eje) {
                case "motivo"    -> "motivo_rechazo";
                case "mes"       -> "formatDateTime(mes, '%Y-%m')";
                case "categoria" -> "categoria";
                default          -> "proveedor";
            };

            // La única columna de dinero del informe. Ver el javadoc: Bodega no
            // la recibe, y el motor no lo impediría.
            String columnaValor = conDinero ? """
                ,      round(sum(cantidad_rechazada * precio_unitario), 2) AS valor_rechazado
                """ : "";

            List<Map<String, Object>> items = ch.queryForList("""
                SELECT %s                                            AS etiqueta,
                       count()                                       AS lineas,
                       countIf(cantidad_rechazada > 0)               AS lineas_con_rechazo,
                       sum(cantidad_pedida)                          AS uds_pedidas,
                       sum(cantidad_recibida)                        AS uds_aceptadas,
                       sum(cantidad_rechazada)                       AS uds_rechazadas,
                       -- El DENOMINADOR, visible: aceptado + rechazado.
                       sum(cantidad_recibida + cantidad_rechazada)   AS uds_llegadas,
                       toFloat64(round(sum(cantidad_rechazada) * 100.0
                             / nullIf(sum(cantidad_recibida + cantidad_rechazada), 0), 2))
                                                                     AS pct_rechazo
                       %s,
                       countDistinctIf(motivo_rechazo, motivo_rechazo != '') AS motivos,
                       countDistinct(proveedor_id)                   AS proveedores,
                       countDistinct(orden_compra_id)                AS ordenes
                FROM %s.%s
                WHERE 1 %s
                GROUP BY etiqueta
                ORDER BY %s
                """.formatted(clave, columnaValor, DWH, TABLA_LINEA, f.where(),
                        "mes".equals(eje) ? "etiqueta ASC" : "uds_rechazadas DESC"), f.args());

            Map<String, Object> sobre = sobre(items);
            conResumen(sobre, kpisRechazo(f, conDinero));
            sobre.put("conValorizacion", conDinero);
            sobre.put("salvedad", SALVEDAD_RECHAZO);
            return conMarcaDeAgua(sobre, TABLA_LINEA);
        });
    }

    private List<Map<String, Object>> kpisRechazo(Filtros f, boolean conDinero) {
        String columnaValor = conDinero ? """
            ,   round(sum(cantidad_rechazada * precio_unitario), 2) AS valor
            """ : "";

        Map<String, Object> t = ch.queryForMap("""
            SELECT count()                                     AS lineas,
                   countIf(cantidad_rechazada > 0)             AS con_rechazo,
                   sum(cantidad_pedida)                        AS pedidas,
                   sum(cantidad_recibida)                      AS aceptadas,
                   sum(cantidad_rechazada)                     AS rechazadas,
                   sum(cantidad_recibida + cantidad_rechazada) AS llegadas,
                   toFloat64(round(sum(cantidad_rechazada) * 100.0
                         / nullIf(sum(cantidad_recibida + cantidad_rechazada), 0), 2)) AS pct,
                   countDistinctIf(motivo_rechazo, motivo_rechazo != '') AS motivos,
                   countDistinct(proveedor_id)                 AS proveedores
                   %s
            FROM %s.%s WHERE 1 %s
            """.formatted(columnaValor, DWH, TABLA_LINEA, f.where()), f.args());

        List<Map<String, Object>> k = new ArrayList<>();
        k.add(kpi("Unidades rechazadas", cero(t.get("rechazadas")), "numero"));
        k.add(kpi("Unidades que llegaron (el denominador)", cero(t.get("llegadas")), "numero"));
        k.add(kpi("Tasa de rechazo", cero(t.get("pct")), "porcentaje"));
        k.add(kpi("Líneas con rechazo", t.get("con_rechazo"), "numero"));
        k.add(kpi("Líneas de compra", t.get("lineas"), "numero"));
        k.add(kpi("Unidades pedidas", cero(t.get("pedidas")), "numero"));
        k.add(kpi("Unidades aceptadas", cero(t.get("aceptadas")), "numero"));
        if (conDinero) {
            k.add(kpi("Valor rechazado", cero(t.get("valor")), "moneda"));
        }
        k.add(kpi("Motivos distintos", t.get("motivos"), "numero"));
        k.add(kpi("Proveedores", t.get("proveedores"), "numero"));
        return k;
    }

    // ═════════════════════════════════════════════════════════════════════
    // OTD-COM-12 — ¿Está subiendo el precio de lo que compramos?
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Evolución del precio de compra entre compras SUCESIVAS, por producto y
     * proveedor.
     *
     * <h3>Éste es el informe para el que se invirtió el ORDER BY de la tabla</h3>
     * {@code fact_compra_linea} es la única tabla del almacén ordenada por
     * {@code (producto_variante_id, proveedor_id, fecha_emision)} y no por fecha:
     * su cliente natural es esta ventana. La serie se recorre con
     * {@code lagInFrame} sobre esa misma partición y ese mismo orden.
     *
     * <h3>El precio anterior de la primera compra NO es cero</h3>
     * {@code lagInFrame} rellena la primera fila de cada partición con el DEFECTO
     * del tipo —{@code 0,00}, no NULL—, que es la misma trampa que C3B.5 describió
     * con los LEFT JOIN de ClickHouse. Comparar contra ese cero daría una «subida
     * del 100 %» en las 1.041 primeras compras y el informe entero diría que
     * todos los precios se han disparado. La frontera se marca con
     * {@code row_number() > 1}, que es un hecho de la partición y no un valor que
     * pueda coincidir con un dato real.
     *
     * <h3>El desempate del mismo día</h3>
     * {@code fecha_emision} es un día sin hora y hay <b>16 pares (producto,
     * proveedor) con dos compras el mismo día</b>. Sin un segundo criterio, el
     * orden dentro del día es arbitrario y la variación cambiaría de una consulta
     * a otra. Se desempata por {@code orden_compra_id}, que es monótono con la
     * creación del documento.
     *
     * <h3>Filtrar recorta la serie, y eso es lo correcto aquí</h3>
     * A diferencia de OTD-VEN-13 (donde el filtro no podía ir antes del reparto),
     * el filtro de fechas de este informe SÍ va antes de la ventana: la pregunta
     * es «cómo evolucionó el precio en este período», así que «precio inicial» es
     * el primero DENTRO del rango. Queda declarado en la salvedad porque el mismo
     * producto da una variación distinta según el rango, y eso confunde si no se
     * dice.
     *
     * Es un informe de PRECIOS: Bodega y Despacho fuera por RUTA; el Analista
     * entra. Paginado: 1.041 pares (producto, proveedor).
     *
     * Verificado contra PostgreSQL con la misma ventana: 1.041 filas, 0
     * diferencias en precio inicial, precio actual, subidas, bajadas y estables.
     */
    public Map<String, Object> evolucionCosto(String desde, String hasta, String proveedor,
                                              String buscar, String tendencia,
                                              Integer minimoCompras, int page, int size) {
        exigirRangoValido(fecha(desde, "desde"), fecha(hasta, "hasta"));
        String fTend = opcion(tendencia, TENDENCIAS, "tendencia");
        int minCompras = entero(minimoCompras, 1, 50, 1, "minimoCompras");

        return ejecutar("OTD-COM-12", () -> {
            Filtros f = new Filtros();
            f.y("fecha_emision >= toDate(?)", fecha(desde, "desde"));
            f.y("fecha_emision <= toDate(?)", fecha(hasta, "hasta"));
            f.y("positionCaseInsensitive(proveedor, ?) > 0", texto(proveedor));
            // SKU y nombre se buscan en UN solo parámetro concatenándolos: dos
            // `position` con el mismo `?` obligarían a duplicar el valor a mano
            // en el array, y ahí es donde el orden posicional se rompe callado.
            f.y("positionCaseInsensitive(concat(sku, ' ', producto_nombre), ?) > 0",
                    texto(buscar));

            String having = " HAVING compras >= " + minCompras
                    + switch (fTend == null ? "" : fTend) {
                        case "subio"     -> " AND variacion > 0";
                        case "bajo"      -> " AND variacion < 0";
                        case "estable"   -> " AND variacion = 0 AND compras > 1";
                        case "sin_serie" -> " AND compras = 1";
                        default          -> "";
                    };

            String base = sqlEvolucionCosto(f.where(), having);
            Object[] args = f.args();

            Map<String, Object> res = paginarCh(
                    base + " ORDER BY variacion_pct DESC, sku ASC",
                    "SELECT count() FROM (" + base + ")", args, page, size);

            conResumen(res, kpisEvolucion(f.where(), args, minCompras));
            res.put("salvedad", SALVEDAD_COSTO);
            return conMarcaDeAgua(res, TABLA_LINEA);
        });
    }

    /**
     * La ventana y su agregado.
     *
     * {@code n > 1} —y no {@code precio_previo != 0}— es lo que distingue «no hay
     * compra anterior» de «la compra anterior costó cero». Ver el javadoc del
     * método público.
     */
    private static String sqlEvolucionCosto(String where, String having) {
        return """
            SELECT sku,
                   any(producto_nombre)                              AS producto,
                   any(categoria)                                    AS categoria,
                   proveedor,
                   count()                                           AS compras,
                   -- Las fechas viajan YA FORMATEADAS como texto: un `date`
                   -- puro serializado «AAAA-MM-DD» lo lee el formateador del
                   -- frontend como UTC y lo pinta un día antes
                   -- (PATRON_INFORMES.md §11).
                   -- OJO al doble por-ciento: esta plantilla pasa por
                   -- String.formatted(), donde un por-ciento seguido de letra es
                   -- un especificador de Java y se comería un argumento antes de
                   -- que el patrón llegue a ClickHouse. Vale también para este
                   -- comentario: está DENTRO del bloque de texto.
                   formatDateTime(min(fecha_emision), '%%d/%%m/%%Y')  AS primera_compra,
                   formatDateTime(max(fecha_emision), '%%d/%%m/%%Y')  AS ultima_compra,
                   argMin(precio_unitario, orden)                    AS precio_inicial,
                   argMax(precio_unitario, orden)                    AS precio_actual,
                   argMax(precio_unitario, orden)
                       - argMin(precio_unitario, orden)              AS variacion,
                   toFloat64(round((argMax(precio_unitario, orden)
                         - argMin(precio_unitario, orden)) * 100.0
                         / nullIf(argMin(precio_unitario, orden), 0), 2)) AS variacion_pct,
                   countIf(n > 1 AND precio_unitario > precio_previo) AS subidas,
                   countIf(n > 1 AND precio_unitario < precio_previo) AS bajadas,
                   countIf(n > 1 AND precio_unitario = precio_previo) AS estables,
                   argMaxIf(precio_unitario - precio_previo, orden, n > 1) AS ultimo_cambio,
                   sum(cantidad_pedida)                              AS uds_compradas
            FROM (
                SELECT sku, producto_nombre, categoria, proveedor,
                       producto_variante_id, proveedor_id,
                       fecha_emision, precio_unitario, cantidad_pedida,
                       (fecha_emision, orden_compra_id)              AS orden,
                       lagInFrame(precio_unitario) OVER w            AS precio_previo,
                       row_number() OVER w                           AS n
                FROM %s.%s
                WHERE 1 %s
                WINDOW w AS (PARTITION BY producto_variante_id, proveedor_id
                             ORDER BY fecha_emision, orden_compra_id
                             ROWS BETWEEN 1 PRECEDING AND 1 PRECEDING)
            )
            GROUP BY sku, proveedor, producto_variante_id, proveedor_id
            %s
            """.formatted(DWH, TABLA_LINEA, where, having);
    }

    /**
     * El resumen mira el conjunto entero, no la página: cuántos pares subieron,
     * cuántos bajaron y cuánto se ha movido el precio medio ponderado por
     * unidades — que es el número que de verdad afecta al costo de la mercancía.
     */
    private List<Map<String, Object>> kpisEvolucion(String where, Object[] args, int minCompras) {
        Map<String, Object> t = ch.queryForMap("""
            SELECT count()                                    AS pares,
                   countIf(compras > 1)                       AS pares_con_serie,
                   countIf(variacion > 0)                     AS subieron,
                   countIf(variacion < 0)                     AS bajaron,
                   countIf(variacion = 0 AND compras > 1)     AS sin_cambio,
                   countIf(compras = 1)                       AS una_sola_compra,
                   sum(compras)                               AS lineas,
                   round(avgIf(variacion_pct, compras > 1), 2) AS pct_medio,
                   round(maxIf(variacion_pct, compras > 1), 2) AS peor_subida,
                   round(minIf(variacion_pct, compras > 1), 2) AS mejor_bajada,
                   round(sum(variacion * uds_compradas)
                         / nullIf(sum(uds_compradas), 0), 4)  AS impacto_por_unidad
            FROM (%s)
            """.formatted(sqlEvolucionCosto(where, " HAVING compras >= " + minCompras)), args);

        List<Map<String, Object>> k = new ArrayList<>();
        k.add(kpi("Pares producto–proveedor", t.get("pares"), "numero"));
        k.add(kpi("Con al menos dos compras", t.get("pares_con_serie"), "numero"));
        k.add(kpi("Subieron de precio", t.get("subieron"), "numero"));
        k.add(kpi("Bajaron de precio", t.get("bajaron"), "numero"));
        k.add(kpi("Sin cambio", t.get("sin_cambio"), "numero"));
        k.add(kpi("Una sola compra (sin serie)", t.get("una_sola_compra"), "numero"));
        k.add(kpi("Variación media", cero(t.get("pct_medio")), "porcentaje"));
        k.add(kpi("Mayor subida", cero(t.get("peor_subida")), "porcentaje"));
        k.add(kpi("Mayor bajada", cero(t.get("mejor_bajada")), "porcentaje"));
        k.add(kpi("Impacto medio por unidad", cero(t.get("impacto_por_unidad")), "moneda"));
        k.add(kpi("Líneas de compra", t.get("lineas"), "numero"));
        return k;
    }

    // ═════════════════════════════════════════════════════════════════════
    // OTD-COM-09 — Cuánto recuperamos del proveedor por mercancía defectuosa
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Crédito y reposición obtenidos del proveedor, con su muestra declarada.
     *
     * <h3>Solo se cuenta como recuperado lo que YA tiene desenlace</h3>
     * {@code valor_recuperado} lo pone el ETL a cero mientras la devolución no
     * está {@code resuelta} o {@code cerrada}. Sin esa distinción, el informe
     * sumaría el pool pendiente y reportaría <b>$9.349,93 recuperados donde son
     * $5.220,94</b> — un 79 % de más sobre dinero que todavía no se ha cobrado
     * ni repuesto. El coste total del pool viaja aparte, en
     * {@code costo_pool}, porque también es una pregunta legítima: cuánto vale
     * lo que hay pendiente de reclamar.
     *
     * <h3>Crédito y reposición NO se mezclan en un solo número</h3>
     * Son cosas distintas: la nota de crédito es dinero que el proveedor abona
     * y la reposición es mercancía que vuelve al stock, valorada a su costo.
     * Cada fila las trae separadas y su suma solo se muestra como
     * «recuperación total», con las dos columnas al lado para que se pueda
     * deshacer.
     *
     * <h3>El proveedor «sin asignar» aparece</h3>
     * 10 de los 38 ítems no tienen proveedor: la inspección del RMA no encontró
     * una orden de compra previa de esa variante y COMPRAS todavía no lo ha
     * asignado a mano. Son ítems <b>pendientes de clasificar</b>, no un hueco
     * del dato, y su fila es exactamente el trabajo que hay por hacer.
     *
     * <h3>El plazo se mide sobre el ciclo de la DEVOLUCIÓN</h3>
     * No desde la detección del ítem: 19 de los 28 ítems agrupados se
     * detectaron después de crearse la devolución que los agrupa, así que esa
     * resta sale negativa (corrección C4.8). {@code dias_hasta_resolucion} mide
     * registro → resolución, que es además lo que el objetivo pregunta.
     *
     * <h3>Es un informe de DINERO</h3>
     * Bodega y Despacho quedan fuera por RUTA. Ojo: el motor NO lo impediría —
     * el script 45 dio a {@code grp_bodega} SELECT sobre
     * {@code item_defectuoso.costo_unitario} para el flujo operativo—, y en
     * ClickHouse no hay GRANT por columna que valga. La barrera es la línea de
     * {@code SecurityConfig}. Es la contrapartida de OTD-COM-08, que sí deja
     * entrar a Bodega precisamente porque su consulta no selecciona importes.
     */
    public Map<String, Object> recuperacionProveedor(String desde, String hasta,
                                                     String proveedor, String origen,
                                                     String estado, String resolucion,
                                                     String agrupar) {
        exigirRangoValido(fecha(desde, "desde"), fecha(hasta, "hasta"));
        String fOrigen = opcion(origen, ORIGENES, "origen");
        String fEstado = opcion(estado, ESTADOS, "estado");
        String fResol  = opcion(resolucion, RESOLUCIONES, "resolucion");
        String eje = agrupar == null || agrupar.isBlank()
                ? "proveedor" : opcion(agrupar, EJES, "agrupar");

        return ejecutar("OTD-COM-09", () -> {
            Filtros f = new Filtros();
            f.y("toDate(fecha_deteccion) >= toDate(?)", fecha(desde, "desde"));
            f.y("toDate(fecha_deteccion) <= toDate(?)", fecha(hasta, "hasta"));
            f.y("positionCaseInsensitive(proveedor, ?) > 0", texto(proveedor));
            f.y("origen = ?", fOrigen);
            f.y("estado = ?", fEstado);
            f.y("tipo_resolucion = ?", fResol);

            String clave = switch (eje) {
                case "mes"        -> "formatDateTime(mes, '%Y-%m')";
                case "categoria"  -> "categoria";
                case "resolucion" -> "tipo_resolucion";
                default           -> "proveedor";
            };

            List<Map<String, Object>> items = ch.queryForList("""
                SELECT %s                                         AS etiqueta,
                       count()                                    AS items,
                       sum(cantidad)                              AS unidades,
                       sum(costo_total)                           AS costo_pool,
                       -- LA MUESTRA, al lado del número que sostiene.
                       countDistinctIf(devolucion_proveedor_id,
                                       devolucion_proveedor_id != 0
                                       AND resuelto = 1)          AS resoluciones,
                       countDistinctIf(devolucion_proveedor_id,
                                       devolucion_proveedor_id != 0) AS devoluciones,
                       countIf(resuelto = 1)                      AS items_resueltos,
                       countIf(estado = 'pendiente')              AS items_pendientes,
                       sumIf(valor_recuperado, tipo_resolucion = 'nota_credito')
                                                                  AS credito,
                       sumIf(valor_recuperado, tipo_resolucion = 'reposicion')
                                                                  AS reposicion,
                       sum(valor_recuperado)                      AS recuperado,
                       round(sum(valor_recuperado) * 100.0
                             / nullIf(sum(costo_total), 0), 2)    AS pct_recuperado,
                       round(avgIf(dias_hasta_resolucion,
                             dias_hasta_resolucion IS NOT NULL), 2) AS dias_resolucion,
                       countIf(origen = 'rma')                    AS de_rma,
                       countIf(origen = 'recepcion')              AS de_recepcion
                FROM %s.%s
                WHERE 1 %s
                GROUP BY etiqueta
                ORDER BY %s
                """.formatted(clave, DWH, TABLA, f.where(),
                        "mes".equals(eje) ? "etiqueta ASC" : "costo_pool DESC"), f.args());

            Map<String, Object> sobre = sobre(items);
            conResumen(sobre, kpisRecuperacion(f));
            sobre.put("salvedad", SALVEDAD_MUESTRA);
            return conMarcaDeAgua(sobre, TABLA);
        });
    }

    /**
     * El resumen empieza por el TAMAÑO de la muestra y solo después da el
     * dinero: es lo que impide leer «$5.220,94 recuperados» como una serie.
     */
    private List<Map<String, Object>> kpisRecuperacion(Filtros f) {
        Map<String, Object> t = ch.queryForMap("""
            SELECT count()                                        AS items,
                   sum(cantidad)                                  AS unidades,
                   countDistinctIf(devolucion_proveedor_id,
                                   devolucion_proveedor_id != 0)  AS devoluciones,
                   countDistinctIf(devolucion_proveedor_id,
                                   devolucion_proveedor_id != 0 AND resuelto = 1)
                                                                  AS resoluciones,
                   countDistinctIf(proveedor, proveedor != 'sin_asignar') AS proveedores,
                   countIf(proveedor = 'sin_asignar')             AS sin_proveedor,
                   countDistinct(mes)                             AS meses,
                   sum(costo_total)                               AS costo_pool,
                   sumIf(valor_recuperado, tipo_resolucion = 'nota_credito') AS credito,
                   sumIf(valor_recuperado, tipo_resolucion = 'reposicion')   AS reposicion,
                   sum(valor_recuperado)                          AS recuperado,
                   countIf(estado = 'pendiente')                  AS pendientes,
                   sumIf(costo_total, estado = 'pendiente')       AS costo_pendiente,
                   round(sum(valor_recuperado) * 100.0
                         / nullIf(sum(costo_total), 0), 2)        AS pct,
                   round(avgIf(dias_hasta_resolucion,
                         dias_hasta_resolucion IS NOT NULL), 2)   AS dias
            FROM %s.%s WHERE 1 %s
            """.formatted(DWH, TABLA, f.where()), f.args());

        List<Map<String, Object>> k = new ArrayList<>();
        // Primero la muestra.
        k.add(kpi("Resoluciones (la muestra)", t.get("resoluciones"), "numero"));
        k.add(kpi("Devoluciones al proveedor", t.get("devoluciones"), "numero"));
        k.add(kpi("Meses con casos", t.get("meses"), "numero"));
        k.add(kpi("Proveedores implicados", t.get("proveedores"), "numero"));
        // Después el dinero.
        k.add(kpi("Ítems defectuosos", t.get("items"), "numero"));
        k.add(kpi("Unidades", t.get("unidades"), "numero"));
        k.add(kpi("Costo del pool", t.get("costo_pool"), "moneda"));
        k.add(kpi("Recuperado", t.get("recuperado"), "moneda"));
        k.add(kpi("En nota de crédito", cero(t.get("credito")), "moneda"));
        k.add(kpi("En reposición", cero(t.get("reposicion")), "moneda"));
        k.add(kpi("Recuperación sobre el pool", cero(t.get("pct")), "porcentaje"));
        // Y al final lo accionable.
        k.add(kpi("Ítems sin reclamar", t.get("pendientes"), "numero"));
        k.add(kpi("Valor sin reclamar", cero(t.get("costo_pendiente")), "moneda"));
        k.add(kpi("Ítems sin proveedor asignado", t.get("sin_proveedor"), "numero"));
        k.add(kpi("Días hasta resolver", cero(t.get("dias")), "dias"));
        return k;
    }

    /** Un conjunto vacío devuelve NULL en el agregado; la tarjeta muestra 0. */
    private static Object cero(Object v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
