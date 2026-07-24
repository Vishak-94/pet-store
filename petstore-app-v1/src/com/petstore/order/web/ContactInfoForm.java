package com.petstore.order.web;

import com.petstore.messaging.events.PurchaseOrderEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Ship-to / bill-to contact info collected at checkout — mirrors the fields the
 * legacy {@code OrderHTMLAction.extractContactInfo} parsed and validated. The
 * REQUIRED set (per legacy) is family name, given name, street 1, city, state,
 * postal code and telephone; {@code streetName2}, {@code country} and
 * {@code email} are optional. Blank optionals are normalised to {@code null}
 * (legacy folded empty street-2 / email to null).
 *
 * <p>A mutable JavaBean so Spring MVC can bind it as a nested command object
 * (e.g. {@code shipTo.familyName}) from the Thymeleaf checkout form.
 */
public class ContactInfoForm {

    private String familyName;
    private String givenName;
    private String streetName1;
    private String streetName2;
    private String city;
    private String state;
    private String zipCode;
    private String country;
    private String telephone;
    private String email;

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * The required fields left blank, labelled with {@code who} (e.g. "Ship-To")
     * so callers can report exactly which side of the order is incomplete —
     * mirrors the legacy missing-field list.
     */
    public List<String> missingRequiredFields(String who) {
        List<String> missing = new ArrayList<>();
        if (blank(familyName)) missing.add(who + " Last Name");
        if (blank(givenName)) missing.add(who + " First Name");
        if (blank(streetName1)) missing.add(who + " Street Address");
        if (blank(city)) missing.add(who + " City");
        if (blank(state)) missing.add(who + " State or Province");
        if (blank(zipCode)) missing.add(who + " Postal Code");
        if (blank(telephone)) missing.add(who + " Telephone Number");
        return missing;
    }

    /**
     * Validates both contacts as the legacy {@code OrderHTMLAction} did — every
     * required field on ship-to AND bill-to — throwing {@link MissingFormDataException}
     * listing all blanks if any are missing.
     */
    public static void requireValid(ContactInfoForm shipTo, ContactInfoForm billTo) {
        List<String> missing = new ArrayList<>(shipTo.missingRequiredFields("Ship-To"));
        missing.addAll(billTo.missingRequiredFields("Bill-To"));
        if (!missing.isEmpty()) {
            throw new MissingFormDataException(missing);
        }
    }

    /** Maps to the JMS ContactInfo, normalising blank optionals to null. */
    public PurchaseOrderEvent.ContactInfo toContactInfo() {
        return new PurchaseOrderEvent.ContactInfo(familyName, givenName, streetName1,
                blank(streetName2) ? null : streetName2, city, state, zipCode,
                blank(country) ? null : country, telephone, blank(email) ? null : email);
    }

    // ---- JavaBean accessors (form binding) ----

    public String getFamilyName() { return familyName; }
    public void setFamilyName(String familyName) { this.familyName = familyName; }

    public String getGivenName() { return givenName; }
    public void setGivenName(String givenName) { this.givenName = givenName; }

    public String getStreetName1() { return streetName1; }
    public void setStreetName1(String streetName1) { this.streetName1 = streetName1; }

    public String getStreetName2() { return streetName2; }
    public void setStreetName2(String streetName2) { this.streetName2 = streetName2; }

    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getZipCode() { return zipCode; }
    public void setZipCode(String zipCode) { this.zipCode = zipCode; }

    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }

    public String getTelephone() { return telephone; }
    public void setTelephone(String telephone) { this.telephone = telephone; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
}
