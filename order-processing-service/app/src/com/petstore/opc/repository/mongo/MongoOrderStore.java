package com.petstore.opc.repository.mongo;

import com.petstore.opc.domain.ContactInfo;
import com.petstore.opc.domain.OrderLine;
import com.petstore.opc.domain.OrderStatus;
import com.petstore.opc.domain.SalesReport;
import com.petstore.opc.domain.SalesReport.SalesBucket;
import com.petstore.opc.domain.WarehouseOrder;
import com.petstore.opc.repository.OrderStore;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.stereotype.Repository;
import org.bson.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.springframework.data.mongodb.core.aggregation.Aggregation.group;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.match;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.unwind;
import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * MongoDB adapter for the {@link OrderStore} port — the {@code mongo}-profile persistence side of
 * the OPC hexagon, the counterpart of {@code JpaOrderStore}. It maps between the framework-free
 * domain {@link WarehouseOrder} and {@link WarehouseOrderDocument} ({@code toDocument}/{@code
 * toDomain}), so the domain never sees MongoDB and persistence stays swappable behind the port.
 *
 * <p>CRUD + status queries go through the derived-query {@link WarehouseOrderMongoRepository}. The
 * two GROUP BY sales aggregations (the legacy {@code getChartInfo}) use a {@link MongoTemplate}
 * pipeline — {@code $match} the date range → {@code $unwind} the embedded {@code lines} array →
 * {@code $group} by category (or by item within a category) summing revenue (Σ qty·unitPrice) and
 * quantity (Σ qty) — reproducing the SQL {@code JOIN ... GROUP BY} exactly (same figures as
 * {@code JpaOrderStore.aggregateSales}, pinned by the sales parity tests).
 *
 * <p>{@code updateStatus} loads → mutates → saves so the {@code @Version} optimistic-lock guard
 * fires on a conflicting concurrent write (approve+deny race → the loser gets an
 * {@code OptimisticLockingFailureException}, surfaced as 409), matching the JPA store's B1 behaviour.
 */
@Repository
@Profile("mongo")
public class MongoOrderStore implements OrderStore {

    private final WarehouseOrderMongoRepository repo;
    private final MongoTemplate mongo;

    MongoOrderStore(WarehouseOrderMongoRepository repo, MongoTemplate mongo) {
        this.repo = repo;
        this.mongo = mongo;
    }

    @Override
    public WarehouseOrder save(WarehouseOrder o) {
        return toDomain(repo.save(toDocument(o)));
    }

    @Override
    public Optional<WarehouseOrder> findById(String orderId) {
        return repo.findById(orderId).map(this::toDomain);
    }

    @Override
    public void updateStatus(String orderId, OrderStatus status) {
        // Load → mutate → save (not a blind $set) so the @Version guard turns a conflicting
        // concurrent transition into an OptimisticLockingFailureException, exactly as under JPA.
        repo.findById(orderId).ifPresent(d -> {
            d.status = status;
            repo.save(d);
        });
    }

    @Override
    public Optional<OrderStatus> statusOf(String orderId) {
        return repo.findById(orderId).map(d -> d.status);
    }

    @Override
    public List<String> orderIdsByStatus(OrderStatus status) {
        return repo.findByStatus(status).stream().map(d -> d.orderId).toList();
    }

    @Override
    public List<WarehouseOrder> findAllByCreatedDesc() {
        return repo.findAllByOrderByCreatedDesc().stream().map(this::toDomain).toList();
    }

    @Override
    public SalesReport aggregateSales(Instant start, Instant end, String categoryId) {
        boolean byItem = categoryId != null;
        // $match the received-date window, then expand the embedded lines so each line is a row
        // (the Mongo equivalent of JOIN o.lines l), optionally narrow to one category, then $group.
        List<AggregationOperation> ops = new ArrayList<>();
        ops.add(match(where(MongoSchema.F_CREATED).gte(Date.from(start)).lte(Date.from(end))));
        ops.add(unwind(MongoSchema.F_LINES));
        if (byItem) {
            ops.add(match(where(MongoSchema.F_LINE_CATEGORY_ID).is(categoryId)));
        }
        String groupField = byItem ? MongoSchema.F_LINE_ITEM_ID : MongoSchema.F_LINE_CATEGORY_ID;
        ops.add(group(groupField)
                // revenue = Σ (quantity * unitPrice); quantity = Σ quantity — same as the JPQL SUMs.
                .sum(org.springframework.data.mongodb.core.aggregation.ArithmeticOperators.Multiply
                        .valueOf(MongoSchema.F_LINE_QUANTITY).multiplyBy(MongoSchema.F_LINE_UNIT_PRICE))
                        .as(MongoSchema.AGG_REVENUE)
                .sum(MongoSchema.F_LINE_QUANTITY).as(MongoSchema.AGG_QUANTITY));

        AggregationResults<Document> results = mongo.aggregate(
                newAggregation(ops), MongoSchema.ORDERS, Document.class);

        List<SalesBucket> buckets = results.getMappedResults().stream()
                .map(r -> new SalesBucket(
                        r.getString(MongoSchema.ID),
                        r.get(MongoSchema.AGG_REVENUE) == null ? 0.0 : ((Number) r.get(MongoSchema.AGG_REVENUE)).doubleValue(),
                        r.get(MongoSchema.AGG_QUANTITY) == null ? 0 : ((Number) r.get(MongoSchema.AGG_QUANTITY)).intValue()))
                .toList();
        return new SalesReport(byItem ? SalesReport.GROUP_BY_ITEM : SalesReport.GROUP_BY_CATEGORY, buckets);
    }

    // ── mapping ──────────────────────────────────────────────────────────────────────

    private WarehouseOrderDocument toDocument(WarehouseOrder o) {
        WarehouseOrderDocument d = new WarehouseOrderDocument();
        d.orderId = o.orderId();
        d.userId = o.userId();
        d.emailId = o.emailId();
        d.locale = o.locale();
        d.currency = o.currency();
        d.totalPrice = o.totalPrice();
        d.status = o.status();
        d.created = o.created();
        d.shipTo = toDocument(o.shipTo());
        d.billTo = toDocument(o.billTo());
        for (OrderLine l : o.lines()) {
            WarehouseOrderDocument.OrderLineDocument le = new WarehouseOrderDocument.OrderLineDocument();
            le.itemId = l.itemId();
            le.productId = l.productId();
            le.categoryId = l.categoryId();
            le.quantity = l.quantity();
            le.unitPrice = l.unitPrice();
            d.lines.add(le);
        }
        return d;
    }

    private WarehouseOrder toDomain(WarehouseOrderDocument d) {
        List<OrderLine> lines = new ArrayList<>();
        for (WarehouseOrderDocument.OrderLineDocument le : d.lines) {
            lines.add(new OrderLine(le.itemId, le.productId, le.categoryId, le.quantity, le.unitPrice));
        }
        return new WarehouseOrder(d.orderId, d.userId, d.emailId, d.locale, d.currency, d.totalPrice,
                d.status, lines, toDomain(d.shipTo), toDomain(d.billTo), d.created);
    }

    private static WarehouseOrderDocument.ContactInfoDocument toDocument(ContactInfo c) {
        if (c == null) {
            return null;
        }
        WarehouseOrderDocument.ContactInfoDocument cd = new WarehouseOrderDocument.ContactInfoDocument();
        cd.familyName = c.familyName();
        cd.givenName = c.givenName();
        cd.streetName1 = c.streetName1();
        cd.streetName2 = c.streetName2();
        cd.city = c.city();
        cd.state = c.state();
        cd.zipCode = c.zipCode();
        cd.country = c.country();
        cd.telephone = c.telephone();
        cd.email = c.email();
        return cd;
    }

    private static ContactInfo toDomain(WarehouseOrderDocument.ContactInfoDocument cd) {
        if (cd == null) {
            return null;
        }
        return new ContactInfo(cd.familyName, cd.givenName, cd.streetName1, cd.streetName2,
                cd.city, cd.state, cd.zipCode, cd.country, cd.telephone, cd.email);
    }
}
