package com.petstore.warehouse.service;

import com.petstore.messaging.Destinations;
import com.petstore.messaging.Events;
import com.petstore.messaging.MessagePublisher;
import com.petstore.messaging.events.OrderApprovedEvent;
import com.petstore.warehouse.domain.OrderLine;
import com.petstore.warehouse.domain.WarehouseOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Publishes an {@link OrderApprovedEvent} to inventory-service over the
 * ApprovedOrderQueue (via the shared {@link MessagePublisher}). Publishes AFTER
 * commit so a rolled-back approval never dispatches for fulfilment.
 */
@Component
public class ApprovalGateway {

    private static final Logger log = LoggerFactory.getLogger(ApprovalGateway.class);

    private final MessagePublisher publisher;

    public ApprovalGateway(MessagePublisher publisher) {
        this.publisher = publisher;
    }

    public void dispatchForFulfilment(WarehouseOrder order) {
        OrderApprovedEvent event = new OrderApprovedEvent(
                Events.meta(OrderApprovedEvent.TYPE),
                order.orderId(), order.userId(), order.emailId(), order.locale(),
                order.lines().stream().map(this::toLine).toList());
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

    private void send(OrderApprovedEvent event) {
        publisher.publish(Destinations.APPROVED_ORDER, event);
        log.info("Order {} dispatched to inventory-service for fulfilment", event.orderId());
    }

    private OrderApprovedEvent.Line toLine(OrderLine l) {
        return new OrderApprovedEvent.Line(l.itemId(), l.productId(), l.categoryId(),
                l.quantity(), l.unitPrice());
    }
}
