package com.petstore.inventory.service;

import com.petstore.inventory.repository.InventoryStore;
import com.petstore.inventory.repository.FulfilledOrderStore;
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
 * Pins the dedup contract, now keyed by ORDER (not message eventId): an order whose
 * stock was already decremented must NOT be decremented again — whether the duplicate
 * arrives as a plain JMS redelivery (same eventId) OR as a re-driven event with a FRESH
 * eventId (order-processing re-publishes one per APPROVED order on each restock;
 * PARITY_AUDIT H2/M8). A first delivery that fully ships records its orderId in the SAME
 * flow as the decrement; a short-stock delivery marks nothing so it can retry after restock.
 */
class FulfilmentServiceIdempotencyTest {

    private static OrderApprovedEvent orderWith(String eventId) {
        // A fixed orderId across events lets a test vary only the eventId to model a re-drive.
        return orderWith(eventId, "order-1");
    }

    private static OrderApprovedEvent orderWith(String eventId, String orderId) {
        return new OrderApprovedEvent(
                new EventMeta(eventId, OrderApprovedEvent.TYPE, "2026-01-01T00:00:00Z", "cid-1"),
                orderId, "user-1", "user-1@petstore.com", "en_US",
                List.of(new OrderApprovedEvent.Line("EST-1", "prod-1", "cat-1", 2, 10.0)));
    }

    @Test
    void redelivery_of_fulfilled_order_does_not_decrement_again() {
        InventoryStore inventory = mock(InventoryStore.class);
        FulfilledOrderStore fulfilled = mock(FulfilledOrderStore.class);
        when(fulfilled.isFulfilled("order-1")).thenReturn(true);

        boolean shipped = new FulfilmentService(inventory, fulfilled).fulfil(orderWith("evt-1"));

        assertThat(shipped).isTrue();                       // treated as already shipped
        verify(inventory, never()).tryReserve(anyString(), anyInt());
        verify(fulfilled, never()).markFulfilled(anyString());
    }

    @Test
    void redrive_with_fresh_eventId_for_shipped_order_is_skipped() {
        // The re-drive case: a DIFFERENT eventId but the SAME order that already shipped.
        // An eventId-keyed ledger would miss this and double-decrement; the orderId ledger catches it.
        InventoryStore inventory = mock(InventoryStore.class);
        FulfilledOrderStore fulfilled = mock(FulfilledOrderStore.class);
        when(fulfilled.isFulfilled("order-1")).thenReturn(true);

        boolean shipped = new FulfilmentService(inventory, fulfilled)
                .fulfil(orderWith("evt-REDRIVE-different", "order-1"));

        assertThat(shipped).isTrue();
        verify(inventory, never()).tryReserve(anyString(), anyInt());
        verify(fulfilled, never()).markFulfilled(anyString());
    }

    @Test
    void first_delivery_with_stock_reserves_and_marks_fulfilled() {
        InventoryStore inventory = mock(InventoryStore.class);
        FulfilledOrderStore fulfilled = mock(FulfilledOrderStore.class);
        when(fulfilled.isFulfilled("order-1")).thenReturn(false);
        when(inventory.quantityOf("EST-1")).thenReturn(Optional.of(5));
        when(inventory.tryReserve("EST-1", 2)).thenReturn(true);

        boolean shipped = new FulfilmentService(inventory, fulfilled).fulfil(orderWith("evt-2"));

        assertThat(shipped).isTrue();
        verify(inventory).tryReserve("EST-1", 2);
        verify(fulfilled).markFulfilled("order-1");
    }

    @Test
    void short_stock_does_not_mark_fulfilled_so_it_can_retry_after_restock() {
        InventoryStore inventory = mock(InventoryStore.class);
        FulfilledOrderStore fulfilled = mock(FulfilledOrderStore.class);
        when(fulfilled.isFulfilled("order-1")).thenReturn(false);
        when(inventory.quantityOf("EST-1")).thenReturn(Optional.of(1));   // short: need 2

        boolean shipped = new FulfilmentService(inventory, fulfilled).fulfil(orderWith("evt-3"));

        assertThat(shipped).isFalse();
        verify(inventory, never()).tryReserve(eq("EST-1"), anyInt());
        verify(fulfilled, never()).markFulfilled(anyString());
    }
}
