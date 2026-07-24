package com.petstore.customer.domain;

/**
 * Customer aggregate root — framework-free value object.
 *
 * <p>Preserves the legacy structure: a customer is keyed by {@code userId} and
 * owns an {@link Account} (contact/billing address), a {@link Profile}
 * (preferences), and a {@link CreditCard}. Credentials live separately in
 * {@link User} (the legacy signon component).
 */
public final class Customer {

    private final String userId;
    private final Account account;
    private final Profile profile;
    private final CreditCard creditCard;

    public Customer(String userId, Account account, Profile profile, CreditCard creditCard) {
        this.userId = userId;
        this.account = account;
        this.profile = profile;
        this.creditCard = creditCard;
    }

    public String getUserId() { return userId; }
    public Account getAccount() { return account; }
    public Profile getProfile() { return profile; }
    public CreditCard getCreditCard() { return creditCard; }
}
