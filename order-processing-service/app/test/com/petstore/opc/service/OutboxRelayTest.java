package com.petstore.opc.service;

import com.petstore.messaging.Destinations;
import com.petstore.messaging.MessagePublisher;
import com.petstore.messaging.events.OrderApprovedEvent;
import com.petstore.messaging.events.OrderStatusEvent;
import com.petstore.opc.repository.OutboxMessage;
import com.petstore.opc.repository.OutboxStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the outbox relay: it deserializes the stored payload back to the
 * typed event, publishes to the right destination (queue vs topic), marks delivered
 * on success, and records a failure (not a publish, not a mark) when the broker send
 * throws — so the row is retried on a later poll.
 */
class OutboxRelayTest {

    private final OutboxStore outbox = mock(OutboxStore.class);
    private final MessagePublisher publisher = mock(MessagePublisher.class);
    private final OutboxRelay relay = new OutboxRelay(outbox, publisher, 100, 10);

    private static OutboxMessage approvedRow() {
        // A wire-shaped OrderApprovedEvent payload (as OutboxWriter would have frozen it).
        String json = "{\"meta\":{\"eventId\":\"e1\",\"type\":\"OrderApproved\","
                + "\"occurredAt\":\"2026-07-25T00:00:00Z\",\"correlationId\":null},"
                + "\"orderId\":\"o1\",\"userId\":\"u\",\"emailId\":\"e@x.com\",\"locale\":\"en_US\","
                + "\"lines\":[]}";
        return new OutboxMessage(1L, Destinations.APPROVED_ORDER_NAME, false, OrderApprovedEvent.TYPE, json, "o1");
    }

    private static OutboxMessage statusRow() {
        String json = "{\"meta\":{\"eventId\":\"e2\",\"type\":\"OrderStatus\","
                + "\"occurredAt\":\"2026-07-25T00:00:00Z\",\"correlationId\":null},"
                + "\"orderId\":\"o1\",\"userId\":\"u\",\"emailId\":\"e@x.com\","
                + "\"status\":\"APPROVED\",\"totalPrice\":10.0}";
        return new OutboxMessage(2L, Destinations.ORDER_STATUS_NAME, true, OrderStatusEvent.TYPE, json, "o1");
    }

    @Test
    void publishesQueueEvent_deserializedToTypedEvent_thenMarksPublished() {
        when(outbox.fetchUnpublished(anyInt(), anyInt())).thenReturn(List.of(approvedRow()));

        relay.publishPending();

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publish(eq(Destinations.APPROVED_ORDER), event.capture());
        assertTrue(event.getValue() instanceof OrderApprovedEvent);
        assertEquals("o1", ((OrderApprovedEvent) event.getValue()).orderId());
        assertEquals("e1", ((OrderApprovedEvent) event.getValue()).meta().eventId());   // id preserved for dedup
        verify(outbox).markPublished(1L);
        verify(outbox, never()).recordFailure(anyInt());
    }

    @Test
    void publishesTopicEvent_toOrderStatusTopic() {
        when(outbox.fetchUnpublished(anyInt(), anyInt())).thenReturn(List.of(statusRow()));

        relay.publishPending();

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publish(eq(Destinations.ORDER_STATUS), event.capture());
        assertTrue(event.getValue() instanceof OrderStatusEvent);
        assertEquals("APPROVED", ((OrderStatusEvent) event.getValue()).status());
        verify(outbox).markPublished(2L);
    }

    @Test
    void publishFailure_recordsFailure_doesNotMarkPublished() {
        when(outbox.fetchUnpublished(anyInt(), anyInt())).thenReturn(List.of(approvedRow()));
        doThrow(new RuntimeException("broker down")).when(publisher).publish(any(), any());

        relay.publishPending();

        verify(outbox).recordFailure(1L);
        verify(outbox, never()).markPublished(anyInt());
    }

    @Test
    void onePoisonRow_doesNotBlockTheRest() {
        when(outbox.fetchUnpublished(anyInt(), anyInt())).thenReturn(List.of(approvedRow(), statusRow()));
        // First publish throws, second succeeds.
        doThrow(new RuntimeException("boom"))
                .when(publisher).publish(eq(Destinations.APPROVED_ORDER), any());

        relay.publishPending();

        verify(outbox).recordFailure(1L);
        verify(outbox).markPublished(2L);   // second row still delivered
    }
}
