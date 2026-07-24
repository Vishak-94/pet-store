package com.petstore.customer.domain;

/**
 * Credit card details — framework-free value object.
 *
 * <p>Restored from the legacy {@code creditcard.ejb.CreditCard} CMP bean
 * (cardNumber, cardType, expiryDate) that the customer Account owned. This was
 * omitted in the first Phase-2 pass; re-added here for legacy parity.
 *
 * <p>Note: stored as-is to preserve legacy behaviour. Tokenising/encrypting card
 * data (PCI) is a security improvement to make post-migration, NOT part of a
 * behaviour-preserving parity migration — flagged in DECISIONS.md.
 */
public final class CreditCard {

    private final String cardNumber;
    private final String cardType;
    private final String expiryDate;

    public CreditCard(String cardNumber, String cardType, String expiryDate) {
        this.cardNumber = cardNumber;
        this.cardType = cardType;
        this.expiryDate = expiryDate;
    }

    public String getCardNumber() { return cardNumber; }
    public String getCardType() { return cardType; }
    public String getExpiryDate() { return expiryDate; }
}
