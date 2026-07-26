package com.petstore.opc.client;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.time.Instant;
import java.util.List;

/** Wire DTOs for the order-processing admin facade. */
public final class OrderDtos {

    private OrderDtos() {
    }

    /**
     * Lightweight order summary for the admin "All Orders" overview — one row per
     * order in a single call (no per-order fetch), carrying the workflow status and
     * the order-received timestamp so the console can list every order sorted by
     * recency. {@code created} is the PO poDate (see {@code WarehouseOrder.created}).
     */
    public record OrderSummaryDto(String orderId, String userId, double totalPrice,
                                  String status, Instant created, int lineCount) {
    }

    /** A single line of an order. */
    public record LineDto(String itemId, String productId, String categoryId,
                          int quantity, double unitPrice) {
    }

    /** Full order detail (for the admin console). */
    public record OrderView(String orderId, String userId, String emailId, String locale,
                            double totalPrice, String status, List<LineDto> lines) {
    }

    /** The result of a status query. */
    public record StatusView(String orderId, String status) {
    }

    /** A page of order ids for a status. */
    public record OrdersByStatus(String status, List<String> orderIds, int count) {
    }

    /** One requested status change in a batch approval (legacy {@code ChangedOrder}). */
    public record OrderStatusChangeDto(@NotBlank String orderId, @NotBlank String newStatus) {
    }

    /**
     * A batch of status changes applied atomically (legacy {@code OrderApproval}). At least one
     * change is required, and each is itself validated ({@code @Valid} cascades into the list) so
     * an empty batch or a blank orderId/newStatus is rejected as 400 rather than reaching the
     * service layer or {@code OrderStatus.valueOf} on a null.
     */
    public record OrderApprovalDto(@NotEmpty @Valid List<OrderStatusChangeDto> orders) {
    }

    /** One aggregation bucket of a sales report, keyed by category or item id. */
    public record SalesBucketDto(String key, double revenue, int quantity) {
    }

    /**
     * Aggregated sales over a date range (legacy {@code getChartInfo} result).
     * {@code groupBy} is {@code "category"} or {@code "item"}.
     */
    public record SalesReportDto(String groupBy, List<SalesBucketDto> buckets) {
    }
}
