package com.petstore.order.service;

/**
 * Thrown when checkout is attempted with an empty cart. Mirrors the legacy
 * {@code ShoppingCartEmptyOrderException} that routed to the
 * cart_empty_order_error screen.
 */
public class EmptyCartException extends RuntimeException {

    public EmptyCartException() {
        super("Cannot check out: shopping cart is empty");
    }
}
