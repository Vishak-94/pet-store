package com.petstore.inventory.repository.jpa;

import com.petstore.inventory.repository.InventoryStore;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * JPA adapter for the {@link InventoryStore} port — the persistence side of the
 * hexagon. Translates the domain-level stock operations into Spring Data calls on
 * {@link InventoryJpaRepository}. The oversell-safety contract lives here:
 * {@link #tryReserve} does its check-and-decrement inside a {@code @Transactional}
 * pessimistic row lock ({@code SELECT … FOR UPDATE}), so concurrent fulfilments
 * can never drive stock negative (the DB {@code quantity >= 0} CHECK is the floor).
 */
@Repository
public class JpaInventoryStore implements InventoryStore {

    private final InventoryJpaRepository jpa;

    JpaInventoryStore(InventoryJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<Integer> quantityOf(String itemId) {
        return jpa.findById(itemId).map(e -> e.quantity);
    }

    @Override
    @Transactional
    public boolean tryReserve(String itemId, int qty) {
        // pessimistic lock: SELECT ... FOR UPDATE, then check + decrement in-tx
        InventoryEntity inv = jpa.findByIdForUpdate(itemId).orElse(null);
        if (inv == null || inv.quantity < qty) {
            return false;
        }
        inv.quantity -= qty;
        return true;
    }

    @Override
    @Transactional
    public void addQuantity(String itemId, int qty) {
        jpa.increment(itemId, qty);
    }

    @Override
    public Map<String, Integer> all() {
        Map<String, Integer> m = new LinkedHashMap<>();
        jpa.findAll().forEach(e -> m.put(e.itemId, e.quantity));
        return m;
    }
}
