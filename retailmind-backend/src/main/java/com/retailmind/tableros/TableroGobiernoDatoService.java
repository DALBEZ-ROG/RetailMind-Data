package com.retailmind.tableros;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * T-7 · TABLERO DE GOBIERNO DEL DATO — nivel estratégico, objetivo OE-10.
 * <b>DATO SENSIBLE: solo ADMIN y GERENTE.</b>
 *
 * Sirve dos decisiones (§3.5 del diseño):
 * <ul>
 *   <li><b>D-10.2</b> qué privilegios de acceso se revocan o se refuerzan;</li>
 *   <li><b>D-10.3</b> <b>si la información con la que se está decidiendo es
 *       confiable</b> — y esta es la razón de ser del tablero.</li>
 * </ul>
 *
 * <h2>Por qué D-10.3 no se puede responder mirando otro tablero</h2>
 * Es el único riesgo del sistema que <b>no se detecta mirando el dato</b>. Una
 * carga que falló a medias no deja una pantalla rara: deja una pantalla
 * perfecta con una tabla de ayer. Las 39 correcciones de
 * {@code CORRECCIONES_DISENO_ETL.md} son todas de la misma familia —cifras
 * plausibles y equivocadas— y ésta es la que no tiene arreglo por SQL: hay que
 * mirar la bitácora de la corrida. Por eso existe este tablero.
 *
 * <h2>Es el único que casi no toca las 19 tablas del almacén</h2>
 * Dos de sus cuatro elementos son informes SIMPLES ya construidos sobre
 * PostgreSQL —OTD-GER-08 (auditoría, 7.073 registros) y OTD-GER-09 (accesos)—
 * y los pide la PANTALLA con una segunda llamada, igual que el carrito
 * abandonado en T-1. Llevarlos al almacén sería crear dos tablas para no ganar
 * nada: su pregunta es de consulta filtrada, no de barrido agregado.
 *
 * Los otros dos salen de {@code etl_ejecucion}, la bitácora que el pipeline ya
 * escribe. <b>Cero tablas nuevas, aquí también.</b>
 *
 * <h2>El corte lo hace la RUTA, no el motor</h2>
 * En {@code /accesos} el motor coincide con la ruta —solo grp_administrador y
 * grp_gerente leen {@code log_acceso}—, pero en {@code /auditoria} <b>NO</b>:
 * {@code grp_analista} sí lee {@code log_auditoria} (script 19). Ahí la única
 * barrera es la línea de {@code SecurityConfig}, enumerada por nombre.
 */
@Service
public class TableroGobiernoDatoService extends TableroServiceBase {

    private static final String CODIGO = "T-7";
    private static final String TITULO = "Tablero de Gobierno del Dato";
    private static final List<String> DECISIONES = List.of("D-10.2", "D-10.3");

    private static final String BITACORA = DWH + ".etl_ejecucion";

    /**
     * Las 19 tablas del modelo, en el orden en que las carga el pipeline.
     *
     * Es una constante del código y no un {@code system.tables}: si mañana
     * alguien crea una tabla suelta en la base, este tablero tiene que seguir
     * midiendo el MODELO declarado —19— y no lo que haya. Una lista que se
     * autodescubre no puede detectar que falta una tabla, que es justo lo que
     * D-10.3 necesita saber.
     */
    private static final List<String> TABLAS = List.of(
            "dim_fecha", "dim_producto", "dim_cliente", "dim_proveedor",
            "dim_promocion_producto",
            "fact_pedido", "fact_venta_linea", "fact_flujo_caja",
            "fact_orden_compra", "fact_compra_linea", "fact_movimiento_inventario",
            "fact_envio", "fact_novedad_envio", "fact_devolucion",
            "fact_devolucion_linea", "fact_ticket", "fact_resena",
            "fact_devolucion_proveedor", "fact_stock_mensual");

    /**
     * La ÚNICA tabla del modelo que no lleva {@code fecha_carga}.
     *
     * {@code dim_fecha} es el calendario, y se GENERA dentro de ClickHouse con
     * {@code numbers()}: nunca consulta PostgreSQL, así que no hay nada que
     * sellar. El resto de la aplicación da por hecho que las 19 tablas llevan
     * la marca —la marca de agua de todos los tableros la lee—, y aquí ese
     * supuesto se rompe: pedirle {@code max(fecha_carga)} no devuelve nulo,
     * revienta con {@code UNKNOWN_IDENTIFIER}.
     *
     * Se excluye del cálculo de frescura y se muestra igualmente en la lista,
     * marcada como generada. No es una tabla que pueda quedar rezagada: un
     * calendario de 730 días no envejece.
     */
    private static final String CALENDARIO = "dim_fecha";

    /** Las tablas que SÍ llevan sello de carga. Son las que definen la frescura. */
    private static List<String> tablasConSello() {
        return TABLAS.stream().filter(t -> !CALENDARIO.equals(t)).toList();
    }

    /**
     * Tareas de la bitácora que NO son tablas del modelo.
     *
     * {@code corrida} es el sobre de la ejecución entera y {@code validar_dwh}
     * es la puerta de control. Distinguirlas no es cosmético: ver más abajo por
     * qué sumar {@code filas_escritas} sin excluirlas DUPLICA el total.
     */
    private static final String NO_SON_TABLAS = "tarea NOT IN ('corrida', 'validar_dwh')";

    public TableroGobiernoDatoService(@Qualifier("pgJdbcTemplate") JdbcTemplate pg,
                                      @Qualifier("clickHouseJdbc") JdbcTemplate ch) {
        super(pg, ch);
    }

    /** @param corridas cuántas ejecuciones muestra el histórico (1-50, por defecto 10) */
    public Map<String, Object> tablero(Integer corridas) {
        int n = entero(corridas, 1, 50, 10, "corridas");

        return servir(CODIGO, TITULO, DECISIONES, () -> {
            Map<String, Object> ultima = resumenUltimaCorrida();

            List<Map<String, Object>> bloques = new ArrayList<>();
            bloques.add(saludDeLaCorrida(ultima));
            bloques.add(historicoDeCorridas(n));
            bloques.add(antiguedadDelDato());

            List<String> salvedades = new ArrayList<>();
            salvedades.add("Este tablero mira la BITÁCORA del pipeline, no los datos. Es el "
                    + "único riesgo del sistema que no se detecta mirando una pantalla: una "
                    + "carga que falló a medias no produce cifras raras, produce cifras "
                    + "perfectas de ayer. Si alguna tabla aparece rezagada o con excepciones, "
                    + "la decisión que dependa de ella se aplaza.");
            salvedades.add("La auditoría y los intentos de acceso NO están en el almacén "
                    + "analítico: se sirven de la base transaccional con los informes que ya "
                    + "existen, y por eso siguen respondiendo aunque el almacén esté caído. "
                    + "Su corte de acceso lo hace la RUTA y no el motor: el rol de analista "
                    + "SÍ puede leer la tabla de auditoría en la base de datos.");

            Map<String, Object> t = sobreTablero(CODIGO, TITULO, DECISIONES,
                    Map.of(), kpis(ultima), bloques, salvedades,
                    tablasConSello().toArray(new String[0]));
            t.put("ultimaCorrida", ultima);
            return t;
        });
    }

    // ═════════════════════════════════════════════════════════════════════
    // La última corrida
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Estado FINAL de cada tarea de la última corrida.
     *
     * <h3>Dos trampas en cuatro líneas de SQL</h3>
     * <ol>
     *   <li><b>Una tarea puede tener VARIAS filas en la misma corrida.</b> La
     *       tarea {@code corrida} escribe una al empezar ({@code en_curso}) y
     *       otra al terminar ({@code exito}). Listadas tal cual, la pantalla
     *       muestra una tarea eternamente «en curso» junto a la misma tarea
     *       terminada: una alarma falsa en el tablero cuyo trabajo es dar
     *       alarmas. Se colapsa con {@code argMax(..., inicio)}, que se queda
     *       con el ÚLTIMO estado.</li>
     *   <li><b>Sumar {@code filas_escritas} DUPLICA el total.</b> La fila
     *       {@code corrida} lleva la suma de todas las tablas (64.085), así que
     *       sumarla junto a ellas da 128.214 — exactamente el doble más las 44
     *       del validador. Y 128.214 es una cifra plausible: nadie la mira dos
     *       veces. Por eso los agregados excluyen las dos pseudo-tareas.</li>
     * </ol>
     */
    private Map<String, Object> resumenUltimaCorrida() {
        List<Map<String, Object>> r = ch.queryForList(
                "SELECT corrida_id, iniciada, tablas, publicadas, fallidas, "
                + "       tablas_con_excepciones, "
                + "       t_excepciones AS excepciones, "
                + "       t_filas_publicadas AS filas_publicadas, "
                + "       resultado_corrida, resultado_control, controles, "
                + "       duracion_seg "
                + "FROM ( "
                + "SELECT any(corrida) AS corrida_id, "
                + "       formatDateTime(min(t_inicio), '%d/%m/%Y %H:%i') AS iniciada, "
                + "       countIf(" + NO_SON_TABLAS + ") AS tablas, "
                + "       countIf(" + NO_SON_TABLAS + " AND resultado = 'exito') AS publicadas, "
                + "       countIf(" + NO_SON_TABLAS + " AND resultado != 'exito') AS fallidas, "
                + "       countIf(" + NO_SON_TABLAS + " AND excepciones > 0) "
                + "           AS tablas_con_excepciones, "
                + "       sumIf(excepciones, " + NO_SON_TABLAS + ") AS t_excepciones, "
                // La suma EXCLUYE las pseudo-tareas: incluirlas duplica el total.
                + "       sumIf(filas, " + NO_SON_TABLAS + ") AS t_filas_publicadas, "
                + "       anyIf(resultado, tarea = 'corrida') AS resultado_corrida, "
                + "       anyIf(resultado, tarea = 'validar_dwh') AS resultado_control, "
                + "       anyIf(filas, tarea = 'validar_dwh') AS controles, "
                + "       round(anyIf(seg, tarea = 'corrida'), 1) AS duracion_seg "
                + "FROM ( "
                + "  SELECT corrida_id AS corrida, tarea, min(inicio) AS t_inicio, "
                + "         argMax(resultado, inicio) AS resultado, "
                + "         argMax(filas_escritas, inicio) AS filas, "
                + "         argMax(excepciones, inicio) AS excepciones, "
                + "         argMax(duracion_seg, inicio) AS seg "
                + "  FROM " + BITACORA + " "
                + "  WHERE corrida_id = (SELECT corrida_id FROM " + BITACORA
                + "                      ORDER BY inicio DESC LIMIT 1) "
                + "  GROUP BY corrida_id, tarea "
                + "))");
        return r.isEmpty() ? Map.of() : r.get(0);
    }

    private List<Map<String, Object>> kpis(Map<String, Object> u) {
        long tablas = num(u.get("tablas"));
        long publicadas = num(u.get("publicadas"));
        long conExcepciones = num(u.get("tablas_con_excepciones"));

        List<Map<String, Object>> a = ch.queryForList(
                "SELECT formatDateTime(max(f), '%d/%m/%Y %H:%i') AS mas_reciente, "
                + "       formatDateTime(min(f), '%d/%m/%Y %H:%i') AS mas_antigua "
                + "FROM (" + unionCargas() + ")");
        Map<String, Object> ant = a.isEmpty() ? Map.of() : a.get(0);

        List<Map<String, Object>> h = ch.queryForList(
                "SELECT dateDiff('hour', min(f), now()) AS horas FROM (" + unionCargas() + ")");
        Object horas = h.isEmpty() ? null : h.get(0).get("horas");

        List<Map<String, Object>> k = new ArrayList<>();
        k.add(kpi("Tablas publicadas en la última corrida", publicadas, "numero",
                "De las " + fmt(TABLAS.size()) + " que declara el modelo. La corrida "
                + "registró " + fmt(tablas) + " tareas de tabla y terminó con estado «"
                + txt(u.get("resultado_corrida")) + "»."));
        k.add(kpi("Filas publicadas", num(u.get("filas_publicadas")), "numero",
                "Suma de las tablas del modelo. NO incluye la fila resumen de la corrida, "
                + "que repite ese mismo total y lo duplicaría, ni las comprobaciones del "
                + "validador."));
        k.add(kpi("Tablas con excepciones", conExcepciones, "numero",
                fmt(num(u.get("excepciones"))) + " excepción(es) registrada(s) en total. Una "
                + "excepción no invalida la tabla —queda marcada fila a fila— pero sí obliga "
                + "a leerla sabiendo cuál es."));
        k.add(kpi("Control de validación", txt(u.get("resultado_control")), "texto",
                fmt(num(u.get("controles"))) + " controles cruzados contra PostgreSQL. Si "
                + "este control no está en «exito», ninguna cifra del resto de tableros "
                + "puede darse por buena."));
        k.add(kpi("Antigüedad del dato (horas)", horas, "numero",
                "Horas desde la carga MÁS REZAGADA de las " + fmt(TABLAS.size())
                + " tablas (" + txt(ant.get("mas_antigua")) + "). El almacén es tan fresco "
                + "como su tabla más atrasada, no como la más reciente."));
        return k;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Elemento 1 — Salud de la última corrida
    // ═════════════════════════════════════════════════════════════════════

    private Map<String, Object> saludDeLaCorrida(Map<String, Object> u) {
        List<Map<String, Object>> items = ch.queryForList(
                "SELECT tarea, "
                + "       if(tarea = 'corrida', 'corrida', "
                + "          if(tarea = 'validar_dwh', 'control', 'tabla')) AS tipo, "
                + "       resultado, "
                + "       filas, "
                + "       excepciones, "
                + "       round(seg, 2) AS duracion_seg, "
                + "       formatDateTime(t_inicio, '%d/%m/%Y %H:%i:%S') AS comenzo, "
                + "       mensaje "
                + "FROM ( "
                + "  SELECT tarea, min(inicio) AS t_inicio, "
                + "         argMax(resultado, inicio) AS resultado, "
                + "         argMax(filas_escritas, inicio) AS filas, "
                + "         argMax(excepciones, inicio) AS excepciones, "
                + "         argMax(duracion_seg, inicio) AS seg, "
                + "         argMax(mensaje, inicio) AS mensaje "
                + "  FROM " + BITACORA + " "
                + "  WHERE corrida_id = (SELECT corrida_id FROM " + BITACORA
                + "                      ORDER BY inicio DESC LIMIT 1) "
                + "  GROUP BY tarea "
                + ") ORDER BY t_inicio");

        long tablas = num(u.get("tablas"));
        return conSalvedad(bloque("salud_corrida",
                "Salud de la última corrida del ETL",
                "semaforo_tabla",
                "Corrida del " + txt(u.get("iniciada")) + " · " + fmt(tablas) + " tareas de "
                + "tabla sobre las " + fmt(TABLAS.size()) + " del modelo · "
                + fmt(num(u.get("publicadas"))) + " publicadas · "
                + fmt(num(u.get("fallidas"))) + " con fallo · duración "
                + txt(u.get("duracion_seg")) + " s.",
                items),
                "Una tarea puede escribir VARIAS entradas en la misma corrida —la corrida "
                + "misma anota una al empezar y otra al acabar— y aquí se muestra el estado "
                + "FINAL de cada una. Listándolas en crudo aparecería una tarea «en curso» "
                + "para siempre al lado de la misma tarea ya terminada, que es una alarma "
                + "falsa en el tablero cuyo trabajo es dar alarmas. Las filas «corrida» y "
                + "«control» no son tablas del modelo y quedan fuera de todos los conteos.");
    }

    // ═════════════════════════════════════════════════════════════════════
    // Elemento 2 — Histórico de corridas
    // ═════════════════════════════════════════════════════════════════════

    private Map<String, Object> historicoDeCorridas(int n) {
        List<Map<String, Object>> items = ch.queryForList(
                "SELECT formatDateTime(t_inicio, '%d/%m/%Y %H:%i') AS cuando, "
                + "       tareas, publicadas, fallidas, "
                + "       t_excepciones AS excepciones, t_filas AS filas, "
                + "       round(duracion_seg, 1) AS duracion_seg, "
                + "       resultado_corrida "
                + "FROM ( "
                + "  SELECT min(t_inicio) AS t_inicio, "
                + "         countIf(" + NO_SON_TABLAS + ") AS tareas, "
                + "         countIf(" + NO_SON_TABLAS + " AND resultado = 'exito') "
                + "             AS publicadas, "
                + "         countIf(" + NO_SON_TABLAS + " AND resultado != 'exito') "
                + "             AS fallidas, "
                + "         sumIf(excepciones, " + NO_SON_TABLAS + ") AS t_excepciones, "
                + "         sumIf(filas, " + NO_SON_TABLAS + ") AS t_filas, "
                + "         anyIf(seg, tarea = 'corrida') AS duracion_seg, "
                + "         anyIf(resultado, tarea = 'corrida') AS resultado_corrida "
                + "  FROM ( "
                + "    SELECT corrida_id, tarea, min(inicio) AS t_inicio, "
                + "           argMax(resultado, inicio) AS resultado, "
                + "           argMax(filas_escritas, inicio) AS filas, "
                + "           argMax(excepciones, inicio) AS excepciones, "
                + "           argMax(duracion_seg, inicio) AS seg "
                + "    FROM " + BITACORA + " GROUP BY corrida_id, tarea "
                + "  ) GROUP BY corrida_id "
                + ") ORDER BY t_inicio DESC LIMIT ?", n);

        List<Map<String, Object>> t = ch.queryForList(
                "SELECT countDistinct(corrida_id) AS corridas FROM " + BITACORA);
        long corridas = t.isEmpty() ? 0 : num(t.get(0).get("corridas"));

        return conSalvedad(bloque("historico_corridas",
                "Histórico de ejecuciones del pipeline",
                "barras",
                "Las " + fmt(Math.min(n, items.size())) + " últimas de " + fmt(corridas)
                + " ejecuciones registradas. «Tareas» cuenta solo tablas del modelo.",
                items),
                "Una corrida con menos tareas de las 19 del modelo NO es necesariamente un "
                + "fallo: el pipeline admite cargar una tabla suelta. Lo que sí es una señal "
                + "es una corrida con tareas fallidas, o una que se quedó a medias y nunca "
                + "escribió su estado final.");
    }

    // ═════════════════════════════════════════════════════════════════════
    // Elemento 3 — Antigüedad del dato que se está mirando
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Una fila por tabla del modelo con su {@code max(fecha_carga)}.
     *
     * Se lee de las TABLAS y no de la bitácora a propósito: la bitácora dice
     * cuándo dijo el pipeline que terminó; {@code fecha_carga} dice qué hay
     * escrito de verdad dentro de la tabla. Si una corrida se anota como
     * correcta y la tabla no se publicó, es exactamente aquí donde se ve — y ése
     * es el escenario que D-10.3 tiene que poder detectar.
     */
    private Map<String, Object> antiguedadDelDato() {
        List<Map<String, Object>> items = new ArrayList<>(ch.queryForList(
                "SELECT tabla, "
                + "       formatDateTime(f, '%d/%m/%Y %H:%i') AS cargada, "
                + "       dateDiff('hour', f, now()) AS horas, "
                + "       filas, "
                + "       0 AS generada "
                + "FROM (" + unionCargas() + ") ORDER BY f ASC, tabla"));

        // El calendario va al final, con su casilla de frescura VACÍA en vez de
        // con un cero: no está al día ni rezagado, no aplica. Se muestra porque
        // el modelo tiene 19 tablas y una lista de 18 invitaría a preguntarse
        // cuál falta.
        List<Map<String, Object>> cal = ch.queryForList(
                "SELECT '" + CALENDARIO + "' AS tabla, "
                + "       NULL AS cargada, NULL AS horas, "
                + "       count() AS filas, 1 AS generada "
                + "FROM " + DWH + "." + CALENDARIO);
        items.addAll(cal);

        return conSalvedad(bloque("antiguedad_dato",
                "Antigüedad del dato, tabla por tabla",
                "ranking",
                fmt(TABLAS.size()) + " tablas del modelo, ordenadas de la más REZAGADA a la "
                + "más reciente. La marca de agua del resto de tableros es la primera fila de "
                + "esta lista, no la última. " + fmt(tablasConSello().size()) + " llevan sello "
                + "de carga; el calendario no, y se muestra al final.",
                items),
                "La fecha sale del contenido de cada tabla y no de la bitácora del pipeline: "
                + "la bitácora dice cuándo el proceso creyó terminar, y esta columna dice qué "
                + "hay escrito de verdad. Cuando las dos discrepan, la que manda es ésta. La "
                + "única excepción es el calendario, que se genera dentro del almacén sin "
                + "consultar la base transaccional y por eso no lleva sello: no puede "
                + "quedarse rezagado.");
    }

    /**
     * {@code UNION ALL} sobre las tablas que llevan sello de carga.
     *
     * Los nombres son constantes del código —salen de {@link #TABLAS}— y nunca
     * del usuario. El calendario queda fuera: no tiene {@code fecha_carga} y
     * pedírsela no devuelve nulo, revienta la consulta entera.
     */
    private static String unionCargas() {
        StringBuilder sql = new StringBuilder();
        List<String> tablas = tablasConSello();
        for (int i = 0; i < tablas.size(); i++) {
            if (i > 0) {
                sql.append(" UNION ALL ");
            }
            String t = tablas.get(i);
            sql.append("SELECT '").append(t).append("' AS tabla, max(fecha_carga) AS f, ")
               .append("count() AS filas FROM ").append(DWH).append('.').append(t);
        }
        return sql.toString();
    }
}
