package com.petstore.opc.repository.mongo;

import com.petstore.opc.domain.OrderStatus;

import java.util.Arrays;
import java.util.List;

/**
 * Single source of truth for the MongoDB collection, field, index, and aggregation-key names used
 * across the {@code mongo}-profile adapters. Collecting them here keeps the three coupled places —
 * the {@code @Document}/{@code @Field} mapping ({@link WarehouseOrderDocument}, {@link OutboxDocument}),
 * the aggregation + update queries ({@link MongoOrderStore}, {@link MongoOutboxStore}), and the
 * validator/index setup ({@link MongoSchemaConfig}) — in lockstep, so a rename can't drift one out of
 * sync with the others (which Mongo would not catch: a mistyped field is silently a different field).
 *
 * <p>{@link #ORDER_STATUSES} is <b>derived from</b> {@link OrderStatus} rather than hand-listed, so the
 * {@code $jsonSchema} status enum can never fall behind the domain enum (adding a status automatically
 * widens the validator instead of silently rejecting the new value).
 *
 * <p>Only application-defined names live here. MongoDB's own command/BSON vocabulary
 * ({@code collMod}, {@code $jsonSchema}, {@code bsonType}, …) stays inline in {@link MongoSchemaConfig}
 * — it is the API DSL, the equivalent of SQL keywords, not a domain string.
 */
final class MongoSchema {

    private MongoSchema() {
    }

    // ── collections ───────────────────────────────────────────────────────────────────
    static final String ORDERS = "orders";
    static final String OUTBOX = "outbox";

    /** Mongo's document key field, shared by both collections. */
    static final String ID = "_id";

    // ── orders fields ─────────────────────────────────────────────────────────────────
    static final String F_STATUS = "status";
    static final String F_CURRENCY = "currency";
    static final String F_TOTAL_PRICE = "totalPrice";
    static final String F_CREATED = "created";
    static final String F_LINES = "lines";
    static final String F_LINE_ITEM_ID = F_LINES + ".itemId";
    static final String F_LINE_CATEGORY_ID = F_LINES + ".categoryId";
    static final String F_LINE_QUANTITY = F_LINES + ".quantity";
    static final String F_LINE_UNIT_PRICE = F_LINES + ".unitPrice";

    // ── outbox fields ─────────────────────────────────────────────────────────────────
    static final String F_DESTINATION = "destination";
    static final String F_IS_TOPIC = "is_topic";
    static final String F_EVENT_TYPE = "eventType";
    static final String F_PAYLOAD = "payload";
    static final String F_PUBLISHED_AT = "publishedAt";
    static final String F_ATTEMPTS = "attempts";

    // ── sales aggregation output keys ───────────────────────────────────────────────────
    static final String AGG_REVENUE = "revenue";
    static final String AGG_QUANTITY = "quantity";

    // ── index names ───────────────────────────────────────────────────────────────────
    static final String IX_ORDERS_STATUS = "ix_orders_status";
    static final String IX_ORDERS_CREATED = "ix_orders_created";
    static final String IX_OUTBOX_UNPUBLISHED = "ix_outbox_unpublished";

    /** Order-workflow states for the {@code $jsonSchema} status enum — derived from the domain enum. */
    static final List<String> ORDER_STATUSES =
            Arrays.stream(OrderStatus.values()).map(Enum::name).toList();
}
