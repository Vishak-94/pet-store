package com.petstore.inventory.service;

import com.petstore.messaging.events.OrderApprovedEvent;
import com.petstore.inventory.repository.InventoryStore;
import com.petstore.inventory.repository.ProcessedEventStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * Order fulfilment — the legacy supplier.ear job (OrderFulfillmentFacade). Given
 * an APPROVED order, reserve stock per line (pessimistic lock) and report whether
 * everything shipped. The approval decision itself lives in warehouse-service;
 * this service only fulfils.
 *
 * <p>All-or-nothing per order (an intentional design decision, not the legacy
 * behaviour): if every line has sufficient stock, reserve all lines and ship —
 * the invoice reports shipped=true and the order moves to COMPLETED. If any line
 * is short, nothing is reserved or decremented and the invoice reports
 * shipped=false — the order stays APPROVED for a later retry once restocked.
 * There is no partial shipment.
 */
@Service
public class FulfilmentService {

    private static final Logger log = LoggerFactory.getLogger(FulfilmentService.class);

    private final InventoryStore inventory;
    private final ProcessedEventStore processedEvents;

    public FulfilmentService(InventoryStore inventory, ProcessedEventStore processedEvents) {
        this.inventory = inventory;
        this.processedEvents = processedEvents;
    }

    /**
     * Reserve stock for every line and ship, atomically. Returns true if fully
     * shipped; false (and no decrement) if any line is short.
     *
     * <p>Idempotent against JMS at-least-once redelivery: an event whose stock was
     * already decremented (marked in {@link ProcessedEventStore}) is treated as
     * already shipped and NOT decremented again. Because the availability check,
     * the reservations, and the processed-marker all run in this one transaction,
     * they commit or roll back together — a redelivery can never oversell. Only a
     * fully-shipped event is marked; a short-stock event decrements nothing, so its
     * redelivery is inherently safe to re-evaluate.
     */
    @Transactional
    public boolean fulfil(OrderApprovedEvent order) {
        String eventId = order.meta() == null ? null : order.meta().eventId();
        if (processedEvents.isProcessed(eventId)) {
            log.info("Order {} event {} already processed — redelivery, skipping decrement",
                    order.orderId(), eventId);
            return true;
        }
        // Acquire row locks in a GLOBAL, deterministic order (by itemId) so two concurrent orders
        // sharing items can never lock them in opposite orders — the classic ABBA deadlock. Without
        // this, order A locking item1→item2 while order B locks item2→item1 deadlocks the DB, which
        // surfaces as a fulfilment failure + JMS redelivery churn once listener concurrency > 1.
        // Pure ordering change: all-or-nothing fulfilment is unaffected (the set of lines is the same).
        List<OrderApprovedEvent.Line> lines = order.lines().stream()
                .sorted(Comparator.comparing(OrderApprovedEvent.Line::itemId))
                .toList();
        // First pass: check availability under lock; abort if any line short.
        for (OrderApprovedEvent.Line line : lines) {
            int available = inventory.quantityOf(line.itemId()).orElse(0);
            if (available < line.quantity()) {
                log.info("Order {} line {} short ({} < {}) — backordered, nothing shipped",
                        order.orderId(), line.itemId(), available, line.quantity());
                return false;
            }
        }
        // Second pass: reserve (pessimistic lock per line), same global order. Any failure rolls back the tx.
        for (OrderApprovedEvent.Line line : lines) {
            if (!inventory.tryReserve(line.itemId(), line.quantity())) {
                log.info("Order {} line {} lost the race — backordered", order.orderId(), line.itemId());
                throw new BackorderException(order.orderId(), line.itemId());
            }
        }
        // Record the applied event in the SAME transaction as the decrement, so the
        // dedup marker and the stock change are atomic (the PK on event_id is the backstop).
        if (eventId != null) {
            processedEvents.markProcessed(eventId);
        }
        log.info("Order {} fully fulfilled ({} lines)", order.orderId(), order.lines().size());
        return true;
    }

    /**
     * Thrown to roll back a partial reservation when a line loses the stock race.
     * This is an EXPECTED business outcome (backorder), distinct from an infrastructure
     * failure — the listener catches only this and lets other exceptions propagate so
     * JMS redelivers rather than silently ACKing a lost order as "not shipped".
     */
    public static class BackorderException extends RuntimeException {
        BackorderException(String orderId, String itemId) {
            super("Backorder: order " + orderId + " item " + itemId);
        }
    }
}
