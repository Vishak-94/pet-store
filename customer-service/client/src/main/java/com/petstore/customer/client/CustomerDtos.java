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

    /** Address/contact payload for registration + account updates. */
    public record AccountDto(String givenName, String familyName, @Email String email, String telephone,
                             String streetName1, String streetName2, String city, String state,
                             String zipCode, String country) {
    }

    /** Credit-card payload. */
    public record CardDto(String cardNumber, String cardType, String expiryDate) {
    }

    /** Profile preferences payload. */
    public record ProfileDto(String preferredLanguage, String favoriteCategory,
                             boolean myListPreference, boolean bannerPreference) {
    }

    /** Registration request. */
    public record RegisterRequest(
            @NotBlank @Size(max = 25) String userName,
            @NotBlank @Size(min = 4, max = 25) String password,
            @Valid AccountDto account,
            CardDto creditCard) {
    }

    /** Read view of a customer (card is masked by the service). */
    public record CustomerView(String userId, Map<String, Object> account,
                               Map<String, Object> profile, String cardMasked) {
    }
}
