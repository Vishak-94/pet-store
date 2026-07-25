package com.petstore.opc.repository.jpa;

import com.petstore.opc.domain.OrderStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/**
 * Spring Data repository over the {@code warehouse_order} aggregate (keyed by
 * {@code orderId}). Beyond inherited CRUD it adds the status filter, the newest-first
 * overview listing, and the two GROUP BY sales aggregations (by category / by item)
 * that back the admin sales report — the legacy {@code getChartInfo}.
 */
interface WarehouseOrderJpaRepository extends JpaRepository<WarehouseOrderEntity, String> {

    /** All orders in a given workflow status (e.g. the PENDING approval queue). */
    List<WarehouseOrderEntity> findByStatus(OrderStatus status);

    /** Every order sorted by received-time descending (admin all-orders overview). */
    List<WarehouseOrderEntity> findAllByOrderByCreatedDesc();

    /**
     * Revenue (Σ qty·unitPrice) + quantity (Σ qty) grouped by CATEGORY over orders
     * received in [start, end]. Legacy {@code getChartInfo(..., categ == null)}.
     * Rows: {@code [categoryId, revenue(Double), quantity(Long)]}.
     */
    @Query("""
            SELECT l.categoryId, SUM(l.quantity * l.unitPrice), SUM(l.quantity)
            FROM WarehouseOrderEntity o JOIN o.lines l
            WHERE o.created BETWEEN :start AND :end
            GROUP BY l.categoryId""")
    List<Object[]> aggregateByCategory(@Param("start") Instant start, @Param("end") Instant end);

    /**
     * Revenue + quantity grouped by ITEM within a single category over orders
     * received in [start, end]. Legacy {@code getChartInfo(..., categ != null)}.
     * Rows: {@code [itemId, revenue(Double), quantity(Long)]}.
     */
    @Query("""
            SELECT l.itemId, SUM(l.quantity * l.unitPrice), SUM(l.quantity)
            FROM WarehouseOrderEntity o JOIN o.lines l
            WHERE o.created BETWEEN :start AND :end AND l.categoryId = :categoryId
            GROUP BY l.itemId""")
    List<Object[]> aggregateByItem(@Param("start") Instant start, @Param("end") Instant end,
                                   @Param("categoryId") String categoryId);
}

/**
 * Spring Data repository over the transactional {@code outbox} table. Beyond the
 * inherited CRUD (the enqueue insert) it adds the relay's oldest-first scan of the
 * unpublished backlog and the two bulk state updates (mark delivered / bump the
 * attempt counter). Package-private, alongside {@link WarehouseOrderJpaRepository}.
 */
interface OutboxJpaRepository extends JpaRepository<OutboxEntity, Long> {

    /**
     * Oldest-first batch of not-yet-published rows ({@code published_at IS NULL}) that
     * are still under the attempt cap, capped via the {@link Pageable}. Rows at/over the
     * cap are parked (poison messages) and skipped. Backed by {@code ix_outbox_unpublished}.
     */
    List<OutboxEntity> findByPublishedAtIsNullAndAttemptsLessThanOrderByIdAsc(int maxAttempts, Pageable pageable);

    /** Stamp a row delivered. Bulk update so the relay needn't reload the entity. */
    @Modifying
    @Query("UPDATE OutboxEntity o SET o.publishedAt = :publishedAt WHERE o.id = :id")
    void markPublished(@Param("id") long id, @Param("publishedAt") Instant publishedAt);

    /** Increment the attempt counter for a row whose publish failed. */
    @Modifying
    @Query("UPDATE OutboxEntity o SET o.attempts = o.attempts + 1 WHERE o.id = :id")
    void incrementAttempts(@Param("id") long id);
}
