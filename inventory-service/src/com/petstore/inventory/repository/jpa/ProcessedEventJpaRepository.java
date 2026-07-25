package com.petstore.inventory.repository.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository over the processed-event ledger (keyed by eventId). Plain
 * CRUD is enough: {@code existsById} answers "seen before?" and {@code save} records
 * a newly-processed event — the two operations {@link JpaProcessedEventStore} needs
 * for at-least-once dedup.
 */
interface ProcessedEventJpaRepository extends JpaRepository<ProcessedEventEntity, String> {
}
