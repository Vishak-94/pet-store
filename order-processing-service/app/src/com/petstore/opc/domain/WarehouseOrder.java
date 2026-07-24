package com.petstore.opc.domain;

import java.util.List;

/** Warehouse's view of an order + its workflow status. Framework-free. */
public record WarehouseOrder(String orderId, String userId, String emailId, String locale,
                             double totalPrice, OrderStatus status, List<OrderLine> lines) {
}
