package com.petstore.cart;

import com.petstore.catalog.client.CatalogDtos.ItemDto;
import com.petstore.catalog.client.CatalogServiceClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Characterization tests pinning the LEGACY shopping-cart behaviour, now on the
 * embeddable {@link CartOperations} + {@link CartStore}. The catalog client is
 * stubbed with known seed items (EST-1/EST-2 @16.50, EST-5 @18.50) so these stay
 * fast, offline unit tests — the observable contract is identical to the legacy
 * ShoppingCartEJB.
 */
class CartOperationsTest {

    private static final Map<String, Double> SEED = Map.of(
            "EST-1", 16.50, "EST-2", 16.50, "EST-5", 18.50);

    private static final String CART = "cart-123";

    private CartStore store;
    private CartOperations cart;

    @BeforeEach
    void setUp() {
        CatalogServiceClient catalog = mock(CatalogServiceClient.class);
        for (var e : SEED.entrySet()) {
            when(catalog.getItem(eq(e.getKey()), anyString()))
                    .thenReturn(Optional.of(item(e.getKey(), e.getValue())));
        }
        when(catalog.getItem(eq("GHOST"), anyString())).thenReturn(Optional.empty());
        when(catalog.getItem(eq("DOES-NOT-EXIST"), anyString())).thenReturn(Optional.empty());
        store = new CartStore(15, 3600);   // long sweep interval — TTL not exercised here
        cart = new CartOperations(store, catalog);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private static ItemDto item(String id, double listPrice) {
        return new ItemDto(null, "PROD", "Name",
                "attr1", null, null, null, null, id, "desc", listPrice, listPrice, "/img.gif");
    }

    @Test
    void addItem_setsQuantityToOne() {
        cart.addItem(CART, "EST-1", null);
        assertThat(cart.view(CART).items()).singleElement()
                .satisfies(ci -> assertThat(ci.quantity()).isEqualTo(1));
    }

    @Test
    void addItem_again_RESETS_quantityToOne_notIncrement() {
        cart.addItem(CART, "EST-1", 5);
        cart.addItem(CART, "EST-1", null);
        assertThat(cart.view(CART).items()).singleElement()
                .satisfies(ci -> assertThat(ci.quantity()).isEqualTo(1));
    }

    @Test
    void setQuantity_zeroOrNegative_silentlyDeletes() {
        cart.addItem(CART, "EST-1", 3);
        cart.setQuantity(CART, "EST-1", 0);
        assertThat(cart.view(CART).items()).isEmpty();
        cart.addItem(CART, "EST-2", 3);
        cart.setQuantity(CART, "EST-2", -4);
        assertThat(cart.view(CART).items()).isEmpty();
    }

    @Test
    void setQuantity_positive_setsAbsoluteQuantity() {
        cart.addItem(CART, "EST-1", 3);
        cart.setQuantity(CART, "EST-1", 7);
        assertThat(cart.view(CART).items()).singleElement()
                .satisfies(ci -> assertThat(ci.quantity()).isEqualTo(7));
    }

    @Test
    void count_isDistinctLineItems_notTotalQuantity() {
        cart.addItem(CART, "EST-1", 5);
        cart.addItem(CART, "EST-2", 9);
        assertThat(cart.view(CART).count()).isEqualTo(2);
    }

    @Test
    void view_skipsItemsNotInCatalog_noError() {
        cart.addItem(CART, "EST-1", null);
        cart.addItem(CART, "DOES-NOT-EXIST", 2);
        assertThat(cart.view(CART).items()).singleElement()
                .satisfies(ci -> assertThat(ci.itemId()).isEqualTo("EST-1"));
    }

    @Test
    void subTotal_sumsUnitCostTimesQuantity_usingListPrice() {
        cart.addItem(CART, "EST-1", 2);   // 16.50 * 2 = 33.00
        cart.addItem(CART, "EST-5", 1);   // 18.50
        assertThat(cart.view(CART).subTotal()).isEqualTo(51.50);
    }

    @Test
    void subtotal_ignoresDanglingItems() {
        cart.addItem(CART, "EST-1", 1);   // 16.50
        cart.addItem(CART, "GHOST", 100); // skipped
        assertThat(cart.view(CART).subTotal()).isEqualTo(16.50);
    }

    @Test
    void empty_clearsCart() {
        cart.addItem(CART, "EST-1", null);
        cart.addItem(CART, "EST-2", null);
        cart.empty(CART);
        assertThat(cart.view(CART).count()).isZero();
        assertThat(cart.view(CART).items()).isEmpty();
    }

    @Test
    void deleteItem_removesSingleLine() {
        cart.addItem(CART, "EST-1", null);
        cart.addItem(CART, "EST-2", null);
        cart.deleteItem(CART, "EST-1");
        assertThat(cart.view(CART).items()).singleElement()
                .satisfies(ci -> assertThat(ci.itemId()).isEqualTo("EST-2"));
    }

    @Test
    void carts_areIsolatedByCartId() {
        cart.addItem("cart-A", "EST-1", 2);
        cart.addItem("cart-B", "EST-5", 1);
        assertThat(cart.view("cart-A").items()).singleElement()
                .satisfies(ci -> assertThat(ci.itemId()).isEqualTo("EST-1"));
        assertThat(cart.view("cart-B").items()).singleElement()
                .satisfies(ci -> assertThat(ci.itemId()).isEqualTo("EST-5"));
    }

    @Test
    void ttlSweep_evictsIdleCarts() {
        try (CartStore shortTtl = new CartStore(0, 3600)) {   // 0-min TTL: everything is "idle"
            CartOperations ops = new CartOperations(shortTtl, mock(CatalogServiceClient.class));
            ops.addItem("c1", "EST-1", 1);
            assertThat(shortTtl.size()).isEqualTo(1);
            shortTtl.evictExpired();
            assertThat(shortTtl.size()).isZero();
        }
    }
}
