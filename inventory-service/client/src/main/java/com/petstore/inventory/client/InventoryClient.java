package com.petstore.inventory.client;

import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Importable client SDK for inventory-service's <b>public</b> per-item availability read
 * ({@code GET /api/inventory/{itemId}/availability}). Consumers just
 * {@code new InventoryClient(baseUrl)} and call {@link #stockFor(String)}; no URLs or JSON shapes
 * leak into caller code (the path is single-sourced in {@link InventoryServiceEndpoints}, which the
 * server also maps).
 *
 * <p><b>Reads are cached in-process</b> behind a {@link SingleFlightStockCache} (TTL default
 * {@link #DEFAULT_TTL}): a fresh entry is served without touching inventory-service, and on expiry
 * exactly ONE thread refreshes an item while concurrent readers wait for its result (single-flight —
 * no cache stampede). This is intended for the storefront's <em>cosmetic</em> stock badge / stepper
 * cap, which tolerates being up to a TTL stale; it is <b>never</b> an oversell guard (the
 * authoritative all-or-nothing stock check stays at fulfilment, inventory-service, under a
 * pessimistic row lock). Because the cache lives here in the SDK, every consumer of this jar gets
 * that protection for free. Transport failures and empty reads are NOT cached — they propagate to
 * the caller (which degrades) and the next call retries the backend, so a stale/hidden badge
 * self-heals the moment inventory-service is healthy.
 *
 * <p>Base URL is a constructor arg (environment-specific); the endpoint path is a contract constant.
 * The bare constructors build a {@link RestClient} with bounded connect/read timeouts so a
 * hung/slow inventory-service can't block caller threads; a consumer that wants a circuit breaker /
 * retry (e.g. the storefront's {@code ResilientRestClient}) passes a preconfigured {@link RestClient}.
 */
public class InventoryClient {

    /** Bounded timeouts so a hung/slow inventory-service can't block caller threads indefinitely. */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    /** Default freshness window for a cached stock reading. Stock badges tolerate hour-old data. */
    public static final Duration DEFAULT_TTL = Duration.ofHours(1);

    private final RestClient http;
    private final SingleFlightStockCache cache;

    /** Use the default base URL ({@code http://localhost:8085}) and default TTL. */
    public InventoryClient() {
        this(InventoryServiceEndpoints.DEFAULT_BASE_URL);
    }

    /** Use a specific base URL (host/port per environment) and the default TTL. */
    public InventoryClient(String baseUrl) {
        this(RestClient.builder().baseUrl(baseUrl).requestFactory(timeoutFactory()).build());
    }

    /** Advanced: supply a preconfigured RestClient (e.g. with a circuit breaker / retry / TLS). */
    public InventoryClient(RestClient restClient) {
        this(restClient, DEFAULT_TTL);
    }

    /**
     * Full control: a preconfigured {@link RestClient} plus the stock-cache TTL.
     *
     * @param restClient the transport (timeouts / breaker / retry are the caller's choice)
     * @param ttl        how long a cached stock reading is served before a single-flight refresh
     */
    public InventoryClient(RestClient restClient, Duration ttl) {
        this.http = restClient;
        this.cache = new SingleFlightStockCache(ttl);
    }

    /**
     * On-hand quantity for a single item, or {@link Optional#empty()} if it can't be determined
     * (the service returns no/blank quantity). Never returns a negative value.
     *
     * <p>Served from the in-process TTL cache when fresh; otherwise refreshed via {@link #fetch}
     * under single-flight semantics ({@link SingleFlightStockCache}). This does NOT swallow
     * transport failures — a breaker-open / timeout surfaces as a {@code RestClientException} (and
     * is not cached) so the caller can decide how to degrade (hide the badge).
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
                .uri(InventoryServiceEndpoints.AVAILABILITY, itemId)
                .retrieve()
                .body(Map.class);
        if (body == null || body.get(InventoryServiceEndpoints.KEY_QUANTITY) == null) {
            return Optional.empty();
        }
        int qty = ((Number) body.get(InventoryServiceEndpoints.KEY_QUANTITY)).intValue();
        return Optional.of(Math.max(0, qty));
    }

    /** A request factory with bounded connect/read timeouts (used by the bare constructors). */
    private static ClientHttpRequestFactory timeoutFactory() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        f.setReadTimeout((int) READ_TIMEOUT.toMillis());
        return f;
    }
}
