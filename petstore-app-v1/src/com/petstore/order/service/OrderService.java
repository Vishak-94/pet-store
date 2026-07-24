package com.petstore.order.service;

import com.petstore.cart.domain.CartItem;
import com.petstore.cart.service.CartService;
import com.petstore.order.domain.LineItem;
import com.petstore.order.domain.PurchaseOrder;
import com.petstore.order.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Checkout orchestration — the monolith's remaining order responsibility after
 * the warehouse split. It CREATES the purchase order (its own DB) and publishes
 * it to JMS. Approval, workflow status, inventory and fulfilment are all owned
 * by warehouse-service now — the monolith no longer tracks order status.
 *
 * <p>Flow: empty cart → EmptyCartException; generate id; build PO from cart;
 * persist; publish full PO to PurchaseOrderQueue; empty cart.
 */
@Service
public class OrderService {

    private static final Locale LOCALE = Locale.US;

    private final CartService cart;
    private final OrderRepository orders;
    private final OrderMessagePublisher publisher;

    // Legacy uidgen started order ids at 1001.
    private final AtomicLong sequence = new AtomicLong(1001);

    public OrderService(CartService cart, OrderRepository orders, OrderMessagePublisher publisher) {
        this.cart = cart;
        this.orders = orders;
        this.publisher = publisher;
    }

    /**
     * Places an order from the current cart. Persists the PO and publishes it to
     * warehouse via JMS (which decides approval + fulfilment).
     *
     * @throws EmptyCartException if the cart has no resolvable items
     */
    @Transactional
    public PurchaseOrder checkout(String userId, String emailId) {
        List<CartItem> items = cart.getItems();
        if (items.isEmpty()) {
            throw new EmptyCartException();
        }

        String orderId = String.valueOf(sequence.getAndIncrement());
        List<LineItem> lines = new ArrayList<>();
        double total = 0d;
        int lineNumber = 0;
        for (CartItem ci : items) {
            double cost = ci.getUnitCost();
            total += cost * ci.getQuantity();
            lines.add(new LineItem(ci.getCategory(), ci.getProductId(), ci.getItemId(),
                    String.valueOf(lineNumber++), ci.getQuantity(), cost));
        }

        PurchaseOrder po = new PurchaseOrder(orderId, userId, emailId,
                Instant.now(), LOCALE, total, lines);

        orders.save(po);                 // monolith owns order creation (purchase_order/line_item)
        publisher.publishNewOrder(po);   // JMS → warehouse (approval + fulfilment)
        cart.empty();
        return po;
    }
}
