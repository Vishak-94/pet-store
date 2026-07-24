package com.petstore.opc.client;

import java.util.List;

/** Wire DTOs for the order-processing admin facade. */
public final class OrderDtos {

    private OrderDtos() {
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
}
