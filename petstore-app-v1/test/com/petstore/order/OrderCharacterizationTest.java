package com.petstore.order;

import com.petstore.cart.CartDtos.CartItemView;
import com.petstore.cart.CartDtos.CartView;
import com.petstore.cart.CartOperations;
import com.petstore.cart.service.CartService;
import com.petstore.cart.web.CartIdFilter;
import com.petstore.messaging.Destination;
import com.petstore.messaging.Destinations;
import com.petstore.messaging.MessagePublisher;
import com.petstore.messaging.events.PurchaseOrderEvent;
import com.petstore.order.service.EmptyCartException;
import com.petstore.order.service.OrderIdGenerator;
import com.petstore.order.service.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Checkout tests for the storefront AFTER the OPC split. Faithful to the legacy
 * OrderEJBAction: checkout builds the PurchaseOrder from the cart, PUBLISHES it to
 * the queue, and empties the cart — it does NOT persist (order-processing-service
 * does that on consume). So these verify PUBLISH + total + empty, not persistence.
 */
class OrderCharacterizationTest {

    private static final String CART_ID = "order-test-cart";

    private CartOperations cartOps;
    private MessagePublisher publisher;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        cartOps = mock(CartOperations.class);
        publisher = mock(MessagePublisher.class);
        orderService = new OrderService(new CartService(cartOps), publisher, new OrderIdGenerator());
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
    void checkout_emptyCart_throws_andPublishesNothing() {
        when(cartOps.view(CART_ID)).thenReturn(CartView.empty());
        assertThatThrownBy(() -> orderService.checkout("jane", "jane@x.com"))
                .isInstanceOf(EmptyCartException.class);
        verify(publisher, never()).publish(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void checkout_publishesPurchaseOrderEvent_withComputedTotal() {
        when(cartOps.view(CART_ID)).thenReturn(new CartView(List.of(
                line("EST-1", "FI-SW-01", 2, 16.50),    // 33.00
                line("EST-5", "K9-BD-01", 1, 18.50)),    // 18.50
                51.50, 2));

        OrderService.OrderPlaced placed = orderService.checkout("bob", "bob@x.com");

        assertThat(placed.total()).isEqualTo(2 * 16.50 + 18.50);
        assertThat(placed.orderId()).isNotBlank();

        // verify it published a PurchaseOrderEvent to the PURCHASE_ORDER queue
        ArgumentCaptor<Destination> dest = ArgumentCaptor.forClass(Destination.class);
        ArgumentCaptor<Object> evt = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publish(dest.capture(), evt.capture());
        assertThat(dest.getValue().name()).isEqualTo(Destinations.PURCHASE_ORDER.name());
        assertThat(evt.getValue()).isInstanceOf(PurchaseOrderEvent.class);
        PurchaseOrderEvent po = (PurchaseOrderEvent) evt.getValue();
        assertThat(po.userId()).isEqualTo("bob");
        assertThat(po.lines()).hasSize(2);
        assertThat(po.totalPrice()).isEqualTo(51.50);
    }

    @Test
    void checkout_emptiesCartAfterPublish() {
        when(cartOps.view(CART_ID)).thenReturn(new CartView(List.of(
                line("EST-1", "FI-SW-01", 1, 16.50)), 16.50, 1));
        orderService.checkout("carol", "carol@x.com");
        verify(cartOps).empty(CART_ID);   // cart cleared after a successful order
    }

    @Test
    void orderIds_areUnique() {
        OrderIdGenerator gen = new OrderIdGenerator();
        assertThat(gen.nextId()).isNotEqualTo(gen.nextId());   // no restart-collision / dup ids
    }
}
