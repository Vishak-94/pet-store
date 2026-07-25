package com.petstore.opc.repository;

/**
 * A single outbound event queued in the transactional outbox — the carrier the
 * {@link OutboxStore} port exchanges with the service layer. Framework-free (no
 * JPA/Jackson), like the domain records: the JSON is already-serialized text, and
 * the JPA mapping lives only in the {@code repository.jpa} adapter.
 *
 * <p>The {@link #payload} is the event serialized with the same Jackson mapper the
 * JMS converter uses, and it embeds a fixed {@code EventMeta.eventId}; because
 * relay delivery is at-least-once, re-sending the frozen payload carries the same
 * id so consumers dedup the redelivery.
 *
 * @param id          outbox row id — {@code null} when enqueuing, populated on fetch
 * @param destination JMS destination name (e.g. {@code ApprovedOrderQueue})
 * @param topic       {@code true} = topic (pub/sub), {@code false} = queue (point-to-point)
 * @param eventType   logical event type id (the JMS {@code _type}); maps back to the event class
 * @param payload     the event serialized as JSON
 * @param orderId     the order this event is about (tracing / operational queries); may be null
 */
public record OutboxMessage(
        Long id,
        String destination,
        boolean topic,
        String eventType,
        String payload,
        String orderId) {

    /** New (not-yet-persisted) message — no id yet. */
    public static OutboxMessage pending(String destination, boolean topic, String eventType,
                                        String payload, String orderId) {
        return new OutboxMessage(null, destination, topic, eventType, payload, orderId);
    }
}
