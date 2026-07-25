package com.petstore.order.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * In-memory idempotency-key store for checkout (the "pre-checkout reservation" design).
 *
 * <p>On {@code POST /pre-checkout} the storefront {@link #reserve(String) reserves} a fresh
 * server-minted order id for the signed-in customer: {@code customerId -> (orderId, issuedAt)}.
 * The (encrypted) id is handed to the browser and echoed back on {@code POST /checkout}, where
 * {@link #consumeIfMatches(String, String)} atomically checks it against the reservation and
 * removes it. The first submit matches + consumes; a refresh / double-click / back-then-resubmit
 * carries the same id but finds the reservation already gone → rejected, so no second order is
 * published. The OPC {@code order_id} primary key is the correctness backstop behind this.
 *
 * <p><b>Scope / lifetime:</b> keyed by {@code customerId} (one outstanding reservation per
 * customer — multi-tab is intentionally out of scope). Entries older than {@code ttl} are
 * evicted by a daemon sweeper so an abandoned checkout can't leak memory. State is per-JVM and
 * lost on restart — fine for a single storefront instance; a shared store would be needed to
 * scale out (see DECISIONS.md).
 */
@Component
public class IdempotencyKeyStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyKeyStore.class);

    /** A reserved order id and when it was issued (for TTL eviction). */
    public record Reservation(String orderId, Instant issuedAt) {
    }

    private final OrderIdGenerator ids;
    private final Duration ttl;
    private final Map<String, Reservation> byCustomer = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sweeper;

    public IdempotencyKeyStore(OrderIdGenerator ids,
                               @Value("${checkout.idempotency.ttl-minutes:30}") long ttlMinutes,
                               @Value("${checkout.idempotency.sweep-interval-seconds:120}") long sweepSeconds) {
        this.ids = ids;
        this.ttl = Duration.ofMinutes(ttlMinutes);
        this.sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "checkout-idempotency-sweeper");
            t.setDaemon(true);
            return t;
        });
        this.sweeper.scheduleAtFixedRate(this::evictExpired, sweepSeconds, sweepSeconds, TimeUnit.SECONDS);
    }

    /**
     * Mint a fresh server-side order id and record it as this customer's outstanding checkout
     * reservation (replacing any previous one — last render wins, multi-tab out of scope).
     *
     * @return the plaintext order id to hand back (encrypted) to the browser
     */
    public String reserve(String customerId) {
        String orderId = ids.nextId();
        byCustomer.put(customerId, new Reservation(orderId, Instant.now()));
        return orderId;
    }

    /**
     * Atomically verify {@code orderId} is the customer's current reservation and, if so, remove
     * it and return {@code true}. Returns {@code false} if there is no reservation, it has a
     * different id (stale/forged), or it was already consumed — i.e. a duplicate submit to reject.
     */
    public boolean consumeIfMatches(String customerId, String orderId) {
        if (customerId == null || orderId == null || orderId.isBlank()) {
            return false;
        }
        // Atomic remove-ONLY-if-matches: compute returns null (removing the entry) when the id
        // matches, else leaves the reservation intact. A wrong/stale id must NOT delete the
        // customer's legitimate outstanding reservation. matched[] captures the outcome.
        boolean[] matched = {false};
        byCustomer.computeIfPresent(customerId, (k, res) -> {
            if (orderId.equals(res.orderId())) {
                matched[0] = true;
                return null;   // consume it
            }
            return res;        // keep it — id didn't match
        });
        return matched[0];
    }

    private void evictExpired() {
        Instant cutoff = Instant.now().minus(ttl);
        int before = byCustomer.size();
        for (Iterator<Map.Entry<String, Reservation>> it = byCustomer.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue().issuedAt().isBefore(cutoff)) {
                it.remove();
            }
        }
        int evicted = before - byCustomer.size();
        if (evicted > 0) {
            log.debug("Evicted {} expired checkout reservation(s)", evicted);
        }
    }

    @Override
    public void close() {
        sweeper.shutdownNow();
    }
}
