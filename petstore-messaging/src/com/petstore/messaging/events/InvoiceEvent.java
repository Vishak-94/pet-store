package com.petstore.messaging.events;

import com.petstore.messaging.EventMeta;

/**
 * The invoice/completion event published to the InvoiceTopic (inventory →
 * everyone). A TOPIC event: warehouse completes the order, notification-service
 * emails the customer, and any future subscriber (analytics/audit) can react —
 * each gets its own copy. Restores the legacy Pet Store InvoiceTopic.
 *
 * <p>{@code shipped=false} means stock was short (backorder) — the order is not
 * completed.
 */
public record InvoiceEvent(
        EventMeta meta,
        String orderId,
        String userId,
        String emailId,
        boolean shipped,
        double totalPrice) {

    public static final String TYPE = "Invoice";
}
