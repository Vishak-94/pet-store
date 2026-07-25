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
 *
 * <p>Scoped to {@code @RestController}s (annotations = RestController.class) so the
 * JSON bodies here are only ever returned to API clients. Browser ({@code @Controller})
 * pages are handled by {@link HtmlExceptionHandler}, which renders an HTML error view
 * instead of leaking a JSON payload into the page.
 */
@RestControllerAdvice(annotations = org.springframework.web.bind.annotation.RestController.class)
public class RestExceptionHandler {

    /** JSON body keys + the error codes returned for each mapped failure. */
    private static final String KEY_ERROR = "error";
    private static final String KEY_MESSAGE = "message";
    private static final String KEY_MISSING_FIELDS = "missingFields";
    private static final String ERROR_ILLEGAL_STATE = "illegal_state";
    private static final String ERROR_NOT_FOUND = "not_found";
    private static final String ERROR_CART_EMPTY = "cart_empty";
    private static final String ERROR_MISSING_FORM_DATA = "missing_form_data";

    /** Illegal workflow transition (e.g. approve an already-shipped order) → 409. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of(KEY_ERROR, ERROR_ILLEGAL_STATE, KEY_MESSAGE, ex.getMessage()));
    }

    /** Unknown order / bad argument → 404. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArg(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of(KEY_ERROR, ERROR_NOT_FOUND, KEY_MESSAGE, ex.getMessage()));
    }

    @ExceptionHandler(EmptyCartException.class)
    public ResponseEntity<Map<String, String>> handleEmptyCart(EmptyCartException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of(KEY_ERROR, ERROR_CART_EMPTY, KEY_MESSAGE, ex.getMessage()));
    }

    /** Missing required ship-to/bill-to address fields at checkout → 400. */
    @ExceptionHandler(MissingFormDataException.class)
    public ResponseEntity<Map<String, Object>> handleMissingFormData(MissingFormDataException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of(KEY_ERROR, ERROR_MISSING_FORM_DATA,
                        KEY_MESSAGE, ex.getMessage(),
                        KEY_MISSING_FIELDS, ex.getMissingFields()));
    }
}
