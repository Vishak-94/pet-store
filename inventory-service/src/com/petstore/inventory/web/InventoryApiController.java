package com.petstore.inventory.web;

import com.petstore.inventory.repository.InventoryStore;
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
    private static final String RESTOCK = "/api/inventory/{itemId}/restock";
    /** JSON response body keys. */
    private static final String KEY_ERROR = "error";
    private static final String KEY_ITEM_ID = "itemId";
    private static final String KEY_ADDED = "added";
    private static final String KEY_QUANTITY = "quantity";
    /** Rejection message when a non-positive restock quantity is requested. */
    private static final String ERROR_QTY_POSITIVE = "qty must be > 0";

    private final InventoryStore inventory;

    public InventoryApiController(InventoryStore inventory) {
        this.inventory = inventory;
    }

    @GetMapping(ALL_INVENTORY)
    public Map<String, Integer> inventory() {
        return inventory.all();
    }

    @PostMapping(RESTOCK)
    public ResponseEntity<Map<String, Object>> restock(@PathVariable String itemId,
                                                       @RequestParam int qty) {
        if (qty <= 0) {
            return ResponseEntity.badRequest().body(Map.of(KEY_ERROR, ERROR_QTY_POSITIVE));
        }
        inventory.addQuantity(itemId, qty);
        return ResponseEntity.ok(Map.of(KEY_ITEM_ID, itemId, KEY_ADDED, qty,
                KEY_QUANTITY, inventory.quantityOf(itemId).orElse(0)));
    }
}
