package com.petstore.opc.domain;

import java.time.Instant;
import java.util.List;

/**
 * Warehouse's view of an order + its workflow status. Framework-free.
 * {@code created} is the order-received timestamp (legacy PurchaseOrder poDate),
 * used for date-range sales aggregation (see {@link SalesReport}).
 * {@code currency} is the ISO 4217 code ({@code USD}/{@code JPY}) the total is
 * denominated in; {@link ApprovalPolicy} keys the auto-approval threshold on it.
 */
public record WarehouseOrder(String orderId, String userId, String emailId, String locale,
                             String currency, double totalPrice, OrderStatus status,
                             List<OrderLine> lines, ContactInfo shipTo, ContactInfo billTo,
                             Instant created) {
}
