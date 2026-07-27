package com.petstore.inventory.service;

import com.petstore.messaging.events.OrderApprovedEvent;
import com.petstore.inventory.repository.InventoryStore;
import com.petstore.inventory.repository.FulfilledOrderStore;
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
    private final FulfilledOrderStore fulfilledOrders;

    public FulfilmentService(InventoryStore inventory, FulfilledOrderStore fulfilledOrders) {
        this.inventory = inventory;
        this.fulfilledOrders = fulfilledOrders;
    }

    /**
     * Reserve stock for every line and ship, atomically. Returns true if fully
     * shipped; false (and no decrement) if any line is short.
     *
     * <p>Idempotent by {@code orderId}: an order whose stock was already decremented
     * (marked in {@link FulfilledOrderStore}) is treated as already shipped and NOT
     * decremented again. Keying on the order (which ships at most once) rather than the
     * message {@code eventId} makes this safe against BOTH a plain JMS redelivery AND a
     * <em>re-driven</em> event — order-processing re-publishes a fresh {@code OrderApprovedEvent}
     * (new eventId) for every APPROVED order on each restock (PARITY_AUDIT H2/M8), and an
     * eventId-keyed ledger would miss that. Because the availability check, the reservations,
     * and the fulfilled-marker all run in this one transaction, they commit or roll back
     * together — a redelivery/re-drive can never oversell. Only a fully-shipped order is
     * marked; a short-stock order decrements nothing, so re-evaluating it later is safe.
     *
     * <p>Example inbound order (as delivered by {@link OrderApprovedListener}):
     * <pre>{@code
     * {
     *   "meta": { "eventId": "evt-9f3", "type": "OrderApproved", "correlationId": "corr-42" },
     *   "orderId": "1001",
     *   "lines": [
     *     { "itemId": "EST-1", "quantity": 2, "unitPrice": 12.50 },
     *     { "itemId": "EST-2", "quantity": 1, "unitPrice": 40.00 }
     *   ]
     * }
     * }</pre>
     * If both lines have stock → all reserved, returns {@code true}. If {@code EST-2} (seeded at
     * qty 1) is short → nothing reserved, returns {@code false} (order stays APPROVED for retry).
     *
     * @param order the approved order to fulfil; {@code orderId} keys the dedup ledger and
     *              {@code lines} are the item/quantity pairs to reserve
     * @return {@code true} if every line was reserved (or the order was already fulfilled on a
     *         redelivery/re-drive); {@code false} if any line was short (no stock decremented)
     * @throws BackorderException if a line loses the stock race in the locked second pass,
     *              rolling back any partial reservation in this transaction
     */
    @Transactional
    public boolean fulfil(OrderApprovedEvent order) {
        String orderId = order.orderId();
        if (fulfilledOrders.isFulfilled(orderId)) {
            log.info("Order {} already fulfilled — redelivery or restock re-drive, skipping decrement",
                    orderId);
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
        // Record the shipped order in the SAME transaction as the decrement, so the
        // dedup marker and the stock change are atomic (the PK on order_id is the backstop).
        if (orderId != null) {
            fulfilledOrders.markFulfilled(orderId);
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
