package com.petstore.customer.client;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * Data-transfer records for the customer-service client SDK. These define the
 * request/response shapes callers work with — independent of any server-side or
 * monolith domain model.
 */
public final class CustomerDtos {

    private CustomerDtos() {
    }

    /** Result of a successful login: the JWT + opaque customer id + roles. */
    public record AuthResult(String token, String customerId, List<String> roles) {
    }

    /**
     * Address/contact payload for registration + account updates. Length caps bound each field to
     * defend against oversized input (the required-field set is still enforced server-side by
     * {@code requireRegistrationFields}); {@code @Email} keeps the optional email well-formed and
     * {@code zipCode} is capped to a postal-code-sized string.
     */
    public record AccountDto(@Size(max = 60) String givenName, @Size(max = 60) String familyName,
                             @Email @Size(max = 120) String email, @Size(max = 30) String telephone,
                             @Size(max = 120) String streetName1, @Size(max = 120) String streetName2,
                             @Size(max = 60) String city, @Size(max = 60) String state,
                             @Size(max = 12) String zipCode, @Size(max = 60) String country) {
    }

    /** Credit-card payload. Length caps only (format/parity per DECISIONS.md; PAN stored as given). */
    public record CardDto(@Size(max = 24) String cardNumber, @Size(max = 30) String cardType,
                          @Size(max = 10) String expiryDate) {
    }

    /** Profile preferences payload. Length caps bound the free-text preference fields. */
    public record ProfileDto(@Size(max = 10) String preferredLanguage, @Size(max = 60) String favoriteCategory,
                             boolean myListPreference, boolean bannerPreference) {
    }

    /** Registration request. */
    public record RegisterRequest(
            @NotBlank @Size(max = 25) String userName,
            @NotBlank @Size(min = 4, max = 25) String password,
            @Valid AccountDto account,
            @Valid CardDto creditCard) {
    }

    /** Read view of a customer (card is masked by the service). */
    public record CustomerView(String userId, Map<String, Object> account,
                               Map<String, Object> profile, String cardMasked) {
    }
}
