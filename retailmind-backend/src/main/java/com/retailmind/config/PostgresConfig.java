package com.retailmind.config;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Datasource PostgreSQL (retailmind): TODO lo transaccional/operativo,
 * incluida la autenticación. Convive con ClickHouseConfig (solo analytics).
 *
 * La app se conecta como retailmind_app (LOGIN NOINHERIT, miembro de los 8
 * grp_*): sin SET LOCAL ROLE la conexión no tiene privilegios de negocio.
 * El rol se asume por transacción vía {@link com.retailmind.security.PgSessionRoleAspect}.
 *
 * order = 0 en la transacción garantiza que el advisor @Transactional sea el
 * MÁS EXTERNO, y el aspecto de rol (Order(10)) corra ya DENTRO de la tx.
 */
@Configuration
@EnableTransactionManagement(order = 0)
public class PostgresConfig {

    @Bean(name = "pgDataSource")
    public DataSource pgDataSource(
            @Value("${postgres.datasource.url}") String url,
            @Value("${postgres.datasource.username}") String username,
            @Value("${postgres.datasource.password}") String password) {

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName("org.postgresql.Driver");
        config.setPoolName("pg-retailmind");
        config.setMaximumPoolSize(10);
        return new HikariDataSource(config);
    }

    @Bean(name = "pgJdbcTemplate")
    public JdbcTemplate pgJdbcTemplate(@Qualifier("pgDataSource") DataSource pgDataSource) {
        return new JdbcTemplate(pgDataSource);
    }

    @Primary
    @Bean(name = "transactionManager")
    public DataSourceTransactionManager pgTransactionManager(
            @Qualifier("pgDataSource") DataSource pgDataSource) {
        return new DataSourceTransactionManager(pgDataSource);
    }
}
