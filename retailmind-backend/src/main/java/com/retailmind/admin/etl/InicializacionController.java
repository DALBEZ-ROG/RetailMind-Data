package com.retailmind.admin.etl;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.retailmind.dto.InicializacionResponseDTO;

/**
 * Administracion de la capa analitica LEGADA (base `retailmind` de ClickHouse).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * NO REINTRODUCIR: aqui vivian TRES endpoints que se suprimieron el 2026-08-08
 * porque destruian datos IRREPRODUCIBLES.
 *
 *   · POST /reset-sistema      → `etl/carga/11_reset_clickhouse.py`
 *         Hacia `DROP TABLE IF EXISTS` sobre `fact_eventos` y las 7
 *         dimensiones del nucleo. Detras de un dialogo que solo pedia teclear
 *         «CONFIRMAR».
 *   · POST /cargar-clickhouse  → `etl/carga/09_load_clickhouse.py`
 *         Hace `TRUNCATE TABLE` de cada dimension y las repuebla desde
 *         `data/stage/datos.parquet`, un volcado de mayo de 2026. Sobre la
 *         base de hoy eso no es «cargar»: es sustituir.
 *   · POST /extraer-pocketbase → `etl/extraccion/08_extract_pocketbase.py`
 *         Primer paso de esa misma cadena. Exige un PocketBase en
 *         `host.docker.internal:8090` que ya no existe (el servicio se
 *         elimino del compose), asi que no puede completarse de ningun modo.
 *   · POST /carga-completa     → los tres anteriores en secuencia.
 *
 * MOTIVO: `fact_eventos` tiene 2.823.245 filas acumuladas durante un semestre
 * a ~108.584 por semana. No se pueden volver a generar: el 96,2 % lo produjo
 * un script con semilla no fijada. Por eso su volumen va declarado
 * `external: true` en el compose y ningun `down` puede llevar `-v`.
 *
 * Hasta hoy los cuatro fallaban por un defecto de configuracion
 * (`INIT_SCRIPTS_PATH` sin definir hacia que `init.scripts.path` cayera en
 * `/app`, donde no hay scripts). Es decir: lo unico que protegia el dato era
 * un error. Al corregir esa variable los botones habrian quedado ARMADOS, asi
 * que se retiran ANTES.
 *
 * Los scripts NO se borraron del repositorio —siguen en `retailmind/etl/`
 * como historial del pipeline PocketBase → Parquet → ClickHouse—; lo que
 * desaparece es la via para dispararlos desde la interfaz.
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Lo que queda es de SOLO LECTURA, salvo `/generar-semana`, que unicamente
 * INSERTA en una semana vacia y aborta si la semana ya tiene una sola fila.
 */
@RestController
@RequestMapping("/api/init")
public class InicializacionController {

    private static final Logger logger = LoggerFactory.getLogger(InicializacionController.class);

    @Value("${etl.python.path:python}")
    private String pythonPath;

    @Value("${init.scripts.path}")
    private String scriptsPath;

    private final EstadoLegadoService estadoService;

    public InicializacionController(EstadoLegadoService estadoService) {
        this.estadoService = estadoService;
    }

    // ── Estado: se CONSULTA la base, no se deduce de un proceso ──────────────

    /**
     * GET /api/init/estado
     * Estado real de la capa legada: conexion a ClickHouse, filas de
     * `fact_eventos`, conteo de las 13 tablas auxiliares y existencia del
     * parquet en disco. Cada indicador de la pantalla sale de aqui.
     */
    @GetMapping("/estado")
    public ResponseEntity<Map<String, Object>> estado() {
        return ResponseEntity.ok(estadoService.estado());
    }

    /**
     * GET /api/init/semanas
     * Semanas con su conteo real (`GROUP BY semana`), cuantos eventos de la
     * tienda viva contiene cada una y cuales quedan libres para el generador.
     */
    @GetMapping("/semanas")
    public ResponseEntity<Map<String, Object>> semanas() {
        return ResponseEntity.ok(estadoService.semanas());
    }

    // ── Diagnostico de solo lectura ──────────────────────────────────────────

    /**
     * POST /api/init/verificar-clickhouse
     * Ejecuta `etl/carga/10_verify_clickhouse.py`, que solo hace SELECT y
     * vuelca conteos por tabla. Se conserva porque es el unico punto donde se
     * ve la salida cruda del pipeline antiguo; los INDICADORES ya no dependen
     * de el (los sirve `/estado`).
     */
    @PostMapping("/verificar-clickhouse")
    public ResponseEntity<InicializacionResponseDTO> verificarClickhouse() {
        logger.info("Verificando datos en ClickHouse (solo lectura)");
        InicializacionResponseDTO result = ejecutarScript(
                "etl/carga/10_verify_clickhouse.py", "Verificacion ClickHouse");
        return ResponseEntity.ok(result);
    }

    // ── Generacion de semanas sinteticas ─────────────────────────────────────

    /**
     * POST /api/init/generar-semana?semana=N
     * Genera 108.584 registros sinteticos para la semana indicada.
     *
     * El script solo INSERTA, y aborta por su cuenta si la semana ya tiene
     * filas (`verificar_semana_existe`, con `count() > 0`). Ese guardia se
     * deja intacto: es conservador y eso es lo correcto. Aqui se le añade una
     * comprobacion PREVIA que rechaza ademas las semanas que contienen eventos
     * de la TIENDA REAL, para dar el motivo exacto antes de arrancar Python.
     */
    @PostMapping("/generar-semana")
    public ResponseEntity<InicializacionResponseDTO> generarSemana(
            @org.springframework.web.bind.annotation.RequestParam("semana") int semana) {
        if (semana < 2 || semana > 52) {
            return ResponseEntity.badRequest().body(new InicializacionResponseDTO(
                    false, "La semana debe estar entre 2 y 52.", "", 0, 0));
        }

        // Rechazo temprano y explicito. `EventoTiendaService` escribe los
        // eventos de la tienda con la semana ISO de hoy, asi que una semana
        // puede estar ocupada por 19 filas reales —ya paso con la 27— y
        // generar 108.584 sinteticos encima las volveria indistinguibles.
        Map<String, Object> est = estadoService.semanas();
        if (Boolean.TRUE.equals(est.get("disponible"))) {
            @SuppressWarnings("unchecked")
            var filas = (java.util.List<Map<String, Object>>) est.get("semanas");
            for (Map<String, Object> f : filas) {
                if ((Integer) f.get("semana") == semana) {
                    return ResponseEntity.badRequest().body(new InicializacionResponseDTO(
                            false,
                            "La semana " + semana + " no esta libre. " + f.get("motivo") + ".",
                            "", 0, 0));
                }
            }
        }

        logger.info("Generando datos sinteticos para semana {}", semana);
        InicializacionResponseDTO result = ejecutarScript(
                "etl/sinteticos/12_generate_synthetic.py --semana " + semana,
                "Generacion semana " + semana);
        return ResponseEntity.ok(result);
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

            return new InicializacionResponseDTO(ok,
                    ok ? descripcion + " completado exitosamente."
                       : descripcion + " finalizo con errores (codigo " + exitCode + ").",
                    output.toString(), dur, registros);

        } catch (Exception e) {
            long dur = Instant.now().getEpochSecond() - inicio;
            logger.error("Excepcion al ejecutar {}: {}", descripcion, e.getMessage(), e);
            return new InicializacionResponseDTO(false,
                    "Error al ejecutar " + descripcion + ": " + e.getMessage(),
                    output.toString(), dur, 0);
        }
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
