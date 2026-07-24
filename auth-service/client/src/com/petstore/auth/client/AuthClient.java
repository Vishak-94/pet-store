package com.petstore.auth.client;

import org.springframework.web.client.RestClient;

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

    private final RestClient http;

    public AuthClient() {
        this(DEFAULT_BASE_URL);
    }

    public AuthClient(String baseUrl) {
        this.http = RestClient.builder().baseUrl(baseUrl).build();
    }

    /** The result of a successful login. */
    public record LoginResult(String token, String userId, java.util.List<String> roles) {
    }

    /**
     * Provision a credential in the central store (used at registration/onboarding).
     * Returns the stable userId the caller should store as a reference. Throws
     * {@link org.springframework.web.client.HttpClientErrorException} on 409 (duplicate)
     * / 400, so the caller can map registration errors.
     */
    @SuppressWarnings("unchecked")
    public String provision(String userName, String password, String role) {
        Map<String, Object> resp = http.post().uri(ACCOUNTS)
                .header("Content-Type", "application/json")
                .body(Map.of("userName", userName, "password", password, "role", role))
                .retrieve().body(Map.class);
        return resp == null ? null : (String) resp.get("userId");
    }

    /** Authenticate; empty on bad credentials (401). */
    @SuppressWarnings("unchecked")
    public Optional<LoginResult> login(String userName, String password) {
        try {
            Map<String, Object> resp = http.post().uri(LOGIN)
                    .header("Content-Type", "application/json")
                    .body(Map.of("userName", userName, "password", password))
                    .retrieve().body(Map.class);
            if (resp == null || resp.get("token") == null) {
                return Optional.empty();
            }
            var roles = (java.util.List<String>) resp.getOrDefault("roles", java.util.List.of());
            return Optional.of(new LoginResult(
                    (String) resp.get("token"), (String) resp.get("userId"), roles));
        } catch (org.springframework.web.client.HttpClientErrorException.Unauthorized e) {
            return Optional.empty();
        }
    }
}
