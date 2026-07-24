package com.petstore.web;

import com.petstore.order.service.EmptyCartException;
import com.petstore.order.web.MissingFormDataException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Centralised REST error translation (SRP: all HTTP error mapping in one place).
 * Maps domain exceptions to appropriate status codes so controllers stay thin.
 */
@RestControllerAdvice
public class RestExceptionHandler {

    /** Illegal workflow transition (e.g. approve an already-shipped order) → 409. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "illegal_state", "message", ex.getMessage()));
    }

    /** Unknown order / bad argument → 404. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArg(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "not_found", "message", ex.getMessage()));
    }

    @ExceptionHandler(EmptyCartException.class)
    public ResponseEntity<Map<String, String>> handleEmptyCart(EmptyCartException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "cart_empty", "message", ex.getMessage()));
    }

    /** Missing required ship-to/bill-to address fields at checkout → 400. */
    @ExceptionHandler(MissingFormDataException.class)
    public ResponseEntity<Map<String, Object>> handleMissingFormData(MissingFormDataException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "missing_form_data",
                        "message", ex.getMessage(),
                        "missingFields", ex.getMissingFields()));
    }
}
