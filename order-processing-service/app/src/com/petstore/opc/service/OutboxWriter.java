package com.petstore.opc.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.petstore.messaging.Destination;
import com.petstore.messaging.MessagingConfig;
import com.petstore.opc.repository.OutboxMessage;
import com.petstore.opc.repository.OutboxStore;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Serializes an outbound event and appends it to the transactional {@link OutboxStore}
 * <em>within the caller's transaction</em>. The gateways call this instead of publishing
 * to JMS directly, so the event row and the order-status write commit or roll back
 * together — the transactional-outbox pattern that replaces the old after-commit publish
 * (which could commit the DB but lose the JMS send on a crash, or vice-versa).
 *
 * <p>The event is frozen to JSON here with the SAME Jackson configuration the JMS
 * converter uses ({@link MessagingConfig#jacksonJmsMessageConverter}), and the frozen
 * payload keeps its {@code EventMeta.eventId} — so when the at-least-once {@link OutboxRelay}
 * re-sends after a crash, consumers see the same id and dedup the redelivery.
 */
@Component
public class OutboxWriter {

    /**
     * event class → {@code _type} id, inverted from {@link MessagingConfig#TYPE_IDS}
     * (the single source of truth) — same derivation as {@code MessagePublisher}, so the
     * type id the relay stamps on the JMS message matches what a direct publish would.
     */
    private static final Map<Class<?>, String> TYPE_BY_CLASS = MessagingConfig.TYPE_IDS.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));

    /** Matches MessagingConfig's converter mapper so the stored JSON is wire-identical. */
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final OutboxStore outbox;

    public OutboxWriter(OutboxStore outbox) {
        this.outbox = outbox;
    }

    /**
     * Enqueue an event for reliable publishing to {@code dest}. Must be called inside the
     * business transaction (the gateways are invoked from {@code @Transactional} services),
     * so the row is committed atomically with the state change.
     *
     * @param dest    the JMS destination (queue/topic) the relay will publish to
     * @param event   the event to freeze to JSON; its class must be registered in
     *                {@link MessagingConfig#TYPE_IDS}
     * @param orderId the order the event is about (for tracing / operational queries)
     * @throws IllegalArgumentException if {@code event}'s class has no {@code _type} id mapping
     */
    public void enqueue(Destination dest, Object event, String orderId) {
        String type = TYPE_BY_CLASS.get(event.getClass());
        if (type == null) {
            throw new IllegalArgumentException("No _type id mapping for event " + event.getClass()
                    + " — add it to MessagingConfig.TYPE_IDS");
        }
        outbox.enqueue(OutboxMessage.pending(dest.name(), dest.topic(), type, toJson(event), orderId));
    }

    private String toJson(Object event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize outbox event " + event.getClass(), e);
        }
    }
}
