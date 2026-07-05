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
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorDTO> handleDataIntegrity(DataIntegrityViolationException ex,
                                                            HttpServletRequest request) {
        logger.error("Violacion de integridad: {} - {}", request.getRequestURI(), ex.getMessage());
        ApiErrorDTO error = new ApiErrorDTO(
                400, "Bad Request",
                "Los datos enviados no cumplen las reglas de la base de datos (referencia inexistente, duplicado o valor fuera de rango).",
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
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
