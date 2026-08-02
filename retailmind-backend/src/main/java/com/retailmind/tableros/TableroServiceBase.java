package com.retailmind.tableros;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.retailmind.informes.InformeCompuestoServiceBase;

/**
 * MOLDE de los TABLEROS DE DIRECCIÓN — nivel ESTRATÉGICO
 * ({@code docs/estrategico/DISENO_NIVEL_ESTRATEGICO.md} §4).
 *
 * <h2>Por qué un molde nuevo y no el de los informes</h2>
 *
 * Un informe táctico es una tabla con filtros: un solo conjunto de filas, un
 * solo grano, y por eso le basta el sobre
 * {@code {items, total, page, size, resumen[]}}. Un tablero NO es eso. Es una
 * vista de dirección donde conviven una serie temporal, un ranking, un embudo,
 * una dispersión y un semáforo, y donde el valor está en <b>leerlos juntos</b>
 * para tomar UNA decisión. Meter seis elementos de naturaleza distinta dentro
 * de un sobre pensado para uno solo obligaría a una de dos cosas —seis
 * peticiones o un {@code items} heterogéneo con columnas nulas— y las dos
 * empobrecen el tablero.
 *
 * <h3>La decisión: UNA respuesta por tablero, con bloques nombrados</h3>
 *
 * {@code GET /api/tableros/{tablero}} devuelve
 * {@code {tablero, titulo, decisiones[], periodo, kpis[], bloques[], salvedades[],
 * datosAl, fuente, analiticaDisponible}}. Cuatro razones, en orden de peso:
 *
 * <ol>
 *   <li><b>Coherencia de la foto.</b> Los seis elementos comparten filtros y se
 *       leen juntos. Con seis peticiones, un usuario que mueve el rango de
 *       meses mientras cargan puede acabar comparando un embudo de un período
 *       contra una serie de otro, y el tablero no da ninguna señal de ello.</li>
 *   <li><b>Una sola marca de agua.</b> «Datos al …» describe la pantalla
 *       entera; con seis respuestas habría seis marcas y la pantalla tendría
 *       que decidir cuál enseña.</li>
 *   <li><b>Una sola decisión de degradación.</b> O hay almacén o no lo hay. Con
 *       seis llamadas, ClickHouse cayéndose a mitad de la carga dejaría medio
 *       tablero pintado y medio vacío — que es exactamente la clase de pantalla
 *       plausible y equivocada que este nivel tiene prohibida.</li>
 *   <li><b>Coste.</b> Sobre ~64.000 filas ClickHouse agrega en milisegundos;
 *       seis agregados en una petición cuestan menos que seis peticiones.</li>
 * </ol>
 *
 * <h3>La excepción declarada: lo que NO sale del almacén</h3>
 *
 * Dos elementos de esta fase no tienen grano en el almacén y el diseño decidió
 * NO crear tabla para ellos (§4, R-7): el carrito abandonado de T-1 y el
 * sobre-stock del presente de T-2. Los sirve la PANTALLA con una segunda
 * llamada al informe SIMPLE de PostgreSQL que ya existe (OTD-VEN-08 y
 * OTD-INV-08). Y eso tiene una consecuencia buena que conviene decir en voz
 * alta: <b>con ClickHouse apagado esos dos bloques siguen vivos</b>, que es
 * justo el invariante del sistema.
 *
 * <h2>Lo que este molde hereda y no repite</h2>
 *
 * Extiende {@link InformeCompuestoServiceBase} para reutilizar, sin duplicar,
 * lo que ya está probado en 39 informes compuestos: la validación por lista
 * blanca, el acumulador {@code Filtros} (fragmentos constantes + parámetros,
 * jamás texto del usuario en el SQL), la lectura {@code FINAL} de las
 * dimensiones, la marca de agua y —sobre todo— la clasificación de
 * {@code esFalloDeConexion}: <b>solo un fallo de CONEXIÓN degrada</b>; una
 * consulta mal formada se propaga como 500 para que el bug duela.
 *
 * <h2>La regla que gobierna todo lo que se pinta aquí</h2>
 *
 * <b>Ninguna cifra se presenta sin su denominador</b> (§2.3 del diseño, lección
 * de C2.7 y C4.2). Por eso {@link #bloque} exige el campo {@code denominador}
 * como argumento obligatorio y no como algo opcional que se olvida: en el nivel
 * estratégico una cifra sin su base no produce una pantalla rara, produce una
 * decisión.
 */
public abstract class TableroServiceBase extends InformeCompuestoServiceBase {

    private static final Logger logger = LoggerFactory.getLogger(TableroServiceBase.class);

    protected TableroServiceBase(JdbcTemplate pg, JdbcTemplate ch) {
        super(pg, ch);
    }

    // ── Bloques ──────────────────────────────────────────────────────────

    /**
     * Un elemento del tablero.
     *
     * @param id            clave estable que la pantalla usa para localizar el
     *                      bloque en su archivo de definiciones
     * @param titulo        cómo se llama en pantalla
     * @param visualizacion pista de dibujo: serie | serie_apilada | barras |
     *                      ranking | embudo | dispersion | semaforo | tabla
     * @param denominador   <b>obligatorio</b>: sobre qué población están
     *                      calculadas estas cifras. Puede ser «todos los
     *                      pedidos del período», pero tiene que estar escrito
     * @param items         las filas del bloque
     */
    protected static Map<String, Object> bloque(String id, String titulo, String visualizacion,
                                                String denominador,
                                                List<Map<String, Object>> items) {
        if (denominador == null || denominador.isBlank()) {
            // Un bloque sin denominador es un bug de programación, no un dato
            // que falta: se corta aquí y no en la pantalla del gerente.
            throw new IllegalStateException(
                    "El bloque «" + id + "» no declara su denominador. En el nivel "
                    + "estratégico ninguna cifra se presenta sin su base (§2.3).");
        }
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("id", id);
        b.put("titulo", titulo);
        b.put("visualizacion", visualizacion);
        b.put("denominador", denominador);
        b.put("items", items);
        b.put("filas", items.size());
        return b;
    }

    /**
     * Salvedad metodológica del bloque: cómo hay que leer estas cifras.
     *
     * No es documentación — es parte del dato, y la pantalla la pinta ENCIMA
     * del elemento. «El margen se computa contra el costo vigente, no
     * histórico» leído después del margen llega tarde.
     */
    protected static Map<String, Object> conSalvedad(Map<String, Object> bloque, String salvedad) {
        bloque.put("salvedad", salvedad);
        return bloque;
    }

    /** Aviso de MUESTRA DÉBIL: el bloque se pinta, pero no sostiene un juicio. */
    protected static Map<String, Object> conMuestra(Map<String, Object> bloque, String muestra) {
        bloque.put("muestra", muestra);
        return bloque;
    }

    // ── KPI de cabecera ──────────────────────────────────────────────────

    /**
     * Tarjeta de cabecera CON su nota. La firma de tres argumentos existe en el
     * molde táctico; aquí la nota no es un extra: es el denominador de la
     * tarjeta, y una tarjeta de dirección sin él es la cifra plausible que este
     * nivel persigue.
     */
    protected static Map<String, Object> kpi(String etiqueta, Object valor, String tipo,
                                             String nota) {
        Map<String, Object> k = new LinkedHashMap<>();
        k.put("etiqueta", etiqueta);
        k.put("valor", valor);
        k.put("tipo", tipo);
        k.put("nota", nota);
        return k;
    }

    // ── Marca de agua conjunta ───────────────────────────────────────────

    /**
     * «Datos al …» de un tablero que se sirve de VARIAS tablas.
     *
     * Se devuelve el <b>mínimo</b> de los {@code max(fecha_carga)} de las
     * tablas implicadas, no el máximo. Un tablero es tan fresco como su fuente
     * más rezagada: si {@code fact_pedido} se cargó hace una hora y
     * {@code fact_flujo_caja} hace dos días, anunciar «datos de hace una hora»
     * sería verdad sobre una tabla y mentira sobre la pantalla. La lista de
     * tablas viaja aparte ({@code tablasFuente}) para que se pueda auditar.
     *
     * Viaja ya FORMATEADA como texto: una fecha serializada la interpreta el
     * formateador del navegador como UTC y la muestra corrida un día
     * ({@code PATRON_INFORMES.md} §11).
     */
    protected String marcaDeAguaConjunta(String... tablas) {
        StringBuilder sql = new StringBuilder("SELECT formatDateTime(min(f), '%d/%m/%Y %H:%i') FROM (");
        for (int i = 0; i < tablas.length; i++) {
            if (i > 0) {
                sql.append(" UNION ALL ");
            }
            // Los nombres de tabla son constantes del código, nunca del usuario.
            sql.append("SELECT max(fecha_carga) AS f FROM ").append(DWH).append('.').append(tablas[i]);
        }
        sql.append(')');
        try {
            return ch.queryForObject(sql.toString(), String.class);
        } catch (DataAccessException e) {
            return null;
        }
    }

    // ── Ejecución con degradación ────────────────────────────────────────

    @FunctionalInterface
    protected interface ConstruccionTablero {
        Map<String, Object> get();
    }

    /**
     * Sirve el tablero y degrada SOLO si la analítica no está alcanzable.
     *
     * Misma disciplina que los informes compuestos, y por la misma razón:
     * capturar todo {@link DataAccessException} convertiría un error de SQL en
     * un tranquilizador «la analítica no está disponible», el tablero saldría
     * vacío con ClickHouse perfectamente vivo y la prueba por API pasaría en
     * verde. Aquí duele más todavía: un tablero vacío se lee como «no pasó
     * nada este trimestre».
     */
    protected Map<String, Object> servir(String codigo, String titulo, List<String> decisiones,
                                         ConstruccionTablero construccion) {
        try {
            return construccion.get();
        } catch (DataAccessException e) {
            if (!esFalloDeConexion(e)) {
                logger.error("Tablero {}: la consulta a ClickHouse falló (el motor SÍ "
                        + "respondió). Es un error del tablero, no una caída de la "
                        + "analítica.", codigo, e);
                throw e;
            }
            logger.warn("Tablero {} degradado: ClickHouse no está alcanzable ({})",
                    codigo, e.getMostSpecificCause().getMessage());
            return tableroDegradado(codigo, titulo, decisiones);
        }
    }

    /**
     * Sobre del tablero cuando el almacén no responde. Mantiene la MISMA forma
     * que un tablero servido —mismos campos, {@code bloques} y {@code kpis}
     * vacíos— para que la pantalla no necesite un camino aparte.
     */
    protected static Map<String, Object> tableroDegradado(String codigo, String titulo,
                                                          List<String> decisiones) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("tablero", codigo);
        t.put("titulo", titulo);
        t.put("decisiones", decisiones);
        t.put("kpis", List.of());
        t.put("bloques", List.of());
        t.put("salvedades", List.of());
        t.put("analiticaDisponible", false);
        t.put("avisoAnalitica",
                "El almacén analítico (ClickHouse) no responde, así que este tablero no "
                + "puede calcularse en este momento. El resto del sistema funciona con "
                + "normalidad: la operación y los informes de consulta directa siguen "
                + "activos, y los bloques de este tablero que se sirven desde PostgreSQL "
                + "se siguen viendo. Vuelve a intentarlo cuando el servicio de analítica "
                + "esté en línea.");
        return t;
    }

    /** Arma el sobre final del tablero con su marca de agua. */
    protected Map<String, Object> sobreTablero(String codigo, String titulo,
                                               List<String> decisiones,
                                               Map<String, Object> periodo,
                                               List<Map<String, Object>> kpis,
                                               List<Map<String, Object>> bloques,
                                               List<String> salvedades,
                                               String... tablasFuente) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("tablero", codigo);
        t.put("titulo", titulo);
        t.put("decisiones", decisiones);
        t.put("periodo", periodo);
        t.put("kpis", new ArrayList<>(kpis));
        t.put("bloques", new ArrayList<>(bloques));
        t.put("salvedades", new ArrayList<>(salvedades));
        t.put("analiticaDisponible", true);
        t.put("fuente", "ClickHouse · " + DWH);
        t.put("tablasFuente", List.of(tablasFuente));
        String datosAl = marcaDeAguaConjunta(tablasFuente);
        if (datosAl != null) {
            t.put("datosAl", datosAl);
        }
        return t;
    }

    // ── Período ──────────────────────────────────────────────────────────

    /**
     * Describe el período consultado, incluido el borde temporal.
     *
     * R-4 del diseño: el seed termina el 2026-07-22 y el calendario avanza. Un
     * tablero que ancla «hoy» al reloj del servidor parecerá muerto dentro de
     * tres meses. Los tableros anclan su presente al {@code max(mes)} del
     * almacén y lo DICEN — es la misma lección que OTD-GER-01 aprendió
     * emitiendo a mano una fila «Día sin movimiento».
     */
    protected Map<String, Object> periodo(String desde, String hasta, String tablaAncla) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("desde", desde);
        p.put("hasta", hasta);
        try {
            List<Map<String, Object>> r = ch.queryForList(
                    "SELECT formatDateTime(min(mes), '%Y-%m') AS primero, "
                    + "       formatDateTime(max(mes), '%Y-%m') AS ultimo, "
                    + "       count(DISTINCT mes) AS meses "
                    + "FROM " + DWH + "." + tablaAncla);
            if (!r.isEmpty()) {
                p.put("primerMesConDato", r.get(0).get("primero"));
                p.put("ultimoMesConDato", r.get(0).get("ultimo"));
                p.put("mesesConDato", r.get(0).get("meses"));
            }
        } catch (DataAccessException e) {
            // La marca de borde es contexto, no el dato: si falla, el tablero
            // se sirve igual. (Si ClickHouse está caído, `servir` ya degradó.)
            logger.debug("No se pudo leer el borde temporal de {}", tablaAncla, e);
        }
        return p;
    }

    // ── Utilidades numéricas ─────────────────────────────────────────────

    /**
     * Porcentaje en {@code Float64}, redondeado a dos decimales.
     *
     * Se escribe aquí y no a mano en cada consulta porque el error es fácil y
     * silencioso: dividir dos {@code Decimal} en ClickHouse devuelve un
     * {@code Decimal} con la escala del operando IZQUIERDO, de modo que 0,1508
     * se trunca a 0,15 y un margen del 15,08 % se publica como 15,00 %. El
     * dinero sigue siendo {@code Decimal} —esa regla no se toca—; un porcentaje
     * derivado no entra en ninguna suma que deba cuadrar al centavo.
     */
    protected static String pct(String numerador, String denominador) {
        return "round(toFloat64(" + numerador + ") / nullIf(toFloat64("
               + denominador + "), 0) * 100, 2)";
    }

    // ── Lectura de una celda del driver ──────────────────────────────────

    /**
     * Entero de una celda de ClickHouse. El driver devuelve {@code UInt64} como
     * {@link java.math.BigInteger} y {@code UInt32} como {@link Long}: castear
     * a un tipo concreto revienta en cuanto el ETL cambia la anchura de una
     * columna, así que se lee por {@link Number}.
     */
    protected static long num(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    /** Dinero de una celda. Nunca se convierte a double para operar con él. */
    protected static BigDecimal dec(Object v) {
        if (v instanceof BigDecimal b) {
            return b;
        }
        if (v instanceof java.math.BigInteger bi) {
            return new BigDecimal(bi);
        }
        return v instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : BigDecimal.ZERO;
    }

    protected static String txt(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    // ── Aritmética de las notas ──────────────────────────────────────────

    protected static double porcentaje(long parte, long total) {
        return total == 0 ? 0d : Math.round(parte * 10000d / total) / 100d;
    }

    protected static double porcentaje(BigDecimal parte, BigDecimal total) {
        if (parte == null || total == null || total.signum() == 0) {
            return 0d;
        }
        return Math.round(parte.doubleValue() * 10000d / total.doubleValue()) / 100d;
    }

    protected static BigDecimal div(BigDecimal total, long n) {
        if (total == null || n == 0) {
            return BigDecimal.ZERO;
        }
        return total.divide(BigDecimal.valueOf(n), 2, java.math.RoundingMode.HALF_UP);
    }

    // ── Formato de las notas (es-EC) ─────────────────────────────────────

    /**
     * Miles con punto. Se formatea en Java y no en el navegador porque estas
     * cifras van DENTRO de una frase («3.924 pedidos no cancelados»), no en una
     * celda de tabla: el formateador del cliente no puede alcanzarlas.
     */
    protected static String fmt(long n) {
        return String.format(java.util.Locale.US, "%,d", n).replace(',', '.');
    }

    /** Dinero en formato es-EC: punto de miles, coma decimal. */
    protected static String money(BigDecimal v) {
        if (v == null) {
            return "$0,00";
        }
        String s = String.format(java.util.Locale.US, "%,.2f", v);
        // Se pasa por un carácter puente ('#') porque sustituir la coma por el
        // punto y después el punto por la coma, en ese orden y sobre el mismo
        // texto, deja TODOS los separadores iguales: el segundo reemplazo
        // deshace el primero. El error no revienta, solo produce «$5.498.570.35».
        return "$" + s.replace(',', '#').replace('.', ',').replace('#', '.');
    }
}
