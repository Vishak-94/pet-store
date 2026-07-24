package com.petstore.cart;

import java.util.List;

/**
 * Wire DTOs for the cart contract — plain records serialized as JSON.
 *
 * <p>{@link CartItemView} mirrors the legacy {@code cart.model.CartItem} (note
 * the legacy quirk preserved: {@code unitCost} is the catalog <em>list price</em>).
 * {@link CartView} is the resolved cart: its lines, the subtotal, and the count
 * of distinct line items.
 */
public final class CartDtos {

    private CartDtos() {
    }

    public record CartItemView(
            String itemId,
            String productId,
            String category,
            String productName,
            String attribute,
            int quantity,
            double unitCost) {

        /** Legacy convenience: line total = unitCost * quantity. */
        public double totalCost() {
            return unitCost * quantity;
        }
    }

    /**
     * The resolved cart. {@code count} is the number of DISTINCT line items
     * (legacy getCount == cart.size()), not the total quantity. {@code subTotal}
     * is Σ(unitCost * quantity) over resolvable items.
     */
    public record CartView(List<CartItemView> items, double subTotal, int count) {

        public static CartView empty() {
            return new CartView(List.of(), 0d, 0);
        }
    }
}
