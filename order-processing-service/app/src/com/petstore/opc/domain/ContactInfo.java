package com.petstore.opc.domain;

/**
 * Ship-to / bill-to contact info stored with an order — the legacy
 * {@code ContactInfo} + nested {@code Address}, flattened. Framework-free.
 * {@code streetName2} is optional (as in legacy).
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
