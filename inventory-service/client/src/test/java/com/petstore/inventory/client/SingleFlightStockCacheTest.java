package com.petstore.inventory.client;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SingleFlightStockCache}: TTL freshness, single-flight refresh under a
 * concurrent burst (one backend hit for many readers), non-caching of failures and empties, and
 * refresh after expiry. Uses an injected nanosecond clock so TTL is driven deterministically —
 * no sleeping, no real time.
 */
class SingleFlightStockCacheTest {

    private final AtomicLong clock = new AtomicLong(0);

    private SingleFlightStockCache cache(Duration ttl) {
        return new SingleFlightStockCache(ttl, clock::get);
    }

    @Test
    void freshEntry_isServedWithoutHittingBackend() {
        SingleFlightStockCache cache = cache(Duration.ofHours(1));
        AtomicInteger calls = new AtomicInteger();
        Function<String, Optional<Integer>> loader = id -> {
            calls.incrementAndGet();
            return Optional.of(7);
        };

        assertThat(cache.get("EST-1", loader)).contains(7);   // miss → loads
        assertThat(cache.get("EST-1", loader)).contains(7);   // fresh → cached
        assertThat(cache.get("EST-1", loader)).contains(7);
        assertThat(calls.get()).isEqualTo(1);                 // only the first call hit the backend
    }

    @Test
    void entry_isRefreshedAfterTtlExpires() {
        SingleFlightStockCache cache = cache(Duration.ofHours(1));
        AtomicInteger calls = new AtomicInteger();
        Function<String, Optional<Integer>> loader = id -> Optional.of(calls.incrementAndGet());

        assertThat(cache.get("EST-1", loader)).contains(1);
        clock.addAndGet(Duration.ofMinutes(59).toNanos());    // still within TTL
        assertThat(cache.get("EST-1", loader)).contains(1);
        clock.addAndGet(Duration.ofMinutes(2).toNanos());     // now 61 min → expired
        assertThat(cache.get("EST-1", loader)).contains(2);   // refreshed
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void concurrentBurstOnSameItem_triggersSingleBackendHit() throws Exception {
        SingleFlightStockCache cache = cache(Duration.ofHours(1));
        int threads = 24;
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch loaderEntered = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        Function<String, Optional<Integer>> slowLoader = id -> {
            calls.incrementAndGet();
            loaderEntered.countDown();
            try {
                releaseLoader.await(5, TimeUnit.SECONDS);     // hold the single-flight lock open
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Optional.of(42);
        };

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier startTogether = new CyclicBarrier(threads);
        var results = new java.util.concurrent.ConcurrentLinkedQueue<Optional<Integer>>();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                await(startTogether);
                results.add(cache.get("EST-1", slowLoader));
            });
        }
        // Let the winner enter the loader, then release everyone.
        assertThat(loaderEntered.await(5, TimeUnit.SECONDS)).isTrue();
        releaseLoader.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(calls.get()).isEqualTo(1);                 // single-flight: one backend hit for all
        assertThat(results).hasSize(threads).allSatisfy(r -> assertThat(r).contains(42));
    }

    @Test
    void loaderFailure_propagatesAndIsNotCached() {
        SingleFlightStockCache cache = cache(Duration.ofHours(1));
        AtomicInteger calls = new AtomicInteger();
        Function<String, Optional<Integer>> flaky = id -> {
            int n = calls.incrementAndGet();
            if (n == 1) {
                throw new IllegalStateException("breaker open");
            }
            return Optional.of(5);
        };

        assertThatThrownBy(() -> cache.get("EST-1", flaky)).isInstanceOf(IllegalStateException.class);
        assertThat(cache.get("EST-1", flaky)).contains(5);    // retried backend (failure not cached)
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void emptyLoad_isNotCached() {
        SingleFlightStockCache cache = cache(Duration.ofHours(1));
        AtomicInteger calls = new AtomicInteger();
        Function<String, Optional<Integer>> loader = id -> {
            int n = calls.incrementAndGet();
            return n == 1 ? Optional.empty() : Optional.of(3);
        };

        assertThat(cache.get("EST-1", loader)).isEmpty();     // service returned no quantity
        assertThat(cache.get("EST-1", loader)).contains(3);   // not cached → retried
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void differentItems_doNotBlockEachOther() {
        SingleFlightStockCache cache = cache(Duration.ofHours(1));
        assertThat(cache.get("EST-1", id -> Optional.of(1))).contains(1);
        assertThat(cache.get("EST-2", id -> Optional.of(2))).contains(2);
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
