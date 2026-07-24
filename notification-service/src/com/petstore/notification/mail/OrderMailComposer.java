package com.petstore.notification.mail;

import com.petstore.messaging.events.InvoiceEvent;
import org.springframework.stereotype.Component;

/**
 * Builds customer emails from order events — the composition half of the legacy
 * {@code MailInvoiceMDB} (which set subject "Java Pet Store Order Shipped: …" and
 * a body from the invoice). Composition is separated from sending so it's unit-
 * testable without any mail transport.
 */
@Component
public class OrderMailComposer {

    /** Legacy MailInvoiceMDB.MAIL_SUBJECT prefix. */
    private static final String SHIPPED_SUBJECT = "Java Pet Store Order Shipped: ";
    private static final String BACKORDER_SUBJECT = "Java Pet Store Order Delayed: ";

    /** Compose the "order shipped" (or backorder) email for an invoice event. */
    public Email fromInvoice(InvoiceEvent invoice) {
        String to = recipient(invoice);
        if (invoice.shipped()) {
            String body = """
                    Dear Customer,

                    Thank you for your order. Your order #%s has been shipped.
                    Order total: $%.2f

                    You can track your order in your Pet Store account.

                    — The Java Pet Store Team""".formatted(invoice.orderId(), invoice.totalPrice());
            return new Email(to, SHIPPED_SUBJECT + invoice.orderId(), body);
        }
        String body = """
                Dear Customer,

                We're sorry — some items in your order #%s are on backorder and
                your order is delayed. We'll notify you when it ships.

                — The Java Pet Store Team""".formatted(invoice.orderId());
        return new Email(to, BACKORDER_SUBJECT + invoice.orderId(), body);
    }

    private static String recipient(InvoiceEvent invoice) {
        if (invoice.emailId() != null && !invoice.emailId().isBlank()) {
            return invoice.emailId();
        }
        // Fallback if the order carried no email (legacy also tolerated missing addr).
        String who = invoice.userId() == null ? "customer" : invoice.userId();
        return who + "@petstore.invalid";
    }
}
