package com.petstore.inventory.client;

/**
 * The inventory-service HTTP contract — path constants shared by the server (which maps them) and
 * every client (which calls them). Single-sourcing the paths here means the server provably cannot
 * drift from what clients expect.
 *
 * <p>Only paths live here; the base URL is environment-specific and supplied to
 * {@link InventoryClient} at construction time (never hardcoded).
 */
public final class InventoryServiceEndpoints {

    private InventoryServiceEndpoints() {
    }

    /** Default base URL for local development (inventory-service runs on :8085). */
    public static final String DEFAULT_BASE_URL = "http://localhost:8085";

    /** GET — full stock table (SUPPLIER/ADMIN): item-id → on-hand quantity. */
    public static final String ALL_INVENTORY = "/api/inventory";

    /** GET — <b>public</b> per-item availability: /api/inventory/{itemId}/availability → {itemId, quantity}. */
    public static final String AVAILABILITY = "/api/inventory/{itemId}/availability";

    /** POST — restock one item (SUPPLIER/ADMIN): /api/inventory/{itemId}/restock?qty= */
    public static final String RESTOCK = "/api/inventory/{itemId}/restock";

    /** JSON body key carrying the on-hand quantity on the availability read. */
    public static final String KEY_QUANTITY = "quantity";
}
