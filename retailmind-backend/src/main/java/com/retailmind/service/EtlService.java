package com.retailmind.service;

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

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.retailmind.dto.CargaHistorialDTO;
import com.retailmind.dto.EstadoTablasDTO;
import com.retailmind.dto.EtlResponseDTO;

@Service
public class EtlService {

    @Value("${etl.python.path:py}")
    private String pythonPath;

    @Value("${etl.scripts.path}")
    private String scriptsPath;

    private final JdbcTemplate jdbc;

    // Nombre fijo del CSV guardado en la carpeta del proyecto
    private static final String CSV_FILENAME = "dataset_upload.csv";

    public EtlService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── 1. Guardar CSV ────────────────────────────────────────────────────────

    public EtlResponseDTO guardarCsv(MultipartFile file) {
        long inicio = Instant.now().getEpochSecond();
        try {
            Path destino = Paths.get(scriptsPath, CSV_FILENAME);
            Files.copy(file.getInputStream(), destino, StandardCopyOption.REPLACE_EXISTING);

            long dur = Instant.now().getEpochSecond() - inicio;
            return new EtlResponseDTO(
                    true,
                    "CSV guardado correctamente: " + destino,
                    "Archivo: " + file.getOriginalFilename()
                            + " | Tamano: " + (file.getSize() / 1024) + " KB",
                    dur
            );
        } catch (Exception e) {
            return new EtlResponseDTO(false, "Error al guardar el CSV: " + e.getMessage(), "", 0);
        }
    }

    // ── 2. Cargar CSV a dataset_temporal ──────────────────────────────────────

    public EtlResponseDTO cargarStaging() {
        // Script Python que carga el CSV a dataset_temporal
        // Se asume que existe un script load_csv_to_staging.py en la carpeta etl/
        // Si no existe, se puede crear inline o usar el script 03 con modificacion.
        // Por defecto ejecutamos el script de carga completa (pasos 01-03).
        return ejecutarScript("etl/load_csv_staging.py", "Carga a dataset_temporal");
    }

    // ── 3. Ejecutar ETL incremental ───────────────────────────────────────────

    public EtlResponseDTO ejecutarEtlIncremental() {
        return ejecutarScript("etl/05_load_incremental.py", "ETL Incremental");
    }

    // ── 4. Ejecutar completo (staging + incremental) ──────────────────────────

    public EtlResponseDTO ejecutarCompleto() {
        long inicio = Instant.now().getEpochSecond();
        StringBuilder outputTotal = new StringBuilder();

        EtlResponseDTO staging = cargarStaging();
        outputTotal.append("=== PASO 1: Carga a Staging ===\n")
                   .append(staging.getOutput()).append("\n");

        if (!staging.isSuccess()) {
            return new EtlResponseDTO(false,
                    "Fallo en la carga a staging. ETL incremental no ejecutado.",
                    outputTotal.toString(),
                    Instant.now().getEpochSecond() - inicio);
        }

        EtlResponseDTO incremental = ejecutarEtlIncremental();
        outputTotal.append("=== PASO 2: ETL Incremental ===\n")
                   .append(incremental.getOutput()).append("\n");

        long dur = Instant.now().getEpochSecond() - inicio;
        boolean ok = incremental.isSuccess();
        return new EtlResponseDTO(
                ok,
                ok ? "Proceso completo ejecutado exitosamente." : "Error en ETL incremental.",
                outputTotal.toString(),
                dur
        );
    }

    // ── 5. Historial de cargas ────────────────────────────────────────────────

    public List<CargaHistorialDTO> getHistorial() {
        String sql = "SELECT semana, fecha_carga, registros_procesados, registros_nuevos " +
                     "FROM carga_historial ORDER BY semana DESC";
        try {
            return jdbc.query(sql, (rs, rowNum) -> new CargaHistorialDTO(
                    rs.getInt("semana"),
                    rs.getString("fecha_carga"),
                    rs.getInt("registros_procesados"),
                    rs.getInt("registros_nuevos")
            ));
        } catch (Exception e) {
            // La tabla puede no existir aun si no se ha ejecutado el ETL
            return new ArrayList<>();
        }
    }

    // ── 6. Estado de tablas ───────────────────────────────────────────────────

    public List<EstadoTablasDTO> getEstadoTablas() {
        String[] tablas = {
            "regiones", "dispositivos", "canales", "fuentes_trafico",
            "categorias", "usuarios", "productos", "sesiones", "eventos", "conversiones"
        };
        List<EstadoTablasDTO> resultado = new ArrayList<>();
        for (String tabla : tablas) {
            try {
                Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + tabla, Long.class);
                resultado.add(new EstadoTablasDTO(tabla, count != null ? count : 0L, "—"));
            } catch (Exception e) {
                resultado.add(new EstadoTablasDTO(tabla, -1L, "Error: " + e.getMessage()));
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
            pb.redirectErrorStream(true); // stderr -> stdout

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

            return new EtlResponseDTO(
                    ok,
                    ok ? descripcion + " completado exitosamente."
                       : descripcion + " finalizo con errores (codigo " + exitCode + ").",
                    output.toString(),
                    dur
            );

        } catch (Exception e) {
            long dur = Instant.now().getEpochSecond() - inicio;
            return new EtlResponseDTO(
                    false,
                    "Error al ejecutar " + descripcion + ": " + e.getMessage(),
                    output.toString(),
                    dur
            );
        }
    }
}
