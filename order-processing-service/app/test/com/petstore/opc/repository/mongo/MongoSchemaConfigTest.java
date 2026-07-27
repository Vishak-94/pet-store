package com.petstore.opc.repository.mongo;

import com.mongodb.MongoWriteException;
import com.petstore.opc.domain.OrderStatus;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link MongoSchemaConfig} against a real Mongo: after {@code applySchema()} the
 * {@code orders} collection carries the {@code $jsonSchema} validator (a document with a bad status
 * or no lines is rejected) and the expected indexes exist. This is the schemaless-store equivalent
 * of asserting the Flyway DDL took effect on the default profile.
 */
@DataMongoTest
@Import({MongoSchemaConfig.class, MongoOrderStore.class})
@ActiveProfiles("mongo")
class MongoSchemaConfigTest extends MongoTestBase {

    @Autowired
    MongoSchemaConfig schemaConfig;
    @Autowired
    MongoOrderStore store;

    @Test
    void appliesValidator_rejectingAnInvalidStatus() {
        schemaConfig.applySchema();

        // A raw insert bypassing the domain mapping, with a status outside the enum, must be rejected
        // by the $jsonSchema validator (validationAction: error).
        Document bad = new Document(MongoSchema.ID, "bad-1")
                .append(MongoSchema.F_STATUS, "SHIPPED_PART")     // not an OrderStatus value
                .append(MongoSchema.F_CURRENCY, "USD")
                .append(MongoSchema.F_TOTAL_PRICE, 10.0)
                .append(MongoSchema.F_LINES, List.of(new Document("itemId", "i1")));
        // The raw driver surfaces a validation failure as MongoWriteException code 121.
        MongoWriteException ex = assertThrows(MongoWriteException.class,
                () -> mongo.getCollection(MongoSchema.ORDERS).insertOne(bad),
                "validator must reject a status outside the OrderStatus enum");
        assertTrue(ex.getMessage().contains("failed validation"), ex.getMessage());
    }

    @Test
    void appliesValidator_rejectingAnOrderWithNoLines() {
        schemaConfig.applySchema();

        Document noLines = new Document(MongoSchema.ID, "bad-2")
                .append(MongoSchema.F_STATUS, OrderStatus.PENDING.name())
                .append(MongoSchema.F_CURRENCY, "USD")
                .append(MongoSchema.F_TOTAL_PRICE, 10.0)
                .append(MongoSchema.F_LINES, List.of());          // minItems: 1 → rejected
        assertThrows(MongoWriteException.class,
                () -> mongo.getCollection(MongoSchema.ORDERS).insertOne(noLines),
                "validator must reject an order with an empty lines array");
    }

    @Test
    void ensuresIndexes_onOrdersAndOutbox() {
        schemaConfig.applySchema();

        assertTrue(indexExists(MongoSchema.ORDERS, MongoSchema.IX_ORDERS_STATUS));
        assertTrue(indexExists(MongoSchema.ORDERS, MongoSchema.IX_ORDERS_CREATED));
        assertTrue(indexExists(MongoSchema.OUTBOX, MongoSchema.IX_OUTBOX_UNPUBLISHED));
    }

    private boolean indexExists(String collection, String indexName) {
        return mongo.indexOps(collection).getIndexInfo().stream()
                .anyMatch(ix -> indexName.equals(ix.getName()));
    }

    @Test
    void savingAValidOrderThroughTheStore_passesTheValidator() {
        schemaConfig.applySchema();

        // The real mapping path must satisfy the validator — a regression guard that the document
        // shape MongoOrderStore writes matches what the validator requires.
        store.save(new com.petstore.opc.domain.WarehouseOrder("ok-1", "u", "e@x.com", "en_US", "USD",
                10.0, OrderStatus.PENDING,
                List.of(new com.petstore.opc.domain.OrderLine("i1", "p1", "DOGS", 1, 10.0)),
                null, null, Instant.parse("2026-01-01T00:00:00Z")));

        assertTrue(store.findById("ok-1").isPresent());
    }
}
