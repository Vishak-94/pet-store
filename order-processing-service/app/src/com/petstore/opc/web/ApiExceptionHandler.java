package com.petstore.opc.web;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Uniform REST error mapping for the OPC admin facade API (all HTTP error translation in one
 * place, mirroring customer-service's {@code RestExceptionHandler}). Before this, bad client input
 * leaked as a 500: an unknown {@code ?status=} or {@code newStatus} hit {@code OrderStatus.valueOf}
 * (→ {@link IllegalArgumentException}) and a malformed {@code ?start=}/{@code ?end=} hit
 * {@code LocalDate.parse} (→ {@link DateTimeParseException}), both of which are the caller's fault
 * (400), not the server's. Illegal workflow transitions and lost optimistic-lock races are
 * conflicts (409). This makes the controller javadoc's "unknown value yields a 400" actually true.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final String FIELD_STATUS = "status";
    private static final String FIELD_ERROR = "error";
    private static final String FIELD_DETAIL = "detail";

    private static final String ERROR_BAD_REQUEST = "bad_request";
    private static final String ERROR_VALIDATION_FAILED = "validation_failed";
    private static final String ERROR_CONFLICT = "conflict";

    private static Map<String, Object> body(HttpStatus status, String error, Object detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(FIELD_STATUS, status.value());
        m.put(FIELD_ERROR, error);
        m.put(FIELD_DETAIL, detail);
        return m;
    }

    /**
     * Bad client input → 400. Covers {@code OrderStatus.valueOf} on an unknown {@code ?status=} /
     * {@code newStatus} and {@code LocalDate.parse} on a malformed date range.
     */
    @ExceptionHandler({IllegalArgumentException.class, DateTimeParseException.class})
    public ResponseEntity<Map<String, Object>> handleBadInput(Exception ex) {
        return ResponseEntity.badRequest().body(body(HttpStatus.BAD_REQUEST, ERROR_BAD_REQUEST, ex.getMessage()));
    }

    /** Bean-validation failures on a request body → 400 with a field→message map. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fields.put(fe.getField(), fe.getDefaultMessage());
        }
        return ResponseEntity.badRequest().body(body(HttpStatus.BAD_REQUEST, ERROR_VALIDATION_FAILED, fields));
    }

    /**
     * Illegal workflow transition (e.g. approving an already-DENIED order) → 409. Thrown by
     * {@code AdminService.applyStatusChange} when {@code OrderStatus.canGoTo} rejects the move.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body(HttpStatus.CONFLICT, ERROR_CONFLICT, ex.getMessage()));
    }

    /**
     * Lost optimistic-lock race (the approve+deny conflict guarded by {@code @Version}, B1) → 409.
     * The concurrent transition already committed; the caller should re-read and retry.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> handleOptimisticLock(OptimisticLockingFailureException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(body(HttpStatus.CONFLICT, ERROR_CONFLICT,
                        "Order was modified concurrently — re-read its status and retry"));
    }
}
