package com.retailmind.exception;

import com.retailmind.dto.ApiErrorDTO;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ApiErrorDTO> handleNotFound(EntityNotFoundException ex,
                                                       HttpServletRequest request) {
        logger.error("Recurso no encontrado: {} - {}", request.getRequestURI(), ex.getMessage());
        ApiErrorDTO error = new ApiErrorDTO(
                404, "Not Found", ex.getMessage(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorDTO> handleConflict(DataIntegrityViolationException ex,
                                                       HttpServletRequest request) {
        logger.error("Conflicto de integridad: {} - {}", request.getRequestURI(), ex.getMessage());
        ApiErrorDTO error = new ApiErrorDTO(
                409, "Conflict",
                "Violacion de integridad de datos. El registro ya existe o viola una restriccion.",
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
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
