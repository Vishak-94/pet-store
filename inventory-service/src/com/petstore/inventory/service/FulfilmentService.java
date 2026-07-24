package com.petstore.inventory.service;

import com.petstore.messaging.events.OrderApprovedEvent;
import com.petstore.inventory.repository.InventoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Order fulfilment — the legacy supplier.ear job (OrderFulfillmentFacade). Given
 * an APPROVED order, reserve stock per line (pessimistic lock) and report whether
 * everything shipped. The approval decision itself lives in warehouse-service;
 * this service only fulfils.
 *
 * <p>All-or-nothing per order: if any line is short, nothing is decremented and
 * the order is reported as not shipped (backorder), mirroring the legacy "ship
 * only if all lines available" behaviour.
 */
@Service
public class FulfilmentService {

    private static final Logger log = LoggerFactory.getLogger(FulfilmentService.class);

    private final InventoryStore inventory;

    public FulfilmentService(InventoryStore inventory) {
        this.inventory = inventory;
    }

    /**
     * Reserve stock for every line and ship, atomically. Returns true if fully
     * shipped; false (and no decrement) if any line is short.
     */
    @Transactional
    public boolean fulfil(OrderApprovedEvent order) {
        // First pass: check availability under lock; abort if any line short.
        for (OrderApprovedEvent.Line line : order.lines()) {
            int available = inventory.quantityOf(line.itemId()).orElse(0);
            if (available < line.quantity()) {
                log.info("Order {} line {} short ({} < {}) — backordered, nothing shipped",
                        order.orderId(), line.itemId(), available, line.quantity());
                return false;
            }
        }
        // Second pass: reserve (pessimistic lock per line). Any failure rolls back the tx.
        for (OrderApprovedEvent.Line line : order.lines()) {
            if (!inventory.tryReserve(line.itemId(), line.quantity())) {
                log.info("Order {} line {} lost the race — backordered", order.orderId(), line.itemId());
                throw new BackorderException(order.orderId(), line.itemId());
            }
        }
        log.info("Order {} fully fulfilled ({} lines)", order.orderId(), order.lines().size());
        return true;
    }

    /** Thrown to roll back a partial reservation when a line loses the stock race. */
    static class BackorderException extends RuntimeException {
        BackorderException(String orderId, String itemId) {
            super("Backorder: order " + orderId + " item " + itemId);
        }
    }
}
