package com.petstore.web;

import com.petstore.cart.service.CartService;
import com.petstore.customer.client.CustomerServiceClient;
import com.petstore.order.service.EmptyCartException;
import com.petstore.order.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * HTML storefront controller. Holds NO customer business logic — registration
 * and customer reads DELEGATE to the customer-service microservice via
 * {@link CustomerServiceClient}. Checkout uses the cart + order services (which
 * remain in the monolith) and the customer's email fetched from customer-service.
 */
@Controller
public class StorefrontController {

    private static final Logger log = LoggerFactory.getLogger(StorefrontController.class);

    private final CustomerServiceClient customerClient;
    private final CartService cart;
    private final OrderService orders;

    public StorefrontController(CustomerServiceClient customerClient, CartService cart,
                                OrderService orders) {
        this.customerClient = customerClient;
        this.cart = cart;
        this.orders = orders;
    }

    // ---- Registration (HTML form → customer-service) ----

    @GetMapping("/register-form")
    public String registerForm() {
        return "register";
    }

    @PostMapping("/register-form")
    public String register(@RequestParam String userName,
                           @RequestParam String password,
                           @RequestParam(required = false) String givenName,
                           @RequestParam(required = false) String familyName,
                           @RequestParam(required = false) String email,
                           @RequestParam(required = false) String street1,
                           @RequestParam(required = false) String city,
                           @RequestParam(required = false) String state,
                           @RequestParam(required = false) String zipCode,
                           @RequestParam(required = false) String country,
                           @RequestParam(required = false) String cardNumber,
                           @RequestParam(required = false) String cardType,
                           @RequestParam(required = false) String cardExpiry,
                           Model model) {
        var account = new com.petstore.customer.client.CustomerDtos.AccountDto(
                givenName, familyName, email, null, street1, null, city, state, zipCode, country);
        var card = (cardNumber == null || cardNumber.isBlank()) ? null
                : new com.petstore.customer.client.CustomerDtos.CardDto(cardNumber, cardType, cardExpiry);
        var request = new com.petstore.customer.client.CustomerDtos.RegisterRequest(
                userName, password, account, card);
        try {
            customerClient.register(request);
            log.info("Registered {} via customer-service", userName);
        } catch (org.springframework.web.client.HttpClientErrorException.Conflict e) {
            model.addAttribute("error", "That username is already taken.");
            return "register";
        } catch (org.springframework.web.client.HttpClientErrorException.BadRequest e) {
            model.addAttribute("error", "Invalid registration details.");
            return "register";
        } catch (org.springframework.web.client.RestClientException e) {
            model.addAttribute("error", "Registration service unavailable, please try again.");
            return "register";
        }
        return "redirect:/login?registered";
    }

    // ---- Checkout (HTML page; customer read via customer-service) ----

    @GetMapping("/checkout")
    public String checkoutPage(Authentication auth, Model model) {
        model.addAttribute("userId", auth.getName());
        model.addAttribute("customer", fetchCustomer(auth).orElse(null));
        model.addAttribute("items", cart.getItems());
        model.addAttribute("subtotal", cart.getSubTotal());
        return "checkout";
    }

    @PostMapping("/checkout")
    public String placeOrder(Authentication auth, Model model) {
        String userId = auth.getName();
        String email = fetchCustomer(auth)
                .map(c -> c.account() == null ? null : (String) c.account().get("email"))
                .orElse(userId + "@petstore.com");
        try {
            OrderService.OrderPlaced placed = orders.checkout(userId, email);
            model.addAttribute("orderId", placed.orderId());
            model.addAttribute("total", placed.total());
            model.addAttribute("status", "SUBMITTED");   // status now owned by warehouse-service
            return "order_complete";
        } catch (EmptyCartException e) {
            model.addAttribute("error", "Your cart is empty.");
            model.addAttribute("items", java.util.List.of());
            return "checkout";
        }
    }

    /** Fetch the signed-in customer from customer-service using the session JWT. */
    private java.util.Optional<com.petstore.customer.client.CustomerDtos.CustomerView> fetchCustomer(Authentication auth) {
        Object token = auth.getCredentials();   // the JWT set by CustomerServiceAuthProvider
        if (token == null) {
            return java.util.Optional.empty();
        }
        // customer-service is keyed by the stable userId (from the token), not the username.
        String userId = auth.getDetails() instanceof String uid ? uid : auth.getName();
        try {
            return customerClient.getCustomer(userId, token.toString());
        } catch (org.springframework.web.client.RestClientException e) {
            log.warn("could not fetch customer {} from customer-service: {}", userId, e.getMessage());
            return java.util.Optional.empty();
        }
    }
}
