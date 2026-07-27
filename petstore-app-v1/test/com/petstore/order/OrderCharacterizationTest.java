package com.petstore.order;

import com.petstore.cart.CartDtos.CartItemView;
import com.petstore.cart.CartDtos.CartView;
import com.petstore.cart.CartOperations;
import com.petstore.cart.service.CartService;
import com.petstore.cart.web.CartIdFilter;
import com.petstore.opc.client.OrderDtos.CheckoutRequest;
import com.petstore.opc.client.OrderDtos.CheckoutResponse;
import com.petstore.opc.client.OrderProcessingClient;
import com.petstore.order.service.EmptyCartException;
import com.petstore.order.service.OrderIdGenerator;
import com.petstore.order.service.OrderIntakeUnavailableException;
import com.petstore.order.service.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestClientException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Checkout tests for the storefront AFTER the OPC split, and after intake became
 * SYNCHRONOUS REST (see DECISIONS.md): checkout builds the CheckoutRequest from the
 * cart, POSTs it to order-processing-service via {@link OrderProcessingClient}, and
 * empties the cart on success — it still does NOT persist locally (OPC is the store).
 * So these verify the intake CALL + total + empty, plus the OPC-down → 503 path.
 */
class OrderCharacterizationTest {

    private static final String CART_ID = "order-test-cart";
    private static final String BEARER = "jwt-token";

    private CartOperations cartOps;
    private OrderProcessingClient orderProcessing;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        cartOps = mock(CartOperations.class);
        orderProcessing = mock(OrderProcessingClient.class);
        orderService = new OrderService(new CartService(cartOps), orderProcessing, new OrderIdGenerator());
        // Default: OPC echoes back the submitted id/total as PENDING.
        when(orderProcessing.checkout(any(CheckoutRequest.class), any())).thenAnswer(inv -> {
            CheckoutRequest req = inv.getArgument(0);
            return new CheckoutResponse(req.orderId(), "PENDING", req.totalPrice());
        });
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CartIdFilter.REQUEST_ATTR, CART_ID);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static CartItemView line(String id, String product, int qty, double cost) {
        return new CartItemView(id, product, null, "Name", "attr", qty, cost);
    }

    @Test
    void checkout_emptyCart_throws_andCallsOpcNothing() {
        when(cartOps.view(CART_ID)).thenReturn(CartView.empty());
        assertThatThrownBy(() -> orderService.checkout(BEARER, "jane", "jane@x.com"))
                .isInstanceOf(EmptyCartException.class);
        verify(orderProcessing, never()).checkout(any(), any());
    }

    @Test
    void checkout_callsOpcIntake_withComputedTotal() {
        when(cartOps.view(CART_ID)).thenReturn(new CartView(List.of(
                line("EST-1", "FI-SW-01", 2, 16.50),    // 33.00
                line("EST-5", "K9-BD-01", 1, 18.50)),    // 18.50
                51.50, 2));

        OrderService.OrderPlaced placed = orderService.checkout(BEARER, "bob", "bob@x.com");

        assertThat(placed.total()).isEqualTo(2 * 16.50 + 18.50);
        assertThat(placed.orderId()).isNotBlank();

        // verify it POSTed a CheckoutRequest to OPC, forwarding the shopper's JWT
        ArgumentCaptor<CheckoutRequest> req = ArgumentCaptor.forClass(CheckoutRequest.class);
        verify(orderProcessing).checkout(req.capture(), eq(BEARER));
        CheckoutRequest sent = req.getValue();
        assertThat(sent.userId()).isEqualTo("bob");
        assertThat(sent.orderId()).isEqualTo(placed.orderId());
        assertThat(sent.currency()).isEqualTo("USD");
        assertThat(sent.lines()).hasSize(2);
        assertThat(sent.totalPrice()).isEqualTo(51.50);
    }

    @Test
    void checkout_emptiesCartAfterSuccessfulIntake() {
        when(cartOps.view(CART_ID)).thenReturn(new CartView(List.of(
                line("EST-1", "FI-SW-01", 1, 16.50)), 16.50, 1));
        orderService.checkout(BEARER, "carol", "carol@x.com");
        verify(cartOps).empty(CART_ID);   // cart cleared after a successful order
    }

    @Test
    void checkout_opcUnreachable_throwsUnavailable_andKeepsCart() {
        when(cartOps.view(CART_ID)).thenReturn(new CartView(List.of(
                line("EST-1", "FI-SW-01", 1, 16.50)), 16.50, 1));
        when(orderProcessing.checkout(any(CheckoutRequest.class), any()))
                .thenThrow(new RestClientException("connection refused"));

        assertThatThrownBy(() -> orderService.checkout(BEARER, "dan", "dan@x.com"))
                .isInstanceOf(OrderIntakeUnavailableException.class);
        verify(cartOps, never()).empty(CART_ID);   // cart intact so the shopper can retry
    }

    @Test
    void orderIds_areUnique() {
        OrderIdGenerator gen = new OrderIdGenerator();
        assertThat(gen.nextId()).isNotEqualTo(gen.nextId());   // no restart-collision / dup ids
    }
}
