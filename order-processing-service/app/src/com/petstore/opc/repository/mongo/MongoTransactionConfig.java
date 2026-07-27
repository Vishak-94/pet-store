package com.petstore.opc.repository.mongo;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;

/**
 * Enables multi-document transactions on the {@code mongo} profile so the transactional outbox
 * keeps its atomicity guarantee. The outbox invariant (OPC #3) requires the event row and the
 * order-status write to commit or roll back <b>together</b>; under JPA that is a plain JDBC
 * transaction, but Spring Data MongoDB only makes {@code @Transactional} open a real Mongo session
 * transaction when a {@link MongoTransactionManager} bean is present. Registering it here means
 * {@code FulfilmentService}/{@code AdminService}'s existing {@code @Transactional} methods bind the
 * order save and the {@code OutboxWriter.enqueue} into one Mongo transaction.
 *
 * <p>Mongo multi-document transactions require a replica set — which is exactly why the runtime is
 * configured as a single-node {@code rs0} (see {@code application.yml} + {@code docker-compose.yml}).
 */
@Configuration
@Profile("mongo")
public class MongoTransactionConfig {

    @Bean
    MongoTransactionManager mongoTransactionManager(MongoDatabaseFactory dbFactory) {
        return new MongoTransactionManager(dbFactory);
    }
}
