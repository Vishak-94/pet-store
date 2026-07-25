package com.petstore.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.petstore.messaging.events.InvoiceEvent;
import com.petstore.messaging.events.OrderApprovedEvent;
import com.petstore.messaging.events.OrderStatusEvent;
import com.petstore.messaging.events.PurchaseOrderEvent;
import jakarta.jms.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.annotation.EnableJms;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.support.converter.MappingJackson2MessageConverter;
import org.springframework.jms.support.converter.MessageConverter;
import org.springframework.jms.support.converter.MessageType;

import java.util.Map;

/**
 * The ONE JMS configuration, shared by every service (replaces the per-service
 * JmsConfig copies). Provides:
 * <ul>
 *   <li>a single JSON {@link MessageConverter} with the {@code _type} id map for
 *       ALL event types — so producers and consumers can never drift;</li>
 *   <li>a {@code queueFactory} (point-to-point) and a {@code topicFactory}
 *       (pub/sub) for {@code @JmsListener(containerFactory = ...)}.</li>
 * </ul>
 *
 * <p>Importing this config (via component scan or {@code @Import}) is all a
 * service needs to send/receive enveloped events.
 */
@Configuration
@EnableJms
public class MessagingConfig {

    /**
     * The JMS message property that carries the logical event type id. Both the converter
     * (which reads it to pick a target class on inbound) and {@link MessagePublisher} (which
     * stamps it on outbound) must agree on this name, so it lives here as the single constant
     * rather than a literal repeated in two places.
     */
    public static final String TYPE_ID_PROPERTY = "_type";

    /** Logical type id → event class. The single source of truth for routing. */
    public static final Map<String, Class<?>> TYPE_IDS = Map.of(
            PurchaseOrderEvent.TYPE, PurchaseOrderEvent.class,
            OrderApprovedEvent.TYPE, OrderApprovedEvent.class,
            InvoiceEvent.TYPE, InvoiceEvent.class,
            OrderStatusEvent.TYPE, OrderStatusEvent.class);

    @Bean
    public MessageConverter jacksonJmsMessageConverter() {
        MappingJackson2MessageConverter c = new MappingJackson2MessageConverter();
        c.setTargetType(MessageType.TEXT);
        c.setTypeIdPropertyName(TYPE_ID_PROPERTY);
        c.setTypeIdMappings(TYPE_IDS);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        c.setObjectMapper(mapper);
        return c;
    }

    /** Point-to-point listeners (queues) — the default. */
    @Bean
    public DefaultJmsListenerContainerFactory queueFactory(
            ConnectionFactory cf, MessageConverter converter) {
        DefaultJmsListenerContainerFactory f = new DefaultJmsListenerContainerFactory();
        f.setConnectionFactory(cf);
        f.setMessageConverter(converter);
        f.setPubSubDomain(false);
        return f;
    }

    /** Pub/sub listeners (topics) — fan-out to every subscriber. */
    @Bean
    public DefaultJmsListenerContainerFactory topicFactory(
            ConnectionFactory cf, MessageConverter converter) {
        DefaultJmsListenerContainerFactory f = new DefaultJmsListenerContainerFactory();
        f.setConnectionFactory(cf);
        f.setMessageConverter(converter);
        f.setPubSubDomain(true);
        // Phase 4c: make topic subscriptions DURABLE + SHARED (JMS 2.0).
        //  - Durable: the broker retains topic messages for a named subscription even while
        //    that subscriber is offline (e.g. a service restart/deploy), then delivers them on
        //    reconnect — so an InvoiceTopic/OrderStatusTopic event is never lost just because a
        //    consumer happened to be down. (Backed by the broker's persistent volume, Phase 4b.)
        //  - Shared: a durable subscription is normally keyed by connection clientId, which must
        //    be UNIQUE per broker connection. This factory is a single shared bean and some
        //    services (notification-service) attach TWO topic @JmsListeners to it — each opens its
        //    own connection, so a single clientId would collide. Shared durable subscriptions
        //    (JMS 2.0) drop the clientId requirement entirely: the subscription is identified by
        //    its NAME alone. Each @JmsListener therefore just supplies a globally-unique
        //    `subscription` name (see the three topic listeners); no clientId to manage.
        f.setSubscriptionDurable(true);
        f.setSubscriptionShared(true);
        return f;
    }
}
