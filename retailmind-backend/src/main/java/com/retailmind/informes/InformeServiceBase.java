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
        int limit = Math.min(Math.max(size, 1), MAX_PAGINA);
        int offset = Math.max(page, 0) * limit;

        Integer total = pg.queryForObject(sqlCount, Integer.class, args);

        Object[] pageArgs = new Object[args.length + 2];
        System.arraycopy(args, 0, pageArgs, 0, args.length);
        pageArgs[args.length] = limit;
        pageArgs[args.length + 1] = offset;

        List<Map<String, Object>> items =
                pg.queryForList(sqlItems + " LIMIT ? OFFSET ?", pageArgs);

        Map<String, Object> res = new HashMap<>();
        res.put("items", items);
        res.put("total", total == null ? 0 : total);
        res.put("page", Math.max(page, 0));
        res.put("size", limit);
        return res;
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
