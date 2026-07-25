package com.petstore.inventory.service;

import com.petstore.inventory.repository.InventoryStore;
import com.petstore.inventory.repository.ProcessedEventStore;
import com.petstore.messaging.EventMeta;
import com.petstore.messaging.events.OrderApprovedEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the at-least-once dedup contract: a redelivered order-approved event must
 * NOT decrement stock a second time, and a first delivery that fully ships must
 * record its event id in the SAME flow as the decrement.
 */
class FulfilmentServiceIdempotencyTest {

    private static OrderApprovedEvent orderWith(String eventId) {
        return new OrderApprovedEvent(
                new EventMeta(eventId, OrderApprovedEvent.TYPE, "2026-01-01T00:00:00Z", "cid-1"),
                "order-1", "user-1", "user-1@petstore.com", "en_US",
                List.of(new OrderApprovedEvent.Line("EST-1", "prod-1", "cat-1", 2, 10.0)));
    }

    @Test
    void redelivery_of_processed_event_does_not_decrement_again() {
        InventoryStore inventory = mock(InventoryStore.class);
        ProcessedEventStore processed = mock(ProcessedEventStore.class);
        when(processed.isProcessed("evt-1")).thenReturn(true);

        boolean shipped = new FulfilmentService(inventory, processed).fulfil(orderWith("evt-1"));

        assertThat(shipped).isTrue();                       // treated as already shipped
        verify(inventory, never()).tryReserve(anyString(), anyInt());
        verify(processed, never()).markProcessed(anyString());
    }

    @Test
    void first_delivery_with_stock_reserves_and_marks_processed() {
        InventoryStore inventory = mock(InventoryStore.class);
        ProcessedEventStore processed = mock(ProcessedEventStore.class);
        when(processed.isProcessed("evt-2")).thenReturn(false);
        when(inventory.quantityOf("EST-1")).thenReturn(Optional.of(5));
        when(inventory.tryReserve("EST-1", 2)).thenReturn(true);

        boolean shipped = new FulfilmentService(inventory, processed).fulfil(orderWith("evt-2"));

        assertThat(shipped).isTrue();
        verify(inventory).tryReserve("EST-1", 2);
        verify(processed).markProcessed("evt-2");
    }

    @Test
    void short_stock_does_not_mark_processed_so_it_can_retry_after_restock() {
        InventoryStore inventory = mock(InventoryStore.class);
        ProcessedEventStore processed = mock(ProcessedEventStore.class);
        when(processed.isProcessed("evt-3")).thenReturn(false);
        when(inventory.quantityOf("EST-1")).thenReturn(Optional.of(1));   // short: need 2

        boolean shipped = new FulfilmentService(inventory, processed).fulfil(orderWith("evt-3"));

        assertThat(shipped).isFalse();
        verify(inventory, never()).tryReserve(eq("EST-1"), anyInt());
        verify(processed, never()).markProcessed(anyString());
    }
}
