package com.petstore.opc.repository.mongo;

import com.petstore.opc.domain.OrderStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/**
 * Spring Data MongoDB repositories over the {@code orders} and {@code outbox} collections —
 * the {@code mongo}-profile counterpart of {@code SpringDataRepositories} (the JPA repos).
 * Derived-query methods mirror the JPA ones so the adapters read almost identically; the
 * GROUP BY sales aggregation is NOT here (it needs {@code MongoTemplate}, in {@link MongoOrderStore}).
 * Package-private, alongside the documents.
 */
interface WarehouseOrderMongoRepository extends MongoRepository<WarehouseOrderDocument, String> {

    /** All orders in a given workflow status (e.g. the PENDING approval queue). */
    List<WarehouseOrderDocument> findByStatus(OrderStatus status);

    /** Every order sorted by received-time descending (admin all-orders overview). */
    List<WarehouseOrderDocument> findAllByOrderByCreatedDesc();
}

/**
 * Spring Data MongoDB repository over the transactional {@code outbox} collection. The relay's
 * oldest-first scan of the unpublished backlog under the attempt cap; the two state stamps
 * (mark delivered / bump attempts) are done in the adapter so it can read back the new count.
 */
interface OutboxMongoRepository extends MongoRepository<OutboxDocument, String> {

    /**
     * Oldest-first batch of not-yet-published documents ({@code publishedAt} is null) still under
     * the attempt cap, capped via the {@link Pageable}. Documents at/over the cap are parked
     * (poison messages) and skipped. Backed by the {@code {publishedAt:1, attempts:1}} index.
     */
    List<OutboxDocument> findByPublishedAtIsNullAndAttemptsLessThanOrderByCreatedAtAsc(int maxAttempts, Pageable pageable);
}
