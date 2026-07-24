package com.petstore.opc.domain;

import java.time.Instant;
import java.util.List;

/**
 * Warehouse's view of an order + its workflow status. Framework-free.
 * {@code created} is the order-received timestamp (legacy PurchaseOrder poDate),
 * used for date-range sales aggregation (see {@link SalesReport}).
 */
public record WarehouseOrder(String orderId, String userId, String emailId, String locale,
                             double totalPrice, OrderStatus status, List<OrderLine> lines,
                             ContactInfo shipTo, ContactInfo billTo, Instant created) {
}
