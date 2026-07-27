package com.petstore.opc.repository.mongo;

import com.petstore.opc.repository.OutboxMessage;
import com.petstore.opc.repository.OutboxStore;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * MongoDB adapter for the {@link OutboxStore} port — the {@code mongo}-profile transactional
 * outbox, the counterpart of {@code JpaOutboxStore}. Maps between the framework-free
 * {@link OutboxMessage} and {@link OutboxDocument}; the port id is the document {@code _id}
 * hex string (no numeric identity, unlike the JPA {@code Long}).
 *
 * <p>The relay's state stamps ({@code markPublished}/{@code recordFailure}) are atomic
 * single-document updates via {@link MongoTemplate} — no ambient transaction needed (each
 * update is its own atomic op, mirroring the JPA adapter's self-transactional {@code @Modifying}
 * updates). {@code recordFailure} uses {@code findAndModify} with {@code returnNew} so it reads
 * back the incremented attempt count in one round-trip, letting the relay detect the park threshold.
 */
@Repository
@Profile("mongo")
public class MongoOutboxStore implements OutboxStore {

    private final OutboxMongoRepository repo;
    private final MongoTemplate mongo;

    MongoOutboxStore(OutboxMongoRepository repo, MongoTemplate mongo) {
        this.repo = repo;
        this.mongo = mongo;
    }

    @Override
    public void enqueue(OutboxMessage message) {
        OutboxDocument d = new OutboxDocument();
        d.destination = message.destination();
        d.topic = message.topic();
        d.eventType = message.eventType();
        d.payload = message.payload();
        d.orderId = message.orderId();
        d.createdAt = Instant.now();
        d.publishedAt = null;
        d.attempts = 0;
        repo.save(d);
    }

    @Override
    public List<OutboxMessage> fetchUnpublished(int limit, int maxAttempts) {
        return repo.findByPublishedAtIsNullAndAttemptsLessThanOrderByCreatedAtAsc(maxAttempts, PageRequest.of(0, limit))
                .stream()
                .map(this::toMessage)
                .toList();
    }

    @Override
    public void markPublished(String id) {
        mongo.updateFirst(Query.query(where(MongoSchema.ID).is(id)),
                new Update().set(MongoSchema.F_PUBLISHED_AT, Instant.now()), OutboxDocument.class);
    }

    @Override
    public int recordFailure(String id) {
        // Atomic increment + read-back in one round-trip so the relay sees the new count and can
        // tell when the row has just reached its park threshold (mirrors the JPA re-read).
        OutboxDocument updated = mongo.findAndModify(
                Query.query(where(MongoSchema.ID).is(id)),
                new Update().inc(MongoSchema.F_ATTEMPTS, 1),
                FindAndModifyOptions.options().returnNew(true),
                OutboxDocument.class);
        return updated == null ? 0 : updated.attempts;
    }

    private OutboxMessage toMessage(OutboxDocument d) {
        return new OutboxMessage(d.id, d.destination, d.topic, d.eventType, d.payload, d.orderId);
    }
}
