package com.petstore.order.domain;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Purchase order aggregate — framework-free value object.
 *
 * <p>Fields carried over from the legacy {@code purchaseorder.ejb.PurchaseOrder}
 * (orderId, userId, emailId, orderDate, locale, totalPrice, lineItems). Shipping
 * contact is captured as a flattened {@link com.petstore.customer.domain.Account}-style
 * snapshot on the order; for Phase 4 we keep the fields the checkout + workflow
 * need. {@code totalPrice} is preserved as the sum of unitPrice*quantity computed
 * at checkout.
 */
public final class PurchaseOrder {

    private final String orderId;
    private final String userId;
    private final String emailId;
    private final Instant orderDate;
    private final Locale locale;
    private final double totalPrice;
    private final List<LineItem> lineItems;

    public PurchaseOrder(String orderId, String userId, String emailId, Instant orderDate,
                         Locale locale, double totalPrice, List<LineItem> lineItems) {
        this.orderId = orderId;
        this.userId = userId;
        this.emailId = emailId;
        this.orderDate = orderDate;
        this.locale = locale;
        this.totalPrice = totalPrice;
        this.lineItems = List.copyOf(lineItems);
    }

    public String getOrderId() { return orderId; }
    public String getUserId() { return userId; }
    public String getEmailId() { return emailId; }
    public Instant getOrderDate() { return orderDate; }
    public Locale getLocale() { return locale; }
    public double getTotalPrice() { return totalPrice; }
    public List<LineItem> getLineItems() { return lineItems; }
}
