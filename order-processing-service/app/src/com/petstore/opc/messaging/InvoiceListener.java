package com.petstore.opc.messaging;

import com.petstore.messaging.Correlation;
import com.petstore.messaging.Destinations;
import com.petstore.messaging.events.InvoiceEvent;
import com.petstore.opc.domain.OrderStatus;
import com.petstore.opc.domain.WarehouseOrder;
import com.petstore.opc.repository.OrderStore;
import com.petstore.opc.service.OrderStatusGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Subscribes to the InvoiceTopic (a TOPIC — pub/sub) and completes the order.
 * Because it's a topic, warehouse is ONE of several independent subscribers
 * (notification-service also receives the same invoice). A shipped invoice moves
 * APPROVED → COMPLETED; a backorder leaves it APPROVED. Idempotent.
 */
@Component
public class InvoiceListener {

    private static final Logger log = LoggerFactory.getLogger(InvoiceListener.class);

    private final OrderStore orders;
    private final OrderStatusGateway statusGateway;

    public InvoiceListener(OrderStore orders, OrderStatusGateway statusGateway) {
        this.orders = orders;
        this.statusGateway = statusGateway;
    }

    // Phase 4c: a unique durable-subscription name. The topicFactory is durable+shared, so this
    // name (NOT a connection clientId) identifies the subscription. It must be UNIQUE per logical
    // subscriber: notification-service also subscribes to InvoiceTopic but under a DIFFERENT name
    // ("notification-invoice"), so each service keeps its own independent copy of every invoice
    // (topic fan-out). Two listeners sharing ONE name would compete for messages instead.
    @JmsListener(destination = Destinations.INVOICE_NAME, containerFactory = "topicFactory",
            subscription = "opc-invoice")
    @Transactional
    public void onInvoice(InvoiceEvent invoice) {
        // Carry the invoice's correlation id so the COMPLETED status event we may re-publish (and
        // the logs below) stay on the original checkout's trace.
        Correlation.set(invoice.meta() == null ? null : invoice.meta().correlationId());
        try {
            log.info("Received invoice for order {} (shipped={})", invoice.orderId(), invoice.shipped());
            OrderStatus status = orders.statusOf(invoice.orderId()).orElse(null);
            if (status == null) {
                log.warn("Invoice for unknown order {} — ignoring", invoice.orderId());
                return;
            }
            if (status == OrderStatus.COMPLETED) {
                return;   // idempotent
            }
            if (invoice.shipped() && status.canGoTo(OrderStatus.COMPLETED)) {
                orders.updateStatus(invoice.orderId(), OrderStatus.COMPLETED);
                log.info("Order {} → COMPLETED (invoice shipped)", invoice.orderId());
                orders.findById(invoice.orderId()).ifPresent(order ->
                        statusGateway.announce(order, OrderStatus.COMPLETED));   // → customer "Order COMPLETED" email (legacy MailCompletedOrderMDB)
            } else if (!invoice.shipped()) {
                log.info("Order {} backordered by inventory — staying {}", invoice.orderId(), status);
            }
        } finally {
            Correlation.clear();
        }
    }
}
