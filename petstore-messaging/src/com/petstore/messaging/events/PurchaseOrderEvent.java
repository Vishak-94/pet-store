package com.petstore.messaging.events;

import com.petstore.messaging.EventMeta;

import java.util.List;

/**
 * A new purchase order placed at checkout (monolith → warehouse over the
 * PurchaseOrderQueue). Envelope metadata in {@code meta}; the order in the rest.
 *
 * <p>{@code shipTo}/{@code billTo} carry the ship-to and bill-to contact info the
 * legacy {@code OrderEJBAction} collected and validated at checkout and stored on
 * the {@code PurchaseOrder}. They are nullable so older producers / serialized
 * messages remain compatible; the checkout form populates them.
 */
public record PurchaseOrderEvent(
        EventMeta meta,
        String orderId,
        String userId,
        String emailId,
        String locale,
        double totalPrice,
        List<Line> lines,
        ContactInfo shipTo,
        ContactInfo billTo) {

    public record Line(String itemId, String productId, String categoryId,
                       int quantity, double unitPrice) {
    }

    /**
     * Ship-to / bill-to contact info — the legacy {@code ContactInfo} + nested
     * {@code Address} flattened. {@code streetName2} is optional (as in legacy);
     * all other fields were required at checkout.
     */
    public record ContactInfo(
            String familyName,
            String givenName,
            String streetName1,
            String streetName2,
            String city,
            String state,
            String zipCode,
            String country,
            String telephone,
            String email) {
    }

    /** The JMS type-id / logical event type. */
    public static final String TYPE = "PurchaseOrder";
}
