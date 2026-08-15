package com.retailmind.informes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.retailmind.auth.AppUserPrincipal;

/**
 * MOLDE de los informes tácticos departamentales (nivel táctico, catálogo
 * {@code docs/tactico/CATALOGO_OBJETIVOS_TACTICOS.md}).
 *
 * Cada departamento (Ventas, Compras, Inventario, Logística, Soporte,
 * Gerencia) tiene UN servicio que extiende esta clase; aquí vive todo lo que
 * NO cambia entre departamentos:
 *
 * <ul>
 *   <li><b>Validación por lista blanca</b> ({@link #opcion}, {@link #entero},
 *       {@link #fecha}): un valor de filtro que no esté en la lista blanca
 *       revienta con {@link IllegalArgumentException} → 400 por
 *       GlobalExceptionHandler. NUNCA se concatena texto del usuario en el SQL.</li>
 *   <li><b>Paginación server-side</b> ({@link #paginar}): las cadenas SQL que
 *       recibe son SIEMPRE constantes escritas por el desarrollador; los datos
 *       del usuario viajan exclusivamente en el array de parámetros.</li>
 *   <li><b>Resumen de cabecera</b> ({@link #kpi}): tarjetas de totales que la
 *       pantalla genérica pinta encima de la tabla.</li>
 *   <li><b>Identidad del solicitante</b> ({@link #rolActual}, {@link #usuarioActualId}):
 *       para los informes que se recortan según quién pregunta (p. ej. el
 *       VENDEDOR solo ve lo suyo en OTD-VEN-02).</li>
 * </ul>
 *
 * SEGURIDAD: los informes son SOLO LECTURA y corren en {@code @Transactional
 * (readOnly = true)} en las subclases, de modo que PgSessionRoleAspect asuma
 * el rol de grupo y la BD aplique GRANTs por columna + RLS + horario. La
 * segregación financiera NO se implementa aquí: se declara en SecurityConfig
 * (Bodega y Despacho fuera de los informes con monto) y el motor la respalda
 * (grp_bodega/grp_despacho no tienen SELECT sobre pedido.total).
 *
 * Un rol fuera de su ventana horaria recibe 0 filas por RLS (pol_horario) o un
 * SQLState 42501 que GlobalExceptionHandler traduce a 403 con mensaje claro.
 */
public abstract class InformeServiceBase {

    /** Tope duro de filas por página: ningún informe descarga la tabla entera. */
    protected static final int MAX_PAGINA = 200;

    protected final JdbcTemplate pg;

    protected InformeServiceBase(JdbcTemplate pg) {
        this.pg = pg;
    }

    // ── Validación de filtros (lista blanca) ─────────────────────────────

    /** Cadena vacía o en blanco → null (el SQL la trata como «sin filtro»). */
    protected static String texto(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /**
     * Valida un filtro de valor cerrado contra su lista blanca. Devuelve null
     * cuando no se envió (= sin filtro) y lanza 400 si llegó algo fuera de la
     * lista. Es la única puerta por la que un valor de filtro llega al SQL.
     */
    protected static String opcion(String valor, Set<String> permitidos, String nombreFiltro) {
        String v = texto(valor);
        if (v == null) {
            return null;
        }
        String norm = v.toLowerCase();
        if (!permitidos.contains(norm)) {
            throw new IllegalArgumentException("Valor no permitido para el filtro «"
                    + nombreFiltro + "»: " + valor + ". Permitidos: "
                    + String.join(", ", permitidos.stream().sorted().toList()));
        }
        return norm;
    }

    /** Entero acotado; fuera de rango es error del usuario, no un recorte silencioso. */
    protected static int entero(Integer valor, int min, int max, int porDefecto, String nombreFiltro) {
        if (valor == null) {
            return porDefecto;
        }
        if (valor < min || valor > max) {
            throw new IllegalArgumentException("El filtro «" + nombreFiltro + "» debe estar entre "
                    + min + " y " + max);
        }
        return valor;
    }

    /**
     * Fecha ISO (YYYY-MM-DD) validada en Java antes de tocar la BD, para dar
     * un 400 legible en vez del error de casteo de PostgreSQL. Se devuelve
     * como texto porque el SQL la castea con {@code ?::date}.
     */
    protected static String fecha(String valor, String nombreFiltro) {
        String v = texto(valor);
        if (v == null) {
            return null;
        }
        try {
            java.time.LocalDate.parse(v);
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("El filtro «" + nombreFiltro
                    + "» debe ser una fecha con formato AAAA-MM-DD");
        }
        return v;
    }

    /**
     * Convierte un rango de DÍAS en el par de INSTANTES con que hay que
     * filtrar una columna {@code timestamptz}, semiabierto {@code [desde, hasta)}.
     *
     * <h3>Por qué esto existe y no se compara contra la fecha a secas</h3>
     * Los informes filtraban con {@code (?::date IS NULL OR col >= ?::date)}, y
     * esa línea tenía DOS problemas encadenados que solo se ven a escala:
     *
     * <ol>
     *   <li><b>Compara tipos distintos.</b> {@code col} es {@code timestamptz} y
     *       el parámetro es {@code date}, así que el operador es
     *       {@code timestamptz_ge_date}, que tiene {@code proleakproof = false}
     *       (verificado en {@code pg_proc}; {@code timestamptz_ge} sí lo es).
     *       Todas estas tablas llevan RLS con {@code pol_horario}, cuyo qual de
     *       seguridad {@code esta_en_horario(fn_grupo_actual())} tampoco es
     *       leakproof, y PostgreSQL solo permite evaluar una condición del
     *       usuario ANTES del qual de seguridad —que es lo que hace un
     *       {@code Index Cond}— si esa condición es leakproof. Resultado: el
     *       índice de fecha NO se usa y, peor, la función de horario se ejecuta
     *       fila a fila sobre la tabla entera.</li>
     *   <li><b>El guard {@code (? IS NULL OR ...)} es opaco.</b> Un OR con un
     *       test de nulidad sobre un parámetro no puede convertirse en condición
     *       de índice ni cuando el operador sí es leakproof.</li>
     * </ol>
     *
     * Medido bajo {@code grp_administrador}, contando un día:
     * <pre>
     *   movimiento_inventario (8,0 M, con índice)  17.060 ms →   17,6 ms
     *   pedido                (3,0 M, con índice)   4.874 ms →    7,8 ms
     *   envio                 (2,11 M, SIN índice)  3.773 ms →   96,8 ms
     *   devolucion            (145 k, SIN índice)   1.047 ms →   25,4 ms
     *   ticket_soporte        (179 k, SIN índice)     663 ms →   29,1 ms
     * </pre>
     * Nótese que gana incluso donde NO hay índice de fecha: con el predicado
     * leakproof el motor descarta por fecha primero y solo llama a
     * {@code esta_en_horario()} sobre las filas supervivientes.
     *
     * <h3>Cómo se usa</h3>
     * El WHERE se arma SOLO con los límites presentes —piezas constantes del
     * código— y los dos instantes viajan como parámetros ligados:
     * {@code AND col >= ?::timestamptz} / {@code AND col < ?::timestamptz}.
     *
     * <h3>Detalles que hay que respetar</h3>
     * <ul>
     *   <li>El intervalo es SEMIABIERTO y {@code hasta} es INCLUSIVO por día:
     *       la frontera superior es el día siguiente a medianoche, no las 23:59
     *       (eso dejaría fuera el último segundo). Es la misma semántica que
     *       tenía {@code < (?::date + 1)}.</li>
     *   <li>La conversión la hace la BASE DE DATOS, no Java: el paso de
     *       {@code date} a {@code timestamptz} depende del {@code TimeZone} de
     *       la sesión (America/Guayaquil), que es el mismo ancla de
     *       {@code esta_en_horario}.</li>
     *   <li>Vuelven como TEXTO con desplazamiento explícito
     *       («2026-08-14 00:00:00-05») y no como {@code java.sql.Timestamp}: un
     *       Timestamp no lleva zona y el driver lo formatearía con la de la JVM,
     *       que no tiene por qué coincidir con la de PostgreSQL.</li>
     *   <li>Si NO hay ningún límite no se consulta nada: devuelve {@code null}
     *       en las dos posiciones y el WHERE no incorpora la condición. Ese es
     *       además el caso normal, que antes pagaba el guard igualmente.</li>
     * </ul>
     *
     * <h3>NO se aplica a columnas de tipo {@code date}, y está MEDIDO</h3>
     * {@code orden_compra.fecha_emision} es {@code date}: ahí el operador es
     * {@code date_ge}/{@code date_le}, que YA es leakproof, y el guard
     * {@code (? IS NULL OR ...)} tampoco estorba —el planificador lo PLIEGA al
     * construir el plan a medida con los valores reales del parámetro
     * (verificado con PREPARE: los dos planes son idénticos, con
     * {@code Index Cond} sobre {@code idx_orden_compra_fecha}, 13,2 vs 8,9 ms)—.
     *
     * Se intentó unificar igualmente los dos informes de Compras
     * (OTD-COM-01 y OTD-COM-11) «por coherencia» y se REVIRTIÓ: medido dos veces
     * con reconstrucción de por medio, el cambio los dejaba ~2× más lentos
     * (120 → 270 ms y 295 → 568 ms) sin arreglar nada. La causa exacta de esa
     * diferencia no se identificó a nivel de SQL —los planes de la consulta de
     * conteo son idénticos—, y eso es justamente el motivo de no tocarlos: no se
     * cambia lo que ya funciona a cambio de un empeoramiento que no se sabe
     * explicar. Si alguien vuelve a intentarlo, que mida antes.
     *
     * @param desde primer día incluido, «AAAA-MM-DD» ya validado, o null
     * @param hasta último día INCLUIDO, «AAAA-MM-DD» ya validado, o null
     * @return {@code [desdeInstante, hastaInstanteExclusivo]}, con null donde no
     *         haya límite
     */
    protected String[] instantesDelDia(String desde, String hasta) {
        if (desde == null && hasta == null) {
            return new String[] { null, null };
        }
        Map<String, Object> r = pg.queryForMap("""
                SELECT (?::date)::timestamptz::text       AS desde,
                       ((?::date) + 1)::timestamptz::text AS hasta""", desde, hasta);
        return new String[] { (String) r.get("desde"), (String) r.get("hasta") };
    }

    /**
     * Trozo de WHERE del filtro por día, con SOLO los límites presentes.
     *
     * Se añade al FINAL del WHERE y sus parámetros al final del array (ver
     * {@link #conLimites}), para no reordenar los que ya había.
     *
     * @param columna nombre de la columna, SIEMPRE un literal escrito en el
     *                código —nunca un dato del usuario, que viaja ligado en
     *                {@code conLimites}—, igual que el resto de piezas
     *                constantes de SQL de la casa
     * @param ts      lo que devolvió {@link #instantesDelDia}
     */
    protected static String filtroDia(String columna, String[] ts) {
        return (ts[0] == null ? "" : " AND " + columna + " >= ?::timestamptz\n")
             + (ts[1] == null ? "" : " AND " + columna + " <  ?::timestamptz\n");
    }

    /**
     * Añade al final los instantes presentes, en el mismo orden del SQL.
     *
     * Se copia a mano y no con {@code List.of(args)}: los filtros ausentes son
     * null y {@code List.of} no admite nulls.
     */
    protected static Object[] conLimites(Object[] args, String[] ts) {
        List<Object> todos = new ArrayList<>();
        if (args != null) {
            for (Object a : args) { todos.add(a); }
        }
        if (ts[0] != null) { todos.add(ts[0]); }
        if (ts[1] != null) { todos.add(ts[1]); }
        return todos.toArray();
    }

    /** Rango coherente: desde nunca puede ser posterior a hasta. */
    protected static void exigirRangoValido(String desde, String hasta) {
        if (desde != null && hasta != null && desde.compareTo(hasta) > 0) {
            throw new IllegalArgumentException(
                    "La fecha «desde» no puede ser posterior a la fecha «hasta»");
        }
    }

    // ── Ejecución paginada ───────────────────────────────────────────────

    /**
     * Ejecuta el par conteo + página de un informe.
     *
     * @param sqlItems SELECT completo SIN LIMIT/OFFSET — constante del código
     * @param sqlCount SELECT count(*) equivalente — constante del código
     * @param args     parámetros (los MISMOS y en el mismo orden para ambos SQL)
     * @return sobre estándar {items, total, page, size} que consume la pantalla genérica
     */
    protected Map<String, Object> paginar(String sqlItems, String sqlCount, Object[] args,
                                          int page, int size) {
        // La implementación vive en com.retailmind.comun.Paginacion desde que
        // hubo que paginar FUERA de los informes (ventas/pedidos tumbaba el
        // servidor con OutOfMemoryError). Se delega en vez de duplicar para que
        // el tope y la forma del sobre no puedan separarse con el tiempo.
        return com.retailmind.comun.Paginacion.paginar(pg, sqlItems, sqlCount, args, page, size);
    }

    /** Sobre sin paginar (informes de pocas filas por naturaleza: metas, ranking de equipo). */
    protected static Map<String, Object> sobre(List<Map<String, Object>> items) {
        Map<String, Object> res = new HashMap<>();
        res.put("items", items);
        res.put("total", items.size());
        res.put("page", 0);
        res.put("size", items.size());
        return res;
    }

    // ── Resumen de cabecera ──────────────────────────────────────────────

    /**
     * Tarjeta de resumen. {@code tipo} le dice a la pantalla genérica cómo
     * formatear: moneda | numero | porcentaje | texto | dias.
     */
    protected static Map<String, Object> kpi(String etiqueta, Object valor, String tipo) {
        Map<String, Object> k = new LinkedHashMap<>();
        k.put("etiqueta", etiqueta);
        k.put("valor", valor);
        k.put("tipo", tipo);
        return k;
    }

    /**
     * Página con el conteo ACOTADO (ver
     * {@link com.retailmind.comun.Paginacion#paginarConTope}). El sobre añade
     * {@code totalEsMinimo}; la pantalla genérica lo pinta como «más de N».
     *
     * @param cuerpoConteo {@code FROM … WHERE …} — constante del código, sin
     *                     SELECT y sin ORDER BY
     */
    protected Map<String, Object> paginarConTope(String sqlItems, String cuerpoConteo,
                                                 Object[] args, int page, int size) {
        return com.retailmind.comun.Paginacion.paginarConTope(
                pg, sqlItems, cuerpoConteo, args, page, Math.min(size, MAX_PAGINA));
    }

    /** ¿El conteo de este sobre llegó al tope, o sea es un mínimo? */
    protected static boolean conteoAcotado(Map<String, Object> sobre) {
        return Boolean.TRUE.equals(sobre.get("totalEsMinimo"));
    }

    /** Adjunta las tarjetas de resumen al sobre del informe. */
    protected static Map<String, Object> conResumen(Map<String, Object> sobre,
                                                    List<Map<String, Object>> kpis) {
        sobre.put("resumen", new ArrayList<>(kpis));
        return sobre;
    }

    // ── Identidad del solicitante ────────────────────────────────────────

    private static AppUserPrincipal principal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppUserPrincipal p) {
            return p;
        }
        throw new IllegalStateException("No se pudo identificar al usuario autenticado");
    }

    /** Código de rol del JWT (ADMIN, GERENTE, VENDEDOR…). */
    protected static String rolActual() {
        String rol = principal().getRolCodigo();
        return rol == null ? "" : rol.toUpperCase();
    }

    protected static Long usuarioActualId() {
        Long id = principal().getUsuarioId();
        if (id == null) {
            throw new IllegalStateException("El usuario autenticado no tiene id de usuario");
        }
        return id;
    }
}
