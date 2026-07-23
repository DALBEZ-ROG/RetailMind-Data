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

    /** Venta facturada del mes de la meta (avance contra la meta). */
    private static final String VENTA_REAL_SQL = """
            CASE WHEN m.departamento IN ('general', 'ventas') THEN
                (SELECT COALESCE(sum(fv.total), 0)
                 FROM factura_venta fv
                 WHERE fv.estado <> 'anulada'
                   AND fv.fecha_emision >= make_date(m.anio, m.mes, 1)
                   AND fv.fecha_emision <  make_date(m.anio, m.mes, 1) + interval '1 month')
            END AS venta_real""";

    private final JdbcTemplate pg;

    public MetasVentaService(@Qualifier("pgJdbcTemplate") JdbcTemplate pg) {
        this.pg = pg;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listar() {
        return pg.queryForList("""
                SELECT m.id, m.anio, m.mes, m.departamento, m.monto_meta, m.notas,
                       m.activo, m.fecha_creacion, m.fecha_actualizacion,
                       trim(concat(u.nombre, ' ', COALESCE(u.apellido, ''))) AS fijada_por,
                       """ + VENTA_REAL_SQL + """
                FROM meta_venta m
                LEFT JOIN usuario u ON u.id = m.fijada_por
                ORDER BY m.anio DESC, m.mes DESC, m.departamento""");
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
