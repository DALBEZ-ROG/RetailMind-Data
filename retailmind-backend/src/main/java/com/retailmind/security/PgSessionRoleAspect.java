package com.retailmind.security;

import java.sql.Connection;
import java.sql.Statement;

import javax.sql.DataSource;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.retailmind.auth.AppUserPrincipal;

/**
 * Núcleo de la seguridad de motor a través de la app.
 *
 * Corre DENTRO de cada transacción de PostgreSQL (el advisor @Transactional
 * tiene order=0 en PostgresConfig y este aspecto Order(10), por lo que se
 * ejecuta después de abrir la tx) y, como primera sentencia:
 *
 *   1. Lee el usuario autenticado del SecurityContext (poblado por el JWT filter).
 *   2. Mapea su rol de app al grp_* SOLO vía la lista blanca {@link DbGroupRole}.
 *   3. Ejecuta SET LOCAL ROLE grp_x sobre la conexión de la transacción.
 *   4. Si es CLIENTE, además SET LOCAL app.cliente_id = <id> (RLS de aislamiento).
 *
 * SET LOCAL muere con el COMMIT/ROLLBACK: la conexión vuelve al pool de Hikari
 * como retailmind_app (NOINHERIT, sin privilegios de negocio), limpia.
 *
 * Sin usuario autenticado (login, arranque) no se asume rol: la conexión queda
 * limitada a los privilegios directos de retailmind_app (usuario/rol/usuario_rol).
 *
 * El paquete analytics/ (ClickHouse) queda excluido explícitamente.
 */
@Aspect
@Component
@Order(10)
public class PgSessionRoleAspect {

    private static final Logger logger = LoggerFactory.getLogger(PgSessionRoleAspect.class);

    private final DataSource pgDataSource;

    public PgSessionRoleAspect(@Qualifier("pgDataSource") DataSource pgDataSource) {
        this.pgDataSource = pgDataSource;
    }

    @Around("""
            (@within(org.springframework.transaction.annotation.Transactional)
             || @annotation(org.springframework.transaction.annotation.Transactional))
            && within(com.retailmind..*)
            && !within(com.retailmind.analytics..*)
            """)
    public Object asumirRolDeGrupo(ProceedingJoinPoint pjp) throws Throwable {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return pjp.proceed();
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AppUserPrincipal principal)) {
            return pjp.proceed();
        }

        DbGroupRole rol = DbGroupRole.fromCodigo(principal.getRolCodigo()).orElse(null);
        if (rol == null) {
            logger.warn("Rol de app '{}' sin mapeo a grupo de PostgreSQL; la tx corre sin SET ROLE",
                    principal.getRolCodigo());
            return pjp.proceed();
        }

        Connection con = DataSourceUtils.getConnection(pgDataSource);
        try {
            try (Statement st = con.createStatement()) {
                // rol.getPgRole() sale del enum (lista blanca): no hay inyección posible
                st.execute("SET LOCAL ROLE " + rol.getPgRole());
                if (rol == DbGroupRole.CLIENTE && principal.getClienteId() != null) {
                    // Long → literal numérico seguro
                    st.execute("SET LOCAL app.cliente_id = '" + principal.getClienteId() + "'");
                }
            }
        } finally {
            DataSourceUtils.releaseConnection(con, pgDataSource);
        }
        return pjp.proceed();
    }
}
