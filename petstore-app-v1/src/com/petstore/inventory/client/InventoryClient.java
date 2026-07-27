package com.petstore.inventory.client;

import org.springframework.web.client.RestClient;

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
 */
public class InventoryClient {

    /** Public availability path (contract); {@code {itemId}} is URL-encoded by RestClient. */
    private static final String AVAILABILITY = "/api/inventory/{itemId}/availability";
    private static final String KEY_QUANTITY = "quantity";

    private final RestClient http;

    public InventoryClient(RestClient http) {
        this.http = http;
    }

    /**
     * On-hand quantity for a single item, or {@link Optional#empty()} if it can't be determined
     * (the service returns no/blank quantity). Never returns a negative value.
     *
     * <p>This does NOT swallow transport failures — a breaker-open / timeout surfaces as a
     * {@code RestClientException} so the caller can decide how to degrade (hide the badge). That
     * mirrors how the storefront treats other downstream reads.
     *
     * @param itemId the item to look up
     * @return the on-hand quantity, or empty if the response carried none
     */
    public Optional<Integer> stockFor(String itemId) {
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
