package com.petstore.messaging;

import jakarta.jms.ConnectionFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.support.converter.MessageConverter;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Thin publisher used by every service: {@code publisher.publish(dest, event)}.
 * It picks the right transport from the {@link Destination} (topic → pub/sub,
 * queue → point-to-point) and stamps the JMS {@code _type} id from the event, so
 * callers never touch JmsTemplate, pub/sub flags, or type headers.
 *
 * <p>Holds two JmsTemplates (one per domain) because a template's pub/sub mode is
 * fixed at construction and must not be mutated per-call.
 */
@Component
public class MessagePublisher {

    /**
     * event class → {@code _type} id, derived once from {@link MessagingConfig#TYPE_IDS}
     * (the single source of truth) by inverting it. Keeping this in lock-step with the
     * converter's own map means adding an event type is a one-line change to {@code TYPE_IDS}
     * — no parallel {@code instanceof} chain here to forget to update.
     */
    private static final Map<Class<?>, String> TYPE_BY_CLASS = MessagingConfig.TYPE_IDS.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));

    private final JmsTemplate queueTemplate;
    private final JmsTemplate topicTemplate;

    public MessagePublisher(ConnectionFactory cf, MessageConverter converter) {
        this.queueTemplate = template(cf, converter, false);
        this.topicTemplate = template(cf, converter, true);
    }

    private static JmsTemplate template(ConnectionFactory cf, MessageConverter converter, boolean pubSub) {
        JmsTemplate t = new JmsTemplate(cf);
        t.setMessageConverter(converter);
        t.setPubSubDomain(pubSub);
        return t;
    }

    /** Publish an event to a destination, stamping its {@code _type} id. */
    public void publish(Destination dest, Object event) {
        JmsTemplate t = dest.topic() ? topicTemplate : queueTemplate;
        String type = typeOf(event);
        t.convertAndSend(dest.name(), event, m -> {
            if (type != null) {
                m.setStringProperty(MessagingConfig.TYPE_ID_PROPERTY, type);
            }
            return m;
        });
    }

    private static String typeOf(Object event) {
        return event == null ? null : TYPE_BY_CLASS.get(event.getClass());
    }
}
