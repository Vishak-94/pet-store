package com.petstore.inventory.messaging;

import com.petstore.messaging.Correlation;
import com.petstore.messaging.Destinations;
import com.petstore.messaging.Events;
import com.petstore.messaging.MessagePublisher;
import com.petstore.messaging.events.InvoiceEvent;
import com.petstore.messaging.events.OrderApprovedEvent;
import com.petstore.inventory.service.FulfilmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@link OrderApprovedEvent}s from the ApprovedOrderQueue (a QUEUE),
 * fulfils from inventory, then publishes an {@link InvoiceEvent} to the
 * InvoiceTopic (a TOPIC — fans out to warehouse + notification-service + …).
 * This is the legacy supplier.ear SupplierOrderMDB: "fulfil and, if shipped, send
 * back the invoice" — legacy sent it to InvoiceTopic, restored here.
 */
@Component
public class OrderApprovedListener {

    private static final Logger log = LoggerFactory.getLogger(OrderApprovedListener.class);

    private final FulfilmentService fulfilment;
    private final MessagePublisher publisher;

    public OrderApprovedListener(FulfilmentService fulfilment, MessagePublisher publisher) {
        this.fulfilment = fulfilment;
        this.publisher = publisher;
    }

    @JmsListener(destination = Destinations.APPROVED_ORDER_NAME, containerFactory = "queueFactory")
    public void onApprovedOrder(OrderApprovedEvent order) {
        // Adopt the approved-order's correlation id so the invoice we publish (and the logs here)
        // stay on the original checkout's trace end-to-end.
        Correlation.set(order.meta() == null ? null : order.meta().correlationId());
        try {
            log.info("Received approved order {} for fulfilment ({} lines)",
                    order.orderId(), order.lines() == null ? 0 : order.lines().size());
            boolean shipped;
            try {
                shipped = fulfilment.fulfil(order);
            } catch (FulfilmentService.BackorderException e) {
                // EXPECTED business outcome — a line lost the stock race. Nothing was
                // decremented (the tx rolled back); report shipped=false and ACK the message.
                log.info("Order {} not shipped: {}", order.orderId(), e.getMessage());
                shipped = false;
            }
            // Any OTHER RuntimeException (lock timeout, DB/broker failure) intentionally
            // propagates: fulfilment did not complete, so we must NOT publish a false
            // invoice or ACK the message — let JMS redeliver it (consumer is idempotent).
            double total = order.lines() == null ? 0d : order.lines().stream()
                    .mapToDouble(l -> l.unitPrice() * l.quantity()).sum();
            // Publish to the TOPIC — every subscriber (warehouse, notifications, …) gets a copy.
            // Carry userId + emailId through so notification-service can email the customer.
            // Events.meta pulls the correlation id from the MDC we just set, carrying the trace forward.
            publisher.publish(Destinations.INVOICE,
                    new InvoiceEvent(Events.meta(InvoiceEvent.TYPE), order.orderId(),
                            order.userId(), order.emailId(), shipped, total));
            log.info("Order {} invoice published to {} (shipped={})",
                    order.orderId(), Destinations.INVOICE.name(), shipped);
        } finally {
            Correlation.clear();
        }
    }
}
