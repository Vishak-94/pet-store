package com.petstore.order.web;

import com.petstore.order.security.OrderKeyCipher;
import com.petstore.order.service.EmptyCartException;
import com.petstore.order.service.IdempotencyKeyStore;
import com.petstore.order.service.OrderIntakeUnavailableException;
import com.petstore.order.service.OrderService;
import com.petstore.security.AuthenticatedUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * JSON checkout endpoint (API alternative to the HTML storefront checkout).
 * Order STATUS is no longer served here — it's owned by warehouse-service
 * ({@code GET /api/orders/{id}/status} on :8082).
 */
@RestController
public class CheckoutController {

    private static final Logger log = LoggerFactory.getLogger(CheckoutController.class);

    /** Fallback email domain for the API path (no customer profile lookup here). */
    private static final String FALLBACK_EMAIL_DOMAIN = "@petstore.com";

    /** JSON response body keys + the messages returned to the API caller. */
    private static final String KEY_ORDER_ID = "orderId";
    private static final String KEY_TOTAL = "total";
    private static final String KEY_NOTE = "note";
    private static final String KEY_ERROR = "error";
    private static final String NOTE_SUBMITTED = "submitted to order-processing-service for fulfilment";
    private static final String ERROR_CART_EMPTY = "cart_empty";
    /** Returned when the pre-checkout {@code orderKey} is missing / invalid / already consumed. */
    private static final String ERROR_INVALID_ORDER_KEY = "invalid_order_key";
    /** Returned (503) when order-processing-service can't be reached — the order was NOT placed; retry. */
    private static final String ERROR_INTAKE_UNAVAILABLE = "order_intake_unavailable";

    private final OrderService orderService;
    private final IdempotencyKeyStore keyStore;
    private final OrderKeyCipher orderKeyCipher;

    public CheckoutController(OrderService orderService, IdempotencyKeyStore keyStore,
                              OrderKeyCipher orderKeyCipher) {
        this.orderService = orderService;
        this.keyStore = keyStore;
        this.orderKeyCipher = orderKeyCipher;
    }

    /**
     * Place an order via the JSON API. Identity is taken from the verified session token (never
     * from request params — see the body comment). Like the HTML {@code POST /checkout}, this
     * requires the encrypted {@code orderKey} synchronizer token minted by {@code POST /pre-checkout}
     * and consumes it exactly once, so a stateless JSON caller can't be cross-site-driven or replayed
     * into placing duplicate orders (the HTML path already had this; parity gap E3). Then ship-to and
     * bill-to are validated (legacy H7 required-field set) and {@link OrderService#checkout} publishes
     * the PurchaseOrderEvent under the reserved order id and empties the cart. Returns 200
     * {@code {orderId, total, note}} on success; 400 {@code {error:"invalid_order_key"}} when the key is
     * missing/replayed; 400 {@code {error:"cart_empty"}} when the cart is empty.
     *
     * <pre>{@code
     * POST /api/checkout        (Bearer session JWT; identity taken from it, not the body)
     *   form: orderKey=<encrypted token from POST /pre-checkout>
     *         shipTo.familyName=Doe&shipTo.givenName=Jane&shipTo.streetName1=1+Main+St
     *         &shipTo.city=Palo+Alto&shipTo.state=CA&shipTo.zipCode=94301&shipTo.telephone=555-0100
     *         &billTo.familyName=...&billTo.givenName=...  (bill-to same H7 required set)
     *
     * 200 OK  {"orderId":"17...", "total":33.00,
     *          "note":"submitted to order-processing-service for fulfilment"}
     *
     * 400 Bad Request  {"error":"invalid_order_key"}   // missing / forged / replayed orderKey
     * 400 Bad Request  {"error":"cart_empty"}          // no resolvable items in the cart
     * }</pre>
     *
     * <p>A missing H7 required field throws MissingFormDataException (surfaced by the REST exception
     * handler); anonymous callers are rejected by SecurityConfig before reaching this handler.
     */
    @PostMapping("/api/checkout")
    public ResponseEntity<Map<String, Object>> checkout(Authentication auth,
            @ModelAttribute CheckoutForm form) {
        // Identity comes from the verified session token, never from request params — otherwise
        // any authenticated caller could place an order billed to an arbitrary userId/email
        // (identity spoofing). Mirrors StorefrontController.placeOrder: username is the token
        // subject, the stable customer userId is on getDetails(). This endpoint is already in
        // the authenticated() matcher, so auth is non-null here.
        String userId = auth.getName();
        String customerId = AuthenticatedUser.userId(auth);
        String email = userId + FALLBACK_EMAIL_DOMAIN;
        // The session JWT is the Authentication credential — proxied to OPC so it authorizes intake.
        String bearer = String.valueOf(auth.getCredentials());

        // Require + consume the pre-checkout reservation exactly once (same flow as the HTML path):
        // decrypt the hidden token to the server-minted order id and atomically match/consume it.
        // A missing / forged / already-consumed key is rejected before anything is published.
        String orderId = orderKeyCipher.decrypt(form.getOrderKey()).orElse(null);
        if (orderId == null || !keyStore.consumeIfMatches(customerId, orderId)) {
            log.info("Invalid/duplicate /api/checkout submit for customer {} — not publishing", customerId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of(KEY_ERROR, ERROR_INVALID_ORDER_KEY));
        }

        try {
            // Legacy OrderHTMLAction validated both ship-to and bill-to before ordering.
            ContactInfoForm.requireValid(form.getShipTo(), form.getBillTo());
            OrderService.OrderPlaced placed = orderService.checkout(bearer, orderId, userId, email,
                    form.getShipTo().toContactInfo(), form.getBillTo().toContactInfo());
            return ResponseEntity.ok(Map.of(
                    KEY_ORDER_ID, placed.orderId(),
                    KEY_TOTAL, placed.total(),
                    KEY_NOTE, NOTE_SUBMITTED));
        } catch (EmptyCartException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of(KEY_ERROR, ERROR_CART_EMPTY));
        } catch (OrderIntakeUnavailableException e) {
            // OPC unreachable — the order was NOT placed and the cart is intact; the shopper can retry.
            log.warn("Order intake unavailable for customer {} — order {} not placed", customerId, orderId, e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of(KEY_ERROR, ERROR_INTAKE_UNAVAILABLE));
        }
    }
}
