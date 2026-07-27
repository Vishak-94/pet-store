package com.petstore.inventory.repository.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One row per fully-fulfilled (shipped) order. The primary key is the {@code orderId},
 * so a second insert of the same id fails the PK constraint — the dedup backstop even if
 * the read-then-check races. Keyed by order (ships at most once), not message eventId, so
 * a re-driven {@code OrderApprovedEvent} (fresh eventId) for an already-shipped order is
 * still skipped. See {@link com.petstore.inventory.repository.FulfilledOrderStore}.
 */
@Entity
@Table(name = "fulfilled_order")
class FulfilledOrderEntity {

    @Id
    @Column(name = "order_id")
    String orderId;

    protected FulfilledOrderEntity() {
    }

    FulfilledOrderEntity(String orderId) {
        this.orderId = orderId;
    }
}
