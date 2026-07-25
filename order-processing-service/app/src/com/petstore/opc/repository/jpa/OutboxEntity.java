package com.petstore.opc.repository.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA row of the transactional outbox (see {@code V2__outbox.sql}). An outbound
 * event is inserted here in the same transaction as the order-status write, then a
 * scheduled relay publishes unpublished rows and stamps {@link #publishedAt}.
 * Package-private with field access, matching {@link WarehouseOrderEntity}.
 */
@Entity
@Table(name = "outbox")
class OutboxEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    Long id;

    @Column(name = "destination") String destination;
    @Column(name = "is_topic") boolean topic;
    @Column(name = "event_type") String eventType;

    /** The event serialized as JSON (same Jackson mapper the JMS converter uses). */
    @Lob
    @Column(name = "payload")
    String payload;

    @Column(name = "order_id") String orderId;
    @Column(name = "created_at") Instant createdAt;

    /** {@code null} until the relay has published it; set = delivered. */
    @Column(name = "published_at") Instant publishedAt;

    /** Publish attempts; parked as a poison row once it hits the relay's cap. */
    @Column(name = "attempts") int attempts;
}
