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
 */
@Configuration
public class HttpClientConfig {

    @Bean
    CustomerServiceClient customerServiceClient(ServiceEndpoints endpoints) {
        String baseUrl = endpoints.getCustomer().getBaseUrl();
        return (baseUrl == null || baseUrl.isBlank())
                ? new CustomerServiceClient()          // SDK default localhost:8081
                : new CustomerServiceClient(baseUrl);
    }

    @Bean
    CatalogServiceClient catalogServiceClient(ServiceEndpoints endpoints) {
        String baseUrl = endpoints.getCatalog().getBaseUrl();
        return (baseUrl == null || baseUrl.isBlank())
                ? new CatalogServiceClient()           // SDK default localhost:8083
                : new CatalogServiceClient(baseUrl);
    }

    // NOTE: no cart client bean — cart runs IN-PROCESS via the embeddable cart
    // library (see CartConfig), not as a remote HTTP service.

    @Bean
    AuthClient authClient(@Value("${services.auth.base-url:http://localhost:8086}") String baseUrl) {
        return new AuthClient(baseUrl);   // login delegated to auth-service (central IdP)
    }
}
