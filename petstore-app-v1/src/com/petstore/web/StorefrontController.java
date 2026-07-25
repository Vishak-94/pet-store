package com.petstore.web;

import com.petstore.cart.service.CartService;
import com.petstore.customer.client.CustomerServiceClient;
import com.petstore.order.security.OrderKeyCipher;
import com.petstore.order.service.EmptyCartException;
import com.petstore.order.service.IdempotencyKeyStore;
import com.petstore.order.service.OrderService;
import com.petstore.order.web.CheckoutForm;
import com.petstore.order.web.ContactInfoForm;
import com.petstore.order.web.MissingFormDataException;
import com.petstore.security.AuthenticatedUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * HTML storefront <b>checkout</b> controller. Holds NO customer business logic — the
 * customer read DELEGATES to the customer-service microservice via
 * {@link CustomerServiceClient}. Checkout uses the cart + order services (which remain
 * in the monolith) and the customer's email fetched from customer-service. Account
 * sign-up lives in {@link RegistrationController}.
 */
@Controller
public class StorefrontController {

    private static final Logger log = LoggerFactory.getLogger(StorefrontController.class);

    /** Thymeleaf view names for the checkout flow. */
    private static final String VIEW_CHECKOUT = "checkout";
    private static final String VIEW_ORDER_COMPLETE = "order_complete";

    /** Model attribute keys consumed by the checkout / order-complete templates. */
    private static final String ATTR_USER_ID = "userId";
    private static final String ATTR_CUSTOMER = "customer";
    private static final String ATTR_ITEMS = "items";
    private static final String ATTR_SUBTOTAL = "subtotal";
    private static final String ATTR_ORDER_ID = "orderId";
    private static final String ATTR_TOTAL = "total";
    private static final String ATTR_STATUS = "status";
    private static final String ATTR_CART_COUNT = "cartCount";
    private static final String ATTR_ERROR = "error";

    /** Customer-account map key holding the email address (customer-service payload). */
    private static final String ACCOUNT_EMAIL = "email";
    /** Fallback email domain when the customer profile has none. */
    private static final String FALLBACK_EMAIL_DOMAIN = "@petstore.com";

    /** Order status shown on the result page — real status is owned by warehouse-service. */
    private static final String STATUS_SUBMITTED = "SUBMITTED";

    /** User-facing messages surfaced on the checkout page. */
    private static final String MSG_DUPLICATE_SUBMIT =
            "This order was already submitted. Please review your cart before ordering again.";
    private static final String MSG_CART_EMPTY = "Your cart is empty.";

    private final CustomerServiceClient customerClient;
    private final CartService cart;
    private final OrderService orders;
    private final IdempotencyKeyStore keyStore;
    private final OrderKeyCipher orderKeyCipher;

    public StorefrontController(CustomerServiceClient customerClient, CartService cart,
                                OrderService orders, IdempotencyKeyStore keyStore,
                                OrderKeyCipher orderKeyCipher) {
        this.customerClient = customerClient;
        this.cart = cart;
        this.orders = orders;
        this.keyStore = keyStore;
        this.orderKeyCipher = orderKeyCipher;
    }

    // ---- Checkout (HTML page; customer read via customer-service) ----

    /** Render the checkout page (order summary + the customer's saved address). */
    @GetMapping("/checkout")
    public String checkoutPage(Authentication auth, Model model) {
        return checkoutModel(auth, model);
    }

    /** Populates the checkout view model (summary + saved address). Shared by the GET page and
     *  the POST re-render on a validation error. The idempotency token is fetched by the page's
     *  JS from {@code POST /pre-checkout} and placed in the hidden {@code orderKey} field. */
    private String checkoutModel(Authentication auth, Model model) {
        model.addAttribute(ATTR_USER_ID, auth.getName());
        model.addAttribute(ATTR_CUSTOMER, fetchCustomer(auth).orElse(null));
        model.addAttribute(ATTR_ITEMS, cart.getItems());
        model.addAttribute(ATTR_SUBTOTAL, cart.getSubTotal());
        return VIEW_CHECKOUT;
    }

    /**
     * Place the order from the HTML checkout form. Resolves identity + email from the session
     * (never request params), then enforces exactly-once submission via the encrypted
     * {@code orderKey} reservation — a refresh/double-click/replay carries an already-consumed
     * key and re-renders the page with a duplicate-submit message instead of publishing again.
     * On success validates both addresses (H7), publishes the PurchaseOrderEvent, and shows the
     * order-complete page; a validation failure re-renders checkout with the error.
     */
    @PostMapping("/checkout")
    public String placeOrder(Authentication auth,
                             @org.springframework.web.bind.annotation.ModelAttribute CheckoutForm form,
                             Model model) {
        String userId = auth.getName();
        // Reservation is keyed by the stable customer userId (same key /pre-checkout used).
        String customerId = AuthenticatedUser.userId(auth);
        String email = fetchCustomer(auth)
                .map(c -> c.account() == null ? null : (String) c.account().get(ACCOUNT_EMAIL))
                .orElse(userId + FALLBACK_EMAIL_DOMAIN);

        // Idempotency: decrypt the hidden token and consume the customer's reservation exactly
        // once. A refresh / double-click / replay carries the same (already-consumed) key, so
        // decrypt+consume fails and we DON'T publish a second order. The plaintext is the
        // server-minted order id; the OPC order_id primary key is the correctness backstop.
        String orderId = orderKeyCipher.decrypt(form.getOrderKey()).orElse(null);
        if (orderId == null || !keyStore.consumeIfMatches(customerId, orderId)) {
            log.info("Duplicate/invalid checkout submit for customer {} — not re-publishing", customerId);
            model.addAttribute(ATTR_ERROR, MSG_DUPLICATE_SUBMIT);
            return checkoutModel(auth, model);
        }

        try {
            // Legacy OrderHTMLAction validated both ship-to and bill-to before ordering.
            ContactInfoForm.requireValid(form.getShipTo(), form.getBillTo());
            OrderService.OrderPlaced placed = orders.checkout(orderId, userId, email,
                    form.getShipTo().toContactInfo(), form.getBillTo().toContactInfo());
            model.addAttribute(ATTR_ORDER_ID, placed.orderId());
            model.addAttribute(ATTR_TOTAL, placed.total());
            model.addAttribute(ATTR_STATUS, STATUS_SUBMITTED);   // status now owned by warehouse-service
            // checkout emptied the cart; refresh the badge (the cartCount @ModelAttribute
            // was resolved before this handler ran, so it still holds the pre-checkout count).
            model.addAttribute(ATTR_CART_COUNT, cart.getCount());
            return VIEW_ORDER_COMPLETE;
        } catch (MissingFormDataException e) {
            // Validation failed → the order was NOT placed and the reservation is already
            // consumed; the page's JS re-reserves a fresh token on re-render for the retry.
            model.addAttribute(ATTR_ERROR, e.getMessage());
            return checkoutModel(auth, model);
        } catch (EmptyCartException e) {
            model.addAttribute(ATTR_ERROR, MSG_CART_EMPTY);
            model.addAttribute(ATTR_ITEMS, java.util.List.of());
            return VIEW_CHECKOUT;
        }
    }

    /** Fetch the signed-in customer from customer-service using the session JWT. */
    private java.util.Optional<com.petstore.customer.client.CustomerDtos.CustomerView> fetchCustomer(Authentication auth) {
        Object token = auth.getCredentials();   // the JWT set by CustomerServiceAuthProvider
        if (token == null) {
            return java.util.Optional.empty();
        }
        // customer-service is keyed by the stable userId (from the token), not the username.
        String userId = AuthenticatedUser.userId(auth);
        try {
            return customerClient.getCustomer(userId, token.toString());
        } catch (org.springframework.web.client.RestClientException e) {
            log.warn("could not fetch customer {} from customer-service: {}", userId, e.getMessage());
            return java.util.Optional.empty();
        }
    }
}
