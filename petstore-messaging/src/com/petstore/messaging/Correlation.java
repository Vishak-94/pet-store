package com.petstore.messaging;

import org.slf4j.MDC;

/**
 * The one place the correlation id crosses the HTTP↔JMS boundary. A correlation id ties a
 * customer action (an HTTP request) to every event and log line it triggers across services,
 * so a single checkout can be traced end-to-end (storefront → OPC → inventory → notification)
 * even though it fans out over JMS.
 *
 * <p>The id lives in the SLF4J {@link MDC} under {@link #MDC_KEY} — the same key
 * customer-service's {@code CorrelationIdFilter} already uses, so log patterns render it as
 * {@code cid=...} uniformly. This helper is the bridge:
 * <ul>
 *   <li>{@link #current()} reads the id producers stamp onto {@link EventMeta} (see
 *       {@link Events#meta(String)}), so an HTTP-triggered publish carries the request's id;</li>
 *   <li>{@link #set(String)} / {@link #clear()} let a JMS listener adopt an <em>inbound</em>
 *       event's correlation id for the duration of handling it, so events it re-publishes and
 *       logs it writes stay on the same trace (the JMS analogue of the HTTP filter).</li>
 * </ul>
 *
 * <p>Framework-free (only slf4j-api) so it can live in the shared contract lib and be reused by
 * every producer and consumer without dragging in a servlet or Spring dependency.
 */
public final class Correlation {

    /** MDC key for the correlation id — matches the storefront/service HTTP filters. */
    public static final String MDC_KEY = "correlationId";

    private Correlation() {
    }

    /** The current correlation id, or {@code null} if none is in scope. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }

    /** Put {@code correlationId} in scope (no-op for a null/blank id, so callers needn't guard). */
    public static void set(String correlationId) {
        if (correlationId != null && !correlationId.isBlank()) {
            MDC.put(MDC_KEY, correlationId);
        }
    }

    /** Remove the correlation id from scope — MUST be called in a finally so pooled threads don't leak it. */
    public static void clear() {
        MDC.remove(MDC_KEY);
    }
}
