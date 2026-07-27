package com.petstore.customer.web;

import com.petstore.customer.client.CustomerDtos.AccountDto;
import com.petstore.customer.client.CustomerDtos.CardDto;
import com.petstore.customer.client.CustomerDtos.CustomerView;
import com.petstore.customer.client.CustomerDtos.ProfileDto;
import com.petstore.customer.client.CustomerServiceClient;
import com.petstore.security.AuthenticatedUser;
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

    /** Thymeleaf view for the account-edit page. */
    private static final String VIEW_UPDATE_CUSTOMER = "update_customer";
    /** Model attribute keys consumed by the update_customer template. */
    private static final String ATTR_CUSTOMER = "customer";
    private static final String ATTR_ERROR = "error";
    private static final String ATTR_UPDATED = "updated";
    /** User-facing error messages for the account-edit flow. */
    private static final String MSG_SESSION_EXPIRED = "Your session has expired, please sign on again.";
    private static final String MSG_INVALID_DETAILS = "Invalid account details.";
    private static final String MSG_SERVICE_UNAVAILABLE = "Account service unavailable, please try again.";

    private final CustomerServiceClient customerClient;

    public CustomerController(CustomerServiceClient customerClient) {
        this.customerClient = customerClient;
    }

    /**
     * Shows the account-edit form, pre-filled from the current customer profile (read from
     * customer-service with the session JWT). Authenticated customers only.
     *
     * <pre>{@code
     * GET /customer
     * (identity + Bearer token from the session Authentication)
     *
     * 200 OK  renders update_customer.html
     *   model: customer = {account:{givenName:"J", familyName:"Doe", ...}, profile:{...}, card:{...}}
     *                      // null if the token expired or customer-service is unavailable
     * }</pre>
     *
     * <p>Anonymous requests are redirected to {@code /login} by SecurityConfig before reaching here.
     */
    @GetMapping("/customer")
    public String editForm(Authentication auth, Model model) {
        model.addAttribute(ATTR_CUSTOMER, fetchCustomer(auth).orElse(null));
        return VIEW_UPDATE_CUSTOMER;
    }

    /**
     * Applies the edits — updates account, profile and card via the SDK (one legacy
     * UPDATE screen → the three SDK operations). Card is only touched when a number
     * is supplied (legacy left an untouched card alone).
     *
     * <pre>{@code
     * POST /customer
     *   form: givenName=Jane&familyName=Doe&email=jane@x.com&telephone=555-0100
     *         &street1=1+Main+St&city=Palo+Alto&state=CA&zipCode=94301&country=US
     *         &language=en_US&favoriteCategory=DOGS&myListPreference=true&bannerPreference=false
     *         &cardNumber=4111...&cardType=Visa&cardExpiry=12/29   // card fields optional
     *
     * 200 OK  renders update_customer.html
     *   model: updated=true, customer={...refreshed profile...}
     * }</pre>
     *
     * <p>Edge cases (re-render update_customer.html with an {@code error} message, no redirect):
     * expired/absent token → "session expired"; customer-service 400 → "invalid details";
     * any other RestClientException → "service unavailable".
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
            model.addAttribute(ATTR_ERROR, MSG_SESSION_EXPIRED);
            return VIEW_UPDATE_CUSTOMER;
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
            model.addAttribute(ATTR_ERROR, MSG_INVALID_DETAILS);
            model.addAttribute(ATTR_CUSTOMER, fetchCustomer(auth).orElse(null));
            return VIEW_UPDATE_CUSTOMER;
        } catch (org.springframework.web.client.RestClientException e) {
            model.addAttribute(ATTR_ERROR, MSG_SERVICE_UNAVAILABLE);
            model.addAttribute(ATTR_CUSTOMER, fetchCustomer(auth).orElse(null));
            return VIEW_UPDATE_CUSTOMER;
        }
        model.addAttribute(ATTR_CUSTOMER, fetchCustomer(auth).orElse(null));
        model.addAttribute(ATTR_UPDATED, true);
        return VIEW_UPDATE_CUSTOMER;
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
        return AuthenticatedUser.userId(auth);
    }
}
