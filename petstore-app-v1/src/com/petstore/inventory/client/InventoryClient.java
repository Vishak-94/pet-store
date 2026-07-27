package com.petstore.inventory.client;

import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Thin storefront-side client for inventory-service's <b>public</b> per-item availability read
 * ({@code GET /api/inventory/{itemId}/availability}). Used to compose a live stock badge onto the
 * item page (read-time API composition: catalog data + this).
 *
 * <p>Unlike catalog/customer/order-processing, inventory-service ships no client SDK jar, and the
 * storefront is its only browse-time consumer, so this small client lives here rather than in a
 * shared module. It is constructed over a {@link RestClient} preconfigured with the circuit
 * breaker + timeouts (see {@code HttpClientConfig}), so a slow/down inventory-service fails fast
 * rather than hanging the page thread; callers still guard the call and degrade (the badge is
 * cosmetic and must never break browsing).
 *
 * <p><b>Reads are cached in-process</b> behind a {@link SingleFlightStockCache} (TTL default 1h):
 * a fresh entry is served without touching inventory-service, and on expiry exactly ONE thread
 * refreshes an item while concurrent readers wait for its result (single-flight — no cache
 * stampede). This is safe because the value only feeds a cosmetic badge / stepper cap and is never
 * an oversell guard, so it tolerates being up to a TTL stale; the authoritative all-or-nothing
 * stock check stays at fulfilment (inventory-service, pessimistic row lock). Transport failures are
 * NOT cached — they propagate to the caller (which degrades) and the next call retries the backend.
 */
public class InventoryClient {

    /** Public availability path (contract); {@code {itemId}} is URL-encoded by RestClient. */
    private static final String AVAILABILITY = "/api/inventory/{itemId}/availability";
    private static final String KEY_QUANTITY = "quantity";

    /** Default freshness window for a cached stock reading. Stock badges tolerate hour-old data. */
    static final Duration DEFAULT_TTL = Duration.ofHours(1);

    private final RestClient http;
    private final SingleFlightStockCache cache;

    public InventoryClient(RestClient http) {
        this(http, DEFAULT_TTL);
    }

    /** @param ttl how long a cached stock reading is served before a single-flight refresh. */
    public InventoryClient(RestClient http, Duration ttl) {
        this.http = http;
        this.cache = new SingleFlightStockCache(ttl);
    }

    /**
     * On-hand quantity for a single item, or {@link Optional#empty()} if it can't be determined
     * (the service returns no/blank quantity). Never returns a negative value.
     *
     * <p>Served from the in-process TTL cache when fresh; otherwise refreshed via
     * {@link #fetch(String)} under single-flight semantics ({@link SingleFlightStockCache}). This
     * does NOT swallow transport failures — a breaker-open / timeout surfaces as a
     * {@code RestClientException} (and is not cached) so the caller can decide how to degrade (hide
     * the badge). That mirrors how the storefront treats other downstream reads.
     *
     * @param itemId the item to look up
     * @return the on-hand quantity, or empty if the response carried none
     */
    public Optional<Integer> stockFor(String itemId) {
        return cache.get(itemId, this::fetch);
    }

    /** The actual remote read — the cache's backend loader. Runs at most once per refresh per item. */
    private Optional<Integer> fetch(String itemId) {
        @SuppressWarnings("unchecked")
        Map<String, Object> body = http.get()
                .uri(AVAILABILITY, itemId)
                .retrieve()
                .body(Map.class);
        if (body == null || body.get(KEY_QUANTITY) == null) {
            return Optional.empty();
        }
        int qty = ((Number) body.get(KEY_QUANTITY)).intValue();
        return Optional.of(Math.max(0, qty));
    }
}
