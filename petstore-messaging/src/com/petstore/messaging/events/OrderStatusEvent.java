package com.petstore.messaging.events;

import com.petstore.messaging.EventMeta;

/**
 * A customer-facing order status change broadcast to the OrderStatusTopic
 * (warehouse → whoever cares). Restores the legacy customer-relations mail
 * triggers that the invoice event alone did not cover:
 * <ul>
 *   <li>{@code APPROVED}/{@code DENIED} → legacy {@code MailOrderApprovalMDB}
 *       ("Java Pet Store Order Status: …");</li>
 *   <li>{@code COMPLETED} → legacy {@code MailCompletedOrderMDB}
 *       ("Java Pet Store Order COMPLETED: …").</li>
 * </ul>
 *
 * <p>A TOPIC event (pub/sub) so notification-service emails the customer while
 * any future subscriber (analytics/audit) can react independently. {@code status}
 * carries the {@link com.petstore.messaging.events} peer name of the new state
 * ("APPROVED"/"DENIED"/"COMPLETED").
 */
public record OrderStatusEvent(
        EventMeta meta,
        String orderId,
        String userId,
        String emailId,
        String status,
        double totalPrice) {

    public static final String TYPE = "OrderStatus";
}
