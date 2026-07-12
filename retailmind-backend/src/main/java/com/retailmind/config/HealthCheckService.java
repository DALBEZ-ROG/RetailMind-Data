package com.retailmind.config;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class HealthCheckService {

    private static final Logger logger = LoggerFactory.getLogger(HealthCheckService.class);

    @Value("${etl.python.path:python}")
    private String pythonPath;

    private final JdbcTemplate jdbc;
    private final JdbcTemplate pgJdbc;

    public HealthCheckService(@Qualifier("jdbcTemplate") JdbcTemplate jdbc,
                              @Qualifier("pgJdbcTemplate") JdbcTemplate pgJdbc) {
        this.jdbc = jdbc;
        this.pgJdbc = pgJdbc;
    }

    public Map<String, String> checkAll() {
        Map<String, String> status = new LinkedHashMap<>();
        status.put("postgres", checkPostgres());
        status.put("clickhouse", checkClickHouse());
        status.put("database", status.get("clickhouse")); // compatibilidad con clientes previos
        status.put("python", checkPythonRuntime());

        // El sistema operativo vive en PostgreSQL: ClickHouse apagado NO tumba
        // el estado global, solo degrada la analítica (y python es solo ETL).
        boolean operativo = !"DOWN".equals(status.get("postgres"));
        status.put("status", operativo ? "UP" : "DOWN");
        status.put("analytics", "DOWN".equals(status.get("clickhouse")) ? "DEGRADED" : "UP");
        return status;
    }

    /** PostgreSQL transaccional, conectado como retailmind_app. */
    public String checkPostgres() {
        try {
            String user = pgJdbc.queryForObject("SELECT current_user", String.class);
            return "UP (" + user + ")";
        } catch (Exception e) {
            logger.warn("Health check PostgreSQL fallo: {}", e.getMessage());
            return "DOWN";
        }
    }

    /** ClickHouse (solo analytics). */
    public String checkClickHouse() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return "UP";
        } catch (Exception e) {
            logger.warn("Health check ClickHouse fallo: {}", e.getMessage());
            return "DOWN";
        }
    }

    /** @deprecated conservado por compatibilidad; ahora es ClickHouse. */
    @Deprecated
    public String checkDatabase() {
        return checkClickHouse();
    }

    public String checkPythonRuntime() {
        try {
            ProcessBuilder pb = new ProcessBuilder(pythonPath, "--version");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                reader.readLine(); // Python 3.x.x
            }

            int exitCode = process.waitFor();
            return exitCode == 0 ? "UP" : "DOWN";
        } catch (Exception e) {
            logger.warn("Health check Python fallo: {}", e.getMessage());
            return "DOWN";
        }
    }
}
