package com.petstore.opc.repository.jpa;

import com.petstore.opc.repository.OutboxMessage;
import com.petstore.opc.repository.OutboxStore;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * JPA adapter for the {@link OutboxStore} port — the persistence side of the
 * transactional outbox. Maps between the framework-free {@link OutboxMessage} and
 * {@link OutboxEntity} and delegates to {@link OutboxJpaRepository}, so the service
 * layer never sees JPA (mirrors {@link JpaOrderStore}).
 */
@Repository
public class JpaOutboxStore implements OutboxStore {

    private final OutboxJpaRepository jpa;

    JpaOutboxStore(OutboxJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void enqueue(OutboxMessage message) {
        OutboxEntity e = new OutboxEntity();
        e.destination = message.destination();
        e.topic = message.topic();
        e.eventType = message.eventType();
        e.payload = message.payload();
        e.orderId = message.orderId();
        e.createdAt = Instant.now();
        e.publishedAt = null;
        e.attempts = 0;
        jpa.save(e);
    }

    @Override
    public List<OutboxMessage> fetchUnpublished(int limit, int maxAttempts) {
        return jpa.findByPublishedAtIsNullAndAttemptsLessThanOrderByIdAsc(maxAttempts, PageRequest.of(0, limit))
                .stream()
                .map(this::toMessage)
                .toList();
    }

    // The relay calls these outside any ambient transaction, and @Modifying JPQL updates
    // need one — so each stamp is its own short transaction (rows are updated independently).
    @Override
    @Transactional
    public void markPublished(long id) {
        jpa.markPublished(id, Instant.now());
    }

    @Override
    @Transactional
    public int recordFailure(long id) {
        jpa.incrementAttempts(id);
        // Re-read the freshly-incremented counter so the relay can tell when this row has just
        // reached its park threshold. clearAutomatically on the @Modifying update evicts any stale
        // cached entity, so this findById sees the committed value.
        return jpa.findById(id).map(e -> e.attempts).orElse(0);
    }

    private OutboxMessage toMessage(OutboxEntity e) {
        return new OutboxMessage(e.id, e.destination, e.topic, e.eventType, e.payload, e.orderId);
    }
}
