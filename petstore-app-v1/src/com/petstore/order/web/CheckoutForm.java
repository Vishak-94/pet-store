package com.petstore.order.web;

import jakarta.validation.Valid;

/**
 * Command object for the HTML checkout POST — the ship-to and bill-to contact
 * blocks the legacy {@code OrderHTMLAction} collected (legacy suffixes {@code _a}
 * / {@code _b}). Bound by Spring MVC as {@code shipTo.*} / {@code billTo.*} form
 * fields; validated by {@link ContactInfoForm#requireValid}.
 */
public class CheckoutForm {

    /**
     * Encrypted idempotency token minted by {@code POST /pre-checkout}. The checkout page's JS
     * fetches it and puts it in a hidden field; it is echoed back on submit, then decrypted and
     * matched against the customer's outstanding reservation so a refresh / double-click replay
     * carries the same value and is de-duplicated. Opaque + tamper-evident on the wire (AES/GCM);
     * never trusted as an arbitrary client value — it must decrypt to the reserved order id
     * (see {@code OrderKeyCipher} / {@code IdempotencyKeyStore}).
     */
    private String orderKey;

    @Valid private ContactInfoForm shipTo = new ContactInfoForm();
    @Valid private ContactInfoForm billTo = new ContactInfoForm();

    public String getOrderKey() { return orderKey; }
    public void setOrderKey(String orderKey) { this.orderKey = orderKey; }

    public ContactInfoForm getShipTo() { return shipTo; }
    public void setShipTo(ContactInfoForm shipTo) { this.shipTo = shipTo; }

    public ContactInfoForm getBillTo() { return billTo; }
    public void setBillTo(ContactInfoForm billTo) { this.billTo = billTo; }
}
