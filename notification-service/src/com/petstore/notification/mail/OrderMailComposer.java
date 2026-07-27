package com.petstore.notification.mail;

import com.petstore.messaging.events.InvoiceEvent;
import com.petstore.messaging.events.OrderStatusEvent;
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
    /** Legacy MailOrderApprovalMDB subject prefix (order approved/denied). */
    private static final String STATUS_SUBJECT = "Java Pet Store Order Status: ";
    /** Legacy MailCompletedOrderMDB subject prefix (order fully shipped/completed). */
    private static final String COMPLETED_SUBJECT = "Java Pet Store Order COMPLETED: ";

    /**
     * Order-status values received on the wire (from {@code OrderStatusEvent.status}).
     * These mirror the OPC {@code OrderStatus} enum names — kept as constants here (rather
     * than depending on the OPC domain) since this leaf service only reads the string; they
     * are a shared contract, not config.
     */
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_DENIED = "DENIED";

    /** Fallback recipient parts when an order carried no email address (legacy tolerated this). */
    private static final String FALLBACK_USER = "customer";
    private static final String FALLBACK_DOMAIN = "@petstore.invalid";

    /** Customer-facing wording for the approved/declined order-status body. */
    private static final String OUTCOME_DENIED = "has been declined";
    private static final String OUTCOME_APPROVED = "has been approved and is being prepared for shipment";

    /**
     * Compose the "order shipped" (or backorder) email for an invoice event. {@code shipped=true}
     * produces the shipped subject/body with the order total; {@code shipped=false} produces the
     * backorder ("delayed") subject/body. Recipient falls back to {@code <userId>@petstore.invalid}
     * when the invoice carries no email. Pure — no transport, no side effects.
     *
     * @param invoice the invoice event (orderId, shipped flag, totalPrice, recipient fields)
     * @return the composed {@link Email} (to, subject, body); never null
     */
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

    /**
     * Compose the order-status email for an approval/denial/completion — the
     * legacy {@code MailOrderApprovalMDB} ("Order Status: …") and
     * {@code MailCompletedOrderMDB} ("Order COMPLETED: …") triggers. A COMPLETED
     * status uses the dedicated completed subject; APPROVED/DENIED use the generic
     * order-status subject. Pure — no transport, no side effects.
     *
     * @param event the order-status event (orderId, status, totalPrice, recipient fields)
     * @return the composed {@link Email} (to, subject, body); never null
     */
    public Email fromStatus(OrderStatusEvent event) {
        String to = recipient(event.emailId(), event.userId());
        if (STATUS_COMPLETED.equals(event.status())) {
            String body = """
                    Dear Customer,

                    Your order #%s is now COMPLETE — all items have shipped.
                    Order total: $%.2f

                    Thank you for shopping with the Java Pet Store.

                    — The Java Pet Store Team""".formatted(event.orderId(), event.totalPrice());
            return new Email(to, COMPLETED_SUBJECT + event.orderId(), body);
        }
        String outcome = STATUS_DENIED.equals(event.status()) ? OUTCOME_DENIED : OUTCOME_APPROVED;
        String body = """
                Dear Customer,

                The status of your order #%s %s.
                Order total: $%.2f

                You can track your order in your Pet Store account.

                — The Java Pet Store Team""".formatted(event.orderId(), outcome, event.totalPrice());
        return new Email(to, STATUS_SUBJECT + event.orderId(), body);
    }

    private static String recipient(InvoiceEvent invoice) {
        return recipient(invoice.emailId(), invoice.userId());
    }

    private static String recipient(String emailId, String userId) {
        if (emailId != null && !emailId.isBlank()) {
            return emailId;
        }
        // Fallback if the order carried no email (legacy also tolerated missing addr).
        String who = userId == null ? FALLBACK_USER : userId;
        return who + FALLBACK_DOMAIN;
    }
}
