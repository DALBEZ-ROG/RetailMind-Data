package com.retailmind.dwh;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

/**
 * PROGRAMACIÓN AUTOMÁTICA de la actualización del almacén: una corrida diaria.
 *
 * <h2>Por qué aquí y no en el contenedor `etl` ni en el programador de Windows</h2>
 *
 * Se evaluaron las tres opciones que ofrece este entorno:
 *
 * <ul>
 *   <li><b>El contenedor {@code etl} de docker-compose</b>, hoy ocioso. Es la
 *       opción que parece natural y es la que peor encaja: <b>PostgreSQL corre
 *       LOCAL, fuera de compose</b>, así que desde dentro del contenedor
 *       {@code ETL_PG_HOST=localhost} apunta al propio contenedor y no a la
 *       base. Habría que reescribir la conexión contra
 *       {@code host.docker.internal}, pasarle las cinco variables
 *       {@code ETL_PG_*} que hoy no recibe e instalarle un cron que la imagen
 *       no trae. Tres piezas nuevas y una dependencia de Docker para un
 *       pipeline cuyo origen está fuera de Docker. Cuando la contenerización se
 *       complete —sigue pendiente— esta opción pasa a ser la buena.</li>
 *   <li><b>El programador de tareas del sistema.</b> Funciona, pero vive fuera
 *       del repositorio: no se versiona, no viaja con el proyecto, no se ve en
 *       una revisión de código y no se puede consultar desde la aplicación.</li>
 *   <li><b>{@code @Scheduled} de Spring</b> — el elegido. El backend ya es el
 *       proceso que lanza el orquestador cuando se pulsa el botón, así que
 *       programarlo aquí <b>no añade ningún servicio</b> y reutiliza entera la
 *       misma pieza: el mismo guardia de concurrencia (una corrida automática
 *       no puede solaparse con una manual, ni al revés), la misma bitácora y el
 *       mismo parte de estado que ve la pantalla. La zona horaria es un
 *       parámetro de primera clase de la anotación, y la configuración se
 *       versiona en {@code application.properties}.</li>
 * </ul>
 *
 * <h2>La zona horaria no es un detalle</h2>
 *
 * {@code zone} es OBLIGATORIO aquí. Sin él, Spring interpreta el cron en la
 * zona por defecto de la JVM: en un contenedor eso es UTC, y las 02:00 se
 * dispararían a las <b>21:00 hora de Ecuador</b> — la franja de más actividad
 * de la tienda, justo lo contrario de lo que se pretende. El mismo criterio de
 * {@code esta_en_horario()} en PostgreSQL y del {@code ZONA_HORARIA} del
 * pipeline: nunca una hora implícita.
 *
 * <h2>02:00</h2>
 *
 * Hora de mínima actividad. Importa menos por la carga —una corrida completa
 * son ~20 s y solo LEE de PostgreSQL— que por la validación: los controles
 * comparan el almacén contra el origen, y con la tienda en movimiento un
 * pedido entrante entre el volcado y el control puede descuadrar un conteo. De
 * madrugada eso es improbable, y si aun así ocurre el reintento del orquestador
 * lo resuelve.
 */
@Configuration
@EnableScheduling
public class DwhProgramacion {

    private static final Logger logger = LoggerFactory.getLogger(DwhProgramacion.class);

    private static final DateTimeFormatter FORMATO =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss z");

    private final DwhActualizacionService servicio;

    /**
     * Deja en el log de arranque CUÁNDO va a correr la próxima vez, resuelto en
     * la zona configurada y también en la de la JVM.
     *
     * No es adorno: en esta máquina la zona del sistema coincide con la del
     * negocio, así que un cron mal zonificado se comportaría exactamente igual
     * aquí y fallaría solo en el contenedor —donde la JVM es UTC—, que es el
     * peor sitio para enterarse. Con las dos horas impresas, la diferencia se
     * ve antes de desplegar en vez de descubrirse cuando la corrida nocturna
     * caiga en plena tarde.
     */
    public DwhProgramacion(DwhActualizacionService servicio,
                           @Value("${dwh.programacion.cron}") String cron,
                           @Value("${dwh.programacion.zona}") String zona) {
        this.servicio = servicio;

        if ("-".equals(cron.trim())) {
            logger.info("Actualización automática del DWH DESACTIVADA (dwh.programacion.cron = '-').");
            return;
        }
        try {
            CronExpression expresion = CronExpression.parse(cron);
            ZoneId zonaNegocio = ZoneId.of(zona);
            ZonedDateTime proxima = expresion.next(ZonedDateTime.now(zonaNegocio));
            logger.info("Actualización automática del DWH: cron '{}' en zona {}. "
                    + "Próxima corrida {} (hora de la JVM: {}).",
                    cron, zona,
                    proxima == null ? "nunca" : proxima.format(FORMATO),
                    proxima == null ? "nunca"
                            : proxima.withZoneSameInstant(ZoneId.systemDefault()).format(FORMATO));
        } catch (RuntimeException e) {
            logger.error("Programación del DWH mal configurada (cron '{}', zona '{}'): {}",
                    cron, zona, e.getMessage());
        }
    }

    /**
     * Corrida diaria. El cron y la zona salen de {@code application.properties}
     * para poder acortar el intervalo en una prueba sin recompilar; el valor
     * {@code -} desactiva la programación sin tocar código.
     */
    @Scheduled(cron = "${dwh.programacion.cron}", zone = "${dwh.programacion.zona}")
    public void actualizacionDiaria() {
        try {
            servicio.disparar("programación automática");
        } catch (DwhActualizacionService.CorridaEnCurso e) {
            // No es un error: alguien lanzó la actualización a mano justo antes.
            // Se salta este turno; mañana vuelve a intentarlo.
            logger.info("Corrida automática omitida: {}", e.getMessage());
        } catch (Exception e) {
            // Una excepción que escape de aquí deja el planificador de Spring
            // sin registrar la siguiente ejecución en algunos escenarios; se
            // atrapa para que un fallo de hoy no cancele el de mañana.
            logger.error("La corrida automática no pudo lanzarse", e);
        }
    }
}
