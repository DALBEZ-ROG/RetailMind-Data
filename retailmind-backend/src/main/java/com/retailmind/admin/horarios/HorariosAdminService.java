package com.retailmind.admin.horarios;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lee/escribe SOLO grupo_horario (ventanas horarias por rol de grupo).
 * No toca ninguna otra tabla ni objeto de seguridad.
 */
@Service
public class HorariosAdminService {

    /**
     * Los 9 roles de grupo del sistema. `grp_soporte` (script 37) faltaba y
     * hacía imposible crear una ventana para soporte desde la interfaz, aunque
     * la tabla ya tenía las suyas sembradas.
     */
    private static final List<String> ROLES_VALIDOS = List.of(
            "grp_gerente", "grp_vendedor", "grp_compras", "grp_bodega",
            "grp_despacho", "grp_cliente", "grp_analista", "grp_soporte",
            "grp_administrador");

    private final JdbcTemplate pg;

    public HorariosAdminService(@Qualifier("pgJdbcTemplate") JdbcTemplate pg) {
        this.pg = pg;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listar() {
        return pg.queryForList("""
                SELECT id, rol_grupo, dia_semana,
                       to_char(hora_inicio, 'HH24:MI') AS hora_inicio,
                       to_char(hora_fin, 'HH24:MI')    AS hora_fin,
                       activo
                FROM grupo_horario
                ORDER BY rol_grupo, dia_semana, hora_inicio""");
    }

    @Transactional
    public long crear(String rolGrupo, Integer diaSemana, String horaInicio,
                      String horaFin, boolean activo) {
        validar(rolGrupo, diaSemana, horaInicio, horaFin);
        return pg.queryForObject("""
                INSERT INTO grupo_horario (rol_grupo, dia_semana, hora_inicio, hora_fin, activo)
                VALUES (?, ?, ?::time, ?::time, ?) RETURNING id""",
                Long.class, rolGrupo, diaSemana, horaInicio, horaFin, activo);
    }

    @Transactional
    public void editar(long id, String horaInicio, String horaFin, Boolean activo) {
        if (horaInicio == null || horaFin == null || activo == null) {
            throw new IllegalArgumentException("horaInicio, horaFin y activo son requeridos");
        }
        int filas = pg.update("""
                UPDATE grupo_horario
                SET hora_inicio = ?::time, hora_fin = ?::time, activo = ?
                WHERE id = ?""", horaInicio, horaFin, activo, id);
        if (filas == 0) {
            throw new IllegalArgumentException("No existe la ventana horaria " + id);
        }
    }

    private void validar(String rolGrupo, Integer diaSemana, String horaInicio, String horaFin) {
        if (!ROLES_VALIDOS.contains(rolGrupo)) {
            throw new IllegalArgumentException("rol_grupo invalido: " + rolGrupo);
        }
        if (diaSemana == null || diaSemana < 0 || diaSemana > 6) {
            throw new IllegalArgumentException("dia_semana debe estar entre 0 (domingo) y 6 (sabado)");
        }
        if (horaInicio == null || horaFin == null) {
            throw new IllegalArgumentException("hora_inicio y hora_fin son requeridas");
        }
    }
}
