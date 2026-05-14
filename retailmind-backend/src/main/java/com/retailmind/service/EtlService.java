package com.retailmind.service;

import com.retailmind.dto.CargaHistorialDTO;
import com.retailmind.dto.EstadoTablasDTO;
import com.retailmind.dto.EtlResponseDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class EtlService {

    private static final Logger logger = LoggerFactory.getLogger(EtlService.class);

    @Value("${etl.python.path:py}")
    private String pythonPath;

    @Value("${etl.scripts.path}")
    private String scriptsPath;

    private final JdbcTemplate jdbc;
    private static final String CSV_FILENAME = "dataset_upload.csv";

    public EtlService(@Qualifier("jdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── 1. Guardar CSV ────────────────────────────────────────────────────────

    public EtlResponseDTO guardarCsv(MultipartFile file) {
        long inicio = Instant.now().getEpochSecond();
        logger.info("Iniciando subida de CSV: {}", file.getOriginalFilename());
        try {
            Path destino = Paths.get(scriptsPath, CSV_FILENAME);
            Files.copy(file.getInputStream(), destino, StandardCopyOption.REPLACE_EXISTING);
            long dur = Instant.now().getEpochSecond() - inicio;
            logger.info("CSV guardado exitosamente: {} ({} KB)", destino, file.getSize() / 1024);
            return new EtlResponseDTO(true,
                    "CSV guardado correctamente: " + destino,
                    "Archivo: " + file.getOriginalFilename() + " | Tamano: " + (file.getSize() / 1024) + " KB",
                    dur);
        } catch (Exception e) {
            logger.error("Error al guardar CSV: {}", e.getMessage(), e);
            logError("CSV_UPLOAD", e.getMessage(), getStackTrace(e));
            return new EtlResponseDTO(false, "Error al guardar el CSV: " + e.getMessage(), "", 0);
        }
    }

    // ── 2. Cargar CSV a staging ───────────────────────────────────────────────

    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 2000))
    public EtlResponseDTO cargarStaging() {
        logger.info("Ejecutando carga a staging (load_csv_staging.py)");
        return ejecutarScript("etl/load_csv_staging.py", "Carga a dataset_temporal");
    }

    @Recover
    public EtlResponseDTO recoverCargarStaging(Exception e) {
        logger.error("Carga a staging fallo despues de 3 intentos: {}", e.getMessage());
        logError("ETL_STAGING", e.getMessage(), getStackTrace(e));
        return new EtlResponseDTO(false,
                "Carga a staging fallo despues de 3 intentos: " + e.getMessage(), "", 0);
    }

    // ── 3. Ejecutar ETL incremental ───────────────────────────────────────────

    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 2000))
    public EtlResponseDTO ejecutarEtlIncremental() {
        logger.info("Ejecutando ETL incremental (05_load_incremental.py)");
        return ejecutarScript("etl/05_load_incremental.py", "ETL Incremental");
    }

    @Recover
    public EtlResponseDTO recoverEtlIncremental(Exception e) {
        logger.error("ETL incremental fallo despues de 3 intentos: {}", e.getMessage());
        logError("ETL_INCREMENTAL", e.getMessage(), getStackTrace(e));
        return new EtlResponseDTO(false,
                "ETL incremental fallo despues de 3 intentos: " + e.getMessage(), "", 0);
    }

    // ── 4. Ejecutar completo ──────────────────────────────────────────────────

    public EtlResponseDTO ejecutarCompleto() {
        long inicio = Instant.now().getEpochSecond();
        StringBuilder outputTotal = new StringBuilder();
        logger.info("Iniciando proceso ETL completo");

        EtlResponseDTO staging = cargarStaging();
        outputTotal.append("=== PASO 1: Carga a Staging ===\n").append(staging.getOutput()).append("\n");

        if (!staging.isSuccess()) {
            logger.warn("Proceso completo detenido: fallo en staging");
            return new EtlResponseDTO(false,
                    "Fallo en la carga a staging. ETL incremental no ejecutado.",
                    outputTotal.toString(), Instant.now().getEpochSecond() - inicio);
        }

        EtlResponseDTO incremental = ejecutarEtlIncremental();
        outputTotal.append("=== PASO 2: ETL Incremental ===\n").append(incremental.getOutput()).append("\n");

        long dur = Instant.now().getEpochSecond() - inicio;
        boolean ok = incremental.isSuccess();
        logger.info("Proceso completo finalizado: {} ({}s)", ok ? "EXITO" : "ERROR", dur);
        return new EtlResponseDTO(ok,
                ok ? "Proceso completo ejecutado exitosamente." : "Error en ETL incremental.",
                outputTotal.toString(), dur);
    }

    // ── 5. Historial ──────────────────────────────────────────────────────────

    public List<CargaHistorialDTO> getHistorial() {
        try {
            return jdbc.query(
                    "SELECT semana, fecha_carga, registros_procesados, registros_nuevos " +
                    "FROM carga_historial ORDER BY semana DESC",
                    (rs, rowNum) -> new CargaHistorialDTO(
                            rs.getInt("semana"),
                            rs.getString("fecha_carga"),
                            rs.getInt("registros_procesados"),
                            rs.getInt("registros_nuevos")));
        } catch (Exception e) {
            logger.warn("No se pudo obtener historial: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    // ── 6. Estado de tablas ───────────────────────────────────────────────────

    public List<EstadoTablasDTO> getEstadoTablas() {
        String[] tablas = {"regiones", "dispositivos", "canales", "fuentes_trafico",
                "categorias", "usuarios", "productos", "sesiones", "eventos", "conversiones"};
        List<EstadoTablasDTO> resultado = new ArrayList<>();
        for (String tabla : tablas) {
            try {
                Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + tabla, Long.class);
                resultado.add(new EstadoTablasDTO(tabla, count != null ? count : 0L, "-"));
            } catch (Exception e) {
                resultado.add(new EstadoTablasDTO(tabla, -1L, "Error"));
            }
        }
        return resultado;
    }

    // ── Utilidad: ejecutar script Python ─────────────────────────────────────

    private EtlResponseDTO ejecutarScript(String scriptRelativo, String descripcion) {
        long inicio = Instant.now().getEpochSecond();
        StringBuilder output = new StringBuilder();

        try {
            File workDir = new File(scriptsPath);
            String scriptAbs = Paths.get(scriptsPath, scriptRelativo)
                    .toString().replace("/", File.separator);

            ProcessBuilder pb = new ProcessBuilder(pythonPath, scriptAbs);
            pb.directory(workDir);
            pb.redirectErrorStream(true);

            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            long dur = Instant.now().getEpochSecond() - inicio;
            boolean ok = exitCode == 0;

            if (ok) {
                logger.info("{} completado exitosamente ({}s)", descripcion, dur);
            } else {
                logger.error("{} fallo con codigo {} ({}s)", descripcion, exitCode, dur);
                logError("PYTHON_" + descripcion.toUpperCase().replace(" ", "_"),
                        "Exit code: " + exitCode, output.toString());
            }

            return new EtlResponseDTO(ok,
                    ok ? descripcion + " completado exitosamente."
                       : descripcion + " finalizo con errores (codigo " + exitCode + ").",
                    output.toString(), dur);

        } catch (Exception e) {
            long dur = Instant.now().getEpochSecond() - inicio;
            logger.error("Excepcion al ejecutar {}: {}", descripcion, e.getMessage(), e);
            logError("PYTHON_EXEC", e.getMessage(), getStackTrace(e));
            return new EtlResponseDTO(false,
                    "Error al ejecutar " + descripcion + ": " + e.getMessage(),
                    output.toString(), dur);
        }
    }

    // ── Utilidad: guardar error en BD ────────────────────────────────────────

    private void logError(String tipo, String mensaje, String stackTrace) {
        try {
            jdbc.update(
                    "INSERT INTO error_log (tipo_error, mensaje, stack_trace) VALUES (?, ?, ?)",
                    tipo, mensaje, stackTrace != null ? stackTrace.substring(0, Math.min(stackTrace.length(), 4000)) : "");
        } catch (Exception e) {
            logger.warn("No se pudo guardar error en BD: {}", e.getMessage());
        }
    }

    private String getStackTrace(Exception e) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement el : e.getStackTrace()) {
            sb.append(el.toString()).append("\n");
            if (sb.length() > 2000) break;
        }
        return sb.toString();
    }
}
