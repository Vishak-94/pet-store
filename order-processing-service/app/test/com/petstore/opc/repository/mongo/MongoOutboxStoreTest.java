package com.petstore.opc.repository.mongo;

import com.petstore.opc.repository.OutboxMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MongoDB counterpart of {@code JpaOutboxStoreTest}: the outbox persistence port over the
 * {@code outbox} collection — enqueue, oldest-first draining of the unpublished backlog,
 * poison-document exclusion by attempt cap, and the two state stamps (mark delivered / record
 * failure). Same contract the relay depends on, verified against a real Mongo.
 */
@DataMongoTest
@Import(MongoOutboxStore.class)
@ActiveProfiles("mongo")
class MongoOutboxStoreTest extends MongoTestBase {

    @Autowired
    MongoOutboxStore store;

    private static OutboxMessage msg(String orderId) {
        return OutboxMessage.pending("ApprovedOrderQueue", false, "OrderApproved",
                "{\"orderId\":\"" + orderId + "\"}", orderId);
    }

    @Test
    void enqueueThenFetch_returnsUnpublishedOldestFirst() {
        store.enqueue(msg("o1"));
        store.enqueue(msg("o2"));

        List<OutboxMessage> batch = store.fetchUnpublished(10, 10);

        assertEquals(2, batch.size());
        assertEquals("o1", batch.get(0).orderId());
        assertEquals("o2", batch.get(1).orderId());
        assertEquals("ApprovedOrderQueue", batch.get(0).destination());
        assertEquals("OrderApproved", batch.get(0).eventType());
    }

    @Test
    void fetchUnpublished_honoursLimit() {
        store.enqueue(msg("o1"));
        store.enqueue(msg("o2"));
        store.enqueue(msg("o3"));

        assertEquals(2, store.fetchUnpublished(2, 10).size());
    }

    @Test
    void markPublished_removesFromUnpublishedBacklog() {
        store.enqueue(msg("o1"));
        String id = store.fetchUnpublished(10, 10).get(0).id();

        store.markPublished(id);

        assertTrue(store.fetchUnpublished(10, 10).isEmpty(), "published document must not be re-fetched");
    }

    @Test
    void recordFailure_parksDocumentOnceAttemptsReachCap() {
        store.enqueue(msg("o1"));
        String id = store.fetchUnpublished(10, 2).get(0).id();

        assertEquals(1, store.recordFailure(id), "returns the new attempt count");
        assertEquals(1, store.fetchUnpublished(10, 2).size(), "still under cap → retried");

        assertEquals(2, store.recordFailure(id), "returns the new attempt count");
        assertTrue(store.fetchUnpublished(10, 2).isEmpty(), "at cap → parked as poison, not retried");
    }
}
