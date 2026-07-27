package com.petstore.opc.repository.mongo;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * MongoDB document form of a transactional-outbox row — the {@code mongo}-profile
 * counterpart of the JPA {@code OutboxEntity}. An outbound event is inserted here in the
 * same (multi-document) transaction as the order-status write, then {@code OutboxRelay}
 * publishes unpublished documents and stamps {@link #publishedAt}.
 *
 * <p>The {@code _id} is a Mongo {@code ObjectId} rendered as its hex string; the
 * {@link com.petstore.opc.repository.OutboxStore} port carries the id as a {@code String},
 * so no numeric identity is needed (unlike the JPA adapter's {@code Long}). Payload is stored
 * as the already-serialized JSON string, exactly as under JPA, so the relay's deserialization
 * is byte-identical across stores.
 */
@Document(collection = MongoSchema.OUTBOX)
class OutboxDocument {

    @Id
    String id;

    String destination;
    @Field(MongoSchema.F_IS_TOPIC) boolean topic;
    String eventType;

    /** The event serialized as JSON (same Jackson mapper the JMS converter uses). */
    String payload;

    String orderId;
    Instant createdAt;

    /** {@code null} until the relay has published it; set = delivered. */
    Instant publishedAt;

    /** Publish attempts; parked as a poison document once it hits the relay's cap. */
    int attempts;
}
