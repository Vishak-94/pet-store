package com.petstore.customer.client;

import com.petstore.customer.client.CustomerDtos.AccountDto;
import com.petstore.customer.client.CustomerDtos.AuthResult;
import com.petstore.customer.client.CustomerDtos.CardDto;
import com.petstore.customer.client.CustomerDtos.CustomerView;
import com.petstore.customer.client.CustomerDtos.ProfileDto;
import com.petstore.customer.client.CustomerDtos.RegisterRequest;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

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

    private final RestClient http;

    /** Use the default base URL ({@code http://localhost:8081}). */
    public CustomerServiceClient() {
        this(CustomerServiceEndpoints.DEFAULT_BASE_URL);
    }

    /** Use a specific base URL (host/port per environment). */
    public CustomerServiceClient(String baseUrl) {
        this.http = RestClient.builder().baseUrl(baseUrl).build();
    }

    /** Advanced: supply a preconfigured RestClient (e.g. with interceptors/TLS). */
    public CustomerServiceClient(RestClient restClient) {
        this.http = restClient;
    }

    // ---- auth ----

    /** Authenticate; empty on bad credentials (401). 'admin' → ADMIN role. */
    @SuppressWarnings("unchecked")
    public Optional<AuthResult> login(String userName, String password) {
        try {
            Map<String, Object> resp = http.post().uri(CustomerServiceEndpoints.LOGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("userName", userName, "password", password))
                    .retrieve().body(Map.class);
            if (resp == null || resp.get("token") == null) {
                return Optional.empty();
            }
            List<String> roles = "admin".equals(userName) ? List.of("ADMIN") : List.of("USER");
            return Optional.of(new AuthResult(
                    (String) resp.get("token"), (String) resp.get("customerId"), roles));
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
                    .header("Authorization", bearer(bearerToken))
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
        return "Bearer " + token;
    }
}
