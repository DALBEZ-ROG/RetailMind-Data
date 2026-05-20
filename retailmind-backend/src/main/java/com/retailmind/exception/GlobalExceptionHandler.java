package com.retailmind.exception;

import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
