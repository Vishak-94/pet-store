package com.petstore.inventory.web;

import com.petstore.inventory.repository.InventoryStore;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Inventory "receiver" UI (Thymeleaf) — replaces the legacy supplier.ear
 * RcvrRequestProcessor: view stock levels and restock (add quantity). SUPPLIER role.
 */
@Controller
public class InventoryUiController {

    /** Route paths + the Thymeleaf view / redirect targets. */
    private static final String INVENTORY = "/inventory";
    private static final String RESTOCK = "/inventory/restock";
    private static final String VIEW_INVENTORY = "inventory";
    private static final String REDIRECT_INVENTORY = "redirect:/inventory";
    /** Model attribute carrying the stock-level map to the view. */
    private static final String ATTR_STOCK = "stock";

    private final InventoryStore inventory;

    public InventoryUiController(InventoryStore inventory) {
        this.inventory = inventory;
    }

    @GetMapping(INVENTORY)
    public String inventory(Model model) {
        model.addAttribute(ATTR_STOCK, inventory.all());
        return VIEW_INVENTORY;
    }

    /** Restock: add quantity to an item (the supplier "receiver" job). */
    @PostMapping(RESTOCK)
    public String restock(@RequestParam String itemId, @RequestParam int qty) {
        if (qty > 0) {
            inventory.addQuantity(itemId, qty);
        }
        return REDIRECT_INVENTORY;
    }
}
