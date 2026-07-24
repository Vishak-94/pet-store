package com.petstore.opc.repository.jpa;

import com.petstore.opc.domain.OrderLine;
import com.petstore.opc.domain.OrderStatus;
import com.petstore.opc.domain.SalesReport;
import com.petstore.opc.domain.SalesReport.SalesBucket;
import com.petstore.opc.domain.WarehouseOrder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the GROUP BY revenue/quantity aggregation over a date range (legacy getChartInfo). */
@DataJpaTest
@Import(JpaOrderStore.class)
class JpaOrderStoreSalesTest {

    @Autowired
    JpaOrderStore store;

    private static WarehouseOrder order(String id, Instant created, OrderLine... lines) {
        return new WarehouseOrder(id, "u", "e@x.com", "en_US", 0.0, OrderStatus.PENDING,
                List.of(lines), null, null, created);
    }

    @Test
    void aggregateByCategory_sumsRevenueAndQuantityInRange() {
        Instant jan = Instant.parse("2026-01-15T00:00:00Z");
        Instant feb = Instant.parse("2026-02-15T00:00:00Z");
        Instant outOfRange = Instant.parse("2025-12-31T00:00:00Z");

        store.save(order("o1", jan,
                new OrderLine("i1", "p1", "DOGS", 2, 10.0),
                new OrderLine("i2", "p2", "CATS", 1, 5.0)));
        store.save(order("o2", feb, new OrderLine("i1", "p1", "DOGS", 3, 10.0)));
        store.save(order("o3", outOfRange, new OrderLine("i1", "p1", "DOGS", 99, 10.0)));

        SalesReport report = store.aggregateSales(
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-12-31T23:59:59Z"), null);

        assertEquals("category", report.groupBy());
        Map<String, SalesBucket> byKey = report.buckets().stream()
                .collect(Collectors.toMap(SalesBucket::key, b -> b));
        assertEquals(50.0, byKey.get("DOGS").revenue());   // (2+3)*10, out-of-range excluded
        assertEquals(5, byKey.get("DOGS").quantity());
        assertEquals(5.0, byKey.get("CATS").revenue());
        assertEquals(1, byKey.get("CATS").quantity());
    }

    @Test
    void aggregateByItem_filtersToCategoryAndGroupsByItem() {
        Instant jan = Instant.parse("2026-01-15T00:00:00Z");
        store.save(order("o1", jan,
                new OrderLine("i1", "p1", "DOGS", 2, 10.0),
                new OrderLine("i2", "p2", "DOGS", 4, 2.5),
                new OrderLine("i3", "p3", "CATS", 9, 100.0)));

        SalesReport report = store.aggregateSales(
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-12-31T23:59:59Z"), "DOGS");

        assertEquals("item", report.groupBy());
        Map<String, SalesBucket> byKey = report.buckets().stream()
                .collect(Collectors.toMap(SalesBucket::key, b -> b));
        assertEquals(20.0, byKey.get("i1").revenue());
        assertEquals(10.0, byKey.get("i2").revenue());
        assertTrue(byKey.containsKey("i1") && byKey.containsKey("i2"));
        assertEquals(2, byKey.size(), "CATS item excluded by category filter");
    }
}
