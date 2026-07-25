package com.petstore.order.web;

import com.petstore.order.security.OrderKeyCipher;
import com.petstore.order.service.IdempotencyKeyStore;
import com.petstore.security.AuthenticatedUser;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Checkout reservation endpoint. The checkout page calls {@code POST /pre-checkout} (via a
 * small fetch) before the shopper submits: this reserves a fresh server-minted order id for the
 * signed-in customer in the {@link IdempotencyKeyStore} and returns it <b>encrypted</b>. The UI
 * stores the encrypted value in the checkout form's hidden {@code orderId} field and echoes it
 * back on {@code POST /checkout}, where it is decrypted and consumed exactly once — making the
 * order submission idempotent against refresh / double-click.
 *
 * <p>ADMIN not involved; authenticated customers only (enforced in SecurityConfig). Keyed by the
 * stable customer userId so the reservation belongs to that customer.
 */
@RestController
public class PreCheckoutController {

    /** JSON key carrying the encrypted token — mirrors the checkout form's hidden {@code orderKey} field. */
    static final String KEY_ORDER_KEY = "orderKey";

    private final IdempotencyKeyStore keyStore;
    private final OrderKeyCipher cipher;

    public PreCheckoutController(IdempotencyKeyStore keyStore, OrderKeyCipher cipher) {
        this.keyStore = keyStore;
        this.cipher = cipher;
    }

    /**
     * Reserve a fresh server-minted order id for the signed-in customer and return it encrypted.
     * The UI parks it in the checkout form's hidden {@code orderKey} field; {@code POST /checkout}
     * decrypts and consumes it exactly once, making submission idempotent against refresh/double-click.
     */
    @PostMapping("/pre-checkout")
    public Map<String, String> reserve(Authentication auth) {
        // Key the reservation by the stable customer userId (Authentication.getDetails), not the
        // username — same key the rest of the storefront uses for the customer aggregate.
        String customerId = AuthenticatedUser.userId(auth);
        String orderId = keyStore.reserve(customerId);
        return Map.of(KEY_ORDER_KEY, cipher.encrypt(orderId));
    }
}
