package com.petstore.notification;

import com.petstore.messaging.Destinations;
import com.petstore.messaging.events.OrderStatusEvent;
import com.petstore.notification.mail.Email;
import com.petstore.notification.mail.MailSender;
import com.petstore.notification.mail.OrderMailComposer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

/**
 * Subscribes to the OrderStatusTopic (pub/sub) and emails the customer on
 * approval/denial/completion — the modern equivalent of the legacy
 * customer-relations {@code MailOrderApprovalMDB} ("Order Status: …") and
 * {@code MailCompletedOrderMDB} ("Order COMPLETED: …"). A separate subscriber
 * from {@link InvoiceNotificationListener} (which handles the shipped/backorder
 * invoice email), restoring all three legacy customer mail triggers.
 */
@Component
public class OrderStatusNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(OrderStatusNotificationListener.class);

    private final OrderMailComposer composer;
    private final MailSender mailSender;

    public OrderStatusNotificationListener(OrderMailComposer composer, MailSender mailSender) {
        this.composer = composer;
        this.mailSender = mailSender;
    }

    // Phase 4c: durable-subscription name for OrderStatusTopic. notification-service is currently
    // the only OrderStatusTopic subscriber, but the name is still unique fleet-wide so a future
    // second subscriber (durable+shared factory keys the subscription by NAME, not clientId) gets
    // its own copy. Durable = a status change emitted while this service is down is retained and
    // delivered on reconnect, so approval/denial/completion emails survive a restart.
    @JmsListener(destination = Destinations.ORDER_STATUS_NAME, containerFactory = "topicFactory",
            subscription = "notification-order-status")
    public void onStatus(OrderStatusEvent event) {
        log.info("Order status {} for order {} (correlationId={}) — notifying customer",
                event.status(), event.orderId(), event.meta().correlationId());
        Email email = composer.fromStatus(event);
        mailSender.send(email);
    }
}
