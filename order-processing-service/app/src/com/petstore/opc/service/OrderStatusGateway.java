package com.petstore.opc.service;

import com.petstore.messaging.Destinations;
import com.petstore.messaging.Events;
import com.petstore.messaging.MessagePublisher;
import com.petstore.messaging.events.OrderStatusEvent;
import com.petstore.opc.domain.OrderStatus;
import com.petstore.opc.domain.WarehouseOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Broadcasts a customer-facing {@link OrderStatusEvent} to the OrderStatusTopic
 * whenever an order changes to a state the customer is emailed about
 * (APPROVED/DENIED/COMPLETED). Restores the legacy customer-relations mail
 * triggers ({@code MailOrderApprovalMDB} / {@code MailCompletedOrderMDB}) that the
 * invoice event did not cover.
 *
 * <p>Publishes AFTER commit (same pattern as {@link ApprovalGateway}) so a
 * rolled-back status change never emails the customer.
 */
@Component
public class OrderStatusGateway {

    private static final Logger log = LoggerFactory.getLogger(OrderStatusGateway.class);

    private final MessagePublisher publisher;

    public OrderStatusGateway(MessagePublisher publisher) {
        this.publisher = publisher;
    }

    /** Announce a status change for an order we have the full record for. */
    public void announce(WarehouseOrder order, OrderStatus newStatus) {
        publishAfterCommit(new OrderStatusEvent(
                Events.meta(OrderStatusEvent.TYPE),
                order.orderId(), order.userId(), order.emailId(),
                newStatus.name(), order.totalPrice()));
    }

    private void publishAfterCommit(OrderStatusEvent event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send(event);
                }
            });
        } else {
            send(event);
        }
    }

    private void send(OrderStatusEvent event) {
        publisher.publish(Destinations.ORDER_STATUS, event);
        log.info("Order {} status {} announced to customer-notification", event.orderId(), event.status());
    }
}
