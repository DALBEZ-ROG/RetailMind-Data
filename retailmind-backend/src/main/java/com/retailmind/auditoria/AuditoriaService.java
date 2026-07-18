package com.retailmind.auditoria;

import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.retailmind.auth.AppUserPrincipal;

/**
 * Rastro de auditoría centralizado sobre log_auditoria (script 42).
 *
 * Generaliza el patrón que ya usaba la aprobación de orden de compra
 * (ComprasService, scripts 19/30): una fila por acción crítica con el
 * usuario del JWT (NUNCA del body), la entidad afectada y el antes/después
 * como jsonb. La tabla es append-only por grants (INSERT sin UPDATE/DELETE
 * para los roles operativos; solo admin puede corregirla) y sin RLS.
 *
 * DEBE invocarse DENTRO de la @Transactional del caso de uso: así corre bajo
 * el SET LOCAL ROLE del usuario (PgSessionRoleAspect) y el log se confirma o
 * revierte junto con la acción que documenta. Roles con INSERT:
 * admin/gerente (19/30) + vendedor/despacho/compras (42).
 */
@Service
public class AuditoriaService {

    /** Lista blanca = CHECK log_auditoria_accion_check de la BD. */
    private static final Set<String> ACCIONES =
            Set.of("INSERT", "UPDATE", "DELETE", "LOGIN", "LOGOUT", "OTRO");

    private final JdbcTemplate pg;
    private final ObjectMapper json;

    public AuditoriaService(@Qualifier("pgJdbcTemplate") JdbcTemplate pg, ObjectMapper json) {
        this.pg = pg;
        this.json = json;
    }

    /**
     * Registra una acción en log_auditoria. El autor sale del JWT del hilo.
     *
     * @param tabla           entidad afectada (nombre de tabla)
     * @param registroId      id del registro afectado
     * @param accion          INSERT/UPDATE/DELETE/... (CHECK de la BD)
     * @param datosAnteriores estado previo relevante (null si es creación)
     * @param datosNuevos     estado nuevo relevante
     */
    public void registrar(String tabla, Long registroId, String accion,
                          Map<String, Object> datosAnteriores,
                          Map<String, Object> datosNuevos) {
        if (!ACCIONES.contains(accion)) {
            throw new IllegalArgumentException(
                    "Acción de auditoría no permitida: " + accion);
        }
        pg.update("""
                INSERT INTO log_auditoria
                    (usuario_id, tabla, registro_id, accion, datos_anteriores, datos_nuevos)
                VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb)""",
                usuarioActualId(), tabla, registroId, accion,
                aJson(datosAnteriores), aJson(datosNuevos));
    }

    private String aJson(Map<String, Object> datos) {
        if (datos == null) {
            return null;
        }
        try {
            return json.writeValueAsString(datos);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar el dato de auditoría", e);
        }
    }

    private Long usuarioActualId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppUserPrincipal p) {
            return p.getUsuarioId();
        }
        return null;
    }
}
