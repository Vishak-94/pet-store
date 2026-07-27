package com.petstore.inventory.client;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * In-memory, TTL-expiring, <b>single-flight</b> cache of per-item on-hand stock, sitting in front of
 * the remote inventory-service read used by {@link InventoryClient}. It exists to spare
 * inventory-service a request per page view for a value that is only ever used for a
 * <em>cosmetic</em> stock badge / stepper cap (never an oversell guard — that stays at fulfilment,
 * all-or-nothing under a row lock), so a slightly stale reading is harmless.
 *
 * <h2>Behaviour</h2>
 * <ul>
 *   <li><b>Hot path is lock-free.</b> While an item's entry is within its TTL, {@link #get} returns it
 *       straight from a {@link ConcurrentHashMap} with no locking or backend call.</li>
 *   <li><b>Single-flight refresh (cache-stampede protection).</b> When an entry is missing or expired
 *       and several threads ask for the same item at once, exactly <b>one</b> thread acquires that
 *       item's lock and calls the backend loader; the others block on the same lock and, on release,
 *       double-check the map and return the value the winner just stored — so a burst of concurrent
 *       readers triggers a single backend hit, not one per reader.</li>
 *   <li><b>Per-item locking.</b> The lock is keyed by item id, so a refresh of item A never blocks a
 *       cached read (or refresh) of item B.</li>
 *   <li><b>Failures and blanks are NOT cached.</b> If the loader throws (e.g. breaker-open / timeout)
 *       the exception propagates to the caller unchanged (so it degrades exactly as before) and
 *       nothing is stored — the next request retries the backend and the badge recovers the moment
 *       inventory-service is healthy again. An {@link Optional#empty()} load (service returned no
 *       quantity) is likewise not cached.</li>
 * </ul>
 *
 * <p>Time is read through an injectable {@link LongSupplier} of nanoseconds (defaulting to
 * {@link System#nanoTime()}, which is monotonic and immune to wall-clock jumps) purely so tests can
 * advance the clock deterministically.
 *
 * <p>Thread-safe. The {@code locks} map retains one lock per distinct item id ever seen; the Pet
 * Store catalog is small and stable, so this is bounded in practice and never pruned (pruning would
 * reintroduce the race the lock exists to prevent).
 */
final class SingleFlightStockCache {

    /** A cached reading: the clamped on-hand quantity and the {@code nanoTime} it was stored. */
    private record Entry(int quantity, long storedAtNanos) {
    }

    private final long ttlNanos;
    private final LongSupplier nanoClock;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    SingleFlightStockCache(Duration ttl) {
        this(ttl, System::nanoTime);
    }

    /** Test seam: inject a controllable nanosecond clock so TTL expiry can be driven deterministically. */
    SingleFlightStockCache(Duration ttl, LongSupplier nanoClock) {
        this.ttlNanos = ttl.toNanos();
        this.nanoClock = nanoClock;
    }

    /**
     * Return the cached quantity for {@code itemId} if it is still fresh; otherwise refresh it via
     * {@code loader} under single-flight semantics (see the class javadoc).
     *
     * @param itemId the item whose stock is wanted
     * @param loader the backend read to run on a miss/expiry (the {@link InventoryClient} HTTP call);
     *               invoked at most once per refresh across all concurrent callers for this item
     * @return the (possibly just-loaded) quantity, or {@link Optional#empty()} if the loader returned
     *         empty (not cached)
     */
    Optional<Integer> get(String itemId, Function<String, Optional<Integer>> loader) {
        Entry cached = entries.get(itemId);
        if (isFresh(cached)) {
            return Optional.of(cached.quantity());
        }
        // Miss or expired: only one thread per item does the backend hit; the rest wait here.
        ReentrantLock lock = locks.computeIfAbsent(itemId, k -> new ReentrantLock());
        lock.lock();
        try {
            // Double-check: a thread that held the lock just before us may already have refreshed,
            // in which case we return its value without a second backend call.
            Entry afterWait = entries.get(itemId);
            if (isFresh(afterWait)) {
                return Optional.of(afterWait.quantity());
            }
            Optional<Integer> loaded = loader.apply(itemId);   // the ONE backend hit; may throw — not cached
            loaded.ifPresent(qty -> entries.put(itemId, new Entry(qty, nanoClock.getAsLong())));
            return loaded;
        } finally {
            lock.unlock();
        }
    }

    private boolean isFresh(Entry entry) {
        return entry != null && (nanoClock.getAsLong() - entry.storedAtNanos()) < ttlNanos;
    }
}
