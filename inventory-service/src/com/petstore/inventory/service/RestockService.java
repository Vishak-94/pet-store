package com.petstore.inventory.service;

import com.petstore.inventory.repository.InventoryStore;
import com.petstore.messaging.Destinations;
import com.petstore.messaging.Events;
import com.petstore.messaging.MessagePublisher;
import com.petstore.messaging.events.RestockEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The supplier "receiver" restock action, shared by the UI and JSON controllers.
 * Adds stock and then announces it: additively increments on-hand quantity, then
 * publishes a {@link RestockEvent} to the RestockTopic so order-processing can
 * re-drive its APPROVED (backordered) orders through fulfilment — restoring the
 * legacy supplier {@code processPendingPO}-on-restock behaviour (PARITY_AUDIT H2/M8).
 *
 * <p>The publish is fire-and-forget (async re-drive): the restock call returns as soon
 * as stock is added; the actual re-fulfilment happens on the JMS backbone. The event
 * carries only the item + amount — order-processing owns the order read-model and
 * decides which orders to re-attempt (all APPROVED, oldest-first).
 */
@Service
public class RestockService {

    private static final Logger log = LoggerFactory.getLogger(RestockService.class);

    private final InventoryStore inventory;
    private final MessagePublisher publisher;

    public RestockService(InventoryStore inventory, MessagePublisher publisher) {
        this.inventory = inventory;
        this.publisher = publisher;
    }

    /**
     * Add {@code qty} to an item's on-hand stock and announce the restock. A non-positive
     * {@code qty} is a no-op (no stock change, no event) so callers can pass through raw form
     * input without a guard. Returns the resulting on-hand quantity.
     *
     * @param itemId the item to restock
     * @param qty    quantity to add; ignored if not {@code > 0}
     * @return the item's on-hand quantity after the restock (0 if unknown)
     */
    public int restock(String itemId, int qty) {
        if (qty <= 0) {
            return inventory.quantityOf(itemId).orElse(0);
        }
        inventory.addQuantity(itemId, qty);
        int onHand = inventory.quantityOf(itemId).orElse(0);
        // Announce the restock so order-processing re-drives backordered (APPROVED) orders.
        // Events.meta pulls any ambient correlation id from the MDC (null for a plain UI click).
        publisher.publish(Destinations.RESTOCK,
                new RestockEvent(Events.meta(RestockEvent.TYPE), itemId, qty));
        log.info("Restocked {} by {} (on hand {}); RestockEvent published to {}",
                itemId, qty, onHand, Destinations.RESTOCK_NAME);
        return onHand;
    }
}
