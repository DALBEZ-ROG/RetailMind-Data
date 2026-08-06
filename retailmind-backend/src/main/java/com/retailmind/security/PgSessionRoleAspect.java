package com.retailmind.security;

import java.sql.Connection;
import java.sql.PreparedStatement;

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
 *   2. Resuelve su rol de motor: la lista blanca {@link DbGroupRole} para los 9
 *      roles del sistema, o el `grp_*` de su rol PERSONALIZADO (script 87).
 *   3. Lo asume con set_config('role', ?, true) — equivalente a SET LOCAL ROLE,
 *      pero con el nombre como PARÁMETRO LIGADO en vez de concatenado.
 *   4. Si es CLIENTE, además app.cliente_id = <id> (RLS de aislamiento).
 *
 * El paso 3 es el que permite que un rol creado en caliente funcione sin
 * debilitar nada: la garantía de que un nombre de rol no puede inyectar SQL ya
 * no depende de que el nombre venga de un enum, sino de que NUNCA se concatena.
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

        // Rol de motor a asumir: el del enum para los 9 del sistema, o el que
        // trae el principal si el usuario lleva un rol PERSONALIZADO (script 87).
        String pgRole = rol != null ? rol.getPgRole() : principal.getRolMotor();
        if (pgRole == null) {
            logger.warn("Rol de app '{}' sin mapeo a grupo de PostgreSQL; la tx corre sin SET ROLE",
                    principal.getRolCodigo());
            return pjp.proceed();
        }

        Connection con = DataSourceUtils.getConnection(pgDataSource);
        try {
            // set_config('role', ?, true) EN VEZ DE «SET LOCAL ROLE » + nombre.
            // Es equivalente (mismo GUC, mismo alcance de transacción) pero el
            // nombre viaja como PARÁMETRO LIGADO, así que deja de existir la
            // concatenación de un identificador — y con ella la única vía por la
            // que un nombre de rol podría inyectar SQL. Eso es lo que permite
            // que el nombre venga de la BD (rol personalizado) sin debilitar
            // nada: antes la seguridad la daba el enum, ahora la da el protocolo.
            try (PreparedStatement ps = con.prepareStatement("SELECT set_config('role', ?, true)")) {
                ps.setString(1, pgRole);
                ps.execute();
            }
            if (rol == DbGroupRole.CLIENTE && principal.getClienteId() != null) {
                try (PreparedStatement ps =
                             con.prepareStatement("SELECT set_config('app.cliente_id', ?, true)")) {
                    ps.setString(1, String.valueOf(principal.getClienteId()));
                    ps.execute();
                }
            }
        } finally {
            DataSourceUtils.releaseConnection(con, pgDataSource);
        }
        return pjp.proceed();
    }
}
