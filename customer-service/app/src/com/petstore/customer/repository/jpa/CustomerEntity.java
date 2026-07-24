package com.petstore.customer.repository.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.petstore.customer.domain.Account;
import com.petstore.customer.domain.CreditCard;
import com.petstore.customer.domain.Customer;
import com.petstore.customer.domain.Profile;

/**
 * JPA mapping of the customer aggregate — redesigned from the legacy CMP graph
 * (Customer/Account/Profile/ContactInfo/Address) flattened into one table with
 * real typed columns (no {@code __PMPrimaryKey}/{@code __reverse_*}).
 */
@Entity
@Table(name = "customer")
class CustomerEntity {

    @Id
    @Column(name = "user_id", length = 25)
    String userId;

    // account / contact
    @Column(name = "given_name") String givenName;
    @Column(name = "family_name") String familyName;
    @Column(name = "email") String email;
    @Column(name = "telephone") String telephone;
    @Column(name = "street1") String streetName1;
    @Column(name = "street2") String streetName2;
    @Column(name = "city") String city;
    @Column(name = "state") String state;
    @Column(name = "zip_code") String zipCode;
    @Column(name = "country") String country;

    // profile
    @Column(name = "preferred_language") String preferredLanguage;
    @Column(name = "favorite_category") String favoriteCategory;
    @Column(name = "my_list_pref") boolean myListPreference;
    @Column(name = "banner_pref") boolean bannerPreference;

    // credit card
    @Column(name = "card_number") String cardNumber;
    @Column(name = "card_type") String cardType;
    @Column(name = "card_expiry") String cardExpiry;

    protected CustomerEntity() {
    }

    static CustomerEntity fromDomain(Customer c) {
        CustomerEntity e = new CustomerEntity();
        e.userId = c.getUserId();
        Account a = c.getAccount();
        if (a != null) {
            e.givenName = a.getGivenName();
            e.familyName = a.getFamilyName();
            e.email = a.getEmail();
            e.telephone = a.getTelephone();
            e.streetName1 = a.getStreetName1();
            e.streetName2 = a.getStreetName2();
            e.city = a.getCity();
            e.state = a.getState();
            e.zipCode = a.getZipCode();
            e.country = a.getCountry();
        }
        Profile p = c.getProfile() == null ? Profile.defaults() : c.getProfile();
        e.preferredLanguage = p.getPreferredLanguage();
        e.favoriteCategory = p.getFavoriteCategory();
        e.myListPreference = p.isMyListPreference();
        e.bannerPreference = p.isBannerPreference();
        CreditCard cc = c.getCreditCard();
        if (cc != null) {
            e.cardNumber = cc.getCardNumber();
            e.cardType = cc.getCardType();
            e.cardExpiry = cc.getExpiryDate();
        }
        return e;
    }

    Customer toDomain() {
        Account a = new Account(givenName, familyName, email, telephone,
                streetName1, streetName2, city, state, zipCode, country);
        Profile p = new Profile(preferredLanguage, favoriteCategory,
                myListPreference, bannerPreference);
        CreditCard cc = (cardNumber == null && cardType == null && cardExpiry == null)
                ? null : new CreditCard(cardNumber, cardType, cardExpiry);
        return new Customer(userId, a, p, cc);
    }
}
