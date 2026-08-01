package com.retailmind.dwh;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * DISPARO Y SEGUIMIENTO de la actualización del data warehouse.
 *
 * Lanza {@code python -m etl.dwh.run_etl} —el orquestador de las 19 cargas— en
 * un proceso aparte y de forma ASÍNCRONA, y lee su progreso de la bitácora
 * {@code retailmind_dwh.etl_ejecucion} que ese mismo orquestador escribe.
 *
 * <h2>Por qué un proceso y no una reimplementación en Java</h2>
 *
 * El pipeline ya existe, está validado al centavo por 44 controles y es la
 * pieza que el diseño (§7.4) quiso AUTÓNOMA justamente para que la invoque
 * quien sea: una persona en la terminal, este endpoint, o mañana un
 * {@code BashOperator} de Airflow. Reescribir aquí el orden topológico
 * duplicaría la lógica y la haría divergir. Este servicio no sabe nada de
 * tablas ni de dependencias: solo lanza, vigila y reporta.
 *
 * <h2>El identificador de corrida lo pone Java, no Python</h2>
 *
 * {@link #disparar} genera el UUID y se lo IMPONE al orquestador con
 * {@code --corrida-id}. Así el endpoint puede devolverlo en el acto —antes de
 * que el proceso haya escrito nada— y el frontend tiene desde el primer
 * instante una clave con la que preguntar. La alternativa (dejar que Python lo
 * genere y pescarlo de su stdout) obligaría a esperar y a parsear texto.
 *
 * <h2>Una corrida a la vez</h2>
 *
 * Dos capas, porque protegen de cosas distintas:
 * <ol>
 *   <li>{@link #activa}, en memoria y con {@code compareAndSet}: cierra la
 *       ventana entre dos peticiones HTTP simultáneas, que una comprobación
 *       «consulto y luego lanzo» dejaría abierta.</li>
 *   <li>La BITÁCORA: detecta la corrida que lanzó otra cosa —la terminal, o
 *       este mismo backend antes de reiniciarse— y de la que la memoria no sabe
 *       nada. Con un límite de antigüedad, porque un proceso muerto a lo bruto
 *       deja su marcador de apertura sin cerrar y bloquearía el botón para
 *       siempre.</li>
 * </ol>
 */
@Service
public class DwhActualizacionService {

    private static final Logger logger = LoggerFactory.getLogger(DwhActualizacionService.class);

    /** Base del almacén. La base {@code retailmind} legada no se toca. */
    private static final String DWH = "retailmind_dwh";
    private static final String BITACORA = DWH + ".etl_ejecucion";

    /** Nombres reservados que el orquestador usa para marcar la corrida. */
    private static final String TAREA_CORRIDA = "corrida";
    private static final String TAREA_VALIDACION = "validar_dwh";
    private static final String EN_CURSO = "en_curso";
    private static final String EXITO = "exito";

    /**
     * Pasado este tiempo, una corrida «en curso» se considera ABANDONADA y deja
     * de bloquear. Una corrida completa tarda ~20 s; el margen es enorme a
     * propósito, porque el precio de equivocarse es asimétrico: dar por muerta
     * una corrida viva lanzaría dos a la vez, y dar por viva una muerta solo
     * retrasa el botón.
     */
    private static final long MINUTOS_ABANDONO = 30;

    /** Tope de la espera del proceso. Más allá, se mata y se reporta. */
    private static final long MINUTOS_TIMEOUT = 30;

    /** Cuántas líneas de la salida del orquestador se conservan en el log. */
    private static final int MAX_LINEAS_LOG = 400;

    private final JdbcTemplate ch;
    private final String rutaEtl;
    private final String python;

    /**
     * Un solo hilo: dos corridas simultáneas no tienen sentido y el ejecutor
     * las serializaría de todos modos. El guardia explícito existe para poder
     * DECIRLO con un 409 en vez de encolar en silencio una segunda corrida que
     * el usuario creería inmediata.
     */
    private final ExecutorService ejecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dwh-run-etl");
        t.setDaemon(true);
        return t;
    });

    /** UUID de la corrida lanzada por ESTE backend y aún viva; null si no hay. */
    private final AtomicReference<UUID> activa = new AtomicReference<>();

    public DwhActualizacionService(
            @Qualifier("clickHouseJdbc") JdbcTemplate ch,
            @Value("${dwh.etl.path}") String rutaEtl,
            @Value("${dwh.python.path}") String python) {
        this.ch = ch;
        this.rutaEtl = rutaEtl;
        this.python = python;
    }

    // ── Disparo ──────────────────────────────────────────────────────────

    /** La corrida ya estaba en marcha. Se traduce a 409 por el handler global. */
    public static class CorridaEnCurso extends IllegalStateException {
        public CorridaEnCurso(String mensaje) {
            super(mensaje);
        }
    }

    /**
     * Lanza la actualización y vuelve EN EL ACTO con el identificador.
     *
     * @param origen quién dispara: el correo del usuario, o «programación
     *               automática». Queda en el log, no en la bitácora — el grano
     *               de {@code etl_ejecucion} es la tarea, y añadirle una
     *               columna de autor obligaría a migrar la tabla.
     * @throws CorridaEnCurso si ya hay una ejecución viva.
     */
    public Map<String, Object> disparar(String origen) {
        UUID corrida = UUID.randomUUID();

        // Capa 1 — atómica: solo un hilo consigue pasar de null a `corrida`.
        if (!activa.compareAndSet(null, corrida)) {
            throw new CorridaEnCurso(
                    "Ya hay una actualización del almacén en curso (corrida "
                    + activa.get() + "). Espera a que termine antes de lanzar otra.");
        }

        // Capa 2 — la bitácora: una corrida que este backend no lanzó.
        try {
            UUID ajena = corridaVivaEnBitacora();
            if (ajena != null) {
                activa.set(null);
                throw new CorridaEnCurso(
                        "Ya hay una actualización del almacén en curso (corrida " + ajena
                        + "), lanzada fuera de esta sesión. Espera a que termine.");
            }
        } catch (DataAccessException e) {
            // Sin bitácora legible no se puede comprobar la capa 2. Se sigue
            // con la capa 1, que es la que cubre el caso frecuente: si
            // ClickHouse está caído, el orquestador fallará y lo reportará.
            logger.warn("No se pudo consultar la bitácora antes de disparar; "
                    + "se continúa solo con el guardia en memoria ({})", e.getMessage());
        }

        logger.info("Actualización del DWH solicitada por {} — corrida {}", origen, corrida);
        ejecutor.submit(() -> {
            try {
                ejecutarOrquestador(corrida, origen);
            } finally {
                activa.set(null);
            }
        });

        Map<String, Object> res = new HashMap<>();
        res.put("corridaId", corrida.toString());
        res.put("enCurso", true);
        res.put("mensaje", "Actualización del almacén iniciada. "
                + "Consulta el progreso en /api/dwh/estado.");
        return res;
    }

    /**
     * Ejecuta el orquestador y espera a que termine. Corre en el hilo del
     * ejecutor, nunca en el de la petición HTTP.
     */
    private void ejecutarOrquestador(UUID corrida, String origen) {
        long t0 = System.currentTimeMillis();
        List<String> comando = List.of(python, "-m", "etl.dwh.run_etl",
                "--corrida-id", corrida.toString());
        StringBuilder salida = new StringBuilder();

        try {
            ProcessBuilder pb = new ProcessBuilder(comando);
            pb.directory(new File(rutaEtl));
            pb.redirectErrorStream(true);
            // El orquestador imprime acentos y cajas; sin forzar UTF-8 la
            // captura llega corrompida en una consola Windows en cp1252.
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            // `-m etl.dwh.run_etl` necesita que el paquete `etl` sea importable
            // desde el directorio de trabajo.
            pb.environment().put("PYTHONPATH", ".");

            Process proceso = pb.start();
            int lineas = 0;
            try (BufferedReader lector = new BufferedReader(
                    new InputStreamReader(proceso.getInputStream(), StandardCharsets.UTF_8))) {
                String linea;
                while ((linea = lector.readLine()) != null) {
                    if (lineas++ < MAX_LINEAS_LOG) {
                        salida.append(linea).append('\n');
                    }
                }
            }

            if (!proceso.waitFor(MINUTOS_TIMEOUT, TimeUnit.MINUTES)) {
                proceso.destroyForcibly();
                logger.error("Corrida {} superó los {} minutos y se abortó. "
                        + "Las tablas ya publicadas conservan su dato.",
                        corrida, MINUTOS_TIMEOUT);
                return;
            }

            int codigo = proceso.exitValue();
            long seg = (System.currentTimeMillis() - t0) / 1000;
            // Los códigos los define run_etl: 0 correcto · 1 no arrancó ·
            // 2 fallo parcial · 3 cargó pero los controles no cuadran.
            if (codigo == 0) {
                logger.info("Corrida {} ({}) COMPLETA y validada en {} s", corrida, origen, seg);
            } else {
                logger.error("Corrida {} ({}) terminó con código {} en {} s. Salida:\n{}",
                        corrida, origen, codigo, seg, salida);
            }

        } catch (java.io.IOException e) {
            logger.error("No se pudo lanzar el orquestador ({} en {}): {}. "
                    + "Revisa las propiedades dwh.python.path y dwh.etl.path.",
                    python, rutaEtl, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Corrida {} interrumpida", corrida);
        }
    }

    // ── Estado ───────────────────────────────────────────────────────────

    /**
     * Parte de la ÚLTIMA corrida orquestada: progreso si sigue viva, resultado
     * si terminó.
     *
     * Se reconstruye leyendo la bitácora y no de una variable en memoria: así
     * el estado sobrevive a un reinicio del backend y refleja también las
     * corridas lanzadas desde la terminal o por la programación automática.
     *
     * Degrada como los informes compuestos: con ClickHouse apagado responde
     * {@code analiticaDisponible=false} en HTTP 200, nunca un 500.
     */
    public Map<String, Object> estado() {
        Map<String, Object> res = new HashMap<>();
        try {
            // La corrida que este backend acaba de lanzar MANDA sobre la última
            // de la bitácora. Entre el 202 y la primera fila que escribe Python
            // pasa cerca de un segundo, y en esa ventana `ultimaCorrida()`
            // devuelve la corrida ANTERIOR —ya cerrada— y el estado diría
            // «no hay nada en curso» justo después de haber lanzado algo. Ahí se
            // colaba una segunda petición que el guardia rechazaba con un 409
            // desconcertante: la pantalla decía que no había corrida y el
            // backend contestaba que sí.
            UUID corrida = activa.get();
            if (corrida == null) {
                corrida = ultimaCorrida();
            }
            if (corrida == null) {
                res.put("analiticaDisponible", true);
                res.put("hayCorridas", false);
                res.put("enCurso", false);
                res.put("mensaje", "El almacén todavía no se ha actualizado desde la aplicación.");
                res.put("datosAl", marcaDeAgua());
                return res;
            }
            return detalle(corrida);

        } catch (DataAccessException e) {
            logger.warn("Estado del DWH no disponible: ClickHouse no responde ({})",
                    e.getMostSpecificCause().getMessage());
            res.put("analiticaDisponible", false);
            res.put("enCurso", false);
            res.put("hayCorridas", false);
            res.put("mensaje", "El almacén de datos (ClickHouse) no responde, así que no se "
                    + "puede consultar el estado de la última actualización. El resto del "
                    + "sistema funciona con normalidad.");
            return res;
        }
    }

    /** UUID de la corrida orquestada más reciente, o null si no hay ninguna. */
    private UUID ultimaCorrida() {
        List<Map<String, Object>> filas = ch.queryForList(
                "SELECT toString(corrida_id) AS id FROM " + BITACORA
                + " WHERE tarea = ? ORDER BY inicio DESC LIMIT 1", TAREA_CORRIDA);
        return filas.isEmpty() ? null : UUID.fromString(String.valueOf(filas.get(0).get("id")));
    }

    /**
     * Corrida viva según la bitácora: tiene marcador de apertura, no tiene
     * marcador de cierre y no ha sobrepasado el límite de abandono.
     */
    private UUID corridaVivaEnBitacora() {
        List<Map<String, Object>> filas = ch.queryForList(
                "SELECT toString(corrida_id) AS id, max(inicio) AS abierta, "
                + "       countIf(resultado <> ?) AS cierres "
                + "FROM " + BITACORA + " WHERE tarea = ? "
                + "GROUP BY corrida_id HAVING cierres = 0 "
                + "ORDER BY abierta DESC LIMIT 1", EN_CURSO, TAREA_CORRIDA);
        if (filas.isEmpty()) {
            return null;
        }
        Object abierta = filas.get(0).get("abierta");
        if (abierta instanceof LocalDateTime inicio
                && inicio.isBefore(LocalDateTime.now().minus(MINUTOS_ABANDONO, ChronoUnit.MINUTES))) {
            logger.warn("La corrida {} lleva abierta más de {} min sin cerrarse: "
                    + "se considera abandonada y deja de bloquear.",
                    filas.get(0).get("id"), MINUTOS_ABANDONO);
            return null;
        }
        return UUID.fromString(String.valueOf(filas.get(0).get("id")));
    }

    /** Reconstruye el parte completo de una corrida a partir de sus filas. */
    private Map<String, Object> detalle(UUID corrida) {
        List<Map<String, Object>> filas = ch.queryForList(
                "SELECT tarea, resultado, filas_escritas, filas_leidas, duracion_seg, mensaje, "
                + "       formatDateTime(inicio, '%d/%m/%Y %H:%i') AS inicio_txt, "
                + "       formatDateTime(fin, '%d/%m/%Y %H:%i') AS fin_txt, "
                + "       inicio "
                + "FROM " + BITACORA + " WHERE corrida_id = toUUID(?) "
                + "ORDER BY inicio, tarea", corrida.toString());

        Map<String, Object> apertura = null;
        Map<String, Object> cierre = null;
        Map<String, Object> validacion = null;
        List<Map<String, Object>> tablas = new ArrayList<>();

        for (Map<String, Object> f : filas) {
            String tarea = String.valueOf(f.get("tarea"));
            String resultado = String.valueOf(f.get("resultado"));
            if (TAREA_CORRIDA.equals(tarea)) {
                if (EN_CURSO.equals(resultado)) {
                    apertura = f;
                } else {
                    cierre = f;
                }
            } else if (TAREA_VALIDACION.equals(tarea)) {
                validacion = f;
            } else {
                tablas.add(f);
            }
        }

        // Viva si la bitácora la muestra abierta, o si es la que este backend
        // está ejecutando ahora mismo (todavía sin marcador de apertura).
        boolean enCurso = (apertura != null && cierre == null) || corrida.equals(activa.get());
        long completadas = tablas.stream()
                .filter(f -> EXITO.equals(String.valueOf(f.get("resultado")))).count();
        long filasCargadas = tablas.stream()
                .filter(f -> EXITO.equals(String.valueOf(f.get("resultado"))))
                .mapToLong(f -> numero(f.get("filas_escritas"))).sum();

        // El total de tareas lo declara el propio orquestador al abrir; así no
        // hay que mantener aquí una constante «19» que caducaría con la primera
        // tabla nueva del modelo.
        long totales = apertura != null ? numero(apertura.get("filas_leidas")) : tablas.size();

        List<Map<String, Object>> errores = new ArrayList<>();
        for (Map<String, Object> f : tablas) {
            if (!EXITO.equals(String.valueOf(f.get("resultado")))) {
                errores.add(Map.of(
                        "tarea", f.get("tarea"),
                        "resultado", f.get("resultado"),
                        "mensaje", String.valueOf(f.getOrDefault("mensaje", ""))));
            }
        }

        Map<String, Object> res = new HashMap<>();
        res.put("analiticaDisponible", true);
        res.put("hayCorridas", true);
        res.put("corridaId", corrida.toString());
        res.put("enCurso", enCurso);
        res.put("tareasTotales", totales);
        res.put("tareasCompletadas", completadas);
        res.put("filas", filasCargadas);
        res.put("errores", errores);
        res.put("tareas", tablas.stream().map(f -> Map.of(
                "tarea", f.get("tarea"),
                "resultado", f.get("resultado"),
                "filas", numero(f.get("filas_escritas")),
                "segundos", f.getOrDefault("duracion_seg", 0))).toList());

        if (apertura != null) {
            res.put("inicio", apertura.get("inicio_txt"));
        }
        if (enCurso) {
            res.put("resultado", EN_CURSO);
            res.put("mensaje", totales == 0
                    ? "Actualización iniciada: preparando las cargas."
                    : "Actualización en curso: " + completadas + " de " + totales
                      + " tablas publicadas.");
        } else if (cierre != null) {
            String resultado = String.valueOf(cierre.get("resultado"));
            res.put("resultado", resultado);
            res.put("fin", cierre.get("fin_txt"));
            res.put("duracionSeg", cierre.get("duracion_seg"));
            res.put("mensaje", String.valueOf(cierre.getOrDefault("mensaje", "")));
            res.put("exito", EXITO.equals(resultado));
        }
        if (validacion != null) {
            res.put("validacion", validacion.get("resultado"));
            res.put("validacionMensaje", validacion.get("mensaje"));
            res.put("controles", numero(validacion.get("filas_leidas")));
        }
        res.put("datosAl", marcaDeAgua());
        return res;
    }

    /**
     * La MISMA marca de agua que enseñan los informes compuestos: el
     * {@code max(fecha_carga)} de una tabla publicada, no la hora en que
     * terminó el proceso. Se lee de {@code fact_pedido} porque es la tabla
     * central del modelo y la que más informes sirven; si la corrida abortó esa
     * carga, la marca sigue siendo la de la corrida anterior — que es
     * exactamente lo que los informes están mostrando.
     */
    private String marcaDeAgua() {
        try {
            return ch.queryForObject(
                    "SELECT formatDateTime(max(fecha_carga), '%d/%m/%Y %H:%i') FROM "
                    + DWH + ".fact_pedido", String.class);
        } catch (DataAccessException e) {
            return null;
        }
    }

    private static long numero(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }
}
