package com.petstore.opc.domain;

/**
 * One requested order status change in a batch approval — the migrated form of the
 * legacy {@code ChangedOrder(orderId, orderStatus)} carried inside {@code OrderApproval}.
 * Framework-free.
 */
public record OrderStatusChange(String orderId, OrderStatus newStatus) {
}
