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

    private final InventoryStore inventory;

    public InventoryApiController(InventoryStore inventory) {
        this.inventory = inventory;
    }

    @GetMapping("/api/inventory")
    public Map<String, Integer> inventory() {
        return inventory.all();
    }

    @PostMapping("/api/inventory/{itemId}/restock")
    public ResponseEntity<Map<String, Object>> restock(@PathVariable String itemId,
                                                       @RequestParam int qty) {
        if (qty <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "qty must be > 0"));
        }
        inventory.addQuantity(itemId, qty);
        return ResponseEntity.ok(Map.of("itemId", itemId, "added", qty,
                "quantity", inventory.quantityOf(itemId).orElse(0)));
    }
}
