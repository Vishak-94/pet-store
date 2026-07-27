package com.petstore.inventory.repository.jpa;

import com.petstore.inventory.repository.FulfilledOrderStore;
import org.springframework.stereotype.Repository;

/**
 * JPA adapter for the {@link FulfilledOrderStore} port — the durable dedup ledger backing
 * idempotent fulfilment. Records each shipped {@code orderId} (primary key) so
 * {@code FulfilmentService} can skip an order that was already decremented, whether the
 * duplicate arrives as a JMS redelivery or as a fresh re-driven event on restock. Both
 * methods null-guard defensively even though callers never pass null.
 */
@Repository
public class JpaFulfilledOrderStore implements FulfilledOrderStore {

    private final FulfilledOrderJpaRepository jpa;

    JpaFulfilledOrderStore(FulfilledOrderJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public boolean isFulfilled(String orderId) {
        return orderId != null && jpa.existsById(orderId);
    }

    @Override
    public void markFulfilled(String orderId) {
        // No caller passes null (guarded in FulfilmentService), but stay defensive.
        if (orderId != null) {
            jpa.save(new FulfilledOrderEntity(orderId));
        }
    }
}
