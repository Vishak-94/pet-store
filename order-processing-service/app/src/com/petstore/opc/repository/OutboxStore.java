package com.petstore.opc.repository;

import java.util.List;

/**
 * Port for the transactional outbox — the write side of the OPC's reliable event
 * publishing. {@link #enqueue} runs inside the caller's transaction (same one that
 * writes the order status), so the event row and the state change commit or roll
 * back together. The relay side ({@link #fetchUnpublished}, {@link #markPublished},
 * {@link #recordFailure}) is driven by the scheduled poller.
 *
 * <p>Persistence stays behind this port (JPA adapter in {@code repository.jpa}),
 * mirroring {@link OrderStore}.
 */
public interface OutboxStore {

    /** Persist a new outbound event in the current transaction (id assigned on save). */
    void enqueue(OutboxMessage message);

    /**
     * Oldest-first batch of not-yet-published messages, capped at {@code limit} rows.
     * Rows whose attempt count has reached {@code maxAttempts} are excluded (parked as
     * poison messages) so they stop being retried but remain in the table for inspection.
     */
    List<OutboxMessage> fetchUnpublished(int limit, int maxAttempts);

    /** Mark a message delivered (stamp {@code published_at}). */
    void markPublished(String id);

    /**
     * Record a failed publish attempt (increment the attempt counter) without publishing,
     * and return the row's <em>new</em> attempt count. The relay uses it to detect the poll
     * on which a row reaches the park threshold, so it can log that once (not on every retry).
     */
    int recordFailure(String id);
}
