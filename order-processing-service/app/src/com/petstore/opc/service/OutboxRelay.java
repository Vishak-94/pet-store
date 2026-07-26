package com.petstore.opc.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.petstore.messaging.Destination;
import com.petstore.messaging.MessagePublisher;
import com.petstore.messaging.MessagingConfig;
import com.petstore.opc.repository.OutboxMessage;
import com.petstore.opc.repository.OutboxStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Polls the transactional {@link OutboxStore} and publishes unsent events to JMS via
 * the shared {@link MessagePublisher} — the relay half of the outbox pattern. The
 * gateways enqueue rows in the business transaction ({@link OutboxWriter}); this drains
 * them just after commit, so a rolled-back order status never emits and a committed one
 * always eventually does.
 *
 * <p>Delivery is <b>at-least-once</b>: a crash between the broker send and the
 * {@code published_at} stamp re-sends the row on the next poll. That is safe because the
 * stored payload carries a fixed {@code EventMeta.eventId} and the OPC's own consumers are
 * idempotent (OPC invariant #4). A row that keeps failing is parked once its attempt count
 * reaches {@link #maxAttempts} (poison-message guard) — it stops being retried but is left
 * in the table for inspection rather than dropped.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** Matches MessagingConfig's converter mapper so stored JSON round-trips identically. */
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final OutboxStore outbox;
    private final MessagePublisher publisher;
    private final int batchSize;
    private final int maxAttempts;

    public OutboxRelay(OutboxStore outbox, MessagePublisher publisher,
                       @Value("${opc.outbox.batch-size:100}") int batchSize,
                       @Value("${opc.outbox.max-attempts:10}") int maxAttempts) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    /**
     * Publish a batch of unsent events, oldest first. Each row is published and stamped
     * independently, so one poison row can't block the rest of the backlog.
     */
    @Scheduled(fixedDelayString = "${opc.outbox.poll-ms:1000}")
    public void publishPending() {
        List<OutboxMessage> batch = outbox.fetchUnpublished(batchSize, maxAttempts);
        for (OutboxMessage message : batch) {
            publishOne(message);
        }
    }

    private void publishOne(OutboxMessage message) {
        try {
            Object event = mapper.readValue(message.payload(), eventClass(message.eventType()));
            publisher.publish(new Destination(message.destination(), message.topic()), event);
            outbox.markPublished(message.id());
            log.debug("Outbox {} published {} to {}", message.id(), message.eventType(), message.destination());
        } catch (Exception e) {
            int attempts = outbox.recordFailure(message.id());
            if (attempts >= maxAttempts) {
                // Parked: this row has exhausted its retries and fetchUnpublished will now skip it.
                // Log at ERROR (once, on the poll that trips the threshold) so a permanently-failing
                // producer-side event is operator-visible — the outbox has no DLQ, this WARN/ERROR is
                // its equivalent of the broker-side dead-letter signal (see DlqListener for the broker side).
                log.error("Outbox {} PARKED after {} failed attempts — {} → {} will NOT be retried; "
                                + "row left in table for inspection. Last error: {}",
                        message.id(), attempts, message.eventType(), message.destination(), e.toString());
            } else {
                log.warn("Outbox {} publish failed for {} → {} (attempt {}/{}, will retry): {}",
                        message.id(), message.eventType(), message.destination(), attempts, maxAttempts, e.toString());
            }
        }
    }

    /** Map the stored {@code _type} id back to the event class via the single routing map. */
    private static Class<?> eventClass(String eventType) {
        Class<?> type = MessagingConfig.TYPE_IDS.get(eventType);
        if (type == null) {
            throw new IllegalStateException("Unknown outbox event type '" + eventType
                    + "' — not in MessagingConfig.TYPE_IDS");
        }
        return type;
    }
}
