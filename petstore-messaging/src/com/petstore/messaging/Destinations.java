package com.petstore.messaging;

import static com.petstore.messaging.Destination.queue;
import static com.petstore.messaging.Destination.topic;

/**
 * The ONE place every JMS destination name lives — no more hardcoded string
 * literals scattered across services (which drift and cause silent routing bugs).
 *
 * <p>Command flows (do this once → one consumer) are QUEUES. Broadcast events
 * (this happened → whoever cares reacts) are TOPICS. The invoice/completion hop is
 * a topic, restoring the legacy Pet Store {@code InvoiceTopic} pub/sub design.
 */
public final class Destinations {

    private Destinations() {
    }

    /**
     * Raw destination NAMES as compile-time {@code String} constants. Needed because
     * {@code @JmsListener(destination = ...)} annotation attributes require a constant
     * expression — a {@link Destination} object won't do. Consumers reference these
     * (e.g. {@code @JmsListener(destination = Destinations.PURCHASE_ORDER_NAME)}) so the
     * listener and the {@link Destination} below can never drift apart. Contract literals —
     * kept as constants, never externalized to config (renaming one breaks routing fleet-wide).
     */
    public static final String PURCHASE_ORDER_NAME = "PurchaseOrderQueue";
    public static final String APPROVED_ORDER_NAME = "ApprovedOrderQueue";
    public static final String INVOICE_NAME = "InvoiceTopic";
    public static final String ORDER_STATUS_NAME = "OrderStatusTopic";
    public static final String RESTOCK_NAME = "RestockTopic";

    /** checkout → warehouse: a new purchase order to process. One consumer. */
    public static final Destination PURCHASE_ORDER = queue(PURCHASE_ORDER_NAME);

    /** warehouse → inventory: an approved order to fulfil. One consumer. */
    public static final Destination APPROVED_ORDER = queue(APPROVED_ORDER_NAME);

    /**
     * inventory → (warehouse + any others): the order was fulfilled/invoiced.
     * A TOPIC (legacy InvoiceTopic) so multiple services fan out on it — warehouse
     * completes the order, notification-service emails the customer, etc.
     */
    public static final Destination INVOICE = topic(INVOICE_NAME);

    /**
     * warehouse → (notification + any others): a customer-facing order status
     * change (approved/denied/completed). A TOPIC so notification-service emails
     * the customer and future subscribers can react. Restores the legacy
     * MailOrderApprovalMDB / MailCompletedOrderMDB triggers.
     */
    public static final Destination ORDER_STATUS = topic(ORDER_STATUS_NAME);

    /**
     * inventory → (order-processing + any others): fresh stock arrived for an item.
     * A TOPIC so multiple subscribers can react independently — order-processing
     * re-drives its APPROVED (backordered) orders through fulfilment (legacy supplier
     * {@code processPendingPO} on restock; PARITY_AUDIT H2/M8), and future subscribers
     * (analytics, low-stock alerting) can attach without touching the producer.
     */
    public static final Destination RESTOCK = topic(RESTOCK_NAME);
}
