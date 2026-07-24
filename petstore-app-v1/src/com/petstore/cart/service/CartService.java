package com.petstore.cart.service;

import com.petstore.cart.domain.CartItem;
import com.petstore.cart.web.CartIdFilter;
import com.petstore.cart.CartDtos.CartView;
import com.petstore.cart.CartOperations;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

/**
 * Cart adapter — the monolith no longer holds cart state in an ad-hoc map; it
 * delegates to the embeddable cart library ({@link CartOperations}), which runs
 * IN-PROCESS (in-memory carts with a sliding TTL). This bean resolves the current
 * request's cart id (issued by {@link CartIdFilter} as a SecureRandom cookie) and
 * calls the library — no network hop, since cart is session-local state.
 *
 * <p>The method surface is unchanged so callers (CartController, OrderService,
 * StorefrontController, GlobalModelAdvice) need no edits. Observable cart
 * behaviour is preserved by the library (set-to-1 add, silent remove on qty<=0,
 * skip dangling items, distinct-line count, list-price subtotal).
 */
@Service
public class CartService {

    private final CartOperations cart;

    public CartService(CartOperations cart) {
        this.cart = cart;
    }

    /** The current request's cart id (set by CartIdFilter). */
    private String cartId() {
        var attrs = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
        Object id = attrs.getRequest().getAttribute(CartIdFilter.REQUEST_ATTR);
        if (id == null) {
            throw new IllegalStateException("No cartId on request — is CartIdFilter registered?");
        }
        return id.toString();
    }

    /** Adds an item, setting its quantity to 1 (resets if already present). */
    public void addItem(String itemId) {
        cart.addItem(cartId(), itemId, null);
    }

    /** Adds an item with an explicit quantity (overwrites any existing). */
    public void addItem(String itemId, int qty) {
        cart.addItem(cartId(), itemId, qty);
    }

    public void deleteItem(String itemId) {
        cart.deleteItem(cartId(), itemId);
    }

    /** Sets absolute quantity; qty &lt;= 0 removes the line (legacy behaviour). */
    public void updateItemQuantity(String itemId, int newQty) {
        cart.setQuantity(cartId(), itemId, newQty);
    }

    /** Number of DISTINCT line items (legacy getCount == cart.size()). */
    public int getCount() {
        return cart.view(cartId()).count();
    }

    /** Current quantity of an item in the cart (0 if not present). */
    public int quantityOf(String itemId) {
        return cart.view(cartId()).items().stream()
                .filter(i -> i.itemId().equals(itemId))
                .mapToInt(com.petstore.cart.CartDtos.CartItemView::quantity)
                .findFirst().orElse(0);
    }

    /** Resolves the cart to CartItems (dangling items already skipped by the library). */
    public List<CartItem> getItems() {
        return toCartItems(cart.view(cartId()));
    }

    /** Subtotal = sum(unitCost * quantity) over resolvable items. */
    public double getSubTotal() {
        return cart.view(cartId()).subTotal();
    }

    public void empty() {
        cart.empty(cartId());
    }

    private static List<CartItem> toCartItems(CartView view) {
        return view.items().stream()
                .map(i -> new CartItem(i.itemId(), i.productId(), i.category(),
                        i.productName(), i.attribute(), i.quantity(), i.unitCost()))
                .toList();
    }
}
