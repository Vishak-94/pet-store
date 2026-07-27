package com.petstore.opc.repository.mongo;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.domain.Sort;

import java.util.List;

/**
 * Applies the MongoDB collection contract on startup ({@code mongo} profile only) — the schemaless
 * counterpart of the Flyway migrations that own the H2 schema. MongoDB doesn't enforce structure by
 * default; this replaces that safety net with an explicit, versioned setup step so the {@code mongo}
 * profile has the same guarantees the DDL gave the default profile:
 *
 * <ul>
 *   <li><b>{@code orders}</b> — a {@code $jsonSchema} validator (required orderId/status/totalPrice/
 *       currency + non-empty lines, status ∈ the {@link com.petstore.opc.domain.OrderStatus} enum),
 *       plus indexes {@code {status:1}} (the PENDING queue / status filter) and {@code {created:-1}}
 *       (the newest-first overview + the sales date-range scan).</li>
 *   <li><b>{@code outbox}</b> — a validator (required destination/eventType/payload/attempts), plus
 *       the {@code {publishedAt:1, attempts:1}} index backing the relay's unpublished-under-cap scan.</li>
 * </ul>
 *
 * <p>Validators are applied with {@code validationLevel: moderate} + {@code validationAction: error}:
 * a bad insert/update is rejected, but pre-existing documents that predate a validator change are not
 * re-checked — so the validator can be tightened later without a migration (the schema-evolution
 * property that motivated choosing a validator over none). Idempotent: {@code collMod} on an existing
 * collection, {@code create} on a missing one, so re-running on every boot is safe.
 */
@Configuration
@Profile("mongo")
public class MongoSchemaConfig {

    private static final Logger log = LoggerFactory.getLogger(MongoSchemaConfig.class);

    private final MongoTemplate mongo;

    MongoSchemaConfig(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    // Run after the context is ready (connection established) rather than in a @PostConstruct so a
    // transient startup connectivity blip doesn't abort bean creation.
    @EventListener(ApplicationReadyEvent.class)
    public void applySchema() {
        applyValidator(MongoSchema.ORDERS, ordersValidator());
        applyValidator(MongoSchema.OUTBOX, outboxValidator());

        mongo.indexOps(MongoSchema.ORDERS).ensureIndex(
                new Index().on(MongoSchema.F_STATUS, Sort.Direction.ASC).named(MongoSchema.IX_ORDERS_STATUS));
        mongo.indexOps(MongoSchema.ORDERS).ensureIndex(
                new Index().on(MongoSchema.F_CREATED, Sort.Direction.DESC).named(MongoSchema.IX_ORDERS_CREATED));
        mongo.indexOps(MongoSchema.OUTBOX).ensureIndex(new Index()
                .on(MongoSchema.F_PUBLISHED_AT, Sort.Direction.ASC)
                .on(MongoSchema.F_ATTEMPTS, Sort.Direction.ASC)
                .named(MongoSchema.IX_OUTBOX_UNPUBLISHED));
        log.info("MongoDB schema applied: orders + outbox validators and indexes ensured");
    }

    /**
     * Ensure the collection exists (create it plain if missing) then {@code collMod} the validator
     * onto it — one code path for both first-boot and re-boot, so it's fully idempotent. Using the
     * raw {@code collMod} command (rather than {@code CollectionOptions} on create) keeps the
     * {@code $jsonSchema}/level/action in one place and lets a later boot tighten the validator.
     */
    private void applyValidator(String collection, Document schema) {
        if (!mongo.collectionExists(collection)) {
            mongo.createCollection(collection);
        }
        mongo.getDb().runCommand(new Document("collMod", collection)
                .append("validator", new Document("$jsonSchema", schema))
                .append("validationLevel", "moderate")
                .append("validationAction", "error"));
    }

    private static Document ordersValidator() {
        return new Document()
                .append("bsonType", "object")
                .append("required", List.of(MongoSchema.ID, MongoSchema.F_STATUS, MongoSchema.F_CURRENCY,
                        MongoSchema.F_TOTAL_PRICE, MongoSchema.F_LINES))
                .append("properties", new Document()
                        .append(MongoSchema.ID, new Document("bsonType", "string"))
                        .append(MongoSchema.F_STATUS, new Document("enum", MongoSchema.ORDER_STATUSES)
                                .append("description", "must be one of the OrderStatus enum values"))
                        .append(MongoSchema.F_CURRENCY, new Document("bsonType", "string"))
                        .append(MongoSchema.F_TOTAL_PRICE, new Document("bsonType", List.of("double", "int", "long")))
                        .append(MongoSchema.F_LINES, new Document("bsonType", "array")
                                .append("minItems", 1)
                                .append("description", "an order must have at least one line")));
    }

    private static Document outboxValidator() {
        return new Document()
                .append("bsonType", "object")
                .append("required", List.of(MongoSchema.F_DESTINATION, MongoSchema.F_EVENT_TYPE,
                        MongoSchema.F_PAYLOAD, MongoSchema.F_ATTEMPTS))
                .append("properties", new Document()
                        .append(MongoSchema.F_DESTINATION, new Document("bsonType", "string"))
                        .append(MongoSchema.F_EVENT_TYPE, new Document("bsonType", "string"))
                        .append(MongoSchema.F_PAYLOAD, new Document("bsonType", "string"))
                        .append(MongoSchema.F_ATTEMPTS, new Document("bsonType", List.of("int", "long"))));
    }
}
