package com.petstore.auth.client;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Thin client that delegates a login to auth-service ({@code POST /auth/login})
 * and returns the issued token. Verifier services use this so their login pages
 * don't re-implement the HTTP call; the token is then dropped into a {@code jwt}
 * cookie the {@link AuthJwtFilter} reads.
 *
 * <p>Base URL is a constructor arg (env-specific); the path is the contract.
 */
public class AuthClient {

    /** The auth-service endpoints (the contract). */
    public static final String LOGIN = "/auth/login";
    public static final String ACCOUNTS = "/auth/accounts";
    public static final String DEFAULT_BASE_URL = "http://localhost:8086";

    /**
     * JSON field names on the login/provision wire contract. Shared with the
     * auth-service controllers ({@code AuthController}/{@code AccountController}) — kept
     * as constants (contract literals) so client and server can't disagree on a key name.
     */
    public static final String FIELD_USER_NAME = "userName";
    public static final String FIELD_PASSWORD = "password";
    public static final String FIELD_ROLE = "role";
    public static final String FIELD_USER_ID = "userId";
    public static final String FIELD_TOKEN = "token";
    public static final String FIELD_ROLES = "roles";

    /** Bounded timeouts so a hung/slow auth-service can't block a login thread indefinitely. */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final RestClient http;

    public AuthClient() {
        this(DEFAULT_BASE_URL);
    }

    public AuthClient(String baseUrl) {
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(timeoutFactory()).build();
    }

    /**
     * Advanced: supply a preconfigured {@link RestClient} (e.g. with resilience
     * interceptors / TLS / custom timeouts). The caller owns the base URL + factory.
     */
    public AuthClient(RestClient restClient) {
        this.http = restClient;
    }

    /**
     * A request factory with bounded connect/read timeouts. Without these the default
     * factory waits forever, so one unresponsive auth-service would tie up every caller
     * thread and cascade into the caller's own outage.
     */
    private static ClientHttpRequestFactory timeoutFactory() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        f.setReadTimeout((int) READ_TIMEOUT.toMillis());
        return f;
    }

    /** The result of a successful login. */
    public record LoginResult(String token, String userId, java.util.List<String> roles) {
    }

    /**
     * Provision a credential in the central store (used at registration/onboarding) via
     * {@code POST /auth/accounts}. Returns the stable userId the caller should store as a
     * reference.
     *
     * <p>Example:
     * <pre>{@code
     * String userId = new AuthClient().provision("jane", "s3cret", "USER");
     * // userId -> "6f1c...-uuid", stored by the caller as the profile's account reference
     * }</pre>
     *
     * @param userName desired login name (1–25 chars, no {@code %}/{@code *})
     * @param password plaintext password (1–25 chars); hashed server-side, never stored here
     * @param role     requested role; only {@code USER} is allowed without an ADMIN token
     * @return the stable userId, or null if the response body is absent
     * @throws org.springframework.web.client.HttpClientErrorException on 400 (invalid_request),
     *         409 (duplicate_account) or 403 (admin_required), so the caller can map the error
     */
    @SuppressWarnings("unchecked")
    public String provision(String userName, String password, String role) {
        Map<String, Object> resp = http.post().uri(ACCOUNTS)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(Map.of(FIELD_USER_NAME, userName, FIELD_PASSWORD, password, FIELD_ROLE, role))
                .retrieve().body(Map.class);
        return resp == null ? null : (String) resp.get(FIELD_USER_ID);
    }

    /**
     * Authenticate a user via {@code POST /auth/login} and return the issued token + identity.
     * Empty on bad credentials (401) — callers must not distinguish unknown-user from
     * bad-password (avoids user enumeration).
     *
     * <p>Example:
     * <pre>{@code
     * Optional<LoginResult> r = new AuthClient().login("j2ee", "j2ee");
     * r.ifPresent(res -> setCookie("jwt", res.token()));  // res.roles() -> ["USER"]
     * }</pre>
     *
     * @param userName login name
     * @param password plaintext password (sent over the wire; never logged)
     * @return a {@link LoginResult} ({@code token}, {@code userId}, {@code roles}) on success,
     *         or {@link Optional#empty()} on bad credentials
     */
    @SuppressWarnings("unchecked")
    public Optional<LoginResult> login(String userName, String password) {
        try {
            Map<String, Object> resp = http.post().uri(LOGIN)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(Map.of(FIELD_USER_NAME, userName, FIELD_PASSWORD, password))
                    .retrieve().body(Map.class);
            if (resp == null || resp.get(FIELD_TOKEN) == null) {
                return Optional.empty();
            }
            var roles = (java.util.List<String>) resp.getOrDefault(FIELD_ROLES, java.util.List.of());
            return Optional.of(new LoginResult(
                    (String) resp.get(FIELD_TOKEN), (String) resp.get(FIELD_USER_ID), roles));
        } catch (org.springframework.web.client.HttpClientErrorException.Unauthorized e) {
            return Optional.empty();
        }
    }
}
