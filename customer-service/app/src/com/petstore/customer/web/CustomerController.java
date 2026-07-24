package com.petstore.customer.web;

import com.petstore.customer.client.CustomerDtos;
import com.petstore.customer.client.CustomerServiceEndpoints;
import com.petstore.customer.domain.Account;
import com.petstore.customer.domain.CreditCard;
import com.petstore.customer.domain.Customer;
import com.petstore.customer.domain.Profile;
import com.petstore.customer.service.CustomerService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Customer account REST endpoints. The URL paths and request/response DTOs come
 * from the shared {@code customer-service-client} SDK
 * ({@link CustomerServiceEndpoints}, {@link CustomerDtos}) — so the server and
 * every client are guaranteed to agree on the contract (single-sourced).
 */
@RestController
public class CustomerController {

    private final CustomerService customers;

    public CustomerController(CustomerService customers) {
        this.customers = customers;
    }

    @PostMapping(CustomerServiceEndpoints.REGISTER)
    public ResponseEntity<Map<String, String>> register(@Valid @RequestBody CustomerDtos.RegisterRequest req) {
        requireRegistrationFields(req);
        Account account = toAccount(req.account());
        CreditCard card = toCard(req.creditCard());
        Customer c = customers.register(req.userName(), req.password(), account, card);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("userId", c.getUserId(), "status", "registered"));
    }

    @GetMapping(CustomerServiceEndpoints.CUSTOMER)
    public CustomerDtos.CustomerView get(@PathVariable String id) {
        Customer c = customers.findByUserId(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such customer"));
        return toView(c);
    }

    @PutMapping(CustomerServiceEndpoints.ACCOUNT)
    public CustomerDtos.CustomerView updateAccount(@PathVariable String id, @RequestBody CustomerDtos.AccountDto dto) {
        return toView(customers.updateAccount(id, toAccount(dto)));
    }

    @PutMapping(CustomerServiceEndpoints.PROFILE)
    public CustomerDtos.CustomerView updateProfile(@PathVariable String id, @RequestBody CustomerDtos.ProfileDto dto) {
        Profile p = new Profile(dto.preferredLanguage(), dto.favoriteCategory(),
                dto.myListPreference(), dto.bannerPreference());
        return toView(customers.updateProfile(id, p));
    }

    @PutMapping(CustomerServiceEndpoints.CARD)
    public CustomerDtos.CustomerView updateCard(@PathVariable String id, @RequestBody CustomerDtos.CardDto dto) {
        return toView(customers.updateCreditCard(id, toCard(dto)));
    }

    /**
     * Service-side guard mirroring the legacy {@code CustomerHTMLAction} required
     * set ({@code extractContactInfo} + {@code extractCreditCard}): the contact-info
     * and credit-card fields the legacy create form rejected as
     * {@code MissingFormDataException}. Legacy treats streetName2, country and
     * email as optional (email/country are read but never flagged; streetName2 is
     * explicitly nulled when blank), so they are NOT required here. A missing/blank
     * required field yields a clear HTTP 400.
     */
    private static void requireRegistrationFields(CustomerDtos.RegisterRequest req) {
        List<String> missing = new ArrayList<>();
        CustomerDtos.AccountDto a = req.account();
        if (a == null) {
            missing.add("account");
        } else {
            requireField(missing, "account.familyName", a.familyName());
            requireField(missing, "account.givenName", a.givenName());
            requireField(missing, "account.streetName1", a.streetName1());
            requireField(missing, "account.city", a.city());
            requireField(missing, "account.state", a.state());
            requireField(missing, "account.zipCode", a.zipCode());
            requireField(missing, "account.telephone", a.telephone());
        }
        CustomerDtos.CardDto c = req.creditCard();
        if (c == null) {
            missing.add("creditCard");
        } else {
            requireField(missing, "creditCard.cardNumber", c.cardNumber());
            requireField(missing, "creditCard.cardType", c.cardType());
            requireField(missing, "creditCard.expiryDate", c.expiryDate());
        }
        if (!missing.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Missing required registration fields: " + String.join(", ", missing));
        }
    }

    private static void requireField(List<String> missing, String name, String value) {
        if (value == null || value.trim().isEmpty()) {
            missing.add(name);
        }
    }

    // ---- mapping between SDK DTOs and the domain (server-side only) ----

    private static Account toAccount(CustomerDtos.AccountDto a) {
        return a == null ? null : new Account(a.givenName(), a.familyName(), a.email(), a.telephone(),
                a.streetName1(), a.streetName2(), a.city(), a.state(), a.zipCode(), a.country());
    }

    private static CreditCard toCard(CustomerDtos.CardDto c) {
        return c == null ? null : new CreditCard(c.cardNumber(), c.cardType(), c.expiryDate());
    }

    private static CustomerDtos.CustomerView toView(Customer c) {
        Map<String, Object> account = null;
        Account a = c.getAccount();
        if (a != null) {
            account = new LinkedHashMap<>();
            account.put("givenName", a.getGivenName());
            account.put("familyName", a.getFamilyName());
            account.put("email", a.getEmail());
            account.put("telephone", a.getTelephone());
            account.put("streetName1", a.getStreetName1());
            account.put("streetName2", a.getStreetName2());
            account.put("city", a.getCity());
            account.put("state", a.getState());
            account.put("zipCode", a.getZipCode());
            account.put("country", a.getCountry());
            account.put("status", a.getStatus());
        }
        Map<String, Object> profile = null;
        Profile p = c.getProfile();
        if (p != null) {
            profile = new LinkedHashMap<>();
            profile.put("preferredLanguage", p.getPreferredLanguage());
            profile.put("favoriteCategory", p.getFavoriteCategory());
            profile.put("myListPreference", p.isMyListPreference());
            profile.put("bannerPreference", p.isBannerPreference());
        }
        String masked = (c.getCreditCard() == null || c.getCreditCard().getCardNumber() == null)
                ? null : mask(c.getCreditCard().getCardNumber());
        return new CustomerDtos.CustomerView(c.getUserId(), account, profile, masked);
    }

    private static String mask(String pan) {
        String digits = pan.replaceAll("\\s", "");
        return digits.length() <= 4 ? "****" : "**** **** **** " + digits.substring(digits.length() - 4);
    }
}
