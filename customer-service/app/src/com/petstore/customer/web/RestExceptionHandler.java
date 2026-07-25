package com.petstore.customer.web;

import com.petstore.customer.observability.CorrelationIdFilter;
import com.petstore.customer.service.DuplicateAccountException;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Uniform REST error responses (SRP: all HTTP error mapping in one place). Every
 * body includes the correlation id so a client error can be traced back to the
 * exact request in the logs.
 */
@RestControllerAdvice
public class RestExceptionHandler {

    /** Error-body field names (kept as one constant set so all handlers emit an identical shape). */
    private static final String FIELD_STATUS = "status";
    private static final String FIELD_ERROR = "error";
    private static final String FIELD_DETAIL = "detail";
    private static final String FIELD_CORRELATION_ID = "correlationId";
    /** Error codes returned in the {@code error} field. */
    private static final String ERROR_VALIDATION_FAILED = "validation_failed";
    private static final String ERROR_DUPLICATE_ACCOUNT = "duplicate_account";
    private static final String ERROR_NOT_FOUND = "not_found";

    private Map<String, Object> body(HttpStatus status, String error, Object detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(FIELD_STATUS, status.value());
        m.put(FIELD_ERROR, error);
        m.put(FIELD_DETAIL, detail);
        // Same MDC key the CorrelationIdFilter set, so the error body carries the request's trace id.
        m.put(FIELD_CORRELATION_ID, MDC.get(CorrelationIdFilter.MDC_KEY));
        return m;
    }

    /** Bean-validation failures → 400 with a field→message map. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fields.put(fe.getField(), fe.getDefaultMessage());
        }
        return ResponseEntity.badRequest().body(body(HttpStatus.BAD_REQUEST, ERROR_VALIDATION_FAILED, fields));
    }

    @ExceptionHandler(DuplicateAccountException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicate(DuplicateAccountException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(body(HttpStatus.CONFLICT, ERROR_DUPLICATE_ACCOUNT, ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArg(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(body(HttpStatus.NOT_FOUND, ERROR_NOT_FOUND, ex.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleStatus(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        return ResponseEntity.status(status).body(body(status, status.getReasonPhrase(), ex.getReason()));
    }
}
