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

    /** Convenience when there's no correlation id in scope. */
    public static EventMeta meta(String type) {
        return meta(type, null);
    }
}
