package com.retailmind.gerencia;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.retailmind.auth.AppUserPrincipal;

/**
 * Metas de venta por período (meta_venta, script 48 — OTD-VEN-15).
 *
 * GERENTE/ADMIN fijan y editan; VENDEDOR/ANALISTA solo leen (SecurityConfig
 * espeja los GRANTs). Todo dentro de @Transactional para que
 * PgSessionRoleAspect asuma el rol de grupo.
 *
 * A PROPÓSITO no se valida que el período sea futuro o actual: se sembrarán
 * metas históricas de 12-18 meses junto con los datos de volumen.
 *
 * venta_real compara la meta contra las facturas de venta no anuladas del mes
 * (solo aplica a metas 'general'/'ventas'; en otros departamentos va NULL).
 * fijada_por sale SIEMPRE del JWT; fecha_actualizacion la pone el trigger touch.
 */
@Service
public class MetasVentaService {

    /** Lista blanca que espeja el CHECK de meta_venta.departamento. */
    private static final Set<String> DEPARTAMENTOS = Set.of(
            "general", "ventas", "compras", "inventario",
            "logistica", "soporte", "marketing");

    /**
     * Venta facturada del mes de la meta (avance contra la meta).
     *
     * OJO: el bloque CIERRA EN SU PROPIA LÍNEA para que la cadena TERMINE en
     * '\n'. Un bloque de texto de Java NO añade salto tras la última línea si
     * el delimitador va pegado (`... venta_real"""`), y este fragmento se
     * concatena delante de un `FROM`: sin ese salto sale
     * `END AS venta_realFROM meta_venta m`, que es sintaxis inválida y solo
     * revienta en tiempo de ejecución (ERROR: syntax error at or near
     * "meta_venta"). Antes se compensaba con una LÍNEA EN BLANCO invisible en
     * cada punto de uso — `listar()` la tenía y `vigente()` no, así que
     * /api/gerencia/metas/vigente respondía 500 mientras /metas iba en 200.
     * El salto vive AQUÍ, una sola vez, y no en cada concatenación.
     * (Misma familia de trampa que `InformesComprasService:541-544`, donde el
     * bloque recorta el espacio FINAL de cada línea.)
     */
    private static final String VENTA_REAL_SQL = """
            CASE WHEN m.departamento IN ('general', 'ventas') THEN
                (SELECT COALESCE(sum(fv.total), 0)
                 FROM factura_venta fv
                 WHERE fv.estado <> 'anulada'
                   AND fv.fecha_emision >=  make_date(m.anio, m.mes, 1)::timestamptz
                   AND fv.fecha_emision <  (make_date(m.anio, m.mes, 1)
                                            + interval '1 month')::timestamptz)
            END AS venta_real
            """;

    /*
     * LOS DOS CASTEOS A timestamptz DE ARRIBA NO SON COSMÉTICOS.
     *
     * `fecha_emision` es timestamptz y `make_date(...)` devuelve date, así que
     * el operador era `timestamptz_ge_date`, con `proleakproof = false`. Como
     * `factura_venta` tiene RLS (`pol_horario`), PostgreSQL no deja evaluar un
     * qual no-leakproof del usuario ANTES del qual de seguridad —que es
     * justamente lo que hace un `Index Cond`—, así que el índice
     * `idx_factura_venta_fecha` quedaba inservible y la subconsulta recorría
     * las 2.855.378 facturas ENTERAS. Y no una vez: esta subconsulta es
     * CORRELACIONADA y se ejecuta por cada meta de 'general'/'ventas', que hoy
     * son 38.
     *
     * Medido bajo grp_administrador, UNA sola fila de meta:
     *     antes    25.734 ms
     *     después     139 ms      (mismo valor: 1.900.217,38)
     * o sea el listado completo pasaba de ~16 min a ~5 s. `/api/gerencia/metas`
     * no era «lento»: era una pantalla que no llegaba a abrirse nunca.
     *
     * `timestamptz_ge` y `timestamptz_lt` sí son leakproof, y con ellos el plan
     * usa el índice. La frontera superior se castea DESPUÉS de sumar el mes
     * porque `date + interval` da `timestamp` (sin zona), que sería otra
     * comparación cruzada.
     */

    private final JdbcTemplate pg;

    public MetasVentaService(@Qualifier("pgJdbcTemplate") JdbcTemplate pg) {
        this.pg = pg;
    }

    /**
     * Listado de metas con su avance.
     *
     * <h3>Por qué NO usa {@link #VENTA_REAL_SQL}, que es la forma obvia</h3>
     * Esa subconsulta es CORRELACIONADA: se ejecuta una vez por meta de
     * 'general'/'ventas', y hoy son 38 que cubren solo 19 meses distintos — o
     * sea que la mitad del trabajo es repetido. Aun con el predicado ya
     * corregido a leakproof, medido bajo grp_administrador: <b>7.761 ms</b>.
     *
     * Aquí se da UNA sola pasada: se agrupa `factura_venta` por mes en el rango
     * que cubren las metas y se cruza por mes. Medido: <b>774 ms</b>, mismo
     * importe al céntimo (160.701.002,86).
     *
     * <h3>Las dos fronteras se resuelven APARTE y viajan ligadas</h3>
     * Calcularlas dentro, con un CTE tipo {@code WITH r AS (SELECT min(...))},
     * parece más limpio y cuesta 3.458 ms: el rango deja de ser constante, el
     * predicado no puede bajar a {@code Index Cond} y el índice no se usa. Es
     * la misma trampa que documenta {@code InformesGerenciaService.fotoDia}.
     * Por eso se resuelven en una consulta previa —que no toca
     * `factura_venta`— y se pasan como parámetros.
     *
     * Con `idx_factura_venta_fecha_cubriente` el plan es
     * {@code Parallel Index Only Scan} con {@code Heap Fetches: 0}.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listar() {
        // Rango que cubren las metas. Si no hay ninguna, no hay nada que sumar.
        Map<String, Object> r = pg.queryForMap("""
                SELECT min(make_date(anio, mes, 1))::timestamptz::text                    AS desde,
                       (max(make_date(anio, mes, 1)) + interval '1 month')::timestamptz::text AS hasta
                FROM meta_venta""");
        String desde = (String) r.get("desde");
        String hasta = (String) r.get("hasta");
        if (desde == null || hasta == null) {
            return List.of();
        }
        return pg.queryForList("""
                WITH venta_mes AS (
                    SELECT date_trunc('month', fv.fecha_emision) AS mes,
                           sum(fv.total)                         AS total
                    FROM factura_venta fv
                    WHERE fv.estado <> 'anulada'
                      AND fv.fecha_emision >= ?::timestamptz
                      AND fv.fecha_emision <  ?::timestamptz
                    GROUP BY 1)
                SELECT m.id, m.anio, m.mes, m.departamento, m.monto_meta, m.notas,
                       m.activo, m.fecha_creacion, m.fecha_actualizacion,
                       trim(concat(u.nombre, ' ', COALESCE(u.apellido, ''))) AS fijada_por,
                       CASE WHEN m.departamento IN ('general', 'ventas')
                            THEN COALESCE(vm.total, 0) END AS venta_real
                FROM meta_venta m
                LEFT JOIN usuario u ON u.id = m.fijada_por
                LEFT JOIN venta_mes vm
                       ON vm.mes = make_date(m.anio, m.mes, 1)::timestamptz
                ORDER BY m.anio DESC, m.mes DESC, m.departamento""", desde, hasta);
    }

    /** Meta VIGENTE (activa) de un período y departamento, con su avance. */
    @Transactional(readOnly = true)
    public Map<String, Object> vigente(int anio, int mes, String departamento) {
        String depto = normalizarDepartamento(departamento);
        List<Map<String, Object>> filas = pg.queryForList("""
                SELECT m.id, m.anio, m.mes, m.departamento, m.monto_meta, m.notas,
                       m.fecha_creacion,
                       """ + VENTA_REAL_SQL + """
                FROM meta_venta m
                WHERE m.anio = ? AND m.mes = ? AND m.departamento = ? AND m.activo""",
                anio, mes, depto);
        if (filas.isEmpty()) {
            throw new NoSuchElementException("No hay meta vigente para " + depto
                    + " en " + mes + "/" + anio);
        }
        return filas.get(0);
    }

    @Transactional
    public long crear(Integer anio, Integer mes, String departamento,
                      Double montoMeta, String notas) {
        validarPeriodoYMonto(anio, mes, montoMeta);
        String depto = normalizarDepartamento(departamento);
        exigirPeriodoLibre(anio, mes, depto, null);
        return pg.queryForObject("""
                INSERT INTO meta_venta (anio, mes, departamento, monto_meta, notas, fijada_por)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id""",
                Long.class, anio, mes, depto, montoMeta, notas, usuarioActualId());
    }

    /** Edita la meta; quien edita queda como nuevo responsable (fijada_por del JWT). */
    @Transactional
    public void editar(long id, Integer anio, Integer mes, String departamento,
                       Double montoMeta, String notas) {
        validarPeriodoYMonto(anio, mes, montoMeta);
        String depto = normalizarDepartamento(departamento);
        exigirPeriodoLibre(anio, mes, depto, id);
        int filas = pg.update("""
                UPDATE meta_venta
                SET anio = ?, mes = ?, departamento = ?, monto_meta = ?, notas = ?,
                    fijada_por = ?
                WHERE id = ?""",
                anio, mes, depto, montoMeta, notas, usuarioActualId(), id);
        if (filas == 0) {
            throw new NoSuchElementException("No existe la meta " + id);
        }
    }

    @Transactional
    public void activar(long id, boolean activo) {
        int filas = pg.update("UPDATE meta_venta SET activo = ? WHERE id = ?", activo, id);
        if (filas == 0) {
            throw new NoSuchElementException("No existe la meta " + id);
        }
    }

    // ── Guardias ─────────────────────────────────────────────────────────

    /** Rango del CHECK; deliberadamente admite períodos PASADOS (siembra histórica). */
    private static void validarPeriodoYMonto(Integer anio, Integer mes, Double montoMeta) {
        if (anio == null || anio < 2000 || anio > 2100) {
            throw new IllegalArgumentException("El año de la meta debe estar entre 2000 y 2100");
        }
        if (mes == null || mes < 1 || mes > 12) {
            throw new IllegalArgumentException("El mes de la meta debe estar entre 1 y 12");
        }
        if (montoMeta == null || montoMeta <= 0) {
            throw new IllegalArgumentException("El monto de la meta debe ser mayor que cero");
        }
    }

    private static String normalizarDepartamento(String departamento) {
        String depto = departamento == null || departamento.isBlank()
                ? "general" : departamento.trim().toLowerCase();
        if (!DEPARTAMENTOS.contains(depto)) {
            throw new IllegalArgumentException("Departamento inválido. Permitidos: "
                    + String.join(", ", DEPARTAMENTOS.stream().sorted().toList()));
        }
        return depto;
    }

    /** Una sola meta por período+departamento (la BD también lo exige; aquí el mensaje es claro). */
    private void exigirPeriodoLibre(int anio, int mes, String depto, Long idActual) {
        Integer repetidas = pg.queryForObject("""
                SELECT count(*) FROM meta_venta
                WHERE anio = ? AND mes = ? AND departamento = ?
                  AND id <> COALESCE(?::bigint, -1)""",
                Integer.class, anio, mes, depto, idActual);
        if (repetidas != null && repetidas > 0) {
            throw new IllegalStateException("Ya existe una meta de " + depto + " para "
                    + mes + "/" + anio + "; edítala en lugar de crear otra");
        }
    }

    private Long usuarioActualId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppUserPrincipal p
                && p.getUsuarioId() != null) {
            return p.getUsuarioId();
        }
        throw new IllegalStateException("No se pudo identificar al usuario autenticado");
    }
}
