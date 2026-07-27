package com.petstore.order.service;

/**
 * Thrown when checkout cannot reach order-processing-service (OPC) to place the order —
 * the OPC is down/slow or its circuit breaker is open, so the synchronous intake call
 * ({@code POST /api/orders/intake}) failed at the transport layer.
 *
 * <p>This is the deliberate availability trade-off of synchronous intake (Shape 2): with the
 * old fire-and-forget JMS publish, an OPC outage was absorbed by the broker; now it surfaces to
 * the shopper. The checkout controllers map this to a clean <b>503</b> / "try again shortly"
 * page rather than a 500 — the order was NOT placed and the cart is NOT emptied, so the shopper
 * can safely retry. Distinct from {@link EmptyCartException} (a 400 the shopper must fix, not retry).
 */
public class OrderIntakeUnavailableException extends RuntimeException {

    public OrderIntakeUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
