package com.petstore.inventory.web;

import com.petstore.inventory.repository.InventoryStore;
import com.petstore.inventory.service.RestockService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * JSON API for inventory (SUPPLIER/ADMIN) — stock levels + restock. The
 * programmatic equivalent of the legacy supplier receiver.
 */
@RestController
public class InventoryApiController {

    /** Route paths (kept as constants so they can't drift from the security matchers). */
    private static final String ALL_INVENTORY = "/api/inventory";
    private static final String AVAILABILITY = "/api/inventory/{itemId}/availability";
    private static final String RESTOCK = "/api/inventory/{itemId}/restock";
    /** JSON response body keys. */
    private static final String KEY_ERROR = "error";
    private static final String KEY_ITEM_ID = "itemId";
    private static final String KEY_ADDED = "added";
    private static final String KEY_QUANTITY = "quantity";
    /** Rejection message when a non-positive restock quantity is requested. */
    private static final String ERROR_QTY_POSITIVE = "qty must be > 0";

    private final InventoryStore inventory;
    private final RestockService restockService;

    public InventoryApiController(InventoryStore inventory, RestockService restockService) {
        this.inventory = inventory;
        this.restockService = restockService;
    }

    /**
     * Current stock level for every known item — a snapshot of the {@code inventory} table.
     * SUPPLIER/ADMIN only (verify-only JWT). Read-only; no locking.
     *
     * <pre>{@code
     * GET /api/inventory
     *
     * 200 OK
     * {
     *   "EST-1": 42,
     *   "EST-2": 1,
     *   "EST-6": 0
     * }
     * }</pre>
     *
     * @return item-id → on-hand quantity for all items (empty map if none)
     */
    @GetMapping(ALL_INVENTORY)
    public Map<String, Integer> inventory() {
        return inventory.all();
    }

    /**
     * On-hand stock for a SINGLE item — a narrow, <b>public</b> read for the storefront to show a
     * "in stock / only N left / out of stock" badge on the item page (read-time API composition;
     * the storefront composes catalog + this). Deliberately separate from {@link #inventory()}:
     * that returns the whole table and is SUPPLIER/ADMIN-only, whereas per-item availability shown
     * on a public product page is inherently public (any visitor sees it), so this one path is
     * opened in {@code SecurityConfig} — mirroring catalog-service, which serves display data with
     * no auth. Read-only, no locking; an unknown item returns {@code quantity: 0} (not a 404), so
     * the caller renders "out of stock" rather than special-casing missing items.
     *
     * <pre>{@code
     * GET /api/inventory/EST-2/availability
     *
     * 200 OK
     * { "itemId": "EST-2", "quantity": 3 }
     * }</pre>
     *
     * @param itemId the item to look up (path variable)
     * @return 200 with the itemId and on-hand quantity (0 if the item is unknown)
     */
    @GetMapping(AVAILABILITY)
    public Map<String, Object> availability(@PathVariable String itemId) {
        int onHand = inventory.quantityOf(itemId).orElse(0);
        return Map.of(KEY_ITEM_ID, itemId, KEY_QUANTITY, onHand);
    }

    /**
     * Restock a single item — the JSON equivalent of the supplier "receiver" job: additively
     * add {@code qty} to on-hand stock, then publish a {@code RestockEvent} so order-processing
     * re-drives its APPROVED (backordered) orders through fulfilment (legacy processPendingPO on
     * restock; PARITY_AUDIT H2/M8). Stock is still stored per-item (no persisted supplier PO).
     *
     * <pre>{@code
     * POST /api/inventory/EST-2/restock?qty=10
     *
     * 200 OK
     * { "itemId": "EST-2", "added": 10, "quantity": 11 }
     * }</pre>
     *
     * A non-positive quantity is rejected and nothing is changed:
     * <pre>{@code
     * POST /api/inventory/EST-2/restock?qty=0
     *
     * 400 Bad Request
     * { "error": "qty must be > 0" }
     * }</pre>
     *
     * @param itemId the item to restock (path variable)
     * @param qty    quantity to add; must be {@code > 0}
     * @return 200 with the itemId, amount added and resulting quantity; 400 if {@code qty <= 0}
     */
    @PostMapping(RESTOCK)
    public ResponseEntity<Map<String, Object>> restock(@PathVariable String itemId,
                                                       @RequestParam int qty) {
        if (qty <= 0) {
            return ResponseEntity.badRequest().body(Map.of(KEY_ERROR, ERROR_QTY_POSITIVE));
        }
        int onHand = restockService.restock(itemId, qty);   // adds stock + publishes RestockEvent
        return ResponseEntity.ok(Map.of(KEY_ITEM_ID, itemId, KEY_ADDED, qty,
                KEY_QUANTITY, onHand));
    }
}
