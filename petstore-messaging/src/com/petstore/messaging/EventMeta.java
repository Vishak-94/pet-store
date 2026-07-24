package com.petstore.messaging;

/**
 * The standard envelope metadata carried by every event, alongside its domain
 * payload. Kept as a small mixin record embedded in each concrete event so
 * consumers get uniform cross-cutting fields without a generic wrapper (which
 * JSON can't deserialize cleanly without type hints).
 *
 * <ul>
 *   <li>{@code eventId} — unique per message; lets consumers dedup (JMS is
 *       at-least-once).</li>
 *   <li>{@code type} — logical event type (also the JMS {@code _type} id).</li>
 *   <li>{@code occurredAt} — ISO-8601 instant the event happened.</li>
 *   <li>{@code correlationId} — ties the event back to the originating request /
 *       trace (carried from the HTTP X-Correlation-Id / MDC).</li>
 * </ul>
 */
public record EventMeta(String eventId, String type, String occurredAt, String correlationId) {
}
