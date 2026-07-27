package com.petstore.opc.service;

import com.petstore.messaging.Destinations;
import com.petstore.messaging.Events;
import com.petstore.messaging.events.OrderStatusEvent;
import com.petstore.opc.domain.OrderStatus;
import com.petstore.opc.domain.WarehouseOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Broadcasts a customer-facing {@link OrderStatusEvent} to the OrderStatusTopic
 * whenever an order changes to a state the customer is emailed about
 * (APPROVED/DENIED/COMPLETED). Restores the legacy customer-relations mail
 * triggers ({@code MailOrderApprovalMDB} / {@code MailCompletedOrderMDB}) that the
 * invoice event did not cover.
 *
 * <p>Appends the event to the transactional outbox ({@link OutboxWriter}) in the
 * caller's transaction (same pattern as {@link ApprovalGateway}), so a rolled-back
 * status change never emails the customer and a committed one always eventually
 * does — the {@link OutboxRelay} publishes it just after commit.
 */
@Component
public class OrderStatusGateway {

    private static final Logger log = LoggerFactory.getLogger(OrderStatusGateway.class);

    private final OutboxWriter outbox;

    public OrderStatusGateway(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    /**
     * Announce a status change for an order we have the full record for by enqueueing an
     * {@link OrderStatusEvent} to the transactional outbox, keyed by order id. Must be
     * called inside the business transaction that made the change so the event row commits
     * atomically with it; the {@link OutboxRelay} publishes it to OrderStatusTopic just
     * after commit (at-least-once → notification-service emails the customer). Does not
     * publish to JMS itself.
     *
     * @param order     the order the change applies to (id/user/email/total go into the event)
     * @param newStatus the new status the customer is being notified of
     *                  (APPROVED / DENIED / COMPLETED)
     */
    public void announce(WarehouseOrder order, OrderStatus newStatus) {
        OrderStatusEvent event = new OrderStatusEvent(
                Events.meta(OrderStatusEvent.TYPE),
                order.orderId(), order.userId(), order.emailId(),
                newStatus.name(), order.totalPrice());
        outbox.enqueue(Destinations.ORDER_STATUS, event, order.orderId());
        log.info("Order {} status {} queued in outbox for customer-notification", order.orderId(), newStatus);
    }
}
