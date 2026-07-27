package com.petstore.opc.repository;

import com.petstore.opc.domain.OrderStatus;
import com.petstore.opc.domain.SalesReport;
import com.petstore.opc.domain.WarehouseOrder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Port for the warehouse's order read-model + workflow status (owned here). */
public interface OrderStore {

    /** Store an order received from checkout with its initial status. */
    WarehouseOrder save(WarehouseOrder order);

    Optional<WarehouseOrder> findById(String orderId);

    /**
     * Move an order to {@code status}, enforcing the {@link OrderStatus} lifecycle at the store
     * chokepoint: a terminal order can never be reversed (e.g. COMPLETED → APPROVED). A same-status
     * write is an idempotent no-op; any other transition must satisfy {@link OrderStatus#canGoTo},
     * otherwise an {@link IllegalStateException} is thrown (surfaced as 409). No-op if no such order.
     */
    void updateStatus(String orderId, OrderStatus status);

    Optional<OrderStatus> statusOf(String orderId);

    List<String> orderIdsByStatus(OrderStatus status);

    /** Every order, most-recently-received first (for the admin all-orders overview). */
    List<WarehouseOrder> findAllByCreatedDesc();

    /**
     * Aggregate revenue (Σ qty·unitPrice) + quantity (Σ qty) for orders received in
     * [start, end]. When {@code categoryId} is null the report is grouped by category;
     * otherwise it is grouped by item within that category. Legacy {@code getChartInfo}.
     */
    SalesReport aggregateSales(Instant start, Instant end, String categoryId);
}
