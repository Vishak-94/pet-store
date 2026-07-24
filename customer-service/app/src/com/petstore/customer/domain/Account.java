package com.petstore.customer.domain;

/**
 * Customer contact/billing details — framework-free value object.
 *
 * <p>Flattens the legacy Account → ContactInfo → Address graph into one value
 * object for the customer aggregate (the pieces were always accessed together).
 * Field names preserved from the legacy ContactInfo/Address CMP beans.
 */
public final class Account {

    private final String givenName;
    private final String familyName;
    private final String email;
    private final String telephone;
    private final String streetName1;
    private final String streetName2;
    private final String city;
    private final String state;
    private final String zipCode;
    private final String country;

    public Account(String givenName, String familyName, String email, String telephone,
                   String streetName1, String streetName2, String city, String state,
                   String zipCode, String country) {
        this.givenName = givenName;
        this.familyName = familyName;
        this.email = email;
        this.telephone = telephone;
        this.streetName1 = streetName1;
        this.streetName2 = streetName2;
        this.city = city;
        this.state = state;
        this.zipCode = zipCode;
        this.country = country;
    }

    public String getGivenName() { return givenName; }
    public String getFamilyName() { return familyName; }
    public String getEmail() { return email; }
    public String getTelephone() { return telephone; }
    public String getStreetName1() { return streetName1; }
    public String getStreetName2() { return streetName2; }
    public String getCity() { return city; }
    public String getState() { return state; }
    public String getZipCode() { return zipCode; }
    public String getCountry() { return country; }
}
