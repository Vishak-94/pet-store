package com.petstore.order.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Snowflake-style order id generator — a 64-bit, time-ordered, collision-free id
 * generated in-process (no DB round-trip, no shared sequence). Replaces the legacy
 * uidgen EJB (a DB counter) and fixes the previous in-memory AtomicLong that reset
 * to 1001 on every restart (colliding with prior runs).
 *
 * <p>Layout: 41 bits ms-since-epoch | 12 bits per-ms sequence | 10 bits node/random.
 * Time-ordered so ids sort by creation; the random node segment avoids collisions
 * if more than one storefront instance runs. Rendered as a plain decimal string,
 * preserving the "order id is a numeric string" shape the rest of the system expects.
 */
@Component
public class OrderIdGenerator {

    private static final long EPOCH = 1_700_000_000_000L;   // fixed custom epoch (2023-11-14)
    private static final SecureRandom RANDOM = new SecureRandom();

    private final long node = RANDOM.nextInt(1 << 10);      // 10-bit node, per JVM
    private final AtomicInteger seq = new AtomicInteger(0);
    private volatile long lastMs = -1L;

    public synchronized String nextId() {
        long now = Instant.now().toEpochMilli();
        if (now == lastMs) {
            int s = seq.incrementAndGet() & 0xFFF;          // 12-bit sequence within the ms
            if (s == 0) {                                    // sequence exhausted this ms → wait
                while (now <= lastMs) {
                    now = Instant.now().toEpochMilli();
                }
            }
        } else {
            seq.set(0);
        }
        lastMs = now;
        long id = ((now - EPOCH) << 22) | (seq.get() << 10) | node;
        return Long.toUnsignedString(id);
    }
}
