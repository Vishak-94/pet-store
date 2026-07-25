package com.petstore.notification;

import com.petstore.messaging.Destinations;
import com.petstore.messaging.events.InvoiceEvent;
import com.petstore.notification.mail.Email;
import com.petstore.notification.mail.MailSender;
import com.petstore.notification.mail.OrderMailComposer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

/**
 * Subscribes to the InvoiceTopic (pub/sub) and emails the customer — the modern
 * equivalent of the legacy customer-relations {@code MailInvoiceMDB}: receive the
 * invoice event, compose an "Order Shipped" mail, and send it. A separate topic
 * subscriber from admin-office-service's InvoiceListener (both get their own copy).
 */
@Component
public class InvoiceNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(InvoiceNotificationListener.class);

    private final OrderMailComposer composer;
    private final MailSender mailSender;

    public InvoiceNotificationListener(OrderMailComposer composer, MailSender mailSender) {
        this.composer = composer;
        this.mailSender = mailSender;
    }

    // Phase 4c: durable-subscription name, UNIQUE from OPC's InvoiceTopic subscriber ("opc-invoice").
    // Distinct names = two independent durable subscriptions on the same topic, so notification and
    // OPC each receive every invoice (pub/sub fan-out). Durable = an invoice sent while this service
    // is restarting is retained by the broker and delivered on reconnect, so the "Order Shipped"
    // email is never silently dropped.
    @JmsListener(destination = Destinations.INVOICE_NAME, containerFactory = "topicFactory",
            subscription = "notification-invoice")
    public void onInvoice(InvoiceEvent invoice) {
        log.info("Invoice received for order {} (shipped={}, correlationId={}) — notifying customer",
                invoice.orderId(), invoice.shipped(), invoice.meta().correlationId());
        Email email = composer.fromInvoice(invoice);
        mailSender.send(email);
    }
}
