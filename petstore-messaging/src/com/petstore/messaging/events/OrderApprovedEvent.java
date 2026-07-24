package com.petstore.messaging.events;

import com.petstore.messaging.EventMeta;

import java.util.List;

/**
 * An approved order to be fulfilled (warehouse → inventory over the
 * ApprovedOrderQueue).
 */
public record OrderApprovedEvent(
        EventMeta meta,
        String orderId,
        String userId,
        String emailId,
        String locale,
        List<Line> lines) {

    public record Line(String itemId, String productId, String categoryId,
                       int quantity, double unitPrice) {
    }

    public static final String TYPE = "OrderApproved";
}
