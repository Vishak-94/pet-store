package com.petstore.opc.repository.jpa;

import com.petstore.opc.domain.ContactInfo;
import com.petstore.opc.domain.OrderLine;
import com.petstore.opc.domain.OrderStatus;
import com.petstore.opc.domain.SalesReport;
import com.petstore.opc.domain.SalesReport.SalesBucket;
import com.petstore.opc.domain.WarehouseOrder;
import com.petstore.opc.repository.OrderStore;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JPA adapter for the {@link OrderStore} port — the persistence side of the OPC
 * hexagon. It maps between the framework-free domain {@link WarehouseOrder} and the
 * JPA {@code WarehouseOrderEntity}/{@code WarehouseLineEntity}/{@code ContactInfoEmbeddable}
 * (the {@code toDomain}/{@code toEmbeddable} helpers), so the domain never sees JPA and
 * persistence stays swappable behind the port. Delegates all queries — status lookup,
 * status filter, newest-first listing, and the GROUP BY sales aggregation — to
 * {@link WarehouseOrderJpaRepository}.
 */
@Repository
public class JpaOrderStore implements OrderStore {

    private final WarehouseOrderJpaRepository jpa;

    JpaOrderStore(WarehouseOrderJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public WarehouseOrder save(WarehouseOrder o) {
        WarehouseOrderEntity e = new WarehouseOrderEntity();
        e.orderId = o.orderId();
        e.userId = o.userId();
        e.emailId = o.emailId();
        e.locale = o.locale();
        e.totalPrice = o.totalPrice();
        e.status = o.status();
        e.created = o.created();
        e.shipTo = toEmbeddable(o.shipTo());
        e.billTo = toEmbeddable(o.billTo());
        for (OrderLine l : o.lines()) {
            WarehouseLineEntity le = new WarehouseLineEntity();
            le.itemId = l.itemId();
            le.productId = l.productId();
            le.categoryId = l.categoryId();
            le.quantity = l.quantity();
            le.unitPrice = l.unitPrice();
            e.lines.add(le);
        }
        return toDomain(jpa.save(e));
    }

    @Override
    public Optional<WarehouseOrder> findById(String orderId) {
        return jpa.findById(orderId).map(this::toDomain);
    }

    @Override
    public void updateStatus(String orderId, OrderStatus status) {
        jpa.findById(orderId).ifPresent(e -> {
            e.status = status;
            jpa.save(e);
        });
    }

    @Override
    public Optional<OrderStatus> statusOf(String orderId) {
        return jpa.findById(orderId).map(e -> e.status);
    }

    @Override
    public List<String> orderIdsByStatus(OrderStatus status) {
        return jpa.findByStatus(status).stream().map(e -> e.orderId).toList();
    }

    @Override
    public List<WarehouseOrder> findAllByCreatedDesc() {
        return jpa.findAllByOrderByCreatedDesc().stream().map(this::toDomain).toList();
    }

    @Override
    public SalesReport aggregateSales(Instant start, Instant end, String categoryId) {
        boolean byItem = categoryId != null;
        List<Object[]> rows = byItem
                ? jpa.aggregateByItem(start, end, categoryId)
                : jpa.aggregateByCategory(start, end);
        List<SalesBucket> buckets = rows.stream()
                .map(r -> new SalesBucket((String) r[0],
                        r[1] == null ? 0.0 : ((Number) r[1]).doubleValue(),
                        r[2] == null ? 0 : ((Number) r[2]).intValue()))
                .toList();
        return new SalesReport(byItem ? SalesReport.GROUP_BY_ITEM : SalesReport.GROUP_BY_CATEGORY, buckets);
    }

    private WarehouseOrder toDomain(WarehouseOrderEntity e) {
        List<OrderLine> lines = new ArrayList<>();
        for (WarehouseLineEntity le : e.lines) {
            lines.add(new OrderLine(le.itemId, le.productId, le.categoryId, le.quantity, le.unitPrice));
        }
        return new WarehouseOrder(e.orderId, e.userId, e.emailId, e.locale, e.totalPrice, e.status, lines,
                toDomain(e.shipTo), toDomain(e.billTo), e.created);
    }

    private static ContactInfoEmbeddable toEmbeddable(ContactInfo c) {
        if (c == null) {
            return null;
        }
        ContactInfoEmbeddable ce = new ContactInfoEmbeddable();
        ce.familyName = c.familyName();
        ce.givenName = c.givenName();
        ce.streetName1 = c.streetName1();
        ce.streetName2 = c.streetName2();
        ce.city = c.city();
        ce.state = c.state();
        ce.zipCode = c.zipCode();
        ce.country = c.country();
        ce.telephone = c.telephone();
        ce.email = c.email();
        return ce;
    }

    private static ContactInfo toDomain(ContactInfoEmbeddable ce) {
        if (ce == null) {
            return null;
        }
        return new ContactInfo(ce.familyName, ce.givenName, ce.streetName1, ce.streetName2,
                ce.city, ce.state, ce.zipCode, ce.country, ce.telephone, ce.email);
    }
}
