package com.petstore.opc.messaging;

import com.petstore.messaging.Correlation;
import com.petstore.messaging.Destinations;
import com.petstore.messaging.events.PurchaseOrderEvent;
import com.petstore.opc.domain.ContactInfo;
import com.petstore.opc.domain.OrderLine;
import com.petstore.opc.domain.OrderStatus;
import com.petstore.opc.domain.WarehouseOrder;
import com.petstore.opc.service.FulfilmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Consumes {@link PurchaseOrderEvent}s from the PurchaseOrderQueue (published by
 * the monolith checkout) — a QUEUE, so exactly one warehouse instance processes
 * each order. Uses the shared petstore-messaging contract; no local message class.
 *
 * @deprecated Order intake moved to the synchronous REST endpoint
 * {@code POST /api/orders/intake} (see {@code OrderProcessingApiController#intake} and the
 * ADR in {@code DECISIONS.md}); the storefront now calls OPC directly instead of publishing to
 * PurchaseOrderQueue. This listener is retained (and still functional) as a fallback / parity
 * reference until the queue path is removed — both paths converge on the same
 * {@link FulfilmentService#receiveOrder}, so behaviour is identical whichever delivers the order.
 */
@Deprecated
@Component
public class OrderListener {

    private static final Logger log = LoggerFactory.getLogger(OrderListener.class);

    private final FulfilmentService fulfilment;

    public OrderListener(FulfilmentService fulfilment) {
        this.fulfilment = fulfilment;
    }

    /**
     * Consume one {@link PurchaseOrderEvent} off PurchaseOrderQueue: adopt its correlation
     * id, map it (lines + shipTo/billTo contacts + {@code occurredAt}→created) into a PENDING
     * {@link WarehouseOrder}, and hand it to {@link FulfilmentService#receiveOrder} (which
     * persists + auto-approves or leaves PENDING). Idempotent by design — the service dedups
     * on {@code findById}, and a duplicate that races past that guard is caught here as a
     * primary-key {@link DataIntegrityViolationException} and swallowed (ack, no redelivery),
     * so JMS at-least-once redeliveries never double-process an order.
     *
     * @param msg the inbound purchase-order event (from the storefront checkout)
     */
    @JmsListener(destination = Destinations.PURCHASE_ORDER_NAME, containerFactory = "queueFactory")
    public void onOrder(PurchaseOrderEvent msg) {
        // Adopt the inbound event's correlation id for this handling so any downstream event
        // (approval/status published while processing) and every log line stay on the checkout's
        // trace — the JMS analogue of the HTTP CorrelationIdFilter.
        Correlation.set(msg.meta() == null ? null : msg.meta().correlationId());
        try {
            log.info("Received order {} from {} ({} lines)", msg.orderId(),
                    Destinations.PURCHASE_ORDER.name(), msg.lines() == null ? 0 : msg.lines().size());
            List<OrderLine> lines = (msg.lines() == null ? List.<PurchaseOrderEvent.Line>of() : msg.lines())
                    .stream()
                    .map(l -> new OrderLine(l.itemId(), l.productId(), l.categoryId(), l.quantity(), l.unitPrice()))
                    .toList();
            try {
                fulfilment.receiveOrder(new WarehouseOrder(
                        msg.orderId(), msg.userId(), msg.emailId(), msg.locale(),
                        msg.currency(), msg.totalPrice(), OrderStatus.PENDING, lines,
                        toDomain(msg.shipTo()), toDomain(msg.billTo()), occurredAt(msg)));
            } catch (DataIntegrityViolationException dup) {
                // Idempotency backstop (Option 3): the order_id primary key rejected a duplicate
                // that raced past the findById guard (two redeliveries interleaving, or a client
                // replay the storefront token-set didn't catch). Swallow + ack — the order is
                // already stored, so this delivery is a no-op, not a poison message to redeliver.
                log.info("Order {} already persisted (primary-key dedup); ignoring duplicate delivery", msg.orderId());
            }
        } finally {
            Correlation.clear();   // never leak the id across pooled listener threads
        }
    }

    /** Order-received timestamp from the envelope (legacy PurchaseOrder poDate); now if absent/unparseable. */
    private static Instant occurredAt(PurchaseOrderEvent msg) {
        if (msg.meta() == null || msg.meta().occurredAt() == null) {
            return Instant.now();
        }
        try {
            return Instant.parse(msg.meta().occurredAt());
        } catch (DateTimeParseException e) {
            return Instant.now();
        }
    }

    private static ContactInfo toDomain(PurchaseOrderEvent.ContactInfo c) {
        if (c == null) {
            return null;
        }
        return new ContactInfo(c.familyName(), c.givenName(), c.streetName1(), c.streetName2(),
                c.city(), c.state(), c.zipCode(), c.country(), c.telephone(), c.email());
    }
}
