package com.petstore.order.web;

/**
 * Command object for the HTML checkout POST — the ship-to and bill-to contact
 * blocks the legacy {@code OrderHTMLAction} collected (legacy suffixes {@code _a}
 * / {@code _b}). Bound by Spring MVC as {@code shipTo.*} / {@code billTo.*} form
 * fields; validated by {@link ContactInfoForm#requireValid}.
 */
public class CheckoutForm {

    private ContactInfoForm shipTo = new ContactInfoForm();
    private ContactInfoForm billTo = new ContactInfoForm();

    public ContactInfoForm getShipTo() { return shipTo; }
    public void setShipTo(ContactInfoForm shipTo) { this.shipTo = shipTo; }

    public ContactInfoForm getBillTo() { return billTo; }
    public void setBillTo(ContactInfoForm billTo) { this.billTo = billTo; }
}
