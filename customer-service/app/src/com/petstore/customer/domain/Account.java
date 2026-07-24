package com.petstore.customer.domain;

/**
 * Customer contact/billing details — framework-free value object.
 *
 * <p>Flattens the legacy Account → ContactInfo → Address graph into one value
 * object for the customer aggregate (the pieces were always accessed together).
 * Field names preserved from the legacy ContactInfo/Address CMP beans.
 *
 * <p>{@code status} carries the legacy {@code AccountEJB.status} CMP field
 * (values {@link #ACTIVE}/{@link #DISABLED}, seeded to {@code active} at
 * creation). See {@code AccountLocalHome.Active}/{@code Disabled}.
 */
public final class Account {

    /** Legacy {@code AccountLocalHome.Active} status constant. */
    public static final String ACTIVE = "active";
    /** Legacy {@code AccountLocalHome.Disabled} status constant. */
    public static final String DISABLED = "disabled";

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
    private final String status;

    public Account(String givenName, String familyName, String email, String telephone,
                   String streetName1, String streetName2, String city, String state,
                   String zipCode, String country) {
        this(givenName, familyName, email, telephone, streetName1, streetName2,
                city, state, zipCode, country, ACTIVE);
    }

    public Account(String givenName, String familyName, String email, String telephone,
                   String streetName1, String streetName2, String city, String state,
                   String zipCode, String country, String status) {
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
        this.status = status;
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
    public String getStatus() { return status; }
}
