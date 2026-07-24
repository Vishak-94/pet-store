package com.petstore.messaging.events;

import com.petstore.messaging.EventMeta;

import java.util.List;

/**
 * A new purchase order placed at checkout (monolith → warehouse over the
 * PurchaseOrderQueue). Envelope metadata in {@code meta}; the order in the rest.
 */
public record PurchaseOrderEvent(
        EventMeta meta,
        String orderId,
        String userId,
        String emailId,
        String locale,
        double totalPrice,
        List<Line> lines) {

    public record Line(String itemId, String productId, String categoryId,
                       int quantity, double unitPrice) {
    }

    /** The JMS type-id / logical event type. */
    public static final String TYPE = "PurchaseOrder";
}
