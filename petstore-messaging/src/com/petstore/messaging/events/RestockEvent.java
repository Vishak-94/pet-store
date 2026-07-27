package com.petstore.messaging.events;

import com.petstore.messaging.EventMeta;

/**
 * Fresh inventory arrived (inventory → whoever cares) over the RestockTopic.
 * A TOPIC event: it announces that stock for an item was replenished so
 * subscribers can react — order-processing re-drives its APPROVED (backordered)
 * orders through fulfilment, restoring the legacy supplier {@code processPendingPO}
 * "restock re-fulfils pending POs" behaviour (see PARITY_AUDIT H2/M8).
 *
 * <p>Carries only the item + amount added; the re-drive decision (which orders to
 * re-attempt) lives in order-processing, which owns the order read-model. Consumers
 * must be idempotent — re-driving an order that already shipped is a harmless no-op.
 */
public record RestockEvent(
        EventMeta meta,
        String itemId,
        int quantityAdded) {

    public static final String TYPE = "Restock";
}
