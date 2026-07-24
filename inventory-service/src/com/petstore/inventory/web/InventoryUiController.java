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

    private final InventoryStore inventory;

    public InventoryUiController(InventoryStore inventory) {
        this.inventory = inventory;
    }

    @GetMapping("/inventory")
    public String inventory(Model model) {
        model.addAttribute("stock", inventory.all());
        return "inventory";
    }

    /** Restock: add quantity to an item (the supplier "receiver" job). */
    @PostMapping("/inventory/restock")
    public String restock(@RequestParam String itemId, @RequestParam int qty) {
        if (qty > 0) {
            inventory.addQuantity(itemId, qty);
        }
        return "redirect:/inventory";
    }
}
