package com.petstore.opc.domain;

import java.util.Map;
import java.util.Set;

/**
 * Order workflow states + allowed transitions (owned by warehouse — it is the
 * single writer of order status now). Same contract as the monolith's original.
 */
public enum OrderStatus {
    PENDING, APPROVED, DENIED, COMPLETED;

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = Map.of(
            PENDING,   Set.of(APPROVED, DENIED),
            APPROVED,  Set.of(COMPLETED),
            DENIED,    Set.of(),
            COMPLETED, Set.of()
    );

    public boolean canGoTo(OrderStatus target) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(target);
    }
}
