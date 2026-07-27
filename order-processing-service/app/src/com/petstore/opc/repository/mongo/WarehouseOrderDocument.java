package com.petstore.opc.repository.mongo;

import com.petstore.opc.domain.OrderStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * MongoDB document form of the warehouse order aggregate — the {@code mongo}-profile
 * counterpart of the JPA {@code WarehouseOrderEntity}. The whole aggregate is ONE document:
 * lines and both contacts are <b>embedded</b> (no joins), which is why the sales aggregation
 * uses {@code $unwind} over the embedded {@code lines} array rather than a SQL {@code JOIN}.
 *
 * <p>{@code orderId} is the document {@code _id} (the intake mints it, so it is a natural key —
 * a duplicate intake is an idempotent upsert-or-skip, exactly as under JPA). The {@link #version}
 * carries the same optimistic-lock guard as the JPA {@code @Version} column: Spring Data MongoDB
 * turns a {@code save} of a loaded document into a version-guarded {@code update ... where _id and
 * version}, so the approve+deny race still fails the loser with an
 * {@code OptimisticLockingFailureException} (→ 409) instead of last-writer-wins.
 *
 * <p>Mapped fields are framework-annotated here only; the domain {@code WarehouseOrder} stays
 * framework-free and the mapping lives entirely in {@link MongoOrderStore}.
 */
@Document(collection = MongoSchema.ORDERS)
class WarehouseOrderDocument {

    @Id
    String orderId;

    String userId;
    String emailId;
    String locale;
    /** ISO 4217 currency the total is denominated in; ApprovalPolicy keys the threshold on it. */
    String currency;
    double totalPrice;

    /** Stored as the enum NAME (string), matching the JPA {@code @Enumerated(STRING)} column. */
    OrderStatus status;

    /**
     * Optimistic-lock version. Null on first insert (Spring Data seeds it to 0); a version-guarded
     * update bumps it on every save. Mirrors the JPA {@code @Version} column (see B1 / V3 migration).
     */
    @Version
    Long version;

    /** Order-received timestamp (legacy PurchaseOrder poDate) — for date-range sales aggregation. */
    Instant created;

    /** Embedded order lines — the array {@code $unwind} expands for the sales GROUP BY. */
    @Field(MongoSchema.F_LINES)
    List<OrderLineDocument> lines = new ArrayList<>();

    /** Ship-to contact info collected at checkout (embedded sub-document). */
    ContactInfoDocument shipTo;

    /** Bill-to contact info collected at checkout (embedded sub-document). */
    ContactInfoDocument billTo;

    /** Embedded order line (no {@code @Document} — it only ever lives inside an order). */
    static class OrderLineDocument {
        String itemId;
        String productId;
        String categoryId;
        int quantity;
        double unitPrice;
    }

    /** Embedded ship-to / bill-to contact info (flattened, mirrors ContactInfoEmbeddable). */
    static class ContactInfoDocument {
        String familyName;
        String givenName;
        String streetName1;
        String streetName2;
        String city;
        String state;
        String zipCode;
        String country;
        String telephone;
        String email;
    }
}
