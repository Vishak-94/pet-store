package com.petstore.inventory.repository;

/**
 * Dedup ledger keyed by {@code orderId} — records every order whose stock has been
 * decremented (i.e. shipped). Keying on the ORDER (which ships at most once), not the
 * message {@code eventId}, makes fulfilment idempotent across BOTH sources of duplicate
 * delivery:
 * <ul>
 *   <li>plain JMS at-least-once redelivery of the same {@code OrderApprovedEvent}, and</li>
 *   <li>a <em>re-driven</em> event for the same order — order-processing re-publishes a
 *       FRESH {@code OrderApprovedEvent} (new {@code eventId}) for every APPROVED order on
 *       each restock (legacy {@code processPendingPO}; PARITY_AUDIT H2/M8). An eventId-keyed
 *       ledger would NOT catch this, so an already-shipped order sitting APPROVED for the few
 *       ms until its COMPLETED round-trip lands could be decremented twice.</li>
 * </ul>
 *
 * <p>Both operations run inside the fulfilment {@code @Transactional} so the marker and the
 * stock decrement commit — or roll back — atomically. Only a fully-shipped order is marked;
 * a short-stock (backorder) attempt decrements nothing, so re-evaluating it later is safe.
 */
public interface FulfilledOrderStore {

    /** True if this order's stock was already decremented (a redelivery or a re-drive). */
    boolean isFulfilled(String orderId);

    /** Record that this order has been fully fulfilled (shipped). Called in the fulfil transaction. */
    void markFulfilled(String orderId);
}
