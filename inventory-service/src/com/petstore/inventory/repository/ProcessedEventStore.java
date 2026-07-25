package com.petstore.inventory.repository;

/**
 * Dedup store for at-least-once JMS delivery. Records the {@code EventMeta.eventId}
 * of every order-approved event whose stock has actually been decremented, so a
 * redelivery of the same message does not decrement a second time (oversell).
 *
 * <p>Both operations run inside the fulfilment {@code @Transactional} so the marker
 * and the stock decrement commit — or roll back — atomically.
 */
public interface ProcessedEventStore {

    /** True if this event id was already applied (i.e. this is a JMS redelivery). */
    boolean isProcessed(String eventId);

    /** Record that this event id has been fully applied. Called in the fulfil transaction. */
    void markProcessed(String eventId);
}
