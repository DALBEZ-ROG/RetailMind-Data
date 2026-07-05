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
    private final String nombre;

    public AppUserPrincipal(String email,
                            String passwordHash,
                            Collection<? extends GrantedAuthority> authorities,
                            Long usuarioId,
                            Long clienteId,
                            String rolCodigo,
                            String nombre) {
        super(email, passwordHash, authorities);
        this.usuarioId = usuarioId;
        this.clienteId = clienteId;
        this.rolCodigo = rolCodigo;
        this.nombre = nombre;
    }

    public Long getUsuarioId()  { return usuarioId; }
    public Long getClienteId()  { return clienteId; }
    public String getRolCodigo() { return rolCodigo; }
    public String getNombre()   { return nombre; }
}
