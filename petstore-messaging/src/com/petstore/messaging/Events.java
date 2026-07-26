package com.petstore.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * Builds {@link EventMeta} for a new event — a fresh unique {@code eventId}, the
 * current instant, the logical type, and the correlation id (pass the current
 * request's X-Correlation-Id / MDC value so one trace spans HTTP → JMS).
 */
public final class Events {

    private Events() {
    }

    public static EventMeta meta(String type, String correlationId) {
        return new EventMeta(
                UUID.randomUUID().toString(),
                type,
                Instant.now().toString(),
                correlationId);
    }

    /**
     * Build meta for the CURRENT trace: the correlation id is pulled from the ambient
     * {@link Correlation#current() MDC} rather than passed explicitly. This is the default
     * producers use, so an event published while handling an HTTP request (or an inbound JMS
     * event whose id a listener adopted via {@link Correlation#set}) automatically carries that
     * id end-to-end — no need to thread a correlation id through every service signature. Falls
     * back to {@code null} when nothing is in scope (e.g. a scheduled job with no originating request).
     */
    public static EventMeta meta(String type) {
        return meta(type, Correlation.current());
    }
}
