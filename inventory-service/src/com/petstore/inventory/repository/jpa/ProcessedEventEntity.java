package com.petstore.inventory.repository.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One row per fully-applied order-approved event. The primary key is the
 * {@code EventMeta.eventId}, so a second insert of the same id fails the PK
 * constraint — the dedup backstop even if the read-then-check races.
 */
@Entity
@Table(name = "processed_event")
class ProcessedEventEntity {

    @Id
    @Column(name = "event_id")
    String eventId;

    protected ProcessedEventEntity() {
    }

    ProcessedEventEntity(String eventId) {
        this.eventId = eventId;
    }
}
