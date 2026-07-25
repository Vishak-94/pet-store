package com.petstore.warehouse.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** Uniform errors for the warehouse API. */
@RestControllerAdvice(basePackages = "com.petstore.warehouse.web")
public class ApiExceptionHandler {

    /** JSON body keys + error codes returned for the two mapped failures. */
    private static final String KEY_ERROR = "error";
    private static final String KEY_MESSAGE = "message";
    private static final String ERROR_ILLEGAL_STATE = "illegal_state";
    private static final String ERROR_NOT_FOUND = "not_found";

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> illegalState(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(KEY_ERROR, ERROR_ILLEGAL_STATE, KEY_MESSAGE, e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> notFound(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(KEY_ERROR, ERROR_NOT_FOUND, KEY_MESSAGE, e.getMessage()));
    }
}
