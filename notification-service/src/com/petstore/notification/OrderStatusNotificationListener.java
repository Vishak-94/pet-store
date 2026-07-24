package com.petstore.notification;

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

    @JmsListener(destination = "OrderStatusTopic", containerFactory = "topicFactory")
    public void onStatus(OrderStatusEvent event) {
        log.info("Order status {} for order {} (correlationId={}) — notifying customer",
                event.status(), event.orderId(), event.meta().correlationId());
        Email email = composer.fromStatus(event);
        mailSender.send(email);
    }
}
