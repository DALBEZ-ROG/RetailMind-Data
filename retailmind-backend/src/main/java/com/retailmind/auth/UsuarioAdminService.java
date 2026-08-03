package com.retailmind.auth;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.retailmind.auditoria.AuditoriaService;
import com.retailmind.auth.PostgresUserRepository.PgUsuario;

/**
 * Gestión administrativa de usuarios: MODIFICAR (datos + rol) y la BAJA
 * LÓGICA que la pantalla presenta como «Eliminar» (regla 2 del patrón de
 * interfaz, docs/PATRON_UI.md).
 *
 * Reglas de oro respetadas:
 * <ul>
 *   <li>Todo el caso de uso va en UNA {@code @Transactional}, así que corre
 *       bajo el {@code SET LOCAL ROLE grp_administrador} que pone
 *       {@code PgSessionRoleAspect} y el log de auditoría se confirma o se
 *       revierte junto con el cambio que documenta.</li>
 *   <li>El rol se valida por LISTA BLANCA contra la tabla {@code rol} y viaja
 *       siempre como parámetro de JdbcTemplate: nunca se concatena.</li>
 *   <li>{@code fecha_actualizacion} no se escribe (la pone
 *       {@code trg_usuario_touch}).</li>
 *   <li>La CONTRASEÑA no se lee, no se modifica, no se devuelve y no se
 *       registra en auditoría por ninguna de estas rutas. Cambiarla no es
 *       parte de «Modificar»: exigiría su propio caso de uso.</li>
 *   <li>El autor sale SIEMPRE del JWT ({@code AuditoriaService}), nunca del
 *       cuerpo de la petición.</li>
 * </ul>
 */
@Service
public class UsuarioAdminService {

    private final PostgresUserRepository repo;
    private final AuditoriaService auditoria;

    public UsuarioAdminService(PostgresUserRepository repo, AuditoriaService auditoria) {
        this.repo = repo;
        this.auditoria = auditoria;
    }

    /**
     * Modifica nombre, apellido, teléfono y ROL de un usuario existente.
     * El email (credencial de login) es inmutable por esta vía.
     */
    @Transactional
    public void modificar(long id, String nombre, String apellido, String telefono,
                          String rolCodigo) {
        PgUsuario antes = repo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No existe el usuario " + id));

        if (nombre == null || nombre.isBlank()) {
            throw new IllegalArgumentException("El nombre es requerido");
        }
        String rol = rolCodigo == null ? null : rolCodigo.trim().toUpperCase();
        if (rol == null || rol.isBlank()) {
            throw new IllegalArgumentException("El rol es requerido");
        }
        if (!repo.rolExiste(rol)) {
            throw new IllegalArgumentException("Rol invalido: " + rolCodigo);
        }
        // El administrador semilla no puede perder su rol: sería la forma más
        // rápida de quedarse sin ningún ADMIN en el sistema.
        if (DataInitializer.ADMIN_EMAIL.equalsIgnoreCase(antes.email())
                && !"ADMIN".equals(rol)) {
            throw new IllegalStateException(
                    "No se puede cambiar el rol del administrador del sistema");
        }
        // Un CLIENTE tiene ficha en `cliente` y pedidos colgando de ella;
        // convertirlo en personal (o al revés) dejaría el vínculo incoherente.
        boolean eraCliente = "CLIENTE".equals(antes.rolCodigo());
        if (eraCliente != "CLIENTE".equals(rol)) {
            throw new IllegalStateException(
                    "No se puede convertir un usuario CLIENTE en personal interno ni al revés: "
                    + "la ficha de cliente y sus pedidos quedarían huérfanos");
        }

        repo.actualizarDatos(id, nombre.trim(), apellido, telefono);
        if (!rol.equals(antes.rolCodigo())) {
            repo.asignarRolUnico(id, rol);
        }

        auditoria.registrar("usuario", id, "UPDATE",
                datos(antes.nombre(), antes.apellido(), antes.telefono(), antes.rolCodigo(),
                        antes.activo()),
                datos(nombre.trim(), apellido, telefono, rol, antes.activo()));
    }

    /**
     * Baja/alta LÓGICA. Con {@code activo = false} el usuario deja de poder
     * iniciar sesión ({@code AuthService} lo rechaza con
     * {@code USUARIO_INACTIVO}) pero su historial no se toca.
     */
    @Transactional
    public void cambiarActivo(long id, boolean activo) {
        PgUsuario antes = repo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No existe el usuario " + id));

        if (!activo && DataInitializer.ADMIN_EMAIL.equalsIgnoreCase(antes.email())) {
            throw new IllegalStateException(
                    "No se puede desactivar al administrador del sistema");
        }
        Long yo = usuarioActualId();
        if (!activo && yo != null && yo == id) {
            throw new IllegalStateException(
                    "No puedes desactivar tu propia cuenta: perderías el acceso al sistema");
        }
        if (antes.activo() == activo) {
            return;  // idempotente: nada que cambiar, nada que auditar
        }

        repo.cambiarActivo(id, activo);

        auditoria.registrar("usuario", id, "UPDATE",
                Map.of("activo", antes.activo()),
                Map.of("activo", activo));
    }

    private static Map<String, Object> datos(String nombre, String apellido, String telefono,
                                             String rol, boolean activo) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nombre", nombre);
        m.put("apellido", apellido);
        m.put("telefono", telefono);
        m.put("rol", rol);
        m.put("activo", activo);
        return m;  // sin password_hash: la credencial no entra al rastro
    }

    private static Long usuarioActualId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppUserPrincipal p) {
            return p.getUsuarioId();
        }
        return null;
    }
}
