package com.retailmind.informes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * MOLDE de los informes tácticos COMPUESTOS — los que se sirven desde
 * ClickHouse (data warehouse {@code retailmind_dwh}) en vez de PostgreSQL.
 *
 * Extiende el contrato de {@link InformeServiceBase} —mismo sobre
 * {@code {items, total, page, size, resumen[]}}, misma pantalla genérica, misma
 * validación por lista blanca— y añade las TRES diferencias que el diseño del
 * pipeline (§9.7 de {@code docs/estrategico/DISENO_ETL_CLICKHOUSE.md}) anticipó
 * para esta capa:
 *
 * <ol>
 *   <li><b>La fuente es ClickHouse, no {@code pgJdbcTemplate}.</b> Y con ello
 *       una consecuencia que conviene decir en voz alta: estos métodos
 *       <b>NO</b> llevan {@code @Transactional}. La regla de oro del proyecto
 *       («todo acceso a Postgres va dentro de una transacción, o corre sin
 *       {@code SET LOCAL ROLE}») no aplica porque aquí no se toca Postgres;
 *       anotarlos abriría una transacción de PostgreSQL inútil y haría que
 *       {@link com.retailmind.security.PgSessionRoleAspect} asumiera un rol de
 *       grupo para nada.</li>
 *
 *   <li><b>No hay segregación financiera de motor que respalde el corte</b>
 *       (§8.2 del diseño). ClickHouse no tiene RLS por fila ni GRANT por
 *       columna equivalentes, y el ETL escribe todas las columnas de dinero en
 *       tablas planas. La barrera de un informe compuesto es por tanto la
 *       <b>RUTA</b> ({@code SecurityConfig}), exactamente como ya ocurre con
 *       OTD-INV-07, OTD-LOG-11 y OTD-GER-08, donde el motor tampoco alcanza.
 *       Cada endpoint compuesto necesita su propia línea, de lo específico a lo
 *       general.</li>
 *
 *   <li><b>Marca de agua obligatoria</b> (§8.4). Todo sobre compuesto viaja con
 *       {@code datosAl}: el {@code max(fecha_carga)} de la tabla que sirve el
 *       informe. Un informe que no dice de cuándo es su dato es un informe que
 *       miente por omisión — el desfase aceptado es de hasta 24 h.</li>
 * </ol>
 *
 * <h2>Degradación con ClickHouse apagado</h2>
 *
 * Invariante del sistema: con Docker apagado TODO funciona y solo la analítica
 * se degrada, con aviso. {@link #ejecutar} envuelve la consulta y, si ClickHouse
 * no responde, devuelve el sobre VACÍO con {@code analiticaDisponible=false} y
 * un mensaje legible, en HTTP 200. No se propaga un 500: una pantalla de
 * informe no puede tumbar la aplicación, y un error de infraestructura no es un
 * error del usuario. El pool ya falla rápido (3 s) gracias a los timeouts
 * acotados de {@code ClickHouseConfig}.
 *
 * <h2>Parámetros</h2>
 *
 * Mismo criterio que el nivel simple: los valores del usuario viajan SIEMPRE
 * como parámetros de {@code JdbcTemplate} y jamás concatenados. Lo que se
 * concatena son fragmentos {@code AND col = ?} que son constantes del código
 * ({@link Filtros}). Es deliberado que este molde NO imite a los servicios de
 * {@code analytics/}, que construyen su SQL pegando texto.
 */
public abstract class InformeCompuestoServiceBase extends InformeServiceBase {

    private static final Logger logger =
            LoggerFactory.getLogger(InformeCompuestoServiceBase.class);

    /** Base del data warehouse. La base {@code retailmind} legada NO se toca. */
    protected static final String DWH = "retailmind_dwh";

    /**
     * Canales del pedido — CHECK del motor en PostgreSQL: solo estos tres.
     * Vive en el molde porque lo usan informes de tres departamentos distintos
     * (Ventas, Logística y Gerencia) y una lista blanca duplicada acaba
     * divergiendo.
     */
    protected static final java.util.Set<String> CANALES =
            java.util.Set.of("web", "tienda", "telefono");

    /**
     * Lectura correcta de una dimensión {@code ReplacingMergeTree}.
     *
     * El patrón de carga atómica publica la tabla recién creada, así que hoy
     * nunca hay dos versiones de la misma clave conviviendo. Aun así la
     * dimensión se lee con {@code FINAL}: es lo que el motor de tabla promete y
     * lo que la hace correcta si algún día una carga incremental deja dos
     * versiones. Sobre 72 o 1.221 filas el coste es irrelevante — y un cliente
     * duplicado en un {@code JOIN} duplicaría su fila del informe sin avisar.
     *
     * Se envuelve en subconsulta y no se escribe {@code JOIN tabla FINAL},
     * que no es sintaxis válida en el lado derecho de un JOIN.
     */
    protected static String dimension(String tabla) {
        return "(SELECT * FROM " + DWH + "." + tabla + " FINAL)";
    }

    protected final JdbcTemplate ch;

    /**
     * @param pg datasource de PostgreSQL, heredado del molde simple. No se usa
     *           en los informes compuestos; se pasa para que la jerarquía sea
     *           una sola y {@code rolActual()} / {@code opcion()} sigan
     *           disponibles.
     */
    protected InformeCompuestoServiceBase(JdbcTemplate pg, JdbcTemplate ch) {
        super(pg);
        this.ch = ch;
    }

    // ── Acumulador de filtros ────────────────────────────────────────────

    /**
     * Construye la cláusula WHERE pegando fragmentos CONSTANTES y acumulando
     * los valores aparte.
     *
     * Por qué no se usa aquí la guarda NULL por parámetro
     * ({@code (?::tipo IS NULL OR col = ?::tipo)}) que rige en los informes
     * simples: ese truco se apoya en el casteo explícito de PostgreSQL, y
     * ClickHouse no infiere el tipo de un {@code ?} desnudo dentro de un
     * {@code IS NULL}. Añadir el fragmento solo cuando el filtro llega es
     * equivalente en seguridad —el texto del usuario nunca entra en el SQL— y
     * además produce consultas más simples de leer en los logs del motor.
     */
    protected static final class Filtros {
        private final StringBuilder where = new StringBuilder();
        private final List<Object> args = new ArrayList<>();

        /**
         * Constructor explícito y PÚBLICO. El implícito de una clase anidada
         * {@code protected} también es {@code protected}, y el acceso protegido
         * entre paquetes distintos solo alcanza a los miembros de instancias
         * del propio subtipo: una subclase de otro paquete —los tableros del
         * nivel estratégico, {@code com.retailmind.tableros}— no puede hacer
         * {@code new Filtros()}. La clase sigue siendo {@code protected}: quien
         * no herede de este molde no la ve siquiera.
         */
        public Filtros() {
        }

        /** Añade {@code AND <fragmento>} solo si el valor llegó. */
        public Filtros y(String fragmentoConstante, Object valor) {
            if (valor != null) {
                where.append(" AND ").append(fragmentoConstante);
                args.add(valor);
            }
            return this;
        }

        /** Añade un fragmento sin parámetros (constante del código). */
        public Filtros y(String fragmentoConstante) {
            where.append(" AND ").append(fragmentoConstante);
            return this;
        }

        public String where() {
            return where.toString();
        }

        public Object[] args() {
            return args.toArray();
        }

        /** Los mismos args más los de paginación, en orden. */
        public Object[] argsCon(Object... extra) {
            Object[] todos = new Object[args.size() + extra.length];
            System.arraycopy(args.toArray(), 0, todos, 0, args.size());
            System.arraycopy(extra, 0, todos, args.size(), extra.length);
            return todos;
        }
    }

    // ── Marca de agua (§8.4) ─────────────────────────────────────────────

    /**
     * Momento de la última carga del ETL para una tabla del DWH.
     *
     * Viaja ya FORMATEADA como texto, y no como fecha. Es la misma lección que
     * {@code PATRON_INFORMES.md} §11 dejó escrita: una fecha serializada la
     * interpreta el formateador del frontend como UTC y puede mostrarla
     * corrida. Una marca de agua que miente sobre su propia fecha es peor que
     * no tenerla.
     */
    protected String marcaDeAgua(String tabla) {
        try {
            // OJO con el patrón: en ClickHouse el minuto es %i, NO %M — %M es el
            // NOMBRE DEL MES, al revés que en strftime de C o en Java. Con %M la
            // marca de agua salía «30/07/2026 18:July», que además de absurdo
            // habría pasado por bueno en una revisión rápida.
            return ch.queryForObject(
                    "SELECT formatDateTime(max(fecha_carga), '%d/%m/%Y %H:%i') "
                    + "FROM " + DWH + "." + tabla, String.class);
        } catch (DataAccessException e) {
            return null;
        }
    }

    // ── Ejecución con degradación ────────────────────────────────────────

    /** Lo que produce un informe compuesto cuando ClickHouse responde. */
    @FunctionalInterface
    protected interface ConsultaCompuesta {
        Map<String, Object> get();
    }

    /**
     * Ejecuta el informe y degrada con aviso SOLO si la analítica no está
     * alcanzable.
     *
     * <h3>Qué se degrada y qué no, y por qué la distinción importa</h3>
     * Se degrada un fallo de CONEXIÓN: ClickHouse apagado, host inalcanzable,
     * tiempo de espera agotado. Eso no es un fallo de la aplicación y el
     * usuario no puede hacer nada al respecto, así que se responde 200 con el
     * aviso y el sistema sigue en pie.
     *
     * <b>Una consulta mal formada NO se degrada: se propaga.</b> Es un bug del
     * servidor y tiene que doler. Capturar todo {@link DataAccessException} por
     * comodidad convierte un error de SQL en un tranquilizador «la analítica no
     * está disponible», con dos consecuencias: el informe aparece vacío
     * mientras ClickHouse está perfectamente vivo, y la prueba de humo pasa en
     * verde porque el endpoint devuelve 200. Esto no es hipotético — ocurrió en
     * la primera corrida de este mismo informe (un alias de agregado que
     * ClickHouse rechazaba con {@code ILLEGAL_AGGREGATION}) y el enmascaramiento
     * casi lo deja pasar. Un error del servidor debe salir como 500.
     *
     * Un {@link IllegalArgumentException} de la validación de filtros tampoco se
     * toca: sigue saliendo como 400 por {@code GlobalExceptionHandler}. Un
     * filtro mal escrito es un error del usuario.
     */
    protected Map<String, Object> ejecutar(String informe, ConsultaCompuesta consulta) {
        try {
            return consulta.get();
        } catch (DataAccessException e) {
            if (!esFalloDeConexion(e)) {
                // Bug del servidor: que se vea.
                logger.error("Informe compuesto {}: la consulta a ClickHouse falló "
                        + "(el motor SÍ respondió). Esto es un error del informe, "
                        + "no una caída de la analítica.", informe, e);
                throw e;
            }
            logger.warn("Informe compuesto {} degradado: ClickHouse no está alcanzable ({})",
                    informe, e.getMostSpecificCause().getMessage());
            return sobreDegradado();
        }
    }

    /**
     * ¿El fallo es de red/pool y no de la consulta?
     *
     * Es {@code protected} y no {@code private} porque el nivel ESTRATÉGICO
     * ({@code com.retailmind.tableros}) necesita exactamente esta clasificación
     * para degradar un TABLERO, cuyo sobre tiene otra forma que el de un
     * informe. Duplicar la lista de excepciones de red en la otra jerarquía
     * garantizaría que las dos divergieran en la primera excepción nueva del
     * driver: el criterio de «qué es una caída y qué es un bug» tiene que vivir
     * en un solo sitio.
     *
     * Se mira la causa raíz y no solo el tipo de Spring, porque el driver de
     * ClickHouse envuelve un {@code ConnectException} dentro de excepciones
     * genéricas de acceso a datos: quedarse en la capa de Spring clasificaría
     * como «consulta mala» una caída real del servidor, que es el error
     * simétrico y dejaría la aplicación devolviendo 500 con Docker apagado —
     * justo lo que el invariante del sistema prohíbe.
     */
    protected static boolean esFalloDeConexion(DataAccessException e) {
        if (e instanceof org.springframework.dao.DataAccessResourceFailureException
                || e instanceof org.springframework.dao.QueryTimeoutException
                || e instanceof org.springframework.jdbc.CannotGetJdbcConnectionException) {
            return true;
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.ConnectException
                    || t instanceof java.net.SocketTimeoutException
                    || t instanceof java.net.NoRouteToHostException
                    || t instanceof java.net.UnknownHostException
                    || t instanceof java.sql.SQLTransientConnectionException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /**
     * Sobre vacío con el aviso. Mantiene la MISMA forma que un sobre normal
     * para que la pantalla genérica no necesite un camino aparte.
     */
    protected static Map<String, Object> sobreDegradado() {
        Map<String, Object> res = new HashMap<>();
        res.put("items", List.of());
        res.put("total", 0);
        res.put("page", 0);
        res.put("size", 0);
        res.put("resumen", List.of());
        res.put("analiticaDisponible", false);
        res.put("avisoAnalitica",
                "La analítica no está disponible en este momento: el almacén de datos "
                + "(ClickHouse) no responde. El resto del sistema funciona con normalidad "
                + "— los informes de consulta directa y toda la operación siguen activos. "
                + "Vuelve a intentarlo cuando el servicio de analítica esté en línea.");
        return res;
    }

    /** Marca el sobre como servido por la analítica y le pega la marca de agua. */
    protected Map<String, Object> conMarcaDeAgua(Map<String, Object> sobre, String tabla) {
        sobre.put("analiticaDisponible", true);
        sobre.put("fuente", "ClickHouse · " + DWH);
        String datosAl = marcaDeAgua(tabla);
        if (datosAl != null) {
            sobre.put("datosAl", datosAl);
        }
        return sobre;
    }

    // ── Paginación sobre ClickHouse ──────────────────────────────────────

    /**
     * Equivalente de {@link InformeServiceBase#paginar} contra ClickHouse.
     *
     * Se repite en vez de reutilizarse porque el molde simple está atado a
     * {@code pg} por construcción; unificarlos exigiría parametrizar el
     * datasource en la clase base y tocar los 29 informes ya construidos y
     * verificados, a cambio de ahorrar diez líneas.
     */
    protected Map<String, Object> paginarCh(String sqlItems, String sqlCount, Object[] args,
                                            int page, int size) {
        int limit = Math.min(Math.max(size, 1), MAX_PAGINA);
        int offset = Math.max(page, 0) * limit;

        Integer total = ch.queryForObject(sqlCount, Integer.class, args);

        Object[] pageArgs = new Object[args.length + 2];
        System.arraycopy(args, 0, pageArgs, 0, args.length);
        pageArgs[args.length] = limit;
        pageArgs[args.length + 1] = offset;

        List<Map<String, Object>> items =
                ch.queryForList(sqlItems + " LIMIT ? OFFSET ?", pageArgs);

        Map<String, Object> res = new HashMap<>();
        res.put("items", items);
        res.put("total", total == null ? 0 : total);
        res.put("page", Math.max(page, 0));
        res.put("size", limit);
        return res;
    }
}
