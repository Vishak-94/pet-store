package com.petstore.cart;

import com.petstore.cart.CartDtos.CartItemView;
import com.petstore.cart.CartDtos.CartView;
import com.petstore.catalog.client.CatalogServiceClient;
// (CartDtos lives in this same package — imports kept explicit for the nested records)

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Cart operations — the embeddable business logic of the cart, a faithful port
 * of the legacy {@code ShoppingCartLocalEJB}. Runs in-process in the host app.
 *
 * <p>State lives in {@link CartStore} (in-memory, TTL-evicted); item details are
 * resolved on demand from catalog-service via {@link CatalogServiceClient}.
 * Framework-free: construct it with a store and a catalog client (the host wires
 * them as beans). Every operation is keyed by {@code cartId}.
 *
 * <p><b>Legacy behaviour preserved exactly</b> (pinned by tests):
 * <ul>
 *   <li>{@link #addItem(String, String, Integer)} sets quantity to 1 when qty is
 *       null — RESETS, does not increment;</li>
 *   <li>{@link #setQuantity(String, String, int)} with {@code qty <= 0} SILENTLY
 *       removes the line;</li>
 *   <li>{@link #view(String)} SKIPS items no longer in the catalog (no error);</li>
 *   <li>{@code count} is the number of DISTINCT line items, not total quantity;</li>
 *   <li>subtotal uses the catalog list price (the legacy CartItem "unitCost").</li>
 * </ul>
 */
public class CartOperations {

    private static final String LOCALE = "en_US";

    private final CartStore store;
    private final CatalogServiceClient catalog;

    public CartOperations(CartStore store, CatalogServiceClient catalog) {
        this.store = store;
        this.catalog = catalog;
    }

    public CartView addItem(String cartId, String itemId, Integer qty) {
        store.withCart(cartId, q -> q.put(itemId, qty == null ? 1 : qty));
        return view(cartId);
    }

    /** Sets absolute quantity; qty &lt;= 0 removes the line (legacy behaviour). */
    public CartView setQuantity(String cartId, String itemId, int qty) {
        store.withCart(cartId, q -> {
            q.remove(itemId);
            if (qty > 0) {
                q.put(itemId, qty);
            }
            return null;
        });
        return view(cartId);
    }

    public CartView deleteItem(String cartId, String itemId) {
        store.withCart(cartId, q -> q.remove(itemId));
        return view(cartId);
    }

    public void empty(String cartId) {
        store.remove(cartId);
    }

    /**
     * Resolves the cart to a view, skipping any item no longer in the catalog
     * (legacy caught CatalogException and dropped the entry). Subtotal is
     * Σ(listPrice * qty); count is the number of distinct lines.
     */
    public CartView view(String cartId) {
        Map<String, Integer> quantities = store.snapshot(cartId);
        List<CartItemView> items = new ArrayList<>();
        double subTotal = 0d;
        for (Map.Entry<String, Integer> e : quantities.entrySet()) {
            var item = catalog.getItem(e.getKey(), LOCALE).orElse(null);
            if (item == null) {
                continue; // dangling entry — silently skipped, as in legacy
            }
            int qty = e.getValue();
            double unitCost = item.listPrice();   // legacy quirk: unitCost fed from list price
            items.add(new CartItemView(
                    item.itemId(), item.productId(), item.category(),
                    item.productName(), item.attribute1(), qty, unitCost));
            subTotal += unitCost * qty;
        }
        // count = distinct line items (legacy getCount == cart.size()), not the
        // resolved size — a dangling id still counts as a line in the raw cart.
        return new CartView(items, subTotal, quantities.size());
    }
}
