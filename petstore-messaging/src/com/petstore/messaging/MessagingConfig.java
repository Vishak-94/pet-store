package com.petstore.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.petstore.messaging.events.InvoiceEvent;
import com.petstore.messaging.events.OrderApprovedEvent;
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

    /** Logical type id → event class. The single source of truth for routing. */
    public static final Map<String, Class<?>> TYPE_IDS = Map.of(
            PurchaseOrderEvent.TYPE, PurchaseOrderEvent.class,
            OrderApprovedEvent.TYPE, OrderApprovedEvent.class,
            InvoiceEvent.TYPE, InvoiceEvent.class);

    @Bean
    public MessageConverter jacksonJmsMessageConverter() {
        MappingJackson2MessageConverter c = new MappingJackson2MessageConverter();
        c.setTargetType(MessageType.TEXT);
        c.setTypeIdPropertyName("_type");
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
        return f;
    }
}
