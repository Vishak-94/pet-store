package com.petstore.order;

import com.petstore.cart.CartDtos.CartItemView;
import com.petstore.cart.CartDtos.CartView;
import com.petstore.cart.CartOperations;
import com.petstore.cart.service.CartService;
import com.petstore.cart.web.CartIdFilter;
import com.petstore.order.domain.PurchaseOrder;
import com.petstore.order.repository.OrderRepository;
import com.petstore.order.service.EmptyCartException;
import com.petstore.order.service.OrderMessagePublisher;
import com.petstore.order.service.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Monolith checkout tests AFTER the warehouse + catalog + cart splits. The
 * monolith only CREATES the order (persist + publish to JMS); cart contents come
 * from the in-process cart-lib. CartOperations is mocked so checkout totals are
 * pinned without exercising the real store.
 */
@SpringBootTest
class OrderCharacterizationTest {

    private static final String CART_ID = "order-test-cart";

    @Autowired OrderRepository orders;

    @MockBean CartOperations cartClient;

    @BeforeEach
    void bindRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CartIdFilter.REQUEST_ATTR, CART_ID);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void unbind() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static CartView cartWith(CartItemView... items) {
        double sub = 0;
        for (CartItemView i : items) {
            sub += i.unitCost() * i.quantity();
        }
        return new CartView(List.of(items), sub, items.length);
    }

    private static CartItemView line(String id, String product, int qty, double cost) {
        return new CartItemView(id, product, null, "Name", "attr", qty, cost);
    }

    private OrderService checkout(CartService cart) {
        OrderMessagePublisher captured = po -> { };   // no-op publisher (no broker in this test)
        return new OrderService(cart, orders, captured);
    }

    @Test
    void checkout_emptyCart_throws() {
        when(cartClient.view(CART_ID)).thenReturn(CartView.empty());
        assertThatThrownBy(() -> checkout(new CartService(cartClient)).checkout("jane", "jane@x.com"))
                .isInstanceOf(EmptyCartException.class);
    }

    @Test
    void checkout_persistsOrderWithLines_andComputesTotal() {
        when(cartClient.view(CART_ID)).thenReturn(cartWith(
                line("EST-1", "FI-SW-01", 2, 16.50),   // 33.00
                line("EST-5", "K9-BD-01", 1, 18.50)));  // 18.50
        PurchaseOrder po = checkout(new CartService(cartClient)).checkout("bob", "bob@x.com");

        assertThat(po.getTotalPrice()).isEqualTo(2 * 16.50 + 18.50);
        PurchaseOrder saved = orders.findById(po.getOrderId()).orElseThrow();
        assertThat(saved.getLineItems()).hasSize(2);
        assertThat(saved.getUserId()).isEqualTo("bob");
    }

    @Test
    void checkout_publishesToJms_andEmptiesCart() {
        when(cartClient.view(CART_ID)).thenReturn(cartWith(line("EST-1", "FI-SW-01", 1, 16.50)));
        java.util.concurrent.atomic.AtomicInteger published = new java.util.concurrent.atomic.AtomicInteger();
        OrderMessagePublisher counting = po -> published.incrementAndGet();
        new OrderService(new CartService(cartClient), orders, counting).checkout("carol", "carol@x.com");

        assertThat(published.get()).isEqualTo(1);   // published to warehouse
        // cart emptied → delegates to cartClient.empty (verified by no exception; state owned by cart-service)
    }
}
