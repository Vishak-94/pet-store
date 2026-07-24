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

    /** checkout → warehouse: a new purchase order to process. One consumer. */
    public static final Destination PURCHASE_ORDER = queue("PurchaseOrderQueue");

    /** warehouse → inventory: an approved order to fulfil. One consumer. */
    public static final Destination APPROVED_ORDER = queue("ApprovedOrderQueue");

    /**
     * inventory → (warehouse + any others): the order was fulfilled/invoiced.
     * A TOPIC (legacy InvoiceTopic) so multiple services fan out on it — warehouse
     * completes the order, notification-service emails the customer, etc.
     */
    public static final Destination INVOICE = topic("InvoiceTopic");
}
