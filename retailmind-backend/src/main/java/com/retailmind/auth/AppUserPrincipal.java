package com.retailmind.auth;

import java.util.Collection;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

/**
 * Principal autenticado contra la tabla usuario de PostgreSQL.
 * Además del email (username) y las authorities, carga lo que el aspecto
 * PgSessionRoleAspect necesita por transacción: el código del rol de app
 * (para SET LOCAL ROLE) y el cliente_id (para SET LOCAL app.cliente_id).
 */
public class AppUserPrincipal extends User {

    private final Long usuarioId;
    private final Long clienteId;   // null si el usuario no es un cliente
    private final String rolCodigo; // rol.codigo, p.ej. ADMIN, VENDEDOR
    private final String rolMotor;  // grp_* que se asume; null = resolver por enum
    private final String nombre;

    public AppUserPrincipal(String email,
                            String passwordHash,
                            Collection<? extends GrantedAuthority> authorities,
                            Long usuarioId,
                            Long clienteId,
                            String rolCodigo,
                            String nombre) {
        this(email, passwordHash, authorities, usuarioId, clienteId, rolCodigo, null, nombre);
    }

    public AppUserPrincipal(String email,
                            String passwordHash,
                            Collection<? extends GrantedAuthority> authorities,
                            Long usuarioId,
                            Long clienteId,
                            String rolCodigo,
                            String rolMotor,
                            String nombre) {
        super(email, passwordHash, authorities);
        this.usuarioId = usuarioId;
        this.clienteId = clienteId;
        this.rolCodigo = rolCodigo;
        this.rolMotor = rolMotor;
        this.nombre = nombre;
    }

    public Long getUsuarioId()  { return usuarioId; }
    public Long getClienteId()  { return clienteId; }
    public String getRolCodigo() { return rolCodigo; }
    public String getNombre()   { return nombre; }

    /**
     * Rol de motor a asumir, cuando el usuario lleva un rol PERSONALIZADO
     * (script 87). Para los 9 del sistema es {@code null} y el aspecto lo
     * resuelve por el enum {@code DbGroupRole}, como siempre.
     */
    public String getRolMotor() { return rolMotor; }
}
