package com.retailmind.exception;

import java.sql.SQLException;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.retailmind.dto.ApiErrorDTO;

import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiErrorDTO> handleNotFound(NoSuchElementException ex,
                                                       HttpServletRequest request) {
        logger.error("Recurso no encontrado: {} - {}", request.getRequestURI(), ex.getMessage());
        ApiErrorDTO error = new ApiErrorDTO(
                404, "Not Found", ex.getMessage(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorDTO> handleBadRequest(IllegalArgumentException ex,
                                                         HttpServletRequest request) {
        logger.error("Argumento invalido: {} - {}", request.getRequestURI(), ex.getMessage());
        ApiErrorDTO error = new ApiErrorDTO(
                400, "Bad Request", ex.getMessage(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    /** Guardias de estado/idempotencia: la acción ya no aplica al estado actual. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiErrorDTO> handleConflict(IllegalStateException ex,
                                                       HttpServletRequest request) {
        logger.warn("Accion no permitida por estado: {} - {}", request.getRequestURI(), ex.getMessage());
        ApiErrorDTO error = new ApiErrorDTO(
                409, "Conflict", ex.getMessage(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    /** queryForObject/queryForMap sin filas: el recurso referenciado no existe. */
    @ExceptionHandler(EmptyResultDataAccessException.class)
    public ResponseEntity<ApiErrorDTO> handleEmptyResult(EmptyResultDataAccessException ex,
                                                          HttpServletRequest request) {
        logger.warn("Recurso inexistente: {} - {}", request.getRequestURI(), ex.getMessage());
        ApiErrorDTO error = new ApiErrorDTO(
                404, "Not Found",
                "El recurso solicitado no existe o fue eliminado. Verifica los datos seleccionados.",
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    /**
     * Prefijos de los mensajes que escriben NUESTROS PROPIOS guardianes de
     * integridad del kardex ({@code fn_validar_ecuacion_kardex} del script 91 y
     * {@code fn_kardex_apendice} del 108). Están redactados PARA EL USUARIO —en
     * español, nombrando el SKU y la bodega— así que se dejan pasar tal cual en
     * vez de taparlos con el texto genérico.
     */
    private static final String[] MENSAJES_DE_GUARDIA = {
        "Kardex descuadrado:", "Kardex desencadenado:"
    };

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorDTO> handleDataIntegrity(DataIntegrityViolationException ex,
                                                            HttpServletRequest request) {
        logger.error("Violacion de integridad: {} - {}", request.getRequestURI(), ex.getMessage());

        // Un guardián nuestro ya explicó QUÉ pasó y con qué cifras. Sustituirlo
        // por «los datos enviados no cumplen las reglas de la base de datos»
        // convierte un diagnóstico accionable en un mensaje que no dice nada, y
        // deja al usuario sin saber sobre qué producto ni en qué bodega.
        String propio = mensajeDeGuardia(ex);
        if (propio != null) {
            ApiErrorDTO error = new ApiErrorDTO(
                    409, "Conflict", propio, request.getRequestURI());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
        }

        ApiErrorDTO error = new ApiErrorDTO(
                400, "Bad Request",
                "Los datos enviados no cumplen las reglas de la base de datos (referencia inexistente, duplicado o valor fuera de rango).",
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    /**
     * Devuelve el mensaje del guardián de kardex si la causa raíz es uno de
     * ellos, o {@code null} para cualquier otra violación de integridad —que
     * sigue saliendo con el texto genérico de siempre, porque un mensaje crudo
     * de PostgreSQL sobre una FK o un UNIQUE no le sirve de nada al usuario y
     * además destapa nombres de tablas y columnas.
     */
    private static String mensajeDeGuardia(DataIntegrityViolationException ex) {
        Throwable causa = ex.getMostSpecificCause();
        String texto = causa != null ? causa.getMessage() : null;
        if (texto == null) {
            return null;
        }
        for (String prefijo : MENSAJES_DE_GUARDIA) {
            int i = texto.indexOf(prefijo);
            if (i >= 0) {
                // El driver antepone «ERROR: » y añade la posición y el detalle
                // detrás; se recorta a la primera línea, que es la frase.
                String limpio = texto.substring(i);
                int corte = limpio.indexOf('\n');
                return corte > 0 ? limpio.substring(0, corte).trim() : limpio.trim();
            }
        }
        return null;
    }

    /**
     * Seguridad a nivel de BD: SQLState 42501 (insufficient_privilege) cubre el
     * bloqueo por horario (fn_bloquear_fuera_horario), RLS y privilegios de rol.
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiErrorDTO> handleDataAccess(DataAccessException ex,
                                                         HttpServletRequest request) {
        Throwable causa = ex.getMostSpecificCause();
        if (causa instanceof SQLException sql && "42501".equals(sql.getSQLState())) {
            String detalle = causa.getMessage() != null && causa.getMessage().contains("fuera del horario")
                    ? "Acceso denegado: fuera del horario permitido para su rol. Consulte al administrador."
                    : "Acceso denegado: su rol no tiene privilegios para esta operacion.";
            logger.warn("Acceso denegado por BD: {} - {}", request.getRequestURI(), causa.getMessage());
            ApiErrorDTO error = new ApiErrorDTO(
                    403, "Forbidden", detalle, request.getRequestURI());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
        }
        return handleGeneric(ex, request);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // EXCEPCIONES DE SPRING WEB QUE YA TRAEN SU PROPIO ESTADO HTTP
    //
    // Esta clase NO extiende ResponseEntityExceptionHandler, así que el
    // @ExceptionHandler(Exception.class) de abajo se queda con TODO lo que no
    // esté declarado arriba — incluidas las excepciones de Spring que
    // implementan ErrorResponse y que traen su propio código. El resultado es
    // que una URL mal escrita, un método HTTP equivocado o un parámetro con el
    // tipo cambiado se reportan como «Error interno del servidor» (500) en vez
    // de 404, 405 y 400. Y un 500 falso no es un detalle cosmético: manda a
    // buscar el fallo dentro del servidor cuando el problema está en la
    // petición. Los tres se declaran aquí para que conserven su semántica.
    // ─────────────────────────────────────────────────────────────────────────

    /** Ruta inexistente bajo /api/**: es un 404, no un fallo del servidor. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorDTO> handleRutaInexistente(NoResourceFoundException ex,
                                                              HttpServletRequest request) {
        logger.warn("Ruta inexistente: {} {}", request.getMethod(), request.getRequestURI());
        ApiErrorDTO error = new ApiErrorDTO(
                404, "Not Found",
                "La ruta solicitada no existe en esta API.",
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    /** Método HTTP no soportado por la ruta (p. ej. POST sobre un GET). */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorDTO> handleMetodoNoSoportado(HttpRequestMethodNotSupportedException ex,
                                                                HttpServletRequest request) {
        logger.warn("Metodo no soportado: {} {}", request.getMethod(), request.getRequestURI());
        ApiErrorDTO error = new ApiErrorDTO(
                405, "Method Not Allowed",
                "El metodo " + request.getMethod() + " no esta permitido en esta ruta.",
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(error);
    }

    /**
     * Parámetro con el tipo cambiado (`?varianteId=abc`). Es el más dañino de
     * los tres porque se alcanza desde la propia pantalla: un filtro mal
     * escrito devolvía 500 en vez de decir qué parámetro está mal.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorDTO> handleTipoDeParametro(MethodArgumentTypeMismatchException ex,
                                                              HttpServletRequest request) {
        logger.warn("Parametro con tipo invalido en {}: {} = {}",
                request.getRequestURI(), ex.getName(), ex.getValue());
        ApiErrorDTO error = new ApiErrorDTO(
                400, "Bad Request",
                "El parametro «" + ex.getName() + "» no admite el valor enviado.",
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    /**
     * Falta un parámetro OBLIGATORIO de la consulta
     * (p. ej. {@code /api/gerencia/metas/vigente} sin {@code anio}).
     *
     * Es el cuarto miembro de la misma familia de arriba y se quedó fuera: el
     * endpoint respondía **500** a los cuatro roles que lo pueden usar, así que
     * una petición incompleta del navegador se leía como «el servidor está
     * roto». Un parámetro que falta es culpa de la petición: 400.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiErrorDTO> handleParametroAusente(MissingServletRequestParameterException ex,
                                                               HttpServletRequest request) {
        logger.warn("Parametro obligatorio ausente en {}: {}", request.getRequestURI(), ex.getParameterName());
        ApiErrorDTO error = new ApiErrorDTO(
                400, "Bad Request",
                "Falta el parametro obligatorio «" + ex.getParameterName() + "».",
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    /** Falta una parte obligatoria de un multipart (la carga de CSV del ETL). */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiErrorDTO> handleParteAusente(MissingServletRequestPartException ex,
                                                           HttpServletRequest request) {
        logger.warn("Parte multipart ausente en {}: {}", request.getRequestURI(), ex.getRequestPartName());
        ApiErrorDTO error = new ApiErrorDTO(
                400, "Bad Request",
                "Falta el archivo «" + ex.getRequestPartName() + "» en la peticion.",
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    /**
     * Cuerpo ilegible: JSON malformado, vacío, o un tipo que no encaja con el
     * DTO. Afecta a los 18 controladores con {@code @RequestBody}.
     *
     * El mensaje NO incluye el detalle de Jackson a propósito: nombra la clase
     * del DTO y la ruta del campo, que es estructura interna. Se registra en el
     * log, que es donde sirve.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorDTO> handleCuerpoIlegible(HttpMessageNotReadableException ex,
                                                             HttpServletRequest request) {
        logger.warn("Cuerpo ilegible en {}: {}", request.getRequestURI(), ex.getMessage());
        ApiErrorDTO error = new ApiErrorDTO(
                400, "Bad Request",
                "El cuerpo de la peticion no es un JSON valido o no coincide con el formato esperado.",
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    /** Content-Type no soportado por la ruta: 415, no 500. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorDTO> handleTipoNoSoportado(HttpMediaTypeNotSupportedException ex,
                                                              HttpServletRequest request) {
        logger.warn("Content-Type no soportado en {}: {}", request.getRequestURI(), ex.getContentType());
        ApiErrorDTO error = new ApiErrorDTO(
                415, "Unsupported Media Type",
                "El tipo de contenido enviado no esta soportado en esta ruta.",
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(error);
    }

    /** Validación de bean (@Valid) fallida: 400 con los campos que fallaron. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorDTO> handleValidacion(MethodArgumentNotValidException ex,
                                                         HttpServletRequest request) {
        String campos = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(java.util.stream.Collectors.joining("; "));
        logger.warn("Validacion fallida en {}: {}", request.getRequestURI(), campos);
        ApiErrorDTO error = new ApiErrorDTO(
                400, "Bad Request",
                campos.isBlank() ? "Los datos enviados no son validos." : campos,
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    /** Archivo subido por encima del tope configurado. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorDTO> handleArchivoGrande(MaxUploadSizeExceededException ex,
                                                            HttpServletRequest request) {
        logger.warn("Archivo demasiado grande en {}", request.getRequestURI());
        ApiErrorDTO error = new ApiErrorDTO(
                413, "Payload Too Large",
                "El archivo enviado supera el tamano maximo permitido.",
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(error);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorDTO> handleGeneric(Exception ex,
                                                      HttpServletRequest request) {
        logger.error("Error interno en {}: {}", request.getRequestURI(), ex.getMessage(), ex);
        ApiErrorDTO error = new ApiErrorDTO(
                500, "Internal Server Error",
                "Error interno del servidor. Contacte al administrador.",
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }
}
