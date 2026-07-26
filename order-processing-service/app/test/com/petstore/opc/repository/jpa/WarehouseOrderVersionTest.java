package com.petstore.opc.repository.jpa;

import com.petstore.opc.domain.OrderStatus;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the B1 optimistic-lock guard on the order aggregate (the {@code @Version} column added in
 * V3). Two admins acting on the same PENDING order both read version 0; the first commit wins and
 * bumps the version, and the second — writing against the now-stale version — must fail rather than
 * silently overwrite. This is what turns the approve+deny race from last-writer-wins into a
 * detected conflict (surfaced as 409 by the OPC API exception handler).
 */
@DataJpaTest
class WarehouseOrderVersionTest {

    @Autowired
    TestEntityManager em;

    private String persistPendingOrder() {
        WarehouseOrderEntity e = new WarehouseOrderEntity();
        e.orderId = "o-ver-1";
        e.userId = "u";
        e.emailId = "e@x.com";
        e.locale = "en_US";
        e.totalPrice = 10.0;
        e.status = OrderStatus.PENDING;
        e.created = Instant.parse("2026-01-01T00:00:00Z");
        em.persist(e);
        em.flush();
        em.clear();   // detach so subsequent finds start from the DB, not the persistence-context cache
        return e.orderId;
    }

    @Test
    void versionStartsAtZeroAndIncrementsOnUpdate() {
        String id = persistPendingOrder();

        WarehouseOrderEntity loaded = em.find(WarehouseOrderEntity.class, id);
        assertEquals(0L, loaded.version, "new order starts at version 0");

        loaded.status = OrderStatus.APPROVED;
        em.flush();
        assertEquals(1L, loaded.version, "a status write bumps the version");
    }

    @Test
    void staleWrite_afterAConcurrentUpdate_throwsOptimisticLock() {
        String id = persistPendingOrder();

        // Reader A takes a snapshot at version 0, then detaches it (simulating a second request/thread
        // that read the same PENDING order before either wrote).
        WarehouseOrderEntity staleA = em.find(WarehouseOrderEntity.class, id);
        em.detach(staleA);

        // Writer B (approve) commits first → version 0 → 1 in the DB.
        WarehouseOrderEntity freshB = em.find(WarehouseOrderEntity.class, id);
        freshB.status = OrderStatus.APPROVED;
        em.flush();
        em.clear();

        // Writer A (deny) now writes against its stale version-0 snapshot → must be rejected.
        staleA.status = OrderStatus.DENIED;
        assertThrows(OptimisticLockException.class, () -> {
            em.getEntityManager().merge(staleA);
            em.flush();
        });
    }
}
