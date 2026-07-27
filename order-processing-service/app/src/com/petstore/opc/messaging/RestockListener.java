package com.petstore.opc.messaging;

import com.petstore.messaging.Correlation;
import com.petstore.messaging.Destinations;
import com.petstore.messaging.events.RestockEvent;
import com.petstore.opc.service.AdminService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Subscribes to the RestockTopic (a TOPIC — pub/sub) and re-drives backordered orders.
 * When inventory-service adds fresh stock it publishes a {@link RestockEvent}; OPC — the
 * authoritative order owner — reacts by re-dispatching every APPROVED (backordered) order
 * through fulfilment, oldest-first. This restores the legacy supplier
 * {@code processPendingPO()}-on-restock behaviour (PARITY_AUDIT H2/M8) while keeping order
 * ownership in OPC: the event carries only item + amount, and OPC decides which orders to retry.
 *
 * <p>The re-drive reuses the existing {@link AdminService#redriveApprovedForFulfilment()} →
 * {@link com.petstore.opc.service.ApprovalGateway} → outbox → ApprovedOrderQueue pipeline, so
 * there is zero new fulfilment logic here and inventory-service's orderId dedup keeps it idempotent.
 */
@Component
public class RestockListener {

    private static final Logger log = LoggerFactory.getLogger(RestockListener.class);

    private final AdminService admin;

    public RestockListener(AdminService admin) {
        this.admin = admin;
    }

    // A unique durable-subscription name ("opc-restock"). The topicFactory is durable+shared, so this
    // name — not a connection clientId — identifies the subscription; it must be UNIQUE per logical
    // subscriber (cf. "opc-invoice"). OPC is currently the only RestockTopic subscriber, but the
    // distinct name keeps future subscribers (e.g. an analytics observer) from competing for messages.
    /**
     * Consume one {@link RestockEvent} off RestockTopic and re-drive backordered orders. Delegates to
     * {@link AdminService#redriveApprovedForFulfilment()}, which re-dispatches every APPROVED order
     * (oldest-first) via the outbox. Idempotent end-to-end: re-dispatching an already-shipped order is
     * a no-op at inventory-service (orderId dedup), so JMS at-least-once delivery and overlapping
     * restocks are both safe. The event's item/quantity are informational here — OPC re-attempts ALL
     * backorders, not just lines for the restocked item, matching the legacy pending-PO sweep.
     *
     * @param restock the inbound restock event (item + quantity added by inventory-service)
     */
    @JmsListener(destination = Destinations.RESTOCK_NAME, containerFactory = "topicFactory",
            subscription = "opc-restock")
    @Transactional
    public void onRestock(RestockEvent restock) {
        // Carry the restock's correlation id so the OrderApprovedEvents we re-enqueue (and the logs
        // below) stay on the originating restock's trace; null for a plain supplier UI click.
        Correlation.set(restock.meta() == null ? null : restock.meta().correlationId());
        try {
            log.info("Received restock for {} (+{}) — re-driving APPROVED backorders",
                    restock.itemId(), restock.quantityAdded());
            admin.redriveApprovedForFulfilment();
        } finally {
            Correlation.clear();
        }
    }
}
