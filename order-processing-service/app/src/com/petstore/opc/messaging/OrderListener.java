package com.petstore.opc.messaging;

import com.petstore.messaging.Destinations;
import com.petstore.messaging.events.PurchaseOrderEvent;
import com.petstore.opc.domain.ContactInfo;
import com.petstore.opc.domain.OrderLine;
import com.petstore.opc.domain.OrderStatus;
import com.petstore.opc.domain.WarehouseOrder;
import com.petstore.opc.service.FulfilmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Consumes {@link PurchaseOrderEvent}s from the PurchaseOrderQueue (published by
 * the monolith checkout) — a QUEUE, so exactly one warehouse instance processes
 * each order. Uses the shared petstore-messaging contract; no local message class.
 */
@Component
public class OrderListener {

    private static final Logger log = LoggerFactory.getLogger(OrderListener.class);

    private final FulfilmentService fulfilment;

    public OrderListener(FulfilmentService fulfilment) {
        this.fulfilment = fulfilment;
    }

    @JmsListener(destination = "PurchaseOrderQueue", containerFactory = "queueFactory")
    public void onOrder(PurchaseOrderEvent msg) {
        log.info("Received order {} from {} ({} lines)", msg.orderId(),
                Destinations.PURCHASE_ORDER.name(), msg.lines() == null ? 0 : msg.lines().size());
        List<OrderLine> lines = (msg.lines() == null ? List.<PurchaseOrderEvent.Line>of() : msg.lines())
                .stream()
                .map(l -> new OrderLine(l.itemId(), l.productId(), l.categoryId(), l.quantity(), l.unitPrice()))
                .toList();
        fulfilment.receiveOrder(new WarehouseOrder(
                msg.orderId(), msg.userId(), msg.emailId(), msg.locale(),
                msg.totalPrice(), OrderStatus.PENDING, lines,
                toDomain(msg.shipTo()), toDomain(msg.billTo()), occurredAt(msg)));
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
