package com.petstore.cart;

import com.petstore.cart.CartDtos.CartItemView;
import com.petstore.cart.CartDtos.CartView;
import com.petstore.cart.CartOperations;
import com.petstore.cart.service.CartService;
import com.petstore.cart.web.CartIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the monolith's CartService ADAPTER: that it resolves the request's cart
 * id (set by {@link CartIdFilter}), delegates each operation to the in-process
 * {@link CartOperations} library, and maps the returned DTOs to the monolith's
 * {@code CartItem} view model. The cart's business behaviour itself is pinned in
 * the cart library's own tests — here we only verify correct delegation + mapping.
 */
class CartServiceAdapterTest {

    private static final String CART_ID = "cart-abc";

    private CartOperations ops;
    private CartService cart;

    @BeforeEach
    void setUp() {
        ops = mock(CartOperations.class);
        cart = new CartService(ops);
        // Bind a request carrying the cart id, as CartIdFilter would have done.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CartIdFilter.REQUEST_ATTR, CART_ID);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void addItem_delegatesWithCartId() {
        cart.addItem("EST-1");
        verify(ops).addItem(eq(CART_ID), eq("EST-1"), isNull());
    }

    @Test
    void addItemWithQty_delegatesWithCartId() {
        cart.addItem("EST-1", 4);
        verify(ops).addItem(eq(CART_ID), eq("EST-1"), eq(4));
    }

    @Test
    void updateItemQuantity_delegatesToSetQuantity() {
        cart.updateItemQuantity("EST-1", 0);
        verify(ops).setQuantity(eq(CART_ID), eq("EST-1"), eq(0));
    }

    @Test
    void getItems_mapsDtoToCartItem() {
        when(ops.view(CART_ID)).thenReturn(new CartView(
                List.of(new CartItemView("EST-1", "FI-SW-01", null, "Angelfish", "Large", 2, 16.50)),
                33.0, 1));
        var items = cart.getItems();
        assertThat(items).singleElement().satisfies(ci -> {
            assertThat(ci.getItemId()).isEqualTo("EST-1");
            assertThat(ci.getProductName()).isEqualTo("Angelfish");
            assertThat(ci.getQuantity()).isEqualTo(2);
            assertThat(ci.getUnitCost()).isEqualTo(16.50);
        });
    }

    @Test
    void getSubTotal_readsFromView() {
        when(ops.view(CART_ID)).thenReturn(new CartView(List.of(), 51.50, 2));
        assertThat(cart.getSubTotal()).isEqualTo(51.50);
    }

    @Test
    void getCount_delegatesToCatalogFreeCount() {
        // getCount now uses the catalog-free count() (nav badge on every page), not view().
        when(ops.count(CART_ID)).thenReturn(2);
        assertThat(cart.getCount()).isEqualTo(2);
        verify(ops).count(CART_ID);
    }

    @Test
    void quantityOf_findsMatchingLine() {
        when(ops.view(CART_ID)).thenReturn(new CartView(
                List.of(new CartItemView("EST-5", "K9-BD-01", null, "Bulldog", "F", 3, 18.50)),
                55.50, 1));
        assertThat(cart.quantityOf("EST-5")).isEqualTo(3);
        assertThat(cart.quantityOf("NOPE")).isZero();
    }

    @Test
    void empty_delegatesWithCartId() {
        cart.empty();
        verify(ops).empty(eq(CART_ID));
    }
}
