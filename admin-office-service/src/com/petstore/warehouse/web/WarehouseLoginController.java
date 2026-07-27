package com.petstore.warehouse.web;

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
 * Admin staff login for the warehouse UI. Delegates credential checking to
 * auth-service via the {@link AuthClient}; the returned RS256 token is dropped
 * into a 'jwt' cookie the verify-only AuthJwtFilter reads. warehouse-service holds
 * no credentials of its own.
 */
@Controller
public class WarehouseLoginController {

    /**
     * SERVICE-SPECIFIC jwt cookie name — {@code jwt-warehouse}, not the shared default {@code jwt}.
     * Cookies are keyed by host+path, NOT port, so on {@code localhost} every UI shares a cookie
     * namespace. A shared {@code jwt} name (even path-scoped) lets a sibling console's cookie — or a
     * stale one left by an older build — shadow ours, because the reader takes the FIRST cookie of a
     * given name (→ wrong-role 403 / apparent logout, e.g. approve/deny failing). A distinct name
     * makes collision structurally impossible: {@code WarehouseServiceApplication}'s
     * {@link AuthJwtFilter} is wired to read exactly this name, and any legacy {@code jwt} cookie
     * becomes inert (ignored) rather than needing to be cleared.
     */
    public static final String JWT_COOKIE = "jwt-warehouse";
    /**
     * Cookie path scoping the JWT to THIS service's UI routes only. Scoped to {@code /warehouse}
     * (defence in depth alongside the distinct {@link #JWT_COOKIE} name). All warehouse UI routes
     * live under {@code /warehouse/**}; the JSON {@code /api/**} surface reads the header instead.
     */
    private static final String COOKIE_PATH = "/warehouse";
    /** Legacy broad path a pre-fix {@code jwt}/{@code XSRF-TOKEN} cookie may sit on — evicted on login/logout. */
    private static final String LEGACY_COOKIE_PATH = "/";
    /** Legacy shared cookie names (pre per-service rename) — evicted on the legacy path for cleanup. */
    private static final String LEGACY_JWT_COOKIE = "jwt";
    private static final String LEGACY_CSRF_COOKIE = "XSRF-TOKEN";
    /** Thymeleaf view + redirect targets for the login flow. */
    private static final String VIEW_LOGIN = "login";
    private static final String REDIRECT_ORDERS = "redirect:/warehouse/orders";
    private static final String REDIRECT_LOGGED_OUT = "redirect:/warehouse/login?loggedout";
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

    public WarehouseLoginController(AuthClient auth,
                                    @Value("${cookie.secure:false}") boolean cookieSecure) {
        this.auth = auth;
        this.cookieSecure = cookieSecure;
    }

    /** Root redirect: {@code GET /} sends the browser to the approval console ({@code /warehouse/orders}). */
    @GetMapping("/")
    public String home() {
        return REDIRECT_ORDERS;
    }

    /** Render the staff login form ({@code GET /warehouse/login} &rarr; the {@code login} Thymeleaf view). */
    @GetMapping("/warehouse/login")
    public String loginPage() {
        return VIEW_LOGIN;
    }

    /**
     * Handle the staff login form POST. DELEGATES credential checking to auth-service via
     * {@link AuthClient#login}; on success drops the returned RS256 token into a hardened
     * {@code jwt} cookie and redirects to the console; on failure re-renders the form with an error.
     * warehouse-service holds no credentials of its own.
     *
     * <p>Example request (form-encoded):
     * <pre>{@code
     * POST /warehouse/login
     * Content-Type: application/x-www-form-urlencoded
     *
     * username=admin&password=secret
     * }</pre>
     *
     * <p>Example response on success:
     * <pre>{@code
     * HTTP/1.1 302 Found
     * Location: /warehouse/orders
     * Set-Cookie: jwt=<RS256 token>; Path=/; HttpOnly; SameSite=Strict
     * }</pre>
     *
     * <p>On invalid credentials: 200 re-rendering the {@code login} view with an {@code error}
     * model attribute ("Invalid credentials"); no cookie is set.
     */
    @PostMapping("/warehouse/login")
    public String doLogin(@RequestParam String username, @RequestParam String password,
                          HttpServletResponse response, Model model) {
        Optional<AuthClient.LoginResult> result = auth.login(username, password);
        if (result.isEmpty()) {
            model.addAttribute(ATTR_ERROR, MSG_INVALID_CREDENTIALS);
            return VIEW_LOGIN;
        }
        // Evict any legacy broad-path (Path=/) 'jwt' AND 'XSRF-TOKEN' cookies left by an older
        // build or another console before dropping ours. A Path=/ cookie is sent to /warehouse
        // too and BOTH the jwt reader (AuthJwtFilter) and the CSRF reader (CookieCsrfTokenRepository)
        // take the FIRST cookie of that name — so a stale wrong-role jwt shadows this token, and a
        // stale root-path XSRF-TOKEN shadows the /warehouse one, forging a CSRF mismatch. EITHER
        // surfaces as the same 403 → /warehouse/login?forbidden. Self-healing so returning demo
        // users need not clear cookies.
        evictLegacyRootCookies().forEach(c -> response.addHeader(HttpHeaders.SET_COOKIE, c.toString()));
        // Drop the RS256 token in the same 'jwt' cookie the verify-only AuthJwtFilter reads.
        // ResponseCookie so we can set SameSite=Strict (HttpOnly-cookie CSRF hardening) — the
        // servlet Cookie API has no SameSite setter. Secure is config-gated (false for local HTTP).
        response.addHeader(HttpHeaders.SET_COOKIE, jwtCookie(result.get().token(), null).toString());
        return REDIRECT_ORDERS;
    }

    /**
     * Log out: overwrite the {@code jwt} cookie with an empty, immediately-expiring cookie
     * (same attributes so the browser matches and replaces it), then redirect to the login
     * page with a {@code loggedout} marker.
     *
     * <p>Example request:
     * <pre>{@code
     * POST /warehouse/logout
     * Cookie: jwt=<admin JWT>
     * }</pre>
     *
     * <p>Example response:
     * <pre>{@code
     * HTTP/1.1 302 Found
     * Location: /warehouse/login?loggedout
     * Set-Cookie: jwt=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0
     * }</pre>
     */
    @PostMapping("/warehouse/logout")
    public String logout(HttpServletResponse response) {
        // Same attributes as the login cookie (so the browser matches + replaces it) but zero max-age.
        response.addHeader(HttpHeaders.SET_COOKIE, jwtCookie("", Duration.ZERO).toString());
        // Also evict any legacy Path=/ 'jwt' and 'XSRF-TOKEN' cookies so logout fully clears a pre-fix session.
        evictLegacyRootCookies().forEach(c -> response.addHeader(HttpHeaders.SET_COOKIE, c.toString()));
        return REDIRECT_LOGGED_OUT;
    }

    /** Build the jwt cookie with consistent hardening flags; {@code maxAge} null = session cookie. */
    private ResponseCookie jwtCookie(String value, Duration maxAge) {
        ResponseCookie.ResponseCookieBuilder b = ResponseCookie.from(JWT_COOKIE, value)
                .path(COOKIE_PATH)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite(SAME_SITE_STRICT);
        if (maxAge != null) {
            b.maxAge(maxAge);
        }
        return b.build();
    }

    /**
     * {@code Max-Age=0} deletions for the {@code jwt} and {@code XSRF-TOKEN} cookies on the legacy
     * broad {@code Path=/}. Older builds (and any service that set these on {@code /}) planted
     * root-path cookies the browser still sends to {@code /warehouse}; because both the jwt reader
     * ({@link AuthJwtFilter}) and the CSRF reader ({@code CookieCsrfTokenRepository}) take the FIRST
     * cookie of the given name, a stale value shadows ours — a wrong-role jwt, or a mismatched
     * XSRF-TOKEN that forges a CSRF failure. Both surface as {@code /warehouse/login?forbidden}.
     * Deleting them on login/logout makes the fix self-healing. Cookie attributes must mirror the
     * original for the browser to match on delete: the jwt cookie was HttpOnly, the CSRF cookie was
     * not (it must be readable by Thymeleaf), so they use different flags here.
     */
    private java.util.List<ResponseCookie> evictLegacyRootCookies() {
        ResponseCookie jwt = ResponseCookie.from(LEGACY_JWT_COOKIE, "")
                .path(LEGACY_COOKIE_PATH)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite(SAME_SITE_STRICT)
                .maxAge(Duration.ZERO)
                .build();
        // CookieCsrfTokenRepository.withHttpOnlyFalse() → the XSRF-TOKEN cookie is NOT HttpOnly and
        // has no SameSite set, so mirror that (httpOnly=false, no sameSite) for the delete to match.
        ResponseCookie csrf = ResponseCookie.from(LEGACY_CSRF_COOKIE, "")
                .path(LEGACY_COOKIE_PATH)
                .httpOnly(false)
                .secure(cookieSecure)
                .maxAge(Duration.ZERO)
                .build();
        return java.util.List.of(jwt, csrf);
    }
}
