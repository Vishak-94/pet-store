package com.petstore.warehouse.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.util.Map;

/**
 * Uniform errors for the warehouse API. Because this console owns no data and delegates every
 * operation to the OPC via {@code OrderProcessingClient}, a downstream OPC fault must be surfaced
 * as a gateway error (502/503) rather than leaking as a bare 500 — the failure is upstream, and a
 * 5xx that distinguishes "the OPC errored" (502) from "the OPC was unreachable/timed out" (503)
 * tells an operator where to look.
 */
@RestControllerAdvice(basePackages = "com.petstore.warehouse.web")
public class ApiExceptionHandler {

    /** JSON body keys + error codes returned for the mapped failures. */
    private static final String KEY_ERROR = "error";
    private static final String KEY_MESSAGE = "message";
    private static final String ERROR_ILLEGAL_STATE = "illegal_state";
    private static final String ERROR_NOT_FOUND = "not_found";
    private static final String ERROR_UPSTREAM = "upstream_error";
    private static final String ERROR_UPSTREAM_UNAVAILABLE = "upstream_unavailable";

    /** Message shown when the OPC itself returned a 5xx / when it was unreachable. */
    private static final String MSG_UPSTREAM = "Order-processing service returned an error";
    private static final String MSG_UPSTREAM_UNAVAILABLE = "Order-processing service is unavailable";

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> illegalState(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(KEY_ERROR, ERROR_ILLEGAL_STATE, KEY_MESSAGE, e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> notFound(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(KEY_ERROR, ERROR_NOT_FOUND, KEY_MESSAGE, e.getMessage()));
    }

    /** OPC responded with a 5xx → 502 Bad Gateway (the upstream errored, the console did not). */
    @ExceptionHandler(HttpServerErrorException.class)
    public ResponseEntity<Map<String, String>> upstreamError(HttpServerErrorException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of(KEY_ERROR, ERROR_UPSTREAM, KEY_MESSAGE, MSG_UPSTREAM));
    }

    /**
     * OPC unreachable or slow — connect/read timeout ({@link ResourceAccessException}) or any other
     * transport-level {@link RestClientException} → 503 Service Unavailable. Placed after the more
     * specific {@link HttpServerErrorException} handler (which is itself a {@code RestClientException})
     * so a 5xx body still maps to 502.
     */
    @ExceptionHandler({ResourceAccessException.class, RestClientException.class})
    public ResponseEntity<Map<String, String>> upstreamUnavailable(RestClientException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(KEY_ERROR, ERROR_UPSTREAM_UNAVAILABLE, KEY_MESSAGE, MSG_UPSTREAM_UNAVAILABLE));
    }
}
