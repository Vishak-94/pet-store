package com.petstore.inventory.web;

import com.petstore.inventory.repository.InventoryStore;
import com.petstore.inventory.service.RestockService;
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
    private final RestockService restockService;

    public InventoryUiController(InventoryStore inventory, RestockService restockService) {
        this.inventory = inventory;
        this.restockService = restockService;
    }

    /**
     * Render the inventory "receiver" page — the stock-level table for supplier staff.
     * SUPPLIER role. Puts the full item-id → quantity map on the model under {@code stock}.
     *
     * <pre>{@code
     * GET /inventory
     *
     * 200 OK  (Thymeleaf view "inventory.html")
     * model: stock = { "EST-1": 42, "EST-2": 1, ... }
     * }</pre>
     *
     * @param model receives the {@code stock} attribute (item-id → on-hand quantity)
     * @return the {@code inventory} view name
     */
    @GetMapping(INVENTORY)
    public String inventory(Model model) {
        model.addAttribute(ATTR_STOCK, inventory.all());
        return VIEW_INVENTORY;
    }

    /**
     * Restock: add quantity to an item (the supplier "receiver" job) then redirect back to the
     * stock table (POST-redirect-GET). Delegates to {@link RestockService}, which adds the stock
     * and publishes a {@code RestockEvent} so order-processing re-drives backordered orders. A
     * non-positive {@code qty} is silently ignored (no change) rather than reported, since the
     * form always supplies a positive amount.
     *
     * <pre>{@code
     * POST /inventory/restock
     * Content-Type: application/x-www-form-urlencoded
     * itemId=EST-2&qty=10
     *
     * 302 Found  → Location: /inventory
     * }</pre>
     *
     * @param itemId the item to restock (form field)
     * @param qty    quantity to add; ignored if not {@code > 0}
     * @return a redirect to {@code /inventory}
     */
    @PostMapping(RESTOCK)
    public String restock(@RequestParam String itemId, @RequestParam int qty) {
        restockService.restock(itemId, qty);
        return REDIRECT_INVENTORY;
    }
}
