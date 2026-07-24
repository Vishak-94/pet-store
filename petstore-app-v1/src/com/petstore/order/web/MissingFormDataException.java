package com.petstore.order.web;

import java.util.List;

/**
 * Thrown when a required ship-to/bill-to field is blank at checkout — mirrors the
 * legacy {@code MissingFormDataException} raised by {@code OrderHTMLAction}. Carries
 * the list of missing field labels so the HTML flow can re-render the form with a
 * clear message and the JSON flow can return a 400 with the same detail.
 */
public class MissingFormDataException extends RuntimeException {

    private final List<String> missingFields;

    public MissingFormDataException(List<String> missingFields) {
        super("Missing required address fields: " + String.join(", ", missingFields));
        this.missingFields = List.copyOf(missingFields);
    }

    public List<String> getMissingFields() {
        return missingFields;
    }
}
