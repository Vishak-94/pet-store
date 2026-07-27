package com.petstore.opc.web;

import com.petstore.opc.client.OrderDtos.CheckoutRequest;
import com.petstore.opc.client.OrderDtos.CheckoutResponse;
import com.petstore.opc.client.OrderDtos.LineDto;
import com.petstore.opc.domain.OrderStatus;
import com.petstore.opc.domain.WarehouseOrder;
import com.petstore.opc.repository.OrderStore;
import com.petstore.opc.service.AdminService;
import com.petstore.opc.service.FulfilmentService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the synchronous checkout intake ({@code POST /api/orders/intake}) — the REST
 * replacement for the PurchaseOrderQueue path. Verifies the controller maps the request to a
 * PENDING domain order and delegates to the SAME {@link FulfilmentService#receiveOrder} the queue
 * listener uses, and that the response reflects the ACTUAL persisted status (so a duplicate submit
 * or an auto-approval is surfaced truthfully). Auth/validation are exercised at the framework layer.
 */
class OrderProcessingApiControllerIntakeTest {

    private final AdminService admin = mock(AdminService.class);
    private final OrderStore orders = mock(OrderStore.class);
    private final FulfilmentService fulfilment = mock(FulfilmentService.class);
    private final OrderProcessingApiController controller =
            new OrderProcessingApiController(admin, orders, fulfilment);

    private static CheckoutRequest request(double total) {
        return new CheckoutRequest("1042", "asmith", "asmith@example.com", "en_US", "USD", total,
                List.of(new LineDto("EST-1", "FI-SW-01", "FISH", 2, 16.5)), null, null);
    }

    private static WarehouseOrder stored(String id, OrderStatus status, double total) {
        return new WarehouseOrder(id, "asmith", "asmith@example.com", "en_US", "USD", total, status,
                List.of(), null, null, null);
    }

    @Test
    void intake_mapsRequestToPendingDomainOrder_andDelegatesToFulfilment() {
        when(orders.findById("1042")).thenReturn(Optional.of(stored("1042", OrderStatus.APPROVED, 33.0)));

        controller.intake(request(33.0));

        ArgumentCaptor<WarehouseOrder> captor = ArgumentCaptor.forClass(WarehouseOrder.class);
        verify(fulfilment).receiveOrder(captor.capture());
        WarehouseOrder passed = captor.getValue();
        assertThat(passed.orderId()).isEqualTo("1042");
        assertThat(passed.currency()).isEqualTo("USD");
        assertThat(passed.status()).isEqualTo(OrderStatus.PENDING);   // service recomputes; intake always PENDING
        assertThat(passed.lines()).singleElement()
                .satisfies(l -> assertThat(l.itemId()).isEqualTo("EST-1"));
        assertThat(passed.created()).isNull();                         // service stamps it
    }

    @Test
    void intake_returnsThePersistedStatus_notTheRequestedOne() {
        // FulfilmentService auto-approved it under the threshold — the response must reflect APPROVED.
        when(orders.findById("1042")).thenReturn(Optional.of(stored("1042", OrderStatus.APPROVED, 33.0)));

        CheckoutResponse res = controller.intake(request(33.0));

        assertThat(res.orderId()).isEqualTo("1042");
        assertThat(res.status()).isEqualTo("APPROVED");
        assertThat(res.totalPrice()).isEqualTo(33.0);
    }

    @Test
    void intake_duplicateSubmit_returnsAlreadyStoredOrder() {
        // receiveOrder no-ops on a known id (idempotent); we read back the pre-existing order.
        when(orders.findById("1042")).thenReturn(Optional.of(stored("1042", OrderStatus.PENDING, 999.0)));

        CheckoutResponse res = controller.intake(request(33.0));   // request total ignored

        assertThat(res.status()).isEqualTo("PENDING");
        assertThat(res.totalPrice()).isEqualTo(999.0);             // the STORED total, not the request's
        verify(fulfilment).receiveOrder(any());
    }
}
