package com.petstore.warehouse.messaging;

import com.petstore.messaging.events.InvoiceEvent;
import com.petstore.warehouse.domain.OrderStatus;
import com.petstore.warehouse.repository.OrderStore;
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

    public InvoiceListener(OrderStore orders) {
        this.orders = orders;
    }

    @JmsListener(destination = "InvoiceTopic", containerFactory = "topicFactory")
    @Transactional
    public void onInvoice(InvoiceEvent invoice) {
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
        } else if (!invoice.shipped()) {
            log.info("Order {} backordered by inventory — staying {}", invoice.orderId(), status);
        }
    }
}
