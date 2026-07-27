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
    /**
     * Handle one invoice event off the InvoiceTopic: compose the customer email and "send" it
     * (the default adapter logs it). {@code shipped=true} yields an "Order Shipped" mail;
     * {@code shipped=false} (backorder) yields an "Order Delayed" mail. Idempotent — a
     * redelivered invoice simply re-sends the same email.
     *
     * <p>Example inbound event (InvoiceTopic):
     * <pre>{@code
     * {
     *   "meta": { "eventId": "evt-b71", "type": "Invoice", "correlationId": "corr-42" },
     *   "orderId": "1001",
     *   "userId": "j2ee",
     *   "emailId": "buyer@example.com",
     *   "shipped": true,
     *   "totalPrice": 25.00
     * }
     * }</pre>
     * Produces an email to {@code buyer@example.com} with subject
     * {@code "Java Pet Store Order Shipped: 1001"} (or {@code "… Order Delayed: 1001"} when
     * {@code shipped=false}).
     *
     * @param invoice the invoice event to notify the customer about
     */
    @JmsListener(destination = Destinations.INVOICE_NAME, containerFactory = "topicFactory",
            subscription = "notification-invoice")
    public void onInvoice(InvoiceEvent invoice) {
        log.info("Invoice received for order {} (shipped={}, correlationId={}) — notifying customer",
                invoice.orderId(), invoice.shipped(), invoice.meta().correlationId());
        Email email = composer.fromInvoice(invoice);
        mailSender.send(email);
    }
}
