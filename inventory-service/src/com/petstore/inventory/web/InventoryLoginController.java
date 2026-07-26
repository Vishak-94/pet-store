package com.petstore.inventory.web;

import com.petstore.auth.client.AuthClient;
import com.petstore.auth.client.AuthJwtFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Duration;
import java.util.Optional;

/**
 * Supplier staff login for the inventory UI. Delegates credential checking to
 * auth-service via the {@link AuthClient}; on success the returned RS256 token is
 * dropped into a 'jwt' cookie the verify-only AuthJwtFilter reads. inventory-
 * service holds no credentials of its own.
 */
@Controller
public class InventoryLoginController {

    /** Cookie path scoping the JWT to the whole app. */
    private static final String COOKIE_PATH = "/";
    /** Thymeleaf view + redirect targets for the login flow. */
    private static final String VIEW_LOGIN = "login";
    private static final String REDIRECT_INVENTORY = "redirect:/inventory";
    private static final String REDIRECT_LOGGED_OUT = "redirect:/inventory/login?loggedout";
    /** Model attribute + message shown when credentials are rejected. */
    private static final String ATTR_ERROR = "error";
    private static final String MSG_INVALID_CREDENTIALS = "Invalid credentials";
    /** SameSite policy on the jwt cookie — Strict blocks the cross-site send that enabled CSRF. */
    private static final String SAME_SITE_STRICT = "Strict";

    private final AuthClient auth;

    /**
     * Whether to mark the jwt cookie {@code Secure} (HTTPS-only). Config-gated and default
     * {@code false} so login works over plain HTTP in the local demo; set {@code cookie.secure=true}
     * in a real HTTPS deployment. {@code SameSite=Strict} already blocks the cross-site send.
     */
    private final boolean cookieSecure;

    public InventoryLoginController(AuthClient auth,
                                    @Value("${cookie.secure:false}") boolean cookieSecure) {
        this.auth = auth;
        this.cookieSecure = cookieSecure;
    }

    @GetMapping("/")
    public String home() {
        return REDIRECT_INVENTORY;
    }

    @GetMapping("/inventory/login")
    public String loginPage() {
        return VIEW_LOGIN;
    }

    @PostMapping("/inventory/login")
    public String doLogin(@RequestParam String username, @RequestParam String password,
                          HttpServletResponse response, Model model) {
        Optional<AuthClient.LoginResult> result = auth.login(username, password);
        if (result.isEmpty()) {
            model.addAttribute(ATTR_ERROR, MSG_INVALID_CREDENTIALS);
            return VIEW_LOGIN;
        }
        // Drop the RS256 token in the same 'jwt' cookie the verify-only AuthJwtFilter reads.
        // ResponseCookie so we can set SameSite=Strict (HttpOnly-cookie CSRF hardening) — the
        // servlet Cookie API has no SameSite setter. Secure is config-gated (false for local HTTP).
        response.addHeader(HttpHeaders.SET_COOKIE, jwtCookie(result.get().token(), null).toString());
        return REDIRECT_INVENTORY;
    }

    @PostMapping("/inventory/logout")
    public String logout(HttpServletResponse response) {
        // Same attributes as the login cookie (so the browser matches + replaces it) but zero max-age.
        response.addHeader(HttpHeaders.SET_COOKIE, jwtCookie("", Duration.ZERO).toString());
        return REDIRECT_LOGGED_OUT;
    }

    /** Build the jwt cookie with consistent hardening flags; {@code maxAge} null = session cookie. */
    private ResponseCookie jwtCookie(String value, Duration maxAge) {
        ResponseCookie.ResponseCookieBuilder b = ResponseCookie.from(AuthJwtFilter.JWT_COOKIE, value)
                .path(COOKIE_PATH)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite(SAME_SITE_STRICT);
        if (maxAge != null) {
            b.maxAge(maxAge);
        }
        return b.build();
    }
}
