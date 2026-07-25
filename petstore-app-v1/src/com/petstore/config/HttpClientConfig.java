package com.petstore.config;

import com.petstore.auth.client.AuthClient;
import com.petstore.catalog.client.CatalogServiceClient;
import com.petstore.customer.client.CustomerServiceClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the imported client SDKs as Spring beans, using base URLs from
 * {@link ServiceEndpoints} (config). Endpoint paths live inside each SDK (its
 * API contract); only the base URL is environment config here.
 *
 * <p>Each SDK is constructed over a {@link ResilientRestClient} — a circuit breaker
 * (fail fast when a downstream is unhealthy) plus GET-only bounded retry, on top of
 * the SDK's connect/read timeouts. This keeps resilience in the consuming service,
 * out of the thin SDK jars.
 */
@Configuration
public class HttpClientConfig {

    /** Resilience4j circuit-breaker / retry instance names (used in logs + metrics). */
    private static final String CB_CUSTOMER = "customer-service";
    private static final String CB_CATALOG = "catalog-service";
    private static final String CB_AUTH = "auth-service";

    /** Dev fallback base URLs when the {@code services.*.base-url} property is unset. */
    private static final String DEFAULT_CUSTOMER_BASE_URL = "http://localhost:8081";
    private static final String DEFAULT_CATALOG_BASE_URL = "http://localhost:8083";
    private static final String DEFAULT_AUTH_BASE_URL = "http://localhost:8086";

    @Bean
    CustomerServiceClient customerServiceClient(ServiceEndpoints endpoints) {
        String baseUrl = endpoints.getCustomer().getBaseUrl();
        String url = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_CUSTOMER_BASE_URL : baseUrl;
        return new CustomerServiceClient(ResilientRestClient.forService(CB_CUSTOMER, url));
    }

    @Bean
    CatalogServiceClient catalogServiceClient(ServiceEndpoints endpoints) {
        String baseUrl = endpoints.getCatalog().getBaseUrl();
        String url = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_CATALOG_BASE_URL : baseUrl;
        return new CatalogServiceClient(ResilientRestClient.forService(CB_CATALOG, url));
    }

    // NOTE: no cart client bean — cart runs IN-PROCESS via the embeddable cart
    // library (see CartConfig), not as a remote HTTP service.

    @Bean
    AuthClient authClient(@Value("${services.auth.base-url:" + DEFAULT_AUTH_BASE_URL + "}") String baseUrl) {
        // login delegated to auth-service (central IdP)
        return new AuthClient(ResilientRestClient.forService(CB_AUTH, baseUrl));
    }
}
