package com.petstore.inventory.repository;

import java.util.Map;
import java.util.Optional;

/** Port for inventory (from legacy supplier.ear). tryReserve is pessimistic-locked. */
public interface InventoryStore {

    Optional<Integer> quantityOf(String itemId);

    /** Atomic, pessimistic-locked decrement; false if insufficient/unknown. */
    boolean tryReserve(String itemId, int qty);

    void addQuantity(String itemId, int qty);

    /** All stock levels (for the inventory UI). */
    Map<String, Integer> all();
}
