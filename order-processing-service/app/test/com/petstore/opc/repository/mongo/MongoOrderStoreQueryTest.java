package com.petstore.opc.repository.mongo;

import com.petstore.opc.domain.ContactInfo;
import com.petstore.opc.domain.OrderLine;
import com.petstore.opc.domain.OrderStatus;
import com.petstore.opc.domain.SalesReport;
import com.petstore.opc.domain.WarehouseOrder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the {@link MongoOrderStore} query + mapping paths the sales and version tests don't:
 * the derived-query lookups that back the admin console — {@code orderIdsByStatus} (the PENDING
 * approval queue, index {@code ix_orders_status}) and {@code findAllByCreatedDesc} (newest-first
 * overview, index {@code ix_orders_created}) — the <b>full contact round-trip</b> ({@code shipTo}/
 * {@code billTo}, which every other test leaves null), and the <b>negative/empty</b> results a
 * caller must handle gracefully (missing id, no orders in a status, an empty date window).
 *
 * <p>Added while verifying happy- vs negative-case coverage of the Mongo adapter: these port
 * methods and the contact mapping were exercised only indirectly before.
 */
@DataMongoTest
@Import(MongoOrderStore.class)
@ActiveProfiles("mongo")
class MongoOrderStoreQueryTest extends MongoTestBase {

    @Autowired
    MongoOrderStore store;

    private static ContactInfo contact(String who) {
        return new ContactInfo(who, "Given", "1 Main St", null, "Town", "ST", "12345", "USA",
                "5551234567", who + "@x.com");
    }

    private static WarehouseOrder order(String id, OrderStatus status, Instant created) {
        return new WarehouseOrder(id, "u", "e@x.com", "en_US", "USD", 10.0, status,
                List.of(new OrderLine("i1", "p1", "DOGS", 1, 10.0)),
                contact("ship"), contact("bill"), created);
    }

    // ── happy path: full aggregate round-trip incl. embedded contacts ──────────────────

    @Test
    void save_thenFindById_roundTripsEveryFieldIncludingContacts() {
        Instant created = Instant.parse("2026-03-01T09:00:00Z");
        store.save(order("rt-1", OrderStatus.PENDING, created));

        WarehouseOrder found = store.findById("rt-1").orElseThrow();

        assertEquals("rt-1", found.orderId());
        assertEquals("USD", found.currency());
        assertEquals(OrderStatus.PENDING, found.status());
        assertEquals(created, found.created());
        assertEquals(1, found.lines().size());
        assertEquals("DOGS", found.lines().get(0).categoryId());
        // The contact subdocuments must survive the toDocument/toDomain mapping intact.
        assertNotNull(found.shipTo());
        assertEquals("ship", found.shipTo().familyName());
        assertEquals("ship@x.com", found.shipTo().email());
        assertNull(found.shipTo().streetName2(), "optional line-2 stays null");
        assertNotNull(found.billTo());
        assertEquals("bill", found.billTo().familyName());
    }

    // ── happy path: the two admin-console query methods ─────────────────────────────────

    @Test
    void orderIdsByStatus_returnsOnlyMatchingStatus() {
        store.save(order("p-1", OrderStatus.PENDING, Instant.parse("2026-01-01T00:00:00Z")));
        store.save(order("p-2", OrderStatus.PENDING, Instant.parse("2026-01-02T00:00:00Z")));
        store.save(order("a-1", OrderStatus.APPROVED, Instant.parse("2026-01-03T00:00:00Z")));

        List<String> pending = store.orderIdsByStatus(OrderStatus.PENDING);

        assertEquals(2, pending.size());
        assertTrue(pending.containsAll(List.of("p-1", "p-2")));
        assertFalse(pending.contains("a-1"));
    }

    @Test
    void findAllByCreatedDesc_ordersNewestFirst() {
        store.save(order("old", OrderStatus.PENDING, Instant.parse("2026-01-01T00:00:00Z")));
        store.save(order("new", OrderStatus.PENDING, Instant.parse("2026-06-01T00:00:00Z")));
        store.save(order("mid", OrderStatus.PENDING, Instant.parse("2026-03-01T00:00:00Z")));

        List<String> ids = store.findAllByCreatedDesc().stream().map(WarehouseOrder::orderId).toList();

        assertEquals(List.of("new", "mid", "old"), ids);
    }

    // ── negative / empty paths a caller must handle ─────────────────────────────────────

    @Test
    void findById_missing_returnsEmpty() {
        assertTrue(store.findById("does-not-exist").isEmpty());
    }

    @Test
    void statusOf_missing_returnsEmpty() {
        assertEquals(Optional.empty(), store.statusOf("does-not-exist"));
    }

    @Test
    void orderIdsByStatus_noneMatching_returnsEmptyList() {
        store.save(order("a-1", OrderStatus.APPROVED, Instant.parse("2026-01-01T00:00:00Z")));

        assertTrue(store.orderIdsByStatus(OrderStatus.DENIED).isEmpty());
    }

    @Test
    void findAllByCreatedDesc_noOrders_returnsEmptyList() {
        assertTrue(store.findAllByCreatedDesc().isEmpty());
    }

    @Test
    void aggregateSales_emptyWindow_returnsNoBuckets() {
        store.save(order("o1", OrderStatus.PENDING, Instant.parse("2026-06-01T00:00:00Z")));

        // A window that excludes every order must yield an empty report, not an error.
        SalesReport report = store.aggregateSales(
                Instant.parse("2020-01-01T00:00:00Z"), Instant.parse("2020-12-31T23:59:59Z"), null);

        assertTrue(report.buckets().isEmpty());
    }
}
