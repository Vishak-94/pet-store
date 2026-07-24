package com.petstore.order.service;

import com.petstore.cart.domain.CartItem;
import com.petstore.cart.service.CartService;
import com.petstore.messaging.Destinations;
import com.petstore.messaging.Events;
import com.petstore.messaging.MessagePublisher;
import com.petstore.messaging.events.PurchaseOrderEvent;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Checkout orchestration — faithful to the legacy {@code OrderEJBAction}: generate
 * an order id, build the PurchaseOrder from the cart (validating non-empty,
 * computing the total), and PUBLISH it to the queue. It does NOT persist the order
 * — exactly like the legacy storefront, which only sent the PO onto the JMS queue;
 * the Order Processing Center (order-processing-service) persists it on consume.
 *
 * <p>Flow: empty cart → EmptyCartException; snowflake id; build event from cart;
 * publish to PurchaseOrderQueue; empty cart. Fire-and-forget.
 */
@Service
public class OrderService {

    private static final String LOCALE = Locale.US.toString();

    private final CartService cart;
    private final MessagePublisher publisher;
    private final OrderIdGenerator ids;

    public OrderService(CartService cart, MessagePublisher publisher, OrderIdGenerator ids) {
        this.cart = cart;
        this.publisher = publisher;
        this.ids = ids;
    }

    /** The outcome of a checkout — the id assigned and the total (for the result page). */
    public record OrderPlaced(String orderId, double total) {
    }

    /**
     * Places an order from the current cart: build the PO event, publish to the
     * Order Processing Center via JMS, empty the cart. No local persistence.
     *
     * <p>{@code shipTo}/{@code billTo} carry the ship-to and bill-to contact info
     * the legacy {@code OrderEJBAction} collected at checkout; they are populated
     * on the published {@link PurchaseOrderEvent} (may be null for the API path
     * that doesn't collect them).
     *
     * @throws EmptyCartException if the cart has no resolvable items
     */
    public OrderPlaced checkout(String userId, String emailId,
                                PurchaseOrderEvent.ContactInfo shipTo,
                                PurchaseOrderEvent.ContactInfo billTo) {
        List<CartItem> items = cart.getItems();
        if (items.isEmpty()) {
            throw new EmptyCartException();
        }

        String orderId = ids.nextId();
        List<PurchaseOrderEvent.Line> lines = new java.util.ArrayList<>();
        double total = 0d;
        for (CartItem ci : items) {
            double cost = ci.getUnitCost();
            total += cost * ci.getQuantity();
            lines.add(new PurchaseOrderEvent.Line(
                    ci.getItemId(), ci.getProductId(), ci.getCategory(),
                    ci.getQuantity(), cost));
        }

        PurchaseOrderEvent event = new PurchaseOrderEvent(
                Events.meta(PurchaseOrderEvent.TYPE),
                orderId, userId, emailId, LOCALE, total, lines, shipTo, billTo);

        publisher.publish(Destinations.PURCHASE_ORDER, event);   // → order-processing-service
        cart.empty();
        return new OrderPlaced(orderId, total);
    }

    /** Convenience overload for callers that don't collect ship/bill contact info. */
    public OrderPlaced checkout(String userId, String emailId) {
        return checkout(userId, emailId, null, null);
    }
}
