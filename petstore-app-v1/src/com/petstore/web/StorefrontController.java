package com.petstore.web;

import com.petstore.cart.service.CartService;
import com.petstore.customer.client.CustomerServiceClient;
import com.petstore.order.service.EmptyCartException;
import com.petstore.order.service.OrderService;
import com.petstore.order.web.CheckoutForm;
import com.petstore.order.web.ContactInfoForm;
import com.petstore.order.web.MissingFormDataException;
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
    public String registerForm(@RequestParam(required = false) String returnUrl,
                               @org.springframework.web.bind.annotation.RequestHeader(value = "Referer", required = false) String referer,
                               Model model) {
        // Capture the originating screen so we can return there after account creation
        // (legacy returned the user to the pre-signon URL). ?returnUrl= wins over Referer;
        // the Referer is reduced to a local path so the round-trip stays same-app.
        model.addAttribute("returnUrl", returnUrl != null ? returnUrl : localPath(referer));
        return "register";
    }

    /** Reduces an (absolute) Referer to a same-app path+query, or null if not usable. */
    private String localPath(String referer) {
        if (referer == null) {
            return null;
        }
        try {
            java.net.URI uri = java.net.URI.create(referer);
            String path = uri.getRawPath();
            if (path == null || path.isBlank() || path.equals("/register-form")) {
                return null;   // don't loop back to the register page itself
            }
            return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
        } catch (IllegalArgumentException e) {
            return null;
        }
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
                           @RequestParam(required = false) String returnUrl,
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
            model.addAttribute("returnUrl", returnUrl);
            return "register";
        } catch (org.springframework.web.client.HttpClientErrorException.BadRequest e) {
            model.addAttribute("error", "Invalid registration details.");
            model.addAttribute("returnUrl", returnUrl);
            return "register";
        } catch (org.springframework.web.client.RestClientException e) {
            model.addAttribute("error", "Registration service unavailable, please try again.");
            model.addAttribute("returnUrl", returnUrl);
            return "register";
        }
        // Return to the originating screen if we captured one (legacy behaviour),
        // else fall back to the login page.
        if (returnUrl != null && isLocalUrl(returnUrl)) {
            return "redirect:" + returnUrl;
        }
        return "redirect:/login?registered";
    }

    /** Only allow same-app relative redirects (guards against open-redirect). */
    private static boolean isLocalUrl(String url) {
        return url.startsWith("/") && !url.startsWith("//");
    }

    // ---- Checkout (HTML page; customer read via customer-service) ----

    @GetMapping("/checkout")
    public String checkoutPage(Authentication auth, Model model) {
        return checkoutModel(auth, model);
    }

    /** Populates the checkout view model (summary + saved address). Shared by the
     *  GET page and the POST re-render on a validation error. */
    private String checkoutModel(Authentication auth, Model model) {
        model.addAttribute("userId", auth.getName());
        model.addAttribute("customer", fetchCustomer(auth).orElse(null));
        model.addAttribute("items", cart.getItems());
        model.addAttribute("subtotal", cart.getSubTotal());
        return "checkout";
    }

    @PostMapping("/checkout")
    public String placeOrder(Authentication auth,
                             @org.springframework.web.bind.annotation.ModelAttribute CheckoutForm form,
                             Model model) {
        String userId = auth.getName();
        String email = fetchCustomer(auth)
                .map(c -> c.account() == null ? null : (String) c.account().get("email"))
                .orElse(userId + "@petstore.com");
        try {
            // Legacy OrderHTMLAction validated both ship-to and bill-to before ordering.
            ContactInfoForm.requireValid(form.getShipTo(), form.getBillTo());
            OrderService.OrderPlaced placed = orders.checkout(userId, email,
                    form.getShipTo().toContactInfo(), form.getBillTo().toContactInfo());
            model.addAttribute("orderId", placed.orderId());
            model.addAttribute("total", placed.total());
            model.addAttribute("status", "SUBMITTED");   // status now owned by warehouse-service
            return "order_complete";
        } catch (MissingFormDataException e) {
            model.addAttribute("error", e.getMessage());
            return checkoutModel(auth, model);
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
