package com.petstore.notification;

import com.petstore.messaging.MessagingConfig;
import jakarta.jms.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

/**
 * Operator-visible sink for the broker's dead-letter and expiry queues — the last line of
 * the messaging safety net. When a message a consumer keeps rejecting exhausts its redelivery
 * attempts (broker.xml: {@code max-delivery-attempts=3} → {@code dead-letter-address=DLQ}), or
 * a message expires, Artemis routes it here instead of dropping it or hot-looping the queue.
 * Without a consumer those quarantined messages are a silent black hole; this listener turns
 * each one into an ERROR log an operator can alert on (e.g. a permanently-failing invoice or a
 * payload no consumer can deserialize).
 *
 * <p>It is deliberately a <b>raw {@link Message} consumer</b>, not a typed-event listener: a
 * message reaches the DLQ precisely because something about it was un-processable (often an
 * undeserializable payload), so trying to convert it back to a typed event would just re-poison
 * this listener. We read only JMS headers/properties, which are always available, and log the
 * body defensively.
 *
 * <p>This is the broker-side counterpart to the producer-side outbox park signal
 * ({@code OutboxRelay} logs an ERROR when a row exhausts its attempts). notification-service is
 * the natural home: it is a pure observer with the JMS wiring already in place and no business
 * state to corrupt.
 */
@Component
public class DlqListener {

    private static final Logger log = LoggerFactory.getLogger(DlqListener.class);

    /** Artemis header carrying the address a dead-lettered message was originally sent to. */
    private static final String ORIGINAL_ADDRESS = "_AMQ_ORIG_ADDRESS";
    /** Artemis header carrying the original queue name. */
    private static final String ORIGINAL_QUEUE = "_AMQ_ORIG_QUEUE";

    // Queue consumer (point-to-point): the DLQ/ExpiryQueue are anycast queues (broker.xml),
    // so use the queueFactory, NOT the topicFactory the notification listeners use.
    /**
     * Consume a dead-lettered message (one that failed 3 broker delivery attempts) and log it at
     * ERROR for operator attention. Consumed as a raw {@link Message} — never deserialized to a
     * typed event (the body is often what made it un-processable). Never throws (see
     * {@link #logQuarantined}), so the poison message is always ACKed and not re-queued.
     *
     * <p>Example: an {@code InvoiceEvent} whose consumer kept failing arrives on the DLQ carrying
     * headers {@code _AMQ_ORIG_ADDRESS=InvoiceTopic}, {@code _type=Invoice},
     * {@code JMSXDeliveryCount=4}. Produces an ERROR log line:
     * <pre>{@code
     * DLQ received a quarantined message: origin=InvoiceTopic, _type=Invoice,
     *   messageId=ID:..., deliveryCount=4 — requires operator attention. Body: {"orderId":"1001",...}
     * }</pre>
     *
     * @param message the raw quarantined JMS message
     */
    @JmsListener(destination = "DLQ", containerFactory = "queueFactory")
    public void onDeadLetter(Message message) {
        logQuarantined("DLQ", message);
    }

    /**
     * Consume an expired message off the ExpiryQueue and log it at ERROR — same raw-message,
     * never-throw handling as {@link #onDeadLetter}. A message lands here when its TTL elapsed
     * before any consumer processed it.
     *
     * <p>Example: an {@code OrderStatusEvent} that expired produces an ERROR log line:
     * <pre>{@code
     * ExpiryQueue received a quarantined message: origin=OrderStatusTopic, _type=OrderStatus,
     *   messageId=ID:..., deliveryCount=1 — requires operator attention. Body: {"orderId":"1001",...}
     * }</pre>
     *
     * @param message the raw expired JMS message
     */
    @JmsListener(destination = "ExpiryQueue", containerFactory = "queueFactory")
    public void onExpired(Message message) {
        logQuarantined("ExpiryQueue", message);
    }

    private void logQuarantined(String sink, Message message) {
        try {
            String origin = firstNonBlank(
                    message.getStringProperty(ORIGINAL_ADDRESS),
                    message.getStringProperty(ORIGINAL_QUEUE));
            String type = message.getStringProperty(MessagingConfig.TYPE_ID_PROPERTY);
            int deliveryCount = message.getIntProperty("JMSXDeliveryCount");
            log.error("{} received a quarantined message: origin={}, _type={}, messageId={}, "
                            + "deliveryCount={} — requires operator attention. Body: {}",
                    sink, origin, type, safeMessageId(message), deliveryCount, bodyPreview(message));
        } catch (Exception e) {
            // Never let logging a poison message throw — that would re-queue it onto the DLQ.
            log.error("{} received a quarantined message that could not be introspected: {}",
                    sink, e.toString());
        }
    }

    /** Best-effort body extraction; a non-text or unreadable body is reported, never rethrown. */
    private static String bodyPreview(Message message) {
        try {
            if (message instanceof jakarta.jms.TextMessage text) {
                return text.getText();
            }
            return "<non-text " + message.getClass().getSimpleName() + ">";
        } catch (Exception e) {
            return "<unreadable body: " + e + ">";
        }
    }

    private static String safeMessageId(Message message) {
        try {
            return message.getJMSMessageID();
        } catch (Exception e) {
            return "<unknown>";
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return (b != null && !b.isBlank()) ? b : "<unknown>";
    }
}
