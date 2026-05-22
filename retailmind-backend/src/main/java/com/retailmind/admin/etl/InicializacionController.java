package com.retailmind.admin.etl;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.retailmind.dto.InicializacionResponseDTO;

@RestController
@RequestMapping("/api/init")
public class InicializacionController {

    private static final Logger logger = LoggerFactory.getLogger(InicializacionController.class);

    @Value("${etl.python.path:python}")
    private String pythonPath;

    @Value("${init.scripts.path}")
    private String scriptsPath;

    /**
     * POST /api/init/extraer-pocketbase
     * Ejecuta etl/08_extract_pocketbase.py
     */
    @PostMapping("/extraer-pocketbase")
    public ResponseEntity<InicializacionResponseDTO> extraerPocketbase() {
        logger.info("Iniciando extraccion desde Pocketbase");
        InicializacionResponseDTO result = ejecutarScript(
                "etl/extraccion/08_extract_pocketbase.py", "Extraccion desde Pocketbase");
        return ResponseEntity.ok(result);
    }

    /**
     * POST /api/init/cargar-clickhouse
     * Ejecuta etl/09_load_clickhouse.py
     */
    @PostMapping("/cargar-clickhouse")
    public ResponseEntity<InicializacionResponseDTO> cargarClickhouse() {
        logger.info("Iniciando carga a ClickHouse");
        InicializacionResponseDTO result = ejecutarScript(
                "etl/carga/09_load_clickhouse.py", "Carga a ClickHouse");
        return ResponseEntity.ok(result);
    }

    /**
     * POST /api/init/verificar-clickhouse
     * Ejecuta etl/10_verify_clickhouse.py
     */
    @PostMapping("/verificar-clickhouse")
    public ResponseEntity<InicializacionResponseDTO> verificarClickhouse() {
        logger.info("Verificando datos en ClickHouse");
        InicializacionResponseDTO result = ejecutarScript(
                "etl/carga/10_verify_clickhouse.py", "Verificacion ClickHouse");
        return ResponseEntity.ok(result);
    }

    /**
     * POST /api/init/generar-semana?semana=N
     * Genera 108,584 registros sintéticos para la semana indicada.
     */
    @PostMapping("/generar-semana")
    public ResponseEntity<InicializacionResponseDTO> generarSemana(
            @org.springframework.web.bind.annotation.RequestParam("semana") int semana) {
        if (semana < 2 || semana > 52) {
            return ResponseEntity.badRequest().body(new InicializacionResponseDTO(
                    false, "La semana debe estar entre 2 y 52.", "", 0, 0));
        }
        logger.info("Generando datos sinteticos para semana {}", semana);
        InicializacionResponseDTO result = ejecutarScript(
                "etl/sinteticos/12_generate_synthetic.py --semana " + semana,
                "Generacion semana " + semana);
        return ResponseEntity.ok(result);
    }

    /**
     * POST /api/init/reset-sistema
     * Ejecuta etl/11_reset_clickhouse.py - Solo ADMIN
     */
    @PostMapping("/reset-sistema")
    public ResponseEntity<InicializacionResponseDTO> resetSistema() {
        logger.warn("RESET DE SISTEMA solicitado");
        InicializacionResponseDTO result = ejecutarScript(
                "etl/carga/11_reset_clickhouse.py", "Reset del sistema");
        if (result.isSuccess()) {
            result.setMensaje("⚠️ Sistema reseteado. Todos los datos de ClickHouse han sido eliminados.");
        }
        return ResponseEntity.ok(result);
    }

    /**
     * POST /api/init/crear-tienda
     * Ejecuta etl/13_create_shop_tables.py - Crea tablas de tienda y pobla catalogo
     */
    @PostMapping("/crear-tienda")
    public ResponseEntity<InicializacionResponseDTO> crearTienda() {
        logger.info("Creando tablas de tienda y poblando catalogo");
        InicializacionResponseDTO result = ejecutarScript(
                "etl/extraccion/13_create_shop_tables.py", "Creacion de tienda");
        return ResponseEntity.ok(result);
    }

    /**
     * POST /api/init/carga-completa
     * Ejecuta los 3 pasos en secuencia:
     * 08_extract_pocketbase → 09_load_clickhouse → 10_verify_clickhouse
     */
    @PostMapping("/carga-completa")
    public ResponseEntity<InicializacionResponseDTO> cargaCompleta() {
        logger.info("Iniciando carga completa (3 pasos)");
        long inicio = Instant.now().getEpochSecond();
        StringBuilder outputTotal = new StringBuilder();
        long registrosTotal = 0;

        // Paso 1: Extraer de Pocketbase
        outputTotal.append("=== PASO 1: Extraccion desde Pocketbase ===\n");
        InicializacionResponseDTO paso1 = ejecutarScript(
                "etl/extraccion/08_extract_pocketbase.py", "Extraccion Pocketbase");
        outputTotal.append(paso1.getOutput()).append("\n");

        if (!paso1.isSuccess()) {
            long dur = Instant.now().getEpochSecond() - inicio;
            return ResponseEntity.ok(new InicializacionResponseDTO(
                    false, "Fallo en Paso 1: Extraccion desde Pocketbase",
                    outputTotal.toString(), dur, 0));
        }

        // Paso 2: Cargar a ClickHouse
        outputTotal.append("=== PASO 2: Carga a ClickHouse ===\n");
        InicializacionResponseDTO paso2 = ejecutarScript(
                "etl/carga/09_load_clickhouse.py", "Carga ClickHouse");
        outputTotal.append(paso2.getOutput()).append("\n");
        registrosTotal = paso2.getRegistrosCargados();

        if (!paso2.isSuccess()) {
            long dur = Instant.now().getEpochSecond() - inicio;
            return ResponseEntity.ok(new InicializacionResponseDTO(
                    false, "Fallo en Paso 2: Carga a ClickHouse",
                    outputTotal.toString(), dur, 0));
        }

        // Paso 3: Verificar
        outputTotal.append("=== PASO 3: Verificacion ===\n");
        InicializacionResponseDTO paso3 = ejecutarScript(
                "etl/carga/10_verify_clickhouse.py", "Verificacion");
        outputTotal.append(paso3.getOutput()).append("\n");

        long dur = Instant.now().getEpochSecond() - inicio;
        boolean ok = paso3.isSuccess();
        logger.info("Carga completa finalizada: {} ({}s)", ok ? "EXITO" : "ERROR", dur);

        return ResponseEntity.ok(new InicializacionResponseDTO(
                ok,
                ok ? "Carga completa ejecutada exitosamente (3/3 pasos)."
                   : "Carga completa finalizo con advertencias en verificacion.",
                outputTotal.toString(), dur, registrosTotal));
    }

    // ── Utilidad: ejecutar script Python ─────────────────────────────────────

    private InicializacionResponseDTO ejecutarScript(String scriptRelativo, String descripcion) {
        long inicio = Instant.now().getEpochSecond();
        StringBuilder output = new StringBuilder();

        try {
            File workDir = new File(scriptsPath);

            // Separar script de argumentos adicionales
            String[] parts = scriptRelativo.split("\\s+");
            String scriptPath = parts[0];
            String scriptAbs = new File(scriptsPath, scriptPath).getAbsolutePath();

            // Construir comando: python script.py [args...]
            java.util.List<String> command = new java.util.ArrayList<>();
            command.add(pythonPath);
            command.add(scriptAbs);
            for (int i = 1; i < parts.length; i++) {
                command.add(parts[i]);
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workDir);
            pb.redirectErrorStream(true);
            pb.environment().put("PYTHONIOENCODING", "utf-8");

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
            }

            // Intentar extraer registros cargados del output
            long registros = extraerRegistros(output.toString());

            // Sanitizar output si contiene errores de tabla inexistente
            String outputFinal = sanitizarOutput(output.toString());

            return new InicializacionResponseDTO(ok,
                    ok ? descripcion + " completado exitosamente."
                       : descripcion + " finalizo con errores (codigo " + exitCode + ").",
                    outputFinal, dur, registros);

        } catch (Exception e) {
            long dur = Instant.now().getEpochSecond() - inicio;
            logger.error("Excepcion al ejecutar {}: {}", descripcion, e.getMessage(), e);
            return new InicializacionResponseDTO(false,
                    "Error al ejecutar " + descripcion + ": " + e.getMessage(),
                    sanitizarOutput(output.toString()), dur, 0);
        }
    }

    /**
     * Reemplaza mensajes técnicos de ClickHouse por mensajes amigables.
     */
    private String sanitizarOutput(String output) {
        if (output.contains("UNKNOWN_TABLE") || output.contains("Unknown table expression identifier")
                || output.contains("Table retailmind.") && output.contains("does not exist")) {
            return "⚠️  Tabla no encontrada - El sistema está vacío.\n" +
                   "Ejecute CARGA COMPLETA DESDE POCKETBASE para inicializar.\n";
        }
        return output;
    }

    /**
     * Intenta extraer el número de registros del output del script.
     */
    private long extraerRegistros(String output) {
        try {
            // Buscar patrones como "Total registros: 108,584" o "108,584 registros"
            for (String line : output.split("\n")) {
                if (line.contains("Total registros:") || line.contains("registros en fact_eventos")) {
                    String nums = line.replaceAll("[^0-9]", "");
                    if (!nums.isEmpty()) {
                        return Long.parseLong(nums);
                    }
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }
}
