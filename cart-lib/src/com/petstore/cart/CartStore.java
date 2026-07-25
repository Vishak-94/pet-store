package com.petstore.cart;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * In-memory cart store keyed by cart id — the microservice analog of the legacy
 * stateful session bean, but running IN-PROCESS inside the host application (no
 * standalone server). Each cart is an insertion-ordered {@code itemId -> qty}
 * map, plus a last-access timestamp driving a <b>sliding TTL</b>: a cart idle
 * longer than the TTL is evicted, exactly like an {@code HttpSession} timing out.
 *
 * <p>Framework-free by design so the library can be embedded anywhere. The TTL
 * sweeper is a plain daemon {@link ScheduledExecutorService}; call {@link #close()}
 * (or let the host wire it as a bean destroy-method) to stop it cleanly.
 */
public class CartStore implements AutoCloseable {

    /** One cart's mutable state: quantities + last-access time (for the sliding TTL). */
    private static final class CartEntry {
        final Map<String, Integer> quantities = new LinkedHashMap<>();
        volatile Instant lastAccess;

        CartEntry(Instant now) {
            this.lastAccess = now;
        }
    }

    private static final Logger LOG = Logger.getLogger(CartStore.class.getName());

    private final ConcurrentHashMap<String, CartEntry> carts = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final ScheduledExecutorService sweeper;

    /** Default: 15-minute TTL, swept once a minute. */
    public CartStore() {
        this(15, 60);
    }

    public CartStore(long ttlMinutes, long sweepIntervalSeconds) {
        this.ttl = Duration.ofMinutes(ttlMinutes);
        this.sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cart-ttl-sweeper");
            t.setDaemon(true);           // never blocks JVM shutdown
            return t;
        });
        this.sweeper.scheduleWithFixedDelay(
                this::sweepQuietly, sweepIntervalSeconds, sweepIntervalSeconds, TimeUnit.SECONDS);
    }

    /**
     * Runs {@code op} against the cart's quantity map, creating the cart if
     * needed and refreshing its TTL (sliding — any touch extends the life).
     * Synchronized on the entry so mutations are atomic per cart.
     */
    public <T> T withCart(String cartId, Function<Map<String, Integer>, T> op) {
        CartEntry entry = carts.computeIfAbsent(cartId, id -> new CartEntry(Instant.now()));
        synchronized (entry) {
            entry.lastAccess = Instant.now();       // sliding TTL: touching keeps it alive
            return op.apply(entry.quantities);
        }
    }

    /** Read a snapshot copy of the cart's quantities (ordered), refreshing TTL. */
    public Map<String, Integer> snapshot(String cartId) {
        return withCart(cartId, LinkedHashMap::new);
    }

    /** Drop a cart entirely (used by "empty" so an emptied cart doesn't linger). */
    public void remove(String cartId) {
        carts.remove(cartId);
    }

    /** Sliding-TTL sweep — evicts carts idle longer than the TTL. Package-visible for tests. */
    void evictExpired() {
        Instant cutoff = Instant.now().minus(ttl);
        carts.values().removeIf(e -> e.lastAccess.isBefore(cutoff));
    }

    /**
     * The scheduled entry point. {@code scheduleWithFixedDelay} <b>permanently cancels</b>
     * the task if its run throws, which would silently stop TTL eviction for the life of the
     * JVM (a slow memory leak). So any failure in a single sweep is caught and logged; the
     * sweeper keeps running and retries on the next tick. {@link #evictExpired()} stays clean
     * (and package-visible) so tests can assert eviction directly.
     */
    void sweepQuietly() {
        try {
            evictExpired();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "cart TTL sweep failed; will retry next interval", e);
        }
    }

    /** Current number of live carts. */
    public int size() {
        return carts.size();
    }

    @Override
    public void close() {
        sweeper.shutdownNow();
    }
}
