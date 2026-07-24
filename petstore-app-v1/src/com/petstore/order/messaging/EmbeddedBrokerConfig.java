package com.petstore.order.messaging;

import org.springframework.boot.autoconfigure.jms.artemis.ArtemisConfigurationCustomizer;
import org.springframework.context.annotation.Configuration;

/**
 * Opens a TCP (Netty) acceptor on the monolith's embedded Artemis broker so that
 * warehouse-service can connect to the SAME broker over tcp://localhost:61616 and
 * share the PurchaseOrderQueue. Without this, the embedded broker is in-VM only.
 */
@Configuration
public class EmbeddedBrokerConfig implements ArtemisConfigurationCustomizer {

    @Override
    public void customize(org.apache.activemq.artemis.core.config.Configuration configuration) {
        try {
            configuration.addAcceptorConfiguration("netty", "tcp://0.0.0.0:61616");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to add Artemis TCP acceptor", e);
        }
    }
}
