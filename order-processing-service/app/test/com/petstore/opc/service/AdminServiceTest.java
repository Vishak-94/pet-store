package com.petstore.opc.service;

import com.petstore.opc.domain.OrderStatus;
import com.petstore.opc.domain.OrderStatusChange;
import com.petstore.opc.domain.SalesReport;
import com.petstore.opc.domain.WarehouseOrder;
import com.petstore.opc.repository.OrderStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.mockito.InOrder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Unit tests for the migrated batch-approval (legacy updateOrders) + sales aggregation (legacy getChartInfo). */
class AdminServiceTest {

    private final OrderStore orders = mock(OrderStore.class);
    private final ApprovalGateway approvalGateway = mock(ApprovalGateway.class);
    private final OrderStatusGateway statusGateway = mock(OrderStatusGateway.class);
    private final AdminService admin = new AdminService(orders, approvalGateway, statusGateway);

    private static WarehouseOrder order(String id, OrderStatus status) {
        return order(id, status, Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static WarehouseOrder order(String id, OrderStatus status, Instant created) {
        return new WarehouseOrder(id, "u", "e@x.com", "en_US", "USD", 10.0, status, List.of(),
                null, null, created);
    }

    @Test
    void updateOrders_appliesEveryChange_andPublishesPerGateway() {
        when(orders.statusOf("o1")).thenReturn(Optional.of(OrderStatus.PENDING));
        when(orders.statusOf("o2")).thenReturn(Optional.of(OrderStatus.PENDING));
        when(orders.findById("o1")).thenReturn(Optional.of(order("o1", OrderStatus.APPROVED)));
        when(orders.findById("o2")).thenReturn(Optional.of(order("o2", OrderStatus.DENIED)));

        admin.updateOrders(List.of(
                new OrderStatusChange("o1", OrderStatus.APPROVED),
                new OrderStatusChange("o2", OrderStatus.DENIED)));

        verify(orders).updateStatus("o1", OrderStatus.APPROVED);
        verify(orders).updateStatus("o2", OrderStatus.DENIED);
        verify(approvalGateway).dispatchForFulfilment(any());   // only the approved one
        verify(statusGateway).announce(any(), eq(OrderStatus.APPROVED));
        verify(statusGateway).announce(any(), eq(OrderStatus.DENIED));
    }

    @Test
    void updateOrders_illegalTransition_throwsAndStopsBatch() {
        when(orders.statusOf("o1")).thenReturn(Optional.of(OrderStatus.PENDING));
        when(orders.findById("o1")).thenReturn(Optional.of(order("o1", OrderStatus.APPROVED)));
        when(orders.statusOf("bad")).thenReturn(Optional.of(OrderStatus.DENIED));   // terminal → can't go APPROVED

        assertThrows(IllegalStateException.class, () -> admin.updateOrders(List.of(
                new OrderStatusChange("o1", OrderStatus.APPROVED),
                new OrderStatusChange("bad", OrderStatus.APPROVED))));

        verify(orders, never()).updateStatus(eq("bad"), any());
    }

    @Test
    void updateOrders_unknownOrder_throws() {
        when(orders.statusOf("missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> admin.updateOrders(
                List.of(new OrderStatusChange("missing", OrderStatus.APPROVED))));
    }

    @Test
    void redriveApproved_reDispatchesEveryApprovedOrder_oldestFirst() {
        // Backordered (still APPROVED) orders, returned by the store in a NON-chronological order
        // so the test proves AdminService sorts them, not the store.
        WarehouseOrder newer = order("o-new", OrderStatus.APPROVED, Instant.parse("2026-03-01T00:00:00Z"));
        WarehouseOrder older = order("o-old", OrderStatus.APPROVED, Instant.parse("2026-01-01T00:00:00Z"));
        when(orders.orderIdsByStatus(OrderStatus.APPROVED)).thenReturn(List.of("o-new", "o-old"));
        when(orders.findById("o-new")).thenReturn(Optional.of(newer));
        when(orders.findById("o-old")).thenReturn(Optional.of(older));

        admin.redriveApprovedForFulfilment();

        // Both re-dispatched, oldest created first (longest-waiting backorder gets first claim on stock).
        InOrder inOrder = inOrder(approvalGateway);
        inOrder.verify(approvalGateway).dispatchForFulfilment(older);
        inOrder.verify(approvalGateway).dispatchForFulfilment(newer);
        // Pure fulfilment retry — no status change, no customer email.
        verify(orders, never()).updateStatus(any(), any());
        verifyNoInteractions(statusGateway);
    }

    @Test
    void redriveApproved_noApprovedOrders_isNoOp() {
        when(orders.orderIdsByStatus(OrderStatus.APPROVED)).thenReturn(List.of());

        admin.redriveApprovedForFulfilment();

        verifyNoInteractions(approvalGateway);
        verifyNoInteractions(statusGateway);
    }

    @Test
    void salesReport_delegatesToStoreAggregation() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-12-31T23:59:59Z");
        SalesReport report = new SalesReport("category",
                List.of(new SalesReport.SalesBucket("DOGS", 250.0, 5)));
        when(orders.aggregateSales(from, to, null)).thenReturn(report);

        assertSame(report, admin.salesReport(from, to, null));
        assertEquals("category", admin.salesReport(from, to, null).groupBy());
    }
}
