package com.retailmind.security;

import java.util.Optional;

/**
 * LISTA BLANCA fija de los roles de grupo de PostgreSQL.
 * El código de rol de la app (tabla rol.codigo, viaja en el JWT) se mapea
 * aquí a su grp_*. El nombre del rol de motor NUNCA se interpola desde texto
 * del usuario: solo puede salir de este enum, lo que elimina la inyección
 * SQL en el SET LOCAL ROLE.
 */
public enum DbGroupRole {

    ADMIN("grp_administrador"),
    GERENTE("grp_gerente"),
    VENDEDOR("grp_vendedor"),
    COMPRAS("grp_compras"),
    BODEGA("grp_bodega"),
    DESPACHO("grp_despacho"),
    CLIENTE("grp_cliente"),
    ANALISTA("grp_analista");

    private final String pgRole;

    DbGroupRole(String pgRole) {
        this.pgRole = pgRole;
    }

    public String getPgRole() {
        return pgRole;
    }

    /** Resuelve el código de rol de la app contra la lista blanca. */
    public static Optional<DbGroupRole> fromCodigo(String codigo) {
        if (codigo == null) {
            return Optional.empty();
        }
        for (DbGroupRole r : values()) {
            if (r.name().equalsIgnoreCase(codigo)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }
}
