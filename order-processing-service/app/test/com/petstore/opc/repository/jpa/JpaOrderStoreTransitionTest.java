package com.petstore.opc.repository.jpa;

import com.petstore.opc.domain.OrderLine;
import com.petstore.opc.domain.OrderStatus;
import com.petstore.opc.domain.WarehouseOrder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the store-chokepoint lifecycle guard on {@code updateStatus}: the order lifecycle is
 * PENDING → APPROVED → COMPLETED (or PENDING → DENIED), and a terminal order can never be
 * reversed. The guard lives in the store so no caller can bypass it.
 */
@DataJpaTest
@Import(JpaOrderStore.class)
class JpaOrderStoreTransitionTest {

    @Autowired
    JpaOrderStore store;

    private WarehouseOrder seed(String id, OrderStatus status) {
        return store.save(new WarehouseOrder(id, "u", "e@x.com", "en_US", "USD", 0.0, status,
                List.of(new OrderLine("i1", "p1", "DOGS", 1, 10.0)), null, null, Instant.now()));
    }

    @Test
    void happyPath_pendingToApprovedToCompleted() {
        seed("o1", OrderStatus.PENDING);
        store.updateStatus("o1", OrderStatus.APPROVED);
        store.updateStatus("o1", OrderStatus.COMPLETED);
        assertEquals(OrderStatus.COMPLETED, store.statusOf("o1").orElseThrow());
    }

    @Test
    void completedCannotBeReversedToApproved() {
        seed("o2", OrderStatus.COMPLETED);
        assertThrows(IllegalStateException.class,
                () -> store.updateStatus("o2", OrderStatus.APPROVED));
        assertEquals(OrderStatus.COMPLETED, store.statusOf("o2").orElseThrow());
    }

    @Test
    void deniedIsTerminal() {
        seed("o3", OrderStatus.DENIED);
        assertThrows(IllegalStateException.class,
                () -> store.updateStatus("o3", OrderStatus.APPROVED));
        assertEquals(OrderStatus.DENIED, store.statusOf("o3").orElseThrow());
    }

    @Test
    void cannotSkipApprovalPendingStraightToCompleted() {
        seed("o4", OrderStatus.PENDING);
        assertThrows(IllegalStateException.class,
                () -> store.updateStatus("o4", OrderStatus.COMPLETED));
    }

    @Test
    void sameStatusWriteIsIdempotentNoOp() {
        seed("o5", OrderStatus.COMPLETED);
        store.updateStatus("o5", OrderStatus.COMPLETED);   // must not throw (JMS redelivery safe)
        assertEquals(OrderStatus.COMPLETED, store.statusOf("o5").orElseThrow());
    }
}
