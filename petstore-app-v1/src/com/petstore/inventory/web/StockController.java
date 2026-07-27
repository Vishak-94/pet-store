package com.petstore.inventory.web;

import com.petstore.inventory.client.InventoryClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Same-origin stock proxy for the browser. The in-page cart steppers fetch on-hand stock
 * <b>after</b> the page loads (so a slow/down inventory-service never delays rendering) and use
 * it to disable the {@code +} button at the stock ceiling — a UX guardrail, not an oversell guard
 * (the authoritative check stays at fulfilment, all-or-nothing under a row lock).
 *
 * <p>Why a proxy rather than the browser calling inventory-service directly: the storefront runs
 * on :8080 and inventory-service on :8085, so a direct {@code fetch} is cross-origin (blocked by
 * CORS). This endpoint is same-origin ({@code /api/stock/**} on :8080) and simply forwards to the
 * {@link InventoryClient} bean (the same one the item-page badge uses). It therefore inherits the
 * circuit breaker + timeouts and degrades identically: any failure → {@code 204 No Content}, so
 * the browser just leaves the stepper uncapped rather than blocking the shopper.
 */
@RestController
public class StockController {

    private static final String STOCK = "/api/stock/{itemId}";
    private static final String KEY_ITEM_ID = "itemId";
    private static final String KEY_QUANTITY = "quantity";

    private static final Logger log = LoggerFactory.getLogger(StockController.class);

    private final InventoryClient inventory;

    public StockController(InventoryClient inventory) {
        this.inventory = inventory;
    }

    /**
     * On-hand stock for one item, for the browser's after-load stepper cap.
     *
     * <pre>{@code
     * GET /api/stock/EST-18
     *
     * 200 OK   { "itemId": "EST-18", "quantity": 5 }
     * 204      (inventory-service unavailable / unknown → stepper stays uncapped)
     * }</pre>
     *
     * @param itemId the item to look up (path variable)
     * @return 200 with itemId + on-hand quantity, or 204 when stock can't be determined
     */
    @GetMapping(STOCK)
    public ResponseEntity<Map<String, Object>> stock(@PathVariable String itemId) {
        try {
            return inventory.stockFor(itemId)
                    .map(qty -> ResponseEntity.ok(Map.<String, Object>of(KEY_ITEM_ID, itemId, KEY_QUANTITY, qty)))
                    .orElseGet(() -> ResponseEntity.noContent().build());
        } catch (RuntimeException e) {
            log.debug("stock unavailable for {}, stepper left uncapped: {}", itemId, e.getMessage());
            return ResponseEntity.noContent().build();
        }
    }
}
