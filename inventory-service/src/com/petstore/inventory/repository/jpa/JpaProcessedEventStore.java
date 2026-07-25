package com.petstore.inventory.repository.jpa;

import com.petstore.inventory.repository.ProcessedEventStore;
import org.springframework.stereotype.Repository;

/**
 * JPA adapter for the {@link ProcessedEventStore} port — the durable dedup ledger
 * backing idempotent consumption. JMS is at-least-once, so the same
 * {@code OrderApprovedEvent} can be redelivered; this store records each processed
 * eventId (primary key) so {@code FulfilmentService} can skip a replay. Both methods
 * null-guard defensively even though callers never pass null.
 */
@Repository
public class JpaProcessedEventStore implements ProcessedEventStore {

    private final ProcessedEventJpaRepository jpa;

    JpaProcessedEventStore(ProcessedEventJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public boolean isProcessed(String eventId) {
        return eventId != null && jpa.existsById(eventId);
    }

    @Override
    public void markProcessed(String eventId) {
        // No caller passes null (guarded in FulfilmentService), but stay defensive.
        if (eventId != null) {
            jpa.save(new ProcessedEventEntity(eventId));
        }
    }
}
