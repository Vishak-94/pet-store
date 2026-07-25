package com.petstore.opc.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.petstore.messaging.Destinations;
import com.petstore.messaging.Events;
import com.petstore.messaging.events.OrderApprovedEvent;
import com.petstore.opc.repository.OutboxMessage;
import com.petstore.opc.repository.OutboxStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for the outbox writer: it stamps the destination name/kind and the
 * {@code _type} id, and freezes the event as JSON that round-trips back to the same
 * typed event with its {@code eventId} intact (so at-least-once redelivery dedups).
 */
class OutboxWriterTest {

    private final OutboxStore outbox = mock(OutboxStore.class);
    private final OutboxWriter writer = new OutboxWriter(outbox);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void enqueue_stampsDestinationAndType_andFreezesRoundTrippableJson() throws Exception {
        OrderApprovedEvent event = new OrderApprovedEvent(
                Events.meta(OrderApprovedEvent.TYPE),
                "o1", "u", "e@x.com", "en_US", List.of());

        writer.enqueue(Destinations.APPROVED_ORDER, event, "o1");

        ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outbox).enqueue(captor.capture());
        OutboxMessage stored = captor.getValue();

        assertEquals(Destinations.APPROVED_ORDER_NAME, stored.destination());
        assertFalse(stored.topic(), "ApprovedOrderQueue is a queue");
        assertEquals(OrderApprovedEvent.TYPE, stored.eventType());
        assertEquals("o1", stored.orderId());

        OrderApprovedEvent back = mapper.readValue(stored.payload(), OrderApprovedEvent.class);
        assertEquals("o1", back.orderId());
        assertEquals(event.meta().eventId(), back.meta().eventId());   // id survives → consumers dedup
    }
}
