package com.petstore.config;

import com.petstore.auth.client.AuthClient;
import com.petstore.catalog.client.CatalogServiceClient;
import com.petstore.customer.client.CustomerServiceClient;
import com.petstore.opc.client.OrderProcessingClient;
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
    private static final String CB_ORDER_PROCESSING = "order-processing-service";

    /** Dev fallback base URLs when the {@code services.*.base-url} property is unset. */
    private static final String DEFAULT_CUSTOMER_BASE_URL = "http://localhost:8081";
    private static final String DEFAULT_CATALOG_BASE_URL = "http://localhost:8083";
    private static final String DEFAULT_AUTH_BASE_URL = "http://localhost:8086";
    private static final String DEFAULT_ORDER_PROCESSING_BASE_URL = "http://localhost:8088";

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

    @Bean
    OrderProcessingClient orderProcessingClient(ServiceEndpoints endpoints) {
        String baseUrl = endpoints.getOrderProcessing().getBaseUrl();
        String url = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_ORDER_PROCESSING_BASE_URL : baseUrl;
        // Same resilience posture as the other SDKs: circuit-breaker + bounded retry. Checkout intake
        // is a POST (non-idempotent at the transport layer, but idempotent by orderId server-side), so
        // the breaker's fail-fast is what matters here — a hard failure surfaces as a clean 503 to the
        // shopper (OrderService maps the RestClientException), never a hung checkout thread.
        return new OrderProcessingClient(ResilientRestClient.forService(CB_ORDER_PROCESSING, url));
    }

    // NOTE: no cart client bean — cart runs IN-PROCESS via the embeddable cart
    // library (see CartConfig), not as a remote HTTP service.

    @Bean
    AuthClient authClient(@Value("${services.auth.base-url:" + DEFAULT_AUTH_BASE_URL + "}") String baseUrl) {
        // login delegated to auth-service (central IdP)
        return new AuthClient(ResilientRestClient.forService(CB_AUTH, baseUrl));
    }
}
