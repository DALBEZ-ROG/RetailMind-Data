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

    public HealthCheckService(@Qualifier("jdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, String> checkAll() {
        Map<String, String> status = new LinkedHashMap<>();
        status.put("database", checkDatabase());
        status.put("python", checkPythonRuntime());

        boolean allUp = status.values().stream().allMatch("UP"::equals);
        status.put("status", allUp ? "UP" : "DOWN");
        return status;
    }

    public String checkDatabase() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return "UP";
        } catch (Exception e) {
            logger.warn("Health check BD fallo: {}", e.getMessage());
            return "DOWN";
        }
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
