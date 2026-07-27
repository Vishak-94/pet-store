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

    /**
     * Ship-to / bill-to contact info on the checkout intake request — the wire form of the
     * domain {@code ContactInfo} (and the messaging {@code PurchaseOrderEvent.ContactInfo}).
     * All fields nullable at the DTO layer; the storefront already enforces the legacy H7
     * required-field set before it calls, and the whole block is optional (the JSON checkout
     * path may not collect contacts), so it carries no bean-validation constraints here.
     */
    public record ContactInfoDto(String familyName, String givenName, String streetName1,
                                 String streetName2, String city, String state, String zipCode,
                                 String country, String telephone, String email) {
    }

    /**
     * Synchronous checkout intake payload (storefront → OPC {@code POST /api/orders/intake}) —
     * the REST replacement for the {@code PurchaseOrderEvent} that used to go on PurchaseOrderQueue.
     * Carries the same business fields; there is deliberately NO {@code status} (OPC assigns
     * PENDING then runs the auto-approval policy) and NO {@code created} (stamped server-side).
     *
     * <p>{@code orderId} is the storefront's server-minted synchronizer token — passing it (rather
     * than letting OPC mint one) preserves the double-submit idempotency guard: a refresh / replay
     * carries the same id and OPC's {@code order_id} primary-key dedup collapses it to a no-op.
     * {@code currency} is ISO 4217 (nullable → {@code USD} downstream). Prices are trusted from the
     * caller here exactly as the queue payload was — the storefront resolves them from catalog.
     */
    public record CheckoutRequest(@NotBlank String orderId, @NotBlank String userId, String emailId,
                                  String locale, String currency, double totalPrice,
                                  @NotEmpty @Valid List<LineDto> lines,
                                  ContactInfoDto shipTo, ContactInfoDto billTo) {
    }

    /**
     * Result of a successful intake: the persisted order id, its resolved workflow status
     * (PENDING when it needs manual approval, APPROVED when it cleared the auto-approval
     * threshold), and the stored total. Lets the storefront show the outcome without a
     * follow-up fetch. A duplicate submit returns the ALREADY-stored order's id + status.
     */
    public record CheckoutResponse(String orderId, String status, double totalPrice) {
    }

    /**
     * Full order detail (for the admin console). {@code currency} is the ISO 4217 code the
     * total is denominated in (kept distinct from {@code locale}, which is display/i18n); it is
     * the LAST component so the JSON is additive — an older client deserializes without it.
     */
    public record OrderView(String orderId, String userId, String emailId, String locale,
                            double totalPrice, String status, List<LineDto> lines, String currency) {
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
