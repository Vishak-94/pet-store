package com.petstore.opc.service;

import com.petstore.messaging.Destinations;
import com.petstore.messaging.Events;
import com.petstore.messaging.events.OrderApprovedEvent;
import com.petstore.opc.domain.OrderLine;
import com.petstore.opc.domain.WarehouseOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Dispatches an {@link OrderApprovedEvent} to inventory-service over the
 * ApprovedOrderQueue. Instead of publishing to JMS directly, it appends the event to
 * the transactional outbox ({@link OutboxWriter}) <em>in the caller's transaction</em>,
 * so the event and the order-status write commit or roll back atomically; the
 * {@link OutboxRelay} publishes it just after commit. This replaces the old after-commit
 * {@code TransactionSynchronization} publish, which could commit the status change but
 * lose the JMS send on a crash in the window between commit and send.
 */
@Component
public class ApprovalGateway {

    private static final Logger log = LoggerFactory.getLogger(ApprovalGateway.class);

    private final OutboxWriter outbox;

    public ApprovalGateway(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    /**
     * Enqueue an {@link OrderApprovedEvent} for inventory-service to the transactional
     * outbox, keyed by order id. Must be called inside the approving business transaction
     * so the event row commits atomically with the APPROVED status write; the
     * {@link OutboxRelay} publishes it to ApprovedOrderQueue just after commit
     * (at-least-once — the consumer is idempotent). Does not publish to JMS itself.
     *
     * @param order the just-approved order whose lines are copied into the event
     */
    public void dispatchForFulfilment(WarehouseOrder order) {
        OrderApprovedEvent event = new OrderApprovedEvent(
                Events.meta(OrderApprovedEvent.TYPE),
                order.orderId(), order.userId(), order.emailId(), order.locale(),
                order.lines().stream().map(this::toLine).toList());
        outbox.enqueue(Destinations.APPROVED_ORDER, event, order.orderId());
        log.info("Order {} queued in outbox for fulfilment dispatch to inventory-service", order.orderId());
    }

    private OrderApprovedEvent.Line toLine(OrderLine l) {
        return new OrderApprovedEvent.Line(l.itemId(), l.productId(), l.categoryId(),
                l.quantity(), l.unitPrice());
    }
}
