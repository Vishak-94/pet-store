package com.petstore.customer.web;

import com.petstore.customer.client.CustomerDtos.AccountDto;
import com.petstore.customer.client.CustomerDtos.CardDto;
import com.petstore.customer.client.CustomerDtos.CustomerView;
import com.petstore.customer.client.CustomerDtos.ProfileDto;
import com.petstore.customer.client.CustomerServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Optional;

/**
 * Account self-service for a signed-in customer — restores the legacy
 * {@code customer.do}/{@code update_customer.screen} UPDATE path. Holds NO
 * business logic; it DELEGATES to customer-service via the client SDK's
 * {@code updateAccount}/{@code updateProfile}/{@code updateCard}, forwarding the
 * session JWT as a Bearer token (same pattern as the storefront checkout read).
 *
 * <p>Mirrors the legacy {@code CustomerHTMLAction} UPDATE: one screen submitting
 * contact/address, credit card and profile prefs together; no new capabilities.
 */
@Controller
public class CustomerController {

    private static final Logger log = LoggerFactory.getLogger(CustomerController.class);

    private final CustomerServiceClient customerClient;

    public CustomerController(CustomerServiceClient customerClient) {
        this.customerClient = customerClient;
    }

    /** Shows the account-edit form, pre-filled from the current customer profile. */
    @GetMapping("/customer")
    public String editForm(Authentication auth, Model model) {
        model.addAttribute("customer", fetchCustomer(auth).orElse(null));
        return "update_customer";
    }

    /**
     * Applies the edits — updates account, profile and card via the SDK (one legacy
     * UPDATE screen → the three SDK operations). Card is only touched when a number
     * is supplied (legacy left an untouched card alone).
     */
    @PostMapping("/customer")
    public String update(Authentication auth,
                         @RequestParam(required = false) String givenName,
                         @RequestParam(required = false) String familyName,
                         @RequestParam(required = false) String email,
                         @RequestParam(required = false) String telephone,
                         @RequestParam(required = false) String street1,
                         @RequestParam(required = false) String street2,
                         @RequestParam(required = false) String city,
                         @RequestParam(required = false) String state,
                         @RequestParam(required = false) String zipCode,
                         @RequestParam(required = false) String country,
                         @RequestParam(required = false) String language,
                         @RequestParam(required = false) String favoriteCategory,
                         @RequestParam(defaultValue = "false") boolean myListPreference,
                         @RequestParam(defaultValue = "false") boolean bannerPreference,
                         @RequestParam(required = false) String cardNumber,
                         @RequestParam(required = false) String cardType,
                         @RequestParam(required = false) String cardExpiry,
                         Model model) {
        String userId = userId(auth);
        String token = token(auth);
        if (token == null) {
            model.addAttribute("error", "Your session has expired, please sign on again.");
            return "update_customer";
        }
        try {
            customerClient.updateAccount(userId, new AccountDto(
                    givenName, familyName, email, telephone, street1, street2, city, state, zipCode, country), token);
            customerClient.updateProfile(userId, new ProfileDto(
                    language, favoriteCategory, myListPreference, bannerPreference), token);
            if (cardNumber != null && !cardNumber.isBlank()) {
                customerClient.updateCard(userId, new CardDto(cardNumber, cardType, cardExpiry), token);
            }
            log.info("Updated customer {} via customer-service", userId);
        } catch (org.springframework.web.client.HttpClientErrorException.BadRequest e) {
            model.addAttribute("error", "Invalid account details.");
            model.addAttribute("customer", fetchCustomer(auth).orElse(null));
            return "update_customer";
        } catch (org.springframework.web.client.RestClientException e) {
            model.addAttribute("error", "Account service unavailable, please try again.");
            model.addAttribute("customer", fetchCustomer(auth).orElse(null));
            return "update_customer";
        }
        model.addAttribute("customer", fetchCustomer(auth).orElse(null));
        model.addAttribute("updated", true);
        return "update_customer";
    }

    private Optional<CustomerView> fetchCustomer(Authentication auth) {
        String token = token(auth);
        if (token == null) {
            return Optional.empty();
        }
        try {
            return customerClient.getCustomer(userId(auth), token);
        } catch (org.springframework.web.client.RestClientException e) {
            log.warn("could not fetch customer {}: {}", userId(auth), e.getMessage());
            return Optional.empty();
        }
    }

    /** The session JWT (customer-service Bearer token), or null if absent. */
    private static String token(Authentication auth) {
        Object token = auth.getCredentials();
        return token == null ? null : token.toString();
    }

    /** The stable userId customer-service is keyed by (from the token), not the username. */
    private static String userId(Authentication auth) {
        return auth.getDetails() instanceof String uid ? uid : auth.getName();
    }
}
