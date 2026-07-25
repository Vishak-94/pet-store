package com.petstore.customer.client;

import com.petstore.customer.client.CustomerDtos.AccountDto;
import com.petstore.customer.client.CustomerDtos.AuthResult;
import com.petstore.customer.client.CustomerDtos.CardDto;
import com.petstore.customer.client.CustomerDtos.CustomerView;
import com.petstore.customer.client.CustomerDtos.ProfileDto;
import com.petstore.customer.client.CustomerDtos.RegisterRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Importable client SDK for the customer-service microservice.
 *
 * <p>Owns the API contract: endpoint paths ({@link CustomerServiceEndpoints}),
 * operations, and DTOs ({@link CustomerDtos}). Consumers (e.g. the monolith)
 * just {@code new CustomerServiceClient(baseUrl)} — or use the default base URL —
 * and call methods; no URLs or JSON shapes leak into caller code.
 *
 * <p>Base URL is a constructor arg (environment-specific); endpoint paths are
 * hardcoded constants (the contract).
 */
public class CustomerServiceClient {

    /** Bounded timeouts so a hung/slow customer-service can't block caller threads indefinitely. */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    /** Bearer scheme prefix for the {@code Authorization} header (RFC 6750). */
    private static final String BEARER_PREFIX = "Bearer ";
    /** Roles derived from the login; the reserved {@code admin} user maps to ADMIN, else USER. */
    private static final String ADMIN_USER_NAME = "admin";
    private static final String ROLE_ADMIN = "ADMIN";
    private static final String ROLE_USER = "USER";

    private final RestClient http;

    /** Use the default base URL ({@code http://localhost:8081}). */
    public CustomerServiceClient() {
        this(CustomerServiceEndpoints.DEFAULT_BASE_URL);
    }

    /** Use a specific base URL (host/port per environment). */
    public CustomerServiceClient(String baseUrl) {
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(timeoutFactory()).build();
    }

    /** Advanced: supply a preconfigured RestClient (e.g. with interceptors/TLS/timeouts). */
    public CustomerServiceClient(RestClient restClient) {
        this.http = restClient;
    }

    /**
     * A request factory with bounded connect/read timeouts. Without these the default
     * factory waits forever, so one unresponsive customer-service would tie up every caller
     * thread (login, registration, account pages) and cascade into the caller's own outage.
     */
    private static ClientHttpRequestFactory timeoutFactory() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        f.setReadTimeout((int) READ_TIMEOUT.toMillis());
        return f;
    }

    // ---- auth ----

    /** Authenticate; empty on bad credentials (401). 'admin' → ADMIN role. */
    @SuppressWarnings("unchecked")
    public Optional<AuthResult> login(String userName, String password) {
        try {
            Map<String, Object> resp = http.post().uri(CustomerServiceEndpoints.LOGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(CustomerServiceEndpoints.FIELD_USER_NAME, userName,
                            CustomerServiceEndpoints.FIELD_PASSWORD, password))
                    .retrieve().body(Map.class);
            if (resp == null || resp.get(CustomerServiceEndpoints.FIELD_TOKEN) == null) {
                return Optional.empty();
            }
            List<String> roles = ADMIN_USER_NAME.equals(userName) ? List.of(ROLE_ADMIN) : List.of(ROLE_USER);
            return Optional.of(new AuthResult(
                    (String) resp.get(CustomerServiceEndpoints.FIELD_TOKEN),
                    (String) resp.get(CustomerServiceEndpoints.FIELD_CUSTOMER_ID), roles));
        } catch (HttpClientErrorException.Unauthorized e) {
            return Optional.empty();
        }
    }

    // ---- registration ----

    /** Register a customer. True on 2xx; throws HttpClientErrorException on 400/409. */
    public boolean register(RegisterRequest request) {
        var resp = http.post().uri(CustomerServiceEndpoints.REGISTER)
                .contentType(MediaType.APPLICATION_JSON).body(request)
                .retrieve().toBodilessEntity();
        return resp.getStatusCode().is2xxSuccessful();
    }

    // ---- reads / updates (bearer-token protected) ----

    public Optional<CustomerView> getCustomer(String id, String bearerToken) {
        try {
            return Optional.ofNullable(http.get().uri(CustomerServiceEndpoints.CUSTOMER, id)
                    .header(HttpHeaders.AUTHORIZATION, bearer(bearerToken))
                    .retrieve().body(CustomerView.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    public CustomerView updateAccount(String id, AccountDto account, String bearerToken) {
        return http.put().uri(CustomerServiceEndpoints.ACCOUNT, id)
                .header("Authorization", bearer(bearerToken))
                .contentType(MediaType.APPLICATION_JSON).body(account)
                .retrieve().body(CustomerView.class);
    }

    public CustomerView updateProfile(String id, ProfileDto profile, String bearerToken) {
        return http.put().uri(CustomerServiceEndpoints.PROFILE, id)
                .header("Authorization", bearer(bearerToken))
                .contentType(MediaType.APPLICATION_JSON).body(profile)
                .retrieve().body(CustomerView.class);
    }

    public CustomerView updateCard(String id, CardDto card, String bearerToken) {
        return http.put().uri(CustomerServiceEndpoints.CARD, id)
                .header("Authorization", bearer(bearerToken))
                .contentType(MediaType.APPLICATION_JSON).body(card)
                .retrieve().body(CustomerView.class);
    }

    private static String bearer(String token) {
        return BEARER_PREFIX + token;
    }
}
