package com.petstore.opc.repository.mongo;

import com.petstore.opc.domain.OrderLine;
import com.petstore.opc.domain.OrderStatus;
import com.petstore.opc.domain.WarehouseOrder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * MongoDB counterpart of {@code WarehouseOrderVersionTest}: pins the {@code @Version} optimistic-lock
 * guard on the order document. Two admins act on the same PENDING order from the same version; the
 * first save wins and bumps the version, and the second — saving against the now-stale in-memory
 * document — must fail with {@link OptimisticLockingFailureException} rather than silently overwrite.
 * This is the approve+deny race turning into a detected conflict (surfaced as 409), same as under JPA.
 */
@DataMongoTest
@Import(MongoOrderStore.class)
@ActiveProfiles("mongo")
class MongoOrderStoreVersionTest extends MongoTestBase {

    @Autowired
    MongoOrderStore store;

    private WarehouseOrder pending(String id) {
        return new WarehouseOrder(id, "u", "e@x.com", "en_US", "USD", 10.0, OrderStatus.PENDING,
                List.of(new OrderLine("i1", "p1", "DOGS", 1, 10.0)), null, null,
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void updateStatus_appliesTransition() {
        store.save(pending("o-ver-1"));

        store.updateStatus("o-ver-1", OrderStatus.APPROVED);

        assertEquals(OrderStatus.APPROVED, store.statusOf("o-ver-1").orElseThrow());
    }

    @Test
    void staleWrite_afterAConcurrentUpdate_throwsOptimisticLock() {
        store.save(pending("o-ver-2"));

        // Two readers load the same version-0 document (two requests reading the PENDING order).
        WarehouseOrderDocument staleA = mongo.findById("o-ver-2", WarehouseOrderDocument.class);
        WarehouseOrderDocument freshB = mongo.findById("o-ver-2", WarehouseOrderDocument.class);

        // Writer B (approve) commits first → version 0 → 1 in the DB.
        freshB.status = OrderStatus.APPROVED;
        mongo.save(freshB);

        // Writer A (deny) now saves against its stale version-0 snapshot → must be rejected.
        staleA.status = OrderStatus.DENIED;
        assertThrows(OptimisticLockingFailureException.class, () -> mongo.save(staleA));

        assertEquals(OrderStatus.APPROVED, store.statusOf("o-ver-2").orElseThrow(),
                "the winning transition stands; the loser did not overwrite it");
    }
}
