package com.retailmind.dwh;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
    /** El mismo literal que escribe el orquestador al cerrar en rojo. */
    private static final String RESULTADO_FALLO_PARCIAL = "fallo_parcial";

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
     * Corrida viva según la bitácora: quedan marcadores de apertura SIN su
     * cierre correspondiente, y no ha sobrepasado el límite de abandono.
     *
     * <h2>Por qué se comparan aperturas contra cierres y no se exige «cero
     * cierres»</h2>
     *
     * Este método compartía con {@link #detalle} el supuesto de que una corrida
     * deja UN marcador de apertura y UN cierre. Es cierto cuando la dispara el
     * botón —un solo proceso {@code run_etl}— y FALSO cuando la orquesta
     * Airflow: ahí cada tarea del DAG es un proceso independiente y escribe su
     * propio par, de modo que una corrida de 22 tareas deja 22 aperturas y 22
     * cierres bajo el MISMO {@code corrida_id}.
     *
     * Con la condición anterior ({@code cierres = 0}) bastaba con que la
     * primera tarea del DAG terminara para que la corrida dejara de
     * considerarse viva: a partir de ese instante el guardia de concurrencia de
     * la capa 2 no la veía, y el botón podía lanzar una segunda carga sobre las
     * mismas tablas mientras el DAG seguía corriendo. No daba error: daba dos
     * procesos compitiendo por el mismo {@code EXCHANGE TABLES}.
     *
     * Comparar los dos conteos funciona en los dos casos: el botón está vivo
     * con 1 &gt; 0 y muerto con 1 = 1; el DAG está vivo mientras alguna de sus
     * tareas tenga la apertura sin cerrar.
     *
     * LIMITACIÓN DECLARADA: entre dos tareas del DAG no hay ningún proceso
     * {@code run_etl} vivo, así que en esa rendija los conteos se igualan y la
     * corrida no se detecta. Es inherente a deducir el estado de una bitácora
     * que solo conoce procesos: la única forma de cerrarla sería preguntarle a
     * Airflow, y este servicio no habla con Airflow a propósito.
     */
    private UUID corridaVivaEnBitacora() {
        List<Map<String, Object>> filas = ch.queryForList(
                // `abierta` va en epoch por la misma razón que en `detalle`: el
                // driver no entrega `DateTime('America/Guayaquil')` como
                // `LocalDateTime`, así que el `instanceof` de antes NUNCA se
                // cumplía y el corte por abandono no llegaba a evaluarse: una
                // corrida muerta habría bloqueado el botón para siempre.
                // `now()` de ClickHouse y `inicio` salen del mismo reloj, con lo
                // que la resta tampoco depende de la hora de la JVM.
                "SELECT toString(corrida_id) AS id, "
                + "       toUnixTimestamp(now()) - toUnixTimestamp(max(inicio)) AS antiguedad_seg, "
                + "       max(inicio) AS abierta, "
                + "       countIf(resultado =  ?) AS aperturas, "
                + "       countIf(resultado <> ?) AS cierres "
                + "FROM " + BITACORA + " WHERE tarea = ? "
                + "GROUP BY corrida_id HAVING aperturas > cierres "
                + "ORDER BY abierta DESC LIMIT 1", EN_CURSO, EN_CURSO, TAREA_CORRIDA);
        if (filas.isEmpty()) {
            return null;
        }
        long antiguedadSeg = numero(filas.get(0).get("antiguedad_seg"));
        if (antiguedadSeg > MINUTOS_ABANDONO * 60) {
            logger.warn("La corrida {} lleva abierta más de {} min sin cerrarse: "
                    + "se considera abandonada y deja de bloquear.",
                    filas.get(0).get("id"), MINUTOS_ABANDONO);
            return null;
        }
        return UUID.fromString(String.valueOf(filas.get(0).get("id")));
    }

    /**
     * Reconstruye el parte completo de una corrida a partir de sus filas.
     *
     * <h2>Una corrida puede traer UNO o MUCHOS marcadores</h2>
     *
     * El disparo por botón lanza UN proceso {@code run_etl} que carga las 21
     * tablas, así que deja UN marcador de apertura y UN cierre. Airflow lanza
     * UN PROCESO POR TAREA del DAG —es lo que permite que el grafo se vea y que
     * las tablas sin dependencias corran en paralelo—, y cada proceso escribe
     * su propio par: 22 aperturas y 22 cierres bajo el mismo {@code corrida_id}.
     *
     * Este método daba por hecho lo primero. Como el bucle se quedaba con el
     * ÚLTIMO marcador leído, en una corrida de Airflow «el» marcador de
     * apertura acababa siendo el de {@code validar_dwh}, que no carga ninguna
     * tabla y declara «0 tareas en cola». De ahí salía el
     * <b>«21 de 0 tablas publicadas»</b>: ni un error, ni una excepción, ni un
     * log — una cifra plausible y falsa, que es el modo de fallo que este
     * proyecto persigue por encima de los demás.
     *
     * La corrección es tratar la corrida como el AGREGADO de sus marcadores y
     * no como uno solo. Se aplica a los cinco campos que dependían de esa
     * elección: {@code tareasTotales}, {@code inicio}, {@code fin} +
     * {@code duracionSeg}, {@code resultado}/{@code exito} y {@code mensaje}.
     * Los nombres y tipos de la respuesta NO cambian: el frontend no se toca.
     *
     * Funciona sobre las corridas YA REGISTRADAS: solo cambia cómo se leen las
     * filas, no se migra ni se reescribe una sola de ellas.
     */
    private Map<String, Object> detalle(UUID corrida) {
        List<Map<String, Object>> filas = ch.queryForList(
                "SELECT tarea, resultado, filas_escritas, filas_leidas, duracion_seg, mensaje, "
                + "       formatDateTime(inicio, '%d/%m/%Y %H:%i') AS inicio_txt, "
                + "       formatDateTime(fin, '%d/%m/%Y %H:%i') AS fin_txt, "
                // Los instantes viajan como ENTEROS y no como fecha: la columna
                // es `DateTime('America/Guayaquil')` y el driver de ClickHouse
                // no la entrega como `LocalDateTime`, así que un `instanceof`
                // sobre ella falla en silencio y el cálculo se cae al valor de
                // reserva sin que nada lo diga. Con epoch no hay tipo que
                // adivinar.
                + "       toUnixTimestamp(inicio) AS inicio_epoch, "
                + "       toUnixTimestamp(fin)    AS fin_epoch "
                + "FROM " + BITACORA + " WHERE corrida_id = toUUID(?) "
                + "ORDER BY inicio, tarea", corrida.toString());

        //: PRIMERA apertura y ÚLTIMO cierre — el arranque y el final REALES de
        //: la corrida. Antes se guardaba el último de cada uno, con lo que en
        //: Airflow el «inicio» mostrado era el de la última tarea.
        Map<String, Object> primeraApertura = null;
        Map<String, Object> ultimoCierre = null;
        Map<String, Object> validacion = null;
        List<Map<String, Object>> tablas = new ArrayList<>();

        long aperturas = 0;
        long cierres = 0;
        //: Σ de lo que los marcadores DECLARARON tener en cola. El botón lo
        //: declara de una vez (21); Airflow, de una en una (21 × 1 + 0 de la
        //: validación). Suma 21 en ambos casos.
        long declaradas = 0;
        //: Un solo cierre con un resultado que no sea «exito» basta para que la
        //: corrida no lo sea, aunque los posteriores hayan ido bien. En Airflow
        //: el último cierre es el de `validar_dwh` y taparía un fallo anterior.
        boolean todosLosCierresBien = true;

        for (Map<String, Object> f : filas) {
            String tarea = String.valueOf(f.get("tarea"));
            String resultado = String.valueOf(f.get("resultado"));
            if (TAREA_CORRIDA.equals(tarea)) {
                if (EN_CURSO.equals(resultado)) {
                    aperturas++;
                    declaradas += numero(f.get("filas_leidas"));
                    if (primeraApertura == null) {
                        primeraApertura = f;      // ORDER BY inicio: la primera
                    }
                } else {
                    cierres++;
                    ultimoCierre = f;             // ORDER BY inicio: la última
                    todosLosCierresBien &= EXITO.equals(resultado);
                }
            } else if (TAREA_VALIDACION.equals(tarea)) {
                validacion = f;
            } else {
                tablas.add(f);
            }
        }

        // Viva mientras queden aperturas sin cerrar, o si es la que este backend
        // está ejecutando ahora mismo (todavía sin marcador de apertura).
        boolean enCurso = aperturas > cierres || corrida.equals(activa.get());

        long completadas = tablas.stream()
                .filter(f -> EXITO.equals(String.valueOf(f.get("resultado")))).count();
        long filasCargadas = tablas.stream()
                .filter(f -> EXITO.equals(String.valueOf(f.get("resultado"))))
                .mapToLong(f -> numero(f.get("filas_escritas"))).sum();

        // ── El total de tareas ────────────────────────────────────────────
        // Se CUENTAN las tareas reales de la corrida en vez de creerse lo que
        // declaró un marcador. `distinct` porque un reintento de Airflow deja
        // una fila más para la misma tabla (el intento fallido y el bueno), y
        // ahí «22 tablas» sería tan falso como el «0» que se está arreglando.
        long tablasDistintas = tablas.stream()
                .map(f -> String.valueOf(f.get("tarea"))).distinct().count();
        // El máximo con lo declarado conserva el progreso EN VIVO del botón:
        // a mitad de esa corrida hay 7 filas de tabla pero el marcador ya
        // declaró 21, y la pantalla debe seguir diciendo «7 de 21» y no
        // «7 de 7». En Airflow los dos valores coinciden al terminar; mientras
        // corre no hay forma de saber cuántas tareas tiene el DAG sin
        // preguntarle a Airflow, y este servicio no lo hace a propósito.
        long totales = Math.max(tablasDistintas, declaradas);

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

        // El arranque de la corrida es la PRIMERA apertura. Tomando la última,
        // una corrida de Airflow declaraba como hora de inicio la de su tarea
        // final, que empezó segundos antes de terminar todo.
        if (primeraApertura != null) {
            res.put("inicio", primeraApertura.get("inicio_txt"));
        }
        if (enCurso) {
            res.put("resultado", EN_CURSO);
            res.put("mensaje", totales == 0
                    ? "Actualización iniciada: preparando las cargas."
                    : "Actualización en curso: " + completadas + " de " + totales
                      + " tablas publicadas.");
        } else if (ultimoCierre != null) {
            // El veredicto es de la CORRIDA, no del último proceso que cerró.
            // En Airflow ese último es `validar_dwh`, que sale en verde aunque
            // una tabla anterior hubiera fallado: el parte habría dicho «éxito»
            // con la lista de errores llena justo al lado.
            boolean ok = todosLosCierresBien && errores.isEmpty();
            res.put("resultado", ok ? EXITO : RESULTADO_FALLO_PARCIAL);
            res.put("exito", ok);
            res.put("fin", ultimoCierre.get("fin_txt"));

            // Duración: con UN marcador vale la que él mismo midió. Con muchos,
            // sumarlas daría tiempo de CÓMPUTO y no de reloj —las tareas del DAG
            // corren en paralelo—, así que se mide de punta a punta.
            Object duracion = ultimoCierre.get("duracion_seg");
            if (cierres > 1) {
                Double reloj = segundosDePuntaAPunta(primeraApertura, ultimoCierre);
                if (reloj != null) {
                    duracion = reloj;
                }
            }
            res.put("duracionSeg", duracion);

            // Con un solo cierre, su mensaje describe la corrida entera. Con
            // muchos, el del último describe SOLO su tarea («0 tareas
            // publicadas · 0 filas»), así que se compone desde los conteos.
            res.put("mensaje", cierres == 1
                    ? String.valueOf(ultimoCierre.getOrDefault("mensaje", ""))
                    : (ok
                        ? completadas + " tablas publicadas · " + filasCargadas + " filas"
                          + (validacion != null ? " · controles ejecutados" : "")
                        : "Fallo parcial: " + errores.size() + " tarea(s) sin publicar de "
                          + totales + "."));
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

    /**
     * Segundos de RELOJ entre el arranque de la corrida y su cierre, para las
     * corridas con varios marcadores (Airflow).
     *
     * Sumar las duraciones de los marcadores daría tiempo de CÓMPUTO: las
     * tareas del DAG sin dependencias corren a la vez, así que la suma es mayor
     * que el tiempo transcurrido y crecería al añadir paralelismo, que es lo
     * contrario de lo que la cifra debe contar.
     *
     * Los instantes llegan como epoch en segundos ({@code toUnixTimestamp} en
     * la consulta) justamente para no depender de a qué tipo de Java mapee el
     * driver de ClickHouse una columna {@code DateTime} con zona.
     *
     * Devuelve null si la bitácora no trae los dos instantes; quien llama se
     * queda entonces con la duración del último marcador.
     */
    private static Double segundosDePuntaAPunta(Map<String, Object> apertura,
                                                Map<String, Object> cierre) {
        if (apertura == null || cierre == null) {
            return null;
        }
        long desde = numero(apertura.get("inicio_epoch"));
        long hasta = numero(cierre.get("fin_epoch"));
        if (desde <= 0 || hasta < desde) {
            return null;
        }
        return (double) (hasta - desde);
    }
}
